package com.fileverifier;

import com.fileverifier.FileSyncEngine.AbortCause;
import com.fileverifier.FileSyncEngine.JobRequest;
import com.fileverifier.FileSyncEngine.JobResult;
import com.fileverifier.FileSyncEngine.JobStatus;
import com.fileverifier.FileSyncEngine.JobType;
import com.fileverifier.FileSyncEngine.ProgressListener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Who owns a job, and for how long.
 *
 * <p>These are the failures that do not show up as a wrong number in a report, and that is exactly why they
 * matter: a cancel flag the whole engine shared and cleared at the start of the next job, a deadline set for
 * one job and read by another, a single job queue that a stuck thread blocks, a destination reservation
 * released by whichever thread finished last, and a cleanup that either never runs or runs twice and waits on
 * itself. In every test below the question is the same one from the other side - not "did the job stop", but
 * "what happens to everybody else while it stops, and afterwards".
 */
class JobOwnershipTest {

    private static void assumeRealRsync() {
        assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
    }

    // ------------------------------------------------------------------ why a job stopped

    @Test
    void aJobStoppedByTheUserSaysTheUserStoppedIt(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");

        Recorder recorder = new Recorder();
        FileSyncEngine engine = new FileSyncEngine();
        try {
            engine.useRsync(TestSupport.hangingFakeRsync(Files.createDirectories(tmp.resolve("fake")),
                    ">f+++++++++|a.txt", "started.marker").toString());
            assertTrue(engine.start(request(src, dst, JobType.COPY_ONLY, "a.txt"), recorder));
            awaitMarker(dst.resolve("started.marker"));

            assertTrue(engine.cancel(), "cancelling a running job must be reported as accepted");
            assertTrue(recorder.awaitCompletion(30_000), "the cancelled job never reported completion");

            JobResult result = recorder.result();
            assertEquals(AbortCause.USER_CANCELLED, result.abortCause(), result.message());
            assertTrue(result.message().contains("cancelled by the user"), result.message());
            assertTrue(result.wasAborted());
        } finally {
            engine.close();
        }
    }

    @Test
    void aJobThatPassedItsTimeLimitSaysItPassedATimeLimit(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");

        Recorder recorder = new Recorder();
        FileSyncEngine engine = new FileSyncEngine();
        try {
            engine.setJobTimeoutMillis(1_000L);
            engine.useRsync(TestSupport.hangingFakeRsync(Files.createDirectories(tmp.resolve("fake")),
                    ">f+++++++++|a.txt", "started.marker").toString());
            assertTrue(engine.start(request(src, dst, JobType.COPY_ONLY, "a.txt"), recorder));
            awaitMarker(dst.resolve("started.marker"));
            assertTrue(recorder.awaitCompletion(30_000), "the timed-out job never reported completion");

            JobResult result = recorder.result();
            assertEquals(AbortCause.TIMED_OUT, result.abortCause(), result.message());
            // A short limit is a setting, not a fault in the files; the message has to leave no doubt about
            // which of the two the reader is looking at.
            assertTrue(result.message().contains("timeout"), result.message());
            assertTrue(result.message().contains("1.0 s"), result.message());
            assertFalse(result.message().contains("cancelled"), result.message());
        } finally {
            engine.close();
        }
    }

    @Test
    void aJobStoppedBecauseTheProgramIsClosingSaysSo(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");

        Recorder recorder = new Recorder();
        FileSyncEngine engine = new FileSyncEngine();
        engine.useRsync(TestSupport.hangingFakeRsync(Files.createDirectories(tmp.resolve("fake")),
                ">f+++++++++|a.txt", "started.marker").toString());
        assertTrue(engine.start(request(src, dst, JobType.COPY_ONLY, "a.txt"), recorder));
        awaitMarker(dst.resolve("started.marker"));

        engine.close();

        assertTrue(recorder.awaitCompletion(30_000), "closing the engine never finished the job");
        JobResult result = recorder.result();
        assertEquals(AbortCause.ENGINE_CLOSED, result.abortCause(), result.message());
        assertTrue(result.message().contains("closing"), result.message());
        assertNoRsyncIsLeftBehind("after closing the engine mid-job");
    }

