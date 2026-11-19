package com.example.storage.api;
import com.example.storage.service.StorageService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
@RestController @RequestMapping("/storages")
public class StorageController {
    private final StorageService service;
    public StorageController(StorageService service) { this.service=service; }
    @PostMapping public Map<String,Long> create(@RequestBody CreateStorageRequest request) { return Map.of("id",service.create(request)); }
    @GetMapping public List<StorageDto> list() { return service.list(); }
    @DeleteMapping public List<Long> delete(@RequestParam String id) { return service.delete(id); }
}
