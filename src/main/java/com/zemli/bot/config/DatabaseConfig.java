package com.zemli.bot.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class DatabaseConfig {

    @Bean
    public DataSource dataSource(@Value("${DATABASE_URL:}") String databaseUrl) {
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException("DATABASE_URL is empty. Configure PostgreSQL URL in Railway.");
        }
        return postgresDataSource(databaseUrl);
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
        if (rawUrl.startsWith("jdbc:postgresql://")) {
            return rawUrl;
        }
        if (rawUrl.startsWith("postgresql://")) {
            return "jdbc:" + rawUrl;
        }
        if (rawUrl.startsWith("postgres://")) {
            return "jdbc:postgresql://" + rawUrl.substring("postgres://".length());
        }
        throw new IllegalStateException("Unsupported DATABASE_URL format: " + rawUrl);
    }
}
