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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
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

    @Test
    void closingTheProgramWhileACallbackIsBlockedStillEndsThatJob(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");

        CountDownLatch stuck = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        FileSyncEngine engine = new FileSyncEngine();
        // The hole: the terminal guard lives on the watchdog, and closing retires the watchdog. A job blocked
        // inside a caller's callback at that moment used to be left reported as running, with no result - the
        // command-line run exits printing no summary, and the window keeps a job that will never end.
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
        try {
            engine.useRsync(TestSupport.fakeRsync(Files.createDirectories(tmp.resolve("fake")),
                    ">f+++++++++|a.txt", 0).toString());
            assertTrue(engine.start(request(src, dst, JobType.COPY_ONLY, "a.txt"), blocking));
            assertTrue(stuck.await(30, TimeUnit.SECONDS), "the callback never got as far as being blocked");

            long startedAt = System.nanoTime();
            engine.close();
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            assertTrue(millis < 5_000L, "closing waited on the blocked callback for " + millis + " ms");

            JobResult result = engine.lastResult();
            assertNotNull(result, "closing left the job with no result at all");
            assertEquals(JobStatus.FAILED, result.status());
            assertEquals(AbortCause.ENGINE_CLOSED, result.abortCause(), result.message());
            assertFalse(engine.isRunning(), "closing left the engine describing the job as running");
            assertTrue(result.message().contains("closing"), result.message());

            released.countDown();
            awaitLateResult(engine, 1);
            assertEquals(AbortCause.ENGINE_CLOSED, engine.lastResult().abortCause(),
                    "the abandoned thread's late result replaced the one closing reported");
            TestSupport.assertNoProcessesRunning("rsync", "after closing over a job with a blocked callback");
        } finally {
            released.countDown();
            engine.close();
        }
    }

    @Test
    void aForcedCompletionWithABlockedCallbackStillEnforcesTheNextJobsDeadline(@TempDir Path tmp)
            throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Path src2 = Files.createDirectories(tmp.resolve("src2"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        TestSupport.write(src2.resolve("a.txt"), "a\n");

        CountDownLatch stuckInCallback = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        FileSyncEngine engine = new FileSyncEngine();
        try {
            // A listener is somebody else's code and may block on completion. That is entitled, and it cannot
            // be allowed to occupy the watchdog: the watchdog is what enforces the next job's time limit.
            ProgressListener blocking = new ProgressListener() {
                @Override
                public void onFileProcessed(String relPath, String kind, String sourceHash, String destHash) {
                    try {
                        released.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                @Override
                public void onCompletion(JobResult result) {
                    stuckInCallback.countDown();
                    try {
                        released.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            engine.useRsync(TestSupport.hangingFakeRsync(Files.createDirectories(tmp.resolve("fake1")),
                    ">f+++++++++|a.txt", "first.marker").toString());
            assertTrue(engine.start(request(src, dst, JobType.COPY_ONLY, "a.txt"), blocking));
            awaitMarker(dst.resolve("first.marker"));
            engine.cancel();

            awaitIdle(engine, 20_000);
            assertTrue(stuckInCallback.await(20, TimeUnit.SECONDS),
                    "the forced completion was never delivered at all");

            // The engine must still be able to run a job AND still enforce a limit on it, with a callback
            // blocked somewhere else. The only thing that ends a hung rsync is the watchdog.
            engine.setJobTimeoutMillis(1_000L);
            engine.useRsync(TestSupport.hangingFakeRsync(Files.createDirectories(tmp.resolve("fake2")),
                    ">f+++++++++|a.txt", "second.marker").toString());
            Recorder second = new Recorder();
            assertTrue(engine.start(request(src2, dst, JobType.COPY_ONLY, "a.txt"), second),
                    "a blocked completion callback stopped the engine taking another job");
            awaitMarker(dst.resolve("second.marker"));
            assertTrue(second.awaitCompletion(20_000),
                    "the watchdog never enforced the next job's time limit - its thread was inside the"
                            + " blocked completion callback");
            assertEquals(AbortCause.TIMED_OUT, second.result().abortCause(), second.result().message());
            TestSupport.assertNoProcessesRunning("rsync", "after the watchdog ended the second job");
        } finally {
            released.countDown();
            engine.close();
        }
    }

    @Test
    void aJobStoppedWhileItWasStillListingTheFilesStillGetsAReport(@TempDir Path tmp) throws Exception {
        // A job can be stopped while it is still validating a big selection or walking the directory, before
        // any counter exists. "No counters yet" must not mean "no report": the job is named, the phase it was
        // in is named, and the figures say nothing was checked instead of leaving the caller with nothing.
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        int names = 60_000;
        String[] selection = new String[names];
        for (int i = 0; i < names; i++) {
            Path file = src.resolve("f" + (i / 1000) + "/file" + i + ".txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "x\n");
            selection[i] = src.relativize(file).toString().replace('\'', '/');
        }

        FileSyncEngine engine = new FileSyncEngine();
        try {
            Recorder recorder = new Recorder();
            assertTrue(engine.start(TestSupport.request(src, dst, JobType.COPY_AND_VERIFY,
                    TestSupport.selection(selection), TestSupport.selection()), recorder));
            Thread.sleep(150L);
            engine.cancel();
            assertTrue(recorder.awaitCompletion(30_000), "the stopped job never reported anything");
            JobResult result = recorder.result();
            assertEquals(JobStatus.FAILED, result.status());
            assertEquals(AbortCause.USER_CANCELLED, result.abortCause(), result.message());
            assertEquals(0, result.filesChecked(),
                    "a job stopped while listing files checked nothing: " + result.message());
            assertFalse(engine.isRunning());
            assertSame(recorder.result(), engine.lastResult(),
                    "the engine's own account of the last job should be the report that job made");
        } finally {
            engine.close();
        }
    }

    // ------------------------------------------------------------------ closing vs admission

    @Test
    void closingTakesItsTurnAtTheSameLockThatAdmitsAJob(@TempDir Path tmp) throws Exception {
        // The correction, tested at the mechanism rather than at one lucky interleaving: marking the engine
        // closed and capturing the job to stop happen inside the lifecycle critical section, so closing cannot
        // slip past a job that is being admitted. A close that does not participate in that section never
        // queues behind it.
        FileSyncEngine engine = new FileSyncEngine();
        Object lock = lifecycleLockOf(engine);
        CountDownLatch closerStarted = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            closerStarted.countDown();
            engine.close();
        }, "closer-under-test");
        try {
            synchronized (lock) {
                closer.start();
                assertTrue(closerStarted.await(10, TimeUnit.SECONDS));
                awaitThreadState(closer, Thread.State.BLOCKED, 3_000);
                assertEquals(Thread.State.BLOCKED, closer.getState(),
                        "close() walked past the lifecycle lock instead of waiting its turn behind it: "
                                + closer.getState());
            }
            closer.join(10_000);
            assertFalse(closer.isAlive(), "the close never got the lock it was waiting for");
        } finally {
            engine.close();
        }
    }

    @Test
    void aCloseMarkedBeforeAJobIsAskedForRefusesThatJob(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Path copied = TestSupport.write(src.resolve("a.txt"), "a\n");
        FileSyncEngine engine = new FileSyncEngine();
        try {
            engine.useRsync(TestSupport.fakeRsync(Files.createDirectories(tmp.resolve("fake")),
                    ">f+++++++++|a.txt", 0).toString());
            engine.close();
            assertThrows(IllegalStateException.class,
                    () -> engine.execute(request(src, dst, JobType.COPY_ONLY, "a.txt"), null));
            assertFalse(engine.start(request(src, dst, JobType.COPY_ONLY, "a.txt"), null),
                    "start() took a job after the engine had closed");
            assertFalse(Files.exists(dst.resolve("a.txt")), "a job refused after close still wrote to it");
            assertFalse(engine.isRunning());
            assertNull(engine.lastResult());
        } finally {
            engine.close();
        }
    }

    @Test
    void aCloseAfterAJobIsRunningStopsThatJobInsteadOfMissingIt(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        FileSyncEngine engine = new FileSyncEngine();
        try {
            Recorder recorder = new Recorder();
            engine.useRsync(TestSupport.hangingFakeRsync(Files.createDirectories(tmp.resolve("fake")),
                    ">f+++++++++|a.txt", "inflight.marker").toString());
            assertTrue(engine.start(request(src, dst, JobType.COPY_ONLY, "a.txt"), recorder));
            awaitMarker(dst.resolve("inflight.marker"));

            // Admission won, so closing has to have hold of this controller: it stops the job rather than
            // returning and leaving it copying with nothing watching.
            engine.close();
            assertTrue(recorder.awaitCompletion(20_000), "the closed engine never reported its job");
            assertEquals(AbortCause.ENGINE_CLOSED, recorder.result().abortCause(), recorder.result().message());
            assertFalse(Files.exists(dst.resolve("a.txt")), "the stopped job wrote the file anyway");
            TestSupport.assertNoProcessesRunning("rsync", "after closing over a running copy");

            // And it gives the destination back: a stranded claim would keep that directory shut to every
            // engine that comes after, with no job left to release it.
            FileSyncEngine next = new FileSyncEngine();
            try {
                next.useRsync(TestSupport.fakeRsync(Files.createDirectories(tmp.resolve("fake2")),
                        ">f+++++++++|a.txt", 0).toString());
                Recorder second = new Recorder();
                // The very same destination the stopped job was using: if closing or the dropped result left a
                // claim behind, that directory stays shut with no job alive to release it.
                assertTrue(next.start(request(src, dst, JobType.COPY_ONLY, "a.txt"), second),
                        "the destination stayed claimed after the job that held it was stopped");
                assertTrue(second.awaitCompletion(20_000));
                assertEquals(JobStatus.COMPLETED, second.result().status(), second.result().message());
            } finally {
                next.close();
            }
        } finally {
            engine.close();
        }
    }

    @Test
    void aCompletedBeforeCloseFakeDoesNotProveAWriteAfterClose(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(TestSupport.fakeRsync(Files.createDirectories(tmp.resolve("fake")),
                    ">f+++++++++|a.txt", 0).toString());
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<JobResult> outcome = new AtomicReference<>();
            JobRequest job = request(src, dst, JobType.COPY_ONLY, "a.txt");
            Thread caller = new Thread(() -> {
                try { outcome.set(engine.execute(job, null)); }
                finally { completed.countDown(); }
            });
            caller.start();
            assertTrue(completed.await(20, TimeUnit.SECONDS), "job did not finish before close");
            caller.join(10_000);
            assertFalse(caller.isAlive());
            assertEquals(JobStatus.COMPLETED, outcome.get().status(), outcome.get().toString());
            assertFalse(Files.exists(dst.resolve("a.txt")), "the stand-in never copies a file");
            // The old oracle (COMPLETED && !copiedAtClose) is true even though execute returned before close.
            engine.closeOnce();
            assertTrue(outcome.get().status() == JobStatus.COMPLETED && !Files.exists(dst.resolve("a.txt")),
                    "this ordered case demonstrates the old predicate's false positive");
            assertFalse(engine.isRunning());
            TestSupport.assertNoProcessesRunning("rsync", "after a job completed before close");
        }
    }

    @Test
    void aRealFileCompletedBeforeCloseRemainsAValidCompletion(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(TestSupport.copyingFakeRsync(Files.createDirectories(tmp.resolve("fake")),
                    ">f+++++++++|a.txt").toString());
            JobResult result = engine.execute(request(src, dst, JobType.COPY_ONLY, "a.txt"), null);
            assertEquals(JobStatus.COMPLETED, result.status(), result.toString());
            assertEquals("a\n", Files.readString(dst.resolve("a.txt")));
            engine.closeOnce();
            assertEquals("a\n", Files.readString(dst.resolve("a.txt")));
            assertFalse(engine.isRunning());
            TestSupport.assertNoProcessesRunning("rsync", "after a real file completed before close");
        }
    }

    @Test
    void aJobAskedForWhileTheProgramIsClosingIsEitherRefusedOrStopped(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        Path fakeDir = Files.createDirectories(tmp.resolve("fake"));
        Path fake = fakeRsyncGatedUntilAfterClose(fakeDir);

        // The child cannot complete a copy until the closer creates its gate after closeOnce returns.
        // Thus a successful copy, a surviving owned PID, or a file appearing after close is observable
        // evidence of continued work, rather than an inference from an announcement by a no-write fake.
        for (int round = 0; round < 40; round++) {
            // A destination of its own per round, so a file left by one round can never be mistaken for a
            // file written by the next one.
            Path roundDest = Files.createDirectories(dst.resolve("r" + round));
            FileSyncEngine engine = new FileSyncEngine();
            engine.useRsync(fake.toString());
            engine.setJobTimeoutMillis(20_000L);
            JobRequest request = request(src, roundDest, JobType.COPY_ONLY, "a.txt");
            AtomicReference<JobResult> outcome = new AtomicReference<>();
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicBoolean runningAtClose = new AtomicBoolean();
            AtomicBoolean copiedAtClose = new AtomicBoolean();
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            final int r = round;

            Thread caller = new Thread(() -> {
                try {
                    outcome.set(engine.execute(request, null));
                } catch (Throwable t) {
                    thrown.set(t);
                }
            }, "caller-" + r);
            Thread closer = new Thread(() -> {
                try {
                    engine.closeOnce();
                    runningAtClose.set(engine.isRunning());
                    copiedAtClose.set(Files.exists(roundDest.resolve("a.txt")));
                    Files.writeString(roundDest.resolve("allow-copy"), "close returned");
                } catch (Throwable t) {
                    closeFailure.set(t);
                }
            }, "closer-" + r);
            caller.start();
            closer.start();
            caller.join(30_000);
            closer.join(30_000);
            assertFalse(caller.isAlive(), "round " + round + ": the caller never came back");
            assertFalse(closer.isAlive(), "round " + round + ": the close never came back");
            assertNull(closeFailure.get(), "round " + round + ": close or gate failed: " + closeFailure.get());
            assertFalse(runningAtClose.get(), "round " + round + ": engine still running when close returned");
            assertFalse(copiedAtClose.get(), "round " + round + ": the gated child copied before its gate");

            JobResult result = outcome.get();
            if (result == null) {
                assertTrue(thrown.get() instanceof IllegalStateException,
                        "round " + round + ": refused, but not with the reason: " + thrown.get());
            } else {
                if (result.message().startsWith("Another job in this program is already using the destination")) {
                    fail("round " + round + ": the destination stayed claimed with no job holding it: "
                            + result.message());
                }
                assertEquals(JobStatus.FAILED, result.status(),
                        "round " + round + ": the gated job completed after close: " + result);
            }
            assertFalse(engine.isRunning(), "round " + round + ": the engine is still running after closing");
            assertFalse(Files.exists(roundDest.resolve("a.txt")),
                    "round " + round + ": a gated copy appeared after close() returned");
            Path pidFile = roundDest.resolve("process.pid");
            if (Files.exists(pidFile)) {
                long pid = Long.parseLong(Files.readString(pidFile).trim());
                assertTrue(TestSupport.waitFor(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true),
                        10_000), "round " + round + ": the gated rsync process survived close: " + pid);
            }
            try {
                TestSupport.assertNoProcessesRunning("rsync", "in round " + round + " of closing vs admission");
            } finally {
                engine.close();
            }
        }

        // And no round may leave its destination claimed. A claim belonging to a job that has stopped running
        // is a directory locked forever, with nothing left alive to hand it back.
        Set<String> claims = FileSyncEngine.destinationClaimsInUse();
        for (int round = 0; round < 40; round++) {
            String key = dst.resolve("r" + round).toRealPath().toString();
            assertFalse(claims.contains(key),
                    "the job in round " + round + " left its destination claim behind: " + key);
        }
    }

    @Test
    void aStartedGatedCopyIsStoppedBeforeThePostCloseGateOpens(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        Path fake = fakeRsyncGatedUntilAfterClose(Files.createDirectories(tmp.resolve("fake")));
        try (FileSyncEngine engine = new FileSyncEngine()) {
            engine.useRsync(fake.toString());
            JobRequest job = request(src, dst, JobType.COPY_ONLY, "a.txt");
            AtomicReference<JobResult> result = new AtomicReference<>();
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try { result.set(engine.execute(job, null)); }
                catch (Throwable t) { thrown.set(t); }
            }, "started-gated-copy");
            caller.start();
            try {
                Path pidFile = dst.resolve("process.pid");
                awaitMarker(pidFile);
                long pid = Long.parseLong(Files.readString(pidFile).trim());
                assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                        "the marked owned process was not running before close");
                assertFalse(Files.exists(dst.resolve("a.txt")));
                engine.closeOnce();
                Files.writeString(dst.resolve("allow-copy"), "close returned");
                caller.join(30_000);
                assertFalse(caller.isAlive());
                assertNull(thrown.get(), String.valueOf(thrown.get()));
                assertNotNull(result.get());
                assertEquals(JobStatus.FAILED, result.get().status(), result.get().toString());
                assertEquals(AbortCause.ENGINE_CLOSED, result.get().abortCause(), result.get().toString());
                assertFalse(Files.exists(dst.resolve("a.txt")), "the process copied after close");
                assertTrue(TestSupport.waitFor(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true),
                        10_000), "the owned process survived close: " + pid);
                TestSupport.assertNoProcessesRunning("rsync", "after stopping a started gated copy");
            } finally {
                engine.closeOnce();
                caller.join(30_000);
            }
        }
    }

    /** What {@link #parkAStartAcrossClose} observed, so both parked-start tests read the same way. */
    private record ParkedStart(JobResult result, Throwable thrown, Throwable closeFailure,
                               Throwable probeFailure, boolean runningAtClose, boolean fileAtCopyGate,
                               long closeMillis) { }

    /**
     * Park the job inside the interval between its pre-spawn cancellation checks and the process it is about
     * to start, close the engine while it is parked, and only then let it go on.
     *
     * <p>This is the interleaving the 40 rounds above reach only by luck - and did reach, on this commit, on
     * two GitHub jobs at round 28 and locally at round 38. Parking makes the order a fact of the test instead
     * of a property of the scheduler: closing has no process to stop at that moment, so whatever the job does
     * next happens after close() has returned.
     */
    private static ParkedStart parkAStartAcrossClose(FileSyncEngine engine, JobRequest job, Path dst)
            throws InterruptedException {
        CountDownLatch parkedAtSpawnInterval = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicReference<Throwable> probeFailure = new AtomicReference<>();
        engine.setSpawnIntervalProbe((phase, created) -> {
            if (phase != FileSyncEngine.SpawnPhase.BEFORE_FINAL_CHECK) {
                return;
            }
            parkedAtSpawnInterval.countDown();
            try {
                assertTrue(closeReturned.await(30, TimeUnit.SECONDS),
                        "close() never returned while the job was parked before starting its process");
            } catch (Throwable t) {
                probeFailure.compareAndSet(null, t);
            }
        });
        AtomicReference<JobResult> outcome = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        AtomicBoolean runningAtClose = new AtomicBoolean();
        AtomicBoolean fileAtClose = new AtomicBoolean();
        AtomicReference<Long> closeMillis = new AtomicReference<>();

        Thread caller = new Thread(() -> {
            try { outcome.set(engine.execute(job, null)); } catch (Throwable t) { thrown.set(t); }
        }, "parked-spawn-caller");
        Thread closer = new Thread(() -> {
            try {
                assertTrue(parkedAtSpawnInterval.await(30, TimeUnit.SECONDS),
                        "the job never reached the interval before starting its process");
                long at = System.nanoTime();
                engine.closeOnce();
                closeMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at));
                runningAtClose.set(engine.isRunning());
                fileAtClose.set(Files.exists(dst.resolve("a.txt")));
                Files.writeString(dst.resolve("allow-copy"), "close returned");
            } catch (Throwable t) {
                closeFailure.compareAndSet(null, t);
            } finally {
                closeReturned.countDown();
            }
        }, "parked-spawn-closer");
        caller.start();
        closer.start();
        caller.join(30_000);
        closer.join(30_000);

        assertFalse(caller.isAlive(), "the parked job never came back after close()");
        assertFalse(closer.isAlive(), "close() never returned");
        return new ParkedStart(outcome.get(), thrown.get(), closeFailure.get(), probeFailure.get(),
                runningAtClose.get(), fileAtClose.get(), closeMillis.get() == null ? -1L : closeMillis.get());
    }

    /** The assertions both parked-start tests make: refused the start, and nothing left in the destination. */
    private void assertStartWasRefusedAcrossClose(ParkedStart parked, Path dst) throws Exception {
        assertNull(parked.probeFailure(), "the parked job was never released: " + parked.probeFailure());
        assertNull(parked.closeFailure(), String.valueOf(parked.closeFailure()));
        // Closing stayed bounded even with a start parked inside the interval: the wait for a start that is
        // already under way is capped, and a start that is too late is refused instead of chased.
        assertTrue(parked.closeMillis() >= 0L && parked.closeMillis() < 5_000L,
                "close() took " + parked.closeMillis() + " ms with a start parked in the interval");
        assertFalse(parked.runningAtClose(), "engine still running when close returned");
        assertFalse(parked.fileAtCopyGate(), "the parked job wrote before close() returned");

        assertNull(parked.thrown(), String.valueOf(parked.thrown()));
        JobResult result = parked.result();
        assertNotNull(result, "the parked job reported nothing at all");
        assertEquals(JobStatus.FAILED, result.status(), result.toString());
        assertEquals(AbortCause.ENGINE_CLOSED, result.abortCause(), result.toString());
        // The safety property: once close() has returned, no process may have been started for this job and
        // nothing may have reached the destination.
        assertFalse(Files.exists(dst.resolve("process.pid")),
                "a process was started after close() had already stopped the job");
        assertFalse(Files.exists(dst.resolve("a.txt")), "a copy appeared after close() returned");
        assertTrue(result.message().contains("before rsync started"),
                "the job was stopped after a process had been started, not refused one: " + result.message());

        // Refusing the start still accounts for what was selected: one row, saying nothing was copied.
        assertEquals(1, result.filesNotTransferred(), result.toString());
        assertEquals(0, result.filesCopied(), result.toString());
        assertEquals(1, result.outcomes().size(), result.toString());
        assertEquals(FileSyncEngine.TransferDisposition.NOT_COPIED, result.outcomes().get(0).transfer(),
                result.toString());
        assertEquals("a.txt", result.outcomes().get(0).relPath(), result.toString());

        TestSupport.assertNoProcessesRunning("rsync", "after refusing a start parked across close");
        assertFalse(FileSyncEngine.destinationClaimsInUse().contains(dst.toRealPath().toString()),
                "the refused job left its destination claim behind");
    }

    /**
     * A job parked before it starts its process, with an rsync that only copies once a gate the test opens
     * after close() returned exists: the copy is then direct evidence of work done after the program closed.
     */
    @Test
    void aStartParkedAcrossCloseIsRefusedRatherThanWritingAfterClose(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        FileSyncEngine engine = new FileSyncEngine();
        try {
            engine.useRsync(fakeRsyncGatedUntilAfterClose(
                    Files.createDirectories(tmp.resolve("fake"))).toString());
            engine.setJobTimeoutMillis(60_000L);
            ParkedStart parked = parkAStartAcrossClose(engine, request(src, dst, JobType.COPY_ONLY, "a.txt"), dst);
            assertStartWasRefusedAcrossClose(parked, dst);
            assertFalse(engine.isRunning(), "the engine is still running after closing");
        } finally {
            engine.close();
        }
    }

    /**
     * The same parking with an rsync that copies as soon as it runs: a start that survives close() lands a
     * file in the destination whatever the test does next, so this is the GitHub failure with its timing
     * forced rather than waited for.
     */
    @Test
    void aStartParkedAcrossCloseNeverReachesTheDestination(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        FileSyncEngine engine = new FileSyncEngine();
        try {
            engine.useRsync(fakeRsyncCopyingAsSoonAsItRuns(
                    Files.createDirectories(tmp.resolve("fake"))).toString());
            engine.setJobTimeoutMillis(60_000L);
            ParkedStart parked = parkAStartAcrossClose(engine, request(src, dst, JobType.COPY_ONLY, "a.txt"), dst);
            assertStartWasRefusedAcrossClose(parked, dst);
            assertFalse(engine.isRunning(), "the engine is still running after closing");
        } finally {
            engine.close();
        }
    }

    // ------------------------------------------------- the intervals after the final cancellation check

    /**
     * What one parked interval looked like from the outside: what {@code close()} answered with, and what was
     * still true of this program's destination claim, the job's process, and another engine's attempt at the
     * same destination at the moment {@code close()} returned.
     */
    /** Milliseconds of clock granularity allowed when judging whether a park outlasted the handoff bound. */
    private static final long HANDOFF_MEASUREMENT_TOLERANCE_MILLIS = 25L;

    private record ParkedInterval(JobResult result, Throwable thrown, Throwable closeFailure,
                                  Throwable probeFailure, long closeMillis, boolean runningAtClose,
                                  boolean fileAtClose, boolean claimHeldAtClose, boolean processAtCloseAlive,
                                  long processAtClosePid, String outsiderMessage, boolean outsiderCopied,
                                  long handoffMillis, Path destination, long processTheJobDidStart,
                                  String terminalMessage) { }

    /**
     * Park the job at {@code phase} inside its own process start, close the engine while it is parked, ask a
     * second engine for the same destination while the park is still held, and only then let the job go on.
     *
     * <p>The park is released only after {@code close()} has returned, so the interval is longer than
     * {@link FileSyncEngine#SPAWN_HANDOFF_MILLIS} by construction: the stop cannot find a registered process,
     * and whatever the job does next happens after the program has closed. Nothing here depends on the
     * scheduler, on a sleep, or on how long fork and exec happen to take on this machine.
     *
     * @param outsiderTry run on the closer's thread, after {@code close()} returned and while the job is still
     *                    parked; whatever it returns is recorded as this program's answer to another engine
     *                    asking for the same destination.
     */
    private ParkedInterval parkAtPhaseAcrossClose(FileSyncEngine engine, JobRequest job, Path dst,
                                                  FileSyncEngine.SpawnPhase phase, boolean asynchronous,
                                                  Supplier<String> outsiderTry) throws Exception {
        Path key = dst.toRealPath();
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch releasePark = new CountDownLatch(1);
        AtomicReference<Process> parkedProcess = new AtomicReference<>();
        // The engine's own handle for the process the job went on to create, whenever it creates one. Read from
        // the engine rather than from a file the stand-in writes, because a process stopped quickly may never
        // get as far as writing anything at all.
        AtomicReference<Process> processTheJobDidStart = new AtomicReference<>();
        AtomicReference<Throwable> probeFailure = new AtomicReference<>();
        engine.setSpawnIntervalProbe((seen, created) -> {
            if (created != null) {
                processTheJobDidStart.compareAndSet(null, created);
            }
            if (seen != phase) {
                return;
            }
            parkedProcess.compareAndSet(null, created);
            parked.countDown();
            try {
                assertTrue(releasePark.await(30, TimeUnit.SECONDS),
                        "the job was never released from the parked interval " + phase);
            } catch (Throwable t) {
                probeFailure.compareAndSet(null, t);
            }
        });

        AtomicReference<JobResult> outcome = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        AtomicReference<Boolean> runningAtClose = new AtomicReference<>();
        AtomicReference<Boolean> fileAtClose = new AtomicReference<>();
        AtomicReference<Boolean> claimHeldAtClose = new AtomicReference<>();
        AtomicReference<Boolean> processAtCloseAlive = new AtomicReference<>();
        AtomicReference<Long> processAtClosePid = new AtomicReference<>();
        AtomicReference<String> outsiderMessage = new AtomicReference<>();
        AtomicReference<Boolean> outsiderCopied = new AtomicReference<>();
        AtomicReference<Long> closeMillis = new AtomicReference<>();
        Recorder outsider = new Recorder();

        Thread caller = new Thread(() -> {
            try {
                if (asynchronous) {
                    assertTrue(engine.start(job, null), "the asynchronous job was never admitted");
                } else {
                    outcome.set(engine.execute(job, null));
                }
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "parked-" + phase.name().toLowerCase(Locale.ROOT));
        Thread closer = new Thread(() -> {
            try {
                assertTrue(parked.await(30, TimeUnit.SECONDS),
                        "the job never reached the parked interval " + phase);
                long at = System.nanoTime();
                engine.closeOnce();
                closeMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at));
                runningAtClose.set(engine.isRunning());
                fileAtClose.set(Files.exists(dst.resolve("a.txt")));
                claimHeldAtClose.set(FileSyncEngine.destinationClaimsInUse().contains(key.toString()));
                Process held = parkedProcess.get();
                if (held != null) {
                    processAtClosePid.set(held.pid());
                    processAtCloseAlive.set(ProcessHandle.of(held.pid()).map(ProcessHandle::isAlive).orElse(false));
                }
                outsiderMessage.set(outsiderTry.get());
                outsiderCopied.set(Files.exists(dst.resolve("outsider.txt")));
                // Anything the parked job's process does after this point does it after close() returned.
                Files.writeString(dst.resolve("allow-copy"), "close returned");
            } catch (Throwable t) {
                closeFailure.compareAndSet(null, t);
            } finally {
                releasePark.countDown();
            }
        }, "parked-" + phase.name().toLowerCase(Locale.ROOT) + "-closer");
        caller.start();
        closer.start();
        caller.join(30_000);
        closer.join(60_000);
        assertFalse(caller.isAlive(), "the parked job never came back after " + phase);
        assertFalse(closer.isAlive(), "the closer never came back after " + phase);
        if (asynchronous) {
            // The submitting thread is long gone by now; the job runs on the engine's own worker, and that
            // worker is the one that starts (or refuses) the process once the park is released.
            TestSupport.waitFor(() -> processTheJobDidStart.get() != null, 15_000);
        }
        return new ParkedInterval(outcome.get(), thrown.get(), closeFailure.get(), probeFailure.get(),
                closeMillis.get() == null ? -1L : closeMillis.get(),
                Boolean.TRUE.equals(runningAtClose.get()), Boolean.TRUE.equals(fileAtClose.get()),
                Boolean.TRUE.equals(claimHeldAtClose.get()), Boolean.TRUE.equals(processAtCloseAlive.get()),
                processAtClosePid.get() == null ? -1L : processAtClosePid.get(),
                outsiderMessage.get(), Boolean.TRUE.equals(outsiderCopied.get()),
                FileSyncEngine.SPAWN_HANDOFF_MILLIS, dst,
                processTheJobDidStart.get() == null ? -1L : processTheJobDidStart.get().pid(),
                engine.lastResult() == null ? "(nothing reported)" : engine.lastResult().message());
    }

    /**
     * The assertions both intervals share: the park outlasted the handoff bound, {@code close()} stayed
     * bounded, the destination this job was writing into was not handed to anybody while it could still be
     * written, and the report said what was actually known rather than what closing would have liked.
     */
    private void assertIntervalHeldAcrossClose(ParkedInterval parked, Path dst) {
        assertNull(parked.probeFailure(), "the parked job was never released: " + parked.probeFailure());
        assertNull(parked.closeFailure(), String.valueOf(parked.closeFailure()));
        // The park has to outlast the handoff, or the run proves nothing about what happens when it does. The
        // tolerance is clock granularity in the wait's own remaining-time arithmetic, not slack about the bound.
        assertTrue(parked.closeMillis() >= parked.handoffMillis() - HANDOFF_MEASUREMENT_TOLERANCE_MILLIS,
                "close() came back in " + parked.closeMillis() + " ms, under the " + parked.handoffMillis()
                        + " ms it is willing to wait for a start already under way: the park was not held past"
                        + " the handoff bound, so this run says nothing about that bound");
        assertTrue(parked.closeMillis() < 8_000L,
                "close() took " + parked.closeMillis() + " ms with a start parked in the interval");
        assertFalse(parked.runningAtClose(), "engine still running when close returned");

        // The ownership property. Until this job's start is accounted for, the directory it was writing into is
        // still this job's: another engine must not be let in to write beside a process nobody has accounted for.
        assertTrue(parked.claimHeldAtClose(),
                "close() handed the destination back while the job's start was still unaccounted for; what"
                        + " it reported while doing so was: " + parked.terminalMessage());
        assertFalse(parked.outsiderCopied(),
                "another engine copied into a destination whose previous job could still write: "
                        + parked.outsiderMessage());
        assertTrue(parked.outsiderMessage() != null
                        && parked.outsiderMessage().contains("already using the destination"),
                "the second engine was not refused the destination: " + parked.outsiderMessage());
        assertTrue(FileSyncEngine.unaccountedWriterClaimCount() >= 1,
                "the engine handed a claim back without recording that a writer was unaccounted for");
    }

    /**
     * Where both intervals end up once the parked job is let go: the process it set in motion is stopped, and
     * the destination is given back. The waits are the engine's own bounds for a stopped job's tree, not a guess
     * at when things settle down.
     */
    private void assertWriterStoppedAndClaimReleased(ParkedInterval parked, long pid)
            throws Exception {
        if (pid > 0L) {
            assertTrue(TestSupport.waitFor(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true),
                    20_000), "the process this job set in motion was never stopped: " + pid);
        }
        assertTrue(claimIsBack(parked.destination()),
                "the destination stayed claimed after its writer had been accounted for");
        TestSupport.assertNoProcessesRunning("rsync", "after a start parked across close() resolved");
    }

    /** What the engine itself reported for the job it could not account for, and what it must not have said. */
    private void assertReportDoesNotClaimMoreThanIsKnown(FileSyncEngine engine, Path dst) {
        JobResult forced = engine.lastResult();
        assertNotNull(forced, "the closing engine reported nothing for the job it let go");
        assertEquals(JobStatus.FAILED, forced.status(), forced.toString());
        assertFalse(forced.message().contains("nothing more is being written"),
                "the report closed the job by claiming nothing more was being written, while its process start"
                        + " had not been accounted for: " + forced.message());
        assertTrue(forced.message().contains("not been accounted for")
                        || forced.warnings().stream().anyMatch(w -> w.contains("not been accounted for")),
                "the report says nothing about the process start it could not account for: " + forced);
    }

    /**
     * A stop that arrives after the job's last cancellation check, while it holds the claim but has created
     * nothing: closing cannot stop a process that does not exist, and the job is not going to refuse a start it
     * has already decided on. What closing does owe is that the destination stays this job's until the start
     * is accounted for, and that its report says so.
     */
    @Test
    void aStopAfterTheFinalSpawnCheckKeepsTheDestinationWithTheJobItBelongsTo(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Path other = Files.createDirectories(tmp.resolve("other-src"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        TestSupport.write(other.resolve("outsider.txt"), "outsider\n");
        FileSyncEngine engine = new FileSyncEngine();
        try {
            engine.useRsync(fakeRsyncGatedUntilAfterClose(
                    Files.createDirectories(tmp.resolve("fake"))).toString());
            engine.setJobTimeoutMillis(60_000L);
            ParkedInterval parked = parkAtPhaseAcrossClose(engine,
                    request(src, dst, JobType.COPY_ONLY, "a.txt"), dst,
                    FileSyncEngine.SpawnPhase.AFTER_FINAL_CHECK_BEFORE_CREATE, false,
                    () -> outsiderAsksFor(other, dst));

            assertIntervalHeldAcrossClose(parked, dst);
            // Nothing existed at that moment: the park was before creation, so the process slot was empty and
            // there was nothing for closing to stop.
            assertEquals(-1L, parked.processAtClosePid(), "a process existed before the parked creation point");
            assertFalse(parked.fileAtClose(), "the destination was written before the process was even created");
            assertReportDoesNotClaimMoreThanIsKnown(engine, dst);

            // Released after close() returned, the job goes ahead and creates its process: closing could not
            // prevent that, and this test does not pretend otherwise. What it did prevent is anybody else
            // getting in beside it, and what it owes is that the process is stopped and the claim handed back.
            assertTrue(parked.processTheJobDidStart() > 0L,
                    "the released job never started a process, so this run proves nothing about one");
            assertNull(parked.thrown(), String.valueOf(parked.thrown()));
            assertNotNull(parked.result(), "the synchronous job never reported");
            assertEquals(JobStatus.FAILED, parked.result().status(), parked.result().toString());
            assertWriterStoppedAndClaimReleased(parked, parked.processTheJobDidStart());
        } finally {
            engine.close();
        }
    }

    /**
     * A stop that arrives once the process exists but before the engine has taken ownership of it: there is a
     * live, writable process and no registered one. The same ownership rule applies, and the process has to be
     * stopped by the registration path rather than left running.
     */
    @Test
    void aProcessCreatedAcrossCloseIsNotLeftUnownedAndUnstopped(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Path other = Files.createDirectories(tmp.resolve("other-src"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        TestSupport.write(other.resolve("outsider.txt"), "outsider\n");
        FileSyncEngine engine = new FileSyncEngine();
        try {
            engine.useRsync(fakeRsyncWritesThenHoldsOpen(
                    Files.createDirectories(tmp.resolve("fake"))).toString());
            engine.setJobTimeoutMillis(60_000L);
            ParkedInterval parked = parkAtPhaseAcrossClose(engine,
                    request(src, dst, JobType.COPY_ONLY, "a.txt"), dst,
                    FileSyncEngine.SpawnPhase.AFTER_CREATE_BEFORE_REGISTER, false,
                    () -> outsiderAsksFor(other, dst));

            assertIntervalHeldAcrossClose(parked, dst);
            // Here a live process did exist, and had already written: not after close() returned, but while
            // closing was still waiting on a registration this park was holding back. That is exactly why a
            // destination cannot be handed over on the strength of a report having been published.
            assertTrue(parked.processAtClosePid() > 0L, "no process was held at the creation point");
            assertTrue(parked.processAtCloseAlive(),
                    "the process held between creation and registration was not alive: "
                            + parked.processAtClosePid());
            assertTrue(parked.fileAtClose(),
                    "a live, unregistered process did not write, so this run proves nothing about what such a"
                            + " writer is entitled to do");
            assertReportDoesNotClaimMoreThanIsKnown(engine, dst);

            assertNull(parked.thrown(), String.valueOf(parked.thrown()));
            assertNotNull(parked.result(), "the synchronous job never reported");
            assertEquals(JobStatus.FAILED, parked.result().status(), parked.result().toString());
            assertWriterStoppedAndClaimReleased(parked, parked.processAtClosePid());
        } finally {
            engine.close();
        }
    }

    /**
     * The limitation, pinned down instead of argued about. A process that was created but never registered is
     * invisible to closing: there is no handle to signal, and the bounded handoff has already expired while the
     * park held the registration back. So this job can still write into its destination after {@code close()}
     * has returned, and nothing in this engine can stop it happening. What the engine does control is who else
     * may be writing there at that moment, and what its report is willing to say.
     *
     * <p>The stand-in writes only once a release file appears, and that file is written by this test after
     * {@code close()} returned, so any write it causes is a write after the program closed - no timing
     * assumption involved. The outsider engine asks first, in the same moment, so the ownership answer and the
     * write are observed against the same parked state.
     */
    @Test
    void aStartNobodyCouldAccountForCanStillWriteIntoItsDestinationAfterCloseReturns(@TempDir Path tmp)
            throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Path other = Files.createDirectories(tmp.resolve("other-src"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        TestSupport.write(other.resolve("outsider.txt"), "outsider\n");
        FileSyncEngine engine = new FileSyncEngine();
        try {
            engine.useRsync(fakeRsyncGatedUntilAfterClose(
                    Files.createDirectories(tmp.resolve("fake"))).toString());
            engine.setJobTimeoutMillis(60_000L);
            ParkedInterval parked = parkAtPhaseAcrossClose(engine,
                    request(src, dst, JobType.COPY_ONLY, "a.txt"), dst,
                    FileSyncEngine.SpawnPhase.AFTER_CREATE_BEFORE_REGISTER, false, () -> {
                        String refusal = outsiderAsksFor(other, dst);
                        try {
                            Files.writeString(dst.resolve("allow-copy"), "close has returned");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                        boolean wrote;
                        try {
                            wrote = TestSupport.waitFor(() -> Files.exists(dst.resolve("a.txt")), 5_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            wrote = false;
                        }
                        return refusal + (wrote
                                ? " | and the parked process wrote once close had returned"
                                : " | and the parked process never got as far as writing");
                    });

            assertIntervalHeldAcrossClose(parked, dst);
            // The order this test is about: nothing had been written when close() came back, and something was
            // written afterwards, by a process this engine had no handle for.
            assertFalse(parked.fileAtClose(),
                    "this run does not separate the write from close(): the file was already there");
            assertTrue(parked.processAtCloseAlive(),
                    "the unregistered process was not alive when close() returned: " + parked.processAtClosePid());
            assertTrue(parked.outsiderMessage().contains("the parked process wrote once close had returned"),
                    "the write after close() did not happen, so this run does not pin the limitation: "
                            + parked.outsiderMessage());
            // And the two things the engine does control, in the same moment: the destination stayed this job's
            // (assertIntervalHeldAcrossClose), and its report did not promise the writing had stopped.
            assertReportDoesNotClaimMoreThanIsKnown(engine, dst);

            assertNull(parked.thrown(), String.valueOf(parked.thrown()));
            assertWriterStoppedAndClaimReleased(parked, parked.processAtClosePid());
        } finally {
            engine.close();
        }
    }

    /**
     * The same two intervals on the asynchronous path, where closing also waits briefly for the job's own
     * thread. The bound on that wait must not become the thing that decides who owns the destination.
     */
    @Test
    void anAsynchronousJobParkedInEitherIntervalAcrossCloseStillHoldsItsDestination(@TempDir Path tmp)
            throws Exception {
        assumeRealRsync();
        for (FileSyncEngine.SpawnPhase phase
                : List.of(FileSyncEngine.SpawnPhase.AFTER_FINAL_CHECK_BEFORE_CREATE,
                          FileSyncEngine.SpawnPhase.AFTER_CREATE_BEFORE_REGISTER)) {
            Path tmpRoot = Files.createDirectories(tmp.resolve(phase.name()));
            Path src = Files.createDirectories(tmpRoot.resolve("src"));
            Path dst = Files.createDirectories(tmpRoot.resolve("dst"));
            Path other = Files.createDirectories(tmpRoot.resolve("other-src"));
            TestSupport.write(src.resolve("a.txt"), "a\n");
            TestSupport.write(other.resolve("outsider.txt"), "outsider\n");
            FileSyncEngine engine = new FileSyncEngine();
            try {
                boolean createdPhase = phase == FileSyncEngine.SpawnPhase.AFTER_CREATE_BEFORE_REGISTER;
                Path fakeDir = Files.createDirectories(tmpRoot.resolve("fake"));
                engine.useRsync((createdPhase ? fakeRsyncWritesThenHoldsOpen(fakeDir)
                        : fakeRsyncGatedUntilAfterClose(fakeDir)).toString());
                engine.setJobTimeoutMillis(60_000L);
                ParkedInterval parked = parkAtPhaseAcrossClose(engine,
                        request(src, dst, JobType.COPY_ONLY, "a.txt"), dst, phase, true,
                        () -> outsiderAsksFor(other, dst));

                assertIntervalHeldAcrossClose(parked, dst);
                assertReportDoesNotClaimMoreThanIsKnown(engine, dst);
                long pid = parked.processAtClosePid();
                if (createdPhase) {
                    assertTrue(pid > 0L && parked.processAtCloseAlive(),
                            phase + ": no live process was held across the asynchronous close");
                    assertTrue(parked.fileAtClose(),
                            phase + ": the live unregistered process did not write, so nothing was proved about"
                                    + " what closing had to hold back");
                } else {
                    assertEquals(-1L, pid, phase + ": a process existed before the parked creation point");
                    assertTrue(parked.processTheJobDidStart() > 0L,
                            phase + ": the released job never started a process, so nothing was proved about"
                                    + " one that started after close() had returned");
                    pid = parked.processTheJobDidStart();
                }
                assertWriterStoppedAndClaimReleased(parked, pid);
            } finally {
                engine.close();
            }
        }
    }

    /** True once this program has given the destination back, whoever it was that handed it back. */
    private static boolean claimIsBack(Path dst) throws Exception {
        String key = dst.toRealPath().toString();
        return TestSupport.waitFor(() -> !FileSyncEngine.destinationClaimsInUse().contains(key), 20_000);
    }

    /**
     * A stop must not have to queue behind the lock that decides whether a start may happen.
     *
     * <p>The job is parked inside its own spawn interval and the gate is then held by this test for longer than
     * the whole handoff bound. A stop that entered that lock without a bound would sit here until this test
     * decided to let it go - the same failure mode as waiting on somebody's process table, and the same one
     * that used to make a close's duration a fact about a different thread. With the bound in place, closing
     * stops what it can see, says the start is unaccounted for, keeps the destination claimed, and comes back.
     */
    @Test
    void aStopIsNeverQueuedBehindTheDecisionAboutWhetherAStartMayHappen(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FileSyncEngine engine = new FileSyncEngine();
        Object gate = null;
        try {
            engine.useRsync(fakeRsyncCopyingAsSoonAsItRuns(
                    Files.createDirectories(tmp.resolve("fake"))).toString());
            engine.setJobTimeoutMillis(60_000L);
            engine.setSpawnIntervalProbe((phase, created) -> {
                if (phase != FileSyncEngine.SpawnPhase.BEFORE_FINAL_CHECK) {
                    return;
                }
                parked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            AtomicReference<JobResult> outcome = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                try {
                    outcome.set(engine.execute(request(src, dst, JobType.COPY_ONLY, "a.txt"), null));
                } catch (Throwable refused) {
                    // Read back from the outcome below; a refusal here is not the thing under test.
                }
            }, "gate-held-caller");
            caller.start();
            assertTrue(parked.await(30, TimeUnit.SECONDS), "the job never reached its spawn interval");

            gate = spawnGateOf(currentControlOf(engine));
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            AtomicReference<Long> closeMillis = new AtomicReference<>();
            AtomicReference<Boolean> claimAtClose = new AtomicReference<>();
            AtomicReference<String> messageAtClose = new AtomicReference<>();
            // Held for the whole of this, whatever kind of lock the engine's gate happens to be: the point is
            // that a stop has to be able to look at an in-flight start without standing in a queue.
            holdingSpawnGate(gate, () -> {
            Thread closer = new Thread(() -> {
                try {
                    long at = System.nanoTime();
                    engine.closeOnce();
                    closeMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at));
                    claimAtClose.set(FileSyncEngine.destinationClaimsInUse()
                            .contains(dst.toRealPath().toString()));
                    messageAtClose.set(engine.lastResult() == null ? "(nothing reported)"
                            : engine.lastResult().message());
                } catch (Throwable t) {
                    closeFailure.compareAndSet(null, t);
                }
            }, "gate-held-closer");
            closer.start();
            closer.join(10_000);
            assertFalse(closer.isAlive(),
                    "close() sat in the queue for the spawn gate for 10 s while a test held it");
            assertNull(closeFailure.get(), String.valueOf(closeFailure.get()));
            long took = closeMillis.get() == null ? -1L : closeMillis.get();
            assertTrue(took >= FileSyncEngine.SPAWN_HANDOFF_MILLIS - HANDOFF_MEASUREMENT_TOLERANCE_MILLIS
                            && took < 2_500L,
                    "close() took " + took + " ms while the spawn gate was held elsewhere; looking at an"
                            + " in-flight start is bounded at " + FileSyncEngine.SPAWN_HANDOFF_MILLIS + " ms");
            assertTrue(Boolean.TRUE.equals(claimAtClose.get()),
                    "the destination was handed back while a start could not even be looked at: "
                            + messageAtClose.get());
            assertTrue(messageAtClose.get().contains("not been accounted for"),
                    "closing described a start it could not reach as though it had reached it: "
                            + messageAtClose.get());
            });
        } finally {
            release.countDown();
            engine.close();
        }
        TestSupport.assertNoProcessesRunning("rsync", "after a close that could not reach the spawn gate");
    }

    /** Something a test does while a lock is held, and which may be interrupted doing it. */
    @FunctionalInterface
    private interface WhileHeld {
        void run() throws InterruptedException;
    }

    /**
     * Run {@code inside} with the engine's spawn gate held, whichever kind of lock that gate happens to be:
     * the property under test is about holding it, not about which primitive the engine chose.
     */
    private static void holdingSpawnGate(Object gate, WhileHeld inside) throws InterruptedException {
        if (gate instanceof java.util.concurrent.locks.ReentrantLock lock) {
            lock.lock();
            try {
                inside.run();
            } finally {
                lock.unlock();
            }
        } else {
            synchronized (gate) {
                inside.run();
            }
        }
    }

    /** The controller the engine has installed for its current job. */
    private static Object currentControlOf(FileSyncEngine engine) throws Exception {
        java.lang.reflect.Field field = FileSyncEngine.class.getDeclaredField("currentControl");
        field.setAccessible(true);
        Object installed = ((java.util.concurrent.atomic.AtomicReference<?>) field.get(engine)).get();
        assertNotNull(installed, "the engine had no current job to hold a spawn gate");
        return installed;
    }

    /** The lock that decides whether a job's process start may go ahead. */
    private static Object spawnGateOf(Object control) throws Exception {
        java.lang.reflect.Field field = control.getClass().getDeclaredField("spawnGate");
        field.setAccessible(true);
        return field.get(control);
    }

    /** Another engine, asking for the destination the parked job still owns. Its message is the answer. */
    private static String outsiderAsksFor(Path source, Path dst) {
        try (FileSyncEngine outsider = new FileSyncEngine()) {
            return outsider.execute(request(source, dst, JobType.COPY_ONLY, "outsider.txt"), null).message();
        } catch (IOException couldNotAsk) {
            throw new java.io.UncheckedIOException(couldNotAsk);
        }
    }

    /**
     * An rsync stand-in that records its PID, writes the file it was asked to copy, and then stays alive. Both
     * the write and the survival are things this test causes rather than waits for: a process held between
     * creation and registration has done its write, and is still there to be accounted for.
     */
    private static Path fakeRsyncWritesThenHoldsOpen(Path dir) throws IOException {
        Path fake = dir.resolve("fake-writing-rsync");
        String script = """
                #!/bin/sh
                if [ "$1" = --version ]; then echo 'rsync version 3.2.7'; exit 0; fi
                src=$(eval echo "\\${$(($#-1))}")
                dst=$(eval echo "\\${$#}")
                cp "$src/a.txt" "$dst/a.txt"
                printf '%s\\n' "$$" > "$dst/process.pid.tmp"
                mv "$dst/process.pid.tmp" "$dst/process.pid"
                printf '%s\\n' '>f+++++++++|a.txt'
                sleep 120
                exit 0
                """;
        Files.writeString(fake, script);
        Files.setPosixFilePermissions(fake, java.util.EnumSet.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        return fake;
    }

    /** An rsync stand-in that records its PID, announces, and copies immediately. */
    private static Path fakeRsyncCopyingAsSoonAsItRuns(Path dir) throws IOException {
        Path fake = dir.resolve("fake-copying-rsync");
        String script = """
                #!/bin/sh
                if [ "$1" = --version ]; then echo 'rsync version 3.2.7'; exit 0; fi
                src=$(eval echo "\\${$(($#-1))}")
                dst=$(eval echo "\\${$#}")
                printf '%s\\n' "$$" > "$dst/process.pid.tmp"
                mv "$dst/process.pid.tmp" "$dst/process.pid"
                printf '%s\\n' '>f+++++++++|a.txt'
                cp "$src/a.txt" "$dst/a.txt"
                exit 0
                """;
        Files.writeString(fake, script);
        Files.setPosixFilePermissions(fake, java.util.EnumSet.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        return fake;
    }

    private static Path fakeRsyncGatedUntilAfterClose(Path dir) throws IOException {
        Path fake = dir.resolve("fake-gated-rsync");
        String script = """
                #!/bin/sh
                if [ "$1" = --version ]; then echo 'rsync version 3.2.7'; exit 0; fi
                src=$(eval echo "\\${$(($#-1))}")
                dst=$(eval echo "\\${$#}")
                printf '%s\\n' "$$" > "$dst/process.pid.tmp"
                mv "$dst/process.pid.tmp" "$dst/process.pid"
                printf '%s\\n' '>f+++++++++|a.txt'
                while [ ! -e "$dst/allow-copy" ]; do sleep 0.05; done
                cp "$src/a.txt" "$dst/a.txt"
                exit 0
                """;
        Files.writeString(fake, script);
        Files.setPosixFilePermissions(fake, java.util.EnumSet.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        return fake;
    }

    // ------------------------------------------------------------------ hashing that keeps reading

    @Test
    void aCancelledSourceHashStopsTheThreadReadingTheFile(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        // Sparse, so the fixture costs a directory entry rather than gigabytes: what matters is that a read of
        // an enormous file is provably still in progress when the job is cancelled - hashing 40 GiB of holes
        // takes well over a minute, so a worker that is still named "hash-source" three seconds after the
        // cancellation was not one that had just finished its work.
        TestSupport.sparse(src.resolve("huge.bin"), 40L * 1024 * 1024 * 1024);

        CountDownLatch hashing = new CountDownLatch(1);
        Recorder recorder = new Recorder();
        ProgressListener watching = new ProgressListener() {
            @Override
            public void onPhase(String phase) {
                if ("hashing source".equals(phase)) {
                    hashing.countDown();
                }
            }

            @Override
            public void onCompletion(JobResult result) {
                recorder.onCompletion(result);
            }
        };
        FileSyncEngine engine = new FileSyncEngine();
        try {
            assertTrue(engine.start(request(src, dst, JobType.COPY_AND_VERIFY, "huge.bin"), watching));
            assertTrue(hashing.await(30, TimeUnit.SECONDS), "the job never reached source hashing");
            Thread.sleep(400L);
            engine.cancel();
            assertTrue(recorder.awaitCompletion(30_000), "the cancelled hashing job never reported");

            JobResult result = recorder.result();
            assertEquals(AbortCause.USER_CANCELLED, result.abortCause(), result.message());
            awaitNoThreadNamed("hash-source", 3_000);
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("Hashing of the source was stopped")),
                    "a hash cut short should be named as such: " + result.warnings());
            TestSupport.assertNoProcessesRunning("rsync", "after cancelling during source hashing");
        } finally {
            engine.close();
        }
    }

    @Test
    void aCancelledVerificationStopsTheThreadReadingTheFile(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.sparse(src.resolve("huge.bin"), 40L * 1024 * 1024 * 1024);

        CountDownLatch verifying = new CountDownLatch(1);
        Recorder recorder = new Recorder();
        ProgressListener watching = new ProgressListener() {
            @Override
            public void onPhase(String phase) {
                if ("verifying".equals(phase)) {
                    verifying.countDown();
                }
            }

            @Override
            public void onCompletion(JobResult result) {
                recorder.onCompletion(result);
            }
        };
        FileSyncEngine engine = new FileSyncEngine();
        try {
            // Verify Only reads the source to compare it, so the read that has to stop is the one in the
            // verification pool, and it starts after the work has been submitted.
            assertTrue(engine.start(request(src, dst, JobType.VERIFY_ONLY, "huge.bin"), watching));
            assertTrue(verifying.await(30, TimeUnit.SECONDS), "the job never reached verification");
            Thread.sleep(400L);
            engine.cancel();
            assertTrue(recorder.awaitCompletion(30_000), "the cancelled verification never reported");

            JobResult result = recorder.result();
            assertEquals(AbortCause.USER_CANCELLED, result.abortCause(), result.message());
            assertEquals(0, result.filesMismatched(),
                    "an interrupted comparison is not a mismatch: " + result.message());
            assertEquals(0, result.filesMissing(),
                    "an interrupted comparison is not a missing file: " + result.message());
            awaitNoThreadNamed("verify", 3_000);
        } finally {
            engine.close();
        }
    }

    // ------------------------------------------------------------------ admission vs finishing

    @Test
    void anAdmittedJobIsNeverLeftDescribingItselfAsIdleByAnEarlierJob(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        Path fakeDir = Files.createDirectories(tmp.resolve("fake"));
        FileSyncEngine engine = new FileSyncEngine();
        try {
            // Two threads, one engine: a job being cancelled, and somebody trying to admit the next one. The
            // invariant is that the engine's own account of itself never contradicts - a finished job used to
            // reopen admission before it had written its status, so a new job's RUNNING could be stamped over
            // with the old job's FAILED.
            for (int round = 0; round < 40; round++) {
                engine.useRsync(TestSupport.fakeRsync(fakeDir, ">f+++++++++|a.txt", 0).toString());
                Recorder first = new Recorder();
                assertTrue(engine.start(request(src, dst, JobType.COPY_ONLY, "a.txt"), first));
                engine.cancel();

                AtomicReference<JobResult> raced = new AtomicReference<>();
                JobRequest contenderRequest = request(src, dst, JobType.COPY_ONLY, "a.txt");
                Thread contender = new Thread(() -> {
                    for (int attempt = 0; attempt < 200; attempt++) {
                        Recorder second = new Recorder();
                        if (engine.start(contenderRequest, second)) {
                            raced.set(second.awaitResultOrNull());
                            return;
                        }
                    }
                });
                contender.start();
                assertTrue(first.awaitCompletion(30_000), "the cancelled job never reported in round " + round);
                contender.join(30_000);

                if (engine.isRunning()) {
                    assertEquals(JobStatus.RUNNING, engine.status(),
                            "round " + round + ": the engine is running but does not say RUNNING");
                } else {
                    assertNotEquals(JobStatus.RUNNING, engine.status(),
                            "round " + round + ": the engine says RUNNING while admitting nothing");
                }
                if (raced.get() != null) {
                    // Whoever won, the engine's terminal state belongs to the job it admitted last.
                    assertEquals(JobStatus.COMPLETED, engine.lastResult().status(),
                            "round " + round + ": an older job's finish overwrote the state of the job that"
                                    + " was admitted after it");
                }
                awaitNotRunning(engine, 30_000);
            }
        } finally {
            engine.close();
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

    @Test
    void aForcedReportNamesTheRowsItLeftOut(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        CountDownLatch stuck = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        FileSyncEngine engine = new FileSyncEngine();
        try {
            // The copy stand-in writes nothing and exits cleanly, so verification has a verdict to report, and
            // the listener blocks on that row. The engine then has to report the job without the rows that
            // thread had reached - and say how many it left out, not merely that some are missing.
            ProgressListener blocking = new ProgressListener() {
                @Override
                public void onFileProcessed(String relPath, String kind, String sourceHash, String destHash) {
                    if ("MISSING".equals(kind) || "MISMATCH".equals(kind) || "OK".equals(kind)) {
                        stuck.countDown();
                        try {
                            released.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }

                @Override
                public void onCompletion(JobResult result) {
                    // The point is the thread that cannot get back to the engine.
                }
            };
            engine.useRsync(TestSupport.fakeRsync(Files.createDirectories(tmp.resolve("fake")),
                    ">f+++++++++|a.txt", 0).toString());
            assertTrue(engine.start(request(src, dst, JobType.COPY_AND_VERIFY, "a.txt"), blocking));
            assertTrue(stuck.await(30, TimeUnit.SECONDS), "the verdict callback never got as far as being blocked");

            engine.cancel();
            awaitIdle(engine, 20_000);
            JobResult forced = engine.lastResult();
            assertNotNull(forced);
            assertEquals(JobStatus.FAILED, forced.status(), forced.message());
            assertEquals(0, forced.rowsListed(), forced.toString());
            assertEquals(1, forced.rowsOmitted(),
                    "the report that replaced the stuck thread says how many rows it left out: " + forced);
            // The confident half of the same sentence: here the process tree really had been stopped and waited
            // for, so the report is entitled to say nothing more is being written.
            assertTrue(forced.message().contains("nothing more is being written"),
                    "a report made with the process known gone no longer says so: " + forced.message());
            assertTrue(forced.truncated(), forced.toString());
        } finally {
            released.countDown();
            engine.close();
        }
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

    private static Object lifecycleLockOf(FileSyncEngine engine) throws Exception {
        java.lang.reflect.Field field = FileSyncEngine.class.getDeclaredField("lifecycleLock");
        field.setAccessible(true);
        return field.get(engine);
    }

    private static void awaitThreadState(Thread thread, Thread.State wanted, long millis)
            throws InterruptedException {
        long until = System.currentTimeMillis() + millis;
        while (thread.getState() != wanted && System.currentTimeMillis() < until) {
            Thread.sleep(20L);
        }
    }

    /** Wait until no live thread still carries this name - a stopped job's hashing threads included. */
    private static void awaitNoThreadNamed(String name, long millis) throws InterruptedException {
        long until = System.currentTimeMillis() + millis;
        List<Thread> alive = liveThreadsNamed(name);
        while (!alive.isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(100L);
            alive = liveThreadsNamed(name);
        }
        assertTrue(alive.isEmpty(), "threads named " + name + " were still working after the job was stopped:"
                + alive);
    }

    private static List<Thread> liveThreadsNamed(String name) {
        List<Thread> found = new java.util.ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread != null && name.equals(thread.getName()) && thread.isAlive()) {
                found.add(thread);
            }
        }
        return found;
    }

    private static void awaitNotRunning(FileSyncEngine engine, long millis) throws InterruptedException {
        long until = System.currentTimeMillis() + millis;
        while (engine.isRunning() && System.currentTimeMillis() < until) {
            Thread.sleep(50L);
        }
        assertFalse(engine.isRunning(), "the engine never became idle");
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

        JobResult awaitResultOrNull() {
            try {
                return completion.await(30, TimeUnit.SECONDS) ? result.get() : null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        JobResult result() {
            return result.get();
        }
    }
}
