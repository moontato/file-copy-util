package com.fileverifier;

import com.fileverifier.FileSyncEngine.JobResult;
import com.fileverifier.FileSyncEngine.JobStatus;
import com.fileverifier.FileSyncEngine.JobType;
import com.fileverifier.FileSyncEngine.ProgressListener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lifecycle contract: one engine owns at most one job, cancellation terminates rsync (and leaves no
 * orphan behind), and the watchdog enforces an optional job timeout.
 */
class FileSyncEngineLifecycleTest {

    /**
     * Big enough that rsync is provably still transferring when the job is killed, small enough that
     * the throttled tests write only a few megabytes to disk (the transfers are capped with --bwlimit).
     */
    private static final long BIG_FILE_BYTES = 300_000_000L;
    /** KB/s cap applied to the tests that kill a transfer mid-flight, to bound real writes. */
    private static final long THROTTLE_KBPS = 2_000L;

    @TempDir
    Path source;

    @TempDir
    Path dest;

    private static void assumeRsync() {
        assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
    }

    /**
     * Many tiny work units rather than big files: a job made of them cannot finish before a deadline or a
     * cancel lands, and empty files keep the fixture cheap on disk.
     */
    private void manyTinyFiles() throws IOException {
        for (int i = 0; i < 2_000; i++) {
            TestSupport.write(source.resolve("f" + i + ".bin"), "");
            TestSupport.write(dest.resolve("f" + i + ".bin"), "");
        }
    }

    private void smallFixture() throws Exception {
        TestSupport.write(source.resolve("one.txt"), "one\n");
        TestSupport.write(source.resolve("two.txt"), "two\n");
        TestSupport.write(dest.resolve("one.txt"), "one\n");
    }

