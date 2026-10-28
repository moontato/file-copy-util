package com.fileverifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UiJobEventsTest {
    @Test
    void saturationCoalescesStatusBoundsTheQueueAndNeverLosesCompletion() {
        ArrayDeque<Runnable> edt = new ArrayDeque<>();
        List<String> seen = new ArrayList<>();
        long[] dropped = {0};
        FileSyncEngine.JobResult result = new FileSyncEngine.JobResult(
                FileSyncEngine.JobStatus.COMPLETED, "finished", 0, 0, 0, 0, 0, 0, 0,
                List.of(), List.of(), List.of());
        UiJobEvents events = new UiJobEvents(edt::addLast, new UiJobEvents.Sink() {
            @Override public void phase(String phase) { seen.add("phase:" + phase); }
            @Override public void progress(int percent, String text) { seen.add("status:" + text); }
            @Override public void line(String line) { seen.add("line:" + line); }
            @Override public void suppressed(long count) { dropped[0] += count; }
            @Override public void complete(FileSyncEngine.JobResult finished) {
                assertSame(result, finished);
                seen.add("completed");
            }
        });
        events.onPhase("copying");
        events.onPhase("future phase"); // an unfamiliar label must never fail an engine callback
        for (int i = 0; i < 10_000; i++) {
            events.onFileProcessed("file-" + i, "COPIED", null, null);
            events.onProgress(i % 101, "status-" + i);
            assertTrue(events.pendingLines() <= UiJobEvents.MAX_LINES);
            assertEquals(1, edt.size(), "only one drain may be outstanding");
        }
        // Before completion, a single drain delivers the most recent status, regardless of the backlog.
        edt.removeFirst().run();
        assertTrue(seen.contains("status:status-9999"));
        assertTrue(seen.contains("phase:copying"));
        assertTrue(seen.contains("phase:future phase"));
        assertTrue(seen.stream().filter(s -> s.startsWith("line:")).count() <= UiJobEvents.MAX_PER_DRAIN);
        assertEquals(1, edt.size());
        events.onPhase("done");
        events.onCompletion(result);
        events.onProgress(1, "late status");
        events.onFileProcessed("late", "COPIED", null, null);
        assertEquals(1, edt.size());
        edt.removeFirst().run();
        assertEquals("completed", seen.get(seen.size() - 1));
        assertTrue(seen.contains("phase:done"));
        assertFalse(seen.contains("status:late status"));
        assertEquals(10_000 - UiJobEvents.MAX_PER_DRAIN, dropped[0]);
        assertTrue(edt.isEmpty());
        assertFalse(events.drainScheduled());
    }

    @Test
    void oneViewNeverQueuesMoreThanOneDrainAcrossJobGenerations() {
        ArrayDeque<Runnable> edt = new ArrayDeque<>();
        UiDrainScheduler scheduler = new UiDrainScheduler(edt::addLast);
        List<Integer> delivered = new ArrayList<>();
        Object first = new Object();
        scheduler.activate(first);
        scheduler.submit(first, () -> delivered.add(-1));
        for (int i = 0; i < 10_000; i++) {
            Object identity = new Object();
            int value = i;
            scheduler.activate(identity);
            scheduler.submit(identity, () -> delivered.add(value));
            scheduler.submit(first, () -> delivered.add(-2));
            assertEquals(1, edt.size());
        }
        edt.removeFirst().run();
        assertEquals(List.of(9999), delivered);
        assertTrue(edt.isEmpty());
    }

    @Test
    void latestStatusReachesARealEdtAfterAControlledBacklog() throws Exception {
        java.util.concurrent.CountDownLatch occupied = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch delivered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> status = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicLong deliveredAt = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicInteger scheduled = new java.util.concurrent.atomic.AtomicInteger();
        javax.swing.SwingUtilities.invokeLater(() -> {
            occupied.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(occupied.await(5, java.util.concurrent.TimeUnit.SECONDS));
        UiJobEvents events = new UiJobEvents(task -> {
            scheduled.incrementAndGet();
            javax.swing.SwingUtilities.invokeLater(task);
        }, new UiJobEvents.Sink() {
            @Override public void phase(String phase) { }
            @Override public void progress(int percent, String text) {
                status.set(text);
                deliveredAt.set(System.nanoTime());
                delivered.countDown();
            }
            @Override public void line(String line) { }
            @Override public void suppressed(long count) { }
            @Override public void complete(FileSyncEngine.JobResult result) { }
        });
        try {
            for (int i = 0; i < 10_000; i++) {
                events.onFileProcessed("file-" + i, "OK", null, null);
                events.onProgress(10, "status-" + i);
            }
            assertEquals(1, scheduled.get(), "only one drain scheduled while the EDT is blocked");
            assertEquals(UiJobEvents.MAX_LINES, events.pendingLines());
            long start = System.nanoTime();
            release.countDown();
            assertTrue(delivered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("status-9999", status.get());
            System.out.printf("UI measurement: latest status after EDT release %.3f ms (one headless run)%n",
                    (deliveredAt.get() - start) / 1_000_000.0);
        } finally {
            release.countDown();
        }
    }

    @Test
    void anOldDisplayGenerationCannotOverwriteTheNewJobsCompletion() throws Exception {
        java.util.concurrent.atomic.AtomicReference<FileVerifierView> viewRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<UiJobEvents> oldRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<FileSyncEngine.JobResult> finalResult = new java.util.concurrent.atomic.AtomicReference<>();
        FileSyncEngine.JobRequest request = new FileSyncEngine.JobRequest(java.nio.file.Path.of("."),
                java.nio.file.Path.of("."), FileSyncEngine.JobType.VERIFY_ONLY,
                FileSyncEngine.Selection.of(List.of("a")), FileSyncEngine.Selection.none(),
                ScanOptions.DEFAULTS, false, false);
        FileSyncEngine.JobResult oldResult = new FileSyncEngine.JobResult(FileSyncEngine.JobStatus.FAILED,
                "old result", 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of());
        FileSyncEngine.JobResult newResult = new FileSyncEngine.JobResult(FileSyncEngine.JobStatus.COMPLETED,
                "new result", 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of());
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            FileVerifierView view = new FileVerifierView();
            viewRef.set(view);
            UiJobEvents old = view.beginJobDisplay(request);
            oldRef.set(old);
            old.onProgress(90, "old status");
            old.onCompletion(oldResult);
            UiJobEvents newer = view.beginJobDisplay(request);
            newer.onProgress(10, "new status");
            newer.onCompletion(newResult);
            // Both drains are queued behind this EDT callback, with the old drain first.
        });
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            finalResult.set(viewRef.get().completedJobInUse().result());
            oldRef.get().onProgress(1, "late old status");
        });
        javax.swing.SwingUtilities.invokeAndWait(() -> assertSame(newResult, finalResult.get()));
        viewRef.get().shutdown();
    }

    @Test
    void suppressionAndResultTruncationAreReportedAsDifferentFacts() throws Exception {
        java.util.concurrent.atomic.AtomicReference<FileVerifierView> ref = new java.util.concurrent.atomic.AtomicReference<>();
        FileSyncEngine.JobRequest request = new FileSyncEngine.JobRequest(java.nio.file.Path.of("."),
                java.nio.file.Path.of("."), FileSyncEngine.JobType.VERIFY_ONLY,
                FileSyncEngine.Selection.of(List.of("a")), FileSyncEngine.Selection.none(),
                ScanOptions.DEFAULTS, false, false);
        FileSyncEngine.JobResult result = new FileSyncEngine.JobResult(FileSyncEngine.JobStatus.COMPLETED,
                "finished", 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0,
                List.of(new FileSyncEngine.FileOutcome("a", "MISMATCH", null, null)), List.of(), List.of(), 2,
                FileSyncEngine.AbortCause.NONE);
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            FileVerifierView view = new FileVerifierView();
            ref.set(view);
            UiJobEvents events = view.beginJobDisplay(request);
            for (int i = 0; i < 10_000; i++) events.onFileProcessed("file-" + i, "OK", null, null);
            events.onCompletion(result);
        });
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                java.lang.reflect.Field field = FileVerifierView.class.getDeclaredField("tailLog");
                field.setAccessible(true);
                String text = ((TailLog) field.get(ref.get())).text();
                assertTrue(text.contains("10000 live display event(s) suppressed"), text);
                assertTrue(text.contains("2 row(s) are not listed"), text);
                assertTrue(text.contains("mismatched=1"), text);
                assertSame(result, ref.get().completedJobInUse().result());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        ref.get().shutdown();
    }

    @Test
    void queuedTextIsBoundedEvenWhenAnIndividualNameIsVeryLong() {
        ArrayDeque<Runnable> edt = new ArrayDeque<>();
        List<String> lines = new ArrayList<>();
        UiJobEvents events = new UiJobEvents(edt::addLast, new UiJobEvents.Sink() {
            @Override public void phase(String phase) { }
            @Override public void progress(int percent, String text) { }
            @Override public void line(String line) { lines.add(line); }
            @Override public void suppressed(long count) { }
            @Override public void complete(FileSyncEngine.JobResult result) { }
        });
        events.onFileProcessed("a".repeat(100_000), "COPIED", null, null);
        edt.removeFirst().run();
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).length() < UiJobEvents.MAX_LINE_CHARS + 50);
        assertTrue(lines.get(0).contains("display text shortened"));
    }
}