    @Test
    void theThreeKindsOfStopStayThreeDifferentAnswers(@TempDir Path tmp) throws Exception {
        // The point of the field is that the three are not collapsed. If a constant were handed back somewhere,
        // this is the test that notices.
        assertEquals("cancelled by the user", FileSyncEngine.abortWords(AbortCause.USER_CANCELLED));
        assertEquals("stopped on timeout", FileSyncEngine.abortWords(AbortCause.TIMED_OUT));
        assertEquals("cancelled as the program was closing", FileSyncEngine.abortWords(AbortCause.ENGINE_CLOSED));
        assertEquals("ended", FileSyncEngine.abortWords(AbortCause.NONE));
    }

    // ------------------------------------------------------------------ the next job's turn

    @Test
    void aCancelledJobGivesTheDestinationBackToTheJobAfterIt(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");

        FileSyncEngine engine = new FileSyncEngine();
        try {
            engine.useRsync(TestSupport.hangingFakeRsync(Files.createDirectories(tmp.resolve("fake")),
                    ">f+++++++++|a.txt", "started.marker").toString());
            Recorder first = new Recorder();
            assertTrue(engine.start(request(src, dst, JobType.COPY_ONLY, "a.txt"), first));
            awaitMarker(dst.resolve("started.marker"));
            engine.cancel();
            assertTrue(first.awaitCompletion(30_000));

            // The destination is free again, and the second job is judged by its own facts: "another job is
            // already using this destination" here would be a reservation the first job never gave back.
            engine.useRsync(TestSupport.CONFIGURED_RSYNC);
            Recorder second = new Recorder();
            assertTrue(engine.start(request(src, dst, JobType.COPY_AND_VERIFY, "a.txt"), second),
                    "the destination was still reserved by the job that had just been cancelled");
            assertTrue(second.awaitCompletion(60_000));
            assertEquals(JobStatus.COMPLETED, second.result().status(), second.result().message());
            assertEquals(1, second.result().filesCopied(), second.result().message());
            assertEquals("a\n", Files.readString(dst.resolve("a.txt")));
        } finally {
            engine.close();
        }
    }

