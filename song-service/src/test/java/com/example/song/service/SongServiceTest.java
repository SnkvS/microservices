package com.example.song.service;

import com.example.song.api.SongDto;
import com.example.song.persistence.SongEntity;
import com.example.song.persistence.SongRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SongServiceTest {
    private final SongRepository repository = mock(SongRepository.class);
    private final SongService service = new SongService(repository);
    private final SongDto song = new SongDto(42L, "Title", "Artist", "Album", "00:07", "2025");

    @Test
    void createsMetadataWithAllFields() {
        assertThat(service.create(song)).isEqualTo(42L);
        var saved = ArgumentCaptor.forClass(SongEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().toDto()).isEqualTo(song);
    }

    @Test
    void rejectsDuplicateIdsWithoutOverwritingMetadata() {
        when(repository.existsById(42L)).thenReturn(true);
        assertThatThrownBy(() -> service.create(song)).isInstanceOf(SongConflictException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsInvalidMetadataBeforeAccessingPersistence() {
        assertThatThrownBy(() -> service.create(null)).isInstanceOf(SongValidationException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void returnsPersistedMetadataAndReportsMissingSongs() {
        when(repository.findById(42L)).thenReturn(Optional.of(new SongEntity(song)));
        assertThat(service.get("42")).isEqualTo(song);
        assertThatThrownBy(() -> service.get("99")).isInstanceOf(SongNotFoundException.class);
    }

    @Test
    void deletionReturnsOnlyExistingIds() {
        when(repository.existsById(42L)).thenReturn(true);
        assertThat(service.delete("42,99")).containsExactly(42L);
        verify(repository).deleteById(42L);
        verify(repository, never()).deleteById(99L);
    }
}
