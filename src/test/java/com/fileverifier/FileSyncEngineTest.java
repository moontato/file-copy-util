package com.fileverifier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Behavioural contract of the copy/verify engine, driven headlessly against real rsync.
 * Each test maps to a roadmap fix (B1..B9).
 */
class FileSyncEngineTest {

    @TempDir
    Path source;

    @TempDir
    Path dest;

    private TestSupport.Recorder rec;

    @BeforeEach
    void setUp() {
        rec = new TestSupport.Recorder();
    }

    private FileSyncEngine.JobResult run(FileSyncEngine.JobType type, Set<String> srcSel, Set<String> dstSel) {
        return FileSyncEngine.execute(source.toFile(), dest.toFile(), type, srcSel, dstSel, rec);
    }

    private static void assumeRsync() {
        assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
    }

    // ---------------------------------------------------------------- B1

    @Test
    void emptySelectionCopiesNothingInsteadOfEverything() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");
        TestSupport.write(source.resolve("nested/b.txt"), "b");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY, Set.of(), Set.of());

        assertEquals(FileSyncEngine.JobStatus.FAILED, result.status());
        assertTrue(result.message().contains("selection is empty"), result.message());
        assertEquals(0, result.filesChecked());
        assertEquals(0, result.filesCopied());
        try (var entries = Files.list(dest)) {
            assertEquals(0, entries.count(), "an empty selection must not copy the source tree");
        }
    }

    @Test
    void untickingAnExistingDestinationRowExcludesItFromVerification() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");
        TestSupport.write(source.resolve("b.txt"), "b");
        TestSupport.write(dest.resolve("a.txt"), "a");
        TestSupport.write(dest.resolve("b.txt"), "b");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.VERIFY_ONLY,
                TestSupport.selection("a.txt", "b.txt"), TestSupport.selection("a.txt"));

        assertTrue(result.isSuccess(), result.message());
        assertEquals(1, result.filesChecked());
        assertEquals(1, result.filesMatched());
    }

    @Test
    void verifyOnlyStillReportsFilesAbsentFromTheDestination() throws Exception {
        // This is what the GUI can actually express: "absent.txt" has no row in the destination
        // list, so it cannot be ticked there - yet it must still be checked and reported missing.
        TestSupport.write(source.resolve("present.txt"), "p\n");
        TestSupport.write(source.resolve("absent.txt"), "q\n");
        TestSupport.write(dest.resolve("present.txt"), "p\n");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.VERIFY_ONLY,
                TestSupport.selection("present.txt", "absent.txt"), TestSupport.selection("present.txt"));

        assertTrue(result.isSuccess(), result.message());
        assertEquals(2, result.filesChecked());
        assertEquals(1, result.filesMatched());
        assertEquals(1, result.filesMissing());
        assertEquals(0, result.filesMismatched());
        assertEquals("missing", rec.eventFor("absent.txt").status());
    }

    @Test
    void untickingEveryExistingDestinationRowLeavesAnEmptyWorkSet() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");
        TestSupport.write(dest.resolve("a.txt"), "a");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.VERIFY_ONLY,
                TestSupport.selection("a.txt"), Set.of());

        assertEquals(FileSyncEngine.JobStatus.FAILED, result.status());
        assertEquals(0, result.filesChecked());
    }

    // ---------------------------------------------------------------- B2

    @Test
    void copyAndVerifyOnlyCopiesAndVerifiesTheSelection() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("keep1.txt"), "one\n");
        TestSupport.write(source.resolve("sub/keep2.txt"), "two\n");
        TestSupport.write(source.resolve("skip1.txt"), "junk\n");
        TestSupport.write(source.resolve("other/deep/skip2.txt"), "junk\n");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selection("keep1.txt", "sub/keep2.txt"), Set.of());

        assertTrue(result.isSuccess(), result.message());
        assertEquals(2, result.filesChecked());
        assertEquals(2, result.filesCopied());
        assertEquals(2, result.filesMatched());
        assertEquals(0, result.filesMismatched());
        assertEquals(0, result.filesMissing());
        assertEquals(0, result.rsyncExitCode());

        assertTrue(Files.isRegularFile(dest.resolve("keep1.txt")));
        assertTrue(Files.isRegularFile(dest.resolve("sub/keep2.txt")), "--files-from must recreate subdirectories");
        assertFalse(Files.exists(dest.resolve("skip1.txt")));
        assertFalse(Files.exists(dest.resolve("other")));
    }

    @Test
    void unselectedPreExistingMismatchIsNotReported() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("keep.txt"), "good\n");
        TestSupport.write(source.resolve("stale.txt"), "source version\n");
        TestSupport.write(dest.resolve("stale.txt"), "different version\n");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selection("keep.txt"), Set.of());

        assertTrue(result.isSuccess(), result.message());
        assertEquals(1, result.filesChecked());
        assertEquals(1, result.filesMatched());
        assertEquals(0, result.filesMismatched(), "unselected files must not influence the counters");
        assertEquals(0, result.filesMissing());
        assertTrue(Files.isRegularFile(dest.resolve("keep.txt")));
        assertEquals("different version\n", Files.readString(dest.resolve("stale.txt")),
                "an unselected destination file must be left untouched");
    }

    // ---------------------------------------------------------------- B3

    @Test
    void copiedCountIsTheRealTransferCount() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("one.txt"), "1\n");
        TestSupport.write(source.resolve("two.txt"), "2\n");
        TestSupport.write(source.resolve("three.txt"), "3\n");

        FileSyncEngine.JobResult first = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selectAllOf(source), Set.of());
        assertTrue(first.isSuccess(), first.message());
        assertEquals(3, first.filesCopied());

        // Second run over identical content: rsync transfers nothing, so nothing may be reported as copied.
        rec = new TestSupport.Recorder();
        FileSyncEngine.JobResult second = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selectAllOf(source), Set.of());
        assertTrue(second.isSuccess(), second.message());
        assertEquals(0, second.filesCopied(), "filesCopied must be measured from rsync output");
        assertEquals(3, second.filesMatched());
        assertEquals(3, second.filesChecked());
        assertTrue(second.message().contains("0 copied"), second.message());
    }

    // ---------------------------------------------------------------- B4

    @Test
    void verifyOnlyClassifiesMissingAndMismatchSeparately() throws Exception {
        TestSupport.write(source.resolve("match.txt"), "same\n");
        TestSupport.write(source.resolve("mismatch.txt"), "source\n");
        TestSupport.write(source.resolve("missing.txt"), "not-at-dest\n");

        TestSupport.write(dest.resolve("match.txt"), "same\n");
        TestSupport.write(dest.resolve("mismatch.txt"), "DESTINATION\n");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.VERIFY_ONLY,
                TestSupport.selection("match.txt", "mismatch.txt", "missing.txt"),
                TestSupport.selection("match.txt", "mismatch.txt", "missing.txt"));

        assertEquals(FileSyncEngine.JobStatus.COMPLETED, result.status());
        assertEquals(3, result.filesChecked());
        assertEquals(1, result.filesMatched());
        assertEquals(1, result.filesMismatched());
        assertEquals(1, result.filesMissing());
        assertEquals(result.filesChecked(),
                result.filesMatched() + result.filesMismatched() + result.filesMissing());

        TestSupport.Recorder.FileEvent missing = rec.eventFor("missing.txt");
        assertNotNull(missing);
        assertEquals("missing", missing.status());
        assertEquals(FileHasher.SHORT_HASH_LENGTH, missing.srcHash().length());
        assertEquals("", missing.dstHash());

        TestSupport.Recorder.FileEvent mismatch = rec.eventFor("mismatch.txt");
        assertNotNull(mismatch);
        assertEquals("mismatch", mismatch.status());
        assertEquals(FileHasher.SHORT_HASH_LENGTH, mismatch.srcHash().length());
        assertEquals(FileHasher.SHORT_HASH_LENGTH, mismatch.dstHash().length());
        assertFalse(mismatch.srcHash().equals(mismatch.dstHash()));
    }

    @Test
    void copyAndVerifyOverwritesMismatchedDestinationFiles() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("keep.txt"), "keep\n");
        TestSupport.write(source.resolve("stale.txt"), "source version\n");
        TestSupport.write(dest.resolve("stale.txt"), "stale destination copy\n");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selection("keep.txt", "stale.txt"), Set.of());

        assertTrue(result.isSuccess(), result.message());
        assertEquals(2, result.filesChecked());
        assertEquals(2, result.filesCopied());
        assertEquals(2, result.filesMatched(), "the stale destination copy must be replaced");
        assertEquals(0, result.filesMismatched());
        assertEquals(0, result.filesMissing());
        assertEquals("source version\n", Files.readString(dest.resolve("stale.txt")));
        assertEquals("copied", rec.eventFor("stale.txt").status());
        assertEquals("copied", rec.eventFor("keep.txt").status());
    }

    // ---------------------------------------------------------------- B5

    @Test
    void progressDenominatorIsTheWorkSetNotTheWholeTree() throws Exception {
        for (int i = 0; i < 40; i++) {
            TestSupport.write(source.resolve(String.format("tree/f%02d.txt", i)), "content " + i);
            TestSupport.write(dest.resolve(String.format("tree/f%02d.txt", i)), "content " + i);
        }
        TestSupport.write(source.resolve("chosen.txt"), "chosen\n");
        TestSupport.write(dest.resolve("chosen.txt"), "chosen\n");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.VERIFY_ONLY,
                TestSupport.selection("chosen.txt"), TestSupport.selection("chosen.txt"));
        assertTrue(result.isSuccess(), result.message());
        assertEquals(1, result.filesChecked(), "40 unselected files must not inflate the total");
        assertEquals(1, result.filesMatched());
        assertEquals(100, rec.lastPercent());
        rec.assertProgressIsCoarse();
        assertTrue(rec.statusTexts.stream().allMatch(s -> s.contains("/1 ")),
                "progress text must count against the work set: " + rec.statusTexts);
        assertTrue(rec.statusTexts.stream().noneMatch(s -> s.contains("/41")), rec.statusTexts.toString());
    }

    @Test
    void copyAndVerifyProgressEndsAt100AfterTheCopyPhaseOffset() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "a\n");
        TestSupport.write(source.resolve("b.txt"), "b\n");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selectAllOf(source), Set.of());

        assertTrue(result.isSuccess(), result.message());
        assertEquals(100, rec.lastPercent());
        rec.assertProgressIsCoarse();
        assertTrue(rec.phases.stream().anyMatch(p -> p.contains("rsync finished")), rec.phases.toString());
        assertTrue(rec.phases.stream().anyMatch(p -> p.startsWith("Work set: 2 selected")), rec.phases.toString());
        assertTrue(rec.files.stream().anyMatch(f -> f.status().equals("copied")), rec.files.toString());
    }

    // ---------------------------------------------------------------- B7

    @Test
    void rsyncExitCode24IsTreatedAsSuccessAndEverythingElseAsFailure() {
        assertTrue(FileSyncEngine.isSuccessfulExit(0));
        assertTrue(FileSyncEngine.isSuccessfulExit(24), "vanished source files are a warning, not a failure");
        assertFalse(FileSyncEngine.isSuccessfulExit(1));
        assertFalse(FileSyncEngine.isSuccessfulExit(23));
        assertFalse(FileSyncEngine.isSuccessfulExit(12));
        assertFalse(FileSyncEngine.isSuccessfulExit(-1));
    }

    @Test
    void unwritableDestinationFailsWithTheRsyncExitCode() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("file.txt"), "payload\n");
        Files.setPosixFilePermissions(dest, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                    TestSupport.selection("file.txt"), Set.of());

            assertEquals(FileSyncEngine.JobStatus.FAILED, result.status());
            assertTrue(result.message().contains("rsync failed"), result.message());
            assertEquals(23, result.rsyncExitCode(), result.errors().toString());
            assertFalse(result.errors().isEmpty(), "rsync stderr must be surfaced");
            assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("rsync:")), result.errors().toString());
        } finally {
            Files.setPosixFilePermissions(dest, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    void validationRejectsMissingDirectoriesAndIdenticalPaths() {
        FileSyncEngine.JobResult sameDir = FileSyncEngine.execute(source.toFile(), source.toFile(),
                FileSyncEngine.JobType.VERIFY_ONLY, TestSupport.selection("x"), Set.of(), rec);
        assertEquals(FileSyncEngine.JobStatus.FAILED, sameDir.status());
        assertTrue(sameDir.message().contains("same directory"), sameDir.message());

        rec = new TestSupport.Recorder();
        FileSyncEngine.JobResult missing = FileSyncEngine.execute(source.resolve("nope").toFile(), dest.toFile(),
                FileSyncEngine.JobType.VERIFY_ONLY, TestSupport.selection("x"), Set.of(), rec);
        assertEquals(FileSyncEngine.JobStatus.FAILED, missing.status());
        assertTrue(missing.message().contains("Source directory does not exist"), missing.message());
    }

    // ---------------------------------------------------------------- B9

    @Test
    void newlineInFileNameIsReportedInsteadOfCorruptingTheFileList() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("good.txt"), "good\n");
        TestSupport.write(source.resolve("bad\nname.txt"), "bad\n");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selection("good.txt", "bad\nname.txt"), Set.of());

        assertTrue(result.isSuccess(), result.message());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("newline")), result.warnings().toString());
        assertEquals(2, result.filesChecked());
        assertEquals(1, result.filesCopied(), "the unsafe name must not be handed to rsync");
        assertEquals(1, result.filesMissing());
        assertTrue(Files.isRegularFile(dest.resolve("good.txt")));
        try (var entries = Files.list(dest)) {
            assertEquals(1, entries.count(), "only the safe file may reach the destination");
        }
    }

    // ---------------------------------------------------------------- B6

    @Test
    void rsyncLineClassificationMatchesRealOutput() {
        assertEquals(FileSyncEngine.LineKind.COPY, FileSyncEngine.classifyLine(">f+++++++++ one.txt"));
        assertEquals(FileSyncEngine.LineKind.COPY, FileSyncEngine.classifyLine("<f.st....  sub/two.txt"));
        assertEquals(FileSyncEngine.LineKind.DIRECTORY, FileSyncEngine.classifyLine("cd+++++++++ sub/deep/"));
        assertEquals(FileSyncEngine.LineKind.DIRECTORY, FileSyncEngine.classifyLine(">d+++++++++ sub"));
        assertEquals(FileSyncEngine.LineKind.ERROR,
                FileSyncEngine.classifyLine("rsync: [sender] link_stat \"/x/nope.txt\" failed: No such file or directory (2)"));
        assertEquals(FileSyncEngine.LineKind.ERROR,
                FileSyncEngine.classifyLine("rsync error: some files/attrs were not transferred (code 23)"));
        assertEquals(FileSyncEngine.LineKind.WARNING,
                FileSyncEngine.classifyLine("rsync warning: some files vanished before they could be transferred (code 24)"));
        assertEquals(FileSyncEngine.LineKind.IGNORED, FileSyncEngine.classifyLine(""));
        assertEquals(FileSyncEngine.LineKind.IGNORED, FileSyncEngine.classifyLine(null));
        assertEquals(FileSyncEngine.LineKind.IGNORED,
                FileSyncEngine.classifyLine("*** Skipping any contents from this failed directory ***"));

        assertEquals("one.txt", FileSyncEngine.itemizePath(">f+++++++++ one.txt"));
        assertEquals("sub/deep/three.txt", FileSyncEngine.itemizePath(">f.st.... sub/deep/three.txt"));
        assertEquals("", FileSyncEngine.itemizePath(">f+++++++++ "));
    }

    @Test
    void selectionIsNormalizedToRelativeForwardSlashPaths() {
        Set<String> input = new java.util.LinkedHashSet<>();
        input.add("");
        input.add(" /abs/path.txt ");
        input.add("sub/file.txt");
        input.add(null);
        input.add("//double/leading.txt");

        Set<String> normalized = FileSyncEngine.normalize(input);
        assertEquals(Set.of("abs/path.txt", "sub/file.txt", "double/leading.txt"), normalized);
    }

    // ---------------------------------------------------------------- misc

    @Test
    void completionIsReportedExactlyOnceWithTheSameResultThatIsReturned() throws Exception {
        TestSupport.write(source.resolve("one.txt"), "1\n");
        TestSupport.write(dest.resolve("one.txt"), "1\n");

        FileSyncEngine.JobResult returned = run(FileSyncEngine.JobType.VERIFY_ONLY,
                TestSupport.selection("one.txt"), TestSupport.selection("one.txt"));

        assertNotNull(rec.result);
        assertEquals(1, rec.completions, "onCompletion must fire exactly once");
        assertEquals(returned, rec.result);
    }
}
