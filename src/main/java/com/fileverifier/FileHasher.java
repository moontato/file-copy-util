package com.fileverifier;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 hashing of individual files. */
public final class FileHasher {

    private static final int BUFFER_SIZE = 65536;

    /** Display length for hash prefixes in the log (32 bits - enough to tell two files apart visually). */
    public static final int SHORT_HASH_LENGTH = 8;

    private FileHasher() {
    }

    /**
     * Raised when a hash was stopped because the job that asked for it was stopped.
     *
     * <p>It is an {@link IOException} so every existing caller keeps working, and it is its own type so a
     * caller can tell "this file could not be read" (a problem with the file) from "this program stopped
     * paying attention" (a problem with the job). A cancelled job must not fill its report with read errors
     * for files that are perfectly fine.
     */
    public static final class HashAborted extends IOException {
        private static final long serialVersionUID = 1L;

        HashAborted(Path file) {
            super("hashing of " + file + " was stopped on request");
        }
    }

    /**
     * Hash a file, stopping between reads if the calling thread is interrupted.
     *
     * <p>The check sits between reads rather than inside one: a read of a huge local file is otherwise
     * unbounded, so cancelling a job would leave a thread reading tens of gigabytes for nobody. A read that
     * the kernel will not interrupt (a wedged mount, for instance) is a different matter, and the engine says
     * so out loud instead of pretending the read stopped.
     */
    public static String computeHash(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new BufferedInputStream(Files.newInputStream(file), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
                if (Thread.currentThread().isInterrupted()) {
                    throw new HashAborted(file);
                }
            }
        }
        return bytesToHex(digest.digest());
    }

    /** Truncated hash for log/UI output; full hashes belong in reports, not in a scrolling log. */
    public static String shortHash(String hex) {
        if (hex == null) {
            return "";
        }
        return hex.length() <= SHORT_HASH_LENGTH ? hex : hex.substring(0, SHORT_HASH_LENGTH);
    }

    private static String bytesToHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = digits[v >>> 4];
            hex[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(hex);
    }
}
