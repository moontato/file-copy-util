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
import java.util.List;
import java.util.Set;

/**
 * Recursive file scanner producing {@link FileEntry} records.
 *
 * <p>Contract:
 * <ul>
 *   <li>relative, {@code /}-separated, sorted paths - {@link FileEntry#relPath()} is the identity
 *       used by the frame's selection, rsync's {@code --files-from} list, and the log;</li>
 *   <li>{@link ScanOptions} decides what is ignored (exact names and glob patterns, matched against
 *       both the relative path and the entry name), whether hidden entries are included, and what
 *       happens to symlinks;</li>
 *   <li>a missing or non-directory root scans as empty rather than throwing;</li>
 *   <li>unreadable entries become {@link ScanResult#warnings()} instead of aborting the walk.</li>
 * </ul>
 */
public final class DirectoryScanner {

    /** Directory names never descended into (the default value of {@link ScanOptions#ignorePatterns()}). */
    public static final List<String> DEFAULT_IGNORES = ScanOptions.DEFAULT_IGNORED_DIRS;

    public record ScanResult(List<FileEntry> files, int skippedLinks, int ignoredDirs, List<String> warnings) {
        public ScanResult {
            files = List.copyOf(files);
            warnings = List.copyOf(warnings);
        }

        public static ScanResult empty() {
            return new ScanResult(List.of(), 0, 0, List.of());
        }
    }

    private DirectoryScanner() {
    }

    /** Scan with the defaults: ignore VCS/OS metadata, include hidden files, skip symlinks. */
    public static ScanResult scanFiles(Path root) {
        return scanFiles(root, ScanOptions.DEFAULTS);
    }

    /** Defaults replaced by an explicit ignore list (used by tests and simple callers). */
    public static ScanResult scanFiles(Path root, List<String> ignoreDirNames) {
        return scanFiles(root, new ScanOptions(ignoreDirNames, true, ScanOptions.SymlinkPolicy.SKIP));
    }

    public static ScanResult scanFiles(Path root, ScanOptions options) {
        if (root == null || !Files.isDirectory(root)) {
            return ScanResult.empty();
        }
        Path base = root.toAbsolutePath().normalize();
        Collector collector = new Collector(base, options);
        try {
            Files.walkFileTree(base, collector.walkOptions(), Integer.MAX_VALUE, collector);
        } catch (java.nio.file.InvalidPathException e) {
            // The character encoding used for file names comes from the locale. Under LANG=C every
            // non-ASCII byte in a name is unrepresentable, and the walk gives up rather than listing
            // what it could not decode: report the entries that were reached and say why.
            collector.noteUnreadable();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan directory: " + base, e);
        }
        return collector.result();
    }

    /** Relative, '/'-separated path of {@code file} under {@code base}, both sides normalized. */
    public static String relativize(Path base, Path file) {
        Path rel = base.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
        return rel.toString().replace(java.io.File.separatorChar, '/');
    }

