package com.fileverifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Bounded, display-only handoff from one job to the EDT. The JobResult remains authoritative. */
final class UiJobEvents implements FileSyncEngine.ProgressListener {
    static final int MAX_LINES = 256;
    static final int MAX_LINE_CHARS = 2048;
    static final int MAX_PER_DRAIN = 64;

    interface Sink {
        void phase(String phase);
        void progress(int percent, String text);
        void line(String line);
        void suppressed(long count);
        void complete(FileSyncEngine.JobResult result);
    }

    private final Consumer<Runnable> schedule;
    private final Sink sink;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    // These are the engine's finite ordered phase transitions, not per-file events.
    private final boolean[] phases = new boolean[4];
    private boolean dryRun;
    private String otherPhase;
    private int percent;
    private String status;
    private long dropped;
    private boolean scheduled;
    private boolean terminal;
    private FileSyncEngine.JobResult result;

    UiJobEvents(Consumer<Runnable> schedule, Sink sink) {
        this.schedule = schedule;
        this.sink = sink;
    }

    private void enqueue() {
        if (!scheduled) {
            scheduled = true;
            schedule.accept(this::drain);
        }
    }

    @Override
    public synchronized void onPhase(String phase) {
        if (terminal) return;
        int index = switch (phase) {
            case "hashing source" -> 0;
            case "copying", "dry run" -> 1;
            case "verifying" -> 2;
            case "done" -> 3;
            default -> -1;
        };
        if (index < 0) {
            // A future engine phase must not turn a display callback into a failed job.
            otherPhase = clip(phase);
            enqueue();
            return;
        }
        phases[index] = true;
        if ("dry run".equals(phase)) dryRun = true;
        enqueue();
    }

    @Override
    public synchronized void onProgress(int value, String text) {
        if (terminal) return;
        percent = value;
        status = clip(text);
        enqueue();
    }

    @Override
    public synchronized void onFileProcessed(String path, String kind, String sourceHash, String destHash) {
        if (terminal) return;
        if (lines.size() == MAX_LINES) {
            dropped++;
        } else {
            lines.addLast(clip(FileVerifierView.formatFileLine(kind, path, sourceHash, destHash)));
        }
        enqueue();
    }

    @Override
    public synchronized void onCompletion(FileSyncEngine.JobResult value) {
        if (terminal) return;
        terminal = true;
        result = value;
        enqueue();
    }

    private static String clip(String value) {
        if (value == null || value.length() <= MAX_LINE_CHARS) return value;
        return value.substring(0, MAX_LINE_CHARS) + "… [display text shortened]";
    }

    private void drain() {
        List<String> batch = new ArrayList<>();
        List<String> phaseBatch = new ArrayList<>();
        String currentStatus;
        int currentPercent;
        long suppressed;
        FileSyncEngine.JobResult completed;
        synchronized (this) {
            String[] names = {"hashing source", dryRun ? "dry run" : "copying", "verifying", "done"};
            for (int i = 0; i < phases.length; i++) {
                if (phases[i]) { phaseBatch.add(names[i]); phases[i] = false; }
            }
            if (otherPhase != null) {
                phaseBatch.add(otherPhase);
                otherPhase = null;
            }
            currentStatus = status;
            currentPercent = percent;
            status = null;
            completed = result;
            result = null;
            if (completed != null) {
                // Do not let a saturated live log hold completion behind thousands of display lines.
                dropped += lines.size();
                lines.clear();
            } else {
                while (batch.size() < MAX_PER_DRAIN && !lines.isEmpty()) batch.add(lines.removeFirst());
            }
            suppressed = dropped;
            dropped = 0;
        }
        for (String phase : phaseBatch) sink.phase(phase);
        if (currentStatus != null) sink.progress(currentPercent, currentStatus);
        for (String line : batch) sink.line(line);
        if (suppressed > 0) sink.suppressed(suppressed);
        if (completed != null) sink.complete(completed);
        synchronized (this) {
            if (!lines.isEmpty() || status != null || result != null || dropped != 0 || hasPhases()) {
                schedule.accept(this::drain);
            } else {
                scheduled = false;
            }
        }
    }

    private boolean hasPhases() {
        for (boolean phase : phases) if (phase) return true;
        return otherPhase != null;
    }

    synchronized int pendingLines() { return lines.size(); }
    synchronized boolean drainScheduled() { return scheduled; }
}
