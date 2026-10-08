package com.example.airag.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全部业务配置的绑定对象,对应 application.yaml 中的 {@code app.*}。
 *
 * <p>使用 record + 紧凑构造器:即使 yaml 里漏配某一项,也能拿到安全的默认值,不会出现 null。</p>
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Auth auth, Quota quota, RateLimit rateLimit, Gateway gateway, Cors cors, Share share,
                            ShareProxy shareProxy) {

    public static final String DEFAULT_JWT_SECRET =
            "ai-rag-search-please-change-this-secret-to-a-long-random-string-2024";

    public AppProperties {
        if (auth == null) {
            auth = new Auth(null, null, null, null);
        }
        if (quota == null) {
            quota = new Quota(null, null, null, null, null, null);
        }
        if (rateLimit == null) {
            rateLimit = new RateLimit(null, null, null, null, null);
        }
        if (gateway == null) {
            // enabled, connectTimeoutSeconds, readTimeoutSeconds, faq, afterSale
            gateway = new Gateway(null, null, null, null, null);
        }
        if (cors == null) {
            cors = new Cors(null);
        }
        if (share == null) {
            share = new Share(null, null, null, null, null);
        }
        if (shareProxy == null) {
            shareProxy = new ShareProxy(null, null, null, null, null, null, null, null);
        }
    }

    // ------------------------------------------------------------------ auth

    public record Auth(String jwtSecret, Long tokenTtlMinutes, String redirectUrl, DefaultAdmin defaultAdmin) {

        public Auth {
            if (jwtSecret == null || jwtSecret.isBlank()) {
                jwtSecret = DEFAULT_JWT_SECRET;
            }
            if (tokenTtlMinutes == null || tokenTtlMinutes <= 0) {
                tokenTtlMinutes = 720L;
            }
            if (redirectUrl == null) {
                redirectUrl = "";
            }
            if (defaultAdmin == null) {
                defaultAdmin = new DefaultAdmin(null, null, null);
            }
        }

        public boolean usingDefaultSecret() {
            return DEFAULT_JWT_SECRET.equals(jwtSecret);
        }
    }

    public record DefaultAdmin(Boolean enabled, String username, String password) {

        public DefaultAdmin {
            if (enabled == null) {
                enabled = Boolean.TRUE;
            }
            if (username == null || username.isBlank()) {
                username = "admin";
            }
            if (password == null || password.isBlank()) {
                password = "admin123";
            }
        }
    }

    // ----------------------------------------------------------------- quota

    /** 扣减模式。 */
    public enum DeductMode {
        /** 一次提问扣 1 次,支持流式返回。 */
        REQUEST,
        /** 按 token 消耗折算次数,需要关闭流式才能拿到准确 usage。 */
        TOKEN
    }

    public record Quota(Boolean enabled, Integer defaultTotal, DeductMode deductMode, Integer tokensPerUnit,
                        Boolean refundOnFailure, DailyLimit dailyLimit) {

        public Quota {
            if (dailyLimit == null) {
                dailyLimit = new DailyLimit(null, null, null, null);
            }
            if (enabled == null) {
                enabled = Boolean.TRUE;
            }
            if (defaultTotal == null) {
                defaultTotal = 100;
            }
            if (deductMode == null) {
                deductMode = DeductMode.REQUEST;
            }
            if (tokensPerUnit == null || tokensPerUnit <= 0) {
                tokensPerUnit = 1000;
            }
            if (refundOnFailure == null) {
                refundOnFailure = Boolean.TRUE;
            }
        }

        /** -1(或更小)代表不限次数。 */
        public boolean unlimitedByDefault() {
            return defaultTotal < 0;
        }
    }

    /**
     * 每个自然日的提问上限,分两层。
     *
     * <p>与总额度相互独立:总额度管"一共能用多少次",这里管"一天之内最多问几次"。
     * 小于 0 表示不限制,次数后续在 yaml 里改即可。</p>
     *
     * @param perUser   <b>每个账号每天的总次数(两条链路合计)—— 这是主限制</b>。
     *                  个人上限和它两个都要满足,谁先到就按谁的话术拒绝。
     * @param faq       平台 FAQ(ai_question_record)每人每日上限,在总量之上再单独收紧
     * @param afterSale 售后(after_sale_question_record)每人每日上限,同上
     * @param zone      "自然日"按哪个时区算。默认北京时间 —— <b>不能用 JVM 默认时区</b>,
     *                  否则服务器部署在 UTC 时,重置时刻会变成北京时间早上 8 点。
     */
    public record DailyLimit(Integer perUser, Integer faq, Integer afterSale, String zone) {

        public DailyLimit {
            if (perUser == null) {
                perUser = -1;
            }
            if (faq == null) {
                faq = -1;
            }
            if (afterSale == null) {
                afterSale = -1;
            }
            if (zone == null || zone.isBlank()) {
                zone = "Asia/Shanghai";
            }
        }
    }

    // ------------------------------------------------------------ rate limit

    public record RateLimit(Boolean enabled, Long windowSeconds, Integer defaultLimit, Long cleanupIntervalMs,
                           List<Rule> rules) {

        public RateLimit {
            if (enabled == null) {
                enabled = Boolean.TRUE;
            }
            if (windowSeconds == null || windowSeconds <= 0) {
                windowSeconds = 60L;
            }
            if (defaultLimit == null) {
                defaultLimit = 60;
            }
            if (cleanupIntervalMs == null || cleanupIntervalMs <= 0) {
                cleanupIntervalMs = 300_000L;
            }
            if (rules == null) {
                rules = List.of();
            }
        }

        /**
         * @param path          匹配路径,支持精确、{@code /prefix/**}、{@code /prefix/*}
         * @param limit         窗口内最大请求数,0 或负数表示不限制
         * @param windowSeconds 可选,覆盖全局窗口
         */
        public record Rule(String path, Integer limit, Long windowSeconds) {

            public Rule {
                if (path == null) {
                    path = "";
                }
                if (limit == null) {
                    limit = 0;
                }
            }
        }
    }

    // --------------------------------------------------------------- gateway

    public record Gateway(Boolean enabled, Integer connectTimeoutSeconds,
                          Integer readTimeoutSeconds, FastGptApp faq, FastGptApp afterSale) {

        public Gateway {
            if (enabled == null) {
                enabled = Boolean.TRUE;
            }
            if (connectTimeoutSeconds == null || connectTimeoutSeconds <= 0) {
                connectTimeoutSeconds = 10;
            }
            if (readTimeoutSeconds == null || readTimeoutSeconds <= 0) {
                readTimeoutSeconds = 180;
            }
            if (faq == null) {
                faq = new FastGptApp(null, null, null);
            }
            if (afterSale == null) {
                afterSale = new FastGptApp(null, null, null);
            }
        }
    }

    /**
     * 一个 FastGPT 应用(对应"知识库 + 工作流应用"的一套配置)。
     *
     * <p>apiKey 为空即视为未配置。未配置时网关<b>直接报 503</b>,不会返回任何模拟内容 ——
     * 静默降级会让"配置没生效"看起来像"链路正常",非常难排查。</p>
     */
    public record FastGptApp(String apiUrl, String apiKey, String appId) {

        public FastGptApp {
            if (apiUrl == null || apiUrl.isBlank()) {
                apiUrl = "";
            }
            if (apiKey == null) {
                apiKey = "";
            }
            if (appId == null) {
                appId = "";
            }
        }

        public boolean configured() {
            return !apiKey.isBlank() && !apiUrl.isBlank()
                    && !apiUrl.contains("your-fastgpt-host");
        }
    }

    // ----------------------------------------------------------------- share

    /**
     * "地址栏伪装"分享页配置。
     *
     * <p>把 FastGPT 分享页内嵌进我们自己的页面,用户看到的始终是本服务的地址;
     * FastGPT 的真实地址只存在于服务端配置里,不会出现在浏览器地址栏。</p>
     *
     * @param enabled           是否启用;关闭或未配置 targetUrl 时,该路径会跳回内置对话页
     * @param targetUrl         FastGPT 分享页真实地址(不会出现在浏览器地址栏)
     * @param pagePath          对外暴露的本地路径,即登录后跳转的地址
     * @param title             包装页标题与品牌名
     * @param probeFrameOptions 是否探测目标页的 X-Frame-Options / CSP,被拒绝嵌入时给出提示而不是白屏
     */
    public record Share(Boolean enabled, String targetUrl, String pagePath, String title,
                        Boolean probeFrameOptions) {

        public Share {
            if (enabled == null) {
                enabled = Boolean.TRUE;
            }
            if (targetUrl == null) {
                targetUrl = "";
            }
            if (pagePath == null || pagePath.isBlank()) {
                pagePath = "/chat/share";
            }
            if (title == null || title.isBlank()) {
                title = "AI 知识库助手";
            }
            if (probeFrameOptions == null) {
                probeFrameOptions = Boolean.TRUE;
            }
        }

        /** 只有启用且配了目标地址,才值得对外暴露这个页面。 */
        public boolean usable() {
            return enabled && !targetUrl.isBlank();
        }
    }

    // ------------------------------------------------------------ share proxy

    /**
     * 分享页反代拦截配置:把 FastGPT 对话接口截到我们网关上做配额校验与记账。
     *
     * <p>这一项必须和 Nginx 反代配合使用 —— Nginx 负责把 FastGPT 整个源站搬到我们
     * 自己的地址后面,并把对话路径路由到本服务,本服务只做"校验 + 透传 + 记账",
     * 不改写 FastGPT 的协议内容。</p>
     *
     * <p>默认关闭:没装 Nginx 时不会有请求打到这些路径上,开着也没意义;
     * 一旦 Nginx 开始把对话路径路由过来,若这里仍是关闭状态,会明确返回 503
     * 而不是悄悄放行(静默放行等于配额失效,是最糟的失败方式)。</p>
     *
     * @param enabled              是否启用拦截
     * @param upstreamBase         FastGPT 源站地址,用于透传,例如 http://202.140.142.70:4000
     * @param connectTimeoutSeconds 连接上游超时
     * @param readTimeoutSeconds   等待上游响应超时(流式对话要留够)
     * @param maxRequestBytes      允许的最大请求体字节数,超过直接 413
     * @param channel              这些请求归到哪条链路,决定写哪张内容表与 usage_log.channel
     *                             (faq / after-sale)
     * @param storeContent         是否顺带把问答内容写进内容表;关闭则只记 usage_log
     * @param namespaceChatId      是否把请求体里的 chatId 改写成 {@code u<用户ID>-<链路>-<原chatId>}。
     *                             开启后每个账号在 FastGPT 侧拥有独立会话,同一浏览器换账号登录
     *                             也不会读到上一个人的上下文;关闭则原样透传(风险自担)
     */
    public record ShareProxy(Boolean enabled, String upstreamBase, Integer connectTimeoutSeconds,
                             Integer readTimeoutSeconds, Integer maxRequestBytes, String channel,
                             Boolean storeContent, Boolean namespaceChatId) {

        public ShareProxy {
            if (enabled == null) {
                enabled = Boolean.FALSE;
            }
            if (upstreamBase == null) {
                upstreamBase = "";
            }
            if (connectTimeoutSeconds == null || connectTimeoutSeconds <= 0) {
                connectTimeoutSeconds = 10;
            }
            if (readTimeoutSeconds == null || readTimeoutSeconds <= 0) {
                readTimeoutSeconds = 300;
            }
            if (maxRequestBytes == null || maxRequestBytes <= 0) {
                maxRequestBytes = 1024 * 1024;
            }
            if (channel == null || channel.isBlank()) {
                channel = "faq";
            }
            if (storeContent == null) {
                storeContent = Boolean.TRUE;
            }
            if (namespaceChatId == null) {
                namespaceChatId = Boolean.TRUE;
            }
        }

        /** 只有启用且配了上游地址,拦截才有意义。 */
        public boolean usable() {
            return enabled && !upstreamBase.isBlank();
        }

        /**
         * 拼出透传目标地址。
         *
         * @param pathAndQuery 原始请求的路径(含 query),例如 /api/v1/chat/completions?x=1
         */
        public String upstreamUrl(String pathAndQuery) {
            String base = upstreamBase.endsWith("/")
                    ? upstreamBase.substring(0, upstreamBase.length() - 1)
                    : upstreamBase;
            return base + pathAndQuery;
        }
    }

    // ------------------------------------------------------------------ cors

    public record Cors(String allowedOrigins) {

        public Cors {
            if (allowedOrigins == null || allowedOrigins.isBlank()) {
                allowedOrigins = "*";
            }
        }

        /** 逗号分隔 -> 数组。 */
        public String[] originArray() {
            String[] parts = allowedOrigins.split(",");
            String[] result = new String[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = parts[i].trim();
            }
            return result;
        }
    }
}
