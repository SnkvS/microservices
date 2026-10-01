package com.example.song;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SongValidationTest {
    @Test void rejectsMissingAndMalformedFields() {
        var errors = SongValidation.validate(new SongDto(null, "", "a", "album", "2:59", "1899"));
        assertTrue(errors.containsKey("id"));
        assertTrue(errors.containsKey("name"));
        assertTrue(errors.containsKey("duration"));
        assertTrue(errors.containsKey("year"));
    }

    @Test void acceptsValidMetadata() {
        assertTrue(SongValidation.validate(new SongDto(1L, "Song", "Artist", "Album", "02:59", "1977")).isEmpty());
    }

    @Test void parsesOnlyPositiveCsvIds() {
        assertEquals(java.util.List.of(1L, 2L), SongValidation.ids("1,2"));
        assertThrows(IllegalArgumentException.class, () -> SongValidation.ids("1,,2"));
        assertThrows(IllegalArgumentException.class, () -> SongValidation.ids("0"));
        assertThrows(IllegalArgumentException.class, () -> SongValidation.ids("1".repeat(201)));
    }
}
