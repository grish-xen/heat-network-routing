package ru.hackathon.heatnetwork.output;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResultExporterMemoryTest {
    @TempDir Path temporary;

    @Test void streamsOutputLargerThanHeapIncludingOneLargeGeometry() throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = temporary.resolve("export-probe.log");
        Process child = new ProcessBuilder(java.toString(), "-Xmx64m", "-cp", classpath,
                ResultExporterScaleProbe.class.getName(), temporary.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(180, TimeUnit.SECONDS), "Export scale probe timed out");
            assertEquals(0, child.exitValue(), () -> {
                try { return Files.readString(log); } catch (Exception exception) { return exception.toString(); }
            });
            System.out.println(Files.readString(log));
            assertFalse(Files.exists(temporary.resolve("large-result.geojson")));
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); }
        }
    }
}
