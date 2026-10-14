package com.example.resource.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Mp3FileValidatorTest {

    private final Mp3FileValidator validator = new Mp3FileValidator();

    static byte[] fixture(String name) throws IOException {
        try (var stream = Mp3FileValidatorTest.class.getResourceAsStream("/mp3/" + name)) {
            if (stream == null) throw new IOException("Missing test MP3 " + name);
            return stream.readAllBytes();
        }
    }

    @Test
    void acceptsValidMp3EvenWhenSongTagsAreMissing() throws IOException {
        assertThatCode(() -> validator.validate(fixture("valid.mp3"))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(fixture("missing-tags.mp3"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsEmptyFakeAndTruncatedMp3Content() {
        for (byte[] bytes : new byte[][]{new byte[0], "not an MP3".getBytes(), new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0}}) {
            assertThatThrownBy(() -> validator.validate(bytes)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