    @Test
    void aJobThreadThatNeverReturnsDoesNotStopTheJobAfterIt(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        TestSupport.write(src.resolve("b.txt"), "b\n");
        Path fake = Files.createDirectories(tmp.resolve("fake"));

        CountDownLatch stuck = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        FileSyncEngine engine = new FileSyncEngine();
        try {
            // The fake rsync finishes at once; the thread that was reading its output is the one that does
            // not come back, because it is blocked inside a caller's callback. That is the worst case the
            // engine has to survive, and it is a real one: a listener that calls back into a frozen UI.
            ProgressListener blocking = new ProgressListener() {
                @Override
                public void onFileProcessed(String relPath, String kind, String sourceHash, String destHash) {
                    stuck.countDown();
                    try {
                        released.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                @Override
                public void onCompletion(JobResult result) {
                    // The result is not the point of this test; the thread that cannot get back is.
                }
            };
            engine.useRsync(TestSupport.fakeRsync(fake, ">f+++++++++|a.txt", 0).toString());
            assertTrue(engine.start(request(src, dst, JobType.COPY_ONLY, "a.txt"), blocking));
            assertTrue(stuck.await(30, TimeUnit.SECONDS), "the callback never got as far as being blocked");

            engine.cancel();
            // The engine cannot make that thread return, and must not need it to: within its grace it reports
            // the job as stopped and lets go. Anything past this point is the engine admitting it is stuck.
            awaitIdle(engine, 20_000);
            JobResult forced = engine.lastResult();
            assertNotNull(forced);
            assertEquals(JobStatus.FAILED, forced.status());
            assertEquals(AbortCause.USER_CANCELLED, forced.abortCause(), forced.message());
            assertTrue(forced.message().contains("had not finished"), forced.message());

            // And the engine can still run a job. With one serial queue behind it, this is where the design
            // would have stopped: the abandoned thread would still be holding the queue.
            engine.useRsync(TestSupport.CONFIGURED_RSYNC);
            Recorder second = new Recorder();
            assertTrue(engine.start(request(src, dst, JobType.COPY_AND_VERIFY, "b.txt"), second),
                    "a job could not start because an abandoned job thread was still in the way");
            assertTrue(second.awaitCompletion(60_000), "the second job never finished");
            assertEquals(JobStatus.COMPLETED, second.result().status(), second.result().message());
            assertEquals(1, second.result().filesCopied(), second.result().message());

            // The abandoned thread wakes up and tries to report. It gets heard by nobody: its job is not the
            // engine's job any more, and the second job's result still stands.
            released.countDown();
            awaitLateResult(engine, 1);
            assertEquals(JobStatus.COMPLETED, engine.lastResult().status(),
                    "the abandoned job's late result replaced the result of the job that ran after it");
            assertEquals(1, engine.lastResult().filesCopied());
        } finally {
            released.countDown();
            engine.close();
        }
    }

    @Test
    void theEngineIsAlreadyIdleWhenTheCompletionCallbackRuns(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");

        AtomicBoolean runningInsideCallback = new AtomicBoolean(true);
        FileSyncEngine engine = new FileSyncEngine();
        try {
            ProgressListener watching = new ProgressListener() {
                @Override
                public void onCompletion(JobResult result) {
                    runningInsideCallback.set(engine.isRunning());
                }
            };
            // A listener that blocks here must not be able to hold the engine in RUNNING: everything the
            // engine owns is released first, and only then is anyone outside called.
            engine.execute(request(src, dst, JobType.COPY_AND_VERIFY, "a.txt"), watching);
            assertFalse(runningInsideCallback.get(),
                    "the engine still described itself as running inside its own completion callback");
            assertFalse(engine.isRunning());
        } finally {
            engine.close();
        }
    }

    @Test
    void aTimedOutCopyLeavesNoRsyncReadingTheSource(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        // Sparse and throttled: the point is a transfer that is still running when the limit bites, and the
        // owner's disk should not pay for that.
        TestSupport.sparse(src.resolve("big.bin"), 300_000_000L);

        Recorder recorder = new Recorder();
        FileSyncEngine engine = new FileSyncEngine();
        try {
            engine.setBandwidthLimitKbps(1_500L);
            engine.setJobTimeoutMillis(1_500L);
            assertTrue(engine.start(request(src, dst, JobType.COPY_ONLY, "big.bin"), recorder));
            assertTrue(recorder.awaitCompletion(30_000), "the timed-out job never reported completion");

            JobResult result = recorder.result();
            assertEquals(AbortCause.TIMED_OUT, result.abortCause(), result.message());
            // Killing only the process Java started leaves rsync's own children reading the source disk.
            assertNoRsyncIsLeftBehind("after the job's time limit was passed");
        } finally {
            engine.close();
        }
    }

    @Test
    void anAbandonedJobDoesNotHandBackADestinationAnotherJobIsUsing(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");

        CountDownLatch stuck = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        FileSyncEngine abandoning = new FileSyncEngine();
        FileSyncEngine running = new FileSyncEngine();
        try {
            abandoning.useRsync(TestSupport.fakeRsync(Files.createDirectories(tmp.resolve("fake1")),
                    ">f+++++++++|a.txt", 0).toString());
            ProgressListener blocking = new ProgressListener() {
                @Override
                public void onFileProcessed(String relPath, String kind, String sourceHash, String destHash) {
                    stuck.countDown();
                    try {
                        released.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                @Override
                public void onCompletion(JobResult result) {
                }
            };
            assertTrue(abandoning.start(request(src, dst, JobType.COPY_ONLY, "a.txt"), blocking));
            assertTrue(stuck.await(30, TimeUnit.SECONDS));
            abandoning.cancel();
            awaitIdle(abandoning, 20_000);

            // A second engine now takes the destination that the abandoned job had claimed.
            running.useRsync(TestSupport.hangingFakeRsync(Files.createDirectories(tmp.resolve("fake2")),
                    ">f+++++++++|a.txt", "second.marker").toString());
            Recorder second = new Recorder();
            assertTrue(running.start(request(src, dst, JobType.COPY_ONLY, "a.txt"), second),
                    "the destination should be free once the engine had given up on its own job");
            awaitMarker(dst.resolve("second.marker"));

            // The abandoned thread finally wakes up and publishes. It is dropped - and releasing this job's
            // destination as well would free a directory the job above is still writing into, because the
            // destination is named by the same path in both cases.
            released.countDown();
            awaitLateResult(abandoning, 1);

            // The claim is what is being checked, and it is checked by the job that takes the destination:
            // a job of its own must still be refused while the second engine is working there.
            FileSyncEngine third = new FileSyncEngine();
            try {
                Recorder outsider = new Recorder();
                assertTrue(third.start(request(src, dst, JobType.COPY_ONLY, "a.txt"), outsider));
                assertTrue(outsider.awaitCompletion(30_000), "the refusing job never reported");
                assertTrue(outsider.result().message().contains("already using the destination"),
                        "an abandoned job's late cleanup released a destination another job was still using: "
                                + outsider.result().message());
            } finally {
                third.close();
            }

            running.cancel();
            assertTrue(second.awaitCompletion(30_000));
        } finally {
            released.countDown();
            abandoning.close();
            running.close();
        }
    }

    // ------------------------------------------------------------------ cleanup ownership

    @Test
    void cleaningUpTwiceIsPromptAndTheSecondTimeDoesNothing(@TempDir Path tmp) throws Exception {
        FileSyncEngine engine = new FileSyncEngine();
        long started = System.nanoTime();
        engine.closeOnce();
        engine.closeOnce();
        engine.close();
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(millis < 2_000L, "three cleanup calls took " + millis + " ms; the second and third are"
                + " meant to return at once rather than wait on the first one's work");

        Recorder recorder = new Recorder();
        assertFalse(engine.start(request(tmp.resolve("src"), tmp.resolve("dst"), JobType.COPY_ONLY, "a.txt"),
                recorder),
                "a closed engine accepted another job");
        assertThrows(IllegalStateException.class,
                () -> engine.execute(request(tmp.resolve("src"), tmp.resolve("dst"), JobType.COPY_ONLY),
                        recorder));
    }

    @Test
    void theCommandLineRegistersItsCleanupAndTakesItBackAgain(@TempDir Path tmp) throws Exception {
        FileSyncEngine engine = new FileSyncEngine();
        Thread hook = Cli.installEngineCleanup(engine);
        assertNotNull(hook);
        // Registered, and removable: the failure mode here is a cleanup that quietly never runs, or one that
        // is left behind to run again when a finished program exits.
        assertTrue(Runtime.getRuntime().removeShutdownHook(hook),
                "the shutdown hook was never registered, so a signal would leave rsync running");
        Cli.removeEngineCleanup(hook);
        Cli.removeEngineCleanup(hook);

        // The hook's own work: stopping the engine, which is what stops the process tree it owns. The hook
        // cannot be asked for its runnable, so the CLI hands out the same one it installed.
        assertEquals("file-copy-util-engine-cleanup", hook.getName());
        Cli.engineCleanup(engine).run();
        assertFalse(engine.isRunning());
        assertFalse(engine.start(request(tmp.resolve("src"), tmp.resolve("dst"), JobType.COPY_ONLY, "a.txt"),
                new Recorder()));
    }

    // ------------------------------------------------------------------ helpers

    private static JobRequest request(Path source, Path dest, JobType type, String... names)
            throws IOException {
        Files.createDirectories(source);
        Files.createDirectories(dest);
        return TestSupport.request(source, dest, type, TestSupport.selection(names),
                TestSupport.selection());
    }

    private static void awaitMarker(Path marker) throws InterruptedException {
        long until = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < until) {
            if (Files.exists(marker)) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("the stand-in rsync never got as far as " + marker);
    }

    private static void awaitIdle(FileSyncEngine engine, long millis) throws InterruptedException {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            if (!engine.isRunning()) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("the engine still described the job as running after " + millis + " ms");
    }

    private static void awaitLateResult(FileSyncEngine engine, int atLeast) throws InterruptedException {
        long until = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < until) {
            if (engine.lateResultsDropped() >= atLeast) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("the abandoned job's result was never dropped and counted; it may have been"
                + " applied to the job that ran after it");
    }

    private static void assertNoRsyncIsLeftBehind(String when) throws Exception {
        TestSupport.assertNoProcessesRunning("rsync", when);
    }

    /** A listener that keeps the last result it was given. */
    private static final class Recorder implements ProgressListener {

        private final CountDownLatch completion = new CountDownLatch(1);
        private final AtomicReference<JobResult> result = new AtomicReference<>();
        private final AtomicInteger callbacks = new AtomicInteger();

        @Override
        public void onFileProcessed(String relPath, String kind, String sourceHash, String destHash) {
            callbacks.incrementAndGet();
        }

        @Override
        public void onCompletion(JobResult result) {
            this.result.set(result);
            completion.countDown();
        }

        boolean awaitCompletion(long millis) throws InterruptedException {
            return completion.await(millis, TimeUnit.MILLISECONDS);
        }

        JobResult result() {
            return result.get();
        }
    }
}
