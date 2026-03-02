package com.zemli.bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ZemliBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZemliBotApplication.class, args);
    }
}
