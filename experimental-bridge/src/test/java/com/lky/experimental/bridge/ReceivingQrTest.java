package com.lky.experimental.bridge;

import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReceivingQrTest {
    @TempDir Path directory;

    // Deliberately nonpayable colored pixels, never a real receiving QR or encoded payment payload.
    static byte[] nonpayablePng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            image.setRGB(x, y, x < width / 2 ? 0x3377cc : 0xddaa55);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", output)).isTrue();
        return output.toByteArray();
    }

    void write(byte[] bytes) throws IOException { Files.write(directory.resolve("receiving.png"), bytes); }
    void rejects(String configuredDirectory) {
        assertThatThrownBy(() -> new ReceivingQr(configuredDirectory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid private receiving PNG configuration")
                .hasNoCause().hasNoSuppressedExceptions();
    }

    @Test void preservesOriginalBytesAndStartupSnapshotCannotBeMutatedOrReread() throws Exception {
        byte[] original = nonpayablePng(256, 256); write(original);
        ReceivingQr qr = new ReceivingQr(directory.toString());
        assertThat(qr.bytes()).isEqualTo(original);
        byte[] copy = qr.bytes(); copy[0] = 0;
        Files.writeString(directory.resolve("receiving.png"), "untrusted replacement");
        assertThat(qr.bytes()).isEqualTo(original);
        Files.delete(directory.resolve("receiving.png"));
        assertThat(qr.bytes()).isEqualTo(original);
    }

    @Test void emptyConfigurationProvidesExplicitNonpayableFallback() {
        assertThatThrownBy(() -> new ReceivingQr("").bytes()).isInstanceOfSatisfying(BridgeFailure.class,
                failure -> { assertThat(failure.status).isEqualTo(404); assertThat(failure.code).isEqualTo("QR_NOT_CONFIGURED"); });
    }

    @Test void onlyFixedFilenameInAbsoluteNormalizedLocalDirectoryCanBeLoaded() throws Exception {
        write(nonpayablePng(128, 128));
        rejects("relative");
        rejects(directory.resolve("..").resolve(directory.getFileName()).toString());
        rejects(directory.resolve("missing").toString());
        rejects(directory.resolve("receiving.png").toString());
        rejects("//server/private");
        Files.delete(directory.resolve("receiving.png"));
        Files.write(directory.resolve("different.png"), nonpayablePng(128, 128));
        rejects(directory.toString());
        Files.createDirectory(directory.resolve("receiving.png"));
        rejects(directory.toString());
    }

    @Test void rejectsWrongTypeOversizedEmptyAndUndecodableContentWithoutPrivateDiagnostics() throws Exception {
        for (byte[] invalid : new byte[][] {new byte[0], "private-recipient-not-an-image".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new byte[ReceivingQr.MAX_BYTES + 1], new byte[] {(byte)0xff, (byte)0xd8, (byte)0xff, (byte)0xd9}}) {
            write(invalid); rejects(directory.toString());
        }
        byte[] png = nonpayablePng(128, 128);
        // Keep a valid PNG container/CRC, but replace compressed image data with undecodable bytes.
        ByteArrayOutputStream broken = new ByteArrayOutputStream(); broken.write(png, 0, 33);
        broken.write(chunk("IDAT", new byte[] {1, 2, 3, 4})); broken.write(chunk("IEND", new byte[0]));
        write(broken.toByteArray()); rejects(directory.toString());
    }

    @Test void validatesDimensionsBeforeDecodingIncludingBothBoundaries() throws Exception {
        for (int[] dimensions : new int[][] {{127, 128}, {128, 127}, {2049, 128}, {128, 2049}, {0, 128}, {128, -1}}) {
            byte[] png = nonpayablePng(128, 128);
            ByteBuffer.wrap(png).putInt(16, dimensions[0]).putInt(20, dimensions[1]);
            crc(png, 8);
            write(png); rejects(directory.toString());
        }
        for (int size : new int[] {128, 2048}) {
            byte[] png = nonpayablePng(size, size); write(png);
            assertThat(new ReceivingQr(directory.toString()).bytes()).isEqualTo(png);
        }
    }

    @Test void rejectsTruncationCrcDamageTrailingPayloadDuplicateHeaderAndAnimatedPng() throws Exception {
        byte[] png = nonpayablePng(128, 128);
        byte[] badCrc = png.clone(); badCrc[29] ^= 1;
        byte[] trailing = Arrays.copyOf(png, png.length + 12);
        for (byte[] invalid : new byte[][] {Arrays.copyOf(png, 20), Arrays.copyOf(png, png.length - 1), badCrc, trailing,
                insertAfterHeader(png, Arrays.copyOfRange(png, 8, 33)),
                insertAfterHeader(png, chunk("acTL", ByteBuffer.allocate(8).putInt(1).putInt(0).array())),
                insertAfterHeader(png, chunk("fcTL", new byte[26])),
                insertAfterHeader(png, chunk("fdAT", new byte[4]))}) {
            write(invalid); rejects(directory.toString());
        }
    }

    @Test void rejectsLinkedDirectoriesAndAncestorsWithWindowsJunctionFallback() throws Exception {
        Path target = Files.createDirectory(directory.resolve("target"));
        Files.write(target.resolve("receiving.png"), nonpayablePng(128, 128));
        Path nested = Files.createDirectory(target.resolve("nested"));
        Files.write(nested.resolve("receiving.png"), nonpayablePng(128, 128));
        Path link = directory.resolve("linked");
        boolean junction = false;
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            assertThat(System.getProperty("os.name")).startsWith("Windows");
            // A directory junction exercises the reparse-point boundary without the symlink privilege.
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            boolean completed = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            assertThat(completed).as("bounded temporary junction creation completed").isTrue();
            assertThat(process.exitValue()).as("temporary directory junction creation succeeded").isZero();
            junction = true;
        }
        try {
            assertThat(Files.isDirectory(link)).isTrue();
            rejects(link.toString());
            rejects(link.resolve("nested").toString());
        } finally {
            // Remove only the link itself; never recursively traverse a junction during test cleanup.
            Files.delete(link);
        }
        assertThat(Files.exists(target.resolve("receiving.png"))).isTrue();
        if (junction) {
            System.out.println("ReceivingQrTest: directory junction and linked ancestor rejection exercised; direct file symlink unavailable on this host");
            return;
        }
        Path fileRoot = Files.createDirectory(directory.resolve("file-root"));
        Path fileLink = fileRoot.resolve("receiving.png");
        Files.createSymbolicLink(fileLink, target.resolve("receiving.png"));
        try { rejects(fileRoot.toString()); } finally { Files.delete(fileLink); }
    }

    private static byte[] insertAfterHeader(byte[] png, byte[] inserted) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); output.write(png, 0, 33);
        output.write(inserted); output.write(png, 33, png.length - 33); return output.toByteArray();
    }
    private static byte[] chunk(String type, byte[] content) {
        byte[] bytes = ByteBuffer.allocate(content.length + 12).putInt(content.length)
                .put(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).put(content).putInt(0).array();
        crc(bytes, 0); return bytes;
    }
    private static void crc(byte[] bytes, int offset) {
        int length = ByteBuffer.wrap(bytes).getInt(offset);
        CRC32 crc = new CRC32(); crc.update(bytes, offset + 4, length + 4);
        ByteBuffer.wrap(bytes).putInt(offset + 8 + length, (int)crc.getValue());
    }
}
