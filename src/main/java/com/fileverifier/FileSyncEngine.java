package com.fileverifier;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Copy/verify engine with an owned job lifecycle. Free of any UI dependency so it can be driven
 * headlessly (see {@code FileSyncEngineTest} / {@code FileSyncEngineLifecycleTest}).
 *
 * <p><b>Path model.</b> Every file is identified by its {@link FileEntry#relPath()} - relative
 * to the scanned root, '/' separated. No absolute-path string arithmetic happens here.
 *
 * <p><b>Selection semantics.</b> Callers pass the *selected* relative paths. An empty set means
 * "zero files", never "no filter"; an empty work set is refused. Copying everything requires an
 * explicit Select All.
 *
 * <p><b>Work set.</b> COPY_AND_VERIFY: the source-side selection. VERIFY_ONLY: the source-side
 * selection, minus files whose row in the destination list the user explicitly unticked (see
 * {@link Selection}) - a file that is simply absent from the destination stays in the work set and
 * is reported as "missing".
 *
 * <p><b>Counters are measured.</b> {@code filesCopied} counts rsync itemize transfer lines;
 * matched/mismatched/missing come from hashing exactly the work set, so
 * {@code filesChecked == filesMatched + filesMismatched + filesMissing}.
 *
 * <p><b>Job lifecycle.</b> One engine owns at most one job: {@link JobStatus} moves
 * IDLE -&gt; RUNNING -&gt; COMPLETED/FAILED. {@link #start} is refused while a job runs,
 * {@link #cancel} terminates the live {@link Process} (and its descendants), and a watchdog
 * enforces an optional job timeout.
 */
public final class FileSyncEngine implements AutoCloseable {

    public enum JobType {
        COPY_AND_VERIFY,
        VERIFY_ONLY
    }

    public enum JobStatus {
        IDLE,
        RUNNING,
        COMPLETED,
        FAILED
    }

    /** Copy phase occupies 0..COPY_PROGRESS_END of the bar, verification the remainder. */
    static final int COPY_PROGRESS_END = 70;

    /**
     * rsync announces every itemize line before it moves the data (verified against rsync 3.2.7),
     * so announcements alone can only carry the bar this far; the rest of the copy phase is earned
     * when rsync actually exits.
     */
    static final int ANNOUNCE_PROGRESS_END = 40;

    /** How often the watchdog checks the job timeout. */
    static final long WATCHDOG_PERIOD_MS = 250;

    /** How long rsync may run before the watchdog terminates it; 0 disables the timeout. */
    public static final long NO_TIMEOUT = 0L;

    /**
     * What the user's list looks like for one side: which rows are ticked, and which rows exist at
     * all. {@code known} is what makes "unticked" distinguishable from "not in the list" - without
     * it, a stale list would silently exclude files from verification.
     */
    public record Selection(Set<String> selected, Set<String> known) {
        public static Selection of(String... relPaths) {
            Set<String> s = new LinkedHashSet<>(java.util.Arrays.asList(relPaths));
            return new Selection(s, s);
        }

        public static Selection none() {
            return new Selection(Set.of(), Set.of());
        }
    }

    /**
     * Outcome of one job. For a FAILED job (cancelled, timed out, or rsync error) the counters are
     * the partial progress that was actually reached, not zero.
     *
     * <p>{@code filesCopied} counts files rsync reported as transferring. When the job completes
     * that equals the number transferred; for a cancelled or timed-out job it can overstate what
     * actually finished, because rsync announces a file before its data has moved.
     */
    public record JobResult(JobStatus status, String message, int filesChecked, int filesMatched,
                            int filesMismatched, int filesMissing, int filesCopied, int rsyncExitCode,
                            List<String> errors, List<String> warnings) {

        public boolean isSuccess() {
            return status == JobStatus.COMPLETED;
        }
    }

    /**
     * Callbacks are invoked from the engine's worker thread; implementations must marshal
     * to the UI thread themselves.
     */
    public interface ProgressListener {
        /** Coarse, log-worthy milestone (job start, phase change, rsync finished). */
        default void onPhase(String message) {
        }

        /** Progress bar + status label only; never log-worthy (see onPhase). */
        default void onProgress(int percent, String statusText) {
        }

        /** Per-file result: status is one of copied | match | mismatch | missing. */
        default void onFileProcessed(String relPath, String status, String srcHash, String dstHash) {
        }

        void onCompletion(JobResult result);
    }

    private final ExecutorService jobExecutor;
    private final ScheduledExecutorService watchdogExecutor;

    private final AtomicBoolean jobRunning = new AtomicBoolean(false);
    private final AtomicReference<JobStatus> status = new AtomicReference<>(JobStatus.IDLE);
    private final AtomicReference<JobResult> lastResult = new AtomicReference<>();

    private volatile Process process;
    private volatile boolean cancelRequested;
    private volatile boolean timedOut;
    private volatile long jobStartedAtMs;
    private volatile long jobTimeoutMs = NO_TIMEOUT;

    private volatile String lastActivity = "waiting to start";
    private final AtomicLong lastActivityAtMs = new AtomicLong(System.currentTimeMillis());

    public FileSyncEngine() {
        jobExecutor = Executors.newSingleThreadExecutor(daemonFactory("file-copy-util-job"));
        watchdogExecutor = Executors.newSingleThreadScheduledExecutor(daemonFactory("file-copy-util-watchdog"));
    }

    private static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    // ---------------------------------------------------------------- lifecycle

    public JobStatus status() {
        return status.get();
    }

    public boolean isRunning() {
        return status.get() == JobStatus.RUNNING;
    }

    public JobResult lastResult() {
        return lastResult.get();
    }

    /** @param millis maximum job duration, or {@link #NO_TIMEOUT} (default). */
    public void setJobTimeoutMillis(long millis) {
        this.jobTimeoutMs = Math.max(NO_TIMEOUT, millis);
    }

    public long jobTimeoutMillis() {
        return jobTimeoutMs;
    }

    /** Milliseconds since the engine last saw rsync output or hashed a file (liveness display). */
    public long millisSinceLastActivity() {
        return System.currentTimeMillis() - lastActivityAtMs.get();
    }

    public String lastActivityDescription() {
        return lastActivity;
    }

    /**
     * Starts a job on the engine's worker thread.
     *
     * @return false if a job is already running on this engine
     */
    public boolean start(File sourceDir, File destDir, JobType jobType,
                         Selection sourceSelection, Selection destSelection,
                         ProgressListener listener) {
        if (!jobRunning.compareAndSet(false, true)) {
            return false;
        }
        status.set(JobStatus.RUNNING);
        try {
            jobExecutor.submit(() -> runGuarded(sourceDir, destDir, jobType,
                    sourceSelection, destSelection, listener));
            return true;
        } catch (RejectedExecutionException e) {
            jobRunning.set(false);
            status.set(JobStatus.IDLE);
            return false;
        }
    }

    /**
     * Requests cancellation of the running job: the flag is set and the live rsync process (plus
     * its descendants) is killed. The job then completes through {@link ProgressListener#onCompletion}
     * with a FAILED result carrying the counters gathered so far.
     *
     * @return true if a job was running
     */
    public boolean cancel() {
        if (!isRunning()) {
            return false;
        }
        cancelRequested = true;
        markActivity("cancellation requested");
        killProcess();
        return true;
    }

    /** Runs the job on the calling thread. Throws if another job is already running. */
    public JobResult execute(File sourceDir, File destDir, JobType jobType,
                             Selection sourceSelection, Selection destSelection,
                             ProgressListener listener) {
        if (!jobRunning.compareAndSet(false, true)) {
            throw new IllegalStateException("A job is already running on this engine");
        }
        status.set(JobStatus.RUNNING);
        return runGuarded(sourceDir, destDir, jobType, sourceSelection, destSelection, listener);
    }

    /** One-shot convenience for headless callers: private engine, synchronous run, shut down. */
    public static JobResult runOnce(File sourceDir, File destDir, JobType jobType,
                                    Selection sourceSelection, Selection destSelection,
                                    ProgressListener listener) {
        try (FileSyncEngine engine = new FileSyncEngine()) {
            return engine.execute(sourceDir, destDir, jobType, sourceSelection, destSelection, listener);
        }
    }

    @Override
    public void close() {
        cancel();
        jobExecutor.shutdownNow();
        watchdogExecutor.shutdownNow();
        try {
            jobExecutor.awaitTermination(5, TimeUnit.SECONDS);
            watchdogExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (status.get() == JobStatus.RUNNING) {
            status.set(JobStatus.FAILED);
        }
        jobRunning.set(false);
    }

    private JobResult runGuarded(File sourceDir, File destDir, JobType jobType,
                                 Selection sourceSelection, Selection destSelection,
                                 ProgressListener listener) {
        cancelRequested = false;
        timedOut = false;
        jobStartedAtMs = System.currentTimeMillis();
        markActivity("starting");
        status.set(JobStatus.RUNNING);

        ScheduledFuture<?> watchdog = watchdogExecutor.scheduleAtFixedRate(
                this::watchdogTick, WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
        JobResult result = null;
        try {
            result = doExecute(sourceDir, destDir, jobType, sourceSelection, destSelection, listener);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = failure("Job interrupted: " + e.getMessage(), List.of(String.valueOf(e.getMessage())), 0);
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            result = failure("Job failed: " + e.getMessage(), List.of(String.valueOf(e.getMessage())), 0);
        } finally {
            watchdog.cancel(false);
            process = null;
            if (result == null) {
                result = failure("Job aborted unexpectedly.", List.of(), 0);
            }
            lastResult.set(result);
            status.set(result.status());
            jobRunning.set(false);
        }
        listener.onCompletion(result);
        return result;
    }

    private void watchdogTick() {
        if (!isRunning()) {
            return;
        }
        long timeout = jobTimeoutMs;
        if (timeout > NO_TIMEOUT && System.currentTimeMillis() - jobStartedAtMs > timeout) {
            timedOut = true;
            markActivity("timeout after " + timeout + " ms");
            killProcess();
        }
    }

    private void killProcess() {
        Process live = process;
        if (live == null || !live.isAlive()) {
            return;
        }
        try {
            live.descendants().forEach(handle -> handle.destroyForcibly());
        } catch (RuntimeException ignored) {
            // best effort
        }
        live.destroyForcibly();
    }

    private void markActivity(String description) {
        lastActivity = description;
        lastActivityAtMs.set(System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- job body

    private JobResult doExecute(File sourceDir, File destDir, JobType jobType,
                                Selection sourceSelection, Selection destSelection,
                                ProgressListener listener)
            throws IOException, InterruptedException, NoSuchAlgorithmException {

        if (sourceDir == null || destDir == null) {
            return failure("Both source and destination directories must be set.", List.of(), 0);
        }
        Path source = sourceDir.toPath().toAbsolutePath().normalize();
        Path dest = destDir.toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            return failure("Source directory does not exist: " + source, List.of(), 0);
        }
        if (!Files.isDirectory(dest)) {
            return failure("Destination directory does not exist: " + dest, List.of(), 0);
        }
        if (source.equals(dest)) {
            return failure("Source and destination are the same directory: " + source, List.of(), 0);
        }

        DirectoryScanner.ScanResult scan;
        try {
            scan = DirectoryScanner.scanFiles(source);
        } catch (UncheckedIOException e) {
            return failure("Could not scan source directory " + source + ": " + e.getMessage(),
                    List.of(String.valueOf(e.getMessage())), 0);
        }

        Selection src = normalize(sourceSelection);
        Selection dst = normalize(destSelection);

        // Verify Only honours unticking a row in the destination list, but only for a row the
        // user's list actually contains. A file that is merely absent from a stale destination
        // list is not a deliberate untick, and must still be checked (this is also the "missing"
        // case, so it has to stay reachable).
        List<FileEntry> workSet = new ArrayList<>();
        for (FileEntry entry : scan.files()) {
            String rel = entry.relPath();
            if (!src.selected().contains(rel)) {
                continue;
            }
            if (jobType == JobType.VERIFY_ONLY
                    && dst.known().contains(rel)
                    && !dst.selected().contains(rel)) {
                continue;
            }
            workSet.add(entry);
        }

        List<String> warnings = new ArrayList<>(scan.warnings());
        if (workSet.isEmpty()) {
            return failure("Nothing to do: the selection is empty, so no files were copied or verified. "
                            + "Use Select All to include every file.",
                    List.of("Empty selection (" + scan.files().size() + " file(s) in source)."), 0);
        }

        listener.onPhase("Work set: " + workSet.size() + " selected file(s) of "
                + scan.files().size() + " in source"
                + (scan.ignoredDirs() > 0 ? "; " + scan.ignoredDirs() + " ignored dir(s)" : "")
                + (scan.skippedLinks() > 0 ? "; " + scan.skippedLinks() + " symlink(s) skipped" : ""));

        int copied = 0;
        int rsyncExitCode = 0;
        List<String> errors = new ArrayList<>();

        if (jobType == JobType.COPY_AND_VERIFY) {
            listener.onPhase("Copying " + workSet.size() + " file(s) with rsync...");
            RsyncRun run = runRsync(source, dest, workSet, listener);
            copied = run.filesCopied();
            rsyncExitCode = run.exitCode();
            errors.addAll(run.errors());
            warnings.addAll(run.warnings());

            if (cancelRequested) {
                return cancelled(copied, 0, 0, 0, 0, rsyncExitCode);
            }
            if (timedOut) {
                return timeoutResult(copied, 0, 0, 0, 0, rsyncExitCode);
            }
            if (run.aborted()) {
                return failure("rsync failed with exit code " + run.exitCode() + ".",
                        run.errors(), run.exitCode());
            }
            listener.onPhase("rsync finished (exit " + run.exitCode() + "), " + copied
                    + " file(s) transferred.");
        }

        int matched = 0;
        int mismatched = 0;
        int missing = 0;
        int done = 0;
        boolean logMatches = jobType == JobType.VERIFY_ONLY;

        listener.onPhase("Verifying " + workSet.size() + " file(s)...");
        ProgressReporter progress = jobType == JobType.COPY_AND_VERIFY
                ? new ProgressReporter(listener, workSet.size(), COPY_PROGRESS_END, 100 - COPY_PROGRESS_END)
                : new ProgressReporter(listener, workSet.size(), 0, 100);

        for (FileEntry entry : workSet) {
            if (cancelRequested) {
                return cancelled(copied, done, matched, mismatched, missing, rsyncExitCode);
            }
            if (timedOut) {
                return timeoutResult(copied, done, matched, mismatched, missing, rsyncExitCode);
            }

            String rel = entry.relPath();
            String srcHash;
            try {
                srcHash = FileHasher.computeHash(entry.absolutePath());
            } catch (IOException e) {
                missing++;
                warnings.add("Source file could not be read (vanished during job?): " + rel);
                listener.onFileProcessed(rel, "missing", "", "");
                progress.update(++done, statusText(done, workSet.size(), matched, mismatched, missing));
                markActivity("verified " + rel);
                continue;
            }

            Path destFile = dest.resolve(rel);
            if (!Files.isRegularFile(destFile)) {
                missing++;
                listener.onFileProcessed(rel, "missing", FileHasher.shortHash(srcHash), "");
            } else {
                String destHash = FileHasher.computeHash(destFile);
                if (destHash.equals(srcHash)) {
                    matched++;
                    if (logMatches) {
                        listener.onFileProcessed(rel, "match", FileHasher.shortHash(srcHash),
                                FileHasher.shortHash(destHash));
                    }
                } else {
                    mismatched++;
                    listener.onFileProcessed(rel, "mismatch", FileHasher.shortHash(srcHash),
                            FileHasher.shortHash(destHash));
                }
            }
            progress.update(++done, statusText(done, workSet.size(), matched, mismatched, missing));
            markActivity("verified " + rel);

            // A single large hash can overrun the deadline; catch it as soon as the work unit ends
            // instead of waiting for the next iteration (or never).
            if (cancelRequested) {
                return cancelled(copied, done, matched, mismatched, missing, rsyncExitCode);
            }
            if (timedOut) {
                return timeoutResult(copied, done, matched, mismatched, missing, rsyncExitCode);
            }
        }
        progress.complete(statusText(done, workSet.size(), matched, mismatched, missing));

        if (cancelRequested) {
            return cancelled(copied, done, matched, mismatched, missing, rsyncExitCode);
        }
        if (timedOut) {
            return timeoutResult(copied, done, matched, mismatched, missing, rsyncExitCode);
        }

        String message = jobType == JobType.COPY_AND_VERIFY
                ? String.format("Copy & Verify Complete: %d file(s) checked, %d copied, %d matched, %d mismatched, %d missing",
                    workSet.size(), copied, matched, mismatched, missing)
                : String.format("Verification Complete: %d file(s) checked, %d matched, %d mismatched, %d missing",
                    workSet.size(), matched, mismatched, missing);

        return new JobResult(JobStatus.COMPLETED, message, done, matched, mismatched, missing,
                copied, rsyncExitCode, errors, warnings);
    }

    private static String statusText(int done, int total, int matched, int mismatched, int missing) {
        return String.format("Verifying %d/%d - matched: %d, mismatched: %d, missing: %d",
                done, total, matched, mismatched, missing);
    }

    private JobResult cancelled(int copied, int checked, int matched, int mismatched, int missing,
                                int rsyncExitCode) {
        String message = String.format(
                "Job cancelled by the user after %d file(s) copied and %d file(s) verified "
                        + "(matched: %d, mismatched: %d, missing: %d).",
                copied, checked, matched, mismatched, missing);
        return new JobResult(JobStatus.FAILED, message, checked, matched, mismatched, missing, copied,
                rsyncExitCode, List.of(),
                List.of("rsync was terminated; an interrupted transfer may leave a hidden "
                        + "temporary file in the destination directory."));
    }

    private JobResult timeoutResult(int copied, int checked, int matched, int mismatched, int missing,
                                    int rsyncExitCode) {
        String message = String.format(
                "Job aborted: it exceeded the %d ms timeout after %d file(s) copied and %d file(s) verified. "
                        + "rsync was terminated.",
                jobTimeoutMs, copied, checked);
        return new JobResult(JobStatus.FAILED, message, checked, matched, mismatched, missing, copied,
                rsyncExitCode, List.of(), List.of("Increase or clear the job timeout if this job is "
                        + "expected to take longer."));
    }

    private static JobResult failure(String message, List<String> errors, int rsyncExitCode) {
        return new JobResult(JobStatus.FAILED, message, 0, 0, 0, 0, 0, rsyncExitCode, errors, List.of());
    }

    // ---------------------------------------------------------------- rsync

    private record RsyncRun(int exitCode, int filesCopied, int dirsCreated, boolean aborted,
                            List<String> errors, List<String> warnings) {
    }

    static boolean isSuccessfulExit(int exitCode) {
        // 0 = ok, 24 = "some files vanished before they could be transferred" (warning only).
        return exitCode == 0 || exitCode == 24;
    }

    enum LineKind { COPY, DIRECTORY, ERROR, WARNING, IGNORED }

    static LineKind classifyLine(String line) {
        if (line == null || line.isBlank()) {
            return LineKind.IGNORED;
        }
        // Local copies report the receiver side (">f"), pushes report the sender side ("<f").
        if (line.startsWith(">f") || line.startsWith("<f")) {
            return LineKind.COPY;
        }
        if (line.startsWith("cd") || line.startsWith(">d") || line.startsWith("<d")) {
            return LineKind.DIRECTORY;
        }
        if (line.startsWith("rsync: ") || line.startsWith("rsync error")) {
            return LineKind.ERROR;
        }
        if (line.startsWith("rsync warning")) {
            return LineKind.WARNING;
        }
        return LineKind.IGNORED;
    }

    /** The path printed after the itemize prefix; it is relative to the transfer root. */
    static String itemizePath(String line) {
        int space = line.indexOf(' ');
        return space < 0 || space == line.length() - 1 ? "" : line.substring(space + 1).trim();
    }

    private RsyncRun runRsync(Path source, Path dest, List<FileEntry> workSet, ProgressListener listener)
            throws IOException, InterruptedException {

        List<FileEntry> listable = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (FileEntry entry : workSet) {
            String rel = entry.relPath();
            if (rel.indexOf('\n') >= 0 || rel.indexOf('\0') >= 0) {
                warnings.add("Not passed to rsync (name contains a newline or NUL byte): " + rel);
                continue;
            }
            listable.add(entry);
        }
        List<String> errors = new ArrayList<>();
        if (listable.isEmpty()) {
            return new RsyncRun(-1, 0, 0, true,
                    List.of("No file name could be safely passed to rsync --files-from."), warnings);
        }
        if (cancelRequested) {
            return new RsyncRun(-1, 0, 0, true, List.of(), warnings);
        }

        Path listFile = Files.createTempFile("file-copy-util-files-", ".txt");
        int exitCode = -1;
        int copied = 0;
        int dirsCreated = 0;
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(listFile, StandardCharsets.UTF_8)) {
                for (FileEntry entry : listable) {
                    writer.write(entry.relPath());
                    writer.newLine();
                }
            }

            // --relative and --dirs are implied by --files-from in rsync 3.x, so the directory
            // structure of each listed path is recreated under the destination.
            List<String> cmd = List.of("rsync", "-a", "--checksum", "--itemize-changes",
                    "--files-from=" + listFile.toAbsolutePath(),
                    source.toString(), dest.toString());

            Process started;
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                started = pb.start();
            } catch (IOException e) {
                return new RsyncRun(-1, 0, 0, true, List.of(
                        "Could not start rsync (" + e.getMessage() + "). Is rsync installed and on PATH?"),
                        warnings);
            }
            process = started;

            ProgressReporter progress = new ProgressReporter(listener, listable.size(), 0, ANNOUNCE_PROGRESS_END);
            int processed = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    markActivity("rsync: " + line.trim());
                    switch (classifyLine(line)) {
                        case COPY -> {
                            copied++;
                            processed++;
                            listener.onFileProcessed(itemizePath(line), "copied", "", "");
                        }
                        case DIRECTORY -> {
                            dirsCreated++;
                            processed++;
                        }
                        case ERROR -> errors.add(line.trim());
                        case WARNING -> warnings.add(line.trim());
                        case IGNORED -> {
                        }
                    }
                    progress.update(processed, "Copying: " + copied + " transferred, "
                            + dirsCreated + " dir(s) created");
                    if (cancelRequested || timedOut) {
                        killProcess();
                        break;
                    }
                }
            } catch (IOException e) {
                // Expected when the process is killed: the pipe closes mid-read.
                if (!cancelRequested && !timedOut) {
                    errors.add("Failed reading rsync output: " + e.getMessage());
                }
            }
            exitCode = started.waitFor();
            if (isSuccessfulExit(exitCode)) {
                progress.finishAt(COPY_PROGRESS_END, "rsync finished; preparing verification");
            }
        } finally {
            process = null;
            try {
                Files.deleteIfExists(listFile);
            } catch (IOException ignored) {
                // best effort
            }
        }

        boolean aborted = !cancelRequested && !timedOut && !isSuccessfulExit(exitCode);
        return new RsyncRun(exitCode, copied, dirsCreated, aborted, errors, warnings);
    }

    // ---------------------------------------------------------------- helpers

    static Selection normalize(Selection selection) {
        if (selection == null) {
            return Selection.none();
        }
        return new Selection(normalize(selection.selected()), normalize(selection.known()));
    }

    /** Normalizes caller-supplied relative paths to the FileEntry form. */
    static Set<String> normalize(Set<String> relPaths) {
        Set<String> out = new LinkedHashSet<>();
        if (relPaths == null) {
            return out;
        }
        for (String p : relPaths) {
            if (p == null) {
                continue;
            }
            String s = p.trim().replace(java.io.File.separatorChar, '/');
            while (s.startsWith("/")) {
                s = s.substring(1);
            }
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * Emits progress only when the integer percentage changes, so a 100k-file job produces at
     * most 101 callbacks instead of one per file.
     */
    private static final class ProgressReporter {
        private final ProgressListener listener;
        private final int total;
        private final int phaseStart;
        private final int phaseSpan;
        private int lastPercent = -1;

        ProgressReporter(ProgressListener listener, int total, int phaseStart, int phaseSpan) {
            this.listener = listener;
            this.total = total;
            this.phaseStart = phaseStart;
            this.phaseSpan = phaseSpan;
        }

        void update(int done, String statusText) {
            int percent = total <= 0 ? phaseStart
                    : phaseStart + (int) Math.round((double) Math.min(done, total) / total * phaseSpan);
            percent = Math.max(0, Math.min(100, percent));
            if (percent == lastPercent) {
                return;
            }
            lastPercent = percent;
            listener.onProgress(percent, statusText);
        }

        void complete(String statusText) {
            finishAt(100, statusText);
        }

        /** Emits a phase milestone; never goes backwards and never duplicates a percentage. */
        void finishAt(int percent, String statusText) {
            if (percent <= lastPercent) {
                return;
            }
            lastPercent = percent;
            listener.onProgress(percent, statusText);
        }
    }
}
