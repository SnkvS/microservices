package com.example.song;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SongValidation {
    private SongValidation() {}

    public static long id(String raw) {
        if (raw == null || !raw.matches("[1-9][0-9]*")) throw new IllegalArgumentException("Invalid value '" + raw + "' for ID. Must be a positive integer");
        try { return Long.parseLong(raw); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid value '" + raw + "' for ID. Must be a positive integer"); }
    }

    public static List<Long> ids(String raw) {
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("Invalid ID list");
        if (raw.length() > 200) throw new IllegalArgumentException("CSV string is too long: received " + raw.length() + " characters, maximum allowed is 200");
        List<Long> result = new ArrayList<>();
        for (String part : raw.split(",", -1)) {
            if (!part.matches("[1-9][0-9]*")) throw new IllegalArgumentException("Invalid ID format: '" + part + "'. Only positive integers are allowed");
            try { result.add(Long.parseLong(part)); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid ID format: '" + part + "'. Only positive integers are allowed"); }
        }
        return result;
    }

    public static Map<String, String> validate(SongDto song) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (song == null) { errors.put("song", "Song metadata is required"); return errors; }
        if (song.id() == null || song.id() <= 0) errors.put("id", "ID must be a positive number");
        checkText(errors, "name", "Song name", song.name());
        checkText(errors, "artist", "Artist name", song.artist());
        checkText(errors, "album", "Album name", song.album());
        if (song.duration() == null) errors.put("duration", "Duration is required");
        else if (!song.duration().matches("[0-9]{2}:[0-5][0-9]"))
            errors.put("duration", "Duration must be in mm:ss format with leading zeros");
        if (song.year() == null) errors.put("year", "Year is required");
        else if (!song.year().matches("(19|20)[0-9]{2}"))
            errors.put("year", "Year must be between 1900 and 2099");
        return errors;
    }

    private static void checkText(Map<String, String> errors, String field, String label, String value) {
        if (value == null) errors.put(field, label + " is required");
        else if (value.isBlank() || value.length() > 100) errors.put(field, label + " must be between 1 and 100 characters");
    }
}
