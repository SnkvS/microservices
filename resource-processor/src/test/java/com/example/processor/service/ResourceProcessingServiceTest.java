package com.example.processor.service;

import com.example.processor.integration.ResourceClient;
import com.example.processor.integration.SongClient;
import com.example.processor.integration.SongMetadata;
import com.example.processor.metadata.Mp3MetadataExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ResourceProcessingServiceTest {

    private final ResourceClient resources = mock(ResourceClient.class);
    private final SongClient songs = mock(SongClient.class);
    private final ResourceProcessingService service = new ResourceProcessingService(resources, new Mp3MetadataExtractor(), songs);

    @Test
    void downloadsBinaryExtractsMetadataAndSavesUnderTheResourceId() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/mp3/valid.mp3").readAllBytes();
        when(resources.download(42)).thenReturn(bytes);
        service.process(42);
        verify(songs).save(new SongMetadata(42L, "Test Title", "Test Artist", "Test Album", "00:07", "2025"));
    }

    @Test
    void deletedResourceIsAnObsoleteEvent() {
        when(resources.download(42)).thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "missing", null, null, null));
        service.process(42);
        verifyNoInteractions(songs);
    }

    @Test
    void invalidMetadataIsNotSaved() throws Exception {
        when(resources.download(42)).thenReturn(getClass().getResourceAsStream("/mp3/missing-tags.mp3").readAllBytes());
        assertThatThrownBy(() -> service.process(42)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(songs);
    }

    @Test
    void invalidIdsAreRejectedBeforeHttpCalls() {
        assertThatThrownBy(() -> service.process(0)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(resources, songs);
    }
}
