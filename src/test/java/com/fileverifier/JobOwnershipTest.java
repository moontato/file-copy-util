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
import java.util.Set;
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
    void aJobAskedForWhileTheProgramIsClosingIsEitherRefusedOrStopped(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        Path fakeDir = Files.createDirectories(tmp.resolve("fake"));

        // The reproduced race: a job is being admitted exactly as the program closes. Only two endings are
        // acceptable - the close was marked first and the job is refused, or the job got in and the close
        // captured and stopped it. What must never happen is a close that returned having never seen the job,
        // which then copies with the watchdog already retired.
        for (int round = 0; round < 40; round++) {
            // A destination of its own per round, so a file left by one round can never be mistaken for a
            // file written by the next one.
            Path roundDest = Files.createDirectories(dst.resolve("r" + round));
            FileSyncEngine engine = new FileSyncEngine();
            engine.useRsync(TestSupport.fakeRsync(fakeDir, ">f+++++++++|a.txt", 0).toString());
            engine.setJobTimeoutMillis(20_000L);
            JobRequest request = request(src, roundDest, JobType.COPY_ONLY, "a.txt");
            AtomicReference<JobResult> outcome = new AtomicReference<>();
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicBoolean runningAtClose = new AtomicBoolean();
            AtomicBoolean copiedAtClose = new AtomicBoolean();
            final int r = round;

            Thread caller = new Thread(() -> {
                try {
                    outcome.set(engine.execute(request, null));
                } catch (Throwable t) {
                    thrown.set(t);
                }
            }, "caller-" + r);
            Thread closer = new Thread(() -> {
                engine.closeOnce();
                runningAtClose.set(engine.isRunning());
                copiedAtClose.set(Files.exists(roundDest.resolve("a.txt")));
            }, "closer-" + r);
            caller.start();
            closer.start();
            caller.join(30_000);
            closer.join(30_000);
            assertFalse(caller.isAlive(), "round " + round + ": the caller never came back");
            assertFalse(closer.isAlive(), "round " + round + ": the close never came back");

            JobResult result = outcome.get();
            if (result == null) {
                assertTrue(thrown.get() instanceof IllegalStateException,
                        "round " + round + ": refused, but not with the reason: " + thrown.get());
            } else {
                if (result.message().startsWith("Another job in this program is already using the destination")) {
                    fail("round " + round + ": the destination stayed claimed with no job holding it: "
                            + result.message());
                }
                if (result.status() == JobStatus.COMPLETED && !copiedAtClose.get()) {
                    fail("round " + round + ": close() had already returned with nothing copied, and the job"
                            + " then copied anyway - close missed the controller it had admitted. running at"
                            + " close=" + runningAtClose.get() + ", result=" + result);
                }
            }
            assertFalse(engine.isRunning(), "round " + round + ": the engine is still running after closing");
            assertFalse(Files.exists(roundDest.resolve("a.txt")) && !copiedAtClose.get(),
                    "round " + round + ": a file appeared after close() returned");
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
