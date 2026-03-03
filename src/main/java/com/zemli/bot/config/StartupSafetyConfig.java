package com.zemli.bot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

@Configuration
public class StartupSafetyConfig {

    private static final Logger log = LoggerFactory.getLogger(StartupSafetyConfig.class);

    @Bean
    @Order(0)
    public CommandLineRunner startupModeLogger(Environment environment) {
        return args -> {
            boolean devMode = environment.matchesProfiles("dev", "default");
            if (devMode) {
                log.info("====================================");
                log.info("✅ ЗАПУСК В DEV РЕЖИМЕ");
                log.info("✅ Используется H2 база в памяти");
                log.info("✅ Данные НЕ сохранятся после перезапуска");
                log.info("====================================");
            }
        };
    }
}
