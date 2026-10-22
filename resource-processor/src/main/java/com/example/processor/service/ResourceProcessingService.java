package com.example.processor.service;

import com.example.processor.integration.ResourceClient;
import com.example.processor.integration.SongClient;
import com.example.processor.integration.SongMetadata;
import com.example.processor.metadata.Mp3MetadataExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

@Service
public class ResourceProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ResourceProcessingService.class);

    private final ResourceClient resources;
    private final Mp3MetadataExtractor extractor;
    private final SongClient songs;

    public ResourceProcessingService(ResourceClient resources, Mp3MetadataExtractor extractor, SongClient songs) {
        this.resources = resources;
        this.extractor = extractor;
        this.songs = songs;
    }

    public void process(long resourceId) {
        if (resourceId <= 0) throw new IllegalArgumentException("Invalid resource ID");
        byte[] data;
        try {
            data = resources.download(resourceId);
        } catch (HttpClientErrorException.NotFound deleted) {
            log.info("Skipping event for deleted resource {}", resourceId);
            return;
        }
        var metadata = extractor.extract(data);
        songs.save(new SongMetadata(resourceId, metadata.name(), metadata.artist(), metadata.album(),
                metadata.duration(), metadata.year()));
        log.info("Processed resource {}", resourceId);
    }
}
