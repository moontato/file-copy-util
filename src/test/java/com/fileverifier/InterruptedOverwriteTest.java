package com.fileverifier;

import com.fileverifier.FileSyncEngine.FileOutcome;
import com.fileverifier.FileSyncEngine.JobRequest;
import com.fileverifier.FileSyncEngine.JobResult;
import com.fileverifier.FileSyncEngine.JobStatus;
import com.fileverifier.FileSyncEngine.JobType;
import com.fileverifier.FileSyncEngine.ProgressListener;
import com.fileverifier.FileSyncEngine.Selection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The interrupted-overwrite fixture from the audit review, kept as an executable test
 * (plan/2026-10-21_audit-remediation.md section 2.5).
 *
 * <p>The point: a destination file that already exists with the <em>same size</em> as the source but
 * different content, announced by rsync and then interrupted mid-transfer. Existence, size, object kind
 * and containment all look satisfied afterwards, so they cannot prove the transfer completed. The job
 * must not report that file as copied, and the file must not have been truncated in the attempt.
 *
 * <p>The disposition assertions fail until the completion-evidence work lands. That is intentional.
 */
class InterruptedOverwriteTest {

    /** The job's reporting is not what this test is about; the destination's state is. */
    private static final ProgressListener SILENT = new ProgressListener() {
        @Override
        public void onCompletion(FileSyncEngine.JobResult completion) {
        }
    };


    private static final int SIZE = 6_000_000;
    private static final long THROTTLE_KBPS = 300L;

