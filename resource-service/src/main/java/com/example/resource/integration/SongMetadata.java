package com.example.resource.integration;

public record SongMetadata(Long id, String name, String artist, String album, String duration, String year) {
    public SongMetadata withId(long resourceId) { return new SongMetadata(resourceId, name, artist, album, duration, year); }
}
