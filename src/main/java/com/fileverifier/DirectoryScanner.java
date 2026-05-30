package com.fileverifier;

import java.io.File;
import java.util.*;

public class DirectoryScanner {

    public static List<String> scanDirectories(File rootDir) {
        List<String> directories = new ArrayList<>();
        if (rootDir == null || !rootDir.exists() || !rootDir.isDirectory()) {
            return directories;
        }
        recursiveScan(rootDir, rootDir.getAbsolutePath(), directories);
        Collections.sort(directories);
        return directories;
    }

    private static void recursiveScan(File dir, String basePath, List<String> directories) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                directories.add(child.getAbsolutePath());
                recursiveScan(child, basePath, directories);
            }
        }
    }
}