    /**
     * A printable single-element name for a path. {@link Path#getFileName()} is null for a root path,
     * and a root is a perfectly ordinary thing to be asked to scan, so the name has to survive that.
     */
    private static String nameOf(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    /** Convenience for callers that only need the file list. */
    public static List<FileEntry> listFiles(Path root) {
        return scanFiles(root).files();
    }

    private static final class Collector extends SimpleFileVisitor<Path> {
        private final Path base;
        private final ScanOptions options;
        private final List<ScanOptions.IgnoreRule> rules;
        private final List<FileEntry> files = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final ScanOptions.SymlinkPolicy policy;
        private int skippedLinks;
        private int ignoredDirs;
        private int unreadableNames;

        Collector(Path base, ScanOptions options) {
            this.base = base;
            this.options = options;
            this.rules = options.compileRules();
            this.policy = options.symlinkPolicy();
        }

        Set<FileVisitOption> walkOptions() {
            // FOLLOW is the only policy that descends through symlinked directories, and it is the
            // only one where a link cycle is possible; FileVisitOption.FOLLOW_LINKS makes the walk
            // detect that cycle and report it instead of recursing forever.
            return policy == ScanOptions.SymlinkPolicy.FOLLOW
                    ? EnumSet.of(FileVisitOption.FOLLOW_LINKS)
                    : EnumSet.noneOf(FileVisitOption.class);
        }

        void noteUnreadable() {
            unreadableNames++;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            try {
                return lookAtDirectory(dir);
            } catch (java.nio.file.InvalidPathException e) {
                noteUnreadable();
                return FileVisitResult.SKIP_SUBTREE;
            }
        }

        private FileVisitResult lookAtDirectory(Path dir) {
            if (dir.equals(base)) {
                return FileVisitResult.CONTINUE;
            }
            String rel = relativize(base, dir);
            if (options.ignores(rel, rules)) {
                ignoredDirs++;
                return FileVisitResult.SKIP_SUBTREE;
            }
            if (!options.includeHidden() && ScanOptions.isHiddenName(nameOf(dir))) {
                ignoredDirs++;
                return FileVisitResult.SKIP_SUBTREE;
            }
            if (Files.isSymbolicLink(dir)) {
                // Without FOLLOW_LINKS a symlinked directory reaches visitFile, not this method;
                // it is reached here only in FOLLOW mode, where descending is the whole point.
                skippedLinks++;
                return FileVisitResult.CONTINUE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            try {
                lookAtFile(file, attrs);
            } catch (java.nio.file.InvalidPathException e) {
                // A name this JVM cannot represent cannot be listed, copied or hashed. It is one
                // warning for the run, not a crash and not a silently missing file.
                noteUnreadable();
            }
            return FileVisitResult.CONTINUE;
        }

        private void lookAtFile(Path file, BasicFileAttributes attrs) {
            boolean link = Files.isSymbolicLink(file);
            if (link) {
                if (policy == ScanOptions.SymlinkPolicy.SKIP) {
                    skippedLinks++;
                    return;
                }
                if (policy == ScanOptions.SymlinkPolicy.FOLLOW && Files.isDirectory(file)) {
                    // Under FOLLOW the walk descends through a linked directory and lists what is inside
                    // it; the link itself is not listed as an object.
                    skippedLinks++;
                    return;
                }
                if (!Files.exists(file)) {
                    warnings.add("Dangling symlink: " + relativize(base, file));
                    if (policy == ScanOptions.SymlinkPolicy.FOLLOW) {
                        // FOLLOW's verdict is about bytes, and a dangling link has no bytes to compare.
                        skippedLinks++;
                        return;
                    }
                    // Under LINK the link is the object. It is carried, so it is copied as a link and its
                    // text is compared; whether anything stands at its target changes nothing about that.
                }
                // Without FOLLOW_LINKS a link to a directory reaches visitFile rather than
                // preVisitDirectory, which is what LINK wants: the link is listed, and the walk does not
                // descend through it into somebody else's directory tree.
            }
            String rel = relativize(base, file);
            if (options.ignores(rel, rules)) {
                return;
            }
            if (!options.includeHidden() && ScanOptions.isHiddenName(nameOf(file))) {
                return;
            }
            if (!link && !attrs.isRegularFile()) {
                // Fifo, socket, device node: nothing meaningful to copy or hash.
                return;
            }
            files.add(new FileEntry(rel, file));
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            boolean cycle = exc instanceof java.nio.file.FileSystemLoopException;
            warnings.add((cycle ? "Symlink cycle: " : "Unreadable during scan: ")
                    + relativize(base, file) + " (" + exc.getMessage() + ")");
            if (cycle) {
                ignoredDirs++;
            }
            return FileVisitResult.CONTINUE;
        }

        ScanResult result() {
            if (unreadableNames > 0) {
                warnings.add(unreadableNames + " entry(ies) could not be read because their names contain "
                    + "characters this JVM cannot represent (file-name encoding "
                    + System.getProperty("sun.jnu.encoding") + "). Run under a UTF-8 locale - for example "
                    + "LC_ALL=C.UTF-8 - to list, copy and verify such files.");
            }
            files.sort(Comparator.comparing(FileEntry::relPath));
            return new ScanResult(files, skippedLinks, ignoredDirs, warnings);
        }
    }
}
