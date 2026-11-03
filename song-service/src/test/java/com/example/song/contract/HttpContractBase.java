package com.example.song.contract;

import com.example.song.api.*;
import com.example.song.persistence.*;
import com.example.song.service.SongService;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;

@WebMvcTest(controllers = SongController.class, properties = "eureka.client.enabled=false")
@Import({SongService.class, SongJsonConfiguration.class})
public abstract class HttpContractBase {
    @Autowired MockMvc mvc;
    @MockitoBean SongRepository repository;

    @BeforeEach
    void prepareProvider() {
        RestAssuredMockMvc.mockMvc(mvc);
        when(repository.existsById(43L)).thenReturn(true);
        when(repository.existsById(44L)).thenReturn(true);
        for (long id : new long[]{42, 43, 44}) {
            var song = new SongDto(id, id == 44 ? "Other Title" : "Test Title", "Test Artist", "Test Album", "00:07", "2025");
            when(repository.findById(id)).thenReturn(Optional.of(new SongEntity(song)));
        }
    }
}
