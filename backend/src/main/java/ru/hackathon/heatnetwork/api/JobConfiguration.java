package ru.hackathon.heatnetwork.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.servlet.MultipartConfigElement;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JobProperties.class)
class JobConfiguration {
    @Bean
    MultipartConfigElement multipartConfigElement(JobProperties properties) throws IOException {
        // Servlet spools uploads to disk immediately. The application then copies with a bounded buffer.
        Path directory = properties.getStorageDirectory().toAbsolutePath().resolve("multipart");
        Files.createDirectories(directory);
        long limit = properties.getMaxFileBytes();
        return new MultipartConfigElement(directory.toString(), limit, limit + 1024 * 1024, 0);
    }
}
