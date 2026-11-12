package com.example.song.persistence;

import com.example.song.api.SongDto;
import com.example.song.service.SongConflictException;
import com.example.song.service.SongNotFoundException;
import com.example.song.service.SongService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "eureka.client.enabled=false")
class SongPersistenceIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withInitScript("database/init.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired SongService service;
    @Autowired SongRepository repository;
    private final SongDto song = new SongDto(42L, "Title", "Artist", "Album", "00:07", "2025");

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAllInBatch();
    }

    @Test
    void createsAndReadsAllMetadataUsingTheProductionSchema() {
        assertThat(service.create(song)).isEqualTo(42L);
        assertThat(repository.findById(42L)).get().extracting(SongEntity::toDto).isEqualTo(song);
        assertThat(service.get("42")).isEqualTo(song);
    }

    @Test
    void duplicateCreationPreservesTheOriginalRecord() {
        service.create(song);
        assertThatThrownBy(() -> service.create(new SongDto(42L, "Other", "Artist", "Album", "00:07", "2025")))
                .isInstanceOf(SongConflictException.class);
        assertThat(service.get("42")).isEqualTo(song);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void deletionCommitsAndRepeatedDeletionIsEmpty() {
        service.create(song);
        assertThat(service.delete("42,42,99")).containsExactly(42L);
        assertThat(repository.findById(42L)).isEmpty();
        assertThatThrownBy(() -> service.get("42")).isInstanceOf(SongNotFoundException.class);
        assertThat(service.delete("42")).isEmpty();
    }
}
