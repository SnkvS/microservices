package com.example.resource.contract;

import com.example.resource.api.ResourceController;
import com.example.resource.api.ResourceErrorHandler;
import com.example.resource.service.ResourceNotFoundException;
import com.example.resource.service.ResourceService;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;

import static org.mockito.Mockito.*;

public abstract class HttpContractBase {
    @BeforeEach
    void prepareProvider() throws Exception {
        ResourceService service = mock(ResourceService.class);
        try (var input = getClass().getResourceAsStream("/mp3/valid.mp3")) {
            byte[] bytes = input.readAllBytes();
            when(service.get("42")).thenReturn(bytes);
            when(service.get("43")).thenReturn(bytes);
        }
        when(service.get("99")).thenThrow(new ResourceNotFoundException(99));
        RestAssuredMockMvc.standaloneSetup(new ResourceController(service), new ResourceErrorHandler());
    }
}
