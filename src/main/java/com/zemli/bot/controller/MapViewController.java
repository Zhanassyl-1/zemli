package com.zemli.bot.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Controller
public class MapViewController {

    @GetMapping(value = "/map_page", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getMapPage() {
        try {
            Resource resource = new ClassPathResource("static/map/index.html");
            if (!resource.exists()) {
                resource = new ClassPathResource("map/index.html");
            }

            if (!resource.exists()) {
                return ResponseEntity.status(404)
                        .body("<h1>404 - Карта не найдена</h1><p>Файл index.html отсутствует</p>");
            }

            try (InputStream in = resource.getInputStream()) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return ResponseEntity.ok().body(content);
            }
        } catch (IOException e) {
            return ResponseEntity.status(500)
                    .body("<h1>500 - Ошибка сервера</h1><p>" + e.getMessage() + "</p>");
        }
    }
}
