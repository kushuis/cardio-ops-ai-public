package io.cardioops.infrastructure.persistence;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 分一下库。生产是 PostgreSQL，H2 只给不拉 Docker 的测试。并发语义以 PostgreSQL 那条 SQL 为准，不拿 H2 冒充。
 */
public final class JdbcProduct {

    private JdbcProduct() {
    }

    public static boolean isPostgres(JdbcTemplate jdbcTemplate) {
        Boolean result = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql"));
        return Boolean.TRUE.equals(result);
    }
}
