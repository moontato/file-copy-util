package com.fileverifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 3 scan options: ignore patterns, hidden files, and the symlink policies. */
class ScanOptionsTest {

    @TempDir
    Path root;

    private static ScanOptions options(List<String> patterns, boolean hidden,
                                       ScanOptions.SymlinkPolicy policy) {
        return new ScanOptions(patterns, hidden, policy);
    }

    private List<String> paths(DirectoryScanner.ScanResult result) {
        return result.files().stream().map(FileEntry::relPath).toList();
    }

    @Test
    void globPatternsMatchRelativePathsAndEntryNames() throws Exception {
        TestSupport.write(root.resolve("keep.txt"), "1");
        TestSupport.write(root.resolve("logs/app.log"), "2");
        TestSupport.write(root.resolve("sub/skip.log"), "3");
        TestSupport.write(root.resolve("tmp/x.tmp"), "4");

        DirectoryScanner.ScanResult result = DirectoryScanner.scanFiles(root,
                options(List.of("logs", "*.tmp"), true, ScanOptions.SymlinkPolicy.SKIP));

        assertEquals(List.of("keep.txt", "sub/skip.log"), paths(result));
        assertEquals(1, result.ignoredDirs(), "the logs subtree is skipped whole");
    }

    @Test
    void aSuppliedIgnoreListReplacesTheDefaults() throws Exception {
        TestSupport.write(root.resolve("keep.txt"), "1");
        TestSupport.write(root.resolve(".git/config"), "2");
        TestSupport.write(root.resolve("build/out.class"), "3");

        DirectoryScanner.ScanResult result = DirectoryScanner.scanFiles(root,
                options(List.of("build"), true, ScanOptions.SymlinkPolicy.SKIP));

        assertTrue(paths(result).contains(".git/config"), paths(result).toString());
        assertTrue(!paths(result).contains("build/out.class"));
    }

    @Test
    void hiddenFilesAndHiddenDirectoriesCanBeExcluded() throws Exception {
        TestSupport.write(root.resolve("normal.txt"), "1");
        TestSupport.write(root.resolve(".hidden.txt"), "2");
        TestSupport.write(root.resolve("sub/.cache/blob.bin"), "3");

        DirectoryScanner.ScanResult included = DirectoryScanner.scanFiles(root,
                options(List.of(), true, ScanOptions.SymlinkPolicy.SKIP));
        assertEquals(List.of(".hidden.txt", "normal.txt", "sub/.cache/blob.bin"), paths(included));

        DirectoryScanner.ScanResult excluded = DirectoryScanner.scanFiles(root,
                options(List.of(), false, ScanOptions.SymlinkPolicy.SKIP));
        assertEquals(List.of("normal.txt"), paths(excluded));
        assertEquals(1, excluded.ignoredDirs(), "the sub/.cache subtree is skipped whole");
    }

    @Test
    void linkPolicyReportsSymlinksUnderTheirOwnPath() throws Exception {
        TestSupport.write(root.resolve("real.txt"), "payload\n");
        Files.createSymbolicLink(root.resolve("link.txt"), root.resolve("real.txt"));

        DirectoryScanner.ScanResult skip = DirectoryScanner.scanFiles(root,
                options(List.of(), true, ScanOptions.SymlinkPolicy.SKIP));
        assertEquals(List.of("real.txt"), paths(skip));
        assertEquals(1, skip.skippedLinks());

        DirectoryScanner.ScanResult link = DirectoryScanner.scanFiles(root,
                options(List.of(), true, ScanOptions.SymlinkPolicy.LINK));
        assertEquals(List.of("link.txt", "real.txt"), paths(link));
        assertTrue(Files.isSymbolicLink(root.resolve("link.txt")));
    }

    @Test
    void linkPolicyDoesNotDescendIntoSymlinkedDirectories() throws Exception {
        TestSupport.write(root.resolve("dir/a.txt"), "1");
        TestSupport.write(root.resolve("real.txt"), "2");
        Files.createSymbolicLink(root.resolve("mirror"), root.resolve("dir"));

        DirectoryScanner.ScanResult result = DirectoryScanner.scanFiles(root,
                options(List.of(), true, ScanOptions.SymlinkPolicy.LINK));

        assertEquals(List.of("dir/a.txt", "real.txt"), paths(result));
        assertEquals(1, result.skippedLinks());
    }

    @Test
    void followPolicyResolvesLinkTargetsAndWalksLinkedDirectories() throws Exception {
        TestSupport.write(root.resolve("dir/a.txt"), "1");
        TestSupport.write(root.resolve("real.txt"), "2");
        Files.createSymbolicLink(root.resolve("mirror"), root.resolve("dir"));
        Files.createSymbolicLink(root.resolve("alias.txt"), root.resolve("real.txt"));

        DirectoryScanner.ScanResult result = DirectoryScanner.scanFiles(root,
                options(List.of(), true, ScanOptions.SymlinkPolicy.FOLLOW));

        assertEquals(List.of("alias.txt", "dir/a.txt", "mirror/a.txt", "real.txt"), paths(result));
        // The link is reported under its own path, and hashing follows its target - which is what
        // makes a LINK-vs-FOLLOW comparison match what rsync puts at the destination.
        assertEquals("alias.txt", result.files().stream()
                .filter(f -> f.relPath().equals("alias.txt")).findFirst().orElseThrow()
                .absolutePath().getFileName().toString());
        assertEquals(FileHasher.computeHash(root.resolve("real.txt")),
                FileHasher.computeHash(root.resolve("alias.txt")));
    }

    @Test
    void followPolicyReportsALinkCycleAsAWarningInsteadOfRecurring() throws Exception {
        TestSupport.write(root.resolve("dir/a.txt"), "1");
        Files.createSymbolicLink(root.resolve("dir/up"), root);

        DirectoryScanner.ScanResult result = DirectoryScanner.scanFiles(root,
                options(List.of(), true, ScanOptions.SymlinkPolicy.FOLLOW));

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Symlink cycle")),
                result.warnings().toString());
        assertTrue(paths(result).contains("dir/a.txt"), paths(result).toString());
        assertTrue(!paths(result).contains("dir/up/a.txt"), paths(result).toString());
    }

    @Test
    void aMalformedGlobIsSkippedInsteadOfFailingTheScan() throws Exception {
        TestSupport.write(root.resolve("keep.txt"), "1");

        DirectoryScanner.ScanResult result = DirectoryScanner.scanFiles(root,
                options(List.of("[unclosed", "keep2.txt"), true, ScanOptions.SymlinkPolicy.SKIP));

        assertEquals(List.of("keep.txt"), paths(result));
    }

    @Test
    void parseTurnsTheUiFieldIntoPatterns() {
        ScanOptions parsed = ScanOptions.parse("  *.tmp, node_modules ,,", true,
                ScanOptions.SymlinkPolicy.LINK);
        assertEquals(List.of("*.tmp", "node_modules"), parsed.ignorePatterns());
        assertEquals(ScanOptions.SymlinkPolicy.LINK, parsed.symlinkPolicy());

        assertEquals(List.of(), ScanOptions.parse(null, true, ScanOptions.SymlinkPolicy.SKIP)
                .ignorePatterns());
    }

    @Test
    void defaultsIgnoreVcsMetadataAndIncludeHiddenFiles() {
        assertTrue(ScanOptions.DEFAULTS.ignorePatterns().contains(".git"));
        assertTrue(ScanOptions.DEFAULTS.includeHidden());
        assertEquals(ScanOptions.SymlinkPolicy.SKIP, ScanOptions.DEFAULTS.symlinkPolicy());
    }
}
