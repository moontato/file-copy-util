package com.fileverifier;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class FileHasher {

    private static final int BUFFER_SIZE = 8192;
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    public static String computeHash(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        byte[] hashBytes = digest.digest();
        return bytesToHex(hashBytes);
    }

    public static Map<String, String> computeHashesForDirectory(File dir)
            throws IOException, NoSuchAlgorithmException {
        Map<String, String> hashes = new LinkedHashMap<>();
        if (!dir.exists() || !dir.isDirectory()) {
            return hashes;
        }
        recursiveHash(dir, dir.getAbsolutePath(), hashes);
        return hashes;
    }

    private static void recursiveHash(File dir, String basePath, Map<String, String> hashes)
            throws IOException, NoSuchAlgorithmException {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            if (file.isFile()) {
                String relPath = file.getAbsolutePath().substring(basePath.length() + 1);
                hashes.put(relPath, computeHash(file));
            } else if (file.isDirectory()) {
                recursiveHash(file, basePath, hashes);
            }
        }
    }

    public static boolean filesMatch(File file1, File file2)
            throws IOException, NoSuchAlgorithmException {
        if (!file1.exists() || !file2.exists()) {
            return false;
        }
        if (file1.length() != file2.length()) {
            return false;
        }
        return computeHash(file1).equals(computeHash(file2));
    }

    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 2] = HEX_CHARS[v >>> 4];
            hexChars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hexChars);
    }
}
