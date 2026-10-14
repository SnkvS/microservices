package com.example.resource.service;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

@Component
public class Mp3FileValidator {

    private final Tika detector = new Tika();

    private final AutoDetectParser parser = new AutoDetectParser();

    public void validate(byte[] data) {
        if (data == null || data.length == 0 || !"audio/mpeg".equals(detector.detect(data)))
            throw new IllegalArgumentException("Invalid MP3 file");
        Metadata metadata = new Metadata();
        try (var input = new ByteArrayInputStream(data)) {
            parser.parse(input, new BodyContentHandler(-1), metadata, new ParseContext());
            double duration = Double.parseDouble(metadata.get("xmpDM:duration"));
            if (!Double.isFinite(duration) || duration < 0) throw new IllegalArgumentException("Invalid duration");
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid MP3 file", ex);
        }
    }
}
