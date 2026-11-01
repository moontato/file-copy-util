package com.fileverifier;

import com.fileverifier.FileSyncEngine.FileOutcome;
import com.fileverifier.FileSyncEngine.JobRequest;
import com.fileverifier.FileSyncEngine.JobResult;
import com.fileverifier.FileSyncEngine.JobStatus;
import com.fileverifier.FileSyncEngine.JobType;
import com.fileverifier.FileSyncEngine.ProgressListener;
import com.fileverifier.FileSyncEngine.Selection;
import com.fileverifier.FileSyncEngine.TransferDisposition;
import com.fileverifier.FileSyncEngine.VerificationBasis;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The opt-in "source after copying" verification basis, and the pre-copy basis it must not disturb.
 *
 * <p>Two things are being held apart here, which is the whole point of the feature. A copy whose destination
 * was compared against a digest of the source taken <b>before</b> the transfer can say a source file moved
 * during the job. A copy whose two sides were hashed together <b>after</b> the transfer can only say the two
 * sides read alike during verification. Every test of the new basis checks both halves of that: what it may
 * report, and what it must not.
 *
 * <p>The default basis is exercised in the same tests rather than left to the existing suites, because the
 * claim being made is that the default did not move: the phases, the verdicts, the digests and the source-moved
 * warning are asserted for both bases side by side.
 */
class PostCopyVerificationTest {

    @TempDir
    Path tmp;

    private static void assumeRealRsync() {
        assumeTrue(TestSupport.rsyncAvailable(), "needs a real rsync");
    }

    private static void assumePosixPermissions() {
        assumeTrue(!"root".equals(System.getProperty("user.name")),
                "an unreadable file means nothing to a process that can read anything");
    }

    private static JobRequest job(Path src, Path dst, JobType type, VerificationBasis basis,
                                  ScanOptions options, Selection selected) {
        return new JobRequest(src, dst, type, selected, Selection.none(), options, false, false, basis);
    }

    private static JobRequest job(Path src, Path dst, JobType type, VerificationBasis basis,
                                  ScanOptions options, Selection selected, boolean dryRun) {
        return new JobRequest(src, dst, type, selected, Selection.none(), options, false, dryRun, basis);
    }

    private static JobRequest job(Path src, Path dst, JobType type, VerificationBasis basis, String... names) {
        return job(src, dst, type, basis, ScanOptions.DEFAULTS, TestSupport.selection(names));
    }

    private static JobRequest job(Path src, Path dst, JobType type, VerificationBasis basis, Selection selected) {
        return job(src, dst, type, basis, ScanOptions.DEFAULTS, selected);
    }

    private static JobRequest job(Path src, Path dst, JobType type, VerificationBasis basis,
                                  ScanOptions options, String... names) {
        return job(src, dst, type, basis, options, TestSupport.selection(names));
    }

    /** Runs one job on its own engine, which is what every other suite here does. */
    private static JobResult run(JobRequest job, Path fakeRsync, ProgressListener listener) {
        try (FileSyncEngine engine = new FileSyncEngine()) {
            if (fakeRsync != null) {
                engine.useRsync(fakeRsync.toString());
            }
            return engine.execute(job, listener);
        }
    }

    private static FileOutcome outcome(JobResult result, String name) {
        return result.outcomes().stream().filter(o -> o.relPath().equals(name)).findFirst().orElse(null);
    }

    private static List<String> kinds(JobResult result) {
        return result.outcomes().stream().map(FileOutcome::kind).toList();
    }

    private static List<String> names(JobResult result) {
        return result.outcomes().stream().map(FileOutcome::relPath).toList();
    }

    private static boolean mentionsSourceMoving(JobResult result) {
        return result.warnings().stream().anyMatch(w -> w.contains("changed in the source while the job ran"));
    }

    /** A tree with <i>n</i> files, each different, so a verdict is attributable to a name. */
    private static Path tree(Path dir, int files) throws Exception {
        Files.createDirectories(dir);
        for (int i = 0; i < files; i++) {
            TestSupport.write(dir.resolve("sub").resolve("f" + i + ".bin"), "content of file " + i);
        }
        return dir;
    }

    /** A stand-in rsync that transfers nothing and exits cleanly: the destination already holds the files. */
    private static Path idleRsync(Path dir) throws Exception {
        return TestSupport.fakeRsync(Files.createDirectories(dir), "", 0);
    }

    // ---------------------------------------------------------------- the two bases, side by side

