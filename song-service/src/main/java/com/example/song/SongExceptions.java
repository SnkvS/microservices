package com.example.song;

import java.util.Map;

class SongValidationException extends RuntimeException {
    final Map<String, String> details;
    SongValidationException(Map<String, String> details) { this.details = details; }
}
class SongNotFoundException extends RuntimeException {
    SongNotFoundException(long id) { super("Song metadata for ID=" + id + " not found"); }
}
class SongConflictException extends RuntimeException {
    SongConflictException(long id) { super("Metadata for resource ID=" + id + " already exists"); }
}
