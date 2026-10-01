package com.example.resource;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceService {
    private final ResourceRepository repository;
    private final Mp3MetadataExtractor extractor;
    private final SongClient songs;
    public ResourceService(ResourceRepository repository, Mp3MetadataExtractor extractor, SongClient songs) {
        this.repository = repository; this.extractor = extractor; this.songs = songs;
    }

    @Transactional
    public long upload(String contentType, byte[] data) {
        ResourceValidation.contentType(contentType);
        SongMetadata metadata = extractor.extract(data);
        ResourceEntity resource = repository.saveAndFlush(new ResourceEntity(data));
        songs.create(metadata.withId(resource.id));
        return resource.id;
    }

    @Transactional(readOnly = true)
    public byte[] get(String rawId) {
        long id = ResourceValidation.id(rawId);
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(id)).data;
    }

    @Transactional
    public List<Long> delete(String rawIds) {
        List<Long> deleted = new ArrayList<>();
        for (long id : ResourceValidation.ids(rawIds)) {
            if (repository.existsById(id) && !deleted.contains(id)) deleted.add(id);
        }
        songs.delete(deleted);
        repository.deleteAllByIdInBatch(deleted);
        return deleted;
    }
}
