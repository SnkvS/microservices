package com.example.processor;

import com.example.processor.metadata.Mp3MetadataExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceProcessorApplicationTest {

    @Test
    void startsWithoutAWebServerAndProvidesMetadataExtraction() {
        var application = new SpringApplication(ResourceProcessorApplication.class);
        try (var context = application.run("--spring.main.keep-alive=false")) {
            assertThat(application.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
            assertThat(context).isInstanceOf(AnnotationConfigApplicationContext.class);
            assertThat(context.getBean(Mp3MetadataExtractor.class)).isNotNull();
        }
    }
}
