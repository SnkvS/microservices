package com.example.storage.api;
import com.example.storage.persistence.*;
import com.example.storage.service.*;
import com.example.storage.model.StorageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StorageControllerTest {
    StorageRepository repository=mock(StorageRepository.class);
    MockMvc mvc;
    @BeforeEach void setup() {
        var defaults=new StorageDefaults("resources-staging","/files","resources-permanent","/files");
        var mapperBuilder=org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json();
        new StorageJsonConfiguration().strictStorageJsonTypes().customize(mapperBuilder);
        mvc=MockMvcBuilders.standaloneSetup(new StorageController(new StorageService(repository,defaults)))
                .setControllerAdvice(new StorageErrorHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapperBuilder.build())).build();
    }
    @Test void createsListsAndDeletesUsingSpecifiedResponseShapes() throws Exception {
        var saved=new StorageEntity(new CreateStorageRequest(StorageType.STAGING,"custom-bucket","/files"));
        saved.id=3L;
        when(repository.saveAndFlush(any())).thenReturn(saved);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(saved));
        when(repository.findAllById(List.of(3L,99L))).thenReturn(List.of(saved));
        mvc.perform(post("/storages").contentType("application/json").content("""
            {"storageType":"STAGING","bucket":"custom-bucket","path":"/files/"}
            """)).andExpect(status().isOk()).andExpect(content().json("{\"id\":3}"));
        mvc.perform(get("/storages")).andExpect(status().isOk())
                .andExpect(content().json("[{\"id\":3,\"storageType\":\"STAGING\",\"bucket\":\"custom-bucket\",\"path\":\"/files\"}]"));
        mvc.perform(delete("/storages?id=3,99,3")).andExpect(status().isOk()).andExpect(content().json("[3]"));
        mvc.perform(delete("/storages?id=99")).andExpect(content().json("[]"));
    }
    @Test void rejectsMalformedBodiesReservedLocationsAndMissingCsv() throws Exception {
        for (String body:List.of("{}", "{\"storageType\":\"OTHER\",\"bucket\":\"valid-bucket\",\"path\":\"/files\"}",
                "{\"storageType\":\"PERMANENT\",\"bucket\":\"resources-staging\",\"path\":\"/files/\"}")) {
            mvc.perform(post("/storages").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("400"));
        }
        mvc.perform(delete("/storages")).andExpect(status().isBadRequest());
    }
    @Test void rejectsNumericEnumAndNonTextBucket() throws Exception {
        for(String body:List.of("{\"storageType\":0,\"bucket\":\"valid-bucket\",\"path\":\"/files\"}",
                "{\"storageType\":\"STAGING\",\"bucket\":123,\"path\":\"/files\"}")) {
            mvc.perform(post("/storages").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
    }
    @Test void doesNotExposeUnexpectedFailureDetails() throws Exception {
        when(repository.findAllByOrderByIdAsc()).thenThrow(new IllegalStateException("password=secret"));
        mvc.perform(get("/storages")).andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"errorMessage\":\"Internal server error\",\"errorCode\":\"500\"}"));
    }
}
