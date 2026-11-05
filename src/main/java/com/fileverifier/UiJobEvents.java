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
    /**
     * How many queued per-file lines a completion pass still shows ahead of its job summary.
     *
     * <p>A short job used to lose most of its own log: nine verdicts arrived, four lines were displayed, and the
     * completion counted the remaining five as suppressed even though the report said nine matched. Completion now
     * shows the oldest lines that fit in its pass, so a job of a few dozen files reads back whole. A job whose
     * queue is genuinely saturated still has the rest reported as suppressed, and one pass still does no more
     * work than {@link #RETAINED_LINES_AT_COMPLETION} lines plus the summary, so how long completion waits does
     * not grow with the backlog. It stays below {@link #MAX_PER_DRAIN} for that reason.
     */
    static final int RETAINED_LINES_AT_COMPLETION = 32;

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
                // Completion must not eat the per-file lines that arrived before it. The oldest queued lines are
                // delivered in order ahead of the summary, and only what is left over counts as suppressed: the
                // pass is bounded, so a saturated job does not push the completion further behind.
                while (batch.size() < RETAINED_LINES_AT_COMPLETION && !lines.isEmpty()) batch.add(lines.removeFirst());
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
