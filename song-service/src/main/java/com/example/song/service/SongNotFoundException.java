package com.example.song.service;

public class SongNotFoundException extends RuntimeException {
    public SongNotFoundException(long id) { super("Song metadata for ID=" + id + " not found"); }
}
