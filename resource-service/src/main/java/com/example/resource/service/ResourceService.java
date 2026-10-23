package com.example.resource.service;

import com.example.resource.integration.SongClient;
import com.example.resource.messaging.ResourceUploadedPublisher;
import com.example.resource.persistence.ResourceEntity;
import com.example.resource.persistence.ResourceRepository;
import com.example.resource.storage.ResourceStorage;
import com.example.resource.storage.StoredResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ResourceService {

    private static final Logger log = LoggerFactory.getLogger(ResourceService.class);

    private final ResourceRepository repository;

    private final Mp3FileValidator validator;

    private final ResourceStorage storage;

    private final SongClient songs;

    private final TransactionTemplate uploadTransaction;

    private final ResourceUploadedPublisher publisher;

    public ResourceService(ResourceRepository repository, Mp3FileValidator validator, ResourceStorage storage,
                           SongClient songs, PlatformTransactionManager transactions, ResourceUploadedPublisher publisher) {
        this.repository = repository;
        this.validator = validator;
        this.storage = storage;
        this.songs = songs;
        this.publisher = publisher;
        this.uploadTransaction = new TransactionTemplate(transactions);
        this.uploadTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public long upload(String contentType, byte[] data) {
        ResourceValidation.contentType(contentType);
        validator.validate(data);
        StoredResourceLocation location = storage.put("resources/" + UUID.randomUUID() + ".mp3", data);
        long resourceId;
        try {
            resourceId = Objects.requireNonNull(uploadTransaction.execute(status ->
                    repository.saveAndFlush(new ResourceEntity(location.bucket(), location.key())).id));
        } catch (RuntimeException ex) {
            try {
                storage.delete(location);
            } catch (RuntimeException cleanupFailure) {
                ex.addSuppressed(cleanupFailure);
                log.error("Could not remove orphan resource {}/{}", location.bucket(), location.key(), cleanupFailure);
            }
            throw ex;
        }
        publisher.publish(resourceId);
        return resourceId;
    }

    @Transactional(readOnly = true)
    public byte[] get(String rawId) {
        long id = ResourceValidation.id(rawId);
        ResourceEntity resource = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(id));
        return storage.get(new StoredResourceLocation(resource.storageBucket, resource.objectKey));
    }

    @Transactional
    public List<Long> delete(String rawIds) {
        List<Long> ids = List.copyOf(new LinkedHashSet<>(ResourceValidation.ids(rawIds)));
        Map<Long, ResourceEntity> resources = repository.findAllById(ids).stream()
                .collect(Collectors.toMap(resource -> resource.id, resource -> resource));
        List<Long> deleted = ids.stream().filter(resources::containsKey).toList();
        if (deleted.isEmpty()) return deleted;
        for (long id : deleted) {
            ResourceEntity resource = resources.get(id);
            storage.delete(new StoredResourceLocation(resource.storageBucket, resource.objectKey));
        }
        repository.deleteAllByIdInBatch(deleted);
        repository.flush();
        songs.delete(deleted);
        return deleted;
    }
}
