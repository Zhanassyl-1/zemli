package com.zemli.bot.config;

import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Configuration
public class DatabaseConfig {

    @Bean
    @Profile("dev")
    public DataSource devDataSource() {
        return DataSourceBuilder.create()
                .url("jdbc:h2:mem:zemli_bot;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build();
    }

    @Bean
    @Profile("prod")
    public DataSource prodDataSource() {
        String rawUrl = System.getenv("DATABASE_URL");
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new RuntimeException("DATABASE_URL not set");
        }

        if (rawUrl.startsWith("jdbc:")) {
            DataSourceBuilder<?> builder = DataSourceBuilder.create()
                    .url(rawUrl)
                    .driverClassName("org.postgresql.Driver");

            String username = firstNonBlank(
                    System.getenv("DB_USERNAME"),
                    System.getenv("POSTGRES_USER"),
                    System.getenv("PGUSER")
            );
            String password = firstNonBlank(
                    System.getenv("DB_PASSWORD"),
                    System.getenv("POSTGRES_PASSWORD"),
                    System.getenv("PGPASSWORD")
            );
            if (username != null) {
                builder.username(username);
            }
            if (password != null) {
                builder.password(password);
            }
            return builder.build();
        }

        if (rawUrl.startsWith("postgres://") || rawUrl.startsWith("postgresql://")) {
            URI dbUri = URI.create(rawUrl);
            String host = dbUri.getHost();
            int port = dbUri.getPort() == -1 ? 5432 : dbUri.getPort();
            String path = dbUri.getPath();
            if (host == null || host.isBlank() || path == null || path.isBlank() || "/".equals(path)) {
                throw new RuntimeException("Invalid DATABASE_URL format");
            }

            String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + path;
            if (dbUri.getQuery() != null && !dbUri.getQuery().isBlank()) {
                jdbcUrl += "?" + dbUri.getQuery();
            }

            String username = null;
            String password = null;
            String userInfo = dbUri.getUserInfo();
            if (userInfo != null && !userInfo.isBlank()) {
                String[] userParts = userInfo.split(":", 2);
                username = decodeUrlPart(userParts[0]);
                if (userParts.length > 1) {
                    password = decodeUrlPart(userParts[1]);
                }
            }

            if (username == null || username.isBlank()) {
                username = firstNonBlank(
                        System.getenv("DB_USERNAME"),
                        System.getenv("POSTGRES_USER"),
                        System.getenv("PGUSER")
                );
            }
            if (password == null) {
                password = firstNonBlank(
                        System.getenv("DB_PASSWORD"),
                        System.getenv("POSTGRES_PASSWORD"),
                        System.getenv("PGPASSWORD")
                );
            }

            DataSourceBuilder<?> builder = DataSourceBuilder.create()
                    .url(jdbcUrl)
                    .driverClassName("org.postgresql.Driver");

            if (username != null) {
                builder.username(username);
            }
            if (password != null) {
                builder.password(password);
            }
            return builder.build();
        }

        throw new RuntimeException("Unsupported DATABASE_URL format: expected jdbc: or postgres://");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String decodeUrlPart(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
