package com.zemli.bot.config;

import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

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
        String url = System.getenv("DATABASE_URL");
        if (url == null) {
            throw new RuntimeException("DATABASE_URL not set");
        }

        return DataSourceBuilder.create()
                .url(url)
                .build();
    }
}