    @Test
    void anInterruptedOverwriteOfAnIdenticallySizedFileIsNeverReportedAsCopied(@TempDir Path tmp)
        throws Exception {
        assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));

        // Sorted order puts the small file first: it has time to finish, so it becomes the
        // attribution baseline inside the very same job.
        fill(src.resolve("a-small.bin"), 'N', 8_000);
        fill(src.resolve("b-overwrite.bin"), 'N', SIZE);
        fill(dst.resolve("b-overwrite.bin"), 'O', SIZE);       // same size, different content
        TestSupport.write(dst.resolve("z-untouched.bin"), "not selected, must stay as it is\n");

        String overwrittenBefore = FileHasher.computeHash(dst.resolve("b-overwrite.bin"));
        String untouchedBefore = FileHasher.computeHash(dst.resolve("z-untouched.bin"));

        // The interruption is triggered from what the destination shows, not from what this program
        // parsed out of rsync's output. rsync writes each file as a hidden temporary in its destination
        // folder and renames it when that transfer completes, so a temporary for b-overwrite.bin means
        // that transfer is in flight. Tying the trigger to a per-file output line would make this test
        // depend on which output format the engine happens to ask for.
        AtomicReference<FileSyncEngine> engineRef = new AtomicReference<>();
        AtomicBoolean sawInFlight = new AtomicBoolean();
        Thread watcher = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                if (hiddenTemporaryFor(dst, "b-overwrite.bin") != null) {
                    sawInFlight.set(true);
                    engineRef.get().cancel();
                    return;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "interrupt-the-overwrite");
        watcher.setDaemon(true);

        Selection selected = TestSupport.selection("a-small.bin", "b-overwrite.bin");
        JobRequest request = TestSupport.request(src, dst, JobType.COPY_ONLY, selected,
            Selection.none());

        JobResult result;
        try (FileSyncEngine engine = new FileSyncEngine()) {
            engineRef.set(engine);
            engine.setBandwidthLimitKbps(THROTTLE_KBPS);
            watcher.start();
            result = engine.execute(request, SILENT);
        }

        // ---------------------------------------------------------------- what the disk says
        assertTrue(sawInFlight.get(), "the job never reached the file it was meant to interrupt: "
            + result.message());

        String overwrittenAfter = FileHasher.computeHash(dst.resolve("b-overwrite.bin"));
        assertEquals(overwrittenBefore, overwrittenAfter,
            "an interrupted replacement must not leave the destination truncated or partially"
                + " overwritten: rsync writes a hidden temporary file and renames on completion");
        assertEquals(SIZE, Files.size(dst.resolve("b-overwrite.bin")),
            "the surviving file keeps its size (which is exactly why size proves nothing here)");
        assertEquals(untouchedBefore, FileHasher.computeHash(dst.resolve("z-untouched.bin")),
            "an unrelated destination file is left alone");
        assertTrue(Files.exists(dst.resolve("a-small.bin")),
            "the file that finished before the interruption is present: "
                + TestSupport.relPathsUnder(dst));
        assertEquals("N", firstCharacter(dst.resolve("a-small.bin")),
            "a replacement that completed may hold the new data");

        // ---------------------------------------------------------------- what the report says
        assertEquals(JobStatus.FAILED, result.status());
        assertTrue(result.message().contains("cancel"), result.message());

        assertTrue(result.filesCopied() <= 1,
            "the copied count must not include the file whose transfer was interrupted: "
                + result.filesCopied() + " " + result.message());

        List<FileOutcome> outcomes = result.outcomes();
        assertTrue(outcomes.stream().noneMatch(o ->
                        "b-overwrite.bin".equals(o.relPath()) && "COPIED".equals(o.kind())),
            "the interrupted file must never be reported as copied: " + kinds(outcomes));
        assertTrue(outcomes.stream().anyMatch(o ->
                        "a-small.bin".equals(o.relPath()) && "COPIED".equals(o.kind())),
            "section 2.5: a cancelled copy job still has to name the file it did finish, so the report"
                + " is not just a count; no row for it today: " + kinds(outcomes));
        assertTrue(outcomes.stream().anyMatch(o ->
                        "b-overwrite.bin".equals(o.relPath()) && "UNCERTAIN".equals(o.kind())),
            "section 2.3/2.5: an announced file with no completion evidence gets the honest"
                + " disposition, not silence: " + kinds(outcomes));

        // Any partial file rsync left behind has to be named, not hidden.
        List<String> leftovers = TestSupport.relPathsUnder(dst).stream()
            .filter(name -> name.contains("b-overwrite.bin."))
            .toList();
        for (String leftover : leftovers) {
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains(leftover)),
                "a partial file left in the destination must be reported: " + leftovers
                    + " warnings=" + result.warnings());
        }

        TestSupport.assertNoProcessesRunning("rsync", "after an interrupted overwrite");
    }

    /**
     * The same interruption in a job that would have verified. It was stopped before the comparison, so
     * its rows have to carry a transfer answer and a verdict that says the check never ran: an empty
     * Results tab under a "1 copied" summary is what the audit called A10.
     */
    @Test
    void aVerifyingJobStoppedMidTransferReportsTheTransferAndASaidSoVerdict(@TempDir Path tmp)
            throws Exception {
        assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        fill(src.resolve("a-small.bin"), 'N', 8_000);
        fill(src.resolve("b-overwrite.bin"), 'N', SIZE);
        fill(dst.resolve("b-overwrite.bin"), 'O', SIZE);

        AtomicReference<FileSyncEngine> engineRef = new AtomicReference<>();
        AtomicBoolean sawInFlight = new AtomicBoolean();
        Thread watcher = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                if (hiddenTemporaryFor(dst, "b-overwrite.bin") != null) {
                    sawInFlight.set(true);
                    engineRef.get().cancel();
                    return;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "interrupt-the-verifying-copy");
        watcher.setDaemon(true);

        JobResult result;
        try (FileSyncEngine engine = new FileSyncEngine()) {
            engineRef.set(engine);
            engine.setBandwidthLimitKbps(THROTTLE_KBPS);
            watcher.start();
            result = engine.execute(TestSupport.request(src, dst, JobType.COPY_AND_VERIFY,
                TestSupport.selection("a-small.bin", "b-overwrite.bin"), Selection.none()), SILENT);
        }

        assertTrue(sawInFlight.get(), "the job never reached the file it was meant to interrupt: "
            + result.message());
        assertEquals(JobStatus.FAILED, result.status(), result.message());
        List<String> rows = kinds(result.outcomes());
        assertTrue(rows.contains("PENDING:b-overwrite.bin"),
            "the file whose transfer was interrupted is named, with a verdict that admits the check did"
                + " not happen: " + rows);
        assertTrue(result.outcomes().stream()
                .anyMatch(o -> o.relPath().equals("b-overwrite.bin")
                        && o.transfer() == FileSyncEngine.TransferDisposition.UNCERTAIN),
            "and its transfer is called uncertain rather than copied: " + rows);
    }

    /**
     * A completed symlink in the same interrupted job.
     *
     * <p>A symlink is the case where "expected size from the source" is meaningless: the size of a link is
     * the size of its target path string on some systems and its own inode's on others, and it is not what
     * says the copy worked. What says it worked is that the destination did not have that name before, and
     * has a link with the same target text now. This fixture also proves the ledger does not turn a link
     * into uncertain merely because a later file in the same job was interrupted.
     */
    @Test
    void aCompletedSymlinkInAnInterruptedJobIsCopiedNotUncertain(@TempDir Path tmp) throws Exception {
        assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        fill(src.resolve("a-small.txt"), 'N', 8_000);
        Files.createSymbolicLink(src.resolve("a-link.bin"), Path.of("a-small.txt"));   // relative target
        fill(src.resolve("b-overwrite.bin"), 'N', SIZE);
        fill(dst.resolve("b-overwrite.bin"), 'O', SIZE);

        AtomicReference<FileSyncEngine> engineRef = new AtomicReference<>();
        AtomicBoolean sawInFlight = new AtomicBoolean();
        Thread watcher = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                if (hiddenTemporaryFor(dst, "b-overwrite.bin") != null) {
                    sawInFlight.set(true);
                    engineRef.get().cancel();
                    return;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "interrupt-after-the-link");
        watcher.setDaemon(true);

        JobResult result;
        try (FileSyncEngine engine = new FileSyncEngine()) {
            engineRef.set(engine);
            engine.setBandwidthLimitKbps(THROTTLE_KBPS);
            watcher.start();
            result = engine.execute(TestSupport.request(src, dst, JobType.COPY_ONLY,
                TestSupport.selection("a-small.txt", "a-link.bin", "b-overwrite.bin"), Selection.none(),
                new ScanOptions(ScanOptions.DEFAULTS.ignorePatterns(), true,
                    ScanOptions.SymlinkPolicy.LINK), false), SILENT);
        }

        assertTrue(sawInFlight.get(), result.message());
        assertEquals(JobStatus.FAILED, result.status(), result.message());
        List<String> rows = kinds(result.outcomes());
        assertTrue(rows.contains("COPIED:a-link.bin"),
            "the link was created before the interruption and its destination is a link with the same"
                + " target text: " + rows);
        assertTrue(rows.contains("COPIED:a-small.txt"), "the small file finished first: " + rows);
        assertTrue(rows.contains("UNCERTAIN:b-overwrite.bin"),
            "while the interrupted same-size overwrite stays uncertain: " + rows);
        assertTrue(Files.isSymbolicLink(dst.resolve("a-link.bin")),
            "and it really is a link at the destination, not a copy of the target's contents");
        assertEquals("a-small.txt", Files.readSymbolicLink(dst.resolve("a-link.bin")).toString(),
            "the link text is what it was in the source");
    }

    private static void fill(Path file, char marker, int bytes) throws IOException {
        Files.createDirectories(file.getParent());
        try (var channel = java.nio.channels.FileChannel.open(file,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(64 * 1024);
            byte[] pattern = new byte[64 * 1024];
            java.util.Arrays.fill(pattern, (byte) marker);
            int written = 0;
            while (written < bytes) {
                buffer.clear();
                buffer.put(pattern, 0, Math.min(pattern.length, bytes - written));
                buffer.flip();
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                written += Math.min(pattern.length, bytes - written);
            }
        }
    }

    /**
     * The hidden temporary file rsync is writing for {@code name}, or null when there is none. Measured on
     * rsync 3.1.3 and 3.4.0: the name is the destination name with a dot in front and a short random
     * suffix behind it - {@code .big.bin.Ugp1sn}. It exists only while that transfer is in flight, and it
     * disappears when the transfer completes or is killed, so it is a fact about the destination rather
     * than about this program's reading of rsync's output.
     */
    /**
     * The snapshot itself is cancellable, and says so when it was cut short.
     *
     * <p>Recording the before-state costs one metadata call per selected file. On a slow mount that can be
     * the longest thing a job does before rsync even starts, so a Cancel pressed during it must stop the
     * job rather than run to the end of the list - and the names it never reached must not be reported as
     * if their transfer had been checked.
     */
    @Test
    void aSnapshotCutShortByCancellationNamesItselfAndConfirmsNothingForTheRest(@TempDir Path tmp)
            throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        List<FileEntry> workSet = new java.util.ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            String name = String.format("f%04d.bin", i);
            TestSupport.write(src.resolve(name), "x");
            workSet.add(new FileEntry(name, src.resolve(name)));
        }

        List<String> warnings = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        TransferLedger ledger = new TransferLedger(src, dst, workSet, warnings,
                () -> checks.incrementAndGet() > 2);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("stopped early")), warnings.toString());

        // rsync "completed" nothing here: the job was cut short, so only the before-and-after comparison
        // can say anything, and it can only say something about the names it recorded.
        String early = workSet.get(0).relPath();
        String late = workSet.get(workSet.size() - 1).relPath();
        // Nothing was copied, so neither is COPIED; the point is that the unrecorded one can never become
        // COPIED by any evidence, and the recorded one is at least a name the engine knows the state of.
        assertEquals(FileSyncEngine.TransferDisposition.UNCERTAIN, ledger.disposition(late, false, false),
            "the file the snapshot never reached cannot be confirmed");
        assertEquals(FileSyncEngine.TransferDisposition.UNCERTAIN, ledger.disposition(early, false, false),
            "a file that was not written is not copied, recorded or not");
    }

    /** A completed creation in a job that was otherwise cut short is provable, and is claimed. */
    @Test
    void aFileCreatedBeforeTheInterruptionIsConfirmedByTheSnapshot(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("made.txt"), "12345");
        TestSupport.write(src.resolve("untouched.txt"), "9");
        List<FileEntry> workSet = List.of(
            new FileEntry("made.txt", src.resolve("made.txt")),
            new FileEntry("untouched.txt", src.resolve("untouched.txt")));

        List<String> warnings = new java.util.ArrayList<>();
        TransferLedger ledger = new TransferLedger(src, dst, workSet, warnings, () -> false);
        // Pretend the first file arrived and the second did not.
        TestSupport.write(dst.resolve("made.txt"), "12345");

        assertEquals(FileSyncEngine.TransferDisposition.COPIED, ledger.disposition("made.txt", false, false),
            "absent before, present after at the expected kind and size: this job created it");
        assertEquals(FileSyncEngine.TransferDisposition.UNCERTAIN, ledger.disposition("untouched.txt",
                false, false), "rsync announced it, nothing is there, so nothing may be claimed");
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    private static String hiddenTemporaryFor(Path directory, String name) {
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path sibling : entries) {
                String fileName = sibling.getFileName().toString();
                if (fileName.startsWith("." + name + ".") && fileName.length() > name.length() + 2) {
                    return fileName;
                }
            }
        } catch (java.io.IOException problem) {
            return null;
        }
        return null;
    }

    private static String firstCharacter(Path file) throws IOException, NoSuchAlgorithmException {
        try (var stream = Files.newInputStream(file)) {
            return String.valueOf((char) stream.read());
        }
    }

    private static List<String> kinds(List<FileOutcome> outcomes) {
        return outcomes.stream().map(o -> o.kind() + ":" + o.relPath()).toList();
    }
}
