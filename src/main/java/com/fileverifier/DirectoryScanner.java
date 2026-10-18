package com.fileverifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Recursive file scanner producing {@link FileEntry} records.
 *
 * <p>Symlinks are neither followed nor reported: hashing would follow the target while
 * rsync (without {@code --links} semantics decisions made elsewhere) treats the link itself
 * as the payload, and following a link to an ancestor directory recurses forever. Ignored
 * directory names keep huge VCS/OS metadata trees out of the scan.
 */
public final class DirectoryScanner {

    /** Directory names never descended into. */
    public static final List<String> DEFAULT_IGNORES =
            List.of(".git", ".svn", ".hg", ".bzr", "lost+found", ".Trash", "$RECYCLE.BIN");

    public record ScanResult(List<FileEntry> files, int skippedLinks, int ignoredDirs, List<String> warnings) {
        public static ScanResult empty() {
            return new ScanResult(List.of(), 0, 0, List.of());
        }
    }

    private DirectoryScanner() {
    }

    public static ScanResult scanFiles(Path root) {
        return scanFiles(root, DEFAULT_IGNORES);
    }

    public static ScanResult scanFiles(Path root, List<String> ignoreDirNames) {
        if (root == null || !Files.isDirectory(root)) {
            return ScanResult.empty();
        }
        Path base = root.toAbsolutePath().normalize();
        Collector collector = new Collector(base, new HashSet<>(ignoreDirNames));
        try {
            Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, collector);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan directory: " + base, e);
        }
        return collector.result();
    }

    /** Relative, '/'-separated path of {@code file} under {@code base}. */
    public static String relativize(Path base, Path file) {
        Path rel = base.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize());
        return rel.toString().replace(java.io.File.separatorChar, '/');
    }

    private static final class Collector extends SimpleFileVisitor<Path> {
        private final Path base;
        private final Set<String> ignores;
        private final List<FileEntry> files = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private int skippedLinks;
        private int ignoredDirs;

        Collector(Path base, Set<String> ignores) {
            this.base = base;
            this.ignores = ignores;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (dir.equals(base)) {
                return FileVisitResult.CONTINUE;
            }
            if (Files.isSymbolicLink(dir)) {
                skippedLinks++;
                return FileVisitResult.SKIP_SUBTREE;
            }
            if (ignores.contains(dir.getFileName().toString())) {
                ignoredDirs++;
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (Files.isSymbolicLink(file)) {
                skippedLinks++;
                return FileVisitResult.CONTINUE;
            }
            if (attrs.isRegularFile()) {
                files.add(new FileEntry(relativize(base, file), file));
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            warnings.add("Unreadable during scan: " + relativize(base, file) + " (" + exc.getMessage() + ")");
            return FileVisitResult.CONTINUE;
        }

        ScanResult result() {
            files.sort(Comparator.comparing(FileEntry::relPath));
            return new ScanResult(List.copyOf(files), skippedLinks, ignoredDirs, List.copyOf(warnings));
        }
    }
}
