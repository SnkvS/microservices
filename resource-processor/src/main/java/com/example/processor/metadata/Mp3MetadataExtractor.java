package com.example.processor.metadata;

import java.io.ByteArrayInputStream;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

@Component
public class Mp3MetadataExtractor {

    private final Tika detector = new Tika();

    private final AutoDetectParser parser = new AutoDetectParser();

    private static String first(Metadata metadata, String... names) {
        for (String name : names) {
            String value = metadata.get(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    public ExtractedMetadata extract(byte[] data) {
        if (data == null || data.length == 0 || !"audio/mpeg".equals(detector.detect(data)))
            throw new IllegalArgumentException("Invalid MP3 file");
        Metadata metadata = new Metadata();
        try (var input = new ByteArrayInputStream(data)) {
            parser.parse(input, new BodyContentHandler(-1), metadata, new ParseContext());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid MP3 file", ex);
        }
        String duration = first(metadata, "xmpDM:duration");
        String year = first(metadata, "xmpDM:releaseDate", "year", "date", "dc:date");
        if (duration == null) throw new IllegalArgumentException("Invalid MP3 file: missing duration");
        double durationSeconds;
        try {
            durationSeconds = Double.parseDouble(duration);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid MP3 file: invalid duration", ex);
        }
        if (!Double.isFinite(durationSeconds) || durationSeconds < 0 || durationSeconds >= 6000)
            throw new IllegalArgumentException("Invalid MP3 file: invalid duration");
        int seconds = (int) Math.floor(durationSeconds);
        String name = first(metadata, "dc:title", "title");
        String artist = first(metadata, "xmpDM:artist", "Author", "artist");
        String album = first(metadata, "xmpDM:album", "album");
        if (name == null || artist == null || album == null || year == null)
            throw new IllegalArgumentException("Invalid MP3 file: missing required tags");
        return new ExtractedMetadata(name, artist, album, "%02d:%02d".formatted(seconds / 60, seconds % 60), year);
    }
}
