package com.example.resource;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SongClient {
    private final RestClient client;
    public SongClient(@Value("${song.service.url}") String url) { client = RestClient.builder().baseUrl(url).build(); }
    public void create(SongMetadata metadata) {
        client.post().uri("/songs").body(metadata).retrieve().toBodilessEntity();
    }
    public void delete(List<Long> ids) {
        if (ids.isEmpty()) return;
        String csv = ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        client.delete().uri(builder -> builder.path("/songs").queryParam("id", csv).build()).retrieve().toBodilessEntity();
    }
}
