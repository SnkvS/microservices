package com.example.song.service;

import java.util.Map;

public class SongValidationException extends RuntimeException {
    private final Map<String, String> details;
    public SongValidationException(Map<String, String> details) { this.details = details; }
    public Map<String, String> getDetails() { return details; }
}
