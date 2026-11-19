package com.example.resource.service;
import com.example.resource.persistence.*;
import com.example.resource.storage.*;
import com.example.resource.integration.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ResourcePromotionServiceTest {
    final ResourceRepository repository=mock(ResourceRepository.class);
    final ResourceStorage storage=mock(ResourceStorage.class);
    final StorageCatalogClient catalog=mock(StorageCatalogClient.class);
    final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    final ResourcePromotionService service=new ResourcePromotionService(repository,storage,catalog,transactions);
    final StoredResourceLocation source=new StoredResourceLocation("resources-staging","files/recording.mp3");
    final StoredResourceLocation target=new StoredResourceLocation("resources-permanent","done/recording.mp3");
    ResourceEntity row;
    @BeforeEach void setup() {
        row=new ResourceEntity(source.bucket(),source.key()); row.id=42L; row.state=ResourceState.STAGING;
        when(repository.findById(42L)).thenReturn(Optional.of(row));
        when(repository.findForUpdate(42L)).thenReturn(Optional.of(row));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(catalog.select(StorageType.PERMANENT)).thenReturn(new StorageDefinition(2,StorageType.PERMANENT,target.bucket(),"/done"));
    }
    @Test void copiesThenCommitsPermanentThenCleansSource() {
        doAnswer(call->{
            assertThat(row.state).isEqualTo(ResourceState.STAGING);
            assertThat(row.promotionTargetBucket).isEqualTo(target.bucket());
            return null;
        }).when(storage).copy(source,target);
        doAnswer(call->{
            assertThat(row.state).isEqualTo(ResourceState.PERMANENT);
            assertThat(row.storageBucket).isEqualTo(target.bucket());
            assertThat(row.cleanupSourceBucket).isEqualTo(source.bucket());
            return null;
        }).when(storage).delete(source);
        service.promote(42);
        assertThat(row.state).isEqualTo(ResourceState.PERMANENT);
        assertThat(row.cleanupSourceBucket).isNull();
        assertThat(row.promotionTargetBucket).isNull();
        var order=inOrder(storage,transactions);
        order.verify(transactions).commit(any());
        order.verify(storage).copy(source,target);
        order.verify(transactions).commit(any());
        order.verify(storage).delete(source);
    }
    @Test void copyFailureKeepsPinnedDestinationAndReadableSource() {
        doThrow(new ResourceStorageException("copy",new IllegalStateException("offline"))).when(storage).copy(source,target);
        assertThatThrownBy(()->service.promote(42)).isInstanceOf(ResourceStorageException.class);
        assertThat(row.state).isEqualTo(ResourceState.STAGING);
        assertThat(row.storageBucket).isEqualTo(source.bucket());
        assertThat(row.promotionTargetBucket).isEqualTo(target.bucket());
        verify(storage,never()).delete(any());
    }
    @Test void cleanupFailureCanResumeWithoutCatalogOrAnotherCopy() {
        doThrow(new ResourceStorageException("delete",new IllegalStateException("offline"))).doNothing().when(storage).delete(source);
        assertThatThrownBy(()->service.promote(42)).isInstanceOf(ResourceStorageException.class);
        assertThat(row.state).isEqualTo(ResourceState.PERMANENT);
        assertThat(row.cleanupSourceBucket).isEqualTo(source.bucket());
        clearInvocations(storage,catalog);
        service.promote(42);
        verify(storage).delete(source);
        verify(storage,never()).copy(any(),any());
        verifyNoInteractions(catalog);
        assertThat(row.cleanupSourceBucket).isNull();
    }
    @Test void pinnedDestinationSurvivesCatalogChanges() {
        row.promotionTargetBucket=target.bucket(); row.promotionTargetKey=target.key();
        service.promote(42);
        verifyNoInteractions(catalog);
        verify(storage).copy(source,target);
    }
    @Test void identicalDestinationNeverDeletesTheAuthoritativeObject() {
        when(catalog.select(StorageType.PERMANENT)).thenReturn(new StorageDefinition(2,StorageType.PERMANENT,source.bucket(),"/files"));
        assertThatThrownBy(()->service.promote(42)).isInstanceOf(StorageCatalogException.class);
        assertThat(row.state).isEqualTo(ResourceState.STAGING);
        verifyNoInteractions(storage);
    }
    @Test void invalidRecordedCleanupCannotDeleteCurrentObject() {
        row.state=ResourceState.PERMANENT; row.cleanupSourceBucket=source.bucket(); row.cleanupSourceKey=source.key();
        assertThatThrownBy(()->service.promote(42)).isInstanceOf(StorageCatalogException.class);
        verifyNoInteractions(storage,catalog);
    }
    @Test void missingAndAlreadyCompletedResourcesAreIdempotent() {
        when(repository.findById(42L)).thenReturn(Optional.empty());
        service.promote(42); verifyNoInteractions(storage,catalog);
        when(repository.findById(42L)).thenReturn(Optional.of(row)); row.state=ResourceState.PERMANENT;
        service.promote(42); verifyNoInteractions(storage,catalog);
    }
}
