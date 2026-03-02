package com.zemli.bot.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Configuration
public class DatabaseConfig {

    @Bean
    public DataSource dataSource(@Value("${DATABASE_URL:${JDBC_DATABASE_URL:}}") String databaseUrl) {
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "Datasource URL is empty. Set DATABASE_URL in Railway service variables."
            );
        }
        return postgresDataSource(databaseUrl);
    }

    private DataSource postgresDataSource(String rawUrl) {
        ParsedDatabaseUrl parsed = parseDatabaseUrl(rawUrl);
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setJdbcUrl(parsed.jdbcUrl());
        if (parsed.username() != null && !parsed.username().isBlank()) {
            dataSource.setUsername(parsed.username());
        }
        if (parsed.password() != null) {
            dataSource.setPassword(parsed.password());
        }
        dataSource.setConnectionTimeout(3000);
        dataSource.setMaximumPoolSize(20);
        dataSource.setMinimumIdle(5);
        dataSource.setPoolName("zemli-postgres-pool");
        return dataSource;
    }

    private ParsedDatabaseUrl parseDatabaseUrl(String rawUrl) {
        String cleaned = trimOptionalQuotes(rawUrl);
        String uriValue;
        if (cleaned.startsWith("jdbc:postgresql://")) {
            uriValue = "postgresql://" + cleaned.substring("jdbc:postgresql://".length());
        } else if (cleaned.startsWith("jdbc:postgres://")) {
            uriValue = "postgresql://" + cleaned.substring("jdbc:postgres://".length());
        } else if (cleaned.startsWith("postgresql://")) {
            uriValue = cleaned;
        } else if (cleaned.startsWith("postgres://")) {
            uriValue = "postgresql://" + cleaned.substring("postgres://".length());
        } else {
            throw new IllegalStateException("Unsupported DATABASE_URL format: " + rawUrl);
        }

        URI uri = URI.create(uriValue);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("DATABASE_URL host is missing: " + rawUrl);
        }

        String path = uri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            throw new IllegalStateException("DATABASE_URL database name is missing: " + rawUrl);
        }

        StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://")
                .append(host);
        if (uri.getPort() > 0) {
            jdbcUrl.append(':').append(uri.getPort());
        }
        jdbcUrl.append(path);
        if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
            jdbcUrl.append('?').append(uri.getQuery());
        }

        String username = null;
        String password = null;
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int separator = userInfo.indexOf(':');
            if (separator >= 0) {
                username = decodeUriComponent(userInfo.substring(0, separator));
                password = decodeUriComponent(userInfo.substring(separator + 1));
            } else {
                username = decodeUriComponent(userInfo);
            }
        }

        return new ParsedDatabaseUrl(jdbcUrl.toString(), username, password);
    }

    private String trimOptionalQuotes(String value) {
        String trimmed = value.trim();
        if (trimmed.length() > 1) {
            if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                    || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
                return trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    private String decodeUriComponent(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private record ParsedDatabaseUrl(String jdbcUrl, String username, String password) {
    }
}
