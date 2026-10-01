package com.example.resource;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/resources")
public class ResourceController {
    private final ResourceService service;
    public ResourceController(ResourceService service) { this.service = service; }
    @PostMapping public ResponseEntity<Map<String, Long>> upload(@RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType, @RequestBody byte[] data) {
        return ResponseEntity.ok(Map.of("id", service.upload(contentType, data)));
    }
    @GetMapping("/{id}") public ResponseEntity<byte[]> get(@PathVariable String id) {
        byte[] data = service.get(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg")).contentLength(data.length).body(data);
    }
    @DeleteMapping public ResponseEntity<Map<String, List<Long>>> delete(@RequestParam String id) {
        return ResponseEntity.ok(Map.of("ids", service.delete(id)));
    }
}
