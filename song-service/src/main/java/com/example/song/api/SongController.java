package com.example.song.api;

import com.example.song.service.SongService;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/songs")
public class SongController {
    private final SongService service;
    public SongController(SongService service) { this.service = service; }
    @PostMapping public ResponseEntity<Map<String, Long>> create(@RequestBody SongDto dto) {
        return ResponseEntity.ok(Map.of("id", service.create(dto)));
    }
    @GetMapping("/{id}") public ResponseEntity<SongDto> get(@PathVariable String id) {
        return ResponseEntity.ok(service.get(id));
    }
    @DeleteMapping public ResponseEntity<Map<String, List<Long>>> delete(@RequestParam String id) {
        return ResponseEntity.ok(Map.of("ids", service.delete(id)));
    }
}
