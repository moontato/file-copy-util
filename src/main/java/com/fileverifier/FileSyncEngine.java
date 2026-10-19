package com.fileverifier;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copy/verify engine. Pure logic with an owned job lifecycle and no Swing dependency, so the same
 * code path the GUI drives can be driven from a unit test.
 *
 * <p><b>Lifecycle.</b> An engine instance owns at most one job. {@link #start} submits one and
 * refuses a second while it runs; {@link #execute} is the synchronous equivalent; {@link #cancel}
 * requests termination of the running job (killing {@code rsync} and its children) and makes the
 * job finish with {@link JobStatus#FAILED} and the partial counters it reached. Status walks
 * {@code IDLE -> RUNNING -> COMPLETED/FAILED} and is exposed through {@link #status()}.
 * {@link #close()} shuts the engine's threads down and rejects further jobs.
 *
 * <p><b>Single path model.</b> A file's identity is its {@link FileEntry#relPath()} relative to the
 * source directory, and that same string is what the frame's selection holds and what
 * {@code rsync --files-from} consumes.
 *
 * <p><b>Work set.</b> Every job is driven by the caller's {@link Selection}s:
 * <ul>
 *   <li>COPY_AND_VERIFY: selected source files (their directories are implied by
 *       {@code --files-from}). Selected destination rows are ignored - copying is source-driven.</li>
 *   <li>VERIFY_ONLY: selected source files, minus rows the user explicitly unticked in the
 *       destination list; plus files found only at the destination when
 *       {@link JobRequest#detectExtraFiles()} is on.</li>
 * </ul>
 * A file absent from the destination stays in the work set and is reported as "missing", so a
 * destination list that is out of date can never silently skip a file the user asked to verify. A
 * file present only at the destination is reported as "extra".
 *
 * <p><b>Verification is hash-based.</b> Copy &amp; Verify hashes each selected source file once,
 * before {@code rsync} runs, and compares those digests against the copies after {@code rsync}
 * exits; the source is therefore read exactly once per job. Verify Only hashes both sides. Hashing
 * runs on a small bounded pool, but classification and per-file callbacks happen in work-set order
 * so counters and log order stay deterministic.
 *
 * <p><b>Progress.</b> Each file is one unit of the work-set size. The copy phase scales with
 * {@code rsync --info=progress2}'s own transfer percentage; itemize announcements are counted for
 * the copied total and logged, but never move the bar, because they appear long before the data has
 * moved. Verification fills the rest. Progress and completion callbacks arrive on the engine's worker
 * thread - the GUI must marshall them onto the EDT.
 *
 * <p><b>Watchdog.</b> A scheduled watchdog enforces the optional job deadline (killing the live
 * {@code rsync} process past it) and records liveness - the last activity and when it happened -
 * which lets the GUI report "still running, last activity Ns ago" instead of looking frozen.
 */
public class FileSyncEngine implements AutoCloseable {

    public enum JobType { COPY_AND_VERIFY, VERIFY_ONLY }
    public enum JobStatus { IDLE, RUNNING, COMPLETED, FAILED }

    /** Copy phase occupies 0..70% of the bar, verification 70..100%. */
    static final int COPY_PROGRESS_END = 70;
    /** Deadline enforcement granularity. */
    static final long WATCHDOG_PERIOD_MS = 250;
    /** No deadline (default). */
    public static final long NO_TIMEOUT = 0L;
    /** Hard ceiling on retained outcomes (and therefore on the results table) for huge jobs. */
    static final int MAX_OUTCOMES = 50_000;

    /** Hashing is CPU-bound; more threads than this is memory and noise, not speed. */
    private static final int MAX_HASH_THREADS = 4;

    /** rsync progress line, e.g. "  1,500,000,006  99%  975.13MB/s  0:00:01 (xfr#2, to-chk=2/4)". */
    private static final Pattern RSYNC_PERCENT = Pattern.compile("^\\s+[\\d,]+\\s+(\\d{1,3})%");

    private static final AtomicReference<String> RSYNC_BINARY = new AtomicReference<>("rsync");
    private static final AtomicReference<Boolean> RSYNC_AVAILABLE = new AtomicReference<>();

    public interface ProgressListener {
        /** Called on phase transitions: "copying", "verifying", "done". */
        default void onPhase(String phase) {
        }

        /** Progress update, 0-100 inclusive, computed against the work-set size. */
        default void onProgress(int percent, String statusText) {
        }

        /**
         * Per-file result. {@code kind} is one of {@code COPIED}, {@code OK}, {@code MISMATCH},
         * {@code MISSING}, {@code EXTRA}; hashes are short prefixes for display, null when that side
         * had no file to hash.
         */
        default void onFileProcessed(String relativePath, String kind, String sourceHash, String destHash) {
        }

        /** Called exactly once per job with the final result. The one callback a listener must have. */
        void onCompletion(JobResult result);
    }

    /**
     * A list's selection state. {@code selected} are the ticked rows; {@code known} are the rows the
     * list actually contains. The distinction matters: exclusion requires a row the user saw and
     * left unticked, which is not the same as a file the list has never heard of.
     */
    public record Selection(Set<String> selected, Set<String> known) {
        public Selection {
            selected = Set.copyOf(selected);
            known = Set.copyOf(known);
        }

        public static Selection of(Iterable<String> selectedRelPaths) {
            Set<String> selected = new LinkedHashSet<>();
            for (String rel : selectedRelPaths) {
                selected.add(rel);
            }
            return new Selection(selected, selected);
        }

        public static Selection none() {
            return new Selection(Set.of(), Set.of());
        }
    }

    /** Everything one job needs, built by the frame (or a test) and immutable from then on. */
    public record JobRequest(Path sourceDir, Path destDir, JobType jobType,
                             Selection sourceSelection, Selection destinationSelection,
                             ScanOptions scanOptions, boolean detectExtraFiles) {
        public JobRequest {
            scanOptions = scanOptions == null ? ScanOptions.DEFAULTS : scanOptions;
        }
    }

    /**
     * One verified file's outcome, with full digests so a report can be exported from it. {@code kind}
     * is {@code OK}, {@code MISMATCH}, {@code MISSING} or {@code EXTRA}; a hash is null when that side
     * had no file. A COPIED event is not an outcome - it is a transfer, and the same file gets a
     * verification outcome afterwards.
     */
    public record FileOutcome(String relPath, String kind, String sourceHash, String destHash) {
    }

    public record JobResult(JobStatus status, String message,
                            int filesChecked, int filesMatched, int filesMismatched,
                            int filesMissing, int filesExtra, int filesCopied,
                            int rsyncExitCode, List<FileOutcome> outcomes, List<String> errors,
                            List<String> warnings) {

        public JobResult {
            outcomes = List.copyOf(outcomes);
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        public boolean isSuccess() {
            return status == JobStatus.COMPLETED;
        }
    }

    private final AtomicReference<JobStatus> status = new AtomicReference<>(JobStatus.IDLE);
    private final AtomicBoolean jobRunning = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicBoolean timeoutTriggered = new AtomicBoolean(false);
    private volatile boolean closed;
    private final AtomicReference<Process> liveProcess = new AtomicReference<>();
    private final AtomicLong deadline = new AtomicLong(0L);
    private final AtomicLong startedAt = new AtomicLong(0L);
    private final AtomicLong lastActivityAt = new AtomicLong(0L);
    private final AtomicReference<String> lastActivity = new AtomicReference<>("");

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sync-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService jobWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sync-job");
        thread.setDaemon(true);
        return thread;
    });

    private volatile JobResult lastResult;
    private volatile long jobTimeoutMillis = NO_TIMEOUT;
    /** Set by the job thread; read by the watchdog to tell a live transfer from a hung engine. */
    private volatile String jobPhase = "idle";

    public FileSyncEngine() {
        watchdog.scheduleAtFixedRate(this::watchdogTick,
                WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------- lifecycle

    public JobStatus status() {
        return status.get();
    }

    public boolean isRunning() {
        return status.get() == JobStatus.RUNNING;
    }

    public JobResult lastResult() {
        return lastResult;
    }

    /** 0 disables the watchdog deadline. Set it while the engine is idle. */
    public void setJobTimeoutMillis(long millis) {
        this.jobTimeoutMillis = Math.max(0L, millis);
    }

    public long jobTimeoutMillis() {
        return jobTimeoutMillis;
    }

    /** ms since the engine last saw activity; Long.MAX_VALUE when no job has run. */
    public long millisSinceLastActivity() {
        long at = lastActivityAt.get();
        return at == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - at;
    }

    public String lastActivityDescription() {
        return lastActivity.get();
    }

    /**
     * Submits a job. Returns false if a job is already running - there is no queue and no second
     * job, by design.
     */
    public boolean start(JobRequest request, ProgressListener listener) {
        if (closed || !jobRunning.compareAndSet(false, true)) {
            return false;
        }
        beginJob(request, listener);
        try {
            jobWorker.submit(() -> finishJob(runGuarded(request, listener), listener));
            return true;
        } catch (RuntimeException e) {
            jobRunning.set(false);
            status.set(JobStatus.FAILED);
            throw e;
        }
    }

    /** Synchronous variant, for tests and headless callers. Blocks until the job finishes. */
    public JobResult execute(JobRequest request, ProgressListener listener) {
        if (closed) {
            throw new IllegalStateException("Engine is closed");
        }
        if (!jobRunning.compareAndSet(false, true)) {
            throw new IllegalStateException("Engine is busy: a job is already running");
        }
        beginJob(request, listener);
        return finishJob(runGuarded(request, listener), listener);
    }

    /** One-shot convenience: private engine, single job, no reuse. */
    public static JobResult runOnce(JobRequest request, ProgressListener listener) {
        try (FileSyncEngine engine = new FileSyncEngine()) {
            return engine.execute(request, listener);
        }
    }

    /**
     * Ask the running job to stop, and kill {@code rsync} if it is the one holding the job.
     * Returns true when a job was actually running.
     */
    public boolean cancel() {
        if (!isRunning()) {
            return false;
        }
        cancelRequested.set(true);
        markActivity("cancel requested");
        terminateRsync();
        return true;
    }

    @Override
    public void close() {
        closed = true;
        cancel();
        watchdog.shutdownNow();
        jobWorker.shutdownNow();
        try {
            jobWorker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void beginJob(JobRequest request, ProgressListener listener) {
        status.set(JobStatus.RUNNING);
        cancelRequested.set(false);
        timeoutTriggered.set(false);
        startedAt.set(System.nanoTime());
        deadline.set(jobTimeoutMillis > 0
                ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(jobTimeoutMillis) : 0L);
        markActivity("starting: " + request.jobType());
    }

    private JobResult finishJob(JobResult result, ProgressListener listener) {
        lastResult = result;
        status.set(result.status());
        deadline.set(0L);
        liveProcess.set(null);
        jobPhase = "idle";
        jobRunning.set(false);
        listener.onCompletion(result);
        return result;
    }

    private JobResult runGuarded(JobRequest request, ProgressListener listener) {
        try {
            return doExecute(request, listener);
        } catch (Exception e) {
            return failure("Job aborted: " + e.getMessage(), List.of(String.valueOf(e)), List.of(), 0);
        } finally {
            liveProcess.set(null);
            jobPhase = "idle";
        }
    }

    // ---------------------------------------------------------------- preflight

    /**
     * Everything that can be checked before a job is worth starting; an empty list means go. The
     * engine re-checks all of it itself, so a caller that shows this in a dialog is improving the UX,
     * not providing the safety.
     */
    public static List<String> validate(JobRequest request) {
        List<String> problems = new ArrayList<>();
        Path source = request.sourceDir();
        Path dest = request.destDir();
        boolean copies = request.jobType() == JobType.COPY_AND_VERIFY;

        if (source == null || !Files.isDirectory(source)) {
            problems.add("Source is not a directory: " + source);
        } else if (!Files.isReadable(source)) {
            problems.add("Source directory is not readable: " + source);
        }
        if (dest == null || !Files.isDirectory(dest)) {
            problems.add("Destination is not a directory: " + dest);
        } else if (copies && !Files.isWritable(dest)) {
            problems.add("Destination directory is not writable: " + dest);
        } else if (!Files.isReadable(dest)) {
            problems.add("Destination directory is not readable: " + dest);
        }
        if (source != null && dest != null) {
            Path sourceKey = canonical(source);
            Path destKey = canonical(dest);
            if (sourceKey.equals(destKey)) {
                problems.add("Source and destination are the same directory: " + source);
            } else if (destKey.startsWith(sourceKey)) {
                problems.add("Destination is inside the source directory, so the job would copy files into "
                        + "the tree it is reading: " + dest);
            }
        }
        if (copies && !isRsyncAvailable()) {
            problems.add("rsync was not found (looked for '" + rsyncBinary()
                    + "'). Install rsync, or use Verify Only, which needs no rsync.");
        }
        return problems;
    }

    /** Is the rsync binary present and runnable? Probed once and cached. */
    public static boolean isRsyncAvailable() {
        Boolean cached = RSYNC_AVAILABLE.get();
        if (cached != null) {
            return cached;
        }
        boolean available;
        try {
            // "Available" means runnable, not healthy: a missing binary fails to start at all, while
            // a runnable one that exits non-zero is a problem the job itself should report.
            Process probe = new ProcessBuilder(List.of(rsyncBinary(), "--version"))
                    .redirectErrorStream(true)
                    .start();
            probe.getInputStream().close();
            probe.waitFor(10, TimeUnit.SECONDS);
            probe.destroy();
            available = true;
        } catch (IOException | InterruptedException e) {
            available = false;
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        RSYNC_AVAILABLE.compareAndSet(null, available);
        return available;
    }

    /** Test seam: point the engine at a different rsync (or a non-existent one). */
    static void setRsyncBinary(String binary) {
        RSYNC_BINARY.set(binary == null || binary.isBlank() ? "rsync" : binary);
        RSYNC_AVAILABLE.set(null);
    }

    static String rsyncBinary() {
        return RSYNC_BINARY.get();
    }

    private static Path canonical(Path path) {
        try {
            Path absolute = path.toAbsolutePath();
            Path real = absolute.toRealPath();
            return real.startsWith(absolute) ? absolute : real;
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    // ---------------------------------------------------------------- job body

    private JobResult doExecute(JobRequest request, ProgressListener listener)
            throws IOException, NoSuchAlgorithmException, InterruptedException {

        Path sourceDir = request.sourceDir().toAbsolutePath();
        Path destDir = request.destDir().toAbsolutePath();

        List<String> problems = validate(request);
        if (!problems.isEmpty()) {
            return failure("Preflight failed: " + String.join("; ", problems), problems, List.of(), 0);
        }

        Selection src = normalize(request.sourceSelection());
        Selection dst = normalize(request.destinationSelection());
        ScanOptions options = request.scanOptions();
        boolean verifyOnly = request.jobType() == JobType.VERIFY_ONLY;

        if (src.selected().isEmpty() && dst.selected().isEmpty()) {
            return failure("No files were selected: an empty selection means no files, not all files.",
                    List.of(), List.of(), 0);
        }

        markActivity("scanning " + sourceDir);
        DirectoryScanner.ScanResult scan = DirectoryScanner.scanFiles(sourceDir, options);
        DirectoryScanner.ScanResult destScan = verifyOnly ? DirectoryScanner.scanFiles(destDir, options) : null;
        Set<String> sourceRels = new HashSet<>();
        for (FileEntry entry : scan.files()) {
            sourceRels.add(entry.relPath());
        }

        List<FileEntry> workSet = new ArrayList<>();
        for (FileEntry entry : scan.files()) {
            String rel = entry.relPath();
            if (!src.selected().contains(rel)) {
                continue;
            }
            // Exclusion requires a destination row the user actually saw and left unticked.
            if (verifyOnly && dst.known().contains(rel) && !dst.selected().contains(rel)) {
                continue;
            }
            workSet.add(entry);
        }

        List<FileEntry> extras = new ArrayList<>();
        if (destScan != null && request.detectExtraFiles()) {
            for (FileEntry entry : destScan.files()) {
                String rel = entry.relPath();
                if (sourceRels.contains(rel)) {
                    continue;
                }
                if (dst.known().contains(rel) && !dst.selected().contains(rel)) {
                    continue;
                }
                extras.add(entry);
            }
        }

        int workTotal = workSet.size() + extras.size();
        // A ticked path with no scanned file behind it is not the same as an unticked one: the user
        // asked for it and it is gone (deleted, or filtered out by an ignore pattern). Silently doing
        // without it is how a typo in a CLI list or a file deleted after the scan turns into a job
        // that reports success.
        List<String> notFound = new ArrayList<>(src.selected());
        notFound.removeAll(sourceRels);
        notFound.sort(Comparator.naturalOrder());
        String notFoundText = describeNames(notFound);

        if (workTotal == 0) {
            String suffix = notFound.isEmpty() ? "" : " Selected but not found in the source: " + notFoundText + ".";
            return failure("Empty work set: 0 selected source files and 0 destination-only files survived "
                            + "the selection, so there is nothing to do." + suffix,
                    List.of(), List.of(), 0);
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>(scan.warnings());
        if (destScan != null) {
            warnings.addAll(destScan.warnings());
        }
        if (scan.skippedLinks() > 0) {
            warnings.add(scan.skippedLinks() + " symbolic link(s) in the source were ignored; change the "
                    + "symlink policy to copy or verify them.");
        }
        if (!notFound.isEmpty()) {
            warnings.add(notFound.size() + " selected file(s) were not found in the source and were not "
                    + "touched: " + notFoundText + ".");
        }

        int rsyncExitCode = 0;
        ProgressReporter progress = new ProgressReporter(workTotal, listener);
        List<FileOutcome> outcomes = new ArrayList<>();
        Map<String, String> sourceHashes = Map.of();

        if (!verifyOnly) {
            listener.onPhase("copying");
            jobPhase = "copy";
            progress.update(0, "Hashing " + workSet.size() + " source file(s) before copying...");
            // Hashed once, before the copy: these same digests are reused for the comparison below.
            sourceHashes = hashFiles(workSet, "source", warnings);

            Path filesFrom = writeFilesFrom(workSet, warnings);
            if (filesFrom == null) {
                return failure("No selected file name could be handed to rsync (each contains a newline or "
                        + "NUL byte); deselect them and retry.", errors, warnings, 0);
            }
            try {
                Process process = new ProcessBuilder(buildRsyncCommand(sourceDir, destDir, filesFrom, options))
                        .redirectErrorStream(true)
                        .start();
                liveProcess.set(process);

                int copied = pumpRsyncOutput(process, progress, errors, warnings, listener);
                rsyncExitCode = process.waitFor();
                markActivity("rsync exit " + rsyncExitCode);

                if (timeoutWasTriggered()) {
                    return timeoutResult(progress, outcomes, errors, warnings);
                }
                if (cancelRequested.get()) {
                    return cancelledDuringCopy(progress, outcomes, rsyncExitCode, errors, warnings);
                }
                if (!isSuccessfulExit(rsyncExitCode)) {
                    errors.add("rsync exited with code " + rsyncExitCode);
                    return failure("rsync failed (exit code " + rsyncExitCode + ")", errors, warnings,
                            rsyncExitCode);
                }
                if (rsyncExitCode == 24) {
                    warnings.add("rsync exit code 24: some files vanished before they could be transferred.");
                }
                progress.copyComplete(copied);
            } finally {
                Files.deleteIfExists(filesFrom);
            }
        }

        listener.onPhase("verifying");
        jobPhase = "verify";
        progress.verifyStart(!verifyOnly);

        List<FileEntry> destTargets = new ArrayList<>();
        for (FileEntry entry : concat(workSet, extras)) {
            destTargets.add(new FileEntry(entry.relPath(), destDir.resolve(entry.relPath())));
        }

        Map<String, String> destHashes;
        if (verifyOnly) {
            sourceHashes = hashFiles(workSet, "source", warnings);
            destHashes = hashFiles(destTargets, "destination", warnings);
        } else {
            destHashes = hashFiles(destTargets, "destination", warnings);
        }
        if (timeoutWasTriggered()) {
            return timeoutResult(progress, outcomes, errors, warnings);
        }
        if (cancelRequested.get()) {
            return cancelledDuringVerify(progress, outcomes, errors, warnings);
        }

        for (FileEntry entry : workSet) {
            if (cancelRequested.get() || deadlineExceeded()) {
                break;
            }
            String rel = entry.relPath();
            String sourceHash = sourceHashes.get(rel);
            String destHash = destHashes.get(rel);
            markActivity("verified " + rel);
            if (destHash != null && sourceHash != null && sourceHash.equals(destHash)) {
                progress.countMatched();
                recordOutcome(outcomes, listener, rel, "OK", sourceHash, destHash);
            } else if (sourceHash == null && !Files.isRegularFile(entry.absolutePath())) {
                progress.countMissing();
                recordOutcome(outcomes, listener, rel, "MISSING", null, destHash);
            } else if (destHash == null) {
                progress.countMissing();
                recordOutcome(outcomes, listener, rel, "MISSING", sourceHash, null);
            } else {
                progress.countMismatched();
                recordOutcome(outcomes, listener, rel, "MISMATCH", sourceHash, destHash);
            }
            progress.verifiedFile(!verifyOnly);
        }

        for (FileEntry entry : extras) {
            if (cancelRequested.get() || deadlineExceeded()) {
                break;
            }
            progress.countExtra();
            markActivity("verified " + entry.relPath());
            recordOutcome(outcomes, listener, entry.relPath(), "EXTRA", null,
                    destHashes.get(entry.relPath()));
            progress.verifiedFile(!verifyOnly);
        }

        if (outcomes.size() >= MAX_OUTCOMES) {
            warnings.add("Results list truncated at " + MAX_OUTCOMES + " entries; counters are still exact.");
        }

        if (timeoutWasTriggered()) {
            return timeoutResult(progress, outcomes, errors, warnings);
        }
        if (cancelRequested.get()) {
            return cancelledDuringVerify(progress, outcomes, errors, warnings);
        }

        progress.finish("Verification complete");
        listener.onPhase("done");

        StringBuilder message = new StringBuilder("Job complete: ")
                .append(progress.matched()).append(" matched, ")
                .append(progress.mismatched()).append(" mismatched, ")
                .append(progress.missing()).append(" missing, ")
                .append(progress.copied()).append(" copied");
        if (progress.extra() > 0) {
            message.append(", ").append(progress.extra()).append(" destination-only");
        }
        message.append(".");
        return new JobResult(JobStatus.COMPLETED, message.toString(),
                progress.processed(), progress.matched(), progress.mismatched(), progress.missing(),
                progress.extra(), progress.copied(), rsyncExitCode, outcomes, errors, warnings);
    }

    /** Records one verification outcome and the matching streaming-log callback (short hashes). */
    private static void recordOutcome(List<FileOutcome> outcomes, ProgressListener listener, String rel,
                                      String kind, String sourceHash, String destHash) {
        if (outcomes.size() < MAX_OUTCOMES) {
            outcomes.add(new FileOutcome(rel, kind, sourceHash, destHash));
        }
        listener.onFileProcessed(rel, kind,
                sourceHash == null ? null : FileHasher.shortHash(sourceHash),
                destHash == null ? null : FileHasher.shortHash(destHash));
    }

    private JobResult cancelledDuringCopy(ProgressReporter progress, List<FileOutcome> outcomes,
                                          int exitCode, List<String> errors, List<String> warnings) {
        String message = "Job cancelled by the user after " + progress.copied() + " file(s) announced for "
                + "copy and " + progress.processed() + " file(s) verified (matched: " + progress.matched()
                + ", mismatched: " + progress.mismatched() + ", missing: " + progress.missing()
                + "). rsync exit code: " + exitCode + ".";
        return new JobResult(JobStatus.FAILED, message, progress.processed(), progress.matched(),
                progress.mismatched(), progress.missing(), progress.extra(), progress.copied(), exitCode,
                outcomes, errors,
                withWarning(warnings, "rsync was terminated; an interrupted transfer may leave a "
                        + "hidden temporary file in the destination directory."));
    }

    private JobResult cancelledDuringVerify(ProgressReporter progress, List<FileOutcome> outcomes,
                                            List<String> errors, List<String> warnings) {
        String message = "Job cancelled during verification after " + progress.processed()
                + " of " + progress.workTotal() + " file(s) were checked.";
        return new JobResult(JobStatus.FAILED, message, progress.processed(), progress.matched(),
                progress.mismatched(), progress.missing(), progress.extra(), progress.copied(), 0,
                outcomes, errors, warnings);
    }

    private JobResult timeoutResult(ProgressReporter progress, List<FileOutcome> outcomes,
                                    List<String> errors, List<String> warnings) {
        String message = "Job aborted: it exceeded the " + jobTimeoutMillis + " ms timeout after "
                + progress.copied() + " file(s) copied and " + progress.processed() + " file(s) verified.";
        return new JobResult(JobStatus.FAILED, message, progress.processed(), progress.matched(),
                progress.mismatched(), progress.missing(), progress.extra(), progress.copied(), 0,
                outcomes, errors,
                withWarning(warnings, "Increase or clear the job timeout if this job is expected to "
                        + "take longer."));
    }

    private static List<String> withWarning(List<String> warnings, String text) {
        List<String> extended = new ArrayList<>(warnings);
        extended.add(text);
        return extended;
    }

    private static List<FileEntry> concat(List<FileEntry> a, List<FileEntry> b) {
        List<FileEntry> all = new ArrayList<>(a.size() + b.size());
        all.addAll(a);
        all.addAll(b);
        return all;
    }

    /**
     * Hashes a batch of files on a bounded pool. Files that are unreadable are omitted from the
     * result and recorded in {@code warnings}; "missing" is then inferred by the caller from the
     * absence of both a hash and a file.
     */
    private Map<String, String> hashFiles(List<FileEntry> entries, String side, List<String> warnings)
            throws NoSuchAlgorithmException {
        if (entries.isEmpty()) {
            return Map.of();
        }
        int threads = Math.min(MAX_HASH_THREADS, Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "hash-" + side);
            thread.setDaemon(true);
            return thread;
        });
        Map<String, Future<String>> futures = new LinkedHashMap<>();
        try {
            for (FileEntry entry : entries) {
                if (cancelRequested.get() || deadlineExceeded()) {
                    break;
                }
                Path path = entry.absolutePath();
                futures.put(entry.relPath(), pool.submit(() -> {
                    if (cancelRequested.get() || deadlineExceeded()) {
                        return null;
                    }
                    return FileHasher.computeHash(path);
                }));
            }
        } finally {
            pool.shutdown();
        }

        Map<String, String> hashes = new LinkedHashMap<>();
        NoSuchAlgorithmException pending = null;
        for (Map.Entry<String, Future<String>> future : futures.entrySet()) {
            try {
                String hash = future.getValue().get();
                if (hash != null) {
                    hashes.put(future.getKey(), hash);
                }
            } catch (ExecutionException e) {
                if (e.getCause() instanceof NoSuchAlgorithmException algorithmException) {
                    pending = algorithmException;
                } else if (e.getCause() instanceof java.nio.file.NoSuchFileException) {
                    // Absence is not a read error. The caller classifies a file with no hash and no
                    // file as MISSING; warning about it too would bury the log in "could not read"
                    // lines for files that are simply not there.
                } else {
                    warnings.add("Could not read " + side + " file: " + future.getKey()
                            + " (" + e.getCause() + ")");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (pending != null) {
            throw pending;
        }
        return hashes;
    }

    /**
     * Writes the rsync {@code --files-from} list, or returns null when no selected name is usable.
     * The file is UTF-8 and always deleted by the caller's finally block.
     */
    private Path writeFilesFrom(List<FileEntry> workSet, List<String> warnings) throws IOException {
        List<String> relPaths = new ArrayList<>();
        for (FileEntry entry : workSet) {
            String rel = entry.relPath();
            if (rel.indexOf('\n') >= 0 || rel.indexOf('\0') >= 0) {
                warnings.add("Skipped from copy: file name contains a newline or NUL byte: " + rel);
                continue;
            }
            relPaths.add(rel);
        }
        if (relPaths.isEmpty()) {
            return null;
        }
        Path listFile = Files.createTempFile("filecopy_list_", ".txt");
        try (BufferedWriter writer = Files.newBufferedWriter(listFile, StandardCharsets.UTF_8)) {
            for (String rel : relPaths) {
                writer.write(rel);
                writer.newLine();
            }
        }
        return listFile;
    }

    private List<String> buildRsyncCommand(Path sourceDir, Path destDir, Path filesFrom, ScanOptions options) {
        List<String> command = new ArrayList<>();
        command.add(rsyncBinary());
        command.add("-a");
        command.add("--checksum");
        command.add("--itemize-changes");
        command.add("--info=progress2");
        if (options.symlinkPolicy() == ScanOptions.SymlinkPolicy.FOLLOW) {
            command.add("--copy-links");
        }
        command.add("--files-from=" + filesFrom);
        command.add(sourceDir.toString());
        command.add(destDir.toString());
        return command;
    }

    /**
     * Reads merged rsync stdout/stderr until the process exits, reporting copy progress as it goes.
     * Returns the number of files rsync announced as transferring.
     */
    private int pumpRsyncOutput(Process process, ProgressReporter progress, List<String> errors,
                                List<String> warnings, ProgressListener listener) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelRequested.get() || timeoutWasTriggered()) {
                    // The process is being killed; stop reading and stop counting announcements.
                    break;
                }
                int percent = rsyncTransferPercent(line);
                if (percent >= 0) {
                    // rsync's own global percentage: the only trustworthy copy-progress signal, since
                    // itemize lines appear before the file's data has moved.
                    progress.rsyncPercent(percent);
                    markActivity(line.trim());
                    continue;
                }
                switch (classifyLine(line)) {
                    case COPY -> {
                        progress.countCopied();
                        String rel = itemizePath(line);
                        if (rel != null) {
                            listener.onFileProcessed(rel, "COPIED", null, null);
                        }
                        markActivity(line.trim());
                    }
                    case DIRECTORY -> {
                        // Directory creation is implied by --files-from; nothing to count.
                    }
                    case ERROR -> {
                        String message = line.trim();
                        errors.add(message);
                        markActivity(message);
                    }
                    case WARNING -> {
                        warnings.add(line.trim());
                        markActivity(line.trim());
                    }
                    case IGNORED -> {
                        // no action
                    }
                }
            }
        } catch (IOException e) {
            if (!cancelRequested.get() && !deadlineExceeded()) {
                throw e;
            }
            // Cancel/timeout killed the process: a closed pipe is expected, not a job failure.
        }
        return progress.copied();
    }

    /**
     * Extracts the relative path from an itemize line, or null when the line carries no name.
     *
     * <p>The itemize column is space-free but not fixed-length across rsync flavours
     * ({@code >f+++++++++ a.txt}, {@code >f.st.... sub/a.txt}), so the split point is the first
     * space rather than a hard-coded offset.
     */
    static String itemizePath(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith(">") && !trimmed.startsWith("<")) {
            return null;
        }
        int space = trimmed.indexOf(' ');
        if (space < 0) {
            return null;
        }
        String path = trimmed.substring(space + 1).trim();
        return path.isEmpty() ? null : path.replaceAll("/$", "");
    }

    /** Percent from an rsync progress line, or -1 when the line is not one. */
    static int rsyncTransferPercent(String line) {
        Matcher matcher = RSYNC_PERCENT.matcher(line);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Math.min(100, Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    enum LineKind { COPY, DIRECTORY, ERROR, WARNING, IGNORED }

    /**
     * Classifies one rsync output line. With {@code -i} each change line is an update type followed by
     * the item type: update type {@code >} (received), {@code <} (sent), {@code c} (local create or
     * change, which is how a created directory and a changed symlink are reported), {@code h} (hard
     * link), {@code .} (not updated) or {@code *} (a message); item type {@code f} file, {@code d}
     * directory, {@code L} symlink, {@code D} device, {@code S} special file. So a created symlink is
     * {@code cL}, a created directory is {@code cd}, and a local copy of a file is {@code >f}. rsync
     * writes problems as {@code rsync: ...} or {@code rsync error: ...}; a line starting with
     * whitespace is a {@code --info=progress2} progress line.
     */
    static LineKind classifyLine(String line) {
        if (line == null || line.isBlank()) {
            return LineKind.IGNORED;
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("rsync error:")) {
            return LineKind.ERROR;
        }
        if (trimmed.startsWith("rsync warning:")) {
            return LineKind.WARNING;
        }
        if (trimmed.startsWith("rsync:")) {
            // rsync reports per-file problems with a bare "rsync:" prefix, and not all of them are
            // warnings ("rsync: ... some files vanished ..." is one).
            return trimmed.contains("failed:") || trimmed.contains("error")
                    ? LineKind.ERROR : LineKind.WARNING;
        }
        char update = line.charAt(0);
        char type = line.length() > 1 ? line.charAt(1) : 0;
        switch (update) {
            case '>':
            case '<':
            case 'c':
            case 'h':
                // An entry rsync created or changed. A directory is bookkeeping, everything else is a
                // copied item - a symlink counts even though no file data moves.
                if (type == 'd') {
                    return LineKind.DIRECTORY;
                }
                return type == 'f' || type == 'L' || type == 'D' || type == 'S'
                    ? LineKind.COPY : LineKind.IGNORED;
            case '.':
                // Nothing was transferred; only attributes may have been set.
                return type == 'd' ? LineKind.DIRECTORY : LineKind.IGNORED;
            case '*':
                // A message line such as "*deleting   old.txt"; only reachable with --delete.
                return LineKind.WARNING;
            default:
                return LineKind.IGNORED;
        }
    }

    /** Names for a message: the first few sorted ones, then how many more there are. */
    static String describeNames(List<String> names) {
        int shown = Math.min(names.size(), 10);
        String head = String.join(", ", names.subList(0, shown));
        return names.size() > shown ? head + " (+" + (names.size() - shown) + " more)" : head;
    }

    /** rsync exit codes 0 and 24 are success for our purposes; 24 is reported as a warning. */
    static boolean isSuccessfulExit(int exitCode) {
        return exitCode == 0 || exitCode == 24;
    }

    /** Normalizes caller-supplied relative paths to the FileEntry form. */
    static Set<String> normalize(Set<String> relPaths) {
        Set<String> normalized = new HashSet<>();
        for (String rel : relPaths) {
            if (rel == null) {
                continue;
            }
            String trimmed = rel.replace(java.io.File.separatorChar, '/').trim();
            while (trimmed.startsWith("/")) {
                trimmed = trimmed.substring(1);
            }
            // "sub/./b.txt" and "./b.txt" are the same relative path as "b.txt"; a CLI user writes
            // them, and a path identity that keeps the prefix would never match the scanned file.
            while (trimmed.startsWith("./")) {
                trimmed = trimmed.substring(2);
            }
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    static Selection normalize(Selection selection) {
        if (selection == null) {
            return Selection.none();
        }
        return new Selection(normalize(selection.selected()), normalize(selection.known()));
    }

    // ---------------------------------------------------------------- watchdog

    private void watchdogTick() {
        if (!isRunning()) {
            return;
        }
        long deadlineNanos = deadline.get();
        if (deadlineNanos != 0L && System.nanoTime() > deadlineNanos) {
            timeoutTriggered.set(true);
            cancelRequested.set(true);
            markActivity("timeout after " + jobTimeoutMillis + " ms in phase " + jobPhase);
            terminateRsync();
        }
    }

    /**
     * True when the watchdog, not the user, ended the job. The watchdog also sets the cancel flag, so
     * without this the two abort reasons would be indistinguishable in the result.
     */
    private boolean timeoutWasTriggered() {
        return timeoutTriggered.get();
    }

    private void markActivity(String description) {
        lastActivityAt.set(System.currentTimeMillis());
        lastActivity.set(description == null ? "" : description);
    }

    private boolean deadlineExceeded() {
        long deadlineNanos = deadline.get();
        return deadlineNanos != 0L && System.nanoTime() > deadlineNanos;
    }

    /** Kills the live rsync process and everything it spawned. Safe to call more than once. */
    private void terminateRsync() {
        Process process = liveProcess.get();
        if (process == null) {
            return;
        }
        process.descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (IllegalStateException | SecurityException e) {
                // Already gone, or not ours to kill.
            }
        });
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private JobResult failure(String message, List<String> errors, List<String> warnings, int exitCode) {
        return new JobResult(JobStatus.FAILED, message, 0, 0, 0, 0, 0, 0, exitCode, List.of(), errors,
                warnings);
    }

    /**
     * Progress bookkeeping against the work-set size, with integer-percent coalescing and a
     * monotonic bar (progress never moves backwards within a job).
     */
    private static final class ProgressReporter {

        private final int workTotal;
        private final ProgressListener listener;
        private int processed;
        private int matched;
        private int mismatched;
        private int missing;
        private int extra;
        private int copied;
        private int rsyncPercent = -1;
        private int lastPercent = -1;

        ProgressReporter(int workTotal, ProgressListener listener) {
            this.workTotal = workTotal;
            this.listener = listener;
        }

        int workTotal() {
            return workTotal;
        }

        int processed() {
            return processed;
        }

        int matched() {
            return matched;
        }

        int mismatched() {
            return mismatched;
        }

        int missing() {
            return missing;
        }

        int extra() {
            return extra;
        }

        int copied() {
            return copied;
        }

        void countCopied() {
            copied++;
        }

        void countMatched() {
            processed++;
            matched++;
        }

        void countMismatched() {
            processed++;
            mismatched++;
        }

        void countMissing() {
            processed++;
            missing++;
        }

        void countExtra() {
            processed++;
            extra++;
        }

        void update(int percent, String label) {
            emit(percent, label);
        }

        /** Copy progress driven by rsync's own transfer percentage (0..100 of the copy phase). */
        void rsyncPercent(int percent) {
            rsyncPercent = Math.max(rsyncPercent, percent);
            emit((int) Math.round(rsyncPercent * COPY_PROGRESS_END / 100.0),
                    "Copying: " + copied + " announced, " + rsyncPercent + "% transferred");
        }

        void copyComplete(int copied) {
            emit(COPY_PROGRESS_END, "Copy complete: " + copied + " file(s) transferred");
        }

        void verifyStart(boolean afterCopy) {
            emit(afterCopy ? COPY_PROGRESS_END : 0, "Verified 0 of " + workTotal);
        }

        void verifiedFile(boolean afterCopy) {
            double ratio = processed / (double) workTotal;
            int percent = afterCopy
                    ? (int) Math.round(COPY_PROGRESS_END + ratio * (100 - COPY_PROGRESS_END))
                    : (int) Math.round(ratio * 100);
            emit(percent, "Verified " + processed + " of " + workTotal);
        }

        void finish(String label) {
            emit(100, label);
        }

        private void emit(int percent, String label) {
            int clamped = Math.max(0, Math.min(100, percent));
            if (clamped < lastPercent) {
                clamped = lastPercent;
            }
            if (clamped == lastPercent) {
                return;
            }
            lastPercent = clamped;
            listener.onProgress(clamped, label);
        }
    }
}
