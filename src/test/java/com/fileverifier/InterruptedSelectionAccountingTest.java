package com.fileverifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fileverifier.FileSyncEngine.FileOutcome;
import com.fileverifier.FileSyncEngine.JobResult;
import com.fileverifier.FileSyncEngine.JobStatus;
import com.fileverifier.FileSyncEngine.JobType;
import com.fileverifier.FileSyncEngine.ProgressListener;
import com.fileverifier.FileSyncEngine.TransferDisposition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A destination observation can get ahead of the thread consuming rsync's output. */
class InterruptedSelectionAccountingTest {
    @TempDir Path tmp;

    @Test
    void copyOnlyIncludesSelectedNamesWhoseAnnouncementsWereNotConsumed() throws Exception {
        exerciseDelayedReader(JobType.COPY_ONLY, 10);
    }

    @Test
    void verifyingCopyHasOnePendingRowPerSelectedNameWhenReaderIsDelayed() throws Exception {
        exerciseDelayedReader(JobType.COPY_AND_VERIFY, 10);
    }

    @Test
    void delayedReaderKeepsFullTalliesWhenInterruptedRowsAreCapped() throws Exception {
        exerciseDelayedReader(JobType.COPY_ONLY, 1);
    }

    @Test
    void interruptedDryRunDoesNotInventWouldCopyForUnannouncedNames() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a");
        TestSupport.write(src.resolve("b.txt"), "b");
        Path fake = TestSupport.hangingFakeRsync(Files.createDirectories(tmp.resolve("fake")),
                ">f+++++++++|a.txt", null);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(fake.toString());
            Thread watcher = new Thread(() -> {
                try {
                    if (entered.await(15, TimeUnit.SECONDS)) engine.cancel();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    released.countDown();
                }
            });
            watcher.setDaemon(true);
            watcher.start();
            JobResult result = engine.execute(TestSupport.request(src, dst, JobType.COPY_ONLY,
                    TestSupport.selection("a.txt", "b.txt"), TestSupport.selection(),
                    ScanOptions.DEFAULTS, false, true), new ProgressListener() {
                        @Override public void onFileProcessed(String rel, String kind, String sh, String dh) {
                            if ("TRANSFERRING".equals(kind)) {
                                entered.countDown();
                                try { released.await(20, TimeUnit.SECONDS); }
                                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                            }
                        }
                        @Override public void onCompletion(JobResult result) { }
                    });
            watcher.join(25_000);
            assertFalse(watcher.isAlive());
            assertEquals(JobStatus.FAILED, result.status(), result.toString());
            assertEquals(1, result.filesWouldCopy(), result.toString());
            assertEquals(1, result.filesUncertain(), result.toString());
            assertEquals(0, result.filesCopied(), result.toString());
            assertEquals(0, result.filesNotTransferred(), result.toString());
            Map<String, FileOutcome> rows = result.outcomes().stream().collect(
                    Collectors.toMap(FileOutcome::relPath, row -> row));
            assertEquals(TransferDisposition.WOULD_COPY, rows.get("a.txt").transfer());
            assertEquals(TransferDisposition.UNCERTAIN, rows.get("b.txt").transfer());
            assertEquals(0, result.rowsOmitted());
            assertFalse(Files.exists(dst.resolve("a.txt")));
            assertFalse(Files.exists(dst.resolve("b.txt")));
        }
    }

    @Test
    void failedCopyCountsSelectedNamesOnceAndRejectsUnexpectedAnnouncements() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a");
        TestSupport.write(src.resolve("b.txt"), "b");
        Path fake = TestSupport.failingFakeRsync(Files.createDirectories(tmp.resolve("fake")),
                ">f+++++++++|a.txt\n>f+++++++++|a.txt\n>f+++++++++|stranger.txt", 23, null);
        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(fake.toString());
            JobResult result = engine.execute(TestSupport.request(src, dst, JobType.COPY_ONLY,
                    TestSupport.selection("a.txt", "b.txt"), TestSupport.selection()), null);
            assertEquals(JobStatus.FAILED, result.status(), result.toString());
            assertEquals(2, result.filesUncertain(), result.toString());
            assertEquals(0, result.filesCopied(), result.toString());
            assertEquals(2, result.outcomes().size(), result.toString());
            assertEquals(0, result.rowsOmitted());
            assertEquals(java.util.Set.of("a.txt", "b.txt"), result.outcomes().stream()
                    .map(FileOutcome::relPath).collect(Collectors.toSet()));
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("stranger.txt")), result.toString());
        }
    }

    @Test
    void exit24KeepsVanishedProofAndAccountsForStillPresentUnannouncedNames() throws Exception {
        for (JobType type : List.of(JobType.COPY_ONLY, JobType.COPY_AND_VERIFY)) {
            Path base = Files.createDirectories(tmp.resolve(type.name()));
            Path src = Files.createDirectories(base.resolve("src"));
            Path dst = Files.createDirectories(base.resolve("dst"));
            TestSupport.write(src.resolve("a.txt"), "a");
            TestSupport.write(src.resolve("b.txt"), "b");
            TestSupport.write(src.resolve("c.txt"), "c");
            Path fake = TestSupport.vanishingRsync(Files.createDirectories(base.resolve("fake")),
                    "a.txt", "c.txt");
            try (FileSyncEngine engine = new FileSyncEngine()) {
                engine.useRsync(fake.toString());
                JobResult result = engine.execute(TestSupport.request(src, dst, type,
                        TestSupport.selection("a.txt", "b.txt", "c.txt"), TestSupport.selection()), null);
                assertEquals(JobStatus.COMPLETED, result.status(), result.toString());
                assertEquals(24, result.rsyncExitCode());
                assertEquals(List.of("c.txt"), result.vanishedInputs(), result.toString());
                assertEquals(1, result.filesCopied(), result.toString());
                assertEquals(1, result.filesUncertain(), result.toString());
                assertEquals(1, result.filesNotTransferred(), result.toString());
                assertEquals(0, result.rowsOmitted());
                Map<String, FileOutcome> rows = result.outcomes().stream().collect(
                        Collectors.toMap(FileOutcome::relPath, row -> row));
                assertEquals(3, rows.size());
                assertEquals(TransferDisposition.COPIED, rows.get("a.txt").transfer());
                assertEquals(TransferDisposition.UNCERTAIN, rows.get("b.txt").transfer());
                assertEquals(TransferDisposition.NOT_COPIED, rows.get("c.txt").transfer());
            }
        }
    }

    @Test
    void cancellationDuringVerificationKeepsOneRowPerSelectedNameEvenWithACap() throws Exception {
        assumeTrue(TestSupport.rsyncAvailable(), "requires real rsync");
        for (int limit : List.of(1, 3)) {
            Path base = Files.createDirectories(tmp.resolve("limit-" + limit));
            Path src = Files.createDirectories(base.resolve("src"));
            Path dst = Files.createDirectories(base.resolve("dst"));
            for (String name : List.of("a.txt", "b.txt", "c.txt")) TestSupport.write(src.resolve(name), name);
            CountDownLatch verdict = new CountDownLatch(1);
            CountDownLatch released = new CountDownLatch(1);
            try (FileSyncEngine engine = new FileSyncEngine()) {
                engine.setResultLimit(limit);
                Thread canceller = new Thread(() -> {
                    try {
                        if (verdict.await(15, TimeUnit.SECONDS)) engine.cancel();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        released.countDown();
                    }
                });
                canceller.setDaemon(true);
                canceller.start();
                JobResult result = engine.execute(TestSupport.request(src, dst, JobType.COPY_AND_VERIFY,
                        TestSupport.selection("a.txt", "b.txt", "c.txt"), TestSupport.selection()),
                        new ProgressListener() {
                            @Override public void onFileProcessed(String rel, String kind, String sh, String dh) {
                                if ("OK".equals(kind) && verdict.getCount() > 0) {
                                    verdict.countDown();
                                    try { released.await(20, TimeUnit.SECONDS); }
                                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                                }
                            }
                            @Override public void onCompletion(JobResult result) { }
                        });
                canceller.join(25_000);
                assertFalse(canceller.isAlive());
                assertEquals(JobStatus.FAILED, result.status(), result.toString());
                assertEquals(FileSyncEngine.AbortCause.USER_CANCELLED, result.abortCause(), result.toString());
                assertEquals(3, result.filesCopied(), result.toString());
                assertEquals(1, result.filesChecked(), result.toString());
                assertEquals(1, result.filesMatched(), result.toString());
                assertEquals(limit, result.outcomes().size(), result.toString());
                assertEquals(3 - limit, result.rowsOmitted(), result.toString());
                if (limit == 3) {
                    assertEquals(3, result.outcomes().stream().map(FileOutcome::relPath).distinct().count());
                    assertEquals(1, result.outcomes().stream().filter(o -> o.kind().equals("OK")).count());
                    assertEquals(2, result.outcomes().stream().filter(o -> o.kind().equals("PENDING")).count());
                    assertTrue(result.outcomes().stream().allMatch(o -> o.transfer() == TransferDisposition.COPIED));
                }
            }
        }
    }

    @Test
    void cancellationBeforeSpawnAccountsForKnownWorkSetAndCappedRows() throws Exception {
        assumeTrue(TestSupport.rsyncAvailable(), "requires real rsync");
        for (JobType type : List.of(JobType.COPY_ONLY, JobType.COPY_AND_VERIFY)) {
            for (boolean dryRun : List.of(false, true)) {
                Path base = Files.createDirectories(tmp.resolve(type.name() + "-" + dryRun));
                Path src = Files.createDirectories(base.resolve("src"));
                Path dst = Files.createDirectories(base.resolve("dst"));
                TestSupport.write(src.resolve("a.txt"), "a");
                TestSupport.write(src.resolve("b.txt"), "b");
                try (FileSyncEngine engine = new FileSyncEngine()) {
                    engine.setResultLimit(1);
                    JobResult result = engine.execute(TestSupport.request(src, dst, type,
                            TestSupport.selection("a.txt", "b.txt"), TestSupport.selection(),
                            ScanOptions.DEFAULTS, false, dryRun), new ProgressListener() {
                                @Override public void onPhase(String phase) {
                                    if ("copying".equals(phase) || "dry run".equals(phase)) engine.cancel();
                                }
                                @Override public void onCompletion(JobResult result) { }
                            });
                    assertEquals(JobStatus.FAILED, result.status(), result.toString());
                    assertEquals(FileSyncEngine.AbortCause.USER_CANCELLED, result.abortCause(), result.toString());
                    assertEquals(0, result.filesCopied(), result.toString());
                    assertEquals(0, result.filesWouldCopy(), result.toString());
                    assertEquals(0, result.filesUncertain(), result.toString());
                    assertEquals(2, result.filesNotTransferred(), result.toString());
                    assertEquals(0, result.transfersAnnounced(), result.toString());
                    assertEquals(1, result.outcomes().size(), result.toString());
                    assertEquals(1, result.rowsOmitted(), result.toString());
                    assertEquals(TransferDisposition.NOT_COPIED, result.outcomes().get(0).transfer());
                    assertEquals(type == JobType.COPY_AND_VERIFY ? "PENDING" : "NOT_COPIED",
                            result.outcomes().get(0).kind());
                    assertFalse(Files.exists(dst.resolve("a.txt")));
                    assertFalse(Files.exists(dst.resolve("b.txt")));
                }
            }
        }
    }

    @Test
    void cancellationDuringVerificationDoesNotCallAnUnannouncedNameNotCopied() throws Exception {
        assumeTrue(TestSupport.rsyncAvailable(), "requires real rsync");
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "same");
        TestSupport.write(src.resolve("b.txt"), "new");
        Files.copy(src.resolve("a.txt"), dst.resolve("a.txt"), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        CountDownLatch verdict = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        try (FileSyncEngine engine = new FileSyncEngine()) {
            Thread canceller = new Thread(() -> {
                try { if (verdict.await(15, TimeUnit.SECONDS)) engine.cancel(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { released.countDown(); }
            });
            canceller.setDaemon(true);
            canceller.start();
            JobResult result = engine.execute(TestSupport.request(src, dst, JobType.COPY_AND_VERIFY,
                    TestSupport.selection("a.txt", "b.txt"), TestSupport.selection()), new ProgressListener() {
                        @Override public void onFileProcessed(String rel, String kind, String sh, String dh) {
                            if ("OK".equals(kind) && verdict.getCount() > 0) {
                                verdict.countDown();
                                try { released.await(20, TimeUnit.SECONDS); }
                                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                            }
                        }
                        @Override public void onCompletion(JobResult result) { }
                    });
            canceller.join(25_000);
            assertFalse(canceller.isAlive());
            assertEquals(JobStatus.FAILED, result.status(), result.toString());
            assertEquals(1, result.transfersAnnounced(), result.toString());
            assertEquals(1, result.filesCopied(), result.toString());
            assertEquals(1, result.filesUncertain(), result.toString());
            assertEquals(0, result.filesNotTransferred(), result.toString());
            Map<String, FileOutcome> rows = result.outcomes().stream().collect(
                    Collectors.toMap(FileOutcome::relPath, row -> row));
            assertEquals(2, rows.size());
            assertEquals(TransferDisposition.UNCERTAIN, rows.get("a.txt").transfer());
            assertEquals(TransferDisposition.COPIED, rows.get("b.txt").transfer());
            assertEquals(1, result.filesChecked());
        }
    }

    private void exerciseDelayedReader(JobType type, int limit) throws Exception {
        assumeTrue(TestSupport.rsyncAvailable(), "requires real rsync");
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        fill(src.resolve("a-small.txt"), 'N', 8_000);
        Files.createSymbolicLink(src.resolve("a-link.bin"), Path.of("a-small.txt"));
        fill(src.resolve("b-overwrite.bin"), 'N', 6_000_000);
        fill(dst.resolve("b-overwrite.bin"), 'O', 6_000_000);
        String before = FileHasher.computeHash(dst.resolve("b-overwrite.bin"));
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicReference<Throwable> watcherError = new AtomicReference<>();
        AtomicReference<String> first = new AtomicReference<>();
        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.setBandwidthLimitKbps(300);
            engine.setResultLimit(limit);
            Thread watcher = new Thread(() -> {
                try {
                    if (!callbackEntered.await(15, TimeUnit.SECONDS)) {
                        throw new AssertionError("rsync never announced the first file");
                    }
                    if (!TestSupport.waitFor(() -> hasPartial(dst), 15_000)) {
                        throw new AssertionError("rsync never began the overwrite");
                    }
                    if (!Files.isSymbolicLink(dst.resolve("a-link.bin"))
                            || !Files.isRegularFile(dst.resolve("a-small.txt"))) {
                        throw new AssertionError("earlier files not present before cancellation");
                    }
                    engine.cancel();
                } catch (Throwable error) {
                    watcherError.set(error);
                } finally {
                    releaseCallback.countDown();
                }
            }, "delayed-reader-canceller");
            watcher.setDaemon(true);
            watcher.start();
            ProgressListener listener = new ProgressListener() {
                @Override public void onFileProcessed(String rel, String kind, String sh, String dh) {
                    if ("TRANSFERRING".equals(kind) && first.compareAndSet(null, rel)) {
                        callbackEntered.countDown();
                        try {
                            if (!releaseCallback.await(20, TimeUnit.SECONDS)) {
                                throw new AssertionError("canceller did not release the reader");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                    }
                }
                @Override public void onCompletion(JobResult result) { }
            };
            JobResult result;
            try {
                result = engine.execute(TestSupport.request(src, dst, type,
                        TestSupport.selection("a-link.bin", "a-small.txt", "b-overwrite.bin"),
                        TestSupport.selection(), new ScanOptions(ScanOptions.DEFAULTS.ignorePatterns(), true,
                                ScanOptions.SymlinkPolicy.LINK), false), listener);
            } finally {
                releaseCallback.countDown();
                watcher.join(25_000);
            }
            assertFalse(watcher.isAlive(), "canceller never returned");
            assertNull(watcherError.get(), String.valueOf(watcherError.get()));
            assertEquals("a-link.bin", first.get());
            assertEquals(JobStatus.FAILED, result.status(), result.toString());
            assertEquals(FileSyncEngine.AbortCause.USER_CANCELLED, result.abortCause(), result.toString());
            assertEquals(1, result.transfersAnnounced(), result.toString());
            assertEquals(1, result.filesCopied(), result.toString());
            assertEquals(2, result.filesUncertain(), result.toString());
            assertEquals(0, result.filesNotTransferred(), result.toString());
            assertEquals(0, result.filesWouldCopy(), result.toString());
            assertEquals(0, result.filesChecked(), result.toString());
            assertEquals(Math.min(limit, 3), result.outcomes().size(), result.toString());
            assertEquals(Math.max(0, 3 - limit), result.rowsOmitted(), result.toString());
            assertEquals(result.rowsOmitted() > 0, result.truncated(), result.toString());
            assertEquals(before, FileHasher.computeHash(dst.resolve("b-overwrite.bin")));
            assertTrue(Files.isSymbolicLink(dst.resolve("a-link.bin")));
            assertTrue(Files.isRegularFile(dst.resolve("a-small.txt")));
            if (limit >= 3) {
                Map<String, FileOutcome> rows = result.outcomes().stream().collect(
                        Collectors.toMap(FileOutcome::relPath, row -> row));
                assertEquals(3, rows.size(), result.toString());
                assertEquals(TransferDisposition.COPIED, rows.get("a-link.bin").transfer());
                assertEquals(TransferDisposition.UNCERTAIN, rows.get("a-small.txt").transfer(),
                        "a missing announcement is not proof, even if the file exists");
                assertEquals(TransferDisposition.UNCERTAIN, rows.get("b-overwrite.bin").transfer());
                for (FileOutcome row : rows.values()) {
                    assertEquals(type == JobType.COPY_AND_VERIFY ? "PENDING" : row.transfer().name(), row.kind());
                }
            }
        }
    }

    private static void fill(Path file, char marker, int size) throws Exception {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) marker);
        Files.write(file, bytes);
    }

    private static boolean hasPartial(Path dst) {
        try (var paths = Files.newDirectoryStream(dst, ".b-overwrite.bin.*")) {
            return paths.iterator().hasNext();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
