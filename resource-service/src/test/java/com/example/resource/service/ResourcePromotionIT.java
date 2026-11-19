package com.example.resource.service;
import com.example.resource.integration.*;
import com.example.resource.messaging.ResourceUploadedPublisher;
import com.example.resource.persistence.*;
import com.example.resource.storage.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import java.util.UUID;
import java.util.concurrent.*;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
    "eureka.client.enabled=false","spring.rabbitmq.listener.simple.auto-startup=false","spring.rabbitmq.dynamic=false"})
class ResourcePromotionIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine").withInitScript("database/init.sql");
    @Container static final LocalStackContainer S3_CONTAINER=new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0")).withServices(S3);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl); r.add("spring.datasource.username",DB::getUsername); r.add("spring.datasource.password",DB::getPassword);
        r.add("resource.storage.endpoint",()->S3_CONTAINER.getEndpointOverride(S3).toString());
        r.add("resource.storage.region",S3_CONTAINER::getRegion);
        r.add("resource.storage.access-key",S3_CONTAINER::getAccessKey); r.add("resource.storage.secret-key",S3_CONTAINER::getSecretKey);
    }
    @Autowired ResourceRepository repository;
    @Autowired ResourcePromotionService promotion;
    @Autowired ResourceService resources;
    @Autowired PlatformTransactionManager transactions;
    @Autowired S3Client s3;
    @MockitoSpyBean ResourceStorage storage;
    @MockitoBean StorageCatalogClient catalog;
    @MockitoBean SongClient songs;
    @MockitoBean ResourceUploadedPublisher publisher;
    final byte[] bytes={0,1,42,(byte)255};
    @BeforeEach void setup() {
        repository.deleteAllInBatch();
        for(String bucket:new String[]{"resources-staging","resources-permanent"})
            s3.createBucket(r->r.bucket(bucket));
        when(catalog.select(StorageType.PERMANENT)).thenReturn(new StorageDefinition(2,StorageType.PERMANENT,"resources-permanent","/archive"));
    }
    ResourceEntity recording() {
        var row=new ResourceEntity("resources-staging","files/"+UUID.randomUUID()+".mp3");
        storage.put(location(row),bytes);
        return repository.saveAndFlush(row);
    }
    StoredResourceLocation location(ResourceEntity row) { return new StoredResourceLocation(row.storageBucket,row.objectKey); }
    ResourceEntity reload(long id) { return repository.findById(id).orElseThrow(); }

    @Test void realCopyMovesBytesAndDuplicateNotificationDoesNotCopyAgain() {
        var row=recording(); var source=location(row);
        promotion.promote(row.id); var done=reload(row.id);
        assertThat(done.state).isEqualTo(ResourceState.PERMANENT);
        assertThat(done.objectKey).startsWith("archive/");
        assertThat(done.cleanupSourceBucket).isNull();
        assertThat(storage.get(location(done))).containsExactly(bytes);
        assertThat(s3.headObject(r->r.bucket(done.storageBucket).key(done.objectKey)).contentType()).isEqualTo("audio/mpeg");
        assertThatThrownBy(()->storage.get(source)).isInstanceOf(ResourceStorageException.class);
        clearInvocations(storage,catalog);
        promotion.promote(row.id);
        verifyNoInteractions(storage,catalog);
    }
    @Test void copyFailureKeepsDurablePinAndFreshServiceFinishes() {
        var row=recording(); var source=location(row);
        doThrow(new ResourceStorageException("copy",new IllegalStateException("offline"))).doCallRealMethod().when(storage).copy(eq(source),any());
        assertThatThrownBy(()->promotion.promote(row.id)).isInstanceOf(ResourceStorageException.class);
        var pending=reload(row.id);
        assertThat(pending.state).isEqualTo(ResourceState.STAGING);
        assertThat(pending.promotionTargetBucket).isEqualTo("resources-permanent");
        assertThat(storage.get(source)).containsExactly(bytes);
        clearInvocations(catalog);
        new ResourcePromotionService(repository,storage,catalog,transactions).promote(row.id);
        assertThat(reload(row.id).state).isEqualTo(ResourceState.PERMANENT);
        verifyNoInteractions(catalog);
    }
    @Test void failedCommitAfterCopyRetainsSourceAndRecoversAtPinnedTarget() {
        var row=recording(); var source=location(row);
        var failSecondCommit=new PlatformTransactionManager() {
            int commits;
            public TransactionStatus getTransaction(TransactionDefinition d) { return transactions.getTransaction(d); }
            public void rollback(TransactionStatus s) { transactions.rollback(s); }
            public void commit(TransactionStatus s) {
                if (++commits==2) { transactions.rollback(s); throw new IllegalStateException("injected commit failure"); }
                transactions.commit(s);
            }
        };
        assertThatThrownBy(()->new ResourcePromotionService(repository,storage,catalog,failSecondCommit).promote(row.id))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("commit failure");
        var pending=reload(row.id);
        assertThat(pending.state).isEqualTo(ResourceState.STAGING);
        assertThat(pending.storageBucket).isEqualTo(source.bucket());
        assertThat(pending.promotionTargetBucket).isEqualTo("resources-permanent");
        assertThat(storage.get(source)).containsExactly(bytes);
        assertThat(storage.get(new StoredResourceLocation(pending.promotionTargetBucket,pending.promotionTargetKey))).containsExactly(bytes);
        promotion.promote(row.id);
        assertThat(reload(row.id).state).isEqualTo(ResourceState.PERMANENT);
    }
    @Test void cleanupFailureSurvivesRestartWithoutRecopyOrCatalog() {
        var row=recording(); var source=location(row);
        doThrow(new ResourceStorageException("delete",new IllegalStateException("offline"))).doCallRealMethod().when(storage).delete(source);
        assertThatThrownBy(()->promotion.promote(row.id)).isInstanceOf(ResourceStorageException.class);
        var done=reload(row.id);
        assertThat(done.state).isEqualTo(ResourceState.PERMANENT);
        assertThat(done.cleanupSourceBucket).isEqualTo(source.bucket());
        assertThat(storage.get(location(done))).containsExactly(bytes);
        clearInvocations(storage,catalog);
        new ResourcePromotionService(repository,storage,catalog,transactions).promote(row.id);
        verify(storage,never()).copy(any(),any()); verifyNoInteractions(catalog);
        assertThat(reload(row.id).cleanupSourceBucket).isNull();
    }
    @Test void getReadLockKeepsSourceAliveUntilDownloadCompletes() throws Exception {
        var row=recording(); var source=location(row);
        var reading=new CountDownLatch(1); var release=new CountDownLatch(1); var selected=new CountDownLatch(1); var copying=new CountDownLatch(1);
        doAnswer(call->{ reading.countDown(); if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("download blocked"); return call.callRealMethod(); }).when(storage).get(source);
        when(catalog.select(StorageType.PERMANENT)).thenAnswer(call->{selected.countDown(); return new StorageDefinition(2,StorageType.PERMANENT,"resources-permanent","/archive");});
        doAnswer(call->{copying.countDown(); return call.callRealMethod();}).when(storage).copy(eq(source),any());
        var executor=Executors.newFixedThreadPool(2);
        try {
            var download=executor.submit(()->resources.get(row.id.toString()));
            assertThat(reading.await(10,TimeUnit.SECONDS)).isTrue();
            var promote=executor.submit(()->promotion.promote(row.id));
            assertThat(selected.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(copying.await(250,TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            assertThat(download.get(15,TimeUnit.SECONDS)).containsExactly(bytes);
            promote.get(15,TimeUnit.SECONDS);
            assertThat(reload(row.id).state).isEqualTo(ResourceState.PERMANENT);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void deleteRacingPromotionRemovesEveryLocationAndCannotResurrect() throws Exception {
        var row=recording(); var source=location(row);
        var copying=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call->{copying.countDown(); if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("copy blocked"); return call.callRealMethod();}).when(storage).copy(eq(source),any());
        var executor=Executors.newFixedThreadPool(2);
        try {
            var promote=executor.submit(()->promotion.promote(row.id));
            assertThat(copying.await(10,TimeUnit.SECONDS)).isTrue();
            var delete=executor.submit(()->resources.delete(row.id.toString()));
            assertThatThrownBy(()->delete.get(250,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            promote.get(15,TimeUnit.SECONDS); assertThat(delete.get(15,TimeUnit.SECONDS)).containsExactly(row.id);
            promotion.promote(row.id);
            assertThat(repository.findById(row.id)).isEmpty();
            assertThat(s3.listObjectsV2(r->r.bucket("resources-staging").prefix(source.key())).contents()).isEmpty();
            String filename=source.key().substring(source.key().lastIndexOf('/')+1);
            assertThat(s3.listObjectsV2(r->r.bucket("resources-permanent").prefix("archive/"+filename)).contents()).isEmpty();
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void simultaneousIndependentPromotionsUseOnePinnedTargetAndCopyOnlyOnce() throws Exception {
        var row=recording(); var source=location(row);
        var selected=new CountDownLatch(2);
        var choices=new java.util.concurrent.atomic.AtomicInteger();
        when(catalog.select(StorageType.PERMANENT)).thenAnswer(call->{
            int choice=choices.incrementAndGet();
            selected.countDown();
            if(!selected.await(10,TimeUnit.SECONDS)) throw new AssertionError("Both catalog reads must overlap");
            return new StorageDefinition(choice,StorageType.PERMANENT,"resources-permanent","/archive-"+choice);
        });
        var secondInstance=new ResourcePromotionService(repository,storage,catalog,transactions);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(()->promotion.promote(row.id));
            var second=executor.submit(()->secondInstance.promote(row.id));
            first.get(20,TimeUnit.SECONDS); second.get(20,TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        var done=reload(row.id);
        assertThat(done.state).isEqualTo(ResourceState.PERMANENT);
        assertThat(done.objectKey).matches("archive-[12]/.*");
        assertThat(done.promotionTargetBucket).isNull(); assertThat(done.cleanupSourceBucket).isNull();
        assertThat(storage.get(location(done))).containsExactly(bytes);
        verify(storage,times(1)).copy(eq(source),eq(location(done)));
        verify(storage,times(1)).delete(source);
        assertThatThrownBy(()->storage.get(source)).isInstanceOf(ResourceStorageException.class);
    }

    @Test void migrationIsRepeatableAndPreservesLegacyLocation() throws Exception {
        try(var connection=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
            var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA legacy");
            try {
                statement.execute("SET search_path TO legacy");
                statement.execute("CREATE TABLE resources(id BIGINT PRIMARY KEY,storage_bucket VARCHAR(255) NOT NULL,object_key VARCHAR(1024) NOT NULL)");
                statement.execute("INSERT INTO resources VALUES(42,'old-bucket','old/path.mp3')");
                String migration;
                try(var stream=getClass().getResourceAsStream("/database/migrations/006-resource-processing-state.sql")) {
                    migration=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                }
                statement.execute(migration); statement.execute(migration);
                try(var result=statement.executeQuery("SELECT state,storage_bucket,object_key,promotion_target_bucket,cleanup_source_bucket FROM resources")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("PERMANENT");
                    assertThat(result.getString(2)).isEqualTo("old-bucket");
                    assertThat(result.getString(3)).isEqualTo("old/path.mp3");
                    assertThat(result.getString(4)).isNull(); assertThat(result.getString(5)).isNull();
                }
            } finally { statement.execute("DROP SCHEMA legacy CASCADE"); }
        }
    }
}
