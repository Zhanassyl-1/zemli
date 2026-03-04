package com.zemli.bot.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class MapPageController {

    @GetMapping(value = "/map/index.html", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<Resource> mapIndex() {
        return ResponseEntity.ok(new ClassPathResource("static/map/index.html"));
    }

    @GetMapping("/map")
    public String mapPage() {
        return "forward:/map/index.html";
    }

    @GetMapping("/map/")
    public String mapPageSlash() {
        return "forward:/map/index.html";
    }
}
