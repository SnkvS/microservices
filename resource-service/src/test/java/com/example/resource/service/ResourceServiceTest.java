package com.example.resource.service;

import com.example.resource.integration.SongClient;
import com.example.resource.messaging.ResourceUploadedPublisher;
import com.example.resource.messaging.ResourcePublicationException;
import com.example.resource.persistence.ResourceEntity;
import com.example.resource.persistence.ResourceRepository;
import com.example.resource.storage.ResourceStorage;
import com.example.resource.storage.ResourceStorageException;
import com.example.resource.storage.StoredResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResourceServiceTest {

    private final ResourceRepository repository = mock(ResourceRepository.class);

    private final ResourceStorage storage = mock(ResourceStorage.class);

    private final SongClient songs = mock(SongClient.class);

    private final ResourceUploadedPublisher publisher = mock(ResourceUploadedPublisher.class);

    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    private final StoredResourceLocation location = new StoredResourceLocation("resources", "resources/test.mp3");

    private final ResourceService service = new ResourceService(repository, new Mp3FileValidator(), storage, songs, transactions, publisher);

    @BeforeEach
    void setup() {
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void uploadStoresOriginalBytesAndLocationWithoutCallingSongService() throws Exception {
        byte[] bytes = Mp3FileValidatorTest.fixture("missing-tags.mp3");
        when(storage.put(anyString(), eq(bytes))).thenReturn(location);
        when(repository.saveAndFlush(any(ResourceEntity.class))).thenAnswer(call -> {
            ResourceEntity resource = call.getArgument(0);
            resource.id = 42L;
            return resource;
        });
        assertThat(service.upload("audio/mpeg", bytes)).isEqualTo(42);
        var key = ArgumentCaptor.forClass(String.class);
        verify(storage).put(key.capture(), eq(bytes));
        assertThat(key.getValue()).matches("resources/[0-9a-f-]{36}\\.mp3");
        var saved = ArgumentCaptor.forClass(ResourceEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().storageBucket).isEqualTo("resources");
        assertThat(saved.getValue().objectKey).isEqualTo("resources/test.mp3");
        var order = inOrder(transactions, publisher);
        order.verify(transactions).commit(any(TransactionStatus.class));
        order.verify(publisher).publish(42L);
        verifyNoInteractions(songs);
    }

    @Test
    void invalidUploadHasNoStorageDatabaseOrSongSideEffects() {
        assertThatThrownBy(() -> service.upload("text/plain", new byte[]{1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upload("audio/mpeg", "invalid".getBytes())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(storage, repository, songs, transactions, publisher);
    }

    @Test
    void removesStoredObjectWhenDatabaseSaveFails() throws Exception {
        byte[] bytes = Mp3FileValidatorTest.fixture("valid.mp3");
        var failure = new IllegalStateException("Database unavailable");
        when(storage.put(anyString(), eq(bytes))).thenReturn(location);
        when(repository.saveAndFlush(any(ResourceEntity.class))).thenThrow(failure);
        assertThatThrownBy(() -> service.upload("audio/mpeg", bytes)).isSameAs(failure);
        verify(storage).delete(location);
        verifyNoInteractions(publisher);
        verifyNoInteractions(songs);
    }

    @Test
    void removesStoredObjectWhenDatabaseCommitFails() throws Exception {
        byte[] bytes = Mp3FileValidatorTest.fixture("valid.mp3");
        var failure = new IllegalStateException("Commit failed");
        when(storage.put(anyString(), eq(bytes))).thenReturn(location);
        when(repository.saveAndFlush(any(ResourceEntity.class))).thenAnswer(call -> {
            ResourceEntity resource = call.getArgument(0);
            resource.id = 42L;
            return resource;
        });
        doThrow(failure).when(transactions).commit(any(TransactionStatus.class));
        assertThatThrownBy(() -> service.upload("audio/mpeg", bytes)).isSameAs(failure);
        verify(storage).delete(location);
        verifyNoInteractions(publisher);
    }

    @Test
    void publicationFailureRetainsCommittedResourceAndStorage() throws Exception {
        byte[] bytes = Mp3FileValidatorTest.fixture("valid.mp3");
        when(storage.put(anyString(), eq(bytes))).thenReturn(location);
        when(repository.saveAndFlush(any(ResourceEntity.class))).thenAnswer(call -> {
            ResourceEntity resource = call.getArgument(0);
            resource.id = 42L;
            return resource;
        });
        var failure = new ResourcePublicationException(42, new IllegalStateException("offline"));
        doThrow(failure).when(publisher).publish(42);
        assertThatThrownBy(() -> service.upload("audio/mpeg", bytes)).isSameAs(failure);
        verify(transactions).commit(any());
        verify(storage, never()).delete(any());
    }

    @Test
    void failedCompensationDoesNotMaskTheDatabaseFailure() throws Exception {
        byte[] bytes = Mp3FileValidatorTest.fixture("valid.mp3");
        var failure = new IllegalStateException("Save failed");
        var cleanup = new ResourceStorageException("deletion", new IllegalStateException("Offline"));
        when(storage.put(anyString(), eq(bytes))).thenReturn(location);
        when(repository.saveAndFlush(any(ResourceEntity.class))).thenThrow(failure);
        doThrow(cleanup).when(storage).delete(location);
        assertThatThrownBy(() -> service.upload("audio/mpeg", bytes)).isSameAs(failure);
        assertThat(failure.getSuppressed()).containsExactly(cleanup);
    }

    @Test
    void failedObjectUploadDoesNotCreateADatabaseRecord() throws Exception {
        byte[] bytes = Mp3FileValidatorTest.fixture("valid.mp3");
        var failure = new ResourceStorageException("upload", new IllegalStateException("Offline"));
        when(storage.put(anyString(), eq(bytes))).thenThrow(failure);
        assertThatThrownBy(() -> service.upload("audio/mpeg", bytes)).isSameAs(failure);
        verifyNoInteractions(repository, songs, transactions);
    }

    @Test
    void downloadUsesPersistedLocationAndReturnsExactBytes() {
        when(repository.findById(42L)).thenReturn(Optional.of(resource(42L)));
        byte[] bytes = {0, (byte) 255, 42};
        when(storage.get(location)).thenReturn(bytes);
        assertThat(service.get("42")).isEqualTo(bytes);
        verifyNoInteractions(songs);
    }

    @Test
    void distinguishesUnknownResourceFromAMissingStorageObject() {
        when(repository.findById(42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("42")).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(storage);
        when(repository.findById(42L)).thenReturn(Optional.of(resource(42L)));
        var failure = new ResourceStorageException("download", new IllegalStateException("NoSuchKey"));
        when(storage.get(location)).thenThrow(failure);
        assertThatThrownBy(() -> service.get("42")).isSameAs(failure);
    }

    @Test
    void deletionIgnoresDuplicatesAndUnknownIdsAndCleansUpInOrder() {
        when(repository.findAllById(List.of(42L, 99L))).thenReturn(List.of(resource(42L)));
        assertThat(service.delete("42,42,99")).containsExactly(42L);
        var sequence = inOrder(storage, repository, songs);
        sequence.verify(repository).findAllById(List.of(42L, 99L));
        sequence.verify(storage).delete(location);
        sequence.verify(repository).deleteAllByIdInBatch(List.of(42L));
        sequence.verify(repository).flush();
        sequence.verify(songs).delete(List.of(42L));
        verify(storage, times(1)).delete(location);
    }

    @Test
    void storageDeletionFailureStopsDatabaseAndSongDeletion() {
        when(repository.findAllById(List.of(42L))).thenReturn(List.of(resource(42L)));
        var failure = new ResourceStorageException("deletion", new IllegalStateException("Offline"));
        doThrow(failure).when(storage).delete(location);
        assertThatThrownBy(() -> service.delete("42")).isSameAs(failure);
        verify(repository, never()).deleteAllByIdInBatch(any());
        verifyNoInteractions(songs);
    }

    @Test
    void databaseDeletionFailureStopsSongDeletion() {
        when(repository.findAllById(List.of(42L))).thenReturn(List.of(resource(42L)));
        var failure = new IllegalStateException("Delete failed");
        doThrow(failure).when(repository).flush();
        assertThatThrownBy(() -> service.delete("42")).isSameAs(failure);
        verifyNoInteractions(songs);
    }

    @Test
    void deletingOnlyUnknownIdsDoesNotCallStorageOrSongService() {
        assertThat(service.delete("99")).isEmpty();
        verifyNoInteractions(storage, songs);
        verify(repository, never()).deleteAllByIdInBatch(any());
    }

    private ResourceEntity resource(long id) {
        var entity = new ResourceEntity(location.bucket(), location.key());
        entity.id = id;
        return entity;
    }
}
