package com.zemli.bot.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ImageAssetsController {

    @GetMapping("/images/{fileName:.+}")
    public ResponseEntity<Resource> image(@PathVariable String fileName) {
        return serve(fileName);
    }

    @GetMapping("/images/{folder}/{fileName:.+}")
    public ResponseEntity<Resource> nestedImage(
            @PathVariable String folder,
            @PathVariable String fileName
    ) {
        return serve(folder + "/" + fileName);
    }

    private ResponseEntity<Resource> serve(String relativePath) {
        if (relativePath.contains("..")) {
            return ResponseEntity.badRequest().build();
        }
        Resource resource = new ClassPathResource("images/" + relativePath);
        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }
        MediaType mediaType = MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(mediaType).body(resource);
    }
}
