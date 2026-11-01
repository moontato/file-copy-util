package com.fileverifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fileverifier.FileSyncEngine.JobRequest;
import com.fileverifier.FileSyncEngine.JobResult;
import com.fileverifier.FileSyncEngine.JobType;
import com.fileverifier.FileSyncEngine.ProgressListener;
import com.fileverifier.FileSyncEngine.Selection;
import com.fileverifier.FileSyncEngine.VerificationBasis;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What a job holds on to after a read has finished.
 *
 * <p>The queue figures say how much reading is waiting. They say nothing about the other half of the cost: the
 * tasks a job has already taken delivery of, which stay in the cancellation registry because the registry only
 * ever grew. A task is the handle on its result - a digest, a pair of side facts, a whole verdict - so a
 * registry that never lets go of one is a job carrying a finding per file it reported hours ago, through every
 * later phase, until the job itself is gone.
 *
 * <p>These tests measure the registry itself: what it holds while the job works (sampled from a progress
 * callback, on the job thread, where it is exact), what it held at its busiest, and what it still holds when
 * the job is over. The last figure is the whole point of the change: it is zero.
 *
 * <p>The other half of the claim is measured too. Retirement must not throw away the handle on work that is
 * still outstanding, because that handle is the only way a cancel reaches a queued or running read; a job that
 * was stopped mid-tree still has its outstanding reads registered at the moment it was stopped.
 */
class RetainedHashTaskTest {

    @TempDir
    Path tmp;

    private static void assumeRealRsync() {
        assumeTrue(TestSupport.rsyncAvailable(), "needs a real rsync");
    }

    /** A tree with <i>n</i> different files, so every file is a task and no two share a digest. */
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

    private static JobRequest job(Path src, Path dst, JobType type, VerificationBasis basis, Selection selected) {
        return new JobRequest(src, dst, type, selected, Selection.none(), ScanOptions.DEFAULTS, false, false, basis);
    }

    /** Records what the cancellation registry held at each file, on the job thread, where the count is exact. */
    private static final class RegistrySampler implements ProgressListener {
        private final java.util.function.IntSupplier liveCount;
        private final List<Integer> heldAtEachFile = new ArrayList<>();

        private RegistrySampler(FileSyncEngine engine) {
            this.liveCount = engine::retainedHashTasks;
        }

        @Override
        public void onFileProcessed(String rel, String kind, String sourceHash, String destHash) {
            heldAtEachFile.add(liveCount.getAsInt());
        }

        @Override
        public void onCompletion(JobResult result) {
        }
    }

    private static RegistrySampler samplerOf(FileSyncEngine engine) {
        return new RegistrySampler(engine);
    }

    // ---------------------------------------------------------------- a finished job holds nothing

    @Test
    void aCompletedVerificationLeavesNoHashTaskRegistered() throws Exception {
        int files = 400;
        Path src = tree(tmp.resolve("verify-src"), files);
        Path dst = tree(tmp.resolve("verify-dst"), files);

        try (FileSyncEngine engine = new FileSyncEngine()) {
            JobResult result = engine.execute(job(src, dst, JobType.VERIFY_ONLY,
                    VerificationBasis.SOURCE_BEFORE_COPY, TestSupport.selectAllOf(src)), samplerOf(engine));
            assertEquals(files, result.filesChecked(), result.message());
            // This loop queues every file it has work for, which is what it has always done, and the figure is
            // recorded rather than hidden: the bound is on what a finished file still costs, not on this queue.
            assertEquals(files, engine.peakRetainedHashTasks(),
                    "the tasks registered at the busiest point are the files this loop queued at once");
            assertEquals(0, engine.retainedHashTasks(),
                    "a file that has been reported costs nothing more: the task that read it, and the verdict"
                            + " that came out of it, are no longer held anywhere");
        }
    }

