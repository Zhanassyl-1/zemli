package com.zemli.bot.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

@Configuration
public class DatabaseConfig {

    @Bean
    public DataSource dataSource(GameProperties properties) {
        String jdbcUrl = normalizeDatabaseUrl(properties.getDatabaseUrl());

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setConnectionTimeout(3000);
        dataSource.setMaximumPoolSize(20);
        dataSource.setMinimumIdle(5);
        dataSource.setPoolName("zemli-sqlite-pool");
        return dataSource;
    }

    private String normalizeDatabaseUrl(String rawUrl) {
        if (!StringUtils.hasText(rawUrl)) {
            return "jdbc:sqlite:bot.sqlite3";
        }
        if (rawUrl.startsWith("jdbc:sqlite:")) {
            return rawUrl;
        }
        if (rawUrl.startsWith("sqlite+aiosqlite:///")) {
            return "jdbc:sqlite:" + rawUrl.substring("sqlite+aiosqlite:///".length());
        }
        if (rawUrl.startsWith("sqlite:///")) {
            return "jdbc:sqlite:" + rawUrl.substring("sqlite:///".length());
        }
        if (rawUrl.startsWith("sqlite://")) {
            return "jdbc:sqlite:" + rawUrl.substring("sqlite://".length());
        }
        return "jdbc:sqlite:" + rawUrl;
    }
}