    /** Sparse file: instant to create, but still costs rsync ~1s per GB to transfer. */
    private static void sparseFile(Path file, long bytes) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(bytes);
        }
    }

    private static long rsyncProcesses() {
        return ProcessHandle.allProcesses()
                .filter(handle -> handle.info().command()
                        .map(c -> c.equals("rsync") || c.endsWith("/rsync"))
                        .orElse(false))
                .count();
    }

    private static void awaitNoRsyncProcesses() throws InterruptedException {
        for (int i = 0; i < 100 && rsyncProcesses() > 0; i++) {
            Thread.sleep(100);
        }
        assertEquals(0, rsyncProcesses(), "rsync processes outlived the job");
    }

    // ---------------------------------------------------------------- status machine

    @Test
    void statusGoesIdleRunningCompleted() throws Exception {
        smallFixture();
        TestSupport.Recorder rec = new TestSupport.Recorder();

        try (FileSyncEngine engine = new FileSyncEngine()) {
            assertEquals(JobStatus.IDLE, engine.status());
            assertFalse(engine.isRunning());
            assertNull(engine.lastResult());

            assertTrue(engine.start(TestSupport.request(source, dest, JobType.VERIFY_ONLY,
                    TestSupport.selection("one.txt", "two.txt"), TestSupport.selection("one.txt")), rec));
            assertTrue(engine.isRunning(), "start() must make the job observable as RUNNING immediately");

            assertTrue(rec.awaitCompletion(30_000), "job never completed");
            assertFalse(engine.isRunning());
            assertEquals(JobStatus.COMPLETED, engine.status());
            assertNotNull(engine.lastResult());
            assertEquals(rec.result, engine.lastResult());
            assertEquals(1, rec.completions);
        }
    }

    @Test
    void secondStartIsRefusedWhileAJobIsRunning() throws Exception {
        smallFixture();

        // Block the engine's worker thread inside the first phase callback, so the job is
        // guaranteed to still be RUNNING when further jobs are attempted.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean blockedOnce = new AtomicBoolean(false);
        AtomicInteger completions = new AtomicInteger();

        ProgressListener blockingListener = new ProgressListener() {
            @Override
            public void onPhase(String message) {
                if (blockedOnce.compareAndSet(false, true)) {
                    entered.countDown();
                    try {
                        release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            @Override
            public void onFileProcessed(String relativePath, String kind, String sourceHash, String destHash) {
            }

            @Override
            public void onCompletion(JobResult result) {
                completions.incrementAndGet();
            }
        };

        try (FileSyncEngine engine = new FileSyncEngine()) {
            assertTrue(engine.start(TestSupport.request(source, dest, JobType.VERIFY_ONLY,
                    TestSupport.selection("one.txt", "two.txt"), TestSupport.selection("one.txt")),
                    blockingListener));
            assertTrue(entered.await(20, TimeUnit.SECONDS), "job never reached its first phase");
            assertTrue(engine.isRunning());

            assertFalse(engine.start(TestSupport.request(source, dest, JobType.VERIFY_ONLY,
                    TestSupport.selection("one.txt"), TestSupport.selection("one.txt")), blockingListener),
                    "a second job must be refused while one is running");
            assertFalse(engine.start(TestSupport.request(source, dest, JobType.COPY_AND_VERIFY,
                    TestSupport.selection("one.txt"), TestSupport.none()), blockingListener));

            assertThrows(IllegalStateException.class, () -> engine.execute(
                    TestSupport.request(source, dest, JobType.VERIFY_ONLY,
                            TestSupport.selection("one.txt"), TestSupport.selection("one.txt")),
                    blockingListener));

            release.countDown();
            long deadline = System.currentTimeMillis() + 30_000;
            while (engine.isRunning() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertFalse(engine.isRunning());
            assertEquals(1, completions.get(), "only the accepted job may report completion");
            assertEquals(JobStatus.COMPLETED, engine.status());
        }
    }

    @Test
    void engineIsReusableForSequentialJobs() throws Exception {
        smallFixture();
        try (FileSyncEngine engine = new FileSyncEngine()) {
            TestSupport.Recorder first = new TestSupport.Recorder();
            JobResult r1 = engine.execute(TestSupport.request(source, dest, JobType.VERIFY_ONLY,
                    TestSupport.selection("one.txt"), TestSupport.selection("one.txt")), first);
            assertTrue(r1.isSuccess(), r1.message());
            assertEquals(JobStatus.COMPLETED, engine.status());

            TestSupport.Recorder second = new TestSupport.Recorder();
            JobResult r2 = engine.execute(TestSupport.request(source, dest, JobType.COPY_AND_VERIFY,
                    TestSupport.selection("two.txt"), TestSupport.none()), second);
            assertTrue(r2.isSuccess(), r2.message());
            assertEquals(1, r2.filesCopied());
            assertEquals(JobStatus.COMPLETED, engine.status());
            assertEquals(r2, engine.lastResult());

            assertTrue(engine.millisSinceLastActivity() >= 0);
            assertTrue(engine.lastActivityDescription().contains("verified"),
                    engine.lastActivityDescription());
        }
    }

    @Test
    void closeRejectsFurtherJobs() throws Exception {
        smallFixture();
        FileSyncEngine engine = new FileSyncEngine();
        engine.close();
        assertFalse(engine.start(TestSupport.request(source, dest, JobType.VERIFY_ONLY,
                TestSupport.selection("one.txt"), TestSupport.selection("one.txt")),
                new TestSupport.Recorder()));
        assertEquals(JobStatus.IDLE, engine.status());
        engine.close(); // idempotent
    }

    @Test
    void cancelIsHarmlessWhenNoJobIsRunning() {
        try (FileSyncEngine engine = new FileSyncEngine()) {
            assertFalse(engine.cancel());
            assertEquals(JobStatus.IDLE, engine.status());
        }
    }

    // ---------------------------------------------------------------- cancellation

    @Test
    void cancelDuringTransferTerminatesRsyncAndReportsPartialWork() throws Exception {
        assumeRsync();
        // The big file sorts last, so cancelling on the first copied line leaves rsync with
        // ~1.5 GB of work outstanding: the kill cannot race the end of the job.
        TestSupport.write(source.resolve("a.txt"), "small\n");
        TestSupport.write(source.resolve("b.txt"), "small\n");
        sparseFile(source.resolve("zz-huge.bin"), BIG_FILE_BYTES);

        final FileSyncEngine[] holder = new FileSyncEngine[1];
        AtomicInteger copiedEvents = new AtomicInteger();

        TestSupport.Recorder rec = new TestSupport.Recorder() {
            @Override
            public void onFileProcessed(String relPath, String status, String srcHash, String dstHash) {
                super.onFileProcessed(relPath, status, srcHash, dstHash);
                if (status.equals("COPIED") && copiedEvents.incrementAndGet() == 1) {
                    holder[0].cancel();
                }
            }
        };

        try (FileSyncEngine engine = new FileSyncEngine()) {
            holder[0] = engine;
            engine.setBandwidthLimitKbps(THROTTLE_KBPS);
            assertTrue(engine.start(TestSupport.request(source, dest, JobType.COPY_AND_VERIFY,
                    TestSupport.selectAllOf(source), TestSupport.none()), rec));

            assertTrue(rec.awaitCompletion(60_000), "cancelled job never reported completion");
            JobResult result = rec.result;

            assertEquals(JobStatus.FAILED, result.status());
            assertTrue(result.message().contains("cancelled"), result.message());
            assertFalse(engine.isRunning(), "a cancelled job must release the engine");
            assertEquals(JobStatus.FAILED, engine.status());
            assertTrue(result.filesCopied() < 3,
                    "cancellation must stop the job, not let it finish: " + result.filesCopied());
            // rsync announces before it moves data, so a cut-short job reports completed transfers -
            // capped by rsync's own count when it reported one, and admitting uncertainty when it had not
            // reported a percentage yet at all.
            assertTrue(result.message().contains("announced")
                            && (result.message().contains("completed")
                                || result.message().contains("no progress")),
                    result.message());
            assertEquals(0, result.filesChecked(), "verification must not run after cancellation");
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("partial rsync file")),
                    "the engine must say whether rsync left partial files behind: " + result.warnings());
        }

        awaitNoRsyncProcesses();
    }

    // ---------------------------------------------------------------- timeout

    @Test
    void jobTimeoutTerminatesASlowRsync() throws Exception {
        assumeRsync();
        sparseFile(source.resolve("huge.bin"), BIG_FILE_BYTES);

        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.setJobTimeoutMillis(250);
            engine.setBandwidthLimitKbps(THROTTLE_KBPS);
            assertEquals(250, engine.jobTimeoutMillis());

            JobResult result = engine.execute(TestSupport.request(source, dest, JobType.COPY_AND_VERIFY,
                    TestSupport.selection("huge.bin"), TestSupport.none()), new TestSupport.Recorder());

            assertEquals(JobStatus.FAILED, result.status());
            assertTrue(result.message().contains("timeout"), result.message());
            assertTrue(result.message().contains("250 ms"), result.message());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("partial rsync file")),
                    result.warnings().toString());
            assertFalse(engine.isRunning());
            assertEquals(JobStatus.FAILED, engine.status());
        }
        awaitNoRsyncProcesses();
    }

    @Test
    void jobTimeoutAlsoAbortsTheVerificationPhase() throws Exception {
        // VERIFY_ONLY has no copy phase, so a deadline here can only be enforced by the verification
        // loop, and many tiny work units make sure the job cannot finish inside 1 ms. The mid-transfer
        // trip is covered by jobTimeoutTerminatesASlowRsync.
        manyTinyFiles();

        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.setJobTimeoutMillis(1);
            JobResult result = engine.execute(TestSupport.request(source, dest, JobType.VERIFY_ONLY,
                    TestSupport.selectAllOf(source), TestSupport.selectAllOf(dest)), new TestSupport.Recorder());

            assertEquals(JobStatus.FAILED, result.status());
            assertTrue(result.message().contains("timeout"), result.message());
            assertTrue(result.message().contains("1 ms"), result.message());
            assertTrue(result.filesChecked() < 2_000,
                    "the deadline must stop the verification loop: checked=" + result.filesChecked());
            assertEquals(result.filesChecked(),
                    result.filesMatched() + result.filesMismatched() + result.filesMissing());
            assertEquals(0, result.filesMissing(),
                    "a job cut short must not report files it never hashed as missing");
            assertFalse(engine.isRunning());
            assertEquals(JobStatus.FAILED, engine.status());
        }
    }

    @Test
    void cancellationAlsoStopsTheVerificationPhase() throws Exception {
        // Cancelling on the first verified file must stop the rest of the loop; only in-flight units can
        // still finish.
        manyTinyFiles();

        final FileSyncEngine[] holder = new FileSyncEngine[1];
        AtomicInteger verified = new AtomicInteger();
        TestSupport.Recorder rec = new TestSupport.Recorder() {
            @Override
            public void onFileProcessed(String relPath, String status, String srcHash, String dstHash) {
                super.onFileProcessed(relPath, status, srcHash, dstHash);
                if (verified.incrementAndGet() == 1) {
                    holder[0].cancel();
                }
            }
        };

        try (FileSyncEngine engine = new FileSyncEngine()) {
            holder[0] = engine;
            assertTrue(engine.start(TestSupport.request(source, dest, JobType.VERIFY_ONLY,
                    TestSupport.selectAllOf(source), TestSupport.selectAllOf(dest)), rec));
            assertTrue(rec.awaitCompletion(60_000), "cancelled job never reported completion");

            assertEquals(JobStatus.FAILED, rec.result.status());
            assertTrue(rec.result.message().contains("cancelled"), rec.result.message());
            assertTrue(rec.result.filesChecked() < 100,
                    "verification must stop after cancellation: " + rec.result.filesChecked());
        }
    }

    @Test
    void defaultIsNoTimeoutSoAJobFinishes() throws Exception {
        assumeRsync();
        sparseFile(source.resolve("large.bin"), BIG_FILE_BYTES);

        try (FileSyncEngine engine = new FileSyncEngine()) {
            assertEquals(FileSyncEngine.NO_TIMEOUT, engine.jobTimeoutMillis());
            JobResult result = engine.execute(TestSupport.request(source, dest, JobType.COPY_AND_VERIFY,
                    TestSupport.selection("large.bin"), TestSupport.none()), new TestSupport.Recorder());
            assertTrue(result.isSuccess(), result.message());
            assertEquals(1, result.filesCopied());
        }
    }
}
