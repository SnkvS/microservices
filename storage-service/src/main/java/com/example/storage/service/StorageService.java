package com.example.storage.service;
import com.example.storage.api.*;
import com.example.storage.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.stream.Collectors;
@Service
public class StorageService {
    private final StorageRepository repository;
    private final StorageDefaults defaults;
    public StorageService(StorageRepository repository,StorageDefaults defaults) { this.repository=repository; this.defaults=defaults; }
    @Transactional public long create(CreateStorageRequest request) {
        var valid=StorageValidation.storage(request);
        defaults.checkReservation(valid);
        if (repository.existsByBucketAndPath(valid.bucket(),valid.path())) throw new IllegalArgumentException("Storage location already exists");
        return repository.saveAndFlush(new StorageEntity(valid)).id;
    }
    @Transactional(readOnly=true) public List<StorageDto> list() {
        return repository.findAllByOrderByIdAsc().stream().map(StorageEntity::toDto).toList();
    }
    @Transactional public List<Long> delete(String csv) {
        var ids=StorageValidation.ids(csv);
        var existing=repository.findAllById(ids).stream().map(s->s.id).collect(Collectors.toSet());
        var deleted=ids.stream().filter(existing::contains).toList();
        repository.deleteAllByIdInBatch(deleted);
        repository.flush();
        return deleted;
    }
}
