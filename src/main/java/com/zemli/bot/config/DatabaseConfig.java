package com.zemli.bot.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class DatabaseConfig {

    @Bean
    public DataSource dataSource(@Value("${spring.datasource.url:}") String datasourceUrl) {
        if (datasourceUrl == null || datasourceUrl.isBlank()) {
            throw new IllegalStateException(
                    "Datasource URL is empty. Set DATABASE_URL in Railway service variables."
            );
        }
        return postgresDataSource(datasourceUrl);
    }

    private DataSource postgresDataSource(String rawUrl) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setJdbcUrl(normalizeJdbcUrl(rawUrl));
        dataSource.setConnectionTimeout(3000);
        dataSource.setMaximumPoolSize(20);
        dataSource.setMinimumIdle(5);
        dataSource.setPoolName("zemli-postgres-pool");
        return dataSource;
    }

    private String normalizeJdbcUrl(String rawUrl) {
        String url = rawUrl.trim();
        if (url.startsWith("jdbc:postgresql://")) {
            return url;
        }
        if (url.startsWith("jdbc:postgres://")) {
            return "jdbc:postgresql://" + url.substring("jdbc:postgres://".length());
        }
        if (url.startsWith("postgresql://")) {
            return "jdbc:" + url;
        }
        if (url.startsWith("postgres://")) {
            return "jdbc:postgresql://" + url.substring("postgres://".length());
        }
        if (url.startsWith("\"") && url.endsWith("\"") && url.length() > 2) {
            return normalizeJdbcUrl(url.substring(1, url.length() - 1));
        }
        if (url.startsWith("'") && url.endsWith("'") && url.length() > 2) {
            return normalizeJdbcUrl(url.substring(1, url.length() - 1));
        }
        throw new IllegalStateException("Unsupported DATABASE_URL format: " + rawUrl);
    }
}
