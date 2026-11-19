package com.example.storage.contract;
import com.example.storage.api.*;
import com.example.storage.model.StorageType;
import com.example.storage.persistence.*;
import com.example.storage.service.*;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
public abstract class HttpContractBase {
    @BeforeEach void setup() {
        var repository=mock(StorageRepository.class);
        var staging=new StorageEntity(new CreateStorageRequest(StorageType.STAGING,"resources-staging","/files")); staging.id=1L;
        var permanent=new StorageEntity(new CreateStorageRequest(StorageType.PERMANENT,"resources-permanent","/files")); permanent.id=2L;
        var saved=new StorageEntity(new CreateStorageRequest(StorageType.STAGING,"custom-bucket","/files")); saved.id=3L;
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(staging,permanent));
        when(repository.saveAndFlush(any())).thenReturn(saved);
        when(repository.findAllById(List.of(3L,99L))).thenReturn(List.of(saved));
        var defaults=new StorageDefaults("resources-staging","/files","resources-permanent","/files");
        var mapperBuilder=org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json();
        new StorageJsonConfiguration().strictStorageJsonTypes().customize(mapperBuilder);
        RestAssuredMockMvc.mockMvc(MockMvcBuilders.standaloneSetup(new StorageController(new StorageService(repository,defaults)))
            .setControllerAdvice(new StorageErrorHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapperBuilder.build())).build());
    }
}
