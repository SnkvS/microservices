package com.example.resource.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "resources", uniqueConstraints = @UniqueConstraint(columnNames = {"storage_bucket", "object_key"}))
public class ResourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "storage_bucket", nullable = false, length = 255)
    public String storageBucket;

    @Column(name = "object_key", nullable = false, length = 1024)
    public String objectKey;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable=false,length=16)
    public ResourceState state=ResourceState.STAGING;
    @Column(name="promotion_target_bucket") public String promotionTargetBucket;
    @Column(name="promotion_target_key",length=1024) public String promotionTargetKey;
    @Column(name="cleanup_source_bucket") public String cleanupSourceBucket;
    @Column(name="cleanup_source_key",length=1024) public String cleanupSourceKey;

    protected ResourceEntity() {
    }

    public ResourceEntity(String storageBucket, String objectKey) {
        this.storageBucket = storageBucket;
        this.objectKey = objectKey;
    }
}
