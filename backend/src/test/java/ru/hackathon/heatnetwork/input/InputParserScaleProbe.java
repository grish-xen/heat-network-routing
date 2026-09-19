package ru.hackathon.heatnetwork.input;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.node.IntNode;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.ObjectId;
import ru.hackathon.heatnetwork.model.Model.*;

/** Invoked by a separate 64 MiB JVM, independent of the test runner's heap size. */
public final class InputParserScaleProbe {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Path input = directory.resolve("large.geojson");
        Path storage = directory.resolve("datasets");
        int count = 40000;
        String padding = "x".repeat(2048);
        try (BufferedWriter out = Files.newBufferedWriter(input)) {
            out.write("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"id\":\"source\",\"object_type\":\"source\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.75]}}");
            for (int i=0;i<count;i++) {
                out.write(",{\"type\":\"Feature\",\"properties\":{\"id\":"+i+",\"object_type\":\"oks_connection_point\",\"flow_tph\":10,\"description\":\"");
                out.write(padding);
                out.write("\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.61,55.75]}}");
            }
            out.write("]}");
        }
        long bytes = Files.size(input);
        if (bytes <= Runtime.getRuntime().maxMemory()) throw new IllegalStateException("Fixture must exceed heap capacity");
        try (Dataset data = new GeoJsonInputParser(storage).parse(input);
             Stream<InputObject> points = data.objects(InputType.OKS_CONNECTION_POINT)) {
            if (points.count() != count) throw new IllegalStateException("Point count mismatch");
            for (int i=0;i<count;i+=499) {
                if (!data.find(new ObjectId(IntNode.valueOf(i))).isPresent()) {
                    throw new IllegalStateException("ID lookup failed after hash-bucket collisions");
                }
            }
        }
        try (Stream<Path> remaining = Files.list(storage)) {
            if (remaining.count() != 0) throw new IllegalStateException("Temporary dataset leaked");
        }
        Files.delete(input);
        System.out.println("OK: "+count+" points; input="+bytes+" bytes; maxHeap="+Runtime.getRuntime().maxMemory());
    }
}
