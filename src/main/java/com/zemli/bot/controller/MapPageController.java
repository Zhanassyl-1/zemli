package com.zemli.bot.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MapPageController {

    @GetMapping("/map")
    public String mapPage() {
        return "forward:/map/index.html";
    }

    @GetMapping("/map/")
    public String mapPageSlash() {
        return "forward:/map/index.html";
    }
}
