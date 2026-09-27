package ru.hackathon.heatnetwork.api;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapArchiveMemoryTest {
    @TempDir Path temporary;

    @Test void buildsAndQueriesArchiveLargerThanHeap() throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = temporary.resolve("probe.log");
        Process child = new ProcessBuilder(java.toString(), "-Xmx32m", "-cp", classpath,
                Probe.class.getName(), temporary.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(180, TimeUnit.SECONDS), "Map archive probe timed out");
            assertEquals(0, child.exitValue(), () -> {
                try { return Files.readString(log); } catch (IOException e) { return e.toString(); }
            });
            System.out.println(Files.readString(log));
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); }
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            Path source = directory.resolve("source.geojson");
            try (BufferedWriter out = Files.newBufferedWriter(source)) {
                out.write("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"heat_network\",\"diameter\":300},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
                for (int i = 0; i < 2_000_000; i++) {
                    if (i != 0) out.write(',');
                    out.write(i % 2 == 0 ? "[10.123456,20.654321]" : "[10.123457,20.654322]");
                }
                out.write("]}},{\"type\":\"Feature\",\"properties\":{\"id\":2,\"object_type\":\"source\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.5,55.5]}}]}");
            }
            if (Files.size(source) <= Runtime.getRuntime().maxMemory()) throw new AssertionError("Probe source must exceed heap");
            ObjectMapper mapper = new ObjectMapper();
            Path data = directory.resolve("map.data"), index = directory.resolve("map.index");
            MapArchive.build(source, data, index, null, mapper);
            try (MapArchive.Reader reader = new MapArchive.Reader(data, index);
                 MapArchive.Page page = reader.select(new MapQuery("job", "input", "37,55,38,56", null, null, null), 0)) {
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                page.writeTo(result);
                if (mapper.readTree(result.toByteArray()).at("/features/0/properties/id").asInt() != 2) throw new AssertionError("Wrong map page");
            }
            try (MapArchive.Reader reader = new MapArchive.Reader(data, index)) {
                try {
                    reader.select(new MapQuery("job", "input", "10,20,11,21", null, null, null), 0);
                    throw new AssertionError("Oversized feature must have an explicit error");
                } catch (ApiException expected) {
                    if (expected.status != 413) throw expected;
                }
            }
            System.out.println("OK: map input=" + Files.size(source) + "; maxHeap=" + Runtime.getRuntime().maxMemory());
        }
    }
}
