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

    public static String computeHash(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new BufferedInputStream(Files.newInputStream(file), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
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
