package com.example.airag.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时给已存在的表补上新增的列。
 *
 * <p><b>为什么需要它:</b> {@code schema.sql} 里全是 {@code CREATE TABLE IF NOT EXISTS},
 * 这对<b>还不存在的表</b>有效,对<b>已经存在的表</b>却什么都不做 —— 表里少一列,它不会补,
 * 也不会报错。结果是代码里 {@code SELECT *} 读不到新列,运行时报"列不存在",而现场看起来
 * 就像"配置改了没生效"一样莫名其妙。</p>
 *
 * <p>所以这里在启动时用 JDBC 元数据查一遍,缺哪列补哪列。相比在 {@code schema.sql} 里写
 * MySQL 的 {@code INFORMATION_SCHEMA} + {@code PREPARE} 技巧,这个做法:</p>
 * <ul>
 *   <li><b>跨库通用</b> —— 测试跑的是 H2,生产是 MySQL,同一套代码都能用;</li>
 *   <li><b>幂等</b> —— 每次启动都检查,已经有了就跳过;</li>
 *   <li><b>不依赖建表权限</b>,只需要 {@code ALTER} 权限。</li>
 * </ul>
 *
 * <p>顺序上必须早于任何查用户表的组件,所以标了 {@code @Order(0)};
 * {@link com.example.airag.user.DefaultAdminInitializer} 是 {@code @Order(10)}。</p>
 *
 * <p>真正失败(比如数据库账号没有 ALTER 权限)时<b>直接让应用启不起来</b>,而不是带着不完整的
 * 表结构继续跑 —— 那种情况下后面每一次登录都会失败,晚报错不如早报错。</p>
 */
@Component
@Order(0)
public class SchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    /**
     * 需要保证存在的列。
     *
     * <p>故意不写 {@code AFTER xxx}:H2 不支持,写了就没法在测试里跑。
     * 列顺序对查询没有任何影响。</p>
     */
    private static final List<ColumnSpec> REQUIRED_COLUMNS = List.of(
            new ColumnSpec("sys_user", "role", "VARCHAR(16) NOT NULL DEFAULT 'USER'"),
            new ColumnSpec("sys_user", "daily_limit_total", "INT DEFAULT NULL"),
            new ColumnSpec("sys_user", "daily_limit_faq", "INT DEFAULT NULL"),
            new ColumnSpec("sys_user", "daily_limit_after_sale", "INT DEFAULT NULL")
    );

    private final DataSource dataSource;

    public SchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        List<String> added = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (ColumnSpec spec : REQUIRED_COLUMNS) {
                if (columnExists(connection, spec.table(), spec.column())) {
                    continue;
                }
                String ddl = "ALTER TABLE " + spec.table() + " ADD COLUMN " + spec.column() + " " + spec.type();
                log.warn("表 {} 缺少列 {},正在补上:{}", spec.table(), spec.column(), ddl);
                try (Statement statement = connection.createStatement()) {
                    statement.execute(ddl);
                } catch (SQLException ex) {
                    // 这里必须给出可以照抄的手工 SQL,否则用户只看到一句 MySQL 原始报错,
                    // 不知道"缺的列叫什么、类型是什么"。补完之后重启即可。
                    log.error("给 {}.{} 补列失败。请让 DBA 手工执行下面这条语句,然后重启应用:{}\n原始错误:{}",
                            spec.table(), spec.column(), ddl, ex.getMessage());
                    throw ex;
                }
                added.add(spec.table() + "." + spec.column());
            }
        }
        if (added.isEmpty()) {
            log.debug("表结构检查通过:新增列都已存在");
        } else {
            log.warn("表结构已自动补齐 {} 个列:{}", added.size(), String.join(", ", added));
        }
    }

    /**
     * 用元数据判断列是否存在。
     *
     * <p>表名大小写在不同数据库/操作系统上不一致(MySQL on Linux 区分大小写,H2 默认全大写),
     * 所以把几种写法都试一遍,而不是赌某一种。</p>
     */
    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String[] candidates = {
                table,
                table.toUpperCase(Locale.ROOT),
                table.toLowerCase(Locale.ROOT)
        };
        for (String candidate : candidates) {
            try (ResultSet columns = metaData.getColumns(connection.getCatalog(), null, candidate, null)) {
                while (columns.next()) {
                    if (column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private record ColumnSpec(String table, String column, String type) {
    }
}
