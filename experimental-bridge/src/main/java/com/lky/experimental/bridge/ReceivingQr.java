package com.lky.experimental.bridge;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Owner-controlled startup configuration only; no request can choose a path. Never rewrites the image. */
final class ReceivingQr {
    static final int MAX_BYTES = 2_000_000;
    private static final byte[] SIGNATURE = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
    private final byte[] png;

    ReceivingQr(String directory) {
        if (directory.isEmpty()) { png = null; return; }
        try {
            Path root = Path.of(directory);
            if (!root.isAbsolute() || !root.equals(root.normalize()) || directory.startsWith("\\\\")
                    || directory.startsWith("//")) throw new IllegalArgumentException();
            Path file = root.resolve("receiving.png");
            // Reject symbolic links/junctions at any level, rather than resolving them to another source.
            for (Path part = file; part != null; part = part.getParent())
                if (Files.isSymbolicLink(part) || !part.toRealPath().equals(part.toAbsolutePath().normalize()))
                    throw new IllegalArgumentException();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES)
                throw new IllegalArgumentException();
            byte[] bytes;
            try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            validate(bytes);
            png = bytes;
        } catch (Exception ignored) {
            // Do not propagate configured paths, decoder diagnostics or image contents into startup logs.
            throw new IllegalStateException("Invalid private receiving PNG configuration");
        }
    }

    boolean configured() { return png != null; }
    byte[] bytes() {
        if (png == null) throw new BridgeFailure(404, "QR_NOT_CONFIGURED");
        return png.clone();
    }

    private static void validate(byte[] bytes) throws Exception {
        if (bytes.length > MAX_BYTES || bytes.length < 45 || !Arrays.equals(SIGNATURE, Arrays.copyOf(bytes, 8)))
            throw new IllegalArgumentException();
        // Bound dimensions before decoding, validate container integrity and forbid trailing/polyglot data or animation.
        ByteBuffer data = ByteBuffer.wrap(bytes); data.position(8);
        boolean first = true, ended = false;
        while (data.remaining() >= 12) {
            int length = data.getInt();
            if (length < 0 || length > data.remaining() - 8) throw new IllegalArgumentException();
            int start = data.position();
            byte[] type = new byte[4]; data.get(type);
            String name = new String(type, java.nio.charset.StandardCharsets.US_ASCII);
            if (first) {
                if (!name.equals("IHDR") || length != 13) throw new IllegalArgumentException();
                int width = data.getInt(start + 4), height = data.getInt(start + 8);
                if (width < 128 || height < 128 || width > 2048 || height > 2048) throw new IllegalArgumentException();
            } else if (name.equals("IHDR")) throw new IllegalArgumentException();
            if (name.equals("acTL") || name.equals("fcTL") || name.equals("fdAT")) throw new IllegalArgumentException();
            CRC32 crc = new CRC32(); crc.update(bytes, start, length + 4);
            data.position(start + 4 + length);
            if (data.getInt() != (int)crc.getValue()) throw new IllegalArgumentException();
            first = false;
            if (name.equals("IEND")) {
                if (length != 0 || data.hasRemaining()) throw new IllegalArgumentException();
                ended = true; break;
            }
        }
        if (!ended) throw new IllegalArgumentException();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException();
            var reader = readers.next();
            try {
                if (!reader.getFormatName().equalsIgnoreCase("png")) throw new IllegalArgumentException();
                reader.setInput(input, true, true);
                if (reader.read(0) == null) throw new IllegalArgumentException();
            } finally { reader.dispose(); }
        }
    }
}
