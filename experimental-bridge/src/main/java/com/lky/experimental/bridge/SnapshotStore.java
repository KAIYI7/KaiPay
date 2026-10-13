package com.lky.experimental.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Single-process ownership; atomic snapshots retain duplicate history until explicit offline reset. */
final class SnapshotStore implements AutoCloseable {
    private static final long MAX_BYTES = 2_000_000;
    private final Path path;
    private final ObjectMapper mapper;
    private final FileChannel ownership;
    private final FileLock lock;

    SnapshotStore(Path path, ObjectMapper mapper) {
        this.path = path.toAbsolutePath().normalize();
        this.mapper = mapper;
        FileChannel opened = null;
        FileLock acquired = null;
        try {
            Files.createDirectories(this.path.getParent());
            opened = FileChannel.open(this.path.resolveSibling(this.path.getFileName() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            acquired = opened.tryLock();
            if (acquired == null) throw new IOException("Ownership unavailable");
        } catch (Exception ignored) {
            if (opened != null) try { opened.close(); } catch (IOException suppressed) { /* no payload logs */ }
            throw new IllegalStateException("Experimental store unavailable");
        }
        ownership = opened;
        lock = acquired;
    }

    Model.Snapshot load() {
        try {
            if (!Files.exists(path)) return new Model.Snapshot(List.of(), List.of());
            if (Files.size(path) > MAX_BYTES) throw new IOException("Capacity");
            return mapper.readValue(path.toFile(), Model.Snapshot.class);
        } catch (Exception ignored) {
            throw new IllegalStateException("Experimental store unavailable");
        }
    }

    void save(Model.Snapshot snapshot) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            byte[] bytes = mapper.writeValueAsBytes(snapshot);
            if (bytes.length > MAX_BYTES) throw new IOException("Capacity");
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            // Failure to provide atomic replacement fails closed rather than publishing in-memory success.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            try { Files.deleteIfExists(temporary); } catch (IOException suppressed) { /* no payload logs */ }
            throw new BridgeFailure(503, "STORE_UNAVAILABLE");
        }
    }

    @Override public void close() throws IOException {
        try { lock.release(); } finally { ownership.close(); }
    }
}
