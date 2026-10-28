package com.fileverifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class UiCancellationTest {
    @Test
    void cancellingTermResistantProcessDoesNotBlockTheEdt(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path dst = Files.createDirectory(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a"), "x");
        Path ready = tmp.resolve("ready");
        Path fake = tmp.resolve("rsync");
        Files.writeString(fake, "#!/bin/sh\n"
                + "if [ \"$1\" = --version ]; then echo 'rsync version 3.2.7 protocol version 31'; exit 0; fi\n"
                + "trap '' TERM\necho $$ > '" + ready + ".tmp'\nmv '" + ready + ".tmp' '" + ready
                + "'\nwhile :; do sleep 1; done\n");
        fake.toFile().setExecutable(true);
        AtomicReference<FileVerifierView> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> ref.set(new FileVerifierView()));
        FileVerifierView view = ref.get();
        FileSyncEngine engine = view.engineInUse();
        engine.useRsync(fake.toString());
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<FileSyncEngine.JobResult> result = new AtomicReference<>();
        FileSyncEngine.JobRequest request = TestSupport.request(src, dst, FileSyncEngine.JobType.COPY_ONLY,
                TestSupport.selection("a"), TestSupport.none());
        try {
            assertTrue(engine.start(request, r -> { result.set(r); completed.countDown(); }));
            assertTrue(TestSupport.waitFor(() -> Files.exists(ready), 10_000), "fake rsync did not start");
            long pid = Long.parseLong(Files.readString(ready).trim());
            assertTrue(TestSupport.waitFor(() -> ProcessHandle.of(pid)
                    .map(p -> p.descendants().findAny().isPresent()).orElse(false), 5_000));
            List<ProcessHandle> descendants = ProcessHandle.of(pid).orElseThrow().descendants().toList();
            java.lang.reflect.Method cancel = FileVerifierView.class.getDeclaredMethod("requestCancel");
            cancel.setAccessible(true);
            CountDownLatch edtReturned = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicLong elapsed = new java.util.concurrent.atomic.AtomicLong();
            SwingUtilities.invokeLater(() -> {
                long start = System.nanoTime();
                try { cancel.invoke(view); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                elapsed.set(System.nanoTime() - start);
                edtReturned.countDown();
            });
            assertTrue(edtReturned.await(1, TimeUnit.SECONDS), "EDT blocked on process termination");
            System.out.printf("UI measurement: cancel handler on EDT %.3f ms (one TERM-resistant process)%n",
                    elapsed.get() / 1_000_000.0);
            assertTrue(completed.await(15, TimeUnit.SECONDS), "cancelled job did not terminate");
            assertEquals(FileSyncEngine.AbortCause.USER_CANCELLED, result.get().abortCause());
            assertTrue(TestSupport.waitFor(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), 5_000),
                    "the owned TERM-resistant process survived cancellation");
            assertTrue(TestSupport.waitFor(() -> descendants.stream().noneMatch(ProcessHandle::isAlive), 5_000),
                    "a previously observed descendant survived cancellation");
        } finally {
            view.shutdown();
        }
    }

    @Test
    void closingTermResistantProcessLeavesEdtAvailableUntilCleanupFinishes(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path dst = Files.createDirectory(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a"), "x");
        Path ready = tmp.resolve("ready");
        Path fake = tmp.resolve("rsync");
        Files.writeString(fake, "#!/bin/sh\n"
                + "if [ \"$1\" = --version ]; then echo 'rsync version 3.2.7 protocol version 31'; exit 0; fi\n"
                + "trap '' TERM\necho $$ > '" + ready + ".tmp'\nmv '" + ready + ".tmp' '" + ready
                + "'\nwhile :; do sleep 1; done\n");
        fake.toFile().setExecutable(true);
        AtomicReference<FileVerifierView> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> ref.set(new FileVerifierView()));
        FileVerifierView view = ref.get();
        FileSyncEngine engine = view.engineInUse();
        engine.useRsync(fake.toString());
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<FileSyncEngine.JobResult> result = new AtomicReference<>();
        FileSyncEngine.JobRequest request = TestSupport.request(src, dst, FileSyncEngine.JobType.COPY_ONLY,
                TestSupport.selection("a"), TestSupport.none());
        CountDownLatch closed = new CountDownLatch(1);
        try {
            assertTrue(engine.start(request, r -> { result.set(r); completed.countDown(); }));
            assertTrue(TestSupport.waitFor(() -> Files.exists(ready), 10_000));
            long pid = Long.parseLong(Files.readString(ready).trim());
            assertTrue(TestSupport.waitFor(() -> ProcessHandle.of(pid)
                    .map(p -> p.descendants().findAny().isPresent()).orElse(false), 5_000));
            List<ProcessHandle> descendants = ProcessHandle.of(pid).orElseThrow().descendants().toList();
            CountDownLatch returned = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicLong elapsed = new java.util.concurrent.atomic.AtomicLong();
            SwingUtilities.invokeLater(() -> {
                long start = System.nanoTime();
                view.shutdownAsync(closed::countDown);
                elapsed.set(System.nanoTime() - start);
                returned.countDown();
            });
            assertTrue(returned.await(1, TimeUnit.SECONDS), "EDT blocked on close");
            System.out.printf("UI measurement: close handler on EDT %.3f ms (one TERM-resistant process)%n",
                    elapsed.get() / 1_000_000.0);
            assertFalse(engine.start(request, ignored -> { }), "close must shut admission before returning");
            assertTrue(closed.await(15, TimeUnit.SECONDS), "cleanup did not finish");
            assertTrue(completed.await(5, TimeUnit.SECONDS), "terminal result was not delivered");
            assertEquals(FileSyncEngine.AbortCause.ENGINE_CLOSED, result.get().abortCause());
            assertTrue(TestSupport.waitFor(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), 5_000));
            assertTrue(TestSupport.waitFor(() -> descendants.stream().noneMatch(ProcessHandle::isAlive), 5_000),
                    "a previously observed descendant survived close");
        } finally {
            engine.close();
        }
    }

    @Test
    void repeatedEscapeAndDirectCancellationScheduleOnceAndCloseStillCleansUp(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path dst = Files.createDirectory(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a"), "x");
        Path ready = tmp.resolve("ready");
        Path fake = tmp.resolve("rsync");
        Files.writeString(fake, "#!/bin/sh\n"
                + "if [ \"$1\" = --version ]; then echo 'rsync version 3.2.7 protocol version 31'; exit 0; fi\n"
                + "trap '' TERM\necho $$ > '" + ready + ".tmp'\nmv '" + ready + ".tmp' '" + ready
                + "'\nwhile :; do sleep 1; done\n");
        fake.toFile().setExecutable(true);
        AtomicReference<FileVerifierView> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> ref.set(new FileVerifierView()));
        FileVerifierView view = ref.get();
        FileSyncEngine engine = view.engineInUse();
        engine.useRsync(fake.toString());
        List<Runnable> scheduled = new java.util.ArrayList<>();
        engine.setCancellationWorkerLauncher(scheduled::add);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<FileSyncEngine.JobResult> result = new AtomicReference<>();
        FileSyncEngine.JobRequest request = TestSupport.request(src, dst, FileSyncEngine.JobType.COPY_ONLY,
                TestSupport.selection("a"), TestSupport.none());
        CountDownLatch closed = new CountDownLatch(1);
        try {
            assertTrue(engine.start(request, r -> { result.set(r); completed.countDown(); }));
            assertTrue(TestSupport.waitFor(() -> Files.exists(ready), 10_000));
            long pid = Long.parseLong(Files.readString(ready).trim());
            assertTrue(TestSupport.waitFor(() -> ProcessHandle.of(pid)
                    .map(p -> p.descendants().findAny().isPresent()).orElse(false), 5_000));
            List<ProcessHandle> descendants = ProcessHandle.of(pid).orElseThrow().descendants().toList();
            SwingUtilities.invokeAndWait(() -> {
                Object binding = view.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                        .get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0));
                assertEquals("cancel", binding);
                javax.swing.Action escape = view.getActionMap().get(binding);
                for (int i = 0; i < 64; i++) {
                    escape.actionPerformed(new java.awt.event.ActionEvent(view, 0, "escape"));
                }
                // A direct caller must be coalesced as well, independently of the button's enabled state.
                for (int i = 0; i < 64; i++) assertTrue(engine.cancelInBackground());
            });
            // The launcher deliberately holds all scheduled work: there is no timing race with process exit.
            assertTrue(engine.isRunning());
            int scheduledBeforeClose = scheduled.size();
            SwingUtilities.invokeAndWait(() -> view.shutdownAsync(closed::countDown));
            assertTrue(closed.await(15, TimeUnit.SECONDS), "close must perform its own cleanup");
            assertTrue(completed.await(5, TimeUnit.SECONDS), "terminal result was not delivered");
            assertEquals(FileSyncEngine.AbortCause.USER_CANCELLED, result.get().abortCause(),
                    "close must not overwrite the first cancellation cause");
            assertTrue(TestSupport.waitFor(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), 5_000));
            assertTrue(TestSupport.waitFor(() -> descendants.stream().noneMatch(ProcessHandle::isAlive), 5_000));
            assertEquals(1, scheduledBeforeClose, "one cancellation worker per controller, not per key press");
            // A worker delayed beyond closing is still tied to the same controller and can safely complete.
            scheduled.get(0).run();
        } finally {
            engine.close();
        }
    }

    @Test
    void delayedCancellationWorkerCannotStopTheNextJobAndTheNextJobGetsItsOwnWorker(@TempDir Path tmp)
            throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path dst = Files.createDirectory(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a"), "x");
        Path ready = tmp.resolve("ready");
        Path fake = tmp.resolve("rsync");
        Files.writeString(fake, "#!/bin/sh\n"
                + "if [ \"$1\" = --version ]; then echo 'rsync version 3.2.7 protocol version 31'; exit 0; fi\n"
                + "trap '' TERM\necho $$ > '" + ready + ".tmp'\nmv '" + ready + ".tmp' '" + ready
                + "'\nwhile :; do sleep 1; done\n");
        fake.toFile().setExecutable(true);
        FileSyncEngine engine = new FileSyncEngine();
        engine.useRsync(fake.toString());
        List<Runnable> scheduled = new java.util.ArrayList<>();
        engine.setCancellationWorkerLauncher(scheduled::add);
        FileSyncEngine.JobRequest request = TestSupport.request(src, dst, FileSyncEngine.JobType.COPY_ONLY,
                TestSupport.selection("a"), TestSupport.none());
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        AtomicReference<FileSyncEngine.JobResult> secondResult = new AtomicReference<>();
        try {
            assertTrue(engine.start(request, ignored -> firstDone.countDown()));
            assertTrue(TestSupport.waitFor(() -> Files.exists(ready), 10_000));
            assertTrue(engine.cancelInBackground());
            assertEquals(1, scheduled.size());
            // Terminate the first job through the synchronous API while its UI worker remains held.
            assertTrue(engine.cancel());
            assertTrue(firstDone.await(15, TimeUnit.SECONDS));
            Files.delete(ready);
            assertTrue(engine.start(request, r -> { secondResult.set(r); secondDone.countDown(); }));
            assertTrue(TestSupport.waitFor(() -> Files.exists(ready), 10_000));
            long secondPid = Long.parseLong(Files.readString(ready).trim());
            scheduled.get(0).run();
            assertTrue(engine.isRunning(), "the delayed old worker cannot terminate the new job");
            assertTrue(ProcessHandle.of(secondPid).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(engine.cancelInBackground());
            assertEquals(2, scheduled.size(), "a new controller must be able to schedule its own worker");
            scheduled.get(1).run();
            assertTrue(secondDone.await(15, TimeUnit.SECONDS));
            assertEquals(FileSyncEngine.AbortCause.USER_CANCELLED, secondResult.get().abortCause());
            assertTrue(TestSupport.waitFor(() -> ProcessHandle.of(secondPid).map(p -> !p.isAlive()).orElse(true),
                    5_000));
        } finally {
            engine.close();
        }
    }

    @Test
    void backgroundCloseClosesAdmissionBeforeReturning() throws Exception {
        FileSyncEngine engine = new FileSyncEngine();
        CountDownLatch finished = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> engine.closeInBackground(finished::countDown));
        FileSyncEngine.JobRequest request = new FileSyncEngine.JobRequest(Path.of("."), Path.of("."),
                FileSyncEngine.JobType.VERIFY_ONLY, FileSyncEngine.Selection.of(List.of("a")),
                FileSyncEngine.Selection.none(), ScanOptions.DEFAULTS, false, false);
        assertFalse(engine.start(request, ignored -> { }));
        assertTrue(finished.await(10, TimeUnit.SECONDS));
    }
}
