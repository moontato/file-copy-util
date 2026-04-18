package com.fileverifier;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

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

    public static List<String> getRelativePaths(File rootDir, List<String> absolutePaths) {
        String rootPath = rootDir.getAbsolutePath();
        String searchPath = rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;
        return absolutePaths.stream()
            .map(path -> path.startsWith(searchPath) ? path.substring(searchPath.length()) : path)
            .sorted()
            .collect(Collectors.toList());
    }

    public static Map<String, String> buildPathMap(File rootDir, List<String> relativePaths) {
        Map<String, String> map = new LinkedHashMap<>();
        String rootPath = rootDir.getAbsolutePath();
        if (!rootPath.endsWith(File.separator)) {
            rootPath += File.separator;
        }
        for (String rel : relativePaths) {
            map.put(rel, rootPath + rel);
        }
        return map;
    }
}
