package com.example.resource.service;

import java.util.ArrayList;
import java.util.List;

public final class ResourceValidation {
    private ResourceValidation() {}

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

    public static void contentType(String type) {
        if (!"audio/mpeg".equalsIgnoreCase(type))
            throw new IllegalArgumentException("Invalid file format: " + type + ". Only MP3 files are allowed");
    }
}
