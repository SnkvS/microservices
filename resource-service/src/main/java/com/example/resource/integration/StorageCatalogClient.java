package com.example.resource.integration;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.*;
import java.util.*;

public class StorageCatalogClient {
    private static final com.fasterxml.jackson.databind.ObjectReader JSON=new com.fasterxml.jackson.databind.ObjectMapper()
            .reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final RestClient http;
    private final CircuitBreaker breaker;
    private final List<StorageDefinition> fallback;
    public StorageCatalogClient(RestClient http,CircuitBreaker breaker,List<StorageDefinition> fallback) {
        this.http=http; this.breaker=breaker; this.fallback=validate(fallback);
    }
    public List<StorageDefinition> list() {
        try { return breaker.executeSupplier(this::lookup); }
        catch (RuntimeException failure) {
            if (!(failure instanceof CallNotPermittedException) && !isOutage(failure)) throw failure;
            LoggerFactory.getLogger(getClass()).warn("Using configured Storage fallback: {}",failure.getClass().getSimpleName());
            return fallback;
        }
    }
    public StorageDefinition select(StorageType type) {
        return list().stream().filter(s->s.storageType()==type).min(Comparator.comparingLong(StorageDefinition::id))
                .orElseThrow(()->new StorageCatalogException("Missing storage type "+type));
    }
    private List<StorageDefinition> lookup() {
        try {
            String response=http.get().uri("/storages").retrieve().body(String.class);
            if (response==null) throw new StorageCatalogException("Storage catalog is missing");
            var root=JSON.readTree(response);
            if (root==null || !root.isArray()) throw new StorageCatalogException("Storage response must be an array");
            var definitions=new ArrayList<StorageDefinition>();
            for (var entry:root) {
                var id=entry.get("id"); var type=entry.get("storageType");
                var bucket=entry.get("bucket"); var path=entry.get("path");
                if (id==null || !id.isIntegralNumber() || !id.canConvertToLong()
                        || type==null || !type.isTextual() || bucket==null || !bucket.isTextual()
                        || path==null || !path.isTextual()) throw new StorageCatalogException("Invalid Storage field types");
                StorageType storageType;
                try { storageType=StorageType.valueOf(type.textValue()); }
                catch (IllegalArgumentException unknown) { throw new StorageCatalogException("Unknown storage type",unknown); }
                definitions.add(new StorageDefinition(id.longValue(),storageType,bucket.textValue(),path.textValue()));
            }
            return validate(definitions);
        } catch (java.io.IOException malformed) {
            throw new StorageCatalogException("Invalid Storage response JSON",malformed);
        } catch (ResourceAccessException failure) { throw failure; }
        catch (RestClientResponseException failure) {
            if (isOutage(failure)) throw failure;
            throw new StorageCatalogException("Storage catalog rejected the request",failure);
        } catch (RestClientException failure) { throw new StorageCatalogException("Invalid Storage response",failure); }
    }
    static List<StorageDefinition> validate(List<StorageDefinition> definitions) {
        if (definitions==null) throw new StorageCatalogException("Storage catalog is missing");
        var ids=new HashSet<Long>(); var locations=new HashSet<com.example.resource.storage.StoredResourceLocation>(); var types=EnumSet.noneOf(StorageType.class);
        for (var definition:definitions) {
            if (definition==null || definition.id()<=0 || definition.storageType()==null || !ids.add(definition.id()))
                throw new StorageCatalogException("Invalid Storage definition");
            var location=definition.locationFor("validation.mp3");
            if (!locations.add(location)) throw new StorageCatalogException("Duplicate Storage location");
            types.add(definition.storageType());
        }
        if (types.size()!=2) throw new StorageCatalogException("Both storage types are required");
        return List.copyOf(definitions);
    }
    public static boolean isOutage(Throwable failure) {
        if (failure instanceof ResourceAccessException) return true;
        if (failure instanceof RestClientResponseException response)
            return response.getStatusCode().is5xxServerError() || response.getStatusCode().value()==408
                    || response.getStatusCode().value()==429;
        return false;
    }
}
