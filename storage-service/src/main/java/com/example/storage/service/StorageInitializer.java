package com.example.storage.service;
import com.example.storage.api.CreateStorageRequest;
import com.example.storage.persistence.StorageRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
@Component
public class StorageInitializer implements ApplicationRunner {
    private final StorageRepository repository;
    private final StorageDefaults defaults;
    private final JdbcTemplate jdbc;
    public StorageInitializer(StorageRepository repository,StorageDefaults defaults,JdbcTemplate jdbc) {
        this.repository=repository; this.defaults=defaults; this.jdbc=jdbc;
    }
    @Override @Transactional public void run(ApplicationArguments args) {
        for (var storage:repository.findAllByOrderByIdAsc()) {
            try { defaults.checkReservation(new CreateStorageRequest(storage.storageType,storage.bucket,storage.path)); }
            catch (IllegalArgumentException conflict) { throw new IllegalStateException("Stored location conflicts with configured defaults",conflict); }
        }
        seed(defaults.staging); seed(defaults.permanent);
    }
    private void seed(CreateStorageRequest storage) {
        jdbc.update("""
            INSERT INTO storages(storage_type,bucket,path)
            SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM storages WHERE storage_type=?)
            ON CONFLICT (bucket,path) DO NOTHING
            """,storage.storageType().name(),storage.bucket(),storage.path(),storage.storageType().name());
    }
}