    @Test
    void theDefaultStillReadsTheSourceBeforeItCopiesAndTheNewBasisDoesNot() throws Exception {
        Path src = tree(tmp.resolve("phases-src"), 4);
        Path dst = tree(tmp.resolve("phases-dst-each"), 4);
        Path fake = idleRsync(tmp.resolve("phases-tools"));

        Recording defaultRun = new Recording();
        JobResult before = run(job(src, dst, JobType.COPY_AND_VERIFY,
                VerificationBasis.SOURCE_BEFORE_COPY, TestSupport.selectAllOf(src)), fake, defaultRun);
        assertEquals(JobStatus.COMPLETED, before.status(), before.message());
        assertTrue(defaultRun.phases.contains("hashing source"),
                "the long-standing behaviour is the default, so the source is still read first: " + defaultRun.phases);
        assertTrue(defaultRun.phases.indexOf("copying") > defaultRun.phases.indexOf("hashing source"),
                "and the phase order still says so: " + defaultRun.phases);

        Recording postCopy = new Recording();
        JobResult after = run(job(src, dst, JobType.COPY_AND_VERIFY,
                VerificationBasis.SOURCE_AFTER_COPY, TestSupport.selectAllOf(src)), fake, postCopy);
        assertEquals(JobStatus.COMPLETED, after.status(), after.message());
        assertEquals(List.of("copying", "verifying", "done"), postCopy.phases,
                "nothing is hashed before the transfer under this basis, and the phases have to say that"
                        + " rather than leave a reader wondering whether the hashing was skipped");
    }

    @Test
    void bothBasesGiveTheSameVerdictsInWorkSetOrderForTheSameTree() throws Exception {
        Path src = tree(tmp.resolve("agree-src"), 40);
        Path dst = tree(tmp.resolve("agree-dst"), 40);
        Path fake = idleRsync(tmp.resolve("agree-tools"));

        JobRequest first = job(src, dst, JobType.COPY_AND_VERIFY, VerificationBasis.SOURCE_BEFORE_COPY,
                TestSupport.selectAllOf(src));
        JobRequest second = job(src, dst, JobType.COPY_AND_VERIFY, VerificationBasis.SOURCE_AFTER_COPY,
                TestSupport.selectAllOf(src));
        Recording before = new Recording();
        Recording after = new Recording();
        JobResult preCopy = run(first, fake, before);
        JobResult postCopy = run(second, fake, after);

        assertEquals(40, preCopy.filesMatched(), preCopy.message());
        assertEquals(40, postCopy.filesMatched(), postCopy.message());
        assertEquals(0, postCopy.filesMismatched() + postCopy.filesMissing() + postCopy.filesExtra());
        assertEquals(preCopy.filesChecked(), postCopy.filesChecked());
        assertEquals(names(preCopy), names(postCopy),
                "the reads are in a different order; the report is not - rows follow the captured work set");
        assertEquals(kinds(preCopy), kinds(postCopy), "and the verdicts are the same ones: " + kinds(postCopy));
        assertEquals(preCopy.outcomes().stream().map(FileOutcome::sourceHash).toList(),
                postCopy.outcomes().stream().map(FileOutcome::sourceHash).toList(),
                "the source digests are the same digests, whichever pass took them");
        assertEquals(preCopy.outcomes().stream().map(FileOutcome::destHash).toList(),
                postCopy.outcomes().stream().map(FileOutcome::destHash).toList());
        assertEquals(before.verdicts, after.verdicts, "and the streaming log agrees with the table");
        assertEquals(VerificationBasis.SOURCE_BEFORE_COPY, preCopy.verificationBasis());
        assertEquals(VerificationBasis.SOURCE_AFTER_COPY, postCopy.verificationBasis());
    }

