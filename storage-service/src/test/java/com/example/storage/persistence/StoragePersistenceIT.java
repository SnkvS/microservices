package com.example.storage.persistence;
import com.example.storage.api.CreateStorageRequest;
import com.example.storage.model.StorageType;
import com.example.storage.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="eureka.client.enabled=false")
class StoragePersistenceIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine").withInitScript("database/init.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl); r.add("spring.datasource.username",DB::getUsername); r.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired StorageService service;
    @Autowired StorageRepository repository;
    @Autowired StorageInitializer initializer;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void clean() { repository.deleteAllInBatch(); initializer.run(new DefaultApplicationArguments()); }
    @Test void seedsBothTypesAndPreservesCustomDefinitions() {
        assertThat(service.list()).extracting(s->s.storageType()).containsExactly(StorageType.STAGING,StorageType.PERMANENT);
        var custom=new CreateStorageRequest(StorageType.STAGING,"custom-bucket","/extra/");
        long id=service.create(custom);
        initializer.run(new DefaultApplicationArguments());
        assertThat(service.list()).hasSize(3);
        assertThat(service.list().get(2).path()).isEqualTo("/extra");
        assertThatThrownBy(()->service.create(custom)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.delete(id+","+id+",99999")).containsExactly(id);
        assertThat(service.delete(Long.toString(id))).isEmpty();
    }
    @Test void concurrentStartupDoesNotDuplicateSeeds() throws Exception {
        repository.deleteAllInBatch();
        var executor=Executors.newFixedThreadPool(2);
        try {
            var a=executor.submit(()->initializer.run(new DefaultApplicationArguments()));
            var b=executor.submit(()->initializer.run(new DefaultApplicationArguments()));
            a.get(15,TimeUnit.SECONDS); b.get(15,TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertThat(service.list()).hasSize(2);
    }
    @Test void databaseEnforcesTypeLocationAndBootstrapConflictRules() {
        assertThatThrownBy(()->jdbc.update("INSERT INTO storages(storage_type,bucket,path) VALUES ('OTHER','valid-bucket','/files')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        repository.deleteAllInBatch();
        jdbc.update("INSERT INTO storages(storage_type,bucket,path) VALUES ('PERMANENT','resources-staging','/files')");
        assertThatThrownBy(()->initializer.run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts");
        assertThat(repository.count()).isEqualTo(1);
    }
}
