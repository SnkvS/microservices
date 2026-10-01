package com.example.resource;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ResourceValidationTest {
    @Test void validatesIdsAndContentType() {
        assertEquals(java.util.List.of(1L, 2L), ResourceValidation.ids("1,2"));
        assertThrows(IllegalArgumentException.class, () -> ResourceValidation.ids("1,a"));
        assertThrows(IllegalArgumentException.class, () -> ResourceValidation.id("0"));
        assertThrows(IllegalArgumentException.class, () -> ResourceValidation.contentType("application/json"));
    }
}
