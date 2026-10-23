package com.example.resource.service;

import com.example.resource.integration.SongClient;
import com.example.resource.messaging.ResourceUploadedPublisher;
import com.example.resource.persistence.ResourceEntity;
import com.example.resource.persistence.ResourceRepository;
import com.example.resource.storage.ResourceStorage;
import com.example.resource.storage.ResourceStorageException;
import com.example.resource.storage.StoredResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClientException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "eureka.client.enabled=false")
class ResourceDeletionTransactionTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("database/init.sql");

    @Autowired
    ResourceService service;

    @Autowired
    ResourceRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    ResourceStorage storage;

    @MockitoBean
    SongClient songs;

    @MockitoBean
    ResourceUploadedPublisher publisher;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void clearRows() {
        repository.deleteAllInBatch();
    }

    @Test
    void songFailureRollsBackSqlAndRepeatedDeletionCanFinish() {
        ResourceEntity resource = saveResource();
        var location = new StoredResourceLocation(resource.storageBucket, resource.objectKey);
        doThrow(new RestClientException("Song offline")).doNothing().when(songs).delete(List.of(resource.id));

        assertThatThrownBy(() -> service.delete(resource.id.toString())).isInstanceOf(RestClientException.class);
        assertThat(repository.existsById(resource.id)).isTrue();
        verify(storage).delete(location);

        assertThat(service.delete(resource.id.toString())).containsExactly(resource.id);
        assertThat(repository.existsById(resource.id)).isFalse();
        verify(storage, times(2)).delete(location);
        verify(songs, times(2)).delete(List.of(resource.id));
    }

    @Test
    void storageFailureLeavesDatabaseRecordAndDoesNotCallSongs() {
        ResourceEntity resource = saveResource();
        doThrow(new ResourceStorageException("deletion", new IllegalStateException("Offline")))
                .when(storage).delete(new StoredResourceLocation(resource.storageBucket, resource.objectKey));
        assertThatThrownBy(() -> service.delete(resource.id.toString())).isInstanceOf(ResourceStorageException.class);
        assertThat(repository.existsById(resource.id)).isTrue();
        verifyNoInteractions(songs);
    }

    @Test
    void sqlRowsAreDeletedBeforeSongCleanupAndCommittedAfterSuccess() {
        ResourceEntity first = saveResource();
        ResourceEntity second = saveResource();
        doAnswer(call -> {
            assertThat(repository.findAllById(List.of(first.id, second.id))).isEmpty();
            return null;
        }).when(songs).delete(List.of(second.id, first.id));
        assertThat(service.delete(second.id + "," + first.id + "," + second.id + ",99999"))
                .containsExactly(second.id, first.id);
        assertThat(repository.count()).isZero();
        verify(songs).delete(List.of(second.id, first.id));
    }

    @Test
    void databaseSchemaContainsLocationsAndNoBinaryColumn() {
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='resources' order by ordinal_position", String.class))
                .containsExactly("id", "storage_bucket", "object_key");
    }

    private ResourceEntity saveResource() {
        return repository.saveAndFlush(new ResourceEntity("resources", "resources/" + UUID.randomUUID() + ".mp3"));
    }
}
