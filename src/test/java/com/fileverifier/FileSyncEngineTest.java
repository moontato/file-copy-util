package com.fileverifier;

import com.fileverifier.FileSyncEngine.JobResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @TempDir
    Path tools;

    private TestSupport.Recorder rec;

    @BeforeEach
    void setUp() {
        rec = new TestSupport.Recorder();
    }

    private FileSyncEngine.JobResult run(FileSyncEngine.JobType type, FileSyncEngine.Selection srcSel,
                                         FileSyncEngine.Selection dstSel) throws Exception {
        return runExpectingUntouchedSource(type, srcSel, dstSel, ScanOptions.DEFAULTS, false, false);
    }

    private FileSyncEngine.JobResult run(FileSyncEngine.JobType type, FileSyncEngine.Selection srcSel,
                                         FileSyncEngine.Selection dstSel, boolean detectExtraFiles)
            throws Exception {
        return runExpectingUntouchedSource(type, srcSel, dstSel, ScanOptions.DEFAULTS, detectExtraFiles,
                false);
    }

    /**
     * Every copy fixture in this suite runs through here, and every one of them asserts the same thing
     * afterwards: <b>the source is the file-for-file tree it was before the job</b>. A copy tool that
     * damages what it reads is the worst failure it can have, and it is the one a test that only looks at
     * the destination would never see.
     *
     * <p>A test that means to change the source mid-job (a race fixture, a fake rsync that edits the
     * source) says so by calling {@link #runChangingSource} instead - the exception is named, not implied.
     */
    private FileSyncEngine.JobResult runExpectingUntouchedSource(FileSyncEngine.JobType type,
            FileSyncEngine.Selection srcSel, FileSyncEngine.Selection dstSel, ScanOptions options,
            boolean detectExtraFiles, boolean dryRun) throws Exception {
        Map<String, String> sourceBefore = TestSupport.digests(source);
        FileSyncEngine.JobResult result = FileSyncEngine.runOnce(TestSupport.request(source, dest, type,
            srcSel, dstSel, options, detectExtraFiles, dryRun), rec);
        TestSupport.assertUnchanged(source, sourceBefore);
        return result;
    }

    /** A fixture that intends the source to change; the source-immutability check is not applied. */
    private FileSyncEngine.JobResult runChangingSource(FileSyncEngine.JobType type,
            FileSyncEngine.Selection srcSel, FileSyncEngine.Selection dstSel, ScanOptions options,
            boolean detectExtraFiles, boolean dryRun) {
        return FileSyncEngine.runOnce(TestSupport.request(source, dest, type, srcSel, dstSel, options,
            detectExtraFiles, dryRun), rec);
    }

    private static void assumeRsync() {
        assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
    }

    /**
     * A pattern that cannot compile was meant to keep a file out of the job. The file is in the job after
     * all, and the job has to say so. What it must not do is make the situation worse by inventing a result:
     * the file is compared honestly, and the pattern is named in a warning.
     */
    @Test
    void anIgnorePatternThatDoesNotCompileWarnsAndDoesNotInventAVerdict() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("keep.txt"), "same\n");
        TestSupport.write(dest.resolve("keep.txt"), "same\n");

        ScanOptions broken = new ScanOptions(List.of("[bad"), true, ScanOptions.SymlinkPolicy.SKIP);
        FileSyncEngine.JobResult result = runExpectingUntouchedSource(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selection("keep.txt"), TestSupport.none(), broken, false, false);

        assertAll(
                // The file is in the job, and its verdict is the honest one: it is there and it matches.
                () -> assertEquals(FileSyncEngine.JobStatus.COMPLETED, result.status(), result.message()),
                () -> assertEquals(1, result.filesMatched(), result.message()),
                () -> assertEquals(0, result.filesMissing(), "no verdict was invented for the broken pattern"),
                () -> assertEquals(0, result.filesMismatched(), "no verdict was invented for the broken pattern"),
                // The protection that failed says which one it was.
                () -> assertTrue(result.warnings().stream().anyMatch(w -> w.contains("[bad")
                                && w.toLowerCase().contains("not applied")),
                        "a warning names the pattern that was not applied: " + result.warnings()));
    }

    // ---------------------------------------------------------------- B1

    @Test
    void aSelectedFileThatNoLongerExistsIsReportedInsteadOfSilentlySkipped() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");

        JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selection("a.txt", "gone.txt"), TestSupport.none());

        assertTrue(result.isSuccess(), result.message());
        assertEquals(1, result.filesChecked(), "the ghost file is not counted - it does not exist");
        assertEquals(1, result.filesCopied());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("not found in the source")
                        && w.contains("gone.txt")),
                String.valueOf(result.warnings()));
    }

    @Test
    void aSelectionOfOnlyGoneFilesFailsAndNamesThem() throws Exception {
        JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selection("gone.txt"), TestSupport.none());

        assertEquals(FileSyncEngine.JobStatus.FAILED, result.status());
        assertTrue(result.message().contains("Selected but not found in the source"), result.message());
        assertTrue(result.message().contains("gone.txt"), result.message());
        assertEquals(0, result.filesChecked());
    }

    @Test
    void aFileMissingAtTheDestinationIsReportedOnceNotAlsoAsAReadError() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");
        TestSupport.write(source.resolve("b.txt"), "b");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.VERIFY_ONLY,
                TestSupport.selection("a.txt", "b.txt"), TestSupport.none());

        assertTrue(result.isSuccess(), result.message());
        assertEquals(2, result.filesMissing());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("Could not read")),
                "absence is the MISSING counter's job, not a warning per file: " + result.warnings());
    }

    @Test
    void emptySelectionCopiesNothingInsteadOfEverything() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");
        TestSupport.write(source.resolve("nested/b.txt"), "b");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY, TestSupport.none(), TestSupport.none());

        assertEquals(FileSyncEngine.JobStatus.FAILED, result.status());
        assertTrue(result.message().contains("empty selection"), result.message());
        assertEquals(0, result.filesChecked());
        assertEquals(0, result.filesCopied());
        try (var entries = Files.list(dest)) {
            assertEquals(0, entries.count(), "an empty selection must not copy the source tree");
        }
    }

    @Test
    void theWorkSetPreviewPredictsTheJobItPreviews() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");
        TestSupport.write(source.resolve("b.txt"), "b");
        TestSupport.write(source.resolve("c.txt"), "c");
        TestSupport.write(dest.resolve("a.txt"), "a");
        TestSupport.write(dest.resolve("b.txt"), "different");

        List<String> scanned = List.of("a.txt", "b.txt", "c.txt");
        var srcSel = TestSupport.selection("a.txt", "b.txt", "c.txt");
        var dstSel = TestSupport.knownRows(Set.of("a.txt", "b.txt"), "a.txt");

        FileSyncEngine.WorkSetPlan copy =
            FileSyncEngine.planWorkSet(scanned, srcSel, dstSel, FileSyncEngine.JobType.COPY_AND_VERIFY);
        assertEquals(3, copy.selectedAtSource());
        assertEquals(0, copy.untickedAtDestination(), "a copy does not honour destination ticks");
        assertEquals(3, copy.willProcess());

        FileSyncEngine.WorkSetPlan verify =
            FileSyncEngine.planWorkSet(scanned, srcSel, dstSel, FileSyncEngine.JobType.VERIFY_ONLY);
        assertEquals(1, verify.untickedAtDestination());
        assertEquals(2, verify.willProcess());
        assertEquals("b.txt", verify.describeUnticked());

        // The preview is only worth showing if the job agrees with it. Verify first, because the copy
        // would repair the very mismatch this test set up.
        FileSyncEngine.JobResult verifyJob = run(FileSyncEngine.JobType.VERIFY_ONLY, srcSel, dstSel);
        assertEquals(verify.willProcess(), verifyJob.filesChecked(), verifyJob.message());
        assertEquals(1, verifyJob.filesMatched(), verifyJob.message());
        assertEquals(1, verifyJob.filesMissing(), "c.txt is absent at the destination");
        assertEquals(0, verifyJob.filesMismatched(), "the mismatched b.txt was the excluded one");

        FileSyncEngine.JobResult copyJob = run(FileSyncEngine.JobType.COPY_AND_VERIFY, srcSel, dstSel);
        assertEquals(copy.willProcess(), copyJob.filesChecked(), copyJob.message());
        assertEquals(2, copyJob.filesCopied(), "b.txt differs and c.txt is missing: " + copyJob.message());
        assertEquals(3, copyJob.filesMatched(), copyJob.message());
    }

    @Test
    void theConstantTimePreviewAgreesWithTheFullOne() {
        // The GUI counts exclusions by looking up each unticked destination row; the engine's other
        // overload walks the source list. Same rule, so the same numbers, whatever the tick pattern is.
        for (long seed : new long[] {1L, 7L, 42L, 1234L}) {
            java.util.Random random = new java.util.Random(seed);
            List<String> scanned = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                scanned.add("f" + i + ".txt");
            }
            Set<String> srcSelected = new HashSet<>();
            for (String rel : scanned) {
                if (random.nextInt(100) < 70) {
                    srcSelected.add(rel);
                }
            }
            Set<String> destKnown = new HashSet<>();
            for (String rel : scanned) {
                if (random.nextInt(100) < 60) {
                    destKnown.add(rel);
                }
            }
            Set<String> destSelected = new HashSet<>();
            for (String rel : destKnown) {
                if (random.nextInt(100) < 40) {
                    destSelected.add(rel);
                }
            }
            Set<String> destUnticked = new HashSet<>(destKnown);
            destUnticked.removeAll(destSelected);

            FileSyncEngine.WorkSetPlan full = FileSyncEngine.planWorkSet(scanned,
                new FileSyncEngine.Selection(srcSelected, new HashSet<>(scanned)),
                new FileSyncEngine.Selection(destSelected, destKnown),
                FileSyncEngine.JobType.VERIFY_ONLY);
            FileSyncEngine.WorkSetPlan fast = FileSyncEngine.planWorkSet(srcSelected.size(), destUnticked,
                srcSelected::contains);

            assertEquals(full.selectedAtSource(), fast.selectedAtSource(), "seed " + seed);
            assertEquals(full.untickedAtDestination(), fast.untickedAtDestination(), "seed " + seed);
            assertEquals(full.willProcess(), fast.willProcess(), "seed " + seed);
            if (full.describeUnticked() != null && fast.describeUnticked() != null) {
                assertEquals(Set.copyOf(full.untickedNames()), Set.copyOf(fast.untickedNames()), "seed " + seed);
            }
        }
    }

    @Test
    void aPreviewNamesFewExclusionsButNotThousands() {
        List<String> scanned = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (int i = 0; i < 9; i++) {
            scanned.add("f" + i + ".txt");
            known.add("f" + i + ".txt");
        }
        FileSyncEngine.WorkSetPlan plan = FileSyncEngine.planWorkSet(scanned,
            TestSupport.selection(scanned.toArray(new String[0])),
            TestSupport.knownRows(known), FileSyncEngine.JobType.VERIFY_ONLY);

        assertEquals(9, plan.selectedAtSource());
        assertEquals(9, plan.untickedAtDestination());
        assertEquals(0, plan.willProcess());
        // Naming 5 of 9 would imply the rest are unimportant, so nothing is named and only the count is.
        assertTrue(plan.describeUnticked() == null);
    }

    @Test
    void untickingAnExistingDestinationRowExcludesItFromVerification() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");
        TestSupport.write(source.resolve("b.txt"), "b");
        TestSupport.write(dest.resolve("a.txt"), "a");
        TestSupport.write(dest.resolve("b.txt"), "b");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.VERIFY_ONLY,
                TestSupport.selection("a.txt", "b.txt"),
                TestSupport.knownRows(Set.of("a.txt", "b.txt"), "a.txt"));

        assertTrue(result.isSuccess(), result.message());
        assertEquals(1, result.filesChecked());
        assertEquals(1, result.filesMatched());
    }

    @Test
    void staleDestinationListDoesNotSilentlySkipFiles() throws Exception {
        // The GUI exposed this: after a copy job the destination list can be stale. "Not in the
        // list" must not be read as "the user unticked it", or verification would silently skip
        // the very files that were just copied.
        TestSupport.write(source.resolve("a.txt"), "a");
        TestSupport.write(source.resolve("b.txt"), "b");
        TestSupport.write(dest.resolve("a.txt"), "a");
        TestSupport.write(dest.resolve("b.txt"), "b");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.VERIFY_ONLY,
                TestSupport.selection("a.txt", "b.txt"),
                TestSupport.knownRows(Set.of()));

        assertTrue(result.isSuccess(), result.message());
        assertEquals(2, result.filesChecked());
        assertEquals(2, result.filesMatched());
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
        assertEquals("MISSING", rec.eventFor("absent.txt").status());
    }

    @Test
    void untickingEveryExistingDestinationRowLeavesAnEmptyWorkSet() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");
        TestSupport.write(dest.resolve("a.txt"), "a");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.VERIFY_ONLY,
                TestSupport.selection("a.txt"), TestSupport.knownRows(Set.of("a.txt")));

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
                TestSupport.selection("keep1.txt", "sub/keep2.txt"), TestSupport.none());

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
                TestSupport.selection("keep.txt"), TestSupport.none());

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
                TestSupport.selectAllOf(source), TestSupport.none());
        assertTrue(first.isSuccess(), first.message());
        assertEquals(3, first.filesCopied());

        // Second run over identical content: rsync transfers nothing, so nothing may be reported as copied.
        rec = new TestSupport.Recorder();
        FileSyncEngine.JobResult second = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selectAllOf(source), TestSupport.none());
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
                TestSupport.selection("match.txt", "mismatch.txt"));

        assertEquals(FileSyncEngine.JobStatus.COMPLETED, result.status());
        assertEquals(3, result.filesChecked());
        assertEquals(1, result.filesMatched());
        assertEquals(1, result.filesMismatched());
        assertEquals(1, result.filesMissing());
        assertEquals(result.filesChecked(),
                result.filesMatched() + result.filesMismatched() + result.filesMissing());

        TestSupport.Recorder.FileEvent missing = rec.eventFor("missing.txt");
        assertNotNull(missing);
        assertEquals("MISSING", missing.status());
        assertEquals(FileHasher.SHORT_HASH_LENGTH, missing.srcHash().length());
        assertNull(missing.dstHash(), "a file with no destination copy has no destination hash");

        TestSupport.Recorder.FileEvent mismatch = rec.eventFor("mismatch.txt");
        assertNotNull(mismatch);
        assertEquals("MISMATCH", mismatch.status());
        assertEquals(FileHasher.SHORT_HASH_LENGTH, mismatch.srcHash().length());
        assertEquals(FileHasher.SHORT_HASH_LENGTH, mismatch.dstHash().length());
        assertFalse(mismatch.srcHash().equals(mismatch.dstHash()));

        assertEquals(3, result.outcomes().size(), "one outcome per checked file");
        assertEquals(result.filesChecked(), result.outcomes().size());
        assertEquals("MISMATCH", result.outcomes().stream()
                .filter(o -> o.relPath().equals("mismatch.txt")).findFirst().orElseThrow().kind());
        assertNotNull(result.outcomes().stream()
                .filter(o -> o.relPath().equals("match.txt")).findFirst().orElseThrow().sourceHash());
    }

    @Test
    void copyAndVerifyOverwritesMismatchedDestinationFiles() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("keep.txt"), "keep\n");
        TestSupport.write(source.resolve("stale.txt"), "source version\n");
        TestSupport.write(dest.resolve("stale.txt"), "stale destination copy\n");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selection("keep.txt", "stale.txt"), TestSupport.none());

        assertTrue(result.isSuccess(), result.message());
        assertEquals(2, result.filesChecked());
        assertEquals(2, result.filesCopied());
        assertEquals(2, result.filesMatched(), "the stale destination copy must be replaced");
        assertEquals(0, result.filesMismatched());
        assertEquals(0, result.filesMissing());
        assertEquals("source version\n", Files.readString(dest.resolve("stale.txt")));
        assertEquals("TRANSFERRING", rec.eventFor("stale.txt").status());
        assertEquals("TRANSFERRING", rec.eventFor("keep.txt").status());
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
        assertTrue(rec.statusTexts.stream().allMatch(s -> s.contains("of 1")),
                "progress text must count against the work set: " + rec.statusTexts);
        assertTrue(rec.statusTexts.stream().noneMatch(s -> s.contains("of 41")), rec.statusTexts.toString());
    }

    @Test
    void copyAndVerifyProgressEndsAt100AfterTheCopyPhaseOffset() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "a\n");
        TestSupport.write(source.resolve("b.txt"), "b\n");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selectAllOf(source), TestSupport.none());

        assertTrue(result.isSuccess(), result.message());
        assertEquals(100, rec.lastPercent());
        rec.assertProgressIsCoarse();
        assertEquals(List.of("hashing source", "copying", "verifying", "done"), rec.phases,
            "hashing the source is not copying, and a job that is doing it must not be labelled copying: "
                + rec.phases);
        assertTrue(rec.files.stream().anyMatch(f -> f.status().equals("TRANSFERRING")), rec.files.toString());
        assertTrue(rec.percents.contains(FileSyncEngine.COPY_PROGRESS_END),
                "the bar must reach the end of the copy phase: " + rec.percents);
    }

    @Test
    void copyPhaseProgressFollowsTheRsyncTransferPercentage() throws Exception {
        // Driven by a scripted stand-in rsync so the sequence is exact instead of timing-dependent:
        // rsync's 30% and 60% must land inside the 0..70 copy band, not be reported as done.
        TestSupport.write(source.resolve("one.txt"), "1\n");
        Path fake = TestSupport.copyingFakeRsync(tools,
                ">f+++++++++ one.txt\n"
                        + "              3   0%    0.00kB/s    0:00:00\n"
                        + "      100,000,000  30%   10.00MB/s    0:00:01\n"
                        + "      200,000,000  60%   10.00MB/s    0:00:02\n"
                        + "      333,333,333 100%   10.00MB/s    0:00:03 (xfr#1, to-chk=0/1)");
        TestSupport.withRsyncBinary(fake.toString(), () -> {
            FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                    TestSupport.selection("one.txt"), TestSupport.none());

            assertTrue(result.isSuccess(), result.message());
            assertEquals(1, result.filesCopied(), "one itemize line, one copied file");
            assertEquals(1, result.filesMatched());
            // 0 (pre-copy hashing), 21 (30% of the 70-point copy band), 42 (60%), 70 (complete),
            // 100 (verified).
            assertEquals(List.of(0, 21, 42, FileSyncEngine.COPY_PROGRESS_END, 100), rec.percents,
                    rec.percents.toString());
            assertTrue(rec.statusTexts.stream().anyMatch(s -> s.contains("30%") && s.contains("one.txt")),
                "the copy status has to name the file being written and how far along the transfer is - "
                    + "a 30 GB file otherwise shows one number that barely moves for an hour: "
                    + rec.statusTexts);
        });

    }

    // ---------------------------------------------------------------- destination-only files

    @Test
    void destinationOnlyFilesAreReportedAsExtraWhenDetectionIsOn() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a\n");
        TestSupport.write(dest.resolve("a.txt"), "a\n");
        TestSupport.write(dest.resolve("orphan.txt"), "not in the source\n");

        JobResult result = run(FileSyncEngine.JobType.VERIFY_ONLY,
                TestSupport.selection("a.txt"), TestSupport.selection("a.txt"), true);

        assertTrue(result.isSuccess(), result.message());
        // The destination-only file was reported, not compared: it counts in extras and in the
        // progress unit, never in checked.
        assertEquals(1, result.filesChecked());
        assertEquals(2, result.filesProcessed());
        assertEquals(1, result.filesMatched());
        assertEquals(1, result.filesExtra());
        assertEquals("EXTRA", rec.eventFor("orphan.txt").status());
        assertTrue(rec.eventFor("orphan.txt").srcHash() == null, "an extra file has no source hash");
        assertTrue(result.message().contains("destination-only"), result.message());
        // The equation this program reports by: checked is the sum of the verdict figures, and a reported
        // extra is not a verdict. The unit that counted both rows is filesProcessed.
        assertEquals(result.filesChecked(),
                result.filesMatched() + result.filesMismatched() + result.filesMissing());
        assertEquals(result.filesProcessed(), result.filesChecked() + result.filesExtra());
    }

    @Test
    void destinationOnlyDetectionIsOptOut() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a\n");
        TestSupport.write(dest.resolve("a.txt"), "a\n");
        TestSupport.write(dest.resolve("orphan.txt"), "not in the source\n");

        JobResult result = run(FileSyncEngine.JobType.VERIFY_ONLY,
                TestSupport.selection("a.txt"), TestSupport.selection("a.txt"), false);

        assertTrue(result.isSuccess(), result.message());
        assertEquals(1, result.filesChecked());
        assertEquals(0, result.filesExtra());
    }

    @Test
    void aDestinationOnlyFileCanBeExcludedByUntickingItsRow() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a\n");
        TestSupport.write(dest.resolve("a.txt"), "a\n");
        TestSupport.write(dest.resolve("orphan.txt"), "not in the source\n");

        JobResult result = run(FileSyncEngine.JobType.VERIFY_ONLY,
                TestSupport.selection("a.txt"),
                TestSupport.knownRows(Set.of("a.txt", "orphan.txt"), "a.txt"), true);

        assertTrue(result.isSuccess(), result.message());
        assertEquals(1, result.filesChecked());
        assertEquals(0, result.filesExtra(), "the row was visible in the list and deliberately unticked");
    }

    @Test
    void copyModeNeverReportsExtras() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "a\n");
        TestSupport.write(dest.resolve("orphan.txt"), "not in the source\n");

        JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selection("a.txt"), TestSupport.none(), true);

        assertTrue(result.isSuccess(), result.message());
        assertEquals(1, result.filesChecked());
        assertEquals(0, result.filesExtra(), "copying is source-driven; the destination list is not verified");
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
    void unwritableDestinationIsRefusedBeforeRsyncRuns() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("file.txt"), "payload\n");
        Files.setPosixFilePermissions(dest, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                    TestSupport.selection("file.txt"), TestSupport.none());

            assertEquals(FileSyncEngine.JobStatus.FAILED, result.status());
            assertTrue(result.message().contains("not writable"), result.message());
            assertEquals(0, result.rsyncExitCode(), "preflight must reject this before starting rsync");
            assertEquals(0, result.filesCopied());
            assertFalse(result.errors().isEmpty());
        } finally {
            Files.setPosixFilePermissions(dest, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    void preflightReportsEachProblemInWordsTheUserCanActOn() throws Exception {
        TestSupport.write(source.resolve("one.txt"), "1\n");

        List<String> sameDir = FileSyncEngine.validate(
                TestSupport.request(source, source, FileSyncEngine.JobType.COPY_AND_VERIFY,
                        TestSupport.selection("one.txt"), TestSupport.none()));
        assertTrue(sameDir.stream().anyMatch(p -> p.contains("same directory")), sameDir.toString());

        // Overlap is a write hazard. A read-only comparison of a directory against itself is pointless, but
        // it overwrites nothing, and refusing it here would be this program inventing a danger.
        assertTrue(FileSyncEngine.validate(TestSupport.request(source, source,
                FileSyncEngine.JobType.VERIFY_ONLY, TestSupport.selection("one.txt"),
                TestSupport.none())).isEmpty(),
                "Verify Only is not refused for overlap: " + FileSyncEngine.validate(
                    TestSupport.request(source, source, FileSyncEngine.JobType.VERIFY_ONLY,
                        TestSupport.selection("one.txt"), FileSyncEngine.Selection.none())));

        Path nested = source.resolve("inside/dest");
        Files.createDirectories(nested);
        List<String> nestedProblems = FileSyncEngine.validate(
                TestSupport.request(source, nested, FileSyncEngine.JobType.COPY_AND_VERIFY,
                        TestSupport.selection("one.txt"), TestSupport.none()));
        assertTrue(nestedProblems.stream().anyMatch(p -> p.contains("inside the source")),
                nestedProblems.toString());

        List<String> missingSource = FileSyncEngine.validate(
                TestSupport.request(source.resolve("nope"), dest, FileSyncEngine.JobType.VERIFY_ONLY,
                        TestSupport.selection("one.txt"), TestSupport.none()));
        assertTrue(missingSource.stream().anyMatch(p -> p.contains("Source is not a directory")),
                missingSource.toString());

        List<String> ok = FileSyncEngine.validate(
                TestSupport.request(source, dest, FileSyncEngine.JobType.VERIFY_ONLY,
                        TestSupport.selection("one.txt"), TestSupport.none()));
        assertEquals(List.of(), ok, "a valid job must have no problems");

        JobResult refused = FileSyncEngine.runOnce(
                TestSupport.request(source.resolve("nope"), dest, FileSyncEngine.JobType.VERIFY_ONLY,
                        TestSupport.selection("one.txt"), TestSupport.none()), rec);
        assertEquals(FileSyncEngine.JobStatus.FAILED, refused.status());
        assertTrue(refused.message().startsWith("Preflight failed:"), refused.message());
        assertFalse(refused.errors().isEmpty(), "the same problems are reported as errors");
    }

    @Test
    void missingRsyncBinaryIsReportedAsAMissingDependencyNotAFailureMystery() throws Exception {
        TestSupport.write(source.resolve("one.txt"), "1\n");
        TestSupport.withRsyncBinary("definitely-not-an-installed-rsync", () -> {
            FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                    TestSupport.selection("one.txt"), TestSupport.none());

            assertEquals(FileSyncEngine.JobStatus.FAILED, result.status());
            assertTrue(result.message().contains("rsync was not found"), result.message());
            assertTrue(result.message().contains("Verify Only"),
                    "the message must offer the mode that needs no rsync: " + result.message());
            assertFalse(result.message().contains("rsync failed"), result.message());
            assertEquals(0, result.filesChecked());

            // Verify Only must still work without rsync.
            TestSupport.write(dest.resolve("one.txt"), "1\n");
            rec = new TestSupport.Recorder();
            FileSyncEngine.JobResult verifyOnly = run(FileSyncEngine.JobType.VERIFY_ONLY,
                    TestSupport.selection("one.txt"), TestSupport.selection("one.txt"));
            assertTrue(verifyOnly.isSuccess(), verifyOnly.message());
            assertEquals(1, verifyOnly.filesMatched());
        });

    }

    @Test
    void rsyncFailureSurfacesItsExitCodeAndOwnErrorLines() throws Exception {
        TestSupport.write(source.resolve("one.txt"), "1\n");
        Path fake = TestSupport.fakeRsync(tools,
                ">f+++++++++ one.txt\n"
                        + "rsync: [receiver] mkstemp \"dest/.one.txt.XXXXXX\" failed: Permission denied (13)\n"
                        + "rsync error: some files/attrs were not transferred (see previous errors) (code 23)",
                23);
        TestSupport.withRsyncBinary(fake.toString(), () -> {
            FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                    TestSupport.selection("one.txt"), TestSupport.none());

            assertEquals(FileSyncEngine.JobStatus.FAILED, result.status());
            assertEquals(23, result.rsyncExitCode(), result.errors().toString());
            assertTrue(result.message().contains("rsync failed (exit code 23)"), result.message());
            assertTrue(result.errors().stream().anyMatch(e -> e.contains("rsync exited with code 23")),
                    result.errors().toString());
            assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("rsync: [") && e.contains("failed:")),
                    "rsync's own per-file error line must be surfaced: " + result.errors());
        });

    }

    // ---------------------------------------------------------------- B9

    /**
     * A newline in a file name used to be refused, because the list handed to rsync was a line list.
     * It is a NUL-delimited list now, so the name is transferred like any other, and what the job has
     * to get right is not losing it on the way in or out.
     */
    @Test
    void aNameContainingANewlineIsCopiedAndNamedExactly() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("good.txt"), "good\n");
        TestSupport.write(source.resolve("bad\nname.txt"), "bad\n");

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selection("good.txt", "bad\nname.txt"), TestSupport.none());

        assertTrue(result.isSuccess(), result.message());
        assertEquals(2, result.filesChecked(), result.message());
        assertEquals(2, result.filesMatched(), result.message());
        assertEquals(2, result.filesCopied(), result.message());
        assertTrue(Files.isRegularFile(dest.resolve("bad\nname.txt")),
                "the file must arrive under the name it has in the source");
        assertTrue(result.outcomes().stream().anyMatch(o -> o.relPath().equals("bad\nname.txt")),
                "the row must name the file with its newline intact: " + result.outcomes());
    }

    // ---------------------------------------------------------------- B6

    @Test
    void rsyncLineClassificationMatchesRealOutput() {
        assertEquals(FileSyncEngine.LineKind.COPY, FileSyncEngine.classifyLine(">f+++++++++ one.txt"));
        assertEquals(FileSyncEngine.LineKind.COPY, FileSyncEngine.classifyLine("<f.st....  sub/two.txt"));
        assertEquals(FileSyncEngine.LineKind.DIRECTORY, FileSyncEngine.classifyLine("cd+++++++++ sub/deep/"));
        assertEquals(FileSyncEngine.LineKind.DIRECTORY, FileSyncEngine.classifyLine(">d+++++++++ sub"));
        assertEquals(FileSyncEngine.LineKind.DIRECTORY, FileSyncEngine.classifyLine(".d..t....  sub"));
        // rsync's itemize format is <update type><item type>: 'c' means "local create or change", which
        // is how a created directory AND a changed/created symlink are reported. 'L' is a symlink, so a
        // copied link shows up as cL - counting it matters because -a copies symlinks as links.
        assertEquals(FileSyncEngine.LineKind.COPY, FileSyncEngine.classifyLine("cL+++++++++ link.txt -> a.txt"));
        assertEquals(FileSyncEngine.LineKind.COPY, FileSyncEngine.classifyLine("hf+++++++++ hard.txt"));
        assertEquals(FileSyncEngine.LineKind.COPY, FileSyncEngine.classifyLine("cD+++++++++ node"));
        assertEquals(FileSyncEngine.LineKind.COPY, FileSyncEngine.classifyLine(">S+++++++++ fifo"));
        // An item that was only touched (attributes changed) was not transferred.
        assertEquals(FileSyncEngine.LineKind.IGNORED, FileSyncEngine.classifyLine(".f..t....  same.txt"));
        assertEquals(FileSyncEngine.LineKind.WARNING, FileSyncEngine.classifyLine("*deleting   gone.txt"));
        assertEquals(FileSyncEngine.LineKind.ERROR,
                FileSyncEngine.classifyLine("rsync: [sender] link_stat \"/x/nope.txt\" failed: No such file or directory (2)"));
        assertEquals(FileSyncEngine.LineKind.ERROR,
                FileSyncEngine.classifyLine("rsync error: some files/attrs were not transferred (code 23)"));
        assertEquals(FileSyncEngine.LineKind.WARNING,
                FileSyncEngine.classifyLine("rsync warning: some files vanished before they could be transferred (code 24)"));
        assertEquals(FileSyncEngine.LineKind.IGNORED, FileSyncEngine.classifyLine(""));
        assertEquals(FileSyncEngine.LineKind.IGNORED, FileSyncEngine.classifyLine(null));
        // rsync's '*'-message lines are notable ("* Skipping any contents from this failed directory")
        // and must reach the user as a warning, not be swallowed.
        assertEquals(FileSyncEngine.LineKind.WARNING,
                FileSyncEngine.classifyLine("*** Skipping any contents from this failed directory ***"));

        assertEquals("one.txt", FileSyncEngine.itemizePath(">f+++++++++ one.txt"));
        assertEquals("sub/deep/three.txt", FileSyncEngine.itemizePath(">f.st.... sub/deep/three.txt"));
        assertNull(FileSyncEngine.itemizePath(">f+++++++++ "), "a line with no name has no path");
        assertNull(FileSyncEngine.itemizePath("cd+++++++++ sub/"), "directory lines carry no file to count");
    }

    @Test
    void linkPolicyCopiesASymlinkAsALinkAndCountsIt() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "alpha");
        Files.createSymbolicLink(source.resolve("link.txt"), source.resolve("a.txt"));
        ScanOptions linkMode = new ScanOptions(ScanOptions.DEFAULTS.ignorePatterns(), true,
                ScanOptions.SymlinkPolicy.LINK);

        JobResult result = FileSyncEngine.runOnce(TestSupport.request(source, dest,
                FileSyncEngine.JobType.COPY_AND_VERIFY, TestSupport.selection("a.txt", "link.txt"),
                TestSupport.none(), linkMode, false), rec);

        assertTrue(result.isSuccess(), result.message());
        // -a copies a link as a link, and rsync reports it as 'cL' - a link is a copied item even
        // though no file data moves, so it has to be counted.
        assertEquals(2, result.filesCopied(), result.toString());
        assertEquals(2, result.filesMatched());
        assertTrue(Files.isSymbolicLink(dest.resolve("link.txt")), "the link must stay a link");
        assertTrue(result.warnings().isEmpty(), String.valueOf(result.warnings()));
    }

    @Test
    void followPolicyCopiesTheTargetInsteadOfTheLink() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "alpha");
        Files.createSymbolicLink(source.resolve("link.txt"), source.resolve("a.txt"));
        ScanOptions followMode = new ScanOptions(ScanOptions.DEFAULTS.ignorePatterns(), true,
                ScanOptions.SymlinkPolicy.FOLLOW);

        JobResult result = FileSyncEngine.runOnce(TestSupport.request(source, dest,
                FileSyncEngine.JobType.COPY_AND_VERIFY, TestSupport.selection("a.txt", "link.txt"),
                TestSupport.none(), followMode, false), rec);

        assertTrue(result.isSuccess(), result.message());
        assertEquals(2, result.filesCopied(), result.toString());
        assertEquals(2, result.filesMatched());
        assertTrue(Files.isRegularFile(dest.resolve("link.txt")) && !Files.isSymbolicLink(dest.resolve("link.txt")),
                "FOLLOW must hand over the target contents, not a dangling link");
        assertEquals("alpha", Files.readString(dest.resolve("link.txt")));
    }

    @Test
    void skipPolicyLeavesTheLinkOutAndSaysSo() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "alpha");
        Files.createSymbolicLink(source.resolve("link.txt"), source.resolve("a.txt"));

        JobResult result = FileSyncEngine.runOnce(TestSupport.request(source, dest,
                FileSyncEngine.JobType.COPY_AND_VERIFY, TestSupport.selection("a.txt", "link.txt"),
                TestSupport.none(), ScanOptions.DEFAULTS, false), rec);

        assertTrue(result.isSuccess(), result.message());
        assertEquals(1, result.filesChecked(), "the skipped link is not in the work set");
        assertEquals(0, result.filesMissing());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("symbolic link")),
                String.valueOf(result.warnings()));
        assertFalse(Files.exists(dest.resolve("link.txt")));
    }

    @Test
    void rsyncTransferPercentageIsParsedFromProgressLines() {
        // Real rsync 3.2.7 --info=progress2 lines.
        assertEquals(0, FileSyncEngine.rsyncTransferPercent(
                "              3   0%    0.00kB/s    0:00:00 (xfr#1, to-chk=3/4)"));
        assertEquals(91, FileSyncEngine.rsyncTransferPercent(
                "  1,366,917,123  91%    1.27GB/s    0:00:00  "));
        assertEquals(100, FileSyncEngine.rsyncTransferPercent(
                "  1,500,000,006 100%  975.13MB/s    0:00:01 (xfr#3, to-chk=0/4)"));
        assertEquals(-1, FileSyncEngine.rsyncTransferPercent(">f+++++++++ one.txt"));
        assertEquals(-1, FileSyncEngine.rsyncTransferPercent("rsync error: something (code 23)"));
        assertEquals(-1, FileSyncEngine.rsyncTransferPercent(""));
    }

    @Test
    void selectionIsNormalizedToRelativeForwardSlashPaths() {
        Set<String> input = new java.util.LinkedHashSet<>();
        input.add("");
        input.add("/abs/path.txt");
        input.add("sub/file.txt");
        input.add(null);
        input.add("//double/leading.txt");
        input.add("./dotted.txt");
        input.add(" spaced ");

        Set<String> normalized = FileSyncEngine.normalize(input);
        assertEquals(Set.of("abs/path.txt", "sub/file.txt", "double/leading.txt", "dotted.txt", " spaced "),
                normalized, "syntax is rewritten; the whitespace that belongs to a name is not");
    }

    // ---------------------------------------------------------------- scale limits (5.4)

    @Test
    void theResultsListIsBoundedButTheCountersAreNot() throws Exception {
        for (int i = 0; i < 8; i++) {
            TestSupport.write(source.resolve("f" + i + ".txt"), "c" + i + "\n");
            TestSupport.write(dest.resolve("f" + i + ".txt"), "c" + i + "\n");
        }

        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.setResultLimit(5);
            assertEquals(5, engine.resultLimit());
            FileSyncEngine.JobResult result = engine.execute(TestSupport.request(source, dest,
                    FileSyncEngine.JobType.VERIFY_ONLY, TestSupport.selectAllOf(source),
                    TestSupport.selectAllOf(dest)), new TestSupport.Recorder());

            assertTrue(result.isSuccess(), result.message());
            assertEquals(8, result.filesChecked(), "counters must stay exact when the list is truncated");
            assertEquals(8, result.filesMatched());
            assertEquals(5, result.outcomes().size());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("truncated at 5")),
                    String.valueOf(result.warnings()));
        }
    }

    @Test
    void resultsKeepWorkSetOrderSoALargeExportIsDiffable() throws Exception {
        // The hashing runs on a pool; the reported results must still arrive in work-set order, or a
        // 100k-row export would be a different order on every run.
        for (int i = 0; i < 2_000; i++) {
            TestSupport.write(source.resolve(String.format("f%04d.txt", i)), "c" + i + "\n");
            TestSupport.write(dest.resolve(String.format("f%04d.txt", i)), "c" + i + "\n");
        }

        FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.VERIFY_ONLY,
                TestSupport.selectAllOf(source), TestSupport.selectAllOf(dest));

        assertEquals(2_000, result.filesChecked());
        assertEquals(2_000, result.outcomes().size());
        List<String> reported = result.outcomes().stream().map(FileSyncEngine.FileOutcome::relPath).toList();
        assertEquals(List.of("f0000.txt", "f1999.txt"), List.of(reported.get(0), reported.get(1999)));
        String[] copy = reported.toArray(new String[0]);
        Arrays.sort(copy);
        assertTrue(Arrays.equals(reported.toArray(new String[0]), copy), "results must be in work-set order");
    }

    @Test
    void aFileThatExistsButCannotBeReadIsAMismatchNotAMissingFile() throws Exception {
        TestSupport.write(source.resolve("locked.bin"), "secret\n");
        TestSupport.write(dest.resolve("locked.bin"), "secret\n");
        Path unreadable = source.resolve("locked.bin");
        Set<PosixFilePermission> none = EnumSet.noneOf(PosixFilePermission.class);
        Set<PosixFilePermission> readable = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(unreadable, none);
        try {
            FileSyncEngine.JobResult result = run(FileSyncEngine.JobType.VERIFY_ONLY,
                    TestSupport.selection("locked.bin"), TestSupport.selection("locked.bin"));

            assertEquals(1, result.filesMismatched(), "unreadable is not the same as absent: " + result.message());
            assertEquals(0, result.filesMissing(), result.message());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Could not read file")),
                    String.valueOf(result.warnings()));
        } finally {
            Files.setPosixFilePermissions(unreadable, readable);
        }
    }


    // ---------------------------------------------------------------- dry run (5.3)

    private FileSyncEngine.JobResult runDry(FileSyncEngine.JobType type, FileSyncEngine.Selection srcSel,
                                            FileSyncEngine.Selection dstSel) {
        return FileSyncEngine.runOnce(TestSupport.request(source, dest, type, srcSel, dstSel,
                ScanOptions.DEFAULTS, false, true), rec);
    }

    @Test
    void dryRunReportsWhatWouldCopyAndWritesNothing() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "a\n");
        TestSupport.write(source.resolve("sub/b.txt"), "b\n");

        FileSyncEngine.JobResult result = runDry(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selection("a.txt", "sub/b.txt"), TestSupport.none());

        assertTrue(result.isSuccess(), result.message());
        assertTrue(result.message().startsWith("Dry run - nothing was written:"), result.message());
        assertTrue(result.message().contains("2 would be copied"), result.message());
        assertEquals(2, result.filesWouldCopy(),
                "a dry run's figure is intent: these are the files it would have copied");
        assertEquals(0, result.filesCopied(),
                "and it copied none of them, whatever its count said");
        assertEquals(2, result.filesMissing(), "nothing is at the destination yet");
        try (var walk = Files.walk(dest)) {
            assertTrue(walk.noneMatch(Files::isRegularFile), "a dry run must not write anything");
        }
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("--dry-run")),
                String.valueOf(result.warnings()));
    }

    @Test
    void dryRunOnIdenticalTreesSaysThereIsNothingToCopy() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "same\n");
        TestSupport.write(source.resolve("b.txt"), "same2\n");
        TestSupport.write(dest.resolve("a.txt"), "same\n");
        TestSupport.write(dest.resolve("b.txt"), "same2\n");

        FileSyncEngine.JobResult result = runDry(FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selection("a.txt", "b.txt"), TestSupport.none());

        assertTrue(result.isSuccess(), result.message());
        assertEquals(0, result.filesCopied(), "unchanged files are not announced even in a dry run");
        assertEquals(2, result.filesMatched());
        assertEquals(0, result.filesMissing());
    }

    @Test
    void dryRunStillFindsMismatchesAndDestinationOnlyFiles() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "source\n");
        TestSupport.write(dest.resolve("a.txt"), "different\n");
        TestSupport.write(dest.resolve("orphan.txt"), "only at destination\n");

        FileSyncEngine.JobResult result = FileSyncEngine.runOnce(TestSupport.request(source, dest,
                FileSyncEngine.JobType.VERIFY_ONLY, TestSupport.selection("a.txt"), TestSupport.none(),
                ScanOptions.DEFAULTS, true, true), rec);

        assertTrue(result.isSuccess(), result.message());
        assertEquals(1, result.filesMismatched(), result.message());
        assertEquals(1, result.filesExtra(), result.message());
        assertFalse(Files.exists(dest.resolve("a.txt.bak")), "nothing may be written by a dry run");
    }

    // ---------------------------------------------------------------- interrupted transfers (5.1, 5.2)

    @Test
    void aCutShortCopyReportsTransfersRsyncFinishedNotOnesItAnnounced() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a\n");
        TestSupport.write(source.resolve("b.bin"), "b\n");
        TestSupport.write(source.resolve("c.bin"), "c\n");

        // Scripted rsync: announces three files, reports one completed transfer, creates the hidden
        // partial file a killed transfer leaves, then hangs until the deadline cuts it off.
        Path fake = TestSupport.hangingFakeRsync(tools, String.join("\n",
                ">f+++++++++ a.txt",
                "          2   33%    1.00kB/s    0:00:01 (xfr#1, to-chk=2/3)",
                ">f+++++++++ b.bin",
                ">f+++++++++ c.bin"), ".b.bin.ABCDEF");

        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(fake.toString());
            engine.setJobTimeoutMillis(1_500L);
            FileSyncEngine.JobResult result = engine.execute(TestSupport.request(source, dest,
                    FileSyncEngine.JobType.COPY_AND_VERIFY,
                    TestSupport.selection("a.txt", "b.bin", "c.bin"), TestSupport.none()),
                    new TestSupport.Recorder());

            assertEquals(FileSyncEngine.JobStatus.FAILED, result.status());
            // Three announced, but rsync only ever reported one completed transfer - so the reported
            // count is 1, not 3.
            assertTrue(result.message().contains("announced 3"), result.message());
            assertTrue(result.message().contains("completed 1 of them"), result.message());
            // Three announced, and rsync's own count claims one completed - both figures stay
            // visible. The copied figure counts only what this job can show arrived, and this
            // stand-in wrote nothing, so that figure is 0.
            assertEquals(3, result.transfersAnnounced(), result.message());
            assertEquals(1, result.transfersCompletedByRsync(), result.message());
            assertEquals(0, result.filesCopied(), result.message());

            String leftover = result.warnings().stream()
                    .filter(w -> w.contains("partial rsync file")).findFirst().orElse("");
            assertTrue(leftover.contains(".b.bin.ABCDEF"), String.valueOf(result.warnings()));
            assertTrue(leftover.contains("for b.bin"), leftover);
            assertTrue(leftover.contains("delete them or re-run"), leftover);        }

    }

    @Test
    void progressWithNoCompletedTransferMeansNothingWasCopied() throws Exception {
        TestSupport.write(source.resolve("big.bin"), "payload\n");

        // Measured against real rsync 3.1.3 through 3.4.0: while a transfer is in flight, its progress
        // fragments carry no xfr# field at all. A job killed there has announced a copy and finished
        // none of it, which is different from rsync saying nothing at all.
        Path fake = TestSupport.hangingFakeRsync(tools, String.join("\n",
                ">f+++++++++ big.bin",
                "  1,000,000  25%  300.00kB/s    0:00:09",
                "  2,000,000  50%  300.00kB/s    0:00:06"), ".big.bin.ABCDEF");

        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(fake.toString());
            engine.setJobTimeoutMillis(1_500L);
            FileSyncEngine.JobResult result = engine.execute(TestSupport.request(source, dest,
                    FileSyncEngine.JobType.COPY_AND_VERIFY,
                    TestSupport.selection("big.bin"), TestSupport.none()), new TestSupport.Recorder());

            assertEquals(FileSyncEngine.JobStatus.FAILED, result.status(), "a cancelled job did not complete");
        assertEquals(0, result.filesCopied(), result.message());
            assertTrue(result.message().contains("completed none of them"), result.message());
            assertFalse(Files.exists(dest.resolve("big.bin")), "the hidden partial file is not the finished one");        }

    }

    @Test
    void aDryRunCutShortStillReportsWhatItWouldHaveCopied() throws Exception {
        TestSupport.write(source.resolve("a.bin"), "a\n");
        TestSupport.write(source.resolve("b.bin"), "b\n");

        // A dry run's count is intent, not completed transfers: capping it by xfr# would report 0 files
        // "copied" for a run that was never meant to copy anything.
        Path fake = TestSupport.hangingFakeRsync(tools, String.join("\n",
                ">f+++++++++ a.bin",
                "     100%    0.00kB/s    0:00:00 (xfr#0, to-chk=0/2)",
                ">f+++++++++ b.bin"), null);

        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(fake.toString());
            engine.setJobTimeoutMillis(1_500L);
            FileSyncEngine.JobResult result = engine.execute(TestSupport.request(source, dest,
                    FileSyncEngine.JobType.COPY_AND_VERIFY,
                    TestSupport.selection("a.bin", "b.bin"), TestSupport.none(),
                    ScanOptions.DEFAULTS, false, true), new TestSupport.Recorder());

            assertEquals(FileSyncEngine.JobStatus.FAILED, result.status(), "a cancelled dry run did not complete");
            // A dry run cut short still names its intent: two files would have been copied, and
            // none were, because a dry run writes nothing.
            assertEquals(2, result.filesWouldCopy(), result.message());
            assertEquals(0, result.filesCopied(), result.message());
            assertTrue(result.message().contains("would have been copied"), result.message());        }

    }

    @Test
    void aCutShortCopyThatCleanedUpAfterItselfSaysSoInsteadOfWarning() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a\n");
        Path fake = TestSupport.hangingFakeRsync(tools,
                ">f+++++++++ a.txt\n          2   50%    1.00kB/s    0:00:01 (xfr#1, to-chk=0/1)", null);

        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(fake.toString());
            engine.setJobTimeoutMillis(1_500L);
            FileSyncEngine.JobResult result = engine.execute(TestSupport.request(source, dest,
                    FileSyncEngine.JobType.COPY_AND_VERIFY, TestSupport.selection("a.txt"),
                    TestSupport.none()), new TestSupport.Recorder());

            assertEquals(FileSyncEngine.JobStatus.FAILED, result.status());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("No partial rsync file")),
                    String.valueOf(result.warnings()));
            // rsync said it completed the transfer; the copied figure stays at 0 because the
            // stand-in printed output without writing the file, and nothing arrived.
            assertEquals(1, result.transfersCompletedByRsync(), result.message());
            assertEquals(0, result.filesCopied(), result.message());

        }
    }

    @Test
    void leftoverDetectionNamesRealLeftoversAndIgnoresEverythingElse() throws Exception {
        TestSupport.write(dest.resolve("b.bin"), "a complete file\n");
        TestSupport.write(dest.resolve(".b.bin.ABCDEF"), "partial");
        TestSupport.write(dest.resolve(".b.bin.SHORT77"), "7-character suffix: not an rsync temp name");
        TestSupport.write(dest.resolve(".someone.elses.XYZ123"), "a dotfile the user made");
        TestSupport.write(dest.resolve("sub/.c.txt.A1B2C3"), "nested partial");
        TestSupport.write(dest.resolve("sub/notes.md"), "# notes");

        List<String> found = FileSyncEngine.interruptedTempFiles(
                List.of(dest.resolve("b.bin"), dest.resolve("sub/c.txt")));

        assertEquals(2, found.size(), found.toString());
        assertTrue(found.stream().anyMatch(name -> name.contains(".b.bin.ABCDEF") && name.contains("for b.bin")),
                found.toString());
        assertTrue(found.stream().anyMatch(name -> name.contains(".c.txt.A1B2C3")), found.toString());
    }

    @Test
    void byteSizesAreReportedInUnitsAUserCanRead() {
        assertEquals("0 B", FileSyncEngine.humanBytes(0));
        assertEquals("512 B", FileSyncEngine.humanBytes(512));
        assertEquals("1.0 KB", FileSyncEngine.humanBytes(1024));
        assertEquals("1.5 MB", FileSyncEngine.humanBytes(1_572_864));
        assertEquals("2.0 GB", FileSyncEngine.humanBytes(2_147_483_648L));
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
        assertTrue(rec.awaitCompletion(2000));
    }
}
