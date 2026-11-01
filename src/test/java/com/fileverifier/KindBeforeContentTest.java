package com.fileverifier;

import static com.fileverifier.FileSyncEngine.JobType.COPY_AND_VERIFY;
import static com.fileverifier.FileSyncEngine.JobType.VERIFY_ONLY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fileverifier.FileSyncEngine.FileOutcome;
import com.fileverifier.FileSyncEngine.JobResult;
import com.fileverifier.FileSyncEngine.ProgressListener;
import com.fileverifier.FileSyncEngine.Selection;
import com.fileverifier.FileSyncEngine.VerificationBasis;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A verdict that the two objects answer by <b>what they are</b> must not read either one.
 *
 * <p>Every case here is one where the verdict rule turns on kind and link text alone: a regular file opposite
 * a link, a link opposite a regular file, an object in the destination that is neither a link nor a file. The
 * verdict is asserted to be the verdict the rule has always given for that pair - the same kind, the same
 * digest columns - and the claim under test is the new one: nothing was opened to produce it.
 *
 * <p>"Nothing was read" is measured, not asserted from the source. Two devices make an attempted read visible
 * in the report: a file whose mode denies its own owner, whose read can only fail and whose failure the
 * program reports as {@code Could not read file}, and a FIFO, whose read cannot fail and cannot end at all.
 * A verdict that reports no read problem for an unreadable file is a verdict that did not read it, and a job
 * that ends at all in front of a FIFO is a job that never opened it.
 *
 * <p>The mirror cases are here too. A source link under FOLLOW is a pair whose verdict <i>is</i> about bytes,
 * and a pair whose kinds settle nothing is still compared by digest; a fix that stopped reading in those
 * cases would look like an improvement and would be a silent weakening of the comparison.
 */
class KindBeforeContentTest {

    @TempDir
    Path tmp;

    private static void assumePosixPermissions() {
        assumeTrue(!"root".equals(System.getProperty("user.name")),
                "an unreadable file means nothing to a process that can read anything");
    }

    /** mkfifo is a coreutils tool; where it is missing the FIFO case cannot be built. */
    private static boolean assumeFifoCanBeMade() {
        try {
            Process probe = new ProcessBuilder("mkfifo", "--help")
                    .redirectErrorStream(true)
                    .start();
            probe.getInputStream().readAllBytes();
            boolean available = probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0;
            assumeTrue(available, "no mkfifo: a FIFO cannot be built here");
            return true;
        } catch (Exception e) {
            assumeTrue(false, "no mkfifo: a FIFO cannot be built here (" + e + ")");
            return false;
        }
    }

    private static ScanOptions policy(ScanOptions.SymlinkPolicy p) {
        return ScanOptions.DEFAULTS.withSymlinkPolicy(p);
    }

    /** A file its own owner cannot read: any attempt to digest it is a reported failure. */
    private static void makeUnreadable(Path file) throws Exception {
        Files.setPosixFilePermissions(file, EnumSet.noneOf(PosixFilePermission.class));
    }

