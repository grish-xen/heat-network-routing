package ru.hackathon.heatnetwork.api;

import java.nio.file.Path;
import java.util.Map;
import javax.servlet.MultipartConfigElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class JobConfigurationTest {
    @TempDir Path temporary;

    @Test void composeEnvironmentSelectsPersistentDirectoryAndMultipartUsesLongByteLimits() {
        new ApplicationContextRunner().withUserConfiguration(JobConfiguration.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                                "HEATNETWORK_JOBS_STORAGEDIRECTORY", temporary.toString()))))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(temporary, context.getBean(JobProperties.class).getStorageDirectory());
                    MultipartConfigElement multipart = context.getBean(MultipartConfigElement.class);
                    assertEquals(3L * 1024 * 1024 * 1024, multipart.getMaxFileSize());
                    assertEquals(multipart.getMaxFileSize() + 1024 * 1024, multipart.getMaxRequestSize());
                    assertEquals(0, multipart.getFileSizeThreshold());
                    assertEquals(temporary.resolve("multipart").toString(), multipart.getLocation());
                });
    }
}
