package com.example.processor.metadata;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Mp3MetadataExtractorTest {

    private final Mp3MetadataExtractor extractor = new Mp3MetadataExtractor();

    @Test
    void extractsSongFieldsFromTheTaggedSampleWithoutAResourceId() throws IOException {
        assertThat(extractor.extract(fixture("valid.mp3")))
                .isEqualTo(new ExtractedMetadata("Test Title", "Test Artist", "Test Album", "00:07", "2025"));
    }

    @Test
    void reportsMissingSongTagsForAValidMp3() throws IOException {
        byte[] bytes = fixture("missing-tags.mp3");
        assertThatThrownBy(() -> extractor.extract(bytes)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required tags");
    }

    @Test
    void rejectsEmptyFakeAndTruncatedMp3Files() {
        for (byte[] bytes : new byte[][]{new byte[0], "invalid".getBytes(), new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0}}) {
            assertThatThrownBy(() -> extractor.extract(bytes)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> extractor.extract(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private byte[] fixture(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/mp3/" + name)) {
            if (stream == null) throw new IOException("Missing test MP3 " + name);
            return stream.readAllBytes();
        }
    }
}
