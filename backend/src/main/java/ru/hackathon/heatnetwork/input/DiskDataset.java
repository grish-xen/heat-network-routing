package ru.hackathon.heatnetwork.input;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model;
import ru.hackathon.heatnetwork.model.Model.*;
import ru.hackathon.heatnetwork.model.ObjectId;

/** Job-local temporary storage, not the application's persistent database. */
final class DiskDataset implements Dataset {
    private static final int BUCKETS = 65536;
    private static final long MAGIC = 0x484e523100000001L;
    private final Path directory;
    private final ObjectMapper mapper;
    private final Set<Cursor> cursors = new HashSet<>();
    private boolean closed;

    private DiskDataset(Path directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
    }

    @Override public synchronized Optional<InputObject> find(ObjectId id) {
        ensureOpen();
        Objects.requireNonNull(id, "id");
        try (RandomAccessFile index = new RandomAccessFile(directory.resolve("ids.bin").toFile(), "r");
             RandomAccessFile data = new RandomAccessFile(directory.resolve("objects.bin").toFile(), "r")) {
            long offset = locate(index, data, mapper, id);
            if (offset == 0) return Optional.empty();
            data.seek(offset);
            Header header = header(data, data.length());
            return Optional.of(readObject(data, header, mapper));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read dataset", e);
        }
    }

    @Override public Stream<InputObject> objects(InputType type) {
        return stream(Objects.requireNonNull(type, "type"), null);
    }

    @Override public Stream<InputObject> query(Envelope bounds) {
        Objects.requireNonNull(bounds, "bounds");
        Envelope copy = new Envelope(bounds);
        if (!copy.isNull() && (!Double.isFinite(copy.getMinX()) || !Double.isFinite(copy.getMaxX())
                || !Double.isFinite(copy.getMinY()) || !Double.isFinite(copy.getMaxY()))) {
            throw new IllegalArgumentException("Query bounds must be finite metres");
        }
        return stream(null, copy);
    }

