package com.example.resource.api;

import com.example.resource.service.ResourceNotFoundException;
import com.example.resource.service.ResourceService;
import com.example.resource.messaging.ResourcePublicationException;
import com.example.resource.storage.ResourceStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResourceControllerTest {

    private final ResourceService service = mock(ResourceService.class);

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ResourceController(service))
                .setControllerAdvice(new ResourceErrorHandler()).build();
    }

    @Test
    void uploadAndDeleteRetainTheirResponseShapes() throws Exception {
        when(service.upload(eq("audio/mpeg"), any())).thenReturn(42L);
        when(service.delete("42,99")).thenReturn(List.of(42L));
        mvc.perform(post("/resources").contentType("audio/mpeg").content(new byte[]{1}))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(42));
        mvc.perform(delete("/resources").param("id", "42,99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ids[0]").value(42));
    }

    @Test
    void downloadPreservesBytesContentTypeAndContentLength() throws Exception {
        byte[] bytes = {0, 1, (byte) 255, 42};
        when(service.get("42")).thenReturn(bytes);
        mvc.perform(get("/resources/42")).andExpect(status().isOk())
                .andExpect(content().contentType("audio/mpeg"))
                .andExpect(header().string("Content-Length", "4")).andExpect(content().bytes(bytes));
    }

    @Test
    void publicationFailureIs503WithoutLeakingBrokerDetails() throws Exception {
        when(service.upload(eq("audio/mpeg"), any())).thenThrow(
                new ResourcePublicationException(42, new IllegalStateException("secret endpoint")));
        mvc.perform(post("/resources").contentType("audio/mpeg").content(new byte[]{1}))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.errorCode").value("503"))
                .andExpect(jsonPath("$.errorMessage").value("Resource notification failed"));
    }

    @Test
    void storageFailureIs500WithNoInternalDetails() throws Exception {
        when(service.get("42")).thenThrow(new ResourceStorageException("download", new IllegalStateException("secret endpoint")));
        mvc.perform(get("/resources/42")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("500"))
                .andExpect(jsonPath("$.errorMessage").value("Resource storage request failed"));
    }

    @Test
    void missingResourcesAndInvalidRequestsKeepTheirErrorCodes() throws Exception {
        when(service.get("42")).thenThrow(new ResourceNotFoundException(42L));
        when(service.delete("abc")).thenThrow(new IllegalArgumentException("Invalid ID format"));
        mvc.perform(get("/resources/42")).andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("404"));
        mvc.perform(delete("/resources").param("id", "abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("400"));
        mvc.perform(delete("/resources")).andExpect(status().isBadRequest());
        mvc.perform(post("/resources").contentType("audio/mpeg"))
                .andExpect(status().isBadRequest());
    }
}
