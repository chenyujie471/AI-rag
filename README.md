# AI-Rag-search

方案 A 的落地骨架:**自研登录 + 中间网关代理**。

FastGPT 侧保持不动(两个知识库、两个工作流应用各自发布),本服务负责:

```
浏览器 ──登录页──> 本服务(JWT 鉴权 + 额度/限流网关)──> FastGPT 应用 API ──> 知识库
   │                    │
   │                    ├─ 问答页 /faq.html          (AI 知识问答)
   │                    └─ 问答页 /after-sale.html    (售后问答)
   │
   └─> MySQL:sys_user / ai_question_record / after_sale_question_record / usage_log
```

核心点:**每人独立的调用次数**在网关层控制。FastGPT 原生额度是"分享链接级"的,无法区分用户,
所以多用户场景必须由上层按用户维度计数,这里就是这么做的。

---

## 1. 快速启动

### 1.1 准备数据库

```sql
CREATE DATABASE IF NOT EXISTS ai_rag DEFAULT CHARACTER SET utf8mb4;
```

> 默认 JDBC URL 带了 `createDatabaseIfNotExist=true`,用 root 账号时会自动建库。
> 生产环境建议手动建库并去掉这个参数。

四张表由 `src/main/resources/schema.sql` 在**每次启动时幂等创建**(`CREATE TABLE IF NOT EXISTS`),
不需要手工执行。

> **升级已有部署时注意**:`CREATE TABLE IF NOT EXISTS` 只会创建**还不存在**的表,对已经存在的表
> 一列都不会加。管理后台给 `sys_user` 加了三列(`role`、`daily_limit_faq`、`daily_limit_after_sale`),
> 这些由 `config/SchemaMigrator` 在启动时自动补齐(**需要数据库账号有 `ALTER` 权限**),
> 详见 §3.2。跑过一次之后就不用管了。

### 1.2 改连接信息

`src/main/resources/application.yaml` 里的 `spring.datasource`,
或者用环境变量覆盖:

```bash
DB_URL="jdbc:mysql://127.0.0.1:3306/rag_search?useSSL=false&serverTimezone=Asia/Shanghai"
DB_USERNAME=root
DB_PASSWORD=123456
```

### 1.3 启动

```bash
./mvnw spring-boot:run          # Linux / macOS
mvnw.cmd spring-boot:run        # Windows
```

打开 <http://localhost:8100>,用默认账号登录:

| 账号 | 密码 |
| --- | --- |
| `admin` | `admin123` |

首次启动会自动创建该账号(`DefaultAdminInitializer`),日志里会有 WARN 提示。
**正式上线请把 `app.auth.default-admin.enabled` 改为 `false`,或登录后立刻改密码。**

**但在配好 FastGPT 应用之前,问问题是会直接报错的** —— 网关不会返回任何模拟内容,而是返回 502
并在消息里写清缺的是哪一项。见 §2.3。

---

## 2. 后面要配置的三件事

### 2.1 登录后的落地页:我们自己的两个问答页

登录成功后跳的是 **`/faq.html`**(AI 知识问答),页面顶栏可以直接切到 **`/after-sale.html`**(售后问答)。

两个页面都是本服务里的页面,问答请求走我们自己的网关,所以**配额、每日上限、内容记录、
用量记录天然全部生效** —— 不需要 Nginx,也不需要 FastGPT 的分享链接。

| 页面 | 路径 | 链路 | 内容写到哪 |
| --- | --- | --- | --- |
| AI 知识问答 | `/faq.html` | `faq` | `ai_question_record` |
| 售后问答 | `/after-sale.html` | `after-sale` | `after_sale_question_record` |

页面形态照 FastGPT 分享页那套做:居中窄列 + 欢迎屏(标题 / 说明 / 猜你想问)+ 左右分栏气泡 +
圆角输入框,回答**流式逐字输出**,带复制按钮和右侧历史抽屉。两个页面共用
`assets/chat.js` 与 `assets/chat.css`,差异只有页面底部那段内联配置。

#### 想改欢迎语或"猜你想问"?

改对应页面底部那段配置就行,不用动 JS:

```html
<script>
    window.AIRAG_PAGE = {
        channel: 'faq',                       // 决定调 /api/chat/{channel}、写哪张表
        icon:    'sparkles',                  // 欢迎屏图标(sprite 里的 symbol 名)
        label:   'AI 知识问答',
        desc:    '基于平台知识库回答使用、配置与功能类问题,回答会给出依据。',
        samples: ['这个平台怎么使用?', '支持哪些功能?', '数据多久同步一次?']
    };
</script>
```

> `channel` 只认 `faq` 和 `after-sale` 两个值 —— 它决定后端调哪个 FastGPT 应用、扣哪条链路的额度、
> 写哪张内容表。想加第三条链路要同时改后端枚举和 `application.yaml`,不是只加个页面就行。

#### 想换落地页?

登录接口把目标地址放在响应的 `redirectUrl` 字段里,前端不做硬编码,改配置即可:

```yaml
app:
  auth:
    redirect-url: "${LOGIN_REDIRECT_URL:/faq.html}"
```

#### 我不想要 FastGPT 的分享页了,那套代码还留着吗?

留着,但**默认不启用、也不影响上面两个页面**。`app.share`(iframe 伪装页)与
`app.share-proxy`(反代拦截)是为"想直接给用户看 FastGPT 原版界面"准备的备用方案,
完整的取舍和部署步骤见 **§9 反代部署**。一句话概括两者的区别:

| | 模式 A:不装 Nginx | 模式 B:Nginx 反代 |
| --- | --- | --- |
| `/chat/share` 由谁提供 | 本服务的 iframe 伪装页 | FastGPT 原版分享页 |
| 页面形态 | 我们的工具栏 + 内嵌 iframe | FastGPT 原版界面 |
| **配额 / 每日上限 / 记账** | ❌ **完全不生效**(浏览器直连 FastGPT) | ✅ **全部生效** |
| 需要改的配置 | 无 | `app.share-proxy.enabled: true` + 装 Nginx |

**两者都不是默认路径** —— 默认路径就是上面那两个页面,它们没有这些限制。

**备用方案 A 的定位是"看得见"层面的隐藏,不是安全边界,也不限额:**

| | 说明 |
| --- | --- |
| ✅ 地址栏 | 始终是我们的地址,看不到 `202.140.142.70:4000` |
| ✅ 分享链接 | 不在任何对外 URL 里,只写在服务端配置和 iframe 的 `src` 中 |
| ⚠️ 开发者工具 | F12 仍能看到 iframe 的真实 `src` |
| ❌ 额度 / 记账 | 对话是浏览器**直连 FastGPT**,请求根本不经过我们 |

打开该页面时会自动探测一次目标页的 `X-Frame-Options` / `Content-Security-Policy`:
如果 FastGPT 禁止被嵌入,页面会明确告诉你原因并给一个"在新标签页打开"的按钮,
而不是留一片白屏。探测结果缓存 5 分钟,离线环境可用
`app.share.probe-frame-options: false` 关掉。

**备用方案 B 才是让分享页也限额的那个**,完整部署步骤见 **§9 反代部署**。要点是:
把整个 FastGPT 搬到我们自己的地址后面,并把对话路径路由到本服务 —— 请求落在
"必经之路"上了,配额、每日上限、记账才有地方发生。

### 2.2 调用次数

**现在控量的是一个东西:每条链路各自、每人每天能问几次。** 「每账号每日总量」默认是
**不限**的 —— 它只是两条链路相加的统计数字,不是限制;终身总额度(`quota_total`)也已经退役。