    private synchronized Stream<InputObject> stream(InputType type, Envelope bounds) {
        ensureOpen();
        if (bounds != null && bounds.isNull()) return Stream.empty();
        try {
            Cursor cursor = new Cursor(type, bounds);
            cursors.add(cursor);
            Spliterator<InputObject> iterator = new Spliterators.AbstractSpliterator<InputObject>(
                    Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
                @Override public boolean tryAdvance(Consumer<? super InputObject> action) {
                    InputObject next;
                    synchronized (DiskDataset.this) {
                        ensureOpen();
                        try {
                            next = cursor.next();
                        } catch (IOException e) {
                            try { cursor.close(); } catch (IOException closeError) { e.addSuppressed(closeError); }
                            throw new UncheckedIOException("Cannot iterate dataset", e);
                        }
                    }
                    if (next == null) return false;
                    action.accept(next);
                    return true;
                }
            };
            return StreamSupport.stream(iterator, false).onClose(() -> {
                synchronized (DiskDataset.this) {
                    try { cursor.close(); } catch (IOException e) { throw new UncheckedIOException(e); }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open dataset stream", e);
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (Cursor cursor : new ArrayList<>(cursors)) {
            try { cursor.close(); } catch (IOException e) { failure = append(failure, e); }
        }
        try { removeFiles(directory); } catch (IOException e) { failure = append(failure, e); }
        if (failure != null) throw new UncheckedIOException("Cannot remove temporary dataset", failure);
    }

    private void ensureOpen() { if (closed) throw new IllegalStateException("Dataset is closed"); }

    private final class Cursor implements Closeable {
        private final RandomAccessFile data;
        private final InputType type;
        private final Envelope bounds;
        private final long fileLength;
        private boolean done;

        Cursor(InputType type, Envelope bounds) throws IOException {
            this.type = type;
            this.bounds = bounds;
            data = new RandomAccessFile(directory.resolve("objects.bin").toFile(), "r");
            try {
                if (data.readLong() != MAGIC) throw new IOException("Invalid dataset header");
                fileLength = data.length();
            } catch (IOException e) { data.close(); throw e; }
        }

        InputObject next() throws IOException {
            if (done) return null;
            while (data.getFilePointer() < fileLength) {
                Header h = header(data, fileLength);
                if ((type == null || type == h.type) && (bounds == null || bounds.intersects(h.envelope))) {
                    InputObject result = readObject(data, h, mapper);
                    data.seek(h.end);
                    return result;
                }
                data.seek(h.end);
            }
            close();
            return null;
        }

        @Override public void close() throws IOException {
            if (done) return;
            done = true;
            cursors.remove(this);
            data.close();
        }
    }

    private static Header header(RandomAccessFile data, long fileLength) throws IOException {
        long start = data.getFilePointer();
        byte[] bytes = new byte[45];
        data.readFully(bytes);
        ByteBuffer b = ByteBuffer.wrap(bytes);
        int length = b.getInt();
        long end = start + 4L + length;
        if (length < 41 || end > fileLength) throw new IOException("Invalid dataset record length");
        b.getLong(); // next record in ID hash bucket, used only by locate()
        int ordinal = b.get() & 255;
        if (ordinal >= InputType.values().length) throw new IOException("Invalid object type in dataset");
        Envelope envelope = new Envelope(b.getDouble(), b.getDouble(), b.getDouble(), b.getDouble());
        return new Header(end, InputType.values()[ordinal], envelope);
    }

    private static InputObject readObject(RandomAccessFile file, Header header, ObjectMapper mapper) throws IOException {
        byte[] payload = new byte[(int)(header.end - file.getFilePointer())];
        file.readFully(payload);
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload));
        InputObject object = new InputObject();
        object.type = header.type;
        object.id = new ObjectId(mapper.readTree(readText(data)));
        int diameter = data.readInt();
        object.diameterMm = diameter < 0 ? null : diameter;
        String flow = readText(data);
        object.flowTph = flow == null ? null : new BigDecimal(flow);
        object.restrictionType = readText(data);
        int length = data.readInt();
        if (length < 0 || length != data.available()) throw new IOException("Invalid WKB size");
        try {
            object.geometry = new WKBReader(new GeometryFactory(new PrecisionModel(), Model.METRIC_SRID)).read(buffer -> {
                data.readFully(buffer);
                return buffer.length;
            });
        } catch (ParseException e) { throw new IOException("Invalid geometry in temporary storage", e); }
        return object;
    }

    private static long locate(RandomAccessFile index, RandomAccessFile data, ObjectMapper mapper, ObjectId id) throws IOException {
        index.seek(bucket(id) * 8L);
        byte[] head = new byte[8];
        index.readFully(head);
        long position = ByteBuffer.wrap(head).getLong();
        while (position != 0) {
            data.seek(position + 4);
            byte[] bytes = new byte[45];
            data.readFully(bytes);
            ByteBuffer b = ByteBuffer.wrap(bytes);
            long next = b.getLong();
            b.position(41); // skip type and envelope
            int idLength = b.getInt();
            if (idLength < 0 || idLength > data.length() - data.getFilePointer()) throw new IOException("Invalid ID length");
            byte[] idBytes = new byte[idLength];
            data.readFully(idBytes);
            ObjectId stored = new ObjectId(mapper.readTree(idBytes));
            if (id.equals(stored)) return position;
            position = next;
        }
        return 0;
    }

    private static int bucket(ObjectId id) {
        String canonical = id.value().isTextual() ? "s:" + id.value().textValue()
                : "n:" + id.value().decimalValue().stripTrailingZeros().toPlainString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return (digest[0] & 255) * 256 + (digest[1] & 255);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void writeText(DataOutputStream file, String text) throws IOException {
        if (text == null) { file.writeInt(-1); return; }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        file.writeInt(bytes.length);
        file.write(bytes);
    }

    private static String readText(DataInputStream file) throws IOException {
        int length = file.readInt();
        if (length == -1) return null;
        if (length < 0 || length > file.available()) throw new IOException("Invalid string length");
        byte[] bytes = new byte[length];
        file.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void removeFiles(Path directory) throws IOException {
        IOException failure = null;
        for (String name : new String[]{"objects.bin", "ids.bin"}) {
            try { Files.deleteIfExists(directory.resolve(name)); } catch (IOException e) { failure = append(failure, e); }
        }
        try { Files.deleteIfExists(directory); } catch (IOException e) { failure = append(failure, e); }
        if (failure != null) throw failure;
    }

    private static IOException append(IOException first, IOException next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private static final class Header {
        final long end;
        final InputType type;
        final Envelope envelope;
        Header(long end, InputType type, Envelope envelope) { this.end = end; this.type = type; this.envelope = envelope; }
    }

    static final class Builder implements AutoCloseable {
        private final Path directory;
        private final ObjectMapper mapper;
        private RandomAccessFile data;
        private RandomAccessFile index;
        private boolean transferred;

        Builder(Path parent, ObjectMapper mapper) throws IOException {
            Files.createDirectories(parent);
            directory = Files.createTempDirectory(parent, "dataset-");
            this.mapper = mapper;
            try {
                data = new RandomAccessFile(directory.resolve("objects.bin").toFile(), "rw");
                data.writeLong(MAGIC);
                index = new RandomAccessFile(directory.resolve("ids.bin").toFile(), "rw");
                byte[] zeros = new byte[8192];
                for (int bytes = 0; bytes < BUCKETS * 8; bytes += zeros.length) index.write(zeros);
            } catch (IOException e) {
                try { close(); } catch (IOException closeError) { e.addSuppressed(closeError); }
                throw e;
            }
        }

        boolean add(InputObject object) throws IOException {
            if (locate(index, data, mapper, object.id) != 0) return false;
            long bucketOffset = bucket(object.id) * 8L;
            index.seek(bucketOffset);
            byte[] head = new byte[8];
            index.readFully(head);
            long previous = ByteBuffer.wrap(head).getLong();
            long start = data.length();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream record = new DataOutputStream(bytes);
            record.writeInt(0);
            record.writeLong(previous);
            record.writeByte(object.type.ordinal());
            Envelope b = object.geometry.getEnvelopeInternal();
            record.writeDouble(b.getMinX()); record.writeDouble(b.getMaxX());
            record.writeDouble(b.getMinY()); record.writeDouble(b.getMaxY());
            writeText(record, mapper.writeValueAsString(object.id));
            record.writeInt(object.diameterMm == null ? -1 : object.diameterMm);
            writeText(record, object.flowTph == null ? null : object.flowTph.toString());
            writeText(record, object.restrictionType);
            byte[] geometry = new WKBWriter(2, true).write(object.geometry);
            record.writeInt(geometry.length);
            record.write(geometry);
            byte[] payload = bytes.toByteArray();
            ByteBuffer.wrap(payload).putInt(payload.length - 4);
            data.seek(start);
            data.write(payload);
            index.seek(bucketOffset);
            index.write(ByteBuffer.allocate(8).putLong(start).array());
            return true;
        }

        Dataset finish() throws IOException {
            data.close();
            index.close();
            transferred = true;
            return new DiskDataset(directory, mapper);
        }

        @Override public void close() throws IOException {
            if (transferred) return;
            IOException failure = null;
            if (data != null) try { data.close(); } catch (IOException e) { failure = e; }
            if (index != null) try { index.close(); } catch (IOException e) { failure = append(failure, e); }
            try { removeFiles(directory); } catch (IOException e) { failure = append(failure, e); }
            if (failure != null) throw failure;
        }
    }
}
