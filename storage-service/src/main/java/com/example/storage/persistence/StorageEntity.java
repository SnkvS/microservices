package com.example.storage.persistence;
import com.example.storage.api.*;
import com.example.storage.model.StorageType;
import jakarta.persistence.*;
@Entity
@Table(name="storages", uniqueConstraints=@UniqueConstraint(columnNames={"bucket","path"}))
public class StorageEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Enumerated(EnumType.STRING) @Column(name="storage_type", nullable=false, length=16) public StorageType storageType;
    @Column(nullable=false, length=63) public String bucket;
    @Column(nullable=false, length=900) public String path;
    protected StorageEntity() {}
    public StorageEntity(CreateStorageRequest request) {
        storageType=request.storageType(); bucket=request.bucket(); path=request.path();
    }
    public StorageDto toDto() { return new StorageDto(id,storageType,bucket,path); }
}
