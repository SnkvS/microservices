package com.example.song.service;

import com.example.song.api.SongDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class SongValidationTest {

    @Test
    void acceptsValidMetadataIncludingBoundaryYears() {
        for (String year : new String[]{"1900", "2099"}) {
            assertThat(SongValidation.validate(new SongDto(42L, "Title", "Artist", "Album", "00:59", year))).isEmpty();
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "1:23", "01:60", "-1:30", "01:01:01"})
    void rejectsInvalidDurations(String duration) {
        assertThat(SongValidation.validate(new SongDto(42L, "Title", "Artist", "Album", duration, "2025")))
                .containsKey("duration");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"1899", "2100", "25", "2025.0", "abcd"})
    void rejectsInvalidYears(String year) {
        assertThat(SongValidation.validate(new SongDto(42L, "Title", "Artist", "Album", "00:07", year)))
                .containsKey("year");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "0", "-1", "1.5", "01", "9223372036854775808"})
    void rejectsInvalidIds(String id) {
        assertThatThrownBy(() -> SongValidation.id(id)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsAllMissingFieldsAndRejectsOversizedText() {
        assertThat(SongValidation.validate(new SongDto(null, null, null, null, null, null)))
                .containsOnlyKeys("id", "name", "artist", "album", "duration", "year");
        assertThat(SongValidation.validate(new SongDto(42L, " ", "x".repeat(101), "Album", "00:07", "2025")))
                .containsOnlyKeys("name", "artist");
    }

    @Test
    void validatesCsvIncludingEmptyItemsAndMaximumLength() {
        assertThat(SongValidation.ids("42,43")).containsExactly(42L, 43L);
        for (String invalid : new String[]{"42,", ",42", "42,,43", "42,0", "1,".repeat(101)}) {
            assertThatThrownBy(() -> SongValidation.ids(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
