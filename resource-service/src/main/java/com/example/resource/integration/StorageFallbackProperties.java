package com.example.resource.integration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;
@Component
public class StorageFallbackProperties {
    private final List<StorageDefinition> definitions;
    public StorageFallbackProperties(@Value("${storage.fallback.staging.bucket:resources-staging}") String stagingBucket,
                                     @Value("${storage.fallback.staging.path:/files}") String stagingPath,
                                     @Value("${storage.fallback.permanent.bucket:resources-permanent}") String permanentBucket,
                                     @Value("${storage.fallback.permanent.path:/files}") String permanentPath) {
        definitions=StorageCatalogClient.validate(List.of(
                new StorageDefinition(1,StorageType.STAGING,stagingBucket,stagingPath),
                new StorageDefinition(2,StorageType.PERMANENT,permanentBucket,permanentPath)));
    }
    public List<StorageDefinition> definitions() { return definitions; }
}
