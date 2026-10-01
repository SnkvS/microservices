package com.example.song;

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
    SongEntity(SongDto dto) {
        id = dto.id(); name = dto.name(); artist = dto.artist(); album = dto.album();
        duration = dto.duration(); year = dto.year();
    }
    SongDto toDto() { return new SongDto(id, name, artist, album, duration, year); }
}