| | 存在哪 | 用完怎么办 |
| --- | --- | --- |
| **每条链路的每人上限**(主限制) | `sys_user.daily_limit_faq / daily_limit_after_sale`,为 `NULL` 则用全局配置(默认 `20`) | **北京时间 0 点自动恢复** |
| **每账号每日总量**(默认不限) | `sys_user.daily_limit_total`,为 `NULL` 则用全局默认 `app.quota.daily-limit.per-user`(默认 `-1`) | 设了才有限制,第二天 0 点恢复 |

默认行为是「**每条链路 20 次,合起来最多 40 次**」。想给某个账号额外压一个"两条链路合计"
的天花板(比如总共 30 次),才去管理后台填「每日总量」。

**「每账号每日总量」可以在管理后台按人改,改完立刻生效:** `/admin.html` → 用户那一行的「操作」
→「每日上限」→ 填「每日总量」。用户页面上的「今日总计」和「今日剩余」下次刷新就会变成新值,
不用重启。留「跟随全局」就退回 `per-user` 那个默认值。

两层每日上限是「**两个都要满足**」:链路那一层是主限制,总量那一层默认不限、
只在你想额外压一个合计上限时才起作用。谁先到就按谁的话术拒绝。

> ⚠️ **`daily_limit_total` 是新加的列。** `schema.sql` 里全是 `CREATE TABLE IF NOT EXISTS`,
> 对已存在的表什么都不做,所以老库要靠 `config/SchemaMigrator` 在启动时补列
> (**数据库账号要有 `ALTER` 权限**)。没补上时管理后台那个「每日总量」会保存失败。

> ⚠️ **老库里残留的 `quota_total` 会被启动时自动抹成 -1。** 这是 `user/LifetimeQuotaCleanup`
> 干的:`default-total` 只决定新账号建出来是多少,对已经在库里的行没影响,那些带着
> `quota_total = 100` 的老账号照样会在 `QuotaService` 那里拦人。它只在
> `app.quota.default-total` 为**负数**(即"已声明不用终身额度")时才动手,并且会打一条 WARN
> 说明改了几个账号。想真的恢复终身额度,把 `default-total` 配成正数即可,它就不动了。

**改这些值不用再写 SQL 了 —— 用管理后台 `/admin.html`。** 下面这些 SQL 只是留给脚本化运维参考。

**全局配置**(改完重启生效):

```yaml
app:
  quota:
    enabled: true
    default-total: -1         # 已退役,保持 -1。配成正数会重新启用终身额度(并停掉上面的清理)
    deduct-mode: REQUEST      # REQUEST=一次提问扣 1 次;TOKEN=按 token 折算
    tokens-per-unit: 1000     # TOKEN 模式下多少 token 折算 1 次
    refund-on-failure: true   # 上游失败自动退还
    daily-limit:
      per-user: -1            # 每账号每日总量(两条链路合计)。默认 -1=不限 —— 它只是统计数字
      faq: 20                 # ★ 主限制:每条链路各自的每人每日上限
      after-sale: 20          #    所以默认是"每条 20 次,合起来最多 40 次"
      zone: Asia/Shanghai     # "自然日"按哪个时区算,即"0 点"是哪个 0 点
```

> ⚠️ **`zone` 必须和数据库写 `created_at` 用的时区一致。** 记录表的 `created_at` 是
> MySQL 的 `DEFAULT CURRENT_TIMESTAMP` 生成的,也就是<b>数据库服务器的本地时间</b>。
> 日切边界则按 `zone` 在 Java 侧算。两者不一致时(比如库在 UTC、这里写 `Asia/Shanghai`),
> 统计窗口就会整体错位。默认 `Asia/Shanghai` 适配"库和 JVM 都在北京时间"这个最常见的情况;
> 如果库跑在 UTC,把这里也改成 `UTC`。

**为什么不用 JVM 默认时区**:容器里 JVM 常常是 UTC,那样"0 点重置"会变成北京时间早上 8 点。
所以这里是显式写死的,不受 `TZ` 环境变量影响。

**同类操作的 SQL 写法**(管理后台做的事其实就是这些):

```sql
-- 给某人单独设每日总量:每天 50 次(两条链路合计)
UPDATE sys_user SET daily_limit_total = 50 WHERE username = 'zhangsan';

-- 清掉个人设置,回到跟随全局配置
UPDATE sys_user SET daily_limit_total = NULL WHERE username = 'zhangsan';

-- 给某人单独设每链路的上限:FAQ 每天 20 次、售后不限
UPDATE sys_user SET daily_limit_faq = 20, daily_limit_after_sale = -1 WHERE username = 'zhangsan';

-- 提一个人当管理员
UPDATE sys_user SET role = 'ADMIN' WHERE username = 'zhangsan';

-- 看所有人的用量
SELECT id, username, role, status, quota_used, quota_total,
       daily_limit_faq, daily_limit_after_sale,
       CASE WHEN quota_total < 0 THEN -1 ELSE quota_total - quota_used END AS remaining
FROM sys_user ORDER BY quota_used DESC;

-- 新增一个普通用户(密码哈希必须按下面两步生成,别填明文)
INSERT INTO sys_user (username, password_hash, nickname, role, status, quota_total, quota_used)
VALUES ('lisi', '<粘贴哈希>', '李四', 'USER', 1, 200, 0);
```

生成密码哈希(不依赖任何工具,直接跑项目里的类):

```bash
# 只生成哈希
java -cp target/classes com.example.airag.auth.PasswordEncoder 你的明文密码
# 输出: pbkdf2$120000$xxxxx$yyyyy

# 连账号一起给,会顺便打印一条可直接执行的 UPDATE —— 给库里已有的账号重设密码用这个
java -cp target/classes com.example.airag.auth.PasswordEncoder 新密码 lisi
```

> ⚠️ **手工建账号最容易踩的坑:密码必须是 `PasswordEncoder` 生成的哈希。**
> `password_hash` 里填明文、填 MySQL 的 `MD5()`/`SHA2()`、或者从别处复制一段哈希,
> 都会让登录永远失败 —— 因为校验时不做任何猜测,格式不对就直接判否,
> 用户端只会看到一句"账号或密码错误"。见下面的排障清单。

**手工建的账号登不进去?按这个顺序查**

| 现象 | 原因 | 怎么确认 | 怎么修 |
| --- | --- | --- | --- |
| 提示"账号或密码错误" | `password_hash` 不是 `pbkdf2$...` 格式 | **不用猜:启动时就会报**。`PasswordHashAuditor` 会把这些账号列出来,登录失败时也会打一条 WARN(都只报长度、不回显值,因为那里往往就是明文密码) | 用上面第二条命令生成哈希 + `UPDATE`;或去 `/admin.html` 用管理员「重置密码」 |
| 提示"账号已被禁用" | `status = 0` | `SELECT username, role, status FROM sys_user;` | `UPDATE sys_user SET status = 1 WHERE username = 'lisi';` |
| 提示"账号或密码错误",但哈希长度正常(80+ 字符) | 密码确实不符(大小写、空格、复制时带了换行) | 上面那条自检**不会**报这个账号(格式正常,只是密码不对) | 重设密码 |
| 登录成功但看不到「管理后台」 | `role` 不是 `ADMIN` | `SELECT username, role FROM sys_user;` | `UPDATE sys_user SET role = 'ADMIN' WHERE username = 'lisi';`(改完**立即生效**,重新登录都不用) |
| 提示"账号或密码错误",但库里根本没这行 | 改的不是应用连的库 | 确认连的是 `DB_URL` 指向的那个库(默认 `127.0.0.1:3306/rag_search`) | 到正确的库里建账号 |
| 启动时日志说补列失败 | 库账号没有 `ALTER` 权限 | 日志里有具体的 `ALTER TABLE` 语句 | 拿日志里的语句手工执行一次 |

