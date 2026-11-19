package com.example.storage.service;
import com.example.storage.api.CreateStorageRequest;
import com.example.storage.model.StorageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class StorageDefaults {
    public final CreateStorageRequest staging;
    public final CreateStorageRequest permanent;
    public StorageDefaults(@Value("${storage.defaults.staging.bucket:resources-staging}") String stagingBucket,
                           @Value("${storage.defaults.staging.path:/files}") String stagingPath,
                           @Value("${storage.defaults.permanent.bucket:resources-permanent}") String permanentBucket,
                           @Value("${storage.defaults.permanent.path:/files}") String permanentPath) {
        staging=StorageValidation.storage(new CreateStorageRequest(StorageType.STAGING,stagingBucket,stagingPath));
        permanent=StorageValidation.storage(new CreateStorageRequest(StorageType.PERMANENT,permanentBucket,permanentPath));
        if (sameLocation(staging,permanent)) throw new IllegalStateException("Default storage locations must be distinct");
    }
    public void checkReservation(CreateStorageRequest request) {
        var other=request.storageType()==StorageType.STAGING ? permanent : staging;
        if (sameLocation(request,other)) throw new IllegalArgumentException("Location is reserved for "+other.storageType());
    }
    private boolean sameLocation(CreateStorageRequest a,CreateStorageRequest b) {
        return a.bucket().equals(b.bucket()) && a.path().equals(b.path());
    }
}
