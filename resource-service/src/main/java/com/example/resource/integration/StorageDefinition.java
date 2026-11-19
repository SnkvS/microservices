package com.example.resource.integration;
import com.example.resource.storage.StoredResourceLocation;
import java.nio.charset.StandardCharsets;
public record StorageDefinition(long id,StorageType storageType,String bucket,String path) {
    public StoredResourceLocation locationFor(String filename) {
        if (bucket==null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || bucket.contains("..")
                || path==null || !path.startsWith("/") || path.contains("\\")
                || path.getBytes(StandardCharsets.UTF_8).length>900
                || filename==null || filename.isBlank() || filename.contains("/") || filename.contains("\\"))
            throw new StorageCatalogException("Invalid storage location");
        String prefix=path.equals("/") ? "" : path.substring(1);
        if (prefix.endsWith("/")) prefix=prefix.substring(0,prefix.length()-1);
        if (!prefix.isEmpty()) {
            for (String segment:prefix.split("/",-1))
                if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                    throw new StorageCatalogException("Invalid storage prefix");
        }
        String key=prefix.isEmpty() ? filename : prefix+"/"+filename;
        if (key.getBytes(StandardCharsets.UTF_8).length>1024) throw new StorageCatalogException("Object key is too long");
        return new StoredResourceLocation(bucket,key);
    }
}