    private static void makeReadable(Path file) throws Exception {
        Files.setPosixFilePermissions(file, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    /**
     * Did this job try to open {@code rel}? Every read failure the engine reports begins the same way, on
     * either pass, so the absence of one is the measurement: a file that was never opened cannot have failed
     * to be opened.
     */
    private static boolean readWasAttempted(JobResult result, String rel) {
        return result.warnings().stream().anyMatch(w -> w.startsWith("Could not read file: " + rel + " (")
                || w.contains("Could not read file: " + rel + " (")
                || w.contains("Could not read source file: " + rel + " ")
                || w.contains("Could not read destination file: " + rel + " "));
    }

    private static void assertNothingWasRead(JobResult result, String rel) {
        assertFalse(readWasAttempted(result, rel), "a verdict the objects settle by kind must not open them; "
                + rel + " was opened (or its absence was reported as a read failure): " + result.warnings());
    }

    private static FileOutcome outcome(JobResult result, String name) {
        return result.outcomes().stream().filter(o -> o.relPath().equals(name)).findFirst().orElse(null);
    }

    private static JobResult verifyOnly(Path src, Path dst, ScanOptions options, String... names) throws Exception {
        try (FileSyncEngine engine = new FileSyncEngine()) {
            return engine.execute(TestSupport.request(src, dst, VERIFY_ONLY,
                    TestSupport.selection(names), Selection.none(), options, false), null);
        }
    }

    // ---------------------------------------------------------------- the kind cases read nothing

    @Test
    void aSourceFileStandingOppositeADestinationLinkIsNotOpenedUnderEitherPolicy() throws Exception {
        assumePosixPermissions();
        Path src = Files.createDirectories(tmp.resolve("dstlink-src"));
        Path dst = Files.createDirectories(tmp.resolve("dstlink-dst"));
        TestSupport.write(src.resolve("thing.bin"), "a source the job has no reason to read");
        Files.createSymbolicLink(dst.resolve("thing.bin"), dst.resolve("elsewhere"));
        makeUnreadable(src.resolve("thing.bin"));
        try {
            for (ScanOptions.SymlinkPolicy symlinkPolicy
                    : List.of(ScanOptions.SymlinkPolicy.LINK, ScanOptions.SymlinkPolicy.FOLLOW)) {
                JobResult result = verifyOnly(src, dst, policy(symlinkPolicy), "thing.bin");
                FileOutcome judged = outcome(result, "thing.bin");
                assertNotNull(judged, result.outcomes().toString());
                assertEquals("MISMATCH", judged.kind(),
                        "a link opposite a file is a mismatch in kind, as it has always been: " + result.message());
                assertNull(judged.sourceHash(), judged.toString());
                assertNull(judged.destHash(), judged.toString());
                assertEquals(1, result.filesMismatched(), result.message());
                assertNothingWasRead(result, "thing.bin");
            }
        } finally {
            makeReadable(src.resolve("thing.bin"));
        }
    }

    @Test
    void aDestinationFileStandingOppositeASourceLinkIsNotOpenedUnderLinkPolicy() throws Exception {
        assumePosixPermissions();
        Path src = Files.createDirectories(tmp.resolve("srclink-src"));
        Path dst = Files.createDirectories(tmp.resolve("srclink-dst"));
        TestSupport.write(src.resolve("target.txt"), "the link's own target, not in the job");
        Files.createSymbolicLink(src.resolve("thing.txt"), src.resolve("target.txt"));
        TestSupport.write(dst.resolve("thing.txt"), "a plain file where a link belongs");
        makeUnreadable(dst.resolve("thing.txt"));
        try {
            JobResult result = verifyOnly(src, dst, policy(ScanOptions.SymlinkPolicy.LINK), "thing.txt");
            FileOutcome judged = outcome(result, "thing.txt");
            assertNotNull(judged, result.outcomes().toString());
            assertEquals("MISMATCH", judged.kind(), result.message());
            assertNull(judged.sourceHash(), judged.toString());
            assertNull(judged.destHash(), judged.toString());
            assertNothingWasRead(result, "thing.txt");
        } finally {
            makeReadable(dst.resolve("thing.txt"));
        }
    }

    /**
     * The mirror of the test above, and the reason it is not enough to stop reading: under FOLLOW the verdict
     * about a source link <i>is</i> a verdict about bytes, so this pair must still be opened, and the read
     * problem is the evidence that it was.
     */
    @Test
    void aSourceLinkFollowedByContentStillReadsBothSides() throws Exception {
        assumePosixPermissions();
        Path src = Files.createDirectories(tmp.resolve("follow-src"));
        Path dst = Files.createDirectories(tmp.resolve("follow-dst"));
        TestSupport.write(src.resolve("target.txt"), "content reached through the link");
        Files.createSymbolicLink(src.resolve("thing.txt"), src.resolve("target.txt"));
        TestSupport.write(dst.resolve("thing.txt"), "content on the other side");
        makeUnreadable(dst.resolve("thing.txt"));
        try {
            JobResult result = verifyOnly(src, dst, policy(ScanOptions.SymlinkPolicy.FOLLOW), "thing.txt");
            FileOutcome judged = outcome(result, "thing.txt");
            assertNotNull(judged, result.outcomes().toString());
            assertTrue(readWasAttempted(result, "thing.txt"),
                    "FOLLOW asks for a verdict about bytes, so a destination that cannot be read still has to be"
                            + " attempted, and said to have been attempted: " + result.warnings());
            assertEquals(1, result.filesChecked(), result.message());
        } finally {
            makeReadable(dst.resolve("thing.txt"));
        }
    }

    @Test
    void twoLinksUnderLinkPolicyAreComparedByWhatTheySayWithoutReadingTheirTargets() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("twolinks-src"));
        Path dst = Files.createDirectories(tmp.resolve("twolinks-dst"));
        TestSupport.write(src.resolve("target.txt"), "a target neither link is asked about");
        Files.createSymbolicLink(src.resolve("thing.txt"), src.resolve("target.txt"));
        Files.createSymbolicLink(dst.resolve("thing.txt"), dst.resolve("somewhere/else.txt"));

        JobResult result = verifyOnly(src, dst, policy(ScanOptions.SymlinkPolicy.LINK), "thing.txt");
        FileOutcome judged = outcome(result, "thing.txt");
        assertNotNull(judged, result.outcomes().toString());
        assertEquals("MISMATCH", judged.kind(), result.message());
        assertNull(judged.sourceHash(), judged.toString());
        assertNull(judged.destHash(), judged.toString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("different link text")),
                result.warnings().toString());
        assertNothingWasRead(result, "thing.txt");
    }

    @Test
    void anExtraLinkInTheDestinationIsReportedWithoutReadingAnything() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("extralink-src"));
        Path dst = Files.createDirectories(tmp.resolve("extralink-dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        TestSupport.write(dst.resolve("a.txt"), "a\n");
        Files.createSymbolicLink(dst.resolve("extra.txt"), Path.of("/etc/passwd"));

        try (FileSyncEngine engine = new FileSyncEngine()) {
            // LINK policy, because under the default SKIP policy a link is not in the listing at all and
            // there is nothing to report; under LINK it is an object of its own.
            JobResult result = engine.execute(TestSupport.request(src, dst, VERIFY_ONLY,
                    TestSupport.selection("a.txt"), TestSupport.selection("a.txt"),
                    policy(ScanOptions.SymlinkPolicy.LINK), true), null);
            FileOutcome judged = outcome(result, "extra.txt");
            assertNotNull(judged, result.outcomes().toString());
            assertEquals("EXTRA", judged.kind(), result.toString());
            assertNull(judged.destHash(), "an extra link is judged as a link: " + judged);
            assertNothingWasRead(result, "extra.txt");
        }
    }

    // ---------------------------------------------------------------- objects that cannot be digested

    @Test
    void aDirectoryInTheDestinationIsJudgedByItsKindWithoutAFutileOpen() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("dirdst-src"));
        Path dst = Files.createDirectories(tmp.resolve("dirdst-dst"));
        TestSupport.write(src.resolve("thing.bin"), "a file where a directory stands\n");
        Files.createDirectories(dst.resolve("thing.bin"));

        JobResult result = verifyOnly(src, dst, ScanOptions.DEFAULTS, "thing.bin");
        FileOutcome judged = outcome(result, "thing.bin");
        assertNotNull(judged, result.outcomes().toString());
        assertEquals("MISSING", judged.kind(),
                "the verdict for a destination that is not the file is the verdict it has always been: "
                        + result.message());
        assertNotNull(judged.sourceHash(), "the source side is still digested, and its row still carries it: "
                + judged);
        assertNull(judged.destHash(), judged.toString());
        assertEquals(1, result.filesMissing(), result.message());
        // The old code opened the directory, failed, and reported that failure as an unreadable file. The
        // verdict is the same; the report no longer claims a read this program should never have attempted.
        assertNothingWasRead(result, "thing.bin");
        assertFalse(result.warnings().stream().anyMatch(w -> w.contains("Is a directory")),
                result.warnings().toString());
    }

    @Test
    void aFifoInTheDestinationIsNotOpenedAndTheJobStillEnds() throws Exception {
        assumeFifoCanBeMade();
        Path src = Files.createDirectories(tmp.resolve("fifo-src"));
        Path dst = Files.createDirectories(tmp.resolve("fifo-dst"));
        TestSupport.write(src.resolve("pipe.bin"), "a file where a FIFO stands\n");
        new ProcessBuilder("mkfifo", dst.resolve("pipe.bin").toString()).inheritIO().start().waitFor();
        assertTrue(Files.exists(dst.resolve("pipe.bin")), "the FIFO is there to be mistaken for a file");

        try (FileSyncEngine engine = new FileSyncEngine()) {
            // A bounded job, so that a read this program should not attempt cannot turn this test into a hang:
            // the assertion is that the job answers the file at all.
            engine.setJobTimeoutMillis(20_000);
            JobResult result = engine.execute(TestSupport.request(src, dst, VERIFY_ONLY,
                    TestSupport.selection("pipe.bin"), Selection.none(), ScanOptions.DEFAULTS, false), null);
            FileOutcome judged = outcome(result, "pipe.bin");
            assertNotNull(judged, "the file was never answered - the job was stopped by its own limit, which"
                    + " means something opened the FIFO: " + result.message());
            assertEquals("MISSING", judged.kind(), result.message());
            assertNotNull(judged.sourceHash(), judged.toString());
            assertEquals(1, result.filesChecked(), result.message());
            assertNothingWasRead(result, "pipe.bin");
        }
    }

    // ---------------------------------------------------------------- both loops, both bases

    @Test
    void thePostCopyLoopSettlesKindCasesWithoutQueuingAReadForThem() throws Exception {
        assumePosixPermissions();
        Path src = Files.createDirectories(tmp.resolve("postkind-src"));
        Path dst = Files.createDirectories(tmp.resolve("postkind-dst"));
        TestSupport.write(src.resolve("a.txt"), "aaaaaaaa\n");
        TestSupport.write(dst.resolve("a.txt"), "aaaaaaaa\n");
        TestSupport.write(src.resolve("b.bin"), "a source this verdict does not need to read\n");
        Files.createSymbolicLink(dst.resolve("b.bin"), dst.resolve("nowhere"));
        makeUnreadable(src.resolve("b.bin"));
        Path tools = Files.createDirectories(tmp.resolve("postkind-tools"));
        Path fake = TestSupport.fakeRsync(tools, "", 0);

        try {
            try (FileSyncEngine engine = new FileSyncEngine()) {
                engine.useRsync(fake.toString());
                JobResult result = engine.execute(new FileSyncEngine.JobRequest(src, dst, COPY_AND_VERIFY,
                        TestSupport.selection("a.txt", "b.bin"), Selection.none(), ScanOptions.DEFAULTS,
                        false, false, VerificationBasis.SOURCE_AFTER_COPY), null);
                FileOutcome judged = outcome(result, "b.bin");
                assertNotNull(judged, result.outcomes().toString());
                assertEquals("MISMATCH", judged.kind(), result.message());
                assertNull(judged.sourceHash(), "no digest belongs to a verdict settled by kind: " + judged);
                assertNull(judged.destHash(), judged.toString());
                assertNothingWasRead(result, "b.bin");
                // The pair next to it is an ordinary content comparison and must have been one.
                FileOutcome compared = outcome(result, "a.txt");
                assertNotNull(compared, result.outcomes().toString());
                assertEquals("OK", compared.kind(), compared.toString());
                assertNotNull(compared.sourceHash(), compared.toString());
                assertNotNull(compared.destHash(), compared.toString());
            }
        } finally {
            makeReadable(src.resolve("b.bin"));
        }
    }

    /**
     * Deciding from kind first must not freeze the kinds a decision was made from.
     *
     * <p>The post-copy loop probes a file's two kinds when the file enters its window, and a pair the kinds
     * settle queues no read at all. That is only honest if the finding is still made from what the two sides
     * are at the moment the row is written, and so the loop looks at them again then. A file that changed kind
     * while it waited is judged as the object it has become - which is where the older code judged it, because
     * the older code read the kind inside the read task that read the content.
     *
     * <p>The fixture makes the change deterministic: the destination side is a link when the window is filled
     * (so the verdict is settled by kind and nothing is queued), it becomes a matching regular file while the
     * file before it is being reported, and its own row still carries the digest of the content that appeared.
     * Answering from the kinds probed earlier would report a kind mismatch and no digest at all here, and would
     * look exactly like the fix working.
     */
    @Test
    void aPairSettledByKindIsLookedAtAgainBeforeItsVerdictIsWritten() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("recheck-src"));
        Path dst = Files.createDirectories(tmp.resolve("recheck-dst"));
        TestSupport.write(src.resolve("a.txt"), "aaaaaaaa\n");
        TestSupport.write(dst.resolve("a.txt"), "aaaaaaaa\n");
        String arrivedDuringTheJob = "content that was not there when this pair was judged by its kind";
        TestSupport.write(src.resolve("b.bin"), arrivedDuringTheJob);
        Path atTheDestination = dst.resolve("b.bin");
        Files.createSymbolicLink(atTheDestination, dst.resolve("nowhere"));
        Path tools = Files.createDirectories(tmp.resolve("recheck-tools"));
        Path fake = TestSupport.fakeRsync(tools, "", 0);

        ProgressListener changeTheMomentOfReporting = new ProgressListener() {
            private boolean changed;

            @Override
            public void onFileProcessed(String rel, String kind, String sourceHash, String destHash) {
                if (changed || !"a.txt".equals(rel)) {
                    return;
                }
                changed = true;
                try {
                    Files.delete(atTheDestination);
                    TestSupport.write(atTheDestination, arrivedDuringTheJob);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public void onCompletion(FileSyncEngine.JobResult result) {
            }
        };

        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(fake.toString());
            JobResult result = engine.execute(new FileSyncEngine.JobRequest(src, dst, COPY_AND_VERIFY,
                    TestSupport.selection("a.txt", "b.bin"), Selection.none(), ScanOptions.DEFAULTS,
                    false, false, VerificationBasis.SOURCE_AFTER_COPY), changeTheMomentOfReporting);
            FileOutcome judged = outcome(result, "b.bin");
            assertNotNull(judged, result.outcomes().toString());
            assertEquals("OK", judged.kind(),
                    "the destination was a link when this pair entered the window and a matching file when its"
                            + " row was written, and the verdict follows the objects as they are when the finding"
                            + " is made: " + result.message());
            assertEquals(FileHasher.computeHash(src.resolve("b.bin")), judged.sourceHash(), judged.toString());
            assertEquals(FileHasher.computeHash(atTheDestination), judged.destHash(),
                    "the content that appeared was read, rather than the earlier kind being answered from");
            assertEquals(List.of("OK a.txt", "OK b.bin"),
                    result.outcomes().stream().map(o -> o.kind() + " " + o.relPath()).toList(),
                    "and the rows still follow the work set: " + result.outcomes());
        }
    }

    /** The warning sets are written once per file, not once per stage that looked at the file. */
    @Test
    void aKindWarningIsReportedOnceEvenThoughThePairWasExaminedTwice() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("once-src"));
        Path dst = Files.createDirectories(tmp.resolve("once-dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        TestSupport.write(src.resolve("thing.txt"), "a regular file\n");
        TestSupport.write(dst.resolve("a.txt"), "a\n");
        Files.createSymbolicLink(dst.resolve("thing.txt"), dst.resolve("elsewhere"));

        JobResult result = verifyOnly(src, dst, ScanOptions.DEFAULTS, "a.txt", "thing.txt");
        FileOutcome judged = outcome(result, "thing.txt");
        assertNotNull(judged, result.outcomes().toString());
        assertEquals("MISMATCH", judged.kind(), result.toString());
        long times = result.warnings().stream()
                .filter(w -> w.contains("thing.txt: the source has a regular file")).count();
        assertEquals(1, times, "one finding, one warning: " + result.warnings());
        Set<String> distinct = new HashSet<>(result.warnings());
        assertEquals(result.warnings().size(), distinct.size(), "no warning is duplicated: " + result.warnings());
    }
}
