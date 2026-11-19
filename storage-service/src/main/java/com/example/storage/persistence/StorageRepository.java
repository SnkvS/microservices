package com.example.storage.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface StorageRepository extends JpaRepository<StorageEntity,Long> {
    List<StorageEntity> findAllByOrderByIdAsc();
    boolean existsByBucketAndPath(String bucket,String path);
}
