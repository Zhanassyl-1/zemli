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
            return DataSourceBuilder.create()
                    .url(rawUrl)
                    .driverClassName("org.postgresql.Driver")
                    .build();
        }

        try {
            URI uri = new URI(rawUrl.replace("postgres://", "postgresql://"));
            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + uri.getPath();

            String username = null, password = null;
            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                String[] parts = userInfo.split(":", 2);
                username = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                if (parts.length > 1) {
                    password = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                }
            }

            DataSourceBuilder<?> builder = DataSourceBuilder.create()
                    .url(jdbcUrl)
                    .driverClassName("org.postgresql.Driver");
            if (username != null) builder.username(username);
            if (password != null) builder.password(password);
            return builder.build();

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse DATABASE_URL: " + e.getMessage(), e);
        }
    }
}
