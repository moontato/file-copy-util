package com.fileverifier;

import com.fileverifier.FileSyncEngine.FileOutcome;
import com.fileverifier.FileSyncEngine.JobRequest;
import com.fileverifier.FileSyncEngine.JobResult;
import com.fileverifier.FileSyncEngine.JobType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verification became a switch instead of a mode: a job can copy without comparing anything. What has to
 * stay true is that such a job says what it did - every file it wrote, and in its own words that nothing
 * was compared - rather than looking like a verification job that found nothing worth reporting.
 */
class VerificationOptionalTest {

    private static final FileSyncEngine.ProgressListener SILENT = new FileSyncEngine.ProgressListener() {
        @Override
        public void onPhase(String phase) {
        }

        @Override
        public void onProgress(int percent, String statusText) {
        }

        @Override
        public void onFileProcessed(String relPath, String kind, String srcHash, String dstHash) {
        }

        @Override
        public void onCompletion(JobResult result) {
        }
    };

    private static void assumeRsync() {
        assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
    }

    @Test
    void aCopyThatVerifiesNothingStillNamesEveryFileItWrote(@TempDir Path tmp) throws Exception {
        assumeRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a");
        TestSupport.write(src.resolve("b.txt"), "b");
        Files.createDirectories(src.resolve("sub"));
        TestSupport.write(src.resolve("sub/c.txt"), "c");

        // A copy is a read of the source as well as a write to the destination: the tree it reads has to
        // be the tree it leaves.
        Map<String, String> sourceBefore = TestSupport.digests(src);
        JobResult result = FileSyncEngine.runOnce(TestSupport.request(src, dst, JobType.COPY_ONLY,
            TestSupport.selectAllOf(src), FileSyncEngine.Selection.none()), SILENT);
        TestSupport.assertUnchanged(src, sourceBefore);

        assertTrue(result.isSuccess(), result.message());
        assertEquals(3, result.filesCopied(), result.message());
        assertEquals(0, result.filesChecked(), "nothing was compared, so nothing was checked");
        assertEquals(0, result.filesMatched() + result.filesMismatched() + result.filesMissing());

        List<String> kinds = kinds(result);
        assertEquals(List.of("COPIED", "COPIED", "COPIED"), kinds,
            "the transfer is the only fact this job knows, so it is the record: " + result.outcomes());
        for (FileOutcome outcome : result.outcomes()) {
            assertTrue(outcome.sourceHash() == null && outcome.destHash() == null,
                "a COPIED row cannot carry hashes - none were computed: " + outcome);
        }
        Set<String> named = new java.util.HashSet<>();
        for (FileOutcome outcome : result.outcomes()) {
            named.add(outcome.relPath());
        }
        assertEquals(Set.of("a.txt", "b.txt", "sub/c.txt"), named);

        assertTrue(result.message().contains("Verification was turned off"), result.message());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Verification was turned off")
                        && w.contains("not whether the copies are correct")),
            String.valueOf(result.warnings()));
    }

    @Test
    void aCopyThatVerifiesNothingReadsSourceFilesToHashThem(@TempDir Path tmp) throws Exception {
        assumeRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Path unreadable = TestSupport.write(src.resolve("secret.txt"), "secret");
        Files.setPosixFilePermissions(unreadable, EnumSet.noneOf(PosixFilePermission.class));

        // The contrast is the point: a verifying job has to read the source to hash it, and says so when it
        // cannot. A job that will compare nothing has no reason to read it, and must not report a read
        // failure for a read it never attempted.
        JobResult verifying = FileSyncEngine.runOnce(TestSupport.request(src, dst, JobType.COPY_AND_VERIFY,
            TestSupport.selectAllOf(src), FileSyncEngine.Selection.none()), SILENT);
        JobResult copying = FileSyncEngine.runOnce(TestSupport.request(src, dst, JobType.COPY_ONLY,
            TestSupport.selectAllOf(src), FileSyncEngine.Selection.none()), SILENT);

        assertTrue(verifying.warnings().stream().anyMatch(w -> w.contains("Could not read")),
            "the hashing pass is what reads the source, and it should name the file it could not open: "
                + verifying.warnings());
        assertTrue(copying.warnings().stream().noneMatch(w -> w.contains("Could not read")),
            "a copy with verification off hashed nothing, so it cannot have a hashing failure: "
                + copying.warnings());
    }

    @Test
    void aDryRunWithVerificationOffDoesNotCallAnUnwrittenFileCopied(@TempDir Path tmp) throws Exception {
        assumeRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a");

        JobResult result = FileSyncEngine.runOnce(TestSupport.request(src, dst, JobType.COPY_ONLY,
            TestSupport.selectAllOf(src), FileSyncEngine.Selection.none(), ScanOptions.DEFAULTS, false, true),
            SILENT);

        assertTrue(result.isSuccess(), result.message());
        try (var listed = Files.list(dst)) {
            assertEquals(0, listed.count(), "a dry run writes nothing: " + TestSupport.relPathsUnder(dst));
        }
        assertEquals(List.of("WOULD_COPY"), kinds(result),
            "an export that says COPIED for a file nobody wrote records an event that did not happen");
        assertTrue(result.message().contains("would have been copied"), result.message());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("--dry-run")),
            String.valueOf(result.warnings()));
    }

    @Test
    void aVerifyingJobRecordsOneRowPerFileNotOneForTheCopyAndOneForTheVerdict(@TempDir Path tmp)
        throws Exception {
        assumeRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a");

        JobResult result = FileSyncEngine.runOnce(TestSupport.request(src, dst, JobType.COPY_AND_VERIFY,
            TestSupport.selectAllOf(src), FileSyncEngine.Selection.none()), SILENT);

        assertTrue(result.isSuccess(), result.message());
        assertEquals(1, result.filesCopied());
        assertEquals(List.of("OK"), kinds(result),
            "the verdict already says the file was copied and matched; a second row would double the table");
    }

    @Test
    void aCopyAbortedByTimeoutDoesNotClaimItComparedFiles(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a");
        Path fake = TestSupport.hangingFakeRsync(Files.createDirectories(tmp.resolve("tools")),
            "  50%    100 0:00:01    0.00 kB/s    0:00:01    1.txt", "a.txt.partial");

        FileSyncEngine engine = new FileSyncEngine();
        try {
            engine.useRsync(fake.toString());
            engine.setJobTimeoutMillis(1200L);

            JobResult copyOnly = engine.execute(TestSupport.request(src, dst, JobType.COPY_ONLY,
                TestSupport.selectAllOf(src), FileSyncEngine.Selection.none()), SILENT);
            assertTrue(copyOnly.message().contains("verification was off, so nothing was compared"),
                copyOnly.message());

            JobResult verifying = engine.execute(TestSupport.request(src, dst, JobType.COPY_AND_VERIFY,
                TestSupport.selectAllOf(src), FileSyncEngine.Selection.none()), SILENT);
            assertTrue(verifying.message().contains("file(s) were verified"), verifying.message());
        } finally {
            engine.close();
        }
    }

    private static List<String> kinds(JobResult result) {
        List<String> kinds = new ArrayList<>();
        for (FileOutcome outcome : result.outcomes()) {
            kinds.add(outcome.kind());
        }
        java.util.Collections.sort(kinds);
        return kinds;
    }
}