    @Test
    void onlyTheBasisWithABeforeToCompareAgainstCanCallAFileOneThatMoved() throws Exception {
        String movedTo = "content written by somebody else while the copy was running";
        for (VerificationBasis basis : List.of(VerificationBasis.SOURCE_BEFORE_COPY,
                VerificationBasis.SOURCE_AFTER_COPY)) {
            Path base = Files.createDirectories(tmp.resolve("moved-" + basis.name()));
            Path src = Files.createDirectories(base.resolve("src"));
            Path dst = Files.createDirectories(base.resolve("dst"));
            TestSupport.write(src.resolve("a.txt"), "the content the job saw before it started");
            TestSupport.write(dst.resolve("a.txt"), movedTo);
            // Transfers nothing, and rewrites the source while it is "running": the destination now holds
            // what the source holds, and only one basis knows those two used to be different.
            Path fake = TestSupport.mutatingFakeRsync(Files.createDirectories(base.resolve("tools")),
                    "", "a.txt", movedTo, 0);
            JobResult result = run(job(src, dst, JobType.COPY_AND_VERIFY, basis, "a.txt"), fake, null);

            assertEquals(JobStatus.COMPLETED, result.status(), result.message());
            assertEquals(basis, result.verificationBasis());
            if (basis == VerificationBasis.SOURCE_BEFORE_COPY) {
                assertEquals(1, result.filesMismatched(), result.message());
                assertEquals(0, result.filesMatched(), result.message());
                assertTrue(mentionsSourceMoving(result),
                        "a digest from before the transfer is what makes this reportable as movement: "
                                + result.warnings());
            } else {
                assertEquals(1, result.filesMatched(),
                        "the two sides read alike during verification, and that is the whole claim: " + result);
                assertEquals(0, result.filesMismatched() + result.filesMissing(), result.message());
                assertFalse(mentionsSourceMoving(result),
                        "this basis has no before, so it must not imply one: " + result.warnings());
                assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("Verification basis:")),
                        "and the report has to say which basis it used: " + result.warnings());
                FileOutcome row = outcome(result, "a.txt");
                assertNotNull(row);
                assertEquals("OK", row.kind());
                assertNotNull(row.sourceHash());
                assertNotNull(row.destHash());
            }
        }
    }

    @Test
    void aMismatchUnderTheNewBasisDoesNotBlameTheSourceOrClearIt() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("mismatch-src"));
        Path dst = Files.createDirectories(tmp.resolve("mismatch-dst"));
        TestSupport.write(src.resolve("a.txt"), "source content");
        TestSupport.write(dst.resolve("a.txt"), "destination content");
        Path fake = idleRsync(tmp.resolve("mismatch-tools"));

        JobResult result = run(job(src, dst, JobType.COPY_AND_VERIFY,
                VerificationBasis.SOURCE_AFTER_COPY, "a.txt"), fake, null);
        assertEquals(1, result.filesMismatched(), result.message());
        assertEquals(0, result.filesMatched() + result.filesMissing(), result.message());
        assertFalse(mentionsSourceMoving(result),
                "nothing was hashed before the transfer, so nothing here can say the source moved: "
                        + result.warnings());
        FileOutcome row = outcome(result, "a.txt");
        assertNotNull(row);
        assertEquals("MISMATCH", row.kind());
        assertNotNull(row.sourceHash());
        assertNotNull(row.destHash());
    }

    // ---------------------------------------------------------------- absent and unreadable files

    @Test
    void aFileThatNeverArrivedIsStillMissingAndItsSourceDigestIsStillKept() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("missing-src"));
        Path dst = Files.createDirectories(tmp.resolve("missing-dst"));
        TestSupport.write(src.resolve("a.txt"), "a");
        TestSupport.write(src.resolve("b.txt"), "b");
        TestSupport.write(dst.resolve("a.txt"), "a");
        Path fake = idleRsync(tmp.resolve("missing-tools"));

        JobResult result = run(job(src, dst, JobType.COPY_AND_VERIFY,
                VerificationBasis.SOURCE_AFTER_COPY, "a.txt", "b.txt"), fake, null);
        assertEquals(1, result.filesMatched(), result.message());
        assertEquals(1, result.filesMissing(), result.message());
        assertEquals(0, result.filesMismatched(), result.message());
        FileOutcome row = outcome(result, "b.txt");
        assertNotNull(row);
        assertEquals("MISSING", row.kind());
        assertNotNull(row.sourceHash(), "the source was read, and its digest belongs in the report: " + row);
        assertNull(row.destHash(), "there was nothing at the destination to hash: " + row);
        assertEquals(TransferDisposition.NOT_COPIED, row.transfer());
    }

    @Test
    void anUnreadableSourceIsAMismatchAndNotAMissingUnderEitherBasis() throws Exception {
        assumePosixPermissions();
        for (VerificationBasis basis : List.of(VerificationBasis.SOURCE_BEFORE_COPY,
                VerificationBasis.SOURCE_AFTER_COPY)) {
            Path base = Files.createDirectories(tmp.resolve("unreadable-" + basis.name()));
            Path src = Files.createDirectories(base.resolve("src"));
            Path dst = Files.createDirectories(base.resolve("dst"));
            Path file = TestSupport.write(src.resolve("locked.bin"), "unreadable content");
            TestSupport.write(dst.resolve("locked.bin"), "unreadable content");
            Files.setPosixFilePermissions(file, EnumSet.noneOf(PosixFilePermission.class));
            try {
                Path fake = TestSupport.fakeRsync(Files.createDirectories(base.resolve("tools")), "", 0);
                JobResult result = run(job(src, dst, JobType.COPY_AND_VERIFY, basis, "locked.bin"), fake, null);
                FileOutcome row = outcome(result, "locked.bin");
                assertNotNull(row, result.toString());
                assertEquals("MISMATCH", row.kind(),
                        "a file that is there but cannot be read is not a file that is not there: " + row);
                assertEquals(1, result.filesMismatched(), result.message());
                assertEquals(0, result.filesMissing(), result.message());
                assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Could not read")
                                && w.contains("locked.bin")),
                        basis + ": " + result.warnings());
            } finally {
                Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            }
        }
    }

    // ---------------------------------------------------------------- symbolic links

    @Test
    void linksAreJudgedByTheSameRulesWheneverTheSourceWasHashed() throws Exception {
        ScanOptions linkPolicy = ScanOptions.DEFAULTS.withSymlinkPolicy(ScanOptions.SymlinkPolicy.LINK);
        Path src = Files.createDirectories(tmp.resolve("links-src"));
        Path dst = Files.createDirectories(tmp.resolve("links-dst"));
        TestSupport.write(src.resolve("regular.txt"), "regular");
        TestSupport.write(dst.resolve("regular.txt"), "regular");
        // The link text is what LINK compares, so the two links that are meant to match carry the same text.
        Files.createSymbolicLink(src.resolve("same.link"), Path.of("regular.txt"));
        Files.createSymbolicLink(dst.resolve("same.link"), Path.of("regular.txt"));
        Files.createSymbolicLink(src.resolve("other.link"), Path.of("elsewhere.txt"));
        Files.createSymbolicLink(dst.resolve("other.link"), Path.of("a-different-target.txt"));
        // A link in the source with an ordinary file where it should be: two different objects.
        Files.createSymbolicLink(src.resolve("becomes-a-file.link"), Path.of("regular.txt"));
        TestSupport.write(dst.resolve("becomes-a-file.link"), "an ordinary file in the destination");
        Path fake = idleRsync(tmp.resolve("links-tools"));

        Selection selected = TestSupport.selection("regular.txt", "same.link", "other.link",
                "becomes-a-file.link");
        JobResult postCopy = run(job(src, dst, JobType.COPY_AND_VERIFY, VerificationBasis.SOURCE_AFTER_COPY,
                linkPolicy, selected), fake, null);
        JobResult preCopy = run(job(src, dst, JobType.COPY_AND_VERIFY, VerificationBasis.SOURCE_BEFORE_COPY,
                linkPolicy, selected), fake, null);

        assertEquals(kinds(preCopy), kinds(postCopy),
                "the verdict rule is one rule: " + kinds(preCopy) + " vs " + kinds(postCopy));
        Map<String, String> expected = Map.of(
                "regular.txt", "OK",
                "same.link", "OK",
                "other.link", "MISMATCH",
                "becomes-a-file.link", "MISMATCH");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            FileOutcome row = outcome(postCopy, entry.getKey());
            assertNotNull(row, postCopy.toString());
            assertEquals(entry.getValue(), row.kind(), row.toString());
            if (entry.getKey().endsWith(".link")) {
                assertNull(row.sourceHash(), "a link under LINK is judged by its text, so nothing was hashed: "
                        + row);
                assertNull(row.destHash(), row.toString());
            } else {
                assertNotNull(row.sourceHash(), "an ordinary file is judged by its bytes: " + row);
                assertNotNull(row.destHash(), row.toString());
            }
        }
        assertEquals(0, postCopy.filesMissing(), postCopy.message());
        assertTrue(postCopy.warnings().stream().anyMatch(w -> w.contains("different link text")),
                postCopy.warnings().toString());
        assertTrue(postCopy.warnings().stream().anyMatch(w -> w.contains("the source has a symbolic link and the"
                + " destination holds a regular file")), postCopy.warnings().toString());
        assertTrue(postCopy.warnings().stream().noneMatch(w -> w.startsWith("Could not read file:")),
                "no link was followed to decide any of this: " + postCopy.warnings());
    }

    @Test
    void followingASourceLinkHashesTheBytesAtTheEndOfItAndSaysSo() throws Exception {
        ScanOptions follow = ScanOptions.DEFAULTS.withSymlinkPolicy(ScanOptions.SymlinkPolicy.FOLLOW);
        Path src = Files.createDirectories(tmp.resolve("follow-src"));
        Path dst = Files.createDirectories(tmp.resolve("follow-dst"));
        TestSupport.write(src.resolve("target.txt"), "bytes reached through the link");
        Files.createSymbolicLink(src.resolve("via.link"), Path.of("target.txt"));
        TestSupport.write(dst.resolve("via.link"), "bytes reached through the link");
        Path fake = idleRsync(tmp.resolve("follow-tools"));

        JobResult result = run(job(src, dst, JobType.COPY_AND_VERIFY, VerificationBasis.SOURCE_AFTER_COPY,
                follow, "via.link"), fake, null);
        assertEquals(1, result.filesMatched(), result.message());
        FileOutcome row = outcome(result, "via.link");
        assertNotNull(row);
        assertNotNull(row.sourceHash(), "under FOLLOW the verdict is about bytes: " + row);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Compared by bytes reached through a"
                + " symbolic link (FOLLOW): via.link")), result.warnings().toString());
    }

    @Test
    void aLinkStandingInTheDestinationIsStillNeverReadThroughAfterTheCopy() throws Exception {
        ScanOptions follow = ScanOptions.DEFAULTS.withSymlinkPolicy(ScanOptions.SymlinkPolicy.FOLLOW);
        Path src = Files.createDirectories(tmp.resolve("destlink-src"));
        Path dst = Files.createDirectories(tmp.resolve("destlink-dst"));
        TestSupport.write(src.resolve("a.txt"), "a regular file");
        Files.createSymbolicLink(dst.resolve("a.txt"), src.resolve("a.txt"));
        Path fake = idleRsync(tmp.resolve("destlink-tools"));

        JobResult result = run(job(src, dst, JobType.COPY_AND_VERIFY, VerificationBasis.SOURCE_AFTER_COPY,
                follow, "a.txt"), fake, null);
        assertEquals(1, result.filesMismatched(), result.message());
        FileOutcome row = outcome(result, "a.txt");
        assertNotNull(row);
        assertEquals("MISMATCH", row.kind());
        assertNull(row.destHash(), "nothing was read through the destination link: " + row);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("the destination holds a symbolic link")),
                result.warnings().toString());
        assertTrue(result.warnings().stream().noneMatch(w -> w.startsWith("Could not read file:")),
                result.warnings().toString());
    }

    // ---------------------------------------------------------------- bounded work, deterministic order

    @Test
    void postCopyHashingStaysInsideTheWorkerBoundAndTheQueueWindowAndOrderingSurvivesIt() throws Exception {
        int files = 300;
        Path src = tree(tmp.resolve("bounds-src"), files);
        Path dst = tree(tmp.resolve("bounds-dst"), files);
        Path fake = idleRsync(tmp.resolve("bounds-tools"));
        Selection all = TestSupport.selectAllOf(src);
        List<String> workSetOrder;
        int queueDepthOfTheLongStandingLoop;

        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(fake.toString());
            JobResult preCopy = engine.execute(job(src, dst, JobType.COPY_AND_VERIFY,
                    VerificationBasis.SOURCE_BEFORE_COPY, all), null);
            assertEquals(files, preCopy.filesMatched(), preCopy.message());
            workSetOrder = names(preCopy);
            // The pre-existing loop queues every file it has work for. Measured, so that the claim about the
            // new loop being bounded is a comparison and not an assertion in its own corner.
            queueDepthOfTheLongStandingLoop = engine.peakVerificationQueue();
            assertTrue(engine.peakVerificationWorkers() <= FileSyncEngine.verificationWorkerBound());
        }

        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(fake.toString());
            JobResult postCopy = engine.execute(job(src, dst, JobType.COPY_AND_VERIFY,
                    VerificationBasis.SOURCE_AFTER_COPY, all), null);
            assertEquals(files, postCopy.filesMatched(), postCopy.message());
            assertEquals(files, postCopy.outcomes().size(), "every file still gets exactly one row");
            assertEquals(workSetOrder, names(postCopy),
                    "the reads ran concurrently, two per file, out of order on the pool; the rows still follow"
                            + " the captured work set exactly as the default basis does");
            assertTrue(engine.peakVerificationWorkers() <= FileSyncEngine.verificationWorkerBound(),
                    "bounded workers means a measured ceiling, not an intention: saw "
                            + engine.peakVerificationWorkers() + " of at most "
                            + FileSyncEngine.verificationWorkerBound());
            assertTrue(engine.peakVerificationQueue() <= 2 * FileSyncEngine.VERIFY_WINDOW_FILES,
                    "bounded in-flight work means the queue stops growing at the window, not at the size of"
                            + " the tree: saw " + engine.peakVerificationQueue() + " of at most "
                            + 2 * FileSyncEngine.VERIFY_WINDOW_FILES);
            assertTrue(queueDepthOfTheLongStandingLoop > engine.peakVerificationQueue(),
                    "the difference is real, not a renaming of the same loop: pre-copy queued "
                            + queueDepthOfTheLongStandingLoop);
        }
    }

    @Test
    void aCancelledComparisonReportsPendingRowsInsteadOfInventingVerdicts() throws Exception {
        int files = 60;
        Path src = tree(tmp.resolve("cancel-src"), files);
        Path dst = tree(tmp.resolve("cancel-dst"), files);
        Path fake = idleRsync(tmp.resolve("cancel-tools"));
        CountDownLatch firstVerdict = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicReference<JobResult> outcome = new AtomicReference<>();

        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(fake.toString());
            Thread canceller = new Thread(() -> {
                try {
                    if (firstVerdict.await(20, TimeUnit.SECONDS)) {
                        engine.cancel();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    released.countDown();
                }
            }, "post-copy-canceller");
            canceller.setDaemon(true);
            canceller.start();
            outcome.set(engine.execute(job(src, dst, JobType.COPY_AND_VERIFY,
                    VerificationBasis.SOURCE_AFTER_COPY, TestSupport.selectAllOf(src)),
                    new ProgressListener() {
                        @Override
                        public void onFileProcessed(String rel, String kind, String sourceHash, String destHash) {
                            if (firstVerdict.getCount() > 0) {
                                firstVerdict.countDown();
                                try {
                                    released.await(20, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }

                        @Override
                        public void onCompletion(JobResult result) {
                        }
                    }));
            canceller.join(25_000);
        }

        JobResult result = outcome.get();
        assertNotNull(result);
        assertEquals(JobStatus.FAILED, result.status(), result.message());
        assertEquals(FileSyncEngine.AbortCause.USER_CANCELLED, result.abortCause(), result.message());
        List<String> reported = kinds(result);
        long pending = reported.stream().filter("PENDING"::equals).count();
        long matched = reported.stream().filter("OK"::equals).count();
        assertTrue(pending > 0, "a job stopped mid-verification has to name the files it never compared: "
                + reported);
        assertEquals(files, reported.size(), "one row per selected name, capped reports aside");
        assertEquals(0, result.filesMismatched(),
                "a comparison the job never finished is not evidence of a difference: " + result);
        assertEquals(0, result.filesMissing(),
                "and it is not evidence of a missing file either: " + result);
        assertEquals(matched, result.filesMatched(), result.message());
        assertEquals(result.filesMatched(), result.filesChecked(),
                "checked is the sum of the verdicts that were actually answered");
        assertTrue(result.outcomes().stream().allMatch(row -> row.transfer() == TransferDisposition.NOT_COPIED
                        || row.transfer() == TransferDisposition.UNCERTAIN),
                "the transfer half of each row keeps its own answer: " + result.outcomes());
        assertEquals(VerificationBasis.SOURCE_AFTER_COPY, result.verificationBasis(),
                "the job did read the source after the copy, and its report says so even though it stopped");
    }

    // ---------------------------------------------------------------- destination ownership

    @Test
    void theDestinationStaysClaimedWhileThePostCopyComparisonRunsAndIsHandedBackAfterwards()
            throws Exception {
        Path src = tree(tmp.resolve("own-src"), 6);
        Path dst = tree(tmp.resolve("own-dst"), 6);
        Path fake = idleRsync(tmp.resolve("own-tools"));
        String key = dst.toRealPath().toString();
        CountDownLatch verifying = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicReference<JobResult> outcome = new AtomicReference<>();
        AtomicReference<String> outsiderProblem = new AtomicReference<>();

        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(fake.toString());
            Thread worker = new Thread(() -> {
                try {
                    outcome.set(engine.execute(job(src, dst, JobType.COPY_AND_VERIFY,
                            VerificationBasis.SOURCE_AFTER_COPY, TestSupport.selectAllOf(src)),
                            new ProgressListener() {
                                @Override
                                public void onPhase(String phase) {
                                    if ("verifying".equals(phase)) {
                                        verifying.countDown();
                                        try {
                                            released.await(20, TimeUnit.SECONDS);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                    }
                                }

                                @Override
                                public void onCompletion(JobResult result) {
                                }
                            }));
                } catch (RuntimeException e) {
                    outsiderProblem.set(String.valueOf(e));
                }
            }, "post-copy-owner");
            worker.start();
            assertTrue(verifying.await(20, TimeUnit.SECONDS), "the job never reached its verification phase");

            assertTrue(FileSyncEngine.destinationClaimsInUse().contains(key),
                    "a post-copy comparison reads the destination it just wrote; the reservation is what says"
                            + " this job is still the one responsible for it");
            try (FileSyncEngine outsider = new FileSyncEngine()) {
                JobResult refused = outsider.execute(job(src, dst, JobType.COPY_AND_VERIFY,
                        VerificationBasis.SOURCE_AFTER_COPY, TestSupport.selectAllOf(src)), null);
                assertEquals(JobStatus.FAILED, refused.status(), refused.message());
                assertTrue(refused.errors().stream().anyMatch(e -> e.startsWith("destination busy:")),
                        refused.message());
                assertEquals(0, refused.filesProcessed(), "the refused job touched nothing: " + refused);
            }
            released.countDown();
            worker.join(30_000);
            assertNull(outsiderProblem.get(), outsiderProblem.get());
            JobResult result = outcome.get();
            assertNotNull(result);
            assertEquals(JobStatus.COMPLETED, result.status(), result.message());
            assertTrue(TestSupport.waitFor(() -> !FileSyncEngine.destinationClaimsInUse().contains(key), 20_000),
                    "verification finished, so the destination should be open to the next job again");
        }
    }

    // ---------------------------------------------------------------- what the report says

    @Test
    void csvAndJsonRecordWhichDigestsTheVerdictsWereComparedAgainst() throws Exception {
        Path src = tree(tmp.resolve("report-src"), 3);
        Path dst = tree(tmp.resolve("report-dst"), 3);
        Path fake = idleRsync(tmp.resolve("report-tools"));
        Selection all = TestSupport.selectAllOf(src);

        for (VerificationBasis basis : List.of(VerificationBasis.SOURCE_BEFORE_COPY,
                VerificationBasis.SOURCE_AFTER_COPY)) {
            JobResult result = run(job(src, dst, JobType.COPY_AND_VERIFY, basis, all), fake, null);
            String csv = ReportExporter.toCsv(result);
            String json = ReportExporter.toJson(result);
            assertTrue(csv.contains("# verificationBasis," + basis.name()), csv);
            assertTrue(csv.contains("# verificationBasisLimitation"), csv);
            assertTrue(json.contains("\"verificationBasis\": \"" + basis.name() + "\""), json);
            assertTrue(json.contains("\"verificationBasisLimitation\""), json);
            if (basis == VerificationBasis.SOURCE_AFTER_COPY) {
                assertTrue(csv.contains("No digest of the source was taken before rsync ran"), csv);
            } else {
                assertTrue(csv.contains("The source digest predates the transfer"), csv);
            }
            // The per-file columns are untouched: an export that gained a claim it did not have before would be
            // a change to every report anyone already reads.
            assertTrue(csv.startsWith("# file-copy-util report\n# status,COMPLETED\n"), csv.substring(0, 60));
            assertTrue(csv.contains("status,path,source_sha256,destination_sha256,transfer,verification\n"), csv);
        }
    }

    @Test
    void aJobThatCopiedNothingOrComparedNothingRecordsWhichOfThoseItWas() throws Exception {
        assumeRealRsync();
        Path src = tree(tmp.resolve("kinds-src"), 2);
        Path dst = tree(tmp.resolve("kinds-dst"), 2);
        Selection all = TestSupport.selectAllOf(src);

        JobResult verifyOnly = run(job(src, dst, JobType.VERIFY_ONLY,
                VerificationBasis.SOURCE_AFTER_COPY, all), null, null);
        assertEquals(VerificationBasis.BOTH_SIDES_AT_VERIFICATION, verifyOnly.verificationBasis(),
                "a job with no transfer has no 'after copying', and asking for one does not invent it: "
                        + verifyOnly);
        assertEquals(2, verifyOnly.filesMatched(), verifyOnly.message());

        JobResult copyOnly = run(job(src, dst, JobType.COPY_ONLY,
                VerificationBasis.SOURCE_AFTER_COPY, all), null, null);
        assertEquals(VerificationBasis.NOTHING_HASHED, copyOnly.verificationBasis(),
                "nothing was hashed, whatever the request asked for: " + copyOnly);

        // A job that never got as far as running records no basis at all, rather than borrowing the one its
        // request carried.
        JobRequest bad = new JobRequest(src, dst.getParent().resolve("does-not-exist"), JobType.COPY_AND_VERIFY,
                all, Selection.none(), ScanOptions.DEFAULTS, false, false,
                VerificationBasis.SOURCE_AFTER_COPY);
        JobResult refused = run(bad, null, null);
        assertEquals(JobStatus.FAILED, refused.status(), refused.message());
        assertEquals(VerificationBasis.NOT_RECORDED, refused.verificationBasis(),
                "the job was refused before it began, so its report claims no digest schedule at all: "
                        + refused.message());
    }

    @Test
    void aDryRunThatAskedForTheNewBasisRecordsWhatItActuallyDid() throws Exception {
        assumeRealRsync();
        Path src = tree(tmp.resolve("dry-src"), 3);
        Path dst = tree(tmp.resolve("dry-dst"), 3);
        Map<String, String> sourceBefore = TestSupport.digests(src);
        Map<String, String> destinationBefore = TestSupport.digests(dst);
        Selection all = TestSupport.selectAllOf(src);

        JobResult result = run(job(src, dst, JobType.COPY_AND_VERIFY, VerificationBasis.SOURCE_AFTER_COPY,
                ScanOptions.DEFAULTS, all, true), null, null);
        assertEquals(JobStatus.COMPLETED, result.status(), result.message());
        assertEquals(VerificationBasis.BOTH_SIDES_AT_VERIFICATION, result.verificationBasis(),
                "a dry run writes nothing, so it has no copy for the hashing to be after: " + result.message());
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("Verification basis: both sides hashed"
                + " during verification")), result.warnings().toString());
        assertTrue(result.warnings().stream().noneMatch(w -> w.startsWith("Verification basis: source hashed"
                + " after the transfer")), result.warnings().toString());
        assertEquals(0, result.filesCopied() + result.filesWouldCopy(), result.message());
        TestSupport.assertUnchanged(src, sourceBefore);
        TestSupport.assertUnchanged(dst, destinationBefore);
    }

    @Test
    void aRequestCannotAskForABasisThatIsNotAChoice() {
        Path src = tmp.resolve("src");
        Path dst = tmp.resolve("dst");
        for (VerificationBasis basis : List.of(VerificationBasis.BOTH_SIDES_AT_VERIFICATION,
                VerificationBasis.NOTHING_HASHED, VerificationBasis.NOT_RECORDED)) {
            JobRequest job = new JobRequest(src, dst, JobType.COPY_AND_VERIFY,
                    TestSupport.selection("a.txt"), Selection.none(), ScanOptions.DEFAULTS, false, false, basis);
            List<String> problems = FileSyncEngine.validate(job);
            assertTrue(problems.stream().anyMatch(p -> p.contains("is not a choice a job can be given")),
                    basis + " must be refused: " + problems);
        }
        for (VerificationBasis basis : List.of(VerificationBasis.SOURCE_BEFORE_COPY,
                VerificationBasis.SOURCE_AFTER_COPY)) {
            JobRequest job = new JobRequest(src, dst, JobType.COPY_AND_VERIFY,
                    TestSupport.selection("a.txt"), Selection.none(), ScanOptions.DEFAULTS, false, false, basis);
            assertTrue(FileSyncEngine.validate(job).stream()
                            .noneMatch(p -> p.contains("is not a choice a job can be given")),
                    basis + " is a choice a job can be given");
        }
    }

    @Test
    void aHandBuiltResultSaysItDoesNotKnowWhichBasisItUsed() {
        JobResult built = new FileSyncEngine.JobResult(JobStatus.COMPLETED, "hand-built", 1, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 1, 0, List.of(), List.of(), List.of(), 0, FileSyncEngine.AbortCause.NONE);
        assertEquals(VerificationBasis.NOT_RECORDED, built.verificationBasis(),
                "a result nobody ran cannot claim a digest schedule");
        assertTrue(ReportExporter.toCsv(built).contains("# verificationBasis,NOT_RECORDED"),
                ReportExporter.toCsv(built));
    }

    // ---------------------------------------------------------------- the command line

    @Test
    void theCliOffersTheChoiceRecordsItAndRefusesAnythingElse() throws Exception {
        assumeRealRsync();
        Path src = tree(tmp.resolve("cli-src"), 3);
        Path dst = tmp.resolve("cli-dst");
        Files.createDirectories(dst);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Cli.run(new String[]{"--cli", "--source", src.toString(), "--dest", dst.toString(),
                "--all", "--hash-source", "after-copy", "--csv", tmp.resolve("cli.csv").toString(),
                "--json", tmp.resolve("cli.json").toString(), "--quiet"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertEquals(Cli.EXIT_OK, exit, stdout + err.toString(StandardCharsets.UTF_8));
        assertTrue(stdout.contains("verification basis: source hashed after the transfer"), stdout);
        String csv = Files.readString(tmp.resolve("cli.csv"));
        assertTrue(csv.contains("# verificationBasis,SOURCE_AFTER_COPY"), csv);
        assertTrue(Files.readString(tmp.resolve("cli.json")).contains("\"SOURCE_AFTER_COPY\""), "json");
        assertEquals(3, TestSupport.relPathsUnder(dst).size(), "the copy itself happened as it always did");

        ByteArrayOutputStream usageOut = new ByteArrayOutputStream();
        ByteArrayOutputStream usageErr = new ByteArrayOutputStream();
        int bad = Cli.run(new String[]{"--cli", "--source", src.toString(), "--dest", dst.toString(),
                "--all", "--hash-source", "whenever-i-feel-like-it"},
                new PrintStream(usageOut, true, StandardCharsets.UTF_8),
                new PrintStream(usageErr, true, StandardCharsets.UTF_8));
        assertEquals(Cli.EXIT_USAGE, bad);
        assertTrue(usageErr.toString(StandardCharsets.UTF_8)
                .contains("--hash-source must be before-copy or after-copy"),
                usageErr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theCliSaysOutLoudWhenTheChoiceCannotDoAnythingInThisJob() throws Exception {
        assumeRealRsync();
        Path src = tree(tmp.resolve("cli-note-src"), 2);
        Path dst = tmp.resolve("cli-note-dst");
        Files.createDirectories(dst);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Cli.run(new String[]{"--cli", "--source", src.toString(), "--dest", dst.toString(),
                "--all", "--no-verify", "--hash-source", "after-copy", "--quiet"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String stderr = err.toString(StandardCharsets.UTF_8);
        assertEquals(Cli.EXIT_OK, exit, out.toString(StandardCharsets.UTF_8) + stderr);
        assertTrue(stderr.contains("--hash-source after-copy has no effect here"), stderr);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("verification basis: nothing was hashed"),
                out.toString(StandardCharsets.UTF_8));
        ByteArrayOutputStream verifyOut = new ByteArrayOutputStream();
        ByteArrayOutputStream verifyErr = new ByteArrayOutputStream();
        int verifyOnly = Cli.run(new String[]{"--cli", "--source", src.toString(), "--dest", dst.toString(),
                "--all", "--verify-only", "--hash-source", "after-copy", "--quiet"},
                new PrintStream(verifyOut, true, StandardCharsets.UTF_8),
                new PrintStream(verifyErr, true, StandardCharsets.UTF_8));
        assertEquals(Cli.EXIT_OK, verifyOnly, verifyOut.toString(StandardCharsets.UTF_8));
        assertTrue(verifyErr.toString(StandardCharsets.UTF_8).contains("a Verify Only job has no copy to be after"),
                verifyErr.toString(StandardCharsets.UTF_8));

        ByteArrayOutputStream dryOut = new ByteArrayOutputStream();
        ByteArrayOutputStream dryErr = new ByteArrayOutputStream();
        int dryRun = Cli.run(new String[]{"--cli", "--source", src.toString(), "--dest", dst.toString(),
                "--all", "--dry-run", "--hash-source", "after-copy", "--quiet"},
                new PrintStream(dryOut, true, StandardCharsets.UTF_8),
                new PrintStream(dryErr, true, StandardCharsets.UTF_8));
        assertEquals(Cli.EXIT_OK, dryRun, dryOut.toString(StandardCharsets.UTF_8));
        assertTrue(dryErr.toString(StandardCharsets.UTF_8)
                        .contains("a dry run writes nothing, so there is no copy for the hashing to be after"),
                dryErr.toString(StandardCharsets.UTF_8));
        assertTrue(dryOut.toString(StandardCharsets.UTF_8)
                        .contains("verification basis: both sides hashed during verification"),
                dryOut.toString(StandardCharsets.UTF_8));
    }

    /** Records what the engine said about one job, in the order it said it. */
    private static final class Recording implements ProgressListener {
        private final List<String> phases = new ArrayList<>();
        private final List<String> verdicts = new ArrayList<>();

        @Override
        public void onPhase(String phase) {
            phases.add(phase);
        }

        @Override
        public void onFileProcessed(String rel, String kind, String sourceHash, String destHash) {
            verdicts.add(kind + " " + rel);
        }

        @Override
        public void onCompletion(JobResult result) {
        }
    }
}
