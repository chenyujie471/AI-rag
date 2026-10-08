package com.example.airag.config;

import java.io.File;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动时把"实际生效"的关键配置打进日志,用于定位"改了配置却不生效"。
 *
 * <p>为什么需要这个:Spring Boot 里有两类资源的生效时机完全不同 ——</p>
 * <ul>
 *   <li><b>静态页面</b>:每次请求时从 classpath 解析,文件一换立刻生效;</li>
 *   <li><b>application.yaml</b>:只在启动时读一次进内存,不重启永远不生效。</li>
 * </ul>
 *
 * <p>于是很容易出现"新页面能打开、配置却还是旧的"这种半新半旧的状态,光看现象分不清是
 * <b>进程没重启</b>、<b>配置文件是旧的</b>,还是<b>被环境变量覆盖了</b>。这三者的处理方式
 * 完全不同,靠猜很浪费时间,所以这里一次把判别依据全打出来。</p>
 *
 * <p>密钥类只打印前缀与长度,不打印完整值。</p>
 */
@Component
public class StartupDiagnostics implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupDiagnostics.class);

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** 每次改动这里,用来确认进程跑的到底是哪一次构建。 */
    private static final String BUILD_MARKER = "diag-2";

    /**
     * application.yaml 里用到的全部环境变量。
     *
     * <p>启动时<b>只报告真的被设置过的那几个</b>:绝大多数情况下一个都没设,全列出来只会刷屏,
     * 把真正要看的两三行淹掉。</p>
     */
    private static final String[] WATCHED_ENV = {
            "SERVER_PORT", "LOGIN_REDIRECT_URL",
            "DB_URL", "DB_USERNAME", "DB_PASSWORD",
            "JWT_SECRET", "QUOTA_DEFAULT_TOTAL", "DAILY_LIMIT_FAQ", "DAILY_LIMIT_AFTER_SALE",
            "FASTGPT_FAQ_URL", "FASTGPT_FAQ_KEY", "FASTGPT_FAQ_APP_ID",
            "FASTGPT_AFTER_SALE_URL", "FASTGPT_AFTER_SALE_KEY", "FASTGPT_AFTER_SALE_APP_ID",
            "FASTGPT_BASE_URL", "FASTGPT_SHARE_URL", "SHARE_PROXY_ENABLED",
            "SPRING_APPLICATION_JSON"
    };

    private final AppProperties properties;
    private final Environment environment;

    public StartupDiagnostics(AppProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        AppProperties.Gateway gateway = properties.gateway();

        log.info("");
        log.info("==================== AI-Rag 启动自检 ====================");
        log.info("构建标记     : {}", BUILD_MARKER);
        log.info("配置来源     : {}", configFileLocation());
        log.info("生效端口     : {}        登录后跳转: {}",
                environment.getProperty("server.port", "?"), properties.auth().redirectUrl());
        log.info("数据库       : {}", environment.getProperty("spring.datasource.url"));
        reportEnvOverrides();
        log.info("网关 enabled={}", gateway.enabled());
        describeApp("faq", "app.gateway.faq", gateway.faq());
        describeApp("after-sale", "app.gateway.after-sale", gateway.afterSale());
        log.info("========================================================");
        log.info("");
    }

    /**
     * 只报告真正被设置过的环境变量。
     *
     * <p>设成<b>空值</b>的要单独大声喊:环境变量优先级高于 application.yaml,
     * 空字符串照样会把 yaml 里的默认值顶掉 —— 这是最隐蔽的一种"配置不生效"。</p>
     */
    private void reportEnvOverrides() {
        List<String> overrides = new ArrayList<>();
        List<String> blanks = new ArrayList<>();
        for (String name : WATCHED_ENV) {
            String value = System.getenv(name);
            if (value == null) {
                continue;               // 没设过,不占篇幅
            }
            if (value.isBlank()) {
                blanks.add(name);
            } else {
                overrides.add(name + "=" + describeEnvValue(name, value));
            }
        }
        if (overrides.isEmpty() && blanks.isEmpty()) {
            log.info("环境变量覆盖 : <无>,全部使用 application.yaml 里的默认值");
        } else if (!overrides.isEmpty()) {
            log.info("环境变量覆盖 : {}", String.join(", ", overrides));
        }
        if (!blanks.isEmpty()) {
            log.warn("环境变量覆盖 : 以下变量被设成了【空值】,会把 application.yaml 里的值顶掉 —— {}",
                    String.join(", ", blanks));
        }
    }

    private void describeApp(String name, String prefix, AppProperties.FastGptApp app) {
        String label = "网关 " + pad(name);
        if (app.configured()) {
            log.info("{}: 已配置   api-key={}   app-id={}", label, mask(app.apiKey()), orNone(app.appId()));
            return;
        }
        log.warn("{}: 未配置!  {}   api-url={}   ← 调用时会直接报 502,不会返回模拟回复",
                label, missingSummary(app), orNone(app.apiUrl()));
        // 只有这里才需要看"Environment 里的原始值":用来区分"源里本来就是空的"和"绑定没生效"
        log.warn("{}:   Environment 里的 api-key 原始值 = {}",
                label, describeRaw(environment.getProperty(prefix + ".api-key")));
    }

    /** 一句话说清缺哪一项,不放 api-url / app-id(它们已经在上面的行里了)。 */
    private static String missingSummary(AppProperties.FastGptApp app) {
        if (app.apiUrl().isBlank()) {
            return "api-url 为空";
        }
        if (app.apiUrl().contains("your-fastgpt-host")) {
            return "api-url 仍是占位符";
        }
        return "api-key 为空";
    }

    /** classpath 上那份 application.yaml 的实际路径与修改时间 —— 用来判断读的是不是旧文件。 */
    private String configFileLocation() {
        URL url = getClass().getResource("/application.yaml");
        if (url == null) {
            return "<classpath 上找不到 application.yaml>";
        }
        if (!"file".equals(url.getProtocol())) {
            return url.toString();
        }
        try {
            File file = new File(url.toURI());
            return file.getAbsolutePath()
                    + "  (最后修改 " + TIME.format(Instant.ofEpochMilli(file.lastModified())) + ")";
        } catch (Exception ex) {
            return url.toString();
        }
    }

    /** null = 没有任何配置源提供;空串 = 被某个配置源显式置空了(典型是环境变量)。 */
    private static String describeRaw(String value) {
        if (value == null) {
            return "<null>(没有配置源提供该值)";
        }
        if (value.isBlank()) {
            return "<空字符串>(被某个配置源显式置空了,检查环境变量)";
        }
        return mask(value);
    }

    /** 密钥类只给前缀和长度,避免把它写进日志。 */
    private static String describeEnvValue(String name, String value) {
        boolean secret = name.contains("KEY") || name.contains("PASSWORD")
                || name.contains("SECRET") || name.contains("JSON");
        return secret ? mask(value) : value;
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "<未配置>";
        }
        if (value.length() <= 12) {
            return value.charAt(0) + "…(共 " + value.length() + " 字符)";
        }
        return value.substring(0, 12) + "…(共 " + value.length() + " 字符)";
    }

    private static String pad(String name) {
        return name.length() >= 10 ? name : name + " ".repeat(10 - name.length());
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "<未配置>" : value;
    }
}
