package com.fileverifier;

import java.io.File;
import java.util.*;

public class DirectoryScanner {

    public static List<String> scanFiles(File rootDir) {
        List<String> files = new ArrayList<>();
        if (rootDir == null || !rootDir.exists() || !rootDir.isDirectory()) {
            return files;
        }
        recursiveScan(rootDir, rootDir.getAbsolutePath(), files);
        Collections.sort(files);
        return files;
    }

    private static void recursiveScan(File dir, String basePath, List<String> files) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isFile()) {
                files.add(child.getAbsolutePath());
            } else if (child.isDirectory()) {
                recursiveScan(child, basePath, files);
            }
        }
    }
}
