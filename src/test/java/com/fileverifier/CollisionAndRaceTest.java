package com.fileverifier;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two things a filesystem can do that the copy cannot see from inside: two names that are one file on the
 * destination, and a source file that changes while the copy is running. Neither needs a special disk to
 * test what the program says about it.
 */
class CollisionAndRaceTest {

    private static final FileSyncEngine.ProgressListener SILENT = new FileSyncEngine.ProgressListener() {
        @Override
        public void onCompletion(FileSyncEngine.JobResult result) {
        }
    };

    @Test
    void namesThatDifferOnlyByCaseAreNamedAsARisk(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path dst = Files.createDirectory(tmp.resolve("dst"));
        TestSupport.write(src.resolve("A.txt"), "upper");
        TestSupport.write(src.resolve("a.txt"), "lower");
        TestSupport.write(src.resolve("keep.txt"), "keep");

        FileSyncEngine.JobResult result = FileSyncEngine.runOnce(
            TestSupport.request(src, dst, FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selectAllOf(src), FileSyncEngine.Selection.none()), SILENT);

        // Both names are genuinely two files here, so the job runs; what it must do is say what such a
        // pair means on a destination that folds case.
        assertEquals(FileSyncEngine.JobStatus.COMPLETED, result.status(), result.message());
        assertEquals(3, result.filesCopied());
        String collision = result.warnings().stream()
            .filter(w -> w.contains("name collision"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no collision warning: " + result.warnings()));
        assertTrue(collision.contains("A.txt") && collision.contains("a.txt"), collision);
        assertTrue(collision.contains("case-insensitive"), collision);
        assertEquals(1, result.warnings().stream().filter(w -> w.contains("name collision")).count(),
            "one collision group, not one warning per file: " + result.warnings());
    }

    /** The detector itself, without a filesystem in the way. */
    @Test
    void whatCountsAsTheSameName() {
        List<FileEntry> entries = List.of(
            entry("A.txt"), entry("a.txt"),
            entry("caf\u00e9.txt"), entry("cafe\u0301.txt"),           // composed and decomposed forms
            entry("sub/A.txt"), entry("sub/a.txt"),
            entry("top.txt"), entry("sub/top.txt"));                   // different directories: not a clash

        List<String> collisions = FileSyncEngine.collidingNames(entries);

        assertEquals(3, collisions.size(), collisions.toString());
        assertTrue(collisions.stream().anyMatch(c -> c.equals("A.txt / a.txt")), collisions.toString());
        assertTrue(collisions.stream().anyMatch(c -> c.contains("caf") && c.contains("cafe\u0301")),
                "accent composition is the same kind of clash as case: " + collisions);
        assertTrue(collisions.stream().anyMatch(c -> c.equals("sub/A.txt / sub/a.txt")), collisions.toString());
        assertTrue(collisions.stream().noneMatch(c -> c.contains("sub/top.txt")),
                "the directory part belongs to the name: " + collisions);
        assertTrue(FileSyncEngine.collidingNames(List.of(entry("plain.txt"), entry("other.txt"))).isEmpty(),
                "an ordinary tree gets no warning");
    }

    @Test
    void aSourceFileThatMovesMidCopyIsNamedAsThat(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path dst = Files.createDirectory(tmp.resolve("dst"));
        TestSupport.write(src.resolve("moving.txt"), "original content");
        TestSupport.write(dst.resolve("moving.txt"), "something else entirely");

        String previous = FileSyncEngine.rsyncBinary();
        try {
            // Transfers nothing, but rewrites the source file while it is "running".
            FileSyncEngine.setRsyncBinary(TestSupport.mutatingFakeRsync(tmp,
                "", "moving.txt", "content changed while the copy ran", 0).toString());
            FileSyncEngine.JobResult result = FileSyncEngine.runOnce(
                TestSupport.request(src, dst, FileSyncEngine.JobType.COPY_AND_VERIFY,
                    TestSupport.selection("moving.txt"), FileSyncEngine.Selection.none()), SILENT);

            assertEquals(1, result.filesMismatched());
            String warning = result.warnings().stream()
                .filter(w -> w.contains("changed in the source while the job ran"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "a moving source file has to be called that: " + result.warnings()));
            assertTrue(warning.contains("moving.txt"), warning);
            assertTrue(result.outcomes().get(0).kind().equals("MISMATCH"),
                "the verdict stays a mismatch; the warning is what explains it");
        } finally {
            FileSyncEngine.setRsyncBinary(previous);
        }
    }

    /** A VERIFY_ONLY job has no pre-transfer digest, so it cannot claim a file moved. */
    @Test
    void aVerifyOnlyMismatchDoesNotBlameTheSource(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path dst = Files.createDirectory(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "source");
        TestSupport.write(dst.resolve("a.txt"), "destination");

        FileSyncEngine.JobResult result = FileSyncEngine.runOnce(
            TestSupport.request(src, dst, FileSyncEngine.JobType.VERIFY_ONLY,
                TestSupport.selection("a.txt"), FileSyncEngine.Selection.none()), SILENT);

        assertEquals(1, result.filesMismatched());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("changed in the source")),
            "nothing was copied, so nothing has a before-and-after to compare: " + result.warnings());
    }

    /** The partial-file scan runs on a failed copy too, not only on a cancel or a timeout. */
    @Test
    void aFailedCopyNamesTheHalfWrittenFileItLeft(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path dst = Files.createDirectory(tmp.resolve("dst"));
        TestSupport.write(src.resolve("data"), "payload");
        TestSupport.write(src.resolve("later"), "payload");

        String previous = FileSyncEngine.rsyncBinary();
        try {
            FileSyncEngine.setRsyncBinary(TestSupport.failingFakeRsync(tmp,
                ">f+++++++++ data\n"
                    + "  10,000,000  60%   2.1MB/s   00:00:04 (xfr#1, to-chk=1/2)\n"
                    + "rsync: write failed on data: No space left on device\n", 23, ".data.AAAAAA").toString());

            FileSyncEngine.JobResult result = FileSyncEngine.runOnce(
                TestSupport.request(src, dst, FileSyncEngine.JobType.COPY_AND_VERIFY,
                    TestSupport.selection("data", "later"), FileSyncEngine.Selection.none()), SILENT);

            assertEquals(FileSyncEngine.JobStatus.FAILED, result.status());
            assertEquals(1, result.filesCopied(), result.message());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains(".data.AAAAAA")),
                "the half-written file rsync left behind has to be named: " + result.warnings());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("No space left")),
                "rsync's own reason belongs in the report: " + result.warnings());
        } finally {
            FileSyncEngine.setRsyncBinary(previous);
        }
    }

    /**
     * The detector only reads the relative path. A placeholder absolute path also keeps the fixture
     * buildable in a JVM whose locale cannot encode the very name under test - which is the situation the
     * names describe.
     */
    private static FileEntry entry(String relPath) {
        return new FileEntry(relPath, Path.of("placeholder"));
    }
}
