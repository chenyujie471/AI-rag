package com.example.airag;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 整体上下文启动测试。
 *
 * <p>它会真实连接 MySQL(执行 schema.sql),因此在没有数据库的机器上默认关闭。
 * 本地起了 MySQL 之后,把 {@code @Disabled} 去掉即可作为端到端冒烟测试使用。</p>
 */
@SpringBootTest
@Disabled("需要本地 MySQL(app.datasource 指向的库),联调时手动开启")
class AiRagApplicationTests {

    @Test
    void contextLoads() {
    }

}
