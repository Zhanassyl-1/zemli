package com.zemli.bot.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class MapPageController {

    @GetMapping("/map/{fileName:.+}")
    @ResponseBody
    public ResponseEntity<Resource> serveFile(@PathVariable String fileName) {
        return serveFromRoots("map/" + fileName);
    }

    @GetMapping("/map/{folder}/{fileName:.+}")
    @ResponseBody
    public ResponseEntity<Resource> serveNestedFile(
            @PathVariable String folder,
            @PathVariable String fileName
    ) {
        return serveFromRoots("map/" + folder + "/" + fileName);
    }

    private ResponseEntity<Resource> serveFromRoots(String relativePath) {
        Resource resource = findInKnownRoots(relativePath);
        if (resource == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaTypeFactory.getMediaType(resource).orElse(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM))
                .body(resource);
    }

    private Resource findInKnownRoots(String relativePath) {
        Resource fromStatic = new ClassPathResource("static/" + relativePath);
        if (fromStatic.exists() && fromStatic.isReadable()) {
            return fromStatic;
        }
        Resource fromStaticDotMap = new ClassPathResource("static.map/" + relativePath);
        if (fromStaticDotMap.exists() && fromStaticDotMap.isReadable()) {
            return fromStaticDotMap;
        }
        return null;
    }
}
