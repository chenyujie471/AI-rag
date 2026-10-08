-- ===========================================================================
-- AI-Rag-search 初始化脚本(幂等,可重复执行)
-- 由 spring.sql.init 在每次应用启动时自动执行
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. 系统用户表(承载登录账号 + 每人独立额度 + 角色)
--
--    注意:这里的 CREATE TABLE IF NOT EXISTS 只对"还不存在的表"生效。
--    已经建好的库不会因为这里多了几列就自动获得它们 —— 那部分由
--    com.example.airag.config.SchemaMigrator 在启动时用 ALTER TABLE 补齐,
--    它靠 JDBC 元数据(DatabaseMetaData.getColumns)判断缺哪列,可重复执行。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user
(
    id                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username               VARCHAR(64)  NOT NULL COMMENT '登录账号',
    password_hash          VARCHAR(255) NOT NULL COMMENT '密码哈希(PBKDF2,格式 pbkdf2$iter$salt$hash)',
    nickname               VARCHAR(64)           DEFAULT NULL COMMENT '昵称/显示名',
    role                   VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT '角色:ADMIN=管理员 USER=普通用户',
    status                 TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:1=启用 0=禁用',
    quota_total            INT          NOT NULL DEFAULT -1 COMMENT '终身总额度,-1=不限制(默认不限,现在只用每日上限控量)',
    quota_used             INT          NOT NULL DEFAULT 0 COMMENT '已消耗次数',
    daily_limit_total      INT                   DEFAULT NULL COMMENT '每日总量上限(两条链路合计):NULL=用全局默认 -1=不限。这是主限制',
    daily_limit_faq        INT                   DEFAULT NULL COMMENT '平台FAQ每日上限:NULL=用全局默认 -1=不限',
    daily_limit_after_sale INT                   DEFAULT NULL COMMENT '售后每日上限:NULL=用全局默认 -1=不限',
    last_login_at          DATETIME              DEFAULT NULL COMMENT '最后登录时间',
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='系统用户(账号/密码/额度/角色)';

-- ---------------------------------------------------------------------------
-- 2. AI 问题记录表(平台 FAQ 知识库链路)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_question_record
(
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id           BIGINT       NOT NULL COMMENT '提问用户ID',
    username          VARCHAR(64)           DEFAULT NULL COMMENT '冗余账号,便于直接查询',
    session_id        VARCHAR(128)          DEFAULT NULL COMMENT '会话ID(对应 FastGPT chatId)',
    question          TEXT         NOT NULL COMMENT '用户问题',
    answer            MEDIUMTEXT            DEFAULT NULL COMMENT 'AI 回答',
    app_id            VARCHAR(64)           DEFAULT NULL COMMENT 'FastGPT 应用ID',
    prompt_tokens     INT          NOT NULL DEFAULT 0 COMMENT '输入 token',
    completion_tokens INT          NOT NULL DEFAULT 0 COMMENT '输出 token',
    total_tokens      INT          NOT NULL DEFAULT 0 COMMENT '合计 token',
    cost_quota        INT          NOT NULL DEFAULT 0 COMMENT '本次扣减次数',
    status            VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED/REJECTED',
    error_msg         VARCHAR(512)          DEFAULT NULL COMMENT '失败原因',
    duration_ms       BIGINT       NOT NULL DEFAULT 0 COMMENT '耗时(毫秒)',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(毫秒精度,合并日志按它排序)',
    PRIMARY KEY (id),
    KEY idx_ai_q_user_created (user_id, created_at),
    KEY idx_ai_q_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI(平台FAQ)提问记录';

-- ---------------------------------------------------------------------------
-- 3. 售后问题记录表(售后知识库链路)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS after_sale_question_record
(
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id           BIGINT       NOT NULL COMMENT '提问用户ID',
    username          VARCHAR(64)           DEFAULT NULL COMMENT '冗余账号,便于直接查询',
    session_id        VARCHAR(128)          DEFAULT NULL COMMENT '会话ID(对应 FastGPT chatId)',
    question          TEXT         NOT NULL COMMENT '用户问题',
    answer            MEDIUMTEXT            DEFAULT NULL COMMENT 'AI 回答',
    app_id            VARCHAR(64)           DEFAULT NULL COMMENT 'FastGPT 应用ID',
    prompt_tokens     INT          NOT NULL DEFAULT 0 COMMENT '输入 token',
    completion_tokens INT          NOT NULL DEFAULT 0 COMMENT '输出 token',
    total_tokens      INT          NOT NULL DEFAULT 0 COMMENT '合计 token',
    cost_quota        INT          NOT NULL DEFAULT 0 COMMENT '本次扣减次数',
    status            VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED/REJECTED',
    error_msg         VARCHAR(512)          DEFAULT NULL COMMENT '失败原因',
    duration_ms       BIGINT       NOT NULL DEFAULT 0 COMMENT '耗时(毫秒)',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(毫秒精度,合并日志按它排序)',
    PRIMARY KEY (id),
    KEY idx_as_q_user_created (user_id, created_at),
    KEY idx_as_q_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='售后提问记录';

-- ---------------------------------------------------------------------------
-- 4. 账号使用记录表(usage_log)
--
--    只记"谁、什么时候、走哪条链路、扣了几次、成功没有、从哪个 IP 来的",
--    刻意不记用户问题与 AI 回答 —— 那是上面两张内容表的职责。
--
--    FastGPT 内部的调用过程(节点流转 / responseData)也不在这里:网关调用上游时
--    传的是 detail=false,且只解析 choices[].delta.content 与 usage,
--    既没有也不需要存放工作流执行明细。
--
--    和内容表分开的好处:
--      * 两条链路共用一张表,将来加第三条链路不用再加表(channel 列区分);
--      * 内容表可以定期清理,记账数据长期留存,互不影响;
--      * 顺手留下 client_ip / user_agent,便于事后审计。
--
--    注意:usage_log 目前只写不读(没有对外查询接口),直接 SQL 查即可,例如:
--      SELECT channel, COUNT(*) FROM usage_log
--       WHERE user_id = 1 AND created_at >= CURDATE() GROUP BY channel;
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS usage_log
(
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id           BIGINT      NOT NULL COMMENT '用户ID',
    username          VARCHAR(64)          DEFAULT NULL COMMENT '冗余账号,便于直接查询',
    channel           VARCHAR(32) NOT NULL COMMENT '链路:faq / after-sale',
    session_id        VARCHAR(128)         DEFAULT NULL COMMENT '会话ID(对应 FastGPT chatId)',
    app_id            VARCHAR(64)          DEFAULT NULL COMMENT 'FastGPT 应用ID',
    prompt_tokens     INT         NOT NULL DEFAULT 0 COMMENT '输入 token',
    completion_tokens INT         NOT NULL DEFAULT 0 COMMENT '输出 token',
    total_tokens      INT         NOT NULL DEFAULT 0 COMMENT '合计 token',
    cost_quota        INT         NOT NULL DEFAULT 0 COMMENT '本次扣减次数',
    status            VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED/REJECTED',
    error_msg         VARCHAR(512)         DEFAULT NULL COMMENT '失败原因',
    duration_ms       BIGINT      NOT NULL DEFAULT 0 COMMENT '耗时(毫秒)',
    client_ip         VARCHAR(64)          DEFAULT NULL COMMENT '客户端IP(优先取 X-Forwarded-For 第一段)',
    user_agent        VARCHAR(255)         DEFAULT NULL COMMENT 'User-Agent(已按列宽截断)',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_usage_user_created (user_id, created_at),
    KEY idx_usage_channel_created (channel, created_at),
    KEY idx_usage_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='账号使用记录(不含问答内容)';
