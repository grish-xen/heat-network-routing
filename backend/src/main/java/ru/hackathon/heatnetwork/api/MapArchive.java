package ru.hackathon.heatnetwork.api;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/** Immutable WGS84 features plus a fixed-size disk index. No dataset or geometry tree is retained. */
final class MapArchive {
    private static final long MAGIC = 0x484e524d41503031L; // HNRMAP01
    private static final int RECORD_BYTES = 52;
    static final long MAX_PAGE_BYTES = 8L * 1024 * 1024;
    private MapArchive() { }

    static void build(Path source, Path data, Path index, List<String> variants, ObjectMapper mapper) throws IOException {
        try (JsonParser json = mapper.getFactory().createParser(source.toFile());
             CountingOutput bytes = new CountingOutput(Files.newOutputStream(data, StandardOpenOption.CREATE_NEW));
             JsonGenerator output = mapper.getFactory().createGenerator(bytes);
             DataOutputStream entries = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(index, StandardOpenOption.CREATE_NEW)))) {
            entries.writeLong(MAGIC);
            require(json.nextToken(), JsonToken.START_OBJECT);
            boolean found = false;
            while (json.nextToken() != JsonToken.END_OBJECT) {
                require(json.currentToken(), JsonToken.FIELD_NAME);
                String field = json.currentName();
                json.nextToken();
                if (!"features".equals(field)) { json.skipChildren(); continue; }
                found = true;
                require(json.currentToken(), JsonToken.START_ARRAY);
                while (json.nextToken() != JsonToken.END_ARRAY) {
                    CalculationCoordinator.interrupted();
                    require(json.currentToken(), JsonToken.START_OBJECT);
                    long start = bytes.count;
                    Bounds bounds = new Bounds();
                    String variant = null;
                    String type = null;
                    output.writeStartObject();
                    while (json.nextToken() != JsonToken.END_OBJECT) {
                        require(json.currentToken(), JsonToken.FIELD_NAME);
                        String key = json.currentName();
                        output.writeFieldName(key);
                        json.nextToken();
                        if ("geometry".equals(key) && json.currentToken() == JsonToken.START_OBJECT) {
                            geometry(json, output, bounds);
                        } else if ("properties".equals(key)) {
                            require(json.currentToken(), JsonToken.START_OBJECT);
                            output.writeStartObject();
                            while (json.nextToken() != JsonToken.END_OBJECT) {
                                require(json.currentToken(), JsonToken.FIELD_NAME);
                                String property = json.currentName();
                                output.writeFieldName(property);
                                json.nextToken();
                                if ("variant_id".equals(property)) variant = json.getValueAsString();
                                if ("object_type".equals(property)) type = json.getValueAsString();
                                copy(json, output);
                            }
                            output.writeEndObject();
                        } else copy(json, output);
                    }
                    output.writeEndObject();
                    output.flush();
                    if (!bounds.empty() && !"variant_summary".equals(type)) {
                        int ordinal = variants == null ? 0 : variants.indexOf(variant) + 1;
                        if (variants != null && ordinal == 0) throw new IOException("Unknown map variant in export");
                        entries.writeLong(start);
                        entries.writeLong(bytes.count - start);
                        entries.writeDouble(bounds.minX); entries.writeDouble(bounds.minY);
                        entries.writeDouble(bounds.maxX); entries.writeDouble(bounds.maxY);
                        entries.writeInt(ordinal);
                    }
                }
            }
            if (!found) throw new IOException("Missing map features");
        }
    }

    private static void geometry(JsonParser json, JsonGenerator output, Bounds bounds) throws IOException {
        output.writeStartObject();
        while (json.nextToken() != JsonToken.END_OBJECT) {
            require(json.currentToken(), JsonToken.FIELD_NAME);
            String key = json.currentName();
            output.writeFieldName(key);
            json.nextToken();
            if ("coordinates".equals(key)) coordinates(json, output, bounds, 0);
            else copy(json, output);
        }
        output.writeEndObject();
    }

    /** Only the first two ordinates are exposed in the 2D map contract. */
    private static void coordinates(JsonParser json, JsonGenerator output, Bounds bounds, int depth) throws IOException {
        if (depth > 4) throw new IOException("Invalid map coordinate nesting");
        require(json.currentToken(), JsonToken.START_ARRAY);
        output.writeStartArray();
        int numbers = 0;
        double x = 0, y = 0;
        while (json.nextToken() != JsonToken.END_ARRAY) {
            if (json.currentToken() == JsonToken.START_ARRAY) coordinates(json, output, bounds, depth + 1);
            else {
                if (json.currentToken() == null || !json.currentToken().isNumeric()) throw new IOException("Invalid map coordinate");
                if (numbers == 0) x = json.getDoubleValue();
                if (numbers == 1) y = json.getDoubleValue();
                if (numbers++ < 2) output.writeNumber(json.getText());
            }
        }
        if (numbers >= 2) bounds.add(x, y);
        output.writeEndArray();
        if ((++bounds.positions & 1023) == 0) CalculationCoordinator.interrupted();
    }

    /** Preserve arbitrary numeric IDs exactly, including decimal IDs beyond double precision. */
    private static void copy(JsonParser json, JsonGenerator output) throws IOException {
        int nesting = 0;
        int tokens = 0;
        do {
            JsonToken token = json.currentToken();
            if (token == null) throw new IOException("Truncated map feature");
            if (token.isNumeric()) output.writeNumber(json.getText());
            else output.copyCurrentEvent(json);
            if (token.isStructStart()) nesting++;
            if (token.isStructEnd()) nesting--;
            if ((++tokens & 1023) == 0) CalculationCoordinator.interrupted();
            if (nesting > 0) json.nextToken();
        } while (nesting > 0);
    }

    private static void require(JsonToken actual, JsonToken expected) throws IOException {
        if (actual != expected) throw new IOException("Invalid map archive source: expected " + expected);
    }
    private static final class Bounds {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        int positions;
        void add(double x, double y) {
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
        }
        boolean empty() { return minX > maxX; }
    }
    private static final class CountingOutput extends FilterOutputStream {
        long count;
        CountingOutput(OutputStream output) { super(output); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); count += length; }
    }

    /** Open under the job-store lock, scan and stream outside it. Close also on client disconnect. */
    static final class Reader implements AutoCloseable {
        private final RandomAccessFile data;
        private final RandomAccessFile index;
        Reader(Path data, Path index) throws IOException {
            this.data = new RandomAccessFile(data.toFile(), "r");
            try { this.index = new RandomAccessFile(index.toFile(), "r"); }
            catch (IOException failure) { this.data.close(); throw failure; }
        }
        Page select(MapQuery query, int variant) throws IOException { return select(query, variant, MAX_PAGE_BYTES); }
        Page select(MapQuery query, int variant, long maxPageBytes) throws IOException {
            index.seek(0);
            if (index.readLong() != MAGIC || (index.length() - 8) % RECORD_BYTES != 0) throw new IOException("Invalid map index");
            long count = (index.length() - 8) / RECORD_BYTES;
            if (query.offset > count) throw MapQuery.bad("INVALID_CURSOR", "Курсор за пределами данных карты.");
            index.seek(8 + query.offset * RECORD_BYTES);
            ByteBuffer block = ByteBuffer.allocate(RECORD_BYTES * 1024);
            block.limit(0);
            long dataLength = data.length();
            List<Slice> slices = new ArrayList<>();
            long size = 256; // envelope, separators and cursor
            String next = null;
            for (long i = query.offset; i < count; i++) {
                if ((i & 1023) == 0) CalculationCoordinator.interrupted();
                if (block.remaining() < RECORD_BYTES) {
                    int bytes = (int) Math.min(count - i, 1024) * RECORD_BYTES;
                    index.readFully(block.array(), 0, bytes);
                    block.clear();
                    block.limit(bytes);
                }
                long offset = block.getLong(), length = block.getLong();
                double minX = block.getDouble(), minY = block.getDouble(), maxX = block.getDouble(), maxY = block.getDouble();
                int ordinal = block.getInt();
                if (ordinal != variant || !query.intersects(minX, minY, maxX, maxY)) continue;
                if (length < 0 || offset < 0 || length > dataLength - offset) throw new IOException("Invalid map feature offset");
                if (slices.size() >= query.limit || size + length + 1 > maxPageBytes) {
                    if (slices.isEmpty()) throw new ApiException(413, "MAP_FEATURE_TOO_LARGE", "Один объект превышает лимит страницы карты 8 МиБ. Полная геометрия доступна в исходном файле или скачиваемом результате.");
                    next = query.cursor(i);
                    break;
                }
                slices.add(new Slice(offset, length));
                size += length + 1;
            }
            return new Page(this, slices, next);
        }
        @Override public void close() throws IOException { try { index.close(); } finally { data.close(); } }
    }

    static final class Page implements AutoCloseable {
        private final Reader reader;
        private final List<Slice> slices;
        final String nextCursor;
        Page(Reader reader, List<Slice> slices, String nextCursor) { this.reader = reader; this.slices = slices; this.nextCursor = nextCursor; }
        void writeTo(OutputStream output) throws IOException {
            output.write("{\"type\":\"FeatureCollection\",\"features\":[".getBytes(StandardCharsets.UTF_8));
            byte[] buffer = new byte[64 * 1024];
            boolean first = true;
            for (Slice slice : slices) {
                if (!first) output.write(',');
                first = false;
                reader.data.seek(slice.offset);
                long remaining = slice.length;
                while (remaining > 0) {
                    CalculationCoordinator.interrupted();
                    int length = (int) Math.min(remaining, buffer.length);
                    reader.data.readFully(buffer, 0, length);
                    output.write(buffer, 0, length);
                    remaining -= length;
                }
            }
            // Cursor contains only URL-safe base64 characters, never user text.
            String suffix = "],\"nextCursor\":" + (nextCursor == null ? "null" : "\"" + nextCursor + "\"") + "}";
            output.write(suffix.getBytes(StandardCharsets.UTF_8));
        }
        @Override public void close() throws IOException { reader.close(); }
    }
    private static final class Slice {
        final long offset, length;
        Slice(long offset, long length) { this.offset = offset; this.length = length; }
    }
}
