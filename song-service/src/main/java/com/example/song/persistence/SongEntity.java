package com.example.song.persistence;

import com.example.song.api.SongDto;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "songs")
public class SongEntity {
    @Id public Long id;
    @Column(nullable = false, length = 100) public String name;
    @Column(nullable = false, length = 100) public String artist;
    @Column(nullable = false, length = 100) public String album;
    @Column(nullable = false, length = 5) public String duration;
    @Column(nullable = false, length = 4) public String year;

    protected SongEntity() {}
    public SongEntity(SongDto dto) {
        id = dto.id(); name = dto.name(); artist = dto.artist(); album = dto.album();
        duration = dto.duration(); year = dto.year();
    }
    public SongDto toDto() { return new SongDto(id, name, artist, album, duration, year); }
}
