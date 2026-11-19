package com.example.storage.service;
import com.example.storage.api.CreateStorageRequest;
import java.util.List;
import java.util.LinkedHashSet;
import java.nio.charset.StandardCharsets;
public final class StorageValidation {
    private StorageValidation() {}
    public static CreateStorageRequest storage(CreateStorageRequest request) {
        if (request == null || request.storageType() == null)
            throw new IllegalArgumentException("storageType must be STAGING or PERMANENT");
        String bucket = request.bucket();
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || bucket.contains("..") || bucket.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")
                || bucket.startsWith("xn--") || bucket.startsWith("sthree-") || bucket.startsWith("amzn-s3-demo-")
                || bucket.endsWith("-s3alias") || bucket.endsWith("--ol-s3") || bucket.endsWith(".mrap")
                || bucket.endsWith("--x-s3") || bucket.endsWith("--table-s3"))
            throw new IllegalArgumentException("bucket must be a valid S3 bucket name");
        String path = request.path();
        if (path == null || !path.startsWith("/") || path.contains("\\")
                || path.getBytes(StandardCharsets.UTF_8).length > 900)
            throw new IllegalArgumentException("path must be an absolute prefix of at most 900 UTF-8 bytes");
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0,path.length()-1);
        if (!path.equals("/")) {
            for (String segment : path.substring(1).split("/",-1))
                if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                    throw new IllegalArgumentException("path contains an invalid segment");
        }
        return new CreateStorageRequest(request.storageType(),bucket,path);
    }
    public static List<Long> ids(String csv) {
        if (csv == null || csv.isEmpty() || csv.length() >= 200)
            throw new IllegalArgumentException("id CSV must contain fewer than 200 characters");
        var result = new LinkedHashSet<Long>();
        for (String value : csv.split(",",-1)) {
            if (!value.matches("[1-9][0-9]*")) throw new IllegalArgumentException("IDs must be positive integers");
            try { result.add(Long.parseLong(value)); }
            catch (NumberFormatException failure) { throw new IllegalArgumentException("ID exceeds supported range"); }
        }
        return List.copyOf(result);
    }
}