> 注意区分 401 和 403:**401 = 账号或密码不对**(账号不存在也走这条),
> **403 = 账号被禁用**。所以收到 401 时不用去查 `status`。

> 改 `role` 是立即生效的,不需要重新登录 —— 角色**刻意没有写进 JWT**,每次请求都回查数据库。
> 这一点见 §7。

> **万一所有账号都进不去了**:启动时 `DefaultAdminInitializer` 会检查默认管理员账号的
> `password_hash` 是不是本系统能识别的格式。如果不是(比如那行也是手工插的),它会把这个账号
> **重置回配置里的初始密码**(`app.auth.default-admin.password`,默认 `admin123`)并确保它是
> 启用的管理员,日志里有 ERROR 说明。所以只要 `default-admin.enabled` 是 true,
> 你永远不会被彻底锁在门外 —— 进去之后到 `/admin.html` 给其它账号重置密码即可。
> **正式上线请把 `default-admin.enabled` 设为 false**,这条后门也就随之关闭。
>
> 注意它只在"哈希格式压根不可用"时动手,`password_hash` 格式正常但密码忘了的账号不会被重置,
> 那种情况走上面的 `UPDATE` 或后台「重置密码」。

**两种扣减模式的区别**

| 模式 | 计数方式 | 能否流式返回 | 说明 |
| --- | --- | --- | --- |
| `REQUEST`(默认) | 一次提问扣 1 次 | ✅ 支持 | 逻辑简单、体验最好,推荐先用这个 |
| `TOKEN` | 按 `total_tokens / tokens-per-unit` 向上取整 | ❌ 网关会强制关闭流式 | FastGPT 的流式响应不返回真实 token 消耗,只有非流式 + `detail=true` 才准 |

### 2.3 两个 FastGPT 应用(接上真实知识库)

两个知识库工作流各发布一次,拿到各自的 API Key 和 App ID,填到对应位置:

```yaml
app:
  gateway:
    enabled: true
    faq:                           # 平台 FAQ 应用
      api-url: "http://202.140.142.70:4000/api/v1/chat/completions"   # 默认已填好
      api-key: "fastgpt-xxxxxxxxxxxxxxxx"                             # ← 必填
      app-id: "你的FAQ应用ID"                                          # ← 见下方说明
    after-sale:                    # 售后知识库应用
      api-url: "http://202.140.142.70:4000/api/v1/chat/completions"   # 默认已填好
      api-key: "fastgpt-xxxxxxxxxxxxxxxx"                             # ← 必填
      app-id: "你的售后应用ID"
```

> **没有"模拟回复"这个兜底了。** 早期版本在 `api-key` 为空时会返回一段「【本地演示模式】…」的
> 假回复,本意是让前端先跑通,结果它让"配置没生效"看起来和"一切正常"一模一样 —— 页面能问、
> 有回答、额度也照扣,只是答的不是真内容。这个分支**已经删除**:现在配置不完整会直接返回
> **502**,并且消息里写明缺的是哪一项(是 `api-key 为空`、`api-url 是占位符`,还是别的),
> 同时日志里打一条 ERROR。**宁可报错,也不要静默降级。**

三个值分别从哪来:

| 配置项 | 从哪拿 |
| --- | --- |
| `api-url` | FastGPT 的 OpenAI 兼容接口,固定是 `<FastGPT 地址>/api/v1/chat/completions`。默认已指向 `202.140.142.70:4000` |
| `api-key` | FastGPT → **账号 → API 密钥**,或 **应用 → 发布渠道 → API 访问** |
| `app-id` | 打开应用详情页,看浏览器地址栏 URL 里的 `appId` |

> **`app-id` 可以留空** —— 如果你用的是「该应用自己的 API 密钥」,FastGPT 能从密钥推断出应用,
> 留空时请求体里干脆不带 `appId`(见 `FastGptClient`),照样能通。
> 但如果你用的是**账号级 API 密钥**,那就必须在 body 里指明是哪个应用,此时 `app-id` 必填。

也可以用环境变量:`FASTGPT_FAQ_URL` / `FASTGPT_FAQ_KEY` / `FASTGPT_FAQ_APP_ID`,
以及 `FASTGPT_AFTER_SALE_URL` / `FASTGPT_AFTER_SALE_KEY` / `FASTGPT_AFTER_SALE_APP_ID`。

**怎么确认真的接上了:**

1. **看启动日志里的「AI-Rag 启动自检」那段**(由 `StartupDiagnostics` 打印)。它会列出:
   - classpath 上那份 `application.yaml` 的**完整路径和最后修改时间**(用来判断读的是不是旧文件)
   - 实际生效的监听端口
   - 每个应用**实际生效**的 `api-url` / `appId` / `apiKey 前缀与长度`,以及各自的结论
   - 相关环境变量(`SERVER_PORT` / `FASTGPT_FAQ_KEY` / `SPRING_APPLICATION_JSON` …)

   > 这一步很关键。`application.yaml` **只在启动时读一次**,而静态页面是每次请求时解析的,
   > 所以"新页面能打开、配置却还是旧的"这种半新半旧的状态很容易出现。自检日志能一眼分清是
   > **进程没重启**、**读的是旧的 application.yaml**,还是**被环境变量覆盖了**(注意:环境变量
   > 即使被设成**空字符串**也会覆盖 yaml 里的值)。

2. 在 `/faq.html` 问一句 —— 回答是知识库的真实内容,而且**一个字一个字蹦出来**

3. 查库确认这次提问被记下来了:
   ```sql
   SELECT username, question, LEFT(answer, 40), status, cost_quota
     FROM ai_question_record ORDER BY id DESC LIMIT 3;
   ```
   如果 `question` 正常但 `answer` 为空,说明是流式内容的解析问题,不是配置问题。

