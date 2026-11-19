package com.example.resource.service;
import com.example.resource.persistence.*;
import com.example.resource.storage.*;
import com.example.resource.integration.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ResourcePromotionService {
    private final ResourceRepository repository;
    private final ResourceStorage storage;
    private final StorageCatalogClient catalog;
    private final TransactionTemplate transaction;
    public ResourcePromotionService(ResourceRepository repository,ResourceStorage storage,StorageCatalogClient catalog,
                                    PlatformTransactionManager manager) {
        this.repository=repository; this.storage=storage; this.catalog=catalog;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public void promote(long resourceId) {
        if (resourceId<=0) throw new IllegalArgumentException("Invalid resource ID");
        var snapshot=repository.findById(resourceId).orElse(null);
        if (snapshot==null) return;
        if (snapshot.state==ResourceState.STAGING) {
            StorageDefinition selected=snapshot.promotionTargetBucket==null ? catalog.select(StorageType.PERMANENT) : null;
            transaction.executeWithoutResult(status->{
                var row=repository.findForUpdate(resourceId).orElse(null);
                if (row==null || row.state!=ResourceState.STAGING || row.promotionTargetBucket!=null) return;
                String filename=row.objectKey.substring(row.objectKey.lastIndexOf('/')+1);
                var target=selected.locationFor(filename);
                distinct(current(row),target);
                row.promotionTargetBucket=target.bucket(); row.promotionTargetKey=target.key();
                repository.flush();
            });
            transaction.executeWithoutResult(status->{
                var row=repository.findForUpdate(resourceId).orElse(null);
                if (row==null || row.state!=ResourceState.STAGING) return;
                var source=current(row);
                var target=new StoredResourceLocation(row.promotionTargetBucket,row.promotionTargetKey);
                distinct(source,target);
                storage.copy(source,target);
                row.storageBucket=target.bucket(); row.objectKey=target.key(); row.state=ResourceState.PERMANENT;
                row.cleanupSourceBucket=source.bucket(); row.cleanupSourceKey=source.key();
                row.promotionTargetBucket=null; row.promotionTargetKey=null;
                repository.flush();
            });
        }
        transaction.executeWithoutResult(status->{
            var row=repository.findForUpdate(resourceId).orElse(null);
            if (row==null || row.state!=ResourceState.PERMANENT || row.cleanupSourceBucket==null) return;
            var source=new StoredResourceLocation(row.cleanupSourceBucket,row.cleanupSourceKey);
            distinct(current(row),source);
            storage.delete(source);
            row.cleanupSourceBucket=null; row.cleanupSourceKey=null;
            repository.flush();
        });
    }
    private static StoredResourceLocation current(ResourceEntity row) {
        return new StoredResourceLocation(row.storageBucket,row.objectKey);
    }
    private static void distinct(StoredResourceLocation current,StoredResourceLocation other) {
        if (current.equals(other)) throw new StorageCatalogException("Promotion locations must be distinct");
    }
}
