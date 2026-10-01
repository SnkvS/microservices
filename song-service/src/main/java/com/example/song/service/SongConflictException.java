package com.example.song.service;

public class SongConflictException extends RuntimeException {
    public SongConflictException(long id) { super("Metadata for resource ID=" + id + " already exists"); }
}