> 对接的是 FastGPT 的 OpenAI 兼容接口
> `POST {apiUrl}`,`Authorization: Bearer <apiKey>`,body 里带 `appId` / `chatId` / `stream` / `messages`。
> 参考 [FastGPT Chat API 文档](https://doc.fastgpt.cn/en/openapi/chat)。

---

## 3. 数据库表

四张表,建表语句都在 `schema.sql`(`CREATE TABLE IF NOT EXISTS`,幂等,每次启动自动执行)。

**内容表** —— 两条知识库链路各一张,结构完全对称:

| 表名 | 用途 |
| --- | --- |
| `ai_question_record` | AI(平台 FAQ)提问记录 |
| `after_sale_question_record` | 售后提问记录 |

每条记录都会写:问题、回答、会话 ID、FastGPT App ID、三类 token 数、本次扣减次数、
状态(`SUCCESS` / `FAILED` / `REJECTED`)、失败原因、耗时。

`REJECTED` 是**被网关拦下来、压根没转发给 FastGPT** 的那些请求(额度用完 / 每日上限到 / 账号被禁用),
留着方便审计和排查"用户说用不了"的工单。

**账号使用账** —— `usage_log`,见 §3.1。

**账号表** —— `sys_user`:账号、密码哈希、角色、每人额度、**每人每日上限**。见 §3.2。

### 3.1 usage_log:账号使用记录(不含问答内容)

两条链路**共用这一张表**,靠 `channel` 列(`faq` / `after-sale`)区分。每次请求写一行,
被拦下来的也写:

| 列 | 说明 |
| --- | --- |
| `user_id` / `username` | 谁 |
| `channel` | 走哪条链路 |
| `session_id` / `app_id` | 会话与 FastGPT 应用 |
| `prompt_tokens` / `completion_tokens` / `total_tokens` | 上游返回的真实用量 |
| `cost_quota` | 本次扣了几次(被拦的记 0) |
| `status` / `error_msg` / `duration_ms` | 结果与耗时 |
| `client_ip` / `user_agent` | 从哪来的(有反代时优先取 `X-Forwarded-For` 第一段) |
| `created_at` | 什么时候 |

**刻意没有 question / answer 两列。** 内容和记账分开,是因为两者生命周期不同:
内容表(`answer` 是 MEDIUMTEXT)迟早要定期清理,而记账数据要长期留存 —— 混在一起就不敢删。
顺带也解决了"一条链路一张表"的问题:将来加第三条链路,`usage_log` 不用动表结构。

记账落库走的是和内容表一样的"失败不影响对话"策略(`UsageLogService.saveQuietly`),
记账写失败只记 error 日志,绝不会把用户的提问搞失败。

#### 那 FastGPT 内部的调用过程呢?

**不在采集范围内,而且本来就没采。** 具体是:

- 网关调用上游时传的是 **`detail: false`**(见 `FastGptClient`),FastGPT 因此不会返回
  `responseData`(节点流转、各模块耗时、中间变量);
- 客户端只解析两样东西:`choices[].delta.content`(回答文本)和 `usage`(token 数);
- 数据库里也没有任何存放工作流执行明细的列。

所以"用户问一句 → 工作流内部走了哪几个节点"这类信息只存在于 FastGPT 自己那边,我们这边零记录。

### 3.2 sys_user:账号、角色、每日上限

```sql
CREATE TABLE sys_user (
    ...
    role                   VARCHAR(16) NOT NULL DEFAULT 'USER',  -- ADMIN / USER
    status                 TINYINT     NOT NULL DEFAULT 1,       -- 1=启用 0=禁用
    quota_total            INT         NOT NULL DEFAULT -1,      -- 已退役,启动时会被抹成 -1
    quota_used             INT         NOT NULL DEFAULT 0,       -- 只是记账,不再参与拦截
    daily_limit_total      INT         DEFAULT NULL,             -- 每日总量(主限制):NULL=跟随全局,-1=不限
    daily_limit_faq        INT         DEFAULT NULL,             -- NULL=跟随全局,-1=不限
    daily_limit_after_sale INT         DEFAULT NULL,
    ...
);
```

次数限制现在只有一个维度:**每个账号每天能问几次**(北京时间 0 点重置)。

| 字段 | 含义 | 用完之后 |
| --- | --- | --- |
| `daily_limit_total` | **每账号每日总量**(两条链路合计)。为 `NULL` 时用全局默认 `app.quota.daily-limit.per-user` | 第二天 0 点自动恢复 |
| `daily_limit_faq` / `daily_limit_after_sale` | **每条链路单独的上限**(可选,默认不限),在总量之上再收紧 | 第二天 0 点自动恢复 |
| `quota_total` / `quota_used` | **已退役**的终身总额度。启动时由 `LifetimeQuotaCleanup` 抹成 `-1`,界面不再展示 | —— |

这三列都可以在管理后台按人改:「每日上限」弹窗里第一项是**每日总量**(主限制),
下面两项是每条链路的可选设置。保存是**整体覆盖**,所以三个字段每次一起提交,
漏传的会被当成 `NULL`(跟随全局)。

`daily_limit_*` 有三种取值语义,管理后台页面上对应"跟随全局 / 不限 / 自定义"三选一:

| 值 | 含义 |
| --- | --- |
| `NULL` | **跟随全局配置** —— `app.quota.daily-limit.faq` / `.after-sale` |
| `-1` | 这个人这条链路不限次数(但每账号每日总量仍然管着) |
| `>= 0` | 具体次数 |

> **老库必须补列。** `schema.sql` 里全是 `CREATE TABLE IF NOT EXISTS` —— 它对**已存在**的表
> 什么都不做,所以给 `sys_user` 加的三列(`role` / `daily_limit_faq` / `daily_limit_after_sale`)
> 不会自动出现在你已经建好的库上。
>
> 这件事由 `config/SchemaMigrator` 在启动时自动完成:用 JDBC 元数据查一遍,缺哪列补哪列,
> 每次启动都检查,幂等。**数据库账号需要有 `ALTER` 权限。** 如果没有,启动日志里会打出可手工
> 执行的 `ALTER TABLE` 语句,执行完再重启即可。
>
> 之所以不用 MySQL 的 `ADD COLUMN IF NOT EXISTS`(MariaDB 专有)或 `INFORMATION_SCHEMA` +
> `PREPARE` 的写法:测试跑的是 H2,那些写法在 H2 上跑不了,而这个迁移器两边都能用。

### 3.3 常用查询

```sql
-- 某人今天各链路发起过多少次(含被拦的)
SELECT channel,
       COUNT(*)                 AS total,
       SUM(status = 'SUCCESS')  AS ok,
       SUM(status = 'REJECTED') AS blocked,
       SUM(cost_quota)          AS quota_used
  FROM usage_log
 WHERE user_id = 1 AND created_at >= CURDATE()
 GROUP BY channel;

-- 最近 20 条账号使用记录
SELECT created_at, username, channel, status, cost_quota, total_tokens, client_ip
  FROM usage_log ORDER BY id DESC LIMIT 20;

-- 清理 90 天前的内容,记账数据保持不动
DELETE FROM ai_question_record         WHERE created_at < DATE_SUB(NOW(), INTERVAL 90 DAY);
DELETE FROM after_sale_question_record WHERE created_at < DATE_SUB(NOW(), INTERVAL 90 DAY);

-- 看看谁有个人每日上限覆盖(非 NULL 的),以及谁是管理员
SELECT id, username, role, status, quota_total, quota_used, daily_limit_faq, daily_limit_after_sale
  FROM sys_user ORDER BY id;
```

> `usage_log` 目前**只写不读**。查"谁在什么时候用了什么"请用 §4 的 `/api/logs`(自己的)
> 和 `/api/admin/logs`(管理后台看全部),它们查的是两张内容表(带问答正文),信息更全。

---

## 4. 接口一览

### 页面

| 路径 | 说明 |
| --- | --- |
| `/` | 登录页 |
| `/faq.html` | **AI 知识问答**(登录后默认落地页) |
| `/after-sale.html` | **售后问答** |
| `/logs.html` | **我的使用记录** —— 每个人看自己的调用日志 |
| `/profile.html` | **个人信息** —— 账号、额度、用量汇总(只读) |
| `/admin.html` | **管理后台** —— 只有管理员的界面上才会出现「管理后台」入口(左侧导航 + 顶栏);普通用户看不到。直接输地址也行,非管理员会被送回 `/faq.html` |
| `/chat.html` | 跳转到 `/faq.html`(旧链接兜底) |
| `/chat/share` | 可选的 FastGPT 分享页伪装页,见 §9 |

### 接口

**公共 / 每个登录用户**

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/api/auth/login` | 否 | 登录,返回 JWT + 用户信息(含 `admin` 标记)+ 剩余额度 + 跳转地址 |
| `POST` | `/api/auth/password` | **否** | 自助修改密码:核对 `oldPassword` 后写入 `newPassword`。登录页的入口用,见下方说明 |
| `GET` | `/api/auth/me` | 是 | 当前用户 + 剩余额度 + 各链路今日用量 |
| `POST` | `/api/auth/logout` | 是 | 登出(客户端丢弃令牌) |
| `GET` | `/api/quota/me` | 是 | 只查额度 |
| `POST` | `/api/chat/faq` | 是 | FAQ 链路对话,流式 SSE |
| `POST` | `/api/chat/after-sale` | 是 | 售后链路对话,流式 SSE |
| `GET` | `/api/records/faq` | 是 | 我的 FAQ 提问记录(分页 + 关键字) |
| `GET` | `/api/records/after-sale` | 是 | 我的售后提问记录 |
| `GET` | `/api/logs` | 是 | **我的**调用记录(两条链路合并,支持 type/status/keyword/start/end + 分页) |
| `GET` | `/api/logs/stats` | 是 | **我的**按链路、状态汇总 |
| `GET` | `/api/health` | 否 | 存活探测 |

`/api/logs` 与 `/api/logs/stats` 里的 `userId` 是**后端从登录态取的,接口根本不接受这个参数** ——
所以不存在"改个参数看别人记录"的可能。

`/api/auth/password` 是**唯一一个不需要令牌的写接口**,这是有意的:登录页上的「修改密码」入口
就是在"还没登录"的状态下用的。它靠**核对原密码**来证明身份,而不是靠令牌 —— 所以它和
`/api/auth/login` 是同一个性质的"猜密码入口",限流规则里单独给了 `limit: 10`。
拿它改别人的密码是不可能的(原密码对不上就一律 401),但它**不会**让已签发的旧令牌失效:
JWT 是无状态的,改完密码后旧的 2 小时令牌仍然有效,见 §8。

**管理后台**(全部要求 `role=ADMIN`,否则 403 + 错误码 `40303`)

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/admin/overview` | 概览:用户数 / 管理员数 / 已禁用数 + 今日与累计统计 |
| `GET` | `/api/admin/users` | 用户列表(`keyword` 搜账号或昵称,`role` 筛角色,分页) |
| `GET` | `/api/admin/users/{id}` | 单个用户 |
| `POST` | `/api/admin/users` | 新建用户 |
| `PUT` | `/api/admin/users/{id}` | 改昵称 / 角色 / 启用状态 / 总额度(`null` = 保持原值) |
| `PUT` | `/api/admin/users/{id}/daily-limit` | **改每日上限**(`total`=每日总量,`faq`/`afterSale`=每链路;**整体覆盖**:`null` = 跟随全局,`-1` = 不限) |
| `PUT` | `/api/admin/users/{id}/password` | 重置密码 |
| `POST` | `/api/admin/users/{id}/quota/reset` | 已用次数清零 |
| `DELETE` | `/api/admin/users/{id}` | 删除账号(问答记录与使用日志保留) |
| `GET` | `/api/admin/logs` | **全站**调用记录(`userId` 不传即全部) |
| `GET` | `/api/admin/logs/stats` | 全站按链路、状态汇总 |

除登录和健康检查,所有 `/api/**` 都要求 `Authorization: Bearer <token>`。
也接受 `?token=<token>`(给 EventSource 用)与 `airag_token` Cookie(给反代过来的分享页用)。

**为什么权限判断每次都回查数据库**:令牌是自包含的、签发后无法撤回。如果角色写进令牌,
管理员刚撤掉某人权限,对方凭旧令牌还能进后台直到令牌过期。所以 `AdminInterceptor` 按
`user_id` 回查一次 `sys_user` —— 代价是一次主键查询,换来"撤销立即生效"。顺带还能挡住
"账号被禁用后仍拿着未过期令牌访问"的情况。

**反代拦截的两个路径**(只在 `app.share-proxy.enabled=true` 且 Nginx 把它们路由过来时才会被命中;
它们是 FastGPT 的协议路径,不是我们发明的):

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/chat/completions` | 是(Cookie) | FastGPT 对话接口,校验配额后原样透传并记账 |
| `POST` | `/api/core/chat/completions` | 是(Cookie) | 同上(FastGPT 另一个常见端点) |

请求体原样转发给 FastGPT,只有 `chatId` 会被加上账号前缀。详见 §9。

---

## 5. 请求进入网关后的完整流程

**两个问答页(`/faq.html`、`/after-sale.html`)的路径** —— 也就是默认入口:

```
1. AuthInterceptor        解析 JWT -> 写入 UserContext(失败直接 401,不进 Controller)
2. RateLimitInterceptor   滑动窗口频率限制(已登录按用户、未登录按 IP)
3. DailyLimitService      占一个当日名额(超过每日上限直接拦,不扣总额度)
4. ChatService            查额度 -> 原子扣减;不足则落 REJECTED 记录后直接返回,不转发
5. FastGptClient          转发到对应 FastGPT 应用;流式时逐行原样透传给浏览器
6. RecordService          落库到内容表 + usage_log(落库失败只记日志,不影响用户拿到回答)
```

**FastGPT 原版分享页(反代模式)的路径** —— 前 3 步相同,之后分流:

```
4. ShareChatProxyController  占名额 -> 扣额度 -> 把请求体原样透传给 FastGPT 源站
5. (同上)                    响应原样回给浏览器,同时旁路读一眼回答增量与 usage 用于记账
6. RecordService             落库到内容表 + usage_log
```

**额度扣减是原子的**,靠一条带条件的 UPDATE 实现,没有加锁、天然并发安全:

```sql
UPDATE sys_user SET quota_used = quota_used + ?
 WHERE id = ? AND status = 1
   AND (quota_total < 0 OR quota_used + ? <= quota_total)
```

影响行数 = 1 才代表扣减成功。

### 额度 vs 频率限制

这是两件事,都在网关层,配置项分开:

| | 管什么 | 超了怎么办 | 配置 |
| --- | --- | --- | --- |
| **额度** | 总量,用一次少一次 | 提示"次数已用完",不转发 | `app.quota.*` |
| **频率限制** | 单位时间内的请求速度,防刷 | 返回 429 + `Retry-After` | `app.rate-limit.*` |

频率限制是按接口配的,改 yaml 即可:

```yaml
app:
  rate-limit:
    enabled: true
    window-seconds: 60      # 默认窗口
    default-limit: 60       # 默认窗口内上限,0 或负数 = 不限
    rules:
      - path: /api/chat/**  # 对话接口:每分钟 10 次
        limit: 10
      - path: /api/auth/login
        limit: 20
```

规则支持 `精确路径`、`/prefix/**`、`/prefix/*` 三种写法,越靠后的规则优先级越高。

---

## 6. 代码结构

```
src/main/java/com/example/airag/
├── common/      统一响应体、错误码、全局异常处理、JSON 输出工具
├── config/      AppProperties(所有业务配置)、WebConfig(拦截器/跨域)、SchedulingConfig
├── auth/        AuthCookie(HttpOnly 登录 Cookie)、PasswordEncoder(PBKDF2)、JwtService、拦截器、登录接口
├── user/        sys_user 读写、账号服务、默认管理员初始化、密码哈希格式自检(PasswordHashAuditor)
├── quota/       每人独立次数:原子扣减 / 退还 / 快照 + 每日渠道上限
├── ratelimit/   滑动窗口限流器(接口 + 内存实现,可换 Redis)
├── record/      两张提问记录表的读写 + 合并日志查询(LogRepository/LogController)
├── gateway/     FastGPT 代理客户端 + 额度拦截编排(ChatService)
├── usage/       账号使用账 usage_log(不含问答内容)+ 客户端 IP/UA 采集
├── admin/       管理后台:用户管理、全站记录与概览(AdminUserService/AdminUserController/AdminLogController)
└── share/       分享页相关:反代计费拦截、地址栏伪装页、可嵌入性探测

deploy/
└── nginx-airag.conf   反代配置(让限额在 FastGPT 原版页面上生效,见 §9)

src/main/resources/
├── application.yaml           所有可变配置都在这里
├── schema.sql                 四张表的建表语句
├── share/share-frame.html     伪装页模板(iframe 外框,占位符由后端替换)
└── static/
    ├── index.html             登录页(访问 / 即是)
    ├── faq.html               AI 知识问答页 ← 登录后的默认落地页
    ├── after-sale.html        售后问答页
    ├── logs.html              我的使用记录
    ├── profile.html           个人信息(账号、额度、用量汇总)
    ├── admin.html             管理后台(仅管理员)
    ├── chat.html              跳转到 /faq.html(旧链接兜底,已不是真实页面)
    ├── assets/
    │   ├── app.css            设计令牌 + 公共组件(所有页面共用)
    │   ├── layout.css         控制台骨架:顶栏 + 左侧导航 + 主内容区
    │   ├── console.css        日志表 / 筛选栏 / 汇总条 / 分页(logs 与 admin 共用)
    │   ├── login.css          登录页样式(自包含,不依赖 app.css)
    │   ├── chat.css           两个问答页的样式(共用)
    │   ├── admin.css          管理后台特有:概览卡 / Tab / 弹窗 / 三态每日上限编辑器
    │   ├── login-illustration.svg  登录页插画
    │   ├── theme.js           明暗主题切换
    │   ├── layout.js          顶栏与左侧导航注入 + 图标 sprite + window.AIRAG 公共方法
    │   ├── login.js           登录交互
    │   ├── chat.js            问答页逻辑:SSE 流式 + 前端打字机 + Markdown 渲染 + 历史抽屉
    │   ├── logs.js            我的使用记录逻辑
    │   ├── profile.js         个人信息页逻辑(只读:账号 / 额度 / 用量汇总)
    │   └── admin.js           管理后台逻辑
    └── vendor/                第三方库/字体,全部本地托管,内网可用
        ├── marked.min.js      Markdown 渲染
        ├── purify.min.js      DOMPurify,XSS 过滤
        └── fonts/             Inter woff2
```

### 前端说明

**没有构建步骤,不需要 npm。** 所有页面都是原生 HTML/CSS/JS,由 Spring Boot 直接作为静态资源提供,
改完刷新浏览器即可生效。

<a id="61"></a>

#### 前端打字机:为什么"逐字出现"不完全靠上游

链路本身是真流式的(`stream: true` + 网关逐行透传 SSE),但**上游不一定分片**:

| FastGPT 工作流节点 | 是否分片推送 |
| --- | --- |
| `AI 对话` | ✅ 真流式,一小段一小段推 |
| `指定回复` / `代码运行` | ❌ 在节点结束时把整段文字一次性吐下来 |

所以只靠透传的话,遇到第二种节点,页面上会"卡一下 → 整段突然出现"。

`chat.js` 因此把两件事拆开:

```
item.answer  ← 已经从上游收到的完整文本(持久化用的是它)
item.shown   ← 已经画到屏幕上的字符数
```

一个 40ms 的定时器把 `shown` 匀速往 `answer` 上追,每帧吐出 `积压 / 24` 个字符 ——
积压多就吐得快,所以最多落后约 1 秒,不会越拉越远。两种情况看起来都是平滑逐字输出。

想确认上游到底有没有分片,看日志这一行(`FastGptClient` 每次流式结束后打印):

```
流式转发完成: 上游分片 1 个, 首片 812 ms, 总耗时 830 ms, 回答 236 字
  —— 上游把整段一次性推下来了,页面上的逐字效果来自前端打字机,不是真流式
```

- `分片 > 3` → 上游真流式,打字机只是把节奏磨平
- `分片 = 1` → 上游不分片,**要真正的流式体验得去 FastGPT 改工作流**(把输出节点换成 `AI 对话`)

`prefers-reduced-motion: reduce` 时会一次性显示全文,不做动画。

用了哪些第三方东西:

| | 来源 | 引入方式 |
| --- | --- | --- |
| 图标 | [Lucide](https://lucide.dev) v1.48.0,ISC License | 以 SVG sprite **内联**在页面里(`<symbol>` + `<use>`) |
| 字体 | Inter(SIL OFL 1.1) | **本地托管**在 `/vendor/fonts`,内网可用;中文回落系统字体 |
| Markdown | marked + DOMPurify | 本地托管在 `/vendor/`,后者负责过滤渲染结果防 XSS |
| 其余样式与交互 | 自己写的 | 无依赖 |

> 图标 sprite 由 `assets/layout.js` 统一定义并注入,四个控制台页面(faq / after-sale / logs / profile)
> 共用一份 —— 加图标只需改 `layout.js` 里的 `ICONS` 一处。`assets/admin.js` 也从同一份 sprite 取图标。

**为什么不用 Tailwind / Element Plus 这类库?**

这个服务是给自建 FastGPT 做前置网关的,大概率部署在内网。CDN 引框架在内网会直接挂掉;
而走 npm 构建又多出一整套前端工程和构建产物。登录页只有一个表单,自研 CSS 反而更可控。
所以我只把真正提升观感的部分(Lucide 图标、Markdown 渲染)引进来了,而且全部本地托管 ——
字体、脚本都不依赖外网。

如果你确实想换成组件库风格(Element Plus / Ant Design),把 `login.css`、`chat.css`
换成对应 CDN 的样式与组件即可,`login.js` / `chat.js` 里的接口调用逻辑不用动。

**已实现的界面能力**

- 明暗主题:跟随系统 + 顶栏手动切换,选择存 localStorage;首帧内联脚本避免闪白
- 登录页:密码显隐切换、Caps Lock 提示、记住账号、字段级校验、错误抖动、按钮 loading 态
- 问答页:欢迎屏 + 猜你想问一键提问、**流式逐字输出**(带光标)、回答复制、失败原因就地显示
- 问答页:**前端打字机** —— 把"收到多少"和"画出多少"分开,上游即使一次性把整段回答丢下来,
  页面上也是平滑逐字出现的(见 §6.1)
- 问答页:顶栏切换两条链路、右侧历史抽屉(点一条回填到输入框)、剩余额度与今日用量实时刷新
- 问答页:切换页面时会话不丢(`sessionStorage` 按链路各存一份),"新对话"才清空
- 登录页:密码显隐、大写锁定提示、记住账号、失败抖动动画、logo 与标题同行
- 登录页:**自助修改密码**(输入账号 + 原密码 + 新密码两遍,前后端各校验一遍,
  成功后回到登录视图让你用新密码登一次)。已登录的人不会被自动跳转拦住 ——
  直接开 `/index.html#password` 就到这个表单
- 权限划分:**只有管理员**的界面上有「管理后台」入口(左侧导航 + 顶栏),普通用户看到的是
  纯问答 + 个人中心的界面。入口默认 `hidden`,拿到当前用户后才按 `user.admin` 决定露不露,
  所以普通用户不会看到它闪一下
- 使用记录页:筛选(链路/状态/关键字/时间范围)+ 分页,点一行展开完整问答
- 管理后台:概览卡片、用户表格(搜索/角色筛选/分页)、新建与编辑用户、启用禁用、
  重置密码、清零已用次数、**按人调每日上限**、全站使用记录查询(带账号列)
- 界面上**不显示 token 数**:`REQUEST` 扣减模式下 FastGPT 回的是占位的 token 计数,
  展示出来只会误导,所以使用日志与全站记录都不列 Tokens(数据库里仍然照常记录)
- 无障碍:标签关联、`aria-live`、`focus-visible` 焦点环、`prefers-reduced-motion` 降级
- 响应式:窄屏下登录页收起卖点区,问答页收起文字标签、历史抽屉自适应宽度


---

## 7. 安全相关的几个决定

- **密码**:PBKDF2WithHmacSHA256,12 万次迭代,每用户随机 16 字节盐,常量时间比较。
- **令牌**:HS256,纯 JDK 实现(HMAC + Base64URL),没有引入第三方 JWT 库。
  生产环境务必把 `JWT_SECRET` 换成随机长字符串(≥32 字节)。
- **会话隔离**:前端传上来的 `chatId` 会被强制加上 `u<用户ID>-<链路>-` 前缀,
  并过滤成只保留 `[A-Za-z0-9_-]`。否则伪造别人的 `chatId` 就能读到对方的会话上下文。
- **账号枚举**:账号不存在时也会跑一次哈希计算,避免通过响应耗时猜账号。
- **SQL**:所有查询都用参数绑定;两张表名只从 `RecordType` 枚举取,不接受外部输入。
- **角色不进令牌,每次回查数据库**。令牌自包含、签发后撤回不了;把角色写进去会导致
  管理员撤权之后对方凭旧令牌仍能进后台直到过期(默认 2 小时)。回查一次主键的代价换来
  "撤权立即生效",顺带能挡住"账号被禁用但令牌还没过期"。
- **越权防线在后端,不在前端**。前端按 `user.admin` 决定要不要显示「管理后台」入口
  (普通用户界面上根本没这一项),但这只是体验,不是安全措施;
  真正的校验是 `AdminInterceptor`(非管理员 403 + 错误码 `40303`)。
  同理 `/api/logs` 把 `userId` 钉死成登录用户,接口根本不接受这个参数。
- **自助改密码靠原密码证明身份,不靠令牌**。`/api/auth/password` 在拦截器里是放行的,
  因为登录页上的入口就是在登录之前用的。原密码对不上就 401,所以借它越权改别人的密码
  是不可能的;但它的性质等同于登录接口(都是"猜密码"入口),限流单独收紧到 `limit: 10`。
  **注意:改完密码不会让已签发的旧令牌失效**(JWT 无状态,默认 2 小时)。
- **管理后台不会把管理员锁在门外**。管理员不能降级/禁用/删除自己,所以"可用管理员数量"
  不可能被降到 0。万一真被外部改库改成 0 个,启动时 `DefaultAdminInitializer` 会把默认
  管理员账号重新提权并启用,日志里有明确提示。
- **删除用户不删业务数据**。只删 `sys_user` 那一行,问答记录与 `usage_log` 全部保留 ——
  那是审计数据,人走了"他上个月用了多少次"还得查得到。
- **管理后台的写操作没有单独的操作审计表**。目前只进应用日志。要追责到"哪个管理员改的",
  需要再加一张 `admin_audit_log`(见 §8)。

---

## 8. 已知限制 / 建议的下一步

1. **令牌无法立即吊销**。JWT 无状态,登出只是客户端丢弃令牌。
   如果需要"改密码后立刻失效",在 `JwtService` 外面套一层令牌黑名单(Redis 很合适)。
2. **限流器是单机的**。`InMemorySlidingWindowRateLimiter` 多实例部署时各算各的。
   已经抽出 `RateLimiter` 接口,加一个 Redis 实现替换 Bean 即可,调用方不用改。
3. **额度扣减是跨实例安全的**(走数据库原子更新),这点比限流器更稳。
4. `TOKEN` 模式下,超过余额的那一次会"超额放行"(上游已经返回了,不能反过来拒绝用户),
   只记 WARN 日志。要严格避免,可以改成预扣 + 结算两次扣。
5. ~~**没有后台管理页**~~ —— 已经有了,见 `/admin.html` 与 §4。**管理操作没有单独的审计表**
   仍是个缺口:谁在什么时候把谁的额度从 5 改成了 50,目前只留在应用日志里。
   要补的话加一张 `admin_audit_log(actor_id, actor_name, target_user_id, action, before_value,
   after_value, created_at)`,在 `AdminUserService` 的写方法里顺手写一行即可。
6. **没有单应用内分支路由**。现在是你方案里的"两个独立应用 + 两个 Tab"。
   如果要做"用户不用切页面"的版本,就在 `ChatService` 转发前加一个分类节点,
   判断走 `gateway.faq()` 还是 `gateway.afterSale()`,其余逻辑完全不用动。
7. **没跑过编译**。开发这个改动时当前环境里 `pwsh` 一直返回 `0xC0000142`(DLL 初始化失败),
   所有 shell 命令都执行不了,所以 `mvn compile` / 单元测试都没能实际运行过。
   代码是照着 Spring Boot 4.1 的 API 写的,但**请务必先本地编译一次**:

   ```bash
   mvnw.cmd clean test
   ```

   测试分几层:`AuthCoreTest` 是纯单元测试(不需要数据库);
   `GatewayFlowIntegrationTest` 用 H2(MySQL 兼容模式)真实起服务,**不需要本机 MySQL**;
   `AdminApiIntegrationTest` 验证管理后台的权限边界、用户增删改查,以及"按人覆盖每日上限"
   这条核心链路(同样只需 H2);
   `ChangePasswordIntegrationTest` 验证自助改密码:不带令牌也能改(这是设计前提)、
   原密码错/新密码太短/新旧相同/字段为空各自被拒,以及改完新密码能登、旧密码不能登;
   `PerUserDailyLimitIntegrationTest` 验证"每个账号每天的总次数":两条链路**合计**计数、
   按账号独立(不是全站共享)、失败/被拦截不占名额、日切边界正好落在配置时区的 0 点、
   以及管理员按人改「每日总量」后**立刻生效**(改大能继续问,改 0 立刻拦住,改 -1 放开);
   `ShareProxyIntegrationTest` 用 JDK 自带的 `HttpServer` 起一个假 FastGPT,验证反代拦截的
   记账/限额/Cookie 鉴权/不泄露凭证(同样不需要本机 MySQL,也不需要真的 FastGPT);
   `AiRagApplicationTests` 需要真的 MySQL,默认 `@Disabled`,联调时去掉注解即可。
8. **模式 A 的伪装页绕过了额度网关**(重要)。不装 Nginx 时,`/chat/share` 只是把 FastGPT
   分享页用 iframe 嵌进来,用户在里面的每一句对话都是浏览器**直连 FastGPT**:
   `app.quota`(总额度)、`app.quota.daily-limit`(每日上限)、`app.rate-limit` 全部不生效,
   也不会写进 `ai_question_record` / `after_sale_question_record`。
   而且它只是"地址栏里看不见",F12 仍能看到 iframe 的真实地址。
   → **默认入口(`/faq.html` / `/after-sale.html`)没有这个问题**,它们本来就调用我们的网关;
   → 如果一定要用 FastGPT 原版分享页,装 §9 的 Nginx 反代即可全部解决(模式 B)。
9. **反代拦截是"只读旁路",不保证拿到回答全文**。流式场景下我们从 SSE 里顺带收集
   `choices[].delta.content`;如果用户中途关页面、或上游异常中断,可能只收集到一部分,
   记录里的 `answer` 会偏短 —— 但**用了多少次、扣了多少次是准的**,因为那部分在转发前
   就结算了。另外 FastGPT 传 `detail=false` 时 `usage` 常常是占位值(文档示例里是 1/1/1),
   所以 `TOKEN` 扣减模式不可靠,建议用默认的 `REQUEST`(一次提问扣 1 次)。
10. **反代拦截的路径要按实际情况确认**。`ShareChatProxyController` 现在映射
    `/api/v1/chat/completions` 与 `/api/core/chat/completions` 两个 FastGPT 常见端点。
    若 F12 发现分享页实际打的是别的路径,把那个路径同时加进:
    `ShareChatProxyController` 的 `@PostMapping` 和 `deploy/nginx-airag.conf` 里对应的
    `location`。**漏配的后果是"该路径不限流也不记账"**,而不是报错,所以务必按 §9.4 验证一次。

## 9. 反代部署(可选:让限额在 FastGPT 原版页面上生效)

> **这一节现在是可选的。** 默认入口(`/faq.html` / `/after-sale.html`)是我们自己的页面,
> 问答走 `/api/chat/*`,配额、每日上限、记账**本来就是生效的**,不需要 Nginx。
>
> 只有当你想**直接把 FastGPT 原版分享页给用户看**、同时又要按账号限流时,才需要这一节。

这是"登录后跳 FastGPT 原版分享页"与"按账号限流 + 记录"能同时成立的**唯一**办法:让对话
请求落在必经之路上。完整配置文件在 **`deploy/nginx-airag.conf`**,这里说清原理和验证方法。

### 9.1 为什么必须这样

FastGPT 的分享页是它自己的前端,它在浏览器里直接请求 FastGPT 的接口 —— 我们的网关
**看不见这些请求**,自然也就无法限流、无法记账。这不是代码问题,是浏览器的工作方式决定的。

值得注意的是:FastGPT 确实有一个官方机制叫「分享链接身份验证」(在免登录窗口配置「身份验证」
根地址,FastGPT 会在 `shareAuth/init`、`shareAuth/start`、`shareAuth/finish` 三个时机回调你的
服务),它几乎就是为这个需求量身定做的,而且 `finish` 还会回传**真实 token 消耗**。
**但该功能仅商业版提供**,社区版用不了 —— 所以社区版只能走反代。

### 9.2 拓扑

```
浏览器 ──> Nginx (deploy/nginx-airag.conf, 对外 :8080)
              │
              ├─ /api/auth|quota|records|chat|health ─> 我们的 Spring Boot :8100
              ├─ /assets/ /vendor/ /faq.html /after-sale.html / 登录页 ─> 我们的 Spring Boot :8100
              ├─ /api/v1/chat/completions            ─┐
              ├─ /api/core/chat/completions          ─┴> 我们的 Spring Boot :8100  ★ 拦截计费
              └─ 其余全部(分享页、/_next、FastGPT 自己的 API) ─> FastGPT :4000
```

关键点:**只需要本机能出站访问 FastGPT,FastGPT 不需要能反向访问我们**。所以
"我在本机开发、FastGPT 在公网"这种场景照样能用(而 §9.1 提到的 `shareAuth` 就用不了,
因为那需要 FastGPT 主动回调你)。

### 9.3 部署步骤

1. 拷贝 `deploy/nginx-airag.conf`,改三个地址:`airag_gateway` 的端口、
   `fastgpt_upstream` 的地址、`listen` 的对外端口。
2. 放进 Nginx 的 `conf.d/`(`nginx -t && nginx -s reload`)。
3. 打开 `application.yaml`,把反代拦截打开:

   ```yaml
   app:
     share-proxy:
       enabled: true                                        # ← 改成 true
       upstream-base: "http://202.140.142.70:4000"          # FastGPT 源站
       channel: faq                                         # 归到哪条链路(faq / after-sale)
   ```

4. 重启本服务。此时访问 Nginx 的对外端口,`/chat/share` 出来的就是 FastGPT 原版分享页,
   但对话请求会经过我们的网关。

### 9.4 怎么验证真的生效了(务必做)

反代最容易出的问题是**路径没对上** —— 那会导致"页面能用,但完全没限流",而且不报错。
按这三步确认:

1. **看接口**:在分享页里发一条消息,按 F12 → Network,记下那条流式请求的**完整路径**
   (通常是 `/api/v1/chat/completions` 或 `/api/core/chat/completions`)。
2. **看落库**:每发一条消息,数据库应该多一行:

   ```sql
   SELECT id, username, channel, status, total_tokens, cost_quota, created_at
     FROM usage_log ORDER BY id DESC LIMIT 5;
   ```

   **只要这里在增长,就说明限流与记账都在生效。** 不增长 = 路径没拦到。
3. **看拦截**:把每账号每日总量设成 1,连发两条,第二条应该被挡:

   ```yaml
   app:
     quota:
       daily-limit:
         per-user: 1     # 每个账号每天 1 次(两条链路合计)
   ```

   第二条会返回 403 + 错误码 `40302`。注意 FastGPT 的页面**不认识我们的错误格式**,
   用户大概率只看到一句笼统的"请求失败",而不是我们那条"今日提问次数已达上限" ——
   这是自建拦截相比官方 `shareAuth` 的固有差距,商业版才有干净的提示。

### 9.5 身份是怎么传过去的

分享页前端的请求带不了 `Authorization` 头(它不认识我们的 JWT),所以登录接口额外种了一份
**HttpOnly + SameSite=Lax 的 Cookie**(`airag_token`)。同源请求浏览器会自动带上它,
网关据此认出账号。

两个安全细节:

- `SameSite=Lax` 是**防 CSRF 的关键**,不能省 —— 它保证跨站发起的 POST 不会带上这个 Cookie;
  而分享页的对话请求是本域发起的同源请求,不受影响。
- 反代转发给 FastGPT 时,我们会**把这个 Cookie 摘掉再转发**(同时也不转发 `Authorization`),
  避免把我们的登录凭证送给上游。`ShareProxyIntegrationTest` 里有对应的断言。

### 9.6 每个账号的会话隔离

拦截层会把请求体里的 `chatId` 改写成 `u<用户ID>-<链路>-<原chatId>`(可用
`app.share-proxy.namespace-chat-id: false` 关掉)。这样**同一个浏览器换账号登录,
不会读到上一个人的对话上下文**。没带 `chatId` 的请求不改写 —— 那种情况下会话 ID 由
FastGPT 随机生成,本身就不会撞车。

### 9.7 与前缀路由的区别

如果 FastGPT 挂在子路径下(比如 `/fastgpt/chat/share`),Next.js 的 `/_next/*` 等资源是
根绝对路径,会 404 —— 而且 FastGPT 自己的 `/api/*` 会和我们的 `/api/*` 撞车。
所以这里让 FastGPT 占据根路径,把**我们的登录页与接口**用更具体的 `location` 挑出来。
`/chat/share` 在两种模式下都成立这一点,正是靠 Nginx 的路由优先级实现的。

