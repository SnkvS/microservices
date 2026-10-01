package com.example.resource;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class Mp3MetadataExtractorTest {
    @Test void extractsSuppliedMp3TagsWithoutChangingTheirValues() throws Exception {
        try (var zip = new ZipFile(Path.of("..", "sample-mp3-file", "mp3.zip").toFile())) {
            var entry = zip.getEntry("mp3/valid-sample-with-required-tags.mp3");
            byte[] data = zip.getInputStream(entry).readAllBytes();
            SongMetadata song = new Mp3MetadataExtractor().extract(data);
            assertEquals("Test Title", song.name());
            assertEquals("Test Artist", song.artist());
            assertEquals("Test Album", song.album());
            assertEquals("00:07", song.duration());
            assertEquals("2025", song.year());
        }
    }

    @Test void rejectsMissingRequiredTags() throws Exception {
        try (var zip = new ZipFile(Path.of("..", "sample-mp3-file", "mp3.zip").toFile())) {
            var entry = zip.getEntry("mp3/invalid-sample-with-missed-tags.mp3");
            byte[] data = zip.getInputStream(entry).readAllBytes();
            assertThrows(IllegalArgumentException.class, () -> new Mp3MetadataExtractor().extract(data));
        }
    }
}
