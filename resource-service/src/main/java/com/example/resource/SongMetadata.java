package com.example.resource;

public record SongMetadata(Long id, String name, String artist, String album, String duration, String year) {
    SongMetadata withId(long resourceId) { return new SongMetadata(resourceId, name, artist, album, duration, year); }
}
