package com.fileverifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileHasherTest {

    @TempDir
    Path tmp;

    @Test
    void knownVectors() throws Exception {
        Path abc = TestSupport.write(tmp.resolve("abc.txt"), "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                FileHasher.computeHash(abc));

        Path empty = TestSupport.write(tmp.resolve("empty.txt"), "");
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                FileHasher.computeHash(empty));
    }

    @Test
    void buffersAcrossChunkBoundaries() throws Exception {
        byte[] data = new byte[1 << 20];
        new Random(42).nextBytes(data);
        Path file = tmp.resolve("random.bin");
        Files.write(file, data);

        String expected = toHex(MessageDigest.getInstance("SHA-256").digest(data));
        assertEquals(expected, FileHasher.computeHash(file));
        assertEquals(64, FileHasher.computeHash(file).length());
    }

    @Test
    void differentContentProducesDifferentHash() throws Exception {
        String a = FileHasher.computeHash(TestSupport.write(tmp.resolve("a.txt"), "one\n"));
        String b = FileHasher.computeHash(TestSupport.write(tmp.resolve("b.txt"), "two\n"));
        assertTrue(a.equals(b) == false);
    }

    @Test
    void missingFileIsAnIOException() {
        assertThrows(IOException.class, () -> FileHasher.computeHash(tmp.resolve("nope.txt")));
    }

    @Test
    void shortHashTruncatesForDisplay() {
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertEquals(FileHasher.SHORT_HASH_LENGTH, FileHasher.shortHash(hash).length());
        assertEquals(hash.substring(0, FileHasher.SHORT_HASH_LENGTH), FileHasher.shortHash(hash));
        assertEquals("abc", FileHasher.shortHash("abc"));
        assertEquals("", FileHasher.shortHash(null));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Guards against the removed directory-walking hashing path reappearing unused. */
    @Test
    void hashingOneFileDoesNotTouchTheRestOfTheTree() throws Exception {
        TestSupport.write(tmp.resolve("huge.bin"), new String(new char[4096]).replace('\0', 'x'));
        Path target = TestSupport.write(tmp.resolve("small/sub.txt"), "small");
        long start = System.nanoTime();
        String hash = FileHasher.computeHash(target);
        long micros = (System.nanoTime() - start) / 1000;
        assertEquals(64, hash.length());
        assertTrue(micros < 5_000_000, "hashing a single file took " + micros + "us");
        assertTrue(Arrays.asList(tmp.toFile().list()).contains("small"));
    }
}