    @Test
    void thePostCopyLoopHoldsNoMoreThanItsWindowAndHoldsNothingAfterwards() throws Exception {
        assumeRealRsync();
        int files = 300;
        Path src = tree(tmp.resolve("postcopy-src"), files);
        Path dst = tree(tmp.resolve("postcopy-dst"), files);
        Path fake = idleRsync(tmp.resolve("postcopy-tools"));

        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(fake.toString());
            RegistrySampler sampler = samplerOf(engine);
            JobResult result = engine.execute(job(src, dst, JobType.COPY_AND_VERIFY,
                    VerificationBasis.SOURCE_AFTER_COPY, TestSupport.selectAllOf(src)), sampler);
            assertEquals(files, result.filesMatched(), result.message());
            int ceiling = 2 * FileSyncEngine.VERIFY_WINDOW_FILES;
            assertTrue(engine.peakRetainedHashTasks() <= ceiling,
                    "the registry of a 300-file job stops growing at the window: saw "
                            + engine.peakRetainedHashTasks() + " of at most " + ceiling);
            assertEquals(ceiling, engine.peakRetainedHashTasks(),
                    "the ceiling is reached and not merely respected - the loop really does keep the window full");
            assertTrue(sampler.heldAtEachFile.size() == files);
            int[] sample = sampler.heldAtEachFile.stream().mapToInt(Integer::intValue).toArray();
            int largest = 0;
            for (int held : sample) {
                largest = Math.max(largest, held);
            }
            assertTrue(largest <= ceiling, "every single file was reported with at most a window's worth of"
                    + " tasks still registered: saw " + largest);
            assertTrue(sample[0] > 0, "a file reported mid-job is reported while the rest of its window is"
                    + " still registered - retirement drops reads the job has taken delivery of, not reads it"
                    + " is still waiting for: " + sample[0]);
            assertEquals(0, sample[sample.length - 1], "the last row of the job is written with nothing left"
                    + " in the registry");
            assertEquals(0, engine.retainedHashTasks(), result.message());
        }
    }

    /**
     * The accumulation this change removes was across phases: a Copy & Verify under the default basis reads
     * every source file before the transfer and then compares every file after it, and the registry used to
     * hold the tasks of both passes for the rest of the job.
     */
    @Test
    void aCopyDoesNotCarryItsDigestPassIntoItsVerificationPass() throws Exception {
        assumeRealRsync();
        int files = 300;
        Path src = tree(tmp.resolve("phases-src"), files);
        Path dst = tree(tmp.resolve("phases-dst"), files);
        Path fake = idleRsync(tmp.resolve("phases-tools"));

        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(fake.toString());
            JobResult result = engine.execute(job(src, dst, JobType.COPY_AND_VERIFY,
                    VerificationBasis.SOURCE_BEFORE_COPY, TestSupport.selectAllOf(src)), samplerOf(engine));
            assertEquals(files, result.filesMatched(), result.message());
            assertEquals(files, engine.peakRetainedHashTasks(),
                    "two passes over the same 300 files register 300 tasks at a time, not 300 plus 300: the"
                            + " digest pass lets go of each file it has taken delivery of before the next pass"
                            + " starts queuing its own");
            assertEquals(0, engine.retainedHashTasks(), result.message());
        }
    }

    @Test
    void aSecondJobOnTheSameEngineIsNotMeasuredAgainstTheFirstJob() throws Exception {
        int files = 120;
        Path src = tree(tmp.resolve("twice-src"), files);
        Path dst = tree(tmp.resolve("twice-dst"), files);

        try (FileSyncEngine engine = new FileSyncEngine()) {
            JobResult first = engine.execute(job(src, dst, JobType.VERIFY_ONLY,
                    VerificationBasis.SOURCE_BEFORE_COPY, TestSupport.selectAllOf(src)), null);
            assertEquals(files, first.filesChecked(), first.message());
            assertEquals(0, engine.retainedHashTasks(), first.message());

            JobResult second = engine.execute(job(src, dst, JobType.VERIFY_ONLY,
                    VerificationBasis.SOURCE_BEFORE_COPY, TestSupport.selectAllOf(src)), null);
            assertEquals(files, second.filesChecked(), second.message());
            assertEquals(files, engine.peakRetainedHashTasks(),
                    "the figure belongs to the job it was recorded for, not to the engine that ran both");
            assertEquals(0, engine.retainedHashTasks(), second.message());
        }
    }

    // ---------------------------------------------------------------- what is still owed stays registered

    @Test
    void aStoppedJobStillHoldsTheHandleOnEveryReadItHadNotTakenDeliveryOf() throws Exception {
        assumeRealRsync();
        int files = 400;
        Path src = tree(tmp.resolve("stop-src"), files);
        Path dst = tree(tmp.resolve("stop-dst"), files);
        Path fake = idleRsync(tmp.resolve("stop-tools"));
        java.util.concurrent.CountDownLatch firstRow = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<JobResult> outcome = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger heldAtTheMomentOfStopping = new java.util.concurrent.atomic.AtomicInteger(-1);

        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(fake.toString());
            Thread canceller = new Thread(() -> {
                try {
                    if (firstRow.await(20, java.util.concurrent.TimeUnit.SECONDS)) {
                        engine.cancel();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    released.countDown();
                }
            }, "registry-retention-canceller");
            canceller.setDaemon(true);
            canceller.start();
            outcome.set(engine.execute(job(src, dst, JobType.COPY_AND_VERIFY,
                    VerificationBasis.SOURCE_AFTER_COPY, TestSupport.selectAllOf(src)),
                    new ProgressListener() {
                        @Override
                        public void onFileProcessed(String rel, String kind, String sourceHash, String destHash) {
                            if (heldAtTheMomentOfStopping.get() < 0) {
                                heldAtTheMomentOfStopping.set(engine.retainedHashTasks());
                                firstRow.countDown();
                                try {
                                    released.await(20, java.util.concurrent.TimeUnit.SECONDS);
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
            assertTrue(engine.retainedHashTasks() <= 2 * (FileSyncEngine.VERIFY_WINDOW_FILES + 1),
                    "a job that stopped holds at most the work it had outstanding when it stopped - which is all"
                            + " it is entitled to hold, and nothing else it had already reported:"
                            + " " + engine.retainedHashTasks());
        }

        JobResult result = outcome.get();
        assertNotNull(result, "the job did not run");
        assertEquals(FileSyncEngine.AbortCause.USER_CANCELLED, result.abortCause(), result.message());
        assertTrue(result.filesChecked() < files,
                "the job stopped before its tree, which is what cancelling is for: " + result.filesChecked());
        int atTheMomentOfStopping = heldAtTheMomentOfStopping.get();
        assertTrue(atTheMomentOfStopping >= 0, "no row was ever reported, so nothing was measured");
        assertTrue(atTheMomentOfStopping > 1,
                "the reads this job had queued and not yet taken delivery of are still registered - that"
                        + " registration is the only way a stop can reach them");
        assertTrue(atTheMomentOfStopping <= 2 * FileSyncEngine.VERIFY_WINDOW_FILES,
                "and they are a window's worth, not a tree's worth: " + atTheMomentOfStopping);
    }
}
