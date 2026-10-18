package com.fileverifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryScannerTest {

    @TempDir
    Path root;

    @Test
    void returnsRelativeSortedPathsAndCarriesAbsolutePaths() throws Exception {
        TestSupport.write(root.resolve("top.txt"), "1");
        TestSupport.write(root.resolve("a/b.txt"), "2");
        TestSupport.write(root.resolve("a/c/deep/d.txt"), "3");

        DirectoryScanner.ScanResult result = DirectoryScanner.scanFiles(root);

        assertEquals(List.of("a/b.txt", "a/c/deep/d.txt", "top.txt"),
                result.files().stream().map(FileEntry::relPath).toList());
        for (FileEntry entry : result.files()) {
            assertTrue(Files.isRegularFile(entry.absolutePath()), entry.toString());
            assertTrue(entry.absolutePath().startsWith(root.toAbsolutePath().normalize()));
        }
        assertEquals(0, result.skippedLinks());
        assertEquals(0, result.ignoredDirs());
    }

    @Test
    void ignoresVcsAndSystemDirectories() throws Exception {
        TestSupport.write(root.resolve("keep.txt"), "1");
        TestSupport.write(root.resolve(".git/objects/pack.bin"), "2");
        TestSupport.write(root.resolve(".svn/tmp.txt"), "3");

        DirectoryScanner.ScanResult result = DirectoryScanner.scanFiles(root);

        assertEquals(List.of("keep.txt"), TestSupport.relPathsUnder(root));
        assertEquals(2, result.ignoredDirs());
    }

    @Test
    void customIgnoreListReplacesDefaults() throws Exception {
        TestSupport.write(root.resolve("keep.txt"), "1");
        TestSupport.write(root.resolve(".git/config"), "2");
        TestSupport.write(root.resolve("build/out.class"), "3");

        DirectoryScanner.ScanResult result = DirectoryScanner.scanFiles(root, List.of("build"));

        List<String> paths = result.files().stream().map(FileEntry::relPath).toList();
        assertTrue(paths.contains(".git/config"), paths.toString());
        assertTrue(paths.contains("keep.txt"));
        assertTrue(!paths.contains("build/out.class"));
        assertEquals(1, result.ignoredDirs());
    }

    @Test
    void symlinkToAncestorDirectoryDoesNotRecurseForever() throws Exception {
        TestSupport.write(root.resolve("data/file.txt"), "1");
        Files.createSymbolicLink(root.resolve("loop"), root);
        Files.createSymbolicLink(root.resolve("data/self"), root.resolve("data"));

        DirectoryScanner.ScanResult result = DirectoryScanner.scanFiles(root);

        assertEquals(List.of("data/file.txt"),
                result.files().stream().map(FileEntry::relPath).toList());
        assertEquals(2, result.skippedLinks());
    }

    @Test
    void symlinkToFileIsSkippedNotHashedAsAFile() throws Exception {
        TestSupport.write(root.resolve("real.txt"), "1");
        Files.createSymbolicLink(root.resolve("link.txt"), root.resolve("real.txt"));

        DirectoryScanner.ScanResult result = DirectoryScanner.scanFiles(root);

        assertEquals(List.of("real.txt"), result.files().stream().map(FileEntry::relPath).toList());
        assertEquals(1, result.skippedLinks());
    }

    @Test
    void missingOrNullRootYieldsEmptyResult() {
        assertTrue(DirectoryScanner.scanFiles(root.resolve("does-not-exist")).files().isEmpty());
        assertTrue(DirectoryScanner.scanFiles(null).files().isEmpty());
        assertTrue(DirectoryScanner.scanFiles(root.resolve("top.txt").resolve("nope")).files().isEmpty());
    }

    @Test
    void unreadableDirectoryBecomesAWarningNotACrash() throws Exception {
        TestSupport.write(root.resolve("keep.txt"), "1");
        Path sealed = TestSupport.write(root.resolve("sealed/inside.txt"), "2");
        Path sealedDir = sealed.getParent();
        // Execute-only: the directory can be traversed but not listed, which is what makes
        // FileVisitor.visitFileFailed fire with an AccessDeniedException.
        Files.setPosixFilePermissions(sealedDir, EnumSet.of(PosixFilePermission.OWNER_EXECUTE));
        try {
            DirectoryScanner.ScanResult result = DirectoryScanner.scanFiles(root);
            assertTrue(result.warnings().size() >= 1, "expected a warning, got " + result.warnings());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("sealed")), result.warnings().toString());
            assertTrue(!result.files().stream().map(FileEntry::relPath).toList().contains("sealed/inside.txt"));
            assertTrue(result.files().stream().map(FileEntry::relPath).toList().contains("keep.txt"),
                    "an unreadable subdirectory must not abort the rest of the scan");
        } finally {
            Files.setPosixFilePermissions(sealedDir, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    void relativizesThroughNonNormalizedRoots() throws Exception {
        TestSupport.write(root.resolve("sub/file.txt"), "1");
        assertEquals("file.txt", DirectoryScanner.relativize(root.resolve("sub").resolve("."),
                root.resolve("sub/file.txt")));
        assertEquals("sub/file.txt", DirectoryScanner.relativize(root.resolve("sub").resolve(".."),
                root.resolve("sub/file.txt")));
    }

    @Test
    void fileEntryRejectsAbsolutePathIdentity() {
        try {
            new FileEntry("/absolute.txt", root.resolve("x"));
            throw new AssertionError("FileEntry must reject absolute relPath");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("relative"));
        }
    }
}
