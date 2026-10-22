package com.fileverifier;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
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
 *   <li>COPY_AND_VERIFY and COPY_ONLY: selected source files (their directories are implied by
 *       {@code --files-from}). Selected destination rows are ignored - copying is source-driven.</li>
 *   <li>VERIFY_ONLY: selected source files, minus rows the user explicitly unticked in the
 *       destination list; plus files found only at the destination when
 *       {@link JobRequest#detectExtraFiles()} is on.</li>
 * </ul>
 * A file absent from the destination stays in the work set and is reported as "missing", so a
 * destination list that is out of date can never silently skip a file the user asked to verify. A
 * file present only at the destination is reported as "extra".
 *
 * <p><b>Verification is hash-based, and it is optional.</b> A verifying job hashes each selected
 * source file once, before {@code rsync} runs, and compares those digests against the copies after
 * {@code rsync} exits; the source is therefore read exactly once per job. Verify Only hashes both
 * sides. A job with verification turned off hashes nothing at all - reading every file to compute a
 * digest nobody asked for is the expensive part of the job - and its results are the files rsync
 * reported as written, each recorded as COPIED rather than as a verdict. Hashing
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

    /**
     * What a job does. The interface expresses this as two controls rather than three modes: whether
     * the job copies, and whether it also compares hashes afterwards.
     *
     * <ul>
     *   <li>{@link #COPY_AND_VERIFY}: copy the selection, then compare it.</li>
     *   <li>{@link #COPY_ONLY}: copy the selection and report what was written, comparing nothing.</li>
     *   <li>{@link #VERIFY_ONLY}: compare, and never run rsync at all. This is the only way to look at
     *       a backup without touching it, which is why it stays a mode and not a checkbox.</li>
     * </ul>
     */
    public enum JobType {
        COPY_AND_VERIFY, COPY_ONLY, VERIFY_ONLY;

        /** True when rsync runs, so files can be written. */
        public boolean copies() {
            return this != VERIFY_ONLY;
        }

        /** True when digests are compared and each file in the work set gets a verdict. */
        public boolean verifies() {
            return this != COPY_ONLY;
        }

        /** How the job announces itself to a human. */
        public String describe() {
            return switch (this) {
                case COPY_AND_VERIFY -> "Copy and verify";
                case COPY_ONLY -> "Copy only (verification off)";
                case VERIFY_ONLY -> "Verify only (nothing is copied)";
            };
        }
    }
    public enum JobStatus { IDLE, RUNNING, COMPLETED, FAILED }

    /** Copy phase occupies 0..70% of the bar, verification 70..100%. */
    static final int COPY_PROGRESS_END = 70;

    /** How long the tree gets to stop itself before it is killed outright. */
    private static final int TERM_GRACE_SECONDS = 3;
    /** Deadline enforcement granularity. */
    static final long WATCHDOG_PERIOD_MS = 250;
    /** No deadline (default). */
    public static final long NO_TIMEOUT = 0L;
    /** Hard ceiling on retained outcomes (and therefore on the results table) for huge jobs. */
    static final int MAX_OUTCOMES = 50_000;
    static final int NAMED_EXCLUSIONS = 5;

    /** Hashing is CPU-bound; more threads than this is memory and noise, not speed. */
    private static final int MAX_HASH_THREADS = 4;

    /** rsync progress line, e.g. "  1,500,000,006  99%  975.13MB/s  0:00:01 (xfr#2, to-chk=2/4)". */
    private static final Pattern RSYNC_PERCENT = Pattern.compile("^\\s+[\\d,]+\\s+(\\d{1,3})%");

    /**
     * A {@code --info=progress2} stats line: bytes transferred so far, the overall percentage, a rate,
     * and an elapsed time. The percentage is of the whole transfer, not of the file in flight, so the
     * per-file figure has to be derived from it.
     */
    private static final Pattern RSYNC_TRANSFER = Pattern.compile(
            "^\\s+([\\d,]+)\\s+(\\d{1,3})%\\s+([\\d.]+\\s*[kMG]?B/s)\\s+(\\d+:\\d+:\\d+)");
    /** rsync's own completed-transfer counter in the same line: "(xfr#2, to-chk=2/4)". */
    private static final Pattern RSYNC_XFR = Pattern.compile("xfr#(\\d+)");
    /** A partial transfer rsync left behind: ".big.bin.YC9p2z". */
    private static final Pattern RSYNC_TEMP_NAME = Pattern.compile("^\\.(.+)\\.([A-Za-z0-9]{6})$");

    /**
     * The rsync this process uses unless an engine asks for another. {@code FCU_RSYNC} sets it, which is
     * how a test run or a deployment pins one specific build - "the rsync on PATH" is not a reproducible
     * thing, and a result about names, cancellation or an interrupted overwrite is only evidence about the
     * rsync it actually ran.
     */
    private static final AtomicReference<String> RSYNC_BINARY =
            new AtomicReference<>(configuredRsyncBinary());

    private static String configuredRsyncBinary() {
        String pinned = System.getenv("FCU_RSYNC");
        return pinned == null || pinned.isBlank() ? "rsync" : pinned;
    }
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> RSYNC_AVAILABLE
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** Engines share the process default unless one of them asks for something else. */
    private volatile String rsyncOverride;

    /** Destinations with a live job in this program. Released by {@link #finishJob}. */
    private static final Set<String> DESTINATIONS_IN_USE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> reservedDestination = new AtomicReference<>();

    /** Preflight as this engine would run it, including its own rsync override. */
    public List<String> preflight(JobRequest request) {
        return validate(request, rsyncBinaryInUse());
    }

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

    /**
     * Everything one job needs, built by the frame (or a test) and immutable from then on.
     * {@code dryRun} runs rsync with {@code --dry-run}: the work set is computed and the hashes are
     * compared, but nothing is written.
     */
    public record JobRequest(Path sourceDir, Path destDir, JobType jobType,
                             Selection sourceSelection, Selection destinationSelection,
                             ScanOptions scanOptions, boolean detectExtraFiles, boolean dryRun) {
        public JobRequest {
            scanOptions = scanOptions == null ? ScanOptions.DEFAULTS : scanOptions;
        }

        public JobRequest(Path sourceDir, Path destDir, JobType jobType, Selection sourceSelection,
                          Selection destinationSelection, ScanOptions scanOptions, boolean detectExtraFiles) {
            this(sourceDir, destDir, jobType, sourceSelection, destinationSelection, scanOptions,
                    detectExtraFiles, false);
        }
    }

    /**
     * One verified file's outcome, with full digests so a report can be exported from it. {@code kind}
     * is {@code OK}, {@code MISMATCH}, {@code MISSING}, {@code EXTRA} or {@code COPIED}; a hash is null
     * when that side had no file, which is also the case for every COPIED row. A verifying job reports
     * the verdict instead of COPIED: the same file does not appear twice, once as a transfer and once as
     * a result. COPIED appears only when verification is off, where the transfer is the only fact the
     * job knows, and it is deliberately not coloured like a passed check - nothing was checked.
     */
    /**
     * What one file is known to be, as one row.
     *
     * <p>Two separate questions are answered here and must not be collapsed: what happened to the
     * bytes ({@link TransferDisposition}) and whether the two ends agree
     * ({@link #kind()} for a verifying job - the verdict). A job that copied a file and then verified
     * it has a row whose transfer says COPIED and whose verdict says MATCH; a job that was stopped
     * over an overwrite knows neither, and its row says UNCERTAIN and PENDING rather than either a
     * silence or a success.
     */
    public record FileOutcome(String relPath, String kind, String sourceHash, String destHash,
                              TransferDisposition transfer) {
        /** A row with no transfer question to answer: a verify-only job, or a bookkeeping row. */
        public FileOutcome(String relPath, String kind, String sourceHash, String destHash) {
            this(relPath, kind, sourceHash, destHash, null);
        }
    }

    /**
     * What a copy job did to one file.
     *
     * <p>{@link #UNCERTAIN} exists because rsync announces a file when it <b>starts</b> the transfer
     * (measured on 3.1.3 and 3.4.0), so "announced" says nothing about completion, and because an
     * interrupted overwrite cannot be told apart from an untouched one by looking at the destination
     * alone: same name, same size, same digest, same inode on disk after a cancelled copy, and the
     * bytes are the ones that were already there. Guessing COPIED there records a false event.
     */
    public enum TransferDisposition {
        /** Nothing was written for this file in this job (verify-only, or never reached). */
        NOT_COPIED,
        /** A dry run: the file is named, nothing was written. */
        WOULD_COPY,
        /** The transfer is known to have completed - see the rules in DESIGN.md section 3.4. */
        COPIED,
        /** A transfer started; whether it finished cannot be shown from what is on disk. */
        UNCERTAIN;

        public String describe() {
            switch (this) {
                case NOT_COPIED: return "not copied";
                case WOULD_COPY: return "would be copied (dry run)";
                case COPIED: return "copied";
                case UNCERTAIN: return "transfer uncertain";
                default: return name();
            }
        }
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
    /**
     * Every process the live rsync had spawned, snapshotted while the parent was still alive. A local
     * rsync copy is three processes (measured on 3.1.3 and 3.4.0), and killing only the one Java started
     * leaves the other two reading the source after this program has exited. The snapshot is refreshed
     * when the job ends, because a child forked after the first snapshot would otherwise never be seen.
     */
    private final AtomicReference<List<ProcessHandle>> spawnedTree = new AtomicReference<>(List.of());
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
    private volatile long bandwidthLimitKbps;
    private volatile int resultLimit = MAX_OUTCOMES;
    /** Set by the job thread; read by the watchdog to tell a live transfer from a hung engine. */
    private volatile String jobPhase = "idle";

    public FileSyncEngine() {
        watchdog.scheduleAtFixedRate(this::watchdogLoop,
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

    /** Caps rsync's transfer rate in KB/s ({@code --bwlimit}); 0 means unlimited. */
    public void setBandwidthLimitKbps(long kbps) {
        this.bandwidthLimitKbps = Math.max(0L, kbps);
    }

    public long bandwidthLimitKbps() {
        return bandwidthLimitKbps;
    }

    /**
     * How many individual results a job keeps in memory - which is also how many rows the results table,
     * and an export taken from it, can contain. Counters stay exact whatever this is. Set it while the
     * engine is idle.
     */
    public void setResultLimit(int limit) {
        this.resultLimit = Math.max(1, limit);
    }

    public int resultLimit() {
        return resultLimit;
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
        final ProgressListener progressListener = withoutListener(listener);
        if (closed || !jobRunning.compareAndSet(false, true)) {
            return false;
        }
        beginJob(request, progressListener);
        try {
            jobWorker.submit(() -> {
                JobResult result;
                try {
                    result = runGuarded(request, progressListener);
                } catch (Throwable t) {
                    // An Error that escaped the guard (usually an OutOfMemoryError while building its own
                    // message) must still release the engine: a job thread that dies quietly would leave
                    // it reporting RUNNING forever with no way to start another job.
                    result = failure("Job aborted: " + t, List.of(String.valueOf(t)), List.of(), 0);
                }
                finishJob(result, progressListener);
            });
            return true;
        } catch (RuntimeException e) {
            jobRunning.set(false);
            status.set(JobStatus.FAILED);
            throw e;
        }
    }

    /** Synchronous variant, for tests and headless callers. Blocks until the job finishes. */
    public JobResult execute(JobRequest request, ProgressListener listener) {
        listener = withoutListener(listener);
        if (closed) {
            throw new IllegalStateException("Engine is closed");
        }
        if (!jobRunning.compareAndSet(false, true)) {
            throw new IllegalStateException("Engine is busy: a job is already running");
        }
        beginJob(request, listener);
        return finishJob(runGuarded(request, listener), listener);
    }

    /** A caller that does not want progress callbacks gets silence, not a job that fails on a null. */
    private static ProgressListener withoutListener(ProgressListener listener) {
        return listener == null ? NO_PROGRESS : listener;
    }

    private static final ProgressListener NO_PROGRESS = new ProgressListener() {
        @Override
        public void onCompletion(JobResult result) {
        }
    };

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
        String reserved = reservedDestination.getAndSet(null);
        if (reserved != null) {
            DESTINATIONS_IN_USE.remove(reserved);
        }
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
        } catch (OutOfMemoryError e) {
            // A selection too large for this JVM is a job failure with a fixable cause, not a crash. By
            // the time it gets here doExecute's own lists are unreachable, so there is heap left to
            // build this message with.
            return failure("Job aborted: the JVM ran out of memory while working on this selection. Try a "
                            + "narrower selection, turn off destination-only detection, or start the program "
                            + "with a larger heap (java -Xmx1g -jar ...).",
                    List.of("OutOfMemoryError"), List.of(), 0);
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
        return validate(request, rsyncBinary());
    }

    /** Preflight against a specific rsync binary, which is what an engine with an override needs. */
    public static List<String> validate(JobRequest request, String rsyncBinary) {
        List<String> problems = new ArrayList<>();
        Path source = request.sourceDir();
        Path dest = request.destDir();
        boolean copies = request.jobType().copies();

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
        if (copies && !isRsyncAvailable(rsyncBinary)) {
            problems.add("rsync was not found (looked for '" + rsyncBinary
                    + "'). Install rsync, or use Verify Only, which needs no rsync.");
        }
        return problems;
    }

    /** Is the process default rsync present and runnable? */
    public static boolean isRsyncAvailable() {
        return isRsyncAvailable(rsyncBinary());
    }

    /** Is this rsync present and runnable? Probed once per binary and cached. */
    public static boolean isRsyncAvailable(String binary) {
        Boolean cached = RSYNC_AVAILABLE.get(binary);
        if (cached != null) {
            return cached;
        }
        boolean available;
        try {
            // "Available" means runnable, not healthy: a missing binary fails to start at all, while
            // a runnable one that exits non-zero is a problem the job itself should report.
            Process probe = new ProcessBuilder(List.of(binary, "--version"))
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
        RSYNC_AVAILABLE.putIfAbsent(binary, available);
        return available;
    }

    /**
     * Process-wide default, and the test seam: engines with no override of their own use this.
     */
    static void setRsyncBinary(String binary) {
        RSYNC_BINARY.set(binary == null || binary.isBlank() ? "rsync" : binary);
        RSYNC_AVAILABLE.clear();
    }

    static String rsyncBinary() {
        return RSYNC_BINARY.get();
    }

    /**
     * Use a different rsync for this engine only. Pointing the whole process at a binary was a hidden
     * coupling: a GUI and a CLI job in one JVM would have to agree about which rsync the other runs.
     */
    public void useRsync(String binary) {
        rsyncOverride = binary == null || binary.isBlank() ? null : binary;
    }

    /** The rsync this engine runs: its own override if it has one, otherwise the process default. */
    public String rsyncBinaryInUse() {
        String override = rsyncOverride;
        return override != null ? override : rsyncBinary();
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

        List<String> problems = validate(request, rsyncBinaryInUse());
        if (!problems.isEmpty()) {
            return failure("Preflight failed: " + String.join("; ", problems), problems, List.of(), 0);
        }

        // One job per destination per program. Two jobs writing the same directory interleave: each sees
        // the other's files as its own results, and a partial-transfer report can name a file the other
        // job is still writing. This is an in-process guard, not a lock - a second process is a different
        // story, and writing a lock file into someone's destination tree is not a decision to make here.
        String destinationKey = canonical(destDir).toString();
        if (!DESTINATIONS_IN_USE.add(destinationKey)) {
            return failure("Another job in this program is already using the destination " + destDir
                    + ". Wait for it to finish, or point this job at a different directory: two jobs writing"
                    + " one directory produce numbers neither of them can vouch for.",
                    List.of("destination busy: " + destinationKey), List.of(), 0);
        }
        reservedDestination.set(destinationKey);

        Selection src = normalize(request.sourceSelection());
        Selection dst = normalize(request.destinationSelection());
        ScanOptions options = request.scanOptions();
        boolean copies = request.jobType().copies();
        boolean verifies = request.jobType().verifies();

        if (src.selected().isEmpty() && dst.selected().isEmpty()) {
            return failure("No files were selected: an empty selection means no files, not all files.",
                    List.of(), List.of(), 0);
        }

        markActivity("scanning " + sourceDir);
        DirectoryScanner.ScanResult scan = DirectoryScanner.scanFiles(sourceDir, options);
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
            if (excludedAtDestination(dst, rel, !copies)) {
                continue;
            }
            workSet.add(entry);
        }

        // Destination-only detection is the only reason to scan the destination. Doing it here instead of
        // at the top of the job keeps that scan inside a frame that ends immediately: on a 300k-file tree
        // its entry list was tens of megabytes held for the whole job for information one checkbox asks
        // for. With the checkbox off the scan does not happen at all.
        DestinationSides destination = !copies && request.detectExtraFiles()
                ? scanDestinationSides(destDir, options, sourceRels, dst)
                : new DestinationSides(List.of(), List.of());
        List<FileEntry> extras = destination.extras();

        int workTotal = workSet.size() + extras.size();

        // A ticked path with no scanned file behind it is not the same as an unticked one: the user
        // asked for it and it is gone (deleted, or filtered out by an ignore pattern). Silently doing
        // without it is how a typo in a CLI list or a file deleted after the scan turns into a job
        // that reports success.
        List<String> notFound = new ArrayList<>(src.selected());
        notFound.removeAll(sourceRels);
        notFound.sort(Comparator.naturalOrder());
        // Nothing after this point needs the set of every source path, and on a large tree that set is a
        // lot of memory to carry through a transfer for a lookup that is already finished.
        sourceRels = null;
        String notFoundText = describeNames(notFound);

        if (workTotal == 0) {
            String suffix = notFound.isEmpty() ? "" : " Selected but not found in the source: " + notFoundText + ".";
            return failure("Empty work set: 0 selected source files and 0 destination-only files survived "
                            + "the selection, so there is nothing to do." + suffix,
                    List.of(), List.of(), 0);
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>(scan.warnings());
        warnings.addAll(destination.warnings());
        if (scan.skippedLinks() > 0) {
            warnings.add(scan.skippedLinks() + " symbolic link(s) in the source were ignored; change the "
                    + "symlink policy to copy or verify them.");
        }
        if (!notFound.isEmpty()) {
            warnings.add(notFound.size() + " selected file(s) were not found in the source and were not "
                    + "touched: " + notFoundText + ".");
        }

        // Two names that differ only by case, or only by how an accent is composed, are two files here and
        // one file on a case-insensitive or normalising destination - a Windows share, exFAT, some macOS
        // volumes. On a case-sensitive filesystem nothing is wrong, so this names the risk instead of
        // refusing the job. What it must not do is overwrite one file with the other and then report a
        // mismatch as if the copy had been honest.
        List<String> collisions = collidingNames(workSet);
        if (!collisions.isEmpty()) {
            warnings.add(collisions.size() + " name collision(s) in the work set: "
                    + describeNames(collisions) + ". These names differ only by case or by how an accent is"
                    + " composed. On a case-insensitive or normalising destination they are the same file,"
                    + " and the later transfer overwrites the earlier one.");
        }

        int rsyncExitCode = 0;
        ProgressReporter progress = new ProgressReporter(workTotal, listener);
        List<FileOutcome> outcomes = new ArrayList<>();
        Map<String, String> sourceHashes = Map.of();
        boolean dryRun = request.dryRun();
        // Where each selected file would land: used to recognise the hidden partial files a killed
        // rsync leaves behind (see interruptedTempFiles).
        List<Path> destinationTargets = new ArrayList<>();
        // The names rsync announced, in the order it announced them. Every one of them needs a row: a
        // job with verification off has nothing else to report, and a job that was stopped mid-transfer
        // has to say which of these files it is uncertain about.
        List<String> announcedNames = new ArrayList<>();
        // What the destination looked like before rsync ran. Only this comparison can tell a completed
        // transfer from an interrupted one when the two files are the same size.
        TransferLedger ledger = null;
        // Per announced file, what the destination can prove about its transfer (A10, 2.5).
        Map<String, TransferDisposition> transfers = Map.of();

        if (copies && verifies) {
            // Its own phase, because it is its own wait: hashing reads the whole source before rsync has
            // written a single byte, and calling that "copying" is exactly how a job looks stalled on the
            // first file for an hour with an empty destination folder.
            listener.onPhase("hashing source");
            jobPhase = "hash";
            progress.update(0, "Hashing " + workSet.size()
                    + " source file(s) - nothing is written to the destination during this part...");
            // Hashed once, before the copy: these same digests are reused for the comparison below.
            sourceHashes = hashFiles(workSet, "source", warnings);
        }

        if (copies) {
            listener.onPhase(dryRun ? "dry run" : "copying");
            jobPhase = "copy";
            if (verifies) {
                progress.update(0, "Transferring " + workSet.size() + " file(s)...");
            } else {
                // Reading every file to compute a digest that nothing will use is the expensive part of
                // the job, so a job that will not compare reads nothing until rsync does.
                progress.update(0, "Copying without verification: source files are not hashed.");
            }

            for (FileEntry entry : workSet) {
                destinationTargets.add(destDir.resolve(entry.relPath()));
            }
            if (!dryRun) {
                // Snapshot before the transfer: what the job intended to write, and what the destination
                // held beforehand. The snapshot loop is cancellable because on a slow mount it can be the
                // longest thing the job does before rsync starts.
                ledger = new TransferLedger(sourceDir, destDir, workSet, warnings,
                        () -> cancelRequested.get() || deadlineExceeded());
            }

            Path filesFrom = writeFilesFrom(workSet, warnings);
            if (filesFrom == null) {
                return failure("No selected file name could be handed to rsync (each contains a newline, "
                        + "carriage return or NUL byte); deselect them and retry.", errors, warnings, 0);
            }
            try {
                // Asked to stop, and rsync has not started yet: starting it now would put a process on the
                // source disk that nothing in this program is watching any more.
                if (cancelRequested.get()) {
                    return abortedBeforeTransfer("Job cancelled before rsync started: no file was transferred.",
                            outcomes, errors, warnings, verifies);
                }
                if (timeoutWasTriggered()) {
                    return abortedBeforeTransfer("Job aborted: it exceeded the " + jobTimeoutMillis
                            + " ms timeout before rsync started.", outcomes, errors, warnings, verifies);
                }

                Process process = new ProcessBuilder(
                        buildRsyncCommand(sourceDir, destDir, filesFrom, options, dryRun))
                        .redirectErrorStream(true)
                        .start();
                liveProcess.set(process);
                spawnedTree.set(snapshotTree(process));
                // A cancellation that landed between the check above and the spawn still has to reach this
                // process, because the person who pressed Cancel has already been told the job stopped.
                if (cancelRequested.get() || deadlineExceeded()) {
                    terminateRsync();
                }

                int copied = pumpRsyncOutput(process, sourceDir, progress, errors, warnings, listener, dryRun,
                        announcedNames);
                rsyncExitCode = process.waitFor();
                sweepRsyncTree(process);
                markActivity("rsync exit " + rsyncExitCode);

                // What happened to each announced file, decided before any early return below, because a
                // job that is about to be reported as cancelled or failed still owes the user a list of
                // the files it did and did not finish.
                boolean cleanCopy = !timeoutWasTriggered() && !cancelRequested.get()
                        && isSuccessfulExit(rsyncExitCode);
                transfers = transferFacts(ledger, announcedNames, cleanCopy, dryRun);
                if (!cleanCopy) {
                    // A job that is about to be reported as cancelled, timed out or failed still owes the
                    // user a per-file answer. Without it the report is a count with no names, and a count
                    // of "1 copied" over two announced files says nothing about which one.
                    if (verifies) {
                        recordPendingTransfers(outcomes, listener, transfers);
                    } else {
                        recordTransferRows(outcomes, listener, transfers, warnings);
                    }
                }

                if (timeoutWasTriggered()) {
                    return timeoutResult(progress, outcomes, errors, warnings, destinationTargets, dryRun,
                            verifies);
                }
                if (cancelRequested.get()) {
                    return cancelledDuringCopy(progress, outcomes, rsyncExitCode, errors, warnings,
                            destinationTargets, dryRun, verifies);
                }
                if (!isSuccessfulExit(rsyncExitCode)) {
                    errors.add("rsync exited with code " + rsyncExitCode);
                    return rsyncFailedDuringCopy(progress, outcomes, rsyncExitCode, errors, warnings,
                            destinationTargets, dryRun, verifies);
                }
                if (rsyncExitCode == 24) {
                    warnings.add("rsync exit code 24: some files vanished before they could be transferred.");
                }
                progress.copyComplete(copied, dryRun);
            } finally {
                Files.deleteIfExists(filesFrom);
            }
        }

        if (!verifies) {
            // Nothing was compared, so the transfer is the only fact known about each file. Without these
            // rows a copy-only job leaves an empty Results tab and an empty export, which reads as though
            // nothing happened when hundreds of files were in fact written.
            // A dry run wrote nothing, so its rows are named for what would have happened. An export that
            // says COPIED for a file that was never written would be a record of an event that did not occur.
            recordTransferRows(outcomes, listener, transfers, warnings);
            if (progress.copied() > announcedNames.size()) {
                warnings.add("Only the first " + announcedNames.size() + " copied file(s) are listed; the copy"
                        + " reported " + progress.copied() + ". Raise the results limit (--max-results) if"
                        + " the export must name every file.");
            }
            if (timeoutWasTriggered()) {
                return timeoutResult(progress, outcomes, errors, warnings, destinationTargets, dryRun, verifies);
            }
            if (cancelRequested.get()) {
                return cancelledDuringCopy(progress, outcomes, rsyncExitCode, errors, warnings,
                        destinationTargets, dryRun, verifies);
            }
            progress.finish(dryRun ? "Dry run complete" : "Copy complete");
            listener.onPhase("done");
            return completedResult(progress, outcomes, errors, warnings, rsyncExitCode, dryRun,
                    request.jobType());
        }

        listener.onPhase("verifying");
        jobPhase = "verify";
        progress.verifyStart(copies);

        if (!copies) {
            sourceHashes = Map.of();
        }
        verifyEntries(workSet, extras, destDir, sourceHashes, progress, outcomes, listener,
                warnings, !copies, transfers);
        if (timeoutWasTriggered()) {
            return timeoutResult(progress, outcomes, errors, warnings, destinationTargets, dryRun, verifies);
        }
        if (cancelRequested.get()) {
            return cancelledDuringVerify(progress, outcomes, errors, warnings);
        }

        if (outcomes.size() >= resultLimit) {
            warnings.add("Results list truncated at " + resultLimit + " entries; counters are still exact. "
                    + (dryRun ? "" : "Raise it (CLI: --max-results N) if the export must list every file."));
        }

        if (timeoutWasTriggered()) {
            return timeoutResult(progress, outcomes, errors, warnings, destinationTargets, dryRun, verifies);
        }
        if (cancelRequested.get()) {
            return cancelledDuringVerify(progress, outcomes, errors, warnings);
        }

        progress.finish("Verification complete");
        listener.onPhase("done");
        return completedResult(progress, outcomes, errors, warnings, rsyncExitCode, dryRun, request.jobType());
    }

    /**
     * The COMPLETED result, built the same way whether it came out of a copy, a comparison, or both.
     * A job whose verification was switched off says so here as well as in the warnings: a copy that was
     * never compared has to be readable as that, and not mistaken for a copy that passed.
     */
    private JobResult completedResult(ProgressReporter progress, List<FileOutcome> outcomes, List<String> errors,
                                      List<String> warnings, int rsyncExitCode, boolean dryRun, JobType jobType) {
        if (dryRun && jobType.copies()) {
            warnings.add(0, "Dry run: rsync ran with --dry-run, so no file was written or changed.");
        }
        if (!jobType.verifies()) {
            warnings.add("Verification was turned off: no file was hashed or compared. This job reports what"
                    + " was copied, not whether the copies are correct.");
        }
        return new JobResult(JobStatus.COMPLETED, completionMessage(progress, dryRun, jobType),
                progress.processed(), progress.matched(), progress.mismatched(), progress.missing(),
                progress.extra(), progress.copied(), rsyncExitCode, outcomes, errors, warnings);
    }

    private static String completionMessage(ProgressReporter progress, boolean dryRun, JobType jobType) {
        if (!jobType.verifies()) {
            return dryRun
                ? "Dry run - nothing was written: " + progress.copied()
                    + " file(s) would have been copied. Verification was turned off, so nothing was compared."
                : "Copy complete: " + progress.copied() + " file(s) copied. Verification was turned off, so"
                    + " none of them were compared.";
        }
        StringBuilder message = new StringBuilder(dryRun ? "Dry run - nothing was written: " : "Job complete: ")
                .append(progress.matched()).append(" matched, ")
                .append(progress.mismatched()).append(" mismatched, ")
                .append(progress.missing()).append(" missing, ")
                .append(progress.copied()).append(dryRun ? " would be copied" : " copied");
        if (progress.extra() > 0) {
            message.append(", ").append(progress.extra()).append(" destination-only");
        }
        return message.append(".").toString();
    }

    /** Records one verification outcome and the matching streaming-log callback (short hashes). */
    /**
     * The same, with what the job did to the file's bytes. A verifying job reports a verdict and a
     * transfer: "copied=1, checked=1, matched=1" is one file, and collapsing the two questions into one
     * word is what made a copied file look like it had never been touched.
     */
    private void recordOutcome(List<FileOutcome> outcomes, ProgressListener listener, String rel,
                               String kind, String sourceHash, String destHash,
                               TransferDisposition transfer) {
        if (outcomes.size() < resultLimit) {
            outcomes.add(new FileOutcome(rel, kind, sourceHash, destHash, transfer));
        }
        listener.onFileProcessed(rel, kind,
                sourceHash == null ? null : FileHasher.shortHash(sourceHash),
                destHash == null ? null : FileHasher.shortHash(destHash));
    }

    /**
     * An rsync error partway through a copy means a partial copy, not nothing at all. Until this, every
     * failure returned zeroed counters, so a job that had already written files reported "0 copied" while
     * those files sat in the destination. A cut-short job gets the same accounting as a cancelled one.
     */
    private JobResult rsyncFailedDuringCopy(ProgressReporter progress, List<FileOutcome> outcomes,
                                            int exitCode, List<String> errors, List<String> warnings,
                                            List<Path> destinationTargets, boolean dryRun, boolean verifies) {
        int announced = progress.copied();
        int completed = completedTransfers(progress, announced, dryRun);
        String message = "rsync failed (exit code " + exitCode + "): "
                + transfersPhrase(progress, announced, completed, dryRun)
                + ". " + (verifies ? "Nothing was verified" : "Verification was off")
                + "; files already in the destination were left as they are.";
        List<String> extended = new ArrayList<>(warnings);
        extended.add(leftoverWarning(interruptedTempFiles(destinationTargets)));
        return new JobResult(JobStatus.FAILED, message, progress.processed(), progress.matched(),
                progress.mismatched(), progress.missing(), progress.extra(), completed, exitCode,
                outcomes, errors, extended);
    }

    /**
     * What the destination can prove about each file rsync announced.
     *
     * <p>An announcement arrives when rsync starts a transfer, so it is not evidence of completion. For a
     * job that ran to the end cleanly the completion itself is the evidence. For a job that was stopped,
     * only a before/after comparison of the destination can decide, and when the file already existed and
     * still has the same identity, nothing can: the bytes on disk are the ones that were there already.
     * Those files are reported as UNCERTAIN rather than counted as copied.
     */
    private static Map<String, TransferDisposition> transferFacts(TransferLedger ledger,
                                                                  List<String> announced, boolean cleanCopy,
                                                                  boolean dryRun) {
        if (announced.isEmpty()) {
            return Map.of();
        }
        Map<String, TransferDisposition> facts = new LinkedHashMap<>();
        for (String rel : announced) {
            // No ledger means no before-state, which means nothing may be claimed - except a dry run, where
            // the honest answer is "would have been copied" and nothing was written.
            facts.put(rel, ledger == null
                    ? (dryRun ? TransferDisposition.WOULD_COPY : TransferDisposition.UNCERTAIN)
                    : ledger.disposition(rel, cleanCopy, dryRun));
        }
        return facts;
    }

    /**
     * One row per announced file, saying what the destination can prove about its transfer. A job that
     * wrote files and compared nothing has no verdicts, so these rows are its only record; a job that was
     * stopped mid-transfer has to distinguish the file it finished from the one it left half-written.
     */
    private void recordTransferRows(List<FileOutcome> outcomes, ProgressListener listener,
                                    Map<String, TransferDisposition> transfers, List<String> warnings) {
        List<String> unresolved = new ArrayList<>();
        for (Map.Entry<String, TransferDisposition> fact : transfers.entrySet()) {
            if (fact.getValue() == TransferDisposition.UNCERTAIN) {
                unresolved.add(fact.getKey());
            }
            recordOutcome(outcomes, listener, fact.getKey(), fact.getValue().name(), null, null,
                    fact.getValue());
        }
        if (!unresolved.isEmpty()) {
            warnings.add(unresolved.size() + " file(s) had a transfer that cannot be confirmed: rsync"
                    + " started them and the job stopped before it finished, and the destination cannot show"
                    + " whether the bytes arrived - a file that was already there, of the same size, looks"
                    + " exactly the same either way. Re-run the job with verification to check them: "
                    + describeNames(unresolved) + ".");
        }
    }

    /**
     * Rows for a verifying job that was stopped before it could verify anything: the transfer question is
     * answered as far as it can be, and the verdict says the check never ran.
     */
    private void recordPendingTransfers(List<FileOutcome> outcomes, ProgressListener listener,
                                        Map<String, TransferDisposition> transfers) {
        for (Map.Entry<String, TransferDisposition> fact : transfers.entrySet()) {
            recordOutcome(outcomes, listener, fact.getKey(), "PENDING", null, null, fact.getValue());
        }
    }

    private JobResult cancelledDuringCopy(ProgressReporter progress, List<FileOutcome> outcomes,
                                          int exitCode, List<String> errors, List<String> warnings,
                                          List<Path> destinationTargets, boolean dryRun, boolean verifies) {
        int announced = progress.copied();
        int completed = completedTransfers(progress, announced, dryRun);
        String checked = verifies
                ? progress.processed() + " file(s) were verified (matched: " + progress.matched()
                    + ", mismatched: " + progress.mismatched() + ", missing: " + progress.missing() + ")"
                : "verification was off, so nothing was compared";
        String message = "Job cancelled by the user: " + transfersPhrase(progress, announced, completed, dryRun)
                + ", and " + checked + ". rsync exit code: " + exitCode + ".";
        List<String> leftovers = interruptedTempFiles(destinationTargets);
        List<String> extended = new ArrayList<>(warnings);
        extended.add(leftoverWarning(leftovers));
        return new JobResult(JobStatus.FAILED, message, progress.processed(), progress.matched(),
                progress.mismatched(), progress.missing(), progress.extra(), completed, exitCode,
                outcomes, errors, extended);
    }

    /**
     * How many copies a job that was cut short may claim. Measured against rsync 3.1.3, 3.2.7, 3.3.0 and
     * 3.4.0: {@code --info=progress2} prints a progress fragment each time a transfer finishes, carrying
     * {@code (xfr#N, ...)}, and prints fragments carrying no xfr# while a transfer is still in flight.
     * So progress fragments without an xfr# mean nothing had finished yet, not that rsync stayed silent.
     * A dry run is exempt: nothing was written, so its count is intent rather than completed transfers.
     */
    private static int completedTransfers(ProgressReporter progress, int announced, boolean dryRun) {
        if (dryRun) {
            return announced;
        }
        int reported = progress.rsyncTransfers();
        if (reported >= 0) {
            return Math.min(announced, reported);
        }
        return progress.transferProgressSeen() ? 0 : announced;
    }

    /** The same distinction, in words a user can act on. */
    private static String transfersPhrase(ProgressReporter progress, int announced, int completed,
                                          boolean dryRun) {
        if (dryRun) {
            return announced + " file(s) would have been copied";
        }
        if (progress.rsyncTransfers() >= 0) {
            return "rsync announced " + announced + " file(s) for copy and completed " + completed
                    + " of them";
        }
        if (progress.transferProgressSeen()) {
            return "rsync announced " + announced + " file(s) for copy and completed none of them";
        }
        return "rsync announced " + announced
                + " file(s) for copy; it reported no progress, so how many finished is unknown";
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
                                    List<String> errors, List<String> warnings, List<Path> destinationTargets,
                                    boolean dryRun, boolean verifies) {
        int announced = progress.copied();
        int completed = completedTransfers(progress, announced, dryRun);
        String message = "Job aborted: it exceeded the " + jobTimeoutMillis + " ms timeout. "
                + transfersPhrase(progress, announced, completed, dryRun) + ", and "
                + (verifies ? progress.processed() + " file(s) were verified."
                    : "verification was off, so nothing was compared.");
        List<String> extended = new ArrayList<>(warnings);
        extended.add("Increase or clear the job timeout if this job is expected to take longer.");
        if (!destinationTargets.isEmpty()) {
            extended.add(leftoverWarning(interruptedTempFiles(destinationTargets)));
        }
        return new JobResult(JobStatus.FAILED, message, progress.processed(), progress.matched(),
                progress.mismatched(), progress.missing(), progress.extra(), completed, 0,
                outcomes, errors, extended);
    }

    /**
     * rsync transfers a file into a hidden temporary name ({@code .name.XXXXXX}) and renames it into
     * place only when the transfer completes. A transfer killed mid-flight leaves that file behind -
     * measured on rsync 3.2.7: a gracefully SIGTERM'd rsync sometimes removes it, a killed child never
     * does. The engine looks for them in the directories this job would have written to and reports what
     * it finds, with sizes. It does not delete them: deciding that a hidden file in someone's directory
     * is junk is the user's call, not this tool's.
     */
    static List<String> interruptedTempFiles(List<Path> destinationTargets) {
        Map<Path, List<String>> namesByParent = new LinkedHashMap<>();
        for (Path target : destinationTargets) {
            Path parent = target.getParent();
            Path name = target.getFileName();
            if (parent != null && name != null) {
                namesByParent.computeIfAbsent(parent, key -> new ArrayList<>())
                        .add(name.toString());
            }
        }
        List<String> found = new ArrayList<>();
        for (Map.Entry<Path, List<String>> group : namesByParent.entrySet()) {
            Path parent = group.getKey();
            Set<String> realNames = new HashSet<>(group.getValue());
            if (!Files.isDirectory(parent)) {
                continue;
            }
            try (var entries = Files.newDirectoryStream(parent)) {
                for (Path sibling : entries) {
                    Path entryName = sibling.getFileName();
                    if (entryName == null) {
                        continue;
                    }
                    String name = entryName.toString();
                    Matcher temp = RSYNC_TEMP_NAME.matcher(name);
                    if (!temp.matches() || !realNames.contains(temp.group(1)) || !Files.isRegularFile(sibling)) {
                        continue;
                    }
                    found.add(name + " (for " + temp.group(1) + ", " + humanBytes(Files.size(sibling)) + ")");
                }
            } catch (IOException ignored) {
                // A directory we cannot list is not a reason to fail an already-cancelled job.
            }
        }
        return found;
    }

    private static String leftoverWarning(List<String> leftovers) {
        if (leftovers.isEmpty()) {
            return "No partial rsync file was left in the destination.";
        }
        return leftovers.size() + " partial rsync file(s) left in the destination: "
                + describeNames(leftovers) + ". They are incomplete transfers - delete them or re-run "
                + "the copy to finish them.";
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }

    /** What scanning the destination contributes to a job: destination-only files and warnings. */
    private record DestinationSides(List<FileEntry> extras, List<String> warnings) {
    }

    private DestinationSides scanDestinationSides(Path destDir, ScanOptions options,
                                                 Set<String> sourceRels, Selection dst) {
        markActivity("scanning " + destDir + " for destination-only files");
        DirectoryScanner.ScanResult destScan = DirectoryScanner.scanFiles(destDir, options);
        List<FileEntry> extras = new ArrayList<>();
        for (FileEntry entry : destScan.files()) {
            String rel = entry.relPath();
            if (sourceRels.contains(rel)) {
                continue;
            }
            // An unticked destination row the user actually saw is an exclusion; a file that was never
            // in the list is not.
            if (excludedAtDestination(dst, rel, true)) {
                continue;
            }
            extras.add(entry);
        }
        return new DestinationSides(extras, destScan.warnings());
    }

    /**
     * One verdict per file: hash whatever still needs hashing, compare, report, and let the digests go.
     *
     * <p>This is deliberately not "hash every source file into a map, hash every destination file into a
     * second map, then walk the maps". Two full digest maps plus a resolved-path entry list per side is
     * what a 300k-file Verify Only was doing before, and it is memory the comparison never asks for
     * again: only a mismatch needs its digests kept, and those live in the bounded results list. Verdicts
     * are consumed in work-set order, so the results list and any export stay deterministic even though
     * the hashing runs in parallel.
     */
    private void verifyEntries(List<FileEntry> workSet, List<FileEntry> extras, Path destDir,
                               Map<String, String> knownSourceHashes, ProgressReporter progress,
                               List<FileOutcome> outcomes, ProgressListener listener,
                               List<String> warnings, boolean verifyOnly,
                               Map<String, TransferDisposition> transfers)
            throws NoSuchAlgorithmException, InterruptedException {
        int total = workSet.size() + extras.size();
        if (total == 0) {
            return;
        }
        int threads = Math.min(MAX_HASH_THREADS, Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "verify");
            thread.setDaemon(true);
            return thread;
        });
        List<Future<Verdict>> futures = new ArrayList<>(Math.min(total, 1024));
        Set<String> readProblems = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Set<String> unfollowedLinks = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Set<String> sourceMoved = java.util.concurrent.ConcurrentHashMap.newKeySet();
        try {
            for (int index = 0; index < total; index++) {
                if (abortRequested()) {
                    break;
                }
                final int i = index;
                futures.add(pool.submit(() -> verdict(i, workSet, extras, destDir, knownSourceHashes,
                        readProblems, unfollowedLinks, sourceMoved)));
            }
        } finally {
            pool.shutdown();
        }

        for (Future<Verdict> future : futures) {
            if (abortRequested()) {
                break;
            }
            Verdict verdict;
            try {
                verdict = future.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof NoSuchAlgorithmException algorithmException) {
                    throw algorithmException;
                }
                // A task that could not decide is not a verdict; the read problem it recorded (if any)
                // is reported below.
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (verdict == null) {
                continue;
            }
            markActivity("verified " + verdict.relPath());
            switch (verdict.kind()) {
                case "OK" -> progress.countMatched();
                case "MISSING" -> progress.countMissing();
                case "MISMATCH" -> progress.countMismatched();
                default -> progress.countExtra();
            }
            recordOutcome(outcomes, listener, verdict.relPath(), verdict.kind(),
                    verdict.sourceHash(), verdict.destHash(),
                    transfers.getOrDefault(verdict.relPath(), TransferDisposition.NOT_COPIED));
            progress.verifiedFile(!verifyOnly, verdict.relPath());
        }

        for (String problem : readProblems) {
            warnings.add("Could not read file: " + problem);
        }
        if (!sourceMoved.isEmpty()) {
            List<String> moved = new ArrayList<>(sourceMoved);
            Collections.sort(moved);
            warnings.add(moved.size() + " file(s) changed in the source while the job ran: "
                    + describeNames(moved) + ". Their comparison is against the digest taken before the copy"
                    + " started, so the difference here is a file that moved during the job rather than one"
                    + " the copy damaged.");
        }
        for (String rel : unfollowedLinks) {
            warnings.add("Not compared: the destination holds a symbolic link at " + rel
                    + " where a file was expected, and this tool does not follow links, so the contents "
                    + "behind it were not read.");
        }
    }

    /** A file's verdict. {@code kind} is OK, MISMATCH, MISSING or EXTRA; hashes are null where absent. */
    private record Verdict(String kind, String relPath, String sourceHash, String destHash) {
    }

    private Verdict verdict(int index, List<FileEntry> workSet, List<FileEntry> extras, Path destDir,
                            Map<String, String> knownSourceHashes, Set<String> readProblems,
                            Set<String> unfollowedLinks, Set<String> sourceMoved)
            throws NoSuchAlgorithmException {
        if (abortRequested()) {
            return null;
        }
        boolean destinationOnly = index >= workSet.size();
        FileEntry sourceEntry = destinationOnly ? null : workSet.get(index);
        String rel = destinationOnly ? extras.get(index - workSet.size()).relPath() : sourceEntry.relPath();

        // The scanner refuses to list symbolic links; the destination side is reached by resolving a
        // relative path, so a link can appear here even though it never appears in a list. Two cases are
        // not the same thing: a link the job created under the LINK policy, and a link that was already
        // sitting where a file was expected. The first is comparable by its target. The second would
        // mean reading a file outside the destination and calling the digest a comparison.
        Path destPath = destDir.resolve(rel);
        if (Files.isSymbolicLink(destPath)) {
            Path sourcePath = destinationOnly ? null : sourceEntry.absolutePath();
            if (sourcePath != null && Files.isSymbolicLink(sourcePath)) {
                String sourceLink = readLink(sourcePath);
                String destLink = readLink(destPath);
                return new Verdict(sourceLink != null && sourceLink.equals(destLink) ? "OK" : "MISMATCH",
                    rel, null, null);
            }
            unfollowedLinks.add(rel);
            String linkedSource = knownSourceHashes.get(rel);
            return destinationOnly
                ? new Verdict("EXTRA", rel, null, null)
                : new Verdict("MISMATCH", rel, linkedSource, null);
        }

        String destHash = hashFile(destDir.resolve(rel), rel, readProblems);
        if (destinationOnly) {
            return new Verdict("EXTRA", rel, null, destHash);
        }

        String sourceHash = knownSourceHashes.get(rel);
        if (sourceHash == null) {
            sourceHash = hashFile(sourceEntry.absolutePath(), rel, readProblems);
        }
        if (destHash != null && sourceHash != null && sourceHash.equals(destHash)) {
            return new Verdict("OK", rel, sourceHash, destHash);
        }
        if (sourceHash == null && !Files.isRegularFile(sourceEntry.absolutePath())) {
            return new Verdict("MISSING", rel, null, destHash);
        }
        if (destHash == null) {
            return new Verdict("MISSING", rel, sourceHash, null);
        }
        // A copy verifies against the digest taken before the transfer started. If the source has moved
        // since then, the honest description is a file that changed under the job, not one that was
        // damaged by it - and only a copy has a pre-transfer digest to make that distinction.
        String beforeCopy = knownSourceHashes.get(rel);
        if (beforeCopy != null) {
            String now = hashFile(sourceEntry.absolutePath(), rel, readProblems);
            if (now != null && !now.equals(beforeCopy)) {
                sourceMoved.add(rel);
            }
        }
        // A source file that exists but could not be hashed is not "missing": reporting it as such
        // would hide an unreadable file behind a word that means "not there".
        return new Verdict("MISMATCH", rel, sourceHash, destHash);
    }

    /** Where a link points, or null when it cannot be read. The target is never opened. */
    private static String readLink(Path link) {
        try {
            return String.valueOf(Files.readSymbolicLink(link));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Hash one file, or null when it is absent. Absence is not an error to report: the verdict turns it
     * into MISSING, and a warning per absent file would bury the log.
     */
    private String hashFile(Path path, String rel, Set<String> readProblems) throws NoSuchAlgorithmException {
        try {
            return FileHasher.computeHash(path);
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            readProblems.add(rel + " (" + e + ")");
            return null;
        } catch (RuntimeException e) {
            readProblems.add(rel + " (" + e + ")");
            return null;
        }
    }

    /**
     * Hashes a batch of files on a bounded pool. Unreadable files are omitted from the result and
     * recorded in {@code warnings}; absence is inferred by the caller from a missing hash and a missing
     * file. Used for the source digests Copy & Verify needs before rsync runs.
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
                if (abortRequested()) {
                    break;
                }
                Path path = entry.absolutePath();
                futures.put(entry.relPath(), pool.submit(() -> {
                    if (abortRequested()) {
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
    /**
     * Hands rsync the selected names, byte for byte.
     *
     * <p>Two properties of this list are load-bearing, and a program that gets either wrong quietly
     * copies the wrong set of files:
     *
     * <ul>
     *   <li><b>NUL-delimited, with {@code --from0}.</b> A line-delimited list cannot carry a name
     *       containing a newline, and the reader cannot tell where such a name ended.</li>
     *   <li><b>Every entry prefixed with {@code ./}.</b> In a file list, an entry starting with
     *       {@code #} or {@code ;} is a comment - measured on rsync 3.1.3 and 3.4.0, and
     *       {@code --from0} does not change that - so a file named {@code #secret} disappears from the
     *       job while rsync reports success. The prefix defeats the comment syntax and a leading
     *       {@code -} along with it, and says nothing about which file is meant.</li>
     * </ul>
     *
     * <p>The prefix is also why no name is rewritten here: what was scanned is what is written, after
     * the prefix. Only a NUL cannot be handed over, and a Java path cannot contain one anyway.
     */
    private Path writeFilesFrom(List<FileEntry> workSet, List<String> warnings) throws IOException {
        List<String> relPaths = new ArrayList<>();
        for (FileEntry entry : workSet) {
            String rel = entry.relPath();
            if (rel.indexOf('\0') >= 0) {
                // A NUL is the delimiter: a name containing one cannot be expressed in this format.
                warnings.add("Skipped from copy: file name contains a NUL byte: "
                        + RsyncNameCodec.forDisplay(rel));
                continue;
            }
            relPaths.add("./" + rel);
        }
        if (relPaths.isEmpty()) {
            return null;
        }
        Path listFile = Files.createTempFile("filecopy_list_", ".lst");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(relPaths.size() * 32);
        for (String rel : relPaths) {
            bytes.write(rel.getBytes(StandardCharsets.UTF_8));
            bytes.write(0);
        }
        Files.write(listFile, bytes.toByteArray());
        return listFile;
    }

    private List<String> buildRsyncCommand(Path sourceDir, Path destDir, Path filesFrom, ScanOptions options,
                                           boolean dryRun) {
        List<String> command = new ArrayList<>();
        command.add(rsyncBinaryInUse());
        command.add("-a");
        command.add("--checksum");
        command.add("--itemize-changes");
        command.add("--info=progress2");
        if (dryRun) {
            command.add("--dry-run");
        }
        long limit = bandwidthLimitKbps;
        if (limit > 0) {
            command.add("--bwlimit=" + limit);
        }
        if (options.symlinkPolicy() == ScanOptions.SymlinkPolicy.FOLLOW) {
            command.add("--copy-links");
        }
        // The list is NUL-delimited (see writeFilesFrom), and the mention line puts the name last
        // after a fixed separator, so a name containing spaces, a tab or a '|' cannot be misread.
        command.add("--from0");
        command.add("--out-format=%i|%n");
        command.add("--files-from=" + filesFrom);
        command.add(sourceDir.toString());
        command.add(destDir.toString());
        return command;
    }

    /**
     * Reads rsync's output until it ends, reporting progress as it goes, and returns the number of files
     * rsync announced. When {@code copiedNames} is non-null it also collects the announced names, for a
     * job with verification off: those names are that job's only record of what it wrote, and they become
     * its result rows.
     */
    private int pumpRsyncOutput(Process process, Path sourceDir, ProgressReporter progress, List<String> errors,
                                List<String> warnings, ProgressListener listener, boolean dryRun,
                                List<String> copiedNames)
            throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (abortRequested()) {
                    // The process is being killed; stop reading and stop counting announcements.
                    break;
                }
                Matcher xfr = RSYNC_XFR.matcher(line);
                if (xfr.find()) {
                    // rsync's own count of transfers it has finished. The only number that can tell an
                    // announced file from a completed one.
                    progress.rsyncTransfers(Integer.parseInt(xfr.group(1)));
                }
                Matcher transfer = RSYNC_TRANSFER.matcher(line);
                if (transfer.find()) {
                    // rsync's own stats line: bytes moved so far and the percentage of the whole transfer.
                    // The per-file figure is derived from these by the reporter. A --dry-run reports a
                    // meaningless 100% instantly, so it is ignored there.
                    if (!dryRun) {
                        progress.transferStats(Long.parseLong(transfer.group(1).replace(",", "")),
                                Integer.parseInt(transfer.group(2)),
                                transfer.group(3).replaceAll("\\s+", " "), transfer.group(4));
                    }
                    markActivity(line.trim());
                    continue;
                }
                int percent = rsyncTransferPercent(line);
                if (percent >= 0) {
                    // A stats line this rsync variant formats differently: the percentage is still usable,
                    // the bytes and rate are not, so the status line says less instead of guessing.
                    if (!dryRun) {
                        progress.transferPercentOnly(percent);
                    }
                    markActivity(line.trim());
                    continue;
                }
                switch (classifyLine(line)) {
                    case COPY -> {
                        progress.countCopied();
                        String rel = itemizePath(line);
                        if (rel != null) {
                            // Measured on 3.1.3 and 3.4.0: this line arrives when rsync starts the file, not
                            // when it finishes. It is what makes "which file is in flight right now"
                            // answerable, and the xfr# count on the stats line is what says a file finished.
                            progress.announcing(rel, sizeOfSourceFile(sourceDir, rel));
                            listener.onFileProcessed(rel, "TRANSFERRING", null, null);
                            if (copiedNames != null && copiedNames.size() < resultLimit) {
                                copiedNames.add(rel);
                            }
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
        if (classifyLine(line) != LineKind.COPY) {
            // A directory line carries a trailing '/' and no file to count; a message line carries no
            // name at all.
            return null;
        }
        return RsyncNameCodec.nameOf(line);
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

    /** Size of the file rsync has just started, or 0 when it cannot be asked. */
    private static long sizeOfSourceFile(Path sourceDir, String relPath) {
        try {
            return Files.size(sourceDir.resolve(relPath));
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }

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
    /**
     * Groups of work-set paths that would name the same file on a case-insensitive or normalising
     * destination. A group is only a collision when it holds more than one distinct path.
     */
    static List<String> collidingNames(Collection<FileEntry> entries) {
        Map<String, List<String>> byKey = new TreeMap<>();
        for (FileEntry entry : entries) {
            byKey.computeIfAbsent(collisionKey(entry.relPath()), key -> new ArrayList<>()).add(entry.relPath());
        }
        List<String> collisions = new ArrayList<>();
        for (List<String> group : byKey.values()) {
            if (group.size() > 1) {
                Collections.sort(group);
                collisions.add(String.join(" / ", group));
            }
        }
        return collisions;
    }

    /**
     * What a case-insensitive or Unicode-normalising filesystem would treat as one name: composed form
     * first, then case-folded. The directory part is part of the key, because {@code A.txt} and
     * {@code sub/a.txt} are different files even on such a destination.
     */
    static String collisionKey(String relPath) {
        return java.text.Normalizer.normalize(relPath, java.text.Normalizer.Form.NFC)
                .toLowerCase(java.util.Locale.ROOT);
    }

    static String describeNames(List<String> names) {
        int shown = Math.min(names.size(), 10);
        List<String> displayed = new ArrayList<>(shown);
        for (String name : names.subList(0, shown)) {
            // A name may contain a newline, and this text goes into a log line: an unescaped name would
            // forge an extra line and could be read as a different message.
            displayed.add(RsyncNameCodec.forDisplay(name));
        }
        String head = String.join(", ", displayed);
        return names.size() > shown ? head + " (+" + (names.size() - shown) + " more)" : head;
    }

    /** rsync exit codes 0 and 24 are success for our purposes; 24 is reported as a warning. */
    static boolean isSuccessfulExit(int exitCode) {
        return exitCode == 0 || exitCode == 24;
    }

    /** Normalizes caller-supplied relative paths to the FileEntry form. */
    /**
     * The destination side's exclusion rule, in one place so a preview of a job and the job itself cannot
     * drift: only a row the user was shown and left unticked excludes a file, and only for Verify Only.
     */
    static boolean excludedAtDestination(Selection destination, String relPath, boolean verifyOnly) {
        return verifyOnly && destination.known().contains(relPath) && !destination.selected().contains(relPath);
    }

    /**
     * What a job would process, decided by the rule the job uses rather than by a second guess. The GUI
     * shows this before Run is pressed, so the two checkbox lists cannot mean one thing on screen and
     * something else in the engine.
     */
    /**
     * @param untickedNames the first few excluded paths, enough to name a handful; the count above it is
     *                      always exact whatever the cap is.
     */
    public record WorkSetPlan(int selectedAtSource, int untickedAtDestination, int willProcess,
                              List<String> untickedNames) {

        public WorkSetPlan {
            untickedNames = List.copyOf(untickedNames);
        }

        @SuppressFBWarnings(value = "EI_EXPOSE_REP",
                justification = "The canonical constructor stores a List.copyOf, so this is an immutable "
                        + "list the plan owns, never a caller's list.")
        @Override
        public List<String> untickedNames() {
            return untickedNames;
        }

        /** The excluded paths, or null if there are too many to list usefully. */
        public String describeUnticked() {
            if (untickedNames.isEmpty() || untickedNames.size() < untickedAtDestination) {
                return null;
            }
            return String.join(", ", untickedNames);
        }
    }

    /**
     * The same preview for a caller that can answer "is this path a ticked source row?" in constant time:
     * the GUI, which keeps its list indexed by path. Equivalent to the overload above by construction, and
     * tested against it, because recomputing a work set from scratch on every checkbox click is what makes
     * a 100000 row list feel sluggish.
     */
    static WorkSetPlan planWorkSet(int selectedAtSource, Iterable<String> untickedAtDestination,
                                   Predicate<String> tickedAtSource) {
        int excluded = 0;
        List<String> names = new ArrayList<>();
        for (String rel : untickedAtDestination) {
            if (!tickedAtSource.test(rel)) {
                continue;
            }
            excluded++;
            if (names.size() < NAMED_EXCLUSIONS) {
                names.add(rel);
            }
        }
        return new WorkSetPlan(selectedAtSource, excluded, selectedAtSource - excluded, List.copyOf(names));
    }

    static WorkSetPlan planWorkSet(Collection<String> scannedSourcePaths, Selection source,
                                   Selection destination, JobType type) {
        Selection src = normalize(source);
        Selection dst = normalize(destination);
        // Destination unticks exclude only in a job that does not copy: a copy is driven by the source
        // list, and its destination rows are not something the user is being asked to interpret.
        boolean destinationTicksApply = !type.copies();
        int atSource = 0;
        int excluded = 0;
        List<String> names = new ArrayList<>();
        for (String rel : scannedSourcePaths) {
            if (!src.selected().contains(rel)) {
                continue;
            }
            atSource++;
            if (excludedAtDestination(dst, rel, destinationTicksApply)) {
                excluded++;
                if (names.size() < NAMED_EXCLUSIONS) {
                    names.add(rel);
                }
            }
        }
        return new WorkSetPlan(atSource, excluded, atSource - excluded, List.copyOf(names));
    }

    /**
     * Syntax only: separator translation, and a leading {@code /} or {@code ./} that a person types
     * but a scanned relative path never has.
     *
     * <p>It deliberately does <b>not</b> trim. Whitespace at either end of a name is part of that
     * name on POSIX filesystems, and a routine that trims a scanned name can produce the identity of
     * a different, existing file: with {@code " a "} and {@code "a"} both present, a trimmed
     * {@code " a "} selects {@code "a"} - including when {@code a} was the file the user excluded.
     * Trimming stays where it belongs, in the CLI's comma-separated syntax
     * ({@link Cli#splitList}), and the exact {@code --files-from0} channel exists for names it cannot
     * express.
     */
    static Set<String> normalize(Set<String> relPaths) {
        Set<String> normalized = new HashSet<>();
        for (String rel : relPaths) {
            if (rel == null) {
                continue;
            }
            String trimmed = rel.replace(java.io.File.separatorChar, '/');
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
        if (deadlineExceeded()) {
            terminateRsync();
        }
    }

    /**
     * A scheduled task that throws is never rescheduled, which would silently disable the deadline for
     * the rest of the engine's life - so the tick has to survive anything it can throw.
     */
    private void watchdogLoop() {
        try {
            watchdogTick();
        } catch (Throwable t) {
            markActivity("watchdog skipped: " + t.getClass().getSimpleName());
        }
    }

    /**
     * True when the deadline, not the user, ended the job. Both aborts set the cancel flag, so without
     * this the two reasons would be indistinguishable in the result.
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
        if (deadlineNanos == 0L || System.nanoTime() <= deadlineNanos) {
            return false;
        }
        // Record the timeout here rather than waiting for the watchdog's next tick: a phase that ran
        // past the deadline must not get to report the job as complete, nor report files it never
        // hashed as missing.
        if (timeoutTriggered.compareAndSet(false, true)) {
            markActivity("timeout after " + jobTimeoutMillis + " ms in phase " + jobPhase);
        }
        cancelRequested.set(true);
        return true;
    }

    /**
     * Whether a work loop should stop now: the user cancelled, or the deadline passed - in which case a
     * live rsync is killed instead of left running. Idempotent, so any thread may call it.
     */
    private boolean abortRequested() {
        if (cancelRequested.get()) {
            return true;
        }
        if (deadlineExceeded()) {
            terminateRsync();
            return true;
        }
        return false;
    }

    /**
     * Kills the live rsync and everything it spawned. Safe to call more than once.
     *
     * <p>The whole tree is snapshotted before anyone is signalled: once the parent dies its children are
     * re-parented to init, and a {@code descendants()} call made after that finds nothing at all. The
     * signal is SIGTERM first - rsync responds to it by deleting the hidden partial file it was writing,
     * which is what a cancelled job should leave behind in someone's destination folder, nothing - and
     * only survivors get SIGKILL.
     */
    private void terminateRsync() {
        Process process = liveProcess.get();
        if (process == null) {
            return;
        }
        List<ProcessHandle> tree = snapshotTree(process);
        signalTree(process, tree, false);
        if (!awaitTree(process, tree, TERM_GRACE_SECONDS)) {
            signalTree(process, tree, true);
            awaitTree(process, tree, TERM_GRACE_SECONDS);
        }
    }

    private static List<ProcessHandle> snapshotTree(Process process) {
        try {
            return process.descendants().toList();
        } catch (IllegalStateException | SecurityException e) {
            // Already gone, or not ours to read.
            return List.of();
        }
    }

    private static void signalTree(Process process, List<ProcessHandle> tree, boolean forcibly) {
        // Parent first: it is the one that cleans up partial files, and it passes the signal on.
        try {
            if (forcibly) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
        } catch (IllegalStateException | SecurityException e) {
            // Already gone.
        }
        for (ProcessHandle handle : tree) {
            try {
                if (forcibly) {
                    handle.destroyForcibly();
                } else {
                    handle.destroy();
                }
            } catch (IllegalStateException | SecurityException e) {
                // Already gone, or not ours to kill.
            }
        }
    }

    private static boolean awaitTree(Process process, List<ProcessHandle> tree, int seconds) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        try {
            while (System.nanoTime() < until) {
                if (process.isAlive()) {
                    Thread.sleep(100);
                    continue;
                }
                for (ProcessHandle handle : tree) {
                    if (handle.isAlive()) {
                        return false;
                    }
                }
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /**
     * rsync's parent can exit while its children are still finishing. Their data has been transferred -
     * rsync only reports success once the transfer is complete - but a child that outlives its parent is
     * orphaned to init and this program has no way to reach it again, so anything still alive here is
     * killed. This is what a "three rsync processes I never started" report means.
     */
    private void sweepRsyncTree(Process process) {
        List<ProcessHandle> tree = new ArrayList<>(spawnedTree.get());
        for (ProcessHandle handle : snapshotTree(process)) {
            if (!tree.contains(handle)) {
                tree.add(handle);
            }
        }
        spawnedTree.set(List.of());
        List<ProcessHandle> alive = new ArrayList<>();
        for (ProcessHandle handle : tree) {
            try {
                if (handle.isAlive()) {
                    alive.add(handle);
                }
            } catch (IllegalStateException e) {
                // Already gone.
            }
        }
        if (alive.isEmpty()) {
            return;
        }
        markActivity("killing " + alive.size() + " rsync process(es) that outlived their parent");
        signalTree(process, alive, false);
        if (!awaitTree(process, alive, 2)) {
            signalTree(process, alive, true);
        }
    }

    /** A job that was stopped before rsync was ever run: nothing moved, and it must not claim otherwise. */
    private JobResult abortedBeforeTransfer(String message, List<FileOutcome> outcomes, List<String> errors,
                                            List<String> warnings, boolean verifies) {
        String full = verifies ? message : message + " Verification was off, so nothing was compared.";
        return new JobResult(JobStatus.FAILED, full, 0, 0, 0, 0, 0, 0, 0, outcomes, errors, warnings);
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
        /** The file rsync is writing right now, and how big it is. */
        private String inFlight;
        private long inFlightSize;
        /** Bytes moved overall, and the part of that which belongs to files already finished. */
        private long transferredBytes = -1;
        private long transferredOfFinished;
        private int transfersDone;
        private String transferRate;
        private String transferElapsed;
        /** rsync's completed-transfer count; -1 until rsync reports one. */
        private int rsyncTransfers = -1;
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

        /** True once rsync had shown a transfer percentage, which is when its xfr# count is meaningful. */
        boolean transferProgressSeen() {
            return rsyncPercent >= 0;
        }

        int rsyncTransfers() {
            return rsyncTransfers;
        }

        void rsyncTransfers(int count) {
            rsyncTransfers = Math.max(rsyncTransfers, count);
        }

        /** One item rsync announced: a file, a changed file, a copied link. Counted whether or not the line carried a name. */
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

        /**
         * rsync has started transferring {@code relPath}. Its itemize line arrives at the start of that
         * file's transfer, so the previous file in flight - if there was one - has finished: rsync moves
         * files one at a time.
         */
        void announcing(String relPath, long size) {
            if (inFlight != null) {
                transfersDone++;
                transferredOfFinished += inFlightSize;
            }
            inFlight = relPath;
            inFlightSize = Math.max(0L, size);
            emitCopyProgress();
        }

        /** rsync's own stats line: bytes moved so far, the overall percentage, a rate, an elapsed time. */
        void transferStats(long bytes, int percent, String rate, String elapsed) {
            transferredBytes = Math.max(transferredBytes, bytes);
            rsyncPercent = Math.max(rsyncPercent, percent);
            transferRate = rate;
            transferElapsed = elapsed;
            emitCopyProgress();
        }

        /**
         * The bar follows rsync's overall percentage; the words say what the bar cannot: which file is
         * being written now, how much of it has landed, how many files are finished, and how fast it is
         * moving. A 30 GB file would otherwise show one number that barely moves for an hour.
         */
        /** A percentage with no byte counts behind it: the bar moves, the words stay general. */
        void transferPercentOnly(int percent) {
            rsyncPercent = Math.max(rsyncPercent, percent);
            emitCopyProgress();
        }

        private void emitCopyProgress() {
            int percent = (int) Math.round(Math.max(0, Math.min(100, rsyncPercent)) * COPY_PROGRESS_END / 100.0);
            StringBuilder text = new StringBuilder("Copying ");
            if (transferredBytes >= 0) {
                text.append(bytesText(transferredBytes)).append(" so far (").append(rsyncPercent).append("%)");
            } else {
                text.append(copied).append(" file(s) announced");
            }
            if (inFlight != null) {
                text.append(" | now writing: ").append(inFlight);
                if (inFlightSize > 0) {
                    long mine = Math.max(0L, transferredBytes - transferredOfFinished);
                    long clamped = Math.min(mine, inFlightSize);
                    text.append(" (").append(bytesText(clamped)).append(" of ").append(bytesText(inFlightSize))
                        .append(", ").append(Math.round(clamped * 100.0 / inFlightSize)).append("%)");
                }
            }
            if (workTotal > 0) {
                // rsync's own completed-transfer count is the authority: it also counts the last file,
                // which no later announcement would have implied.
                int done = Math.max(transfersDone, Math.max(0, rsyncTransfers));
                text.append(" | ").append(Math.min(done, workTotal)).append(" of ").append(workTotal)
                    .append(" done");
            }
            if (transferRate != null) {
                text.append(" | ").append(transferRate).append(", elapsed ").append(transferElapsed);
            }
            emit(percent, text.toString());
        }

        void copyComplete(int copied, boolean dryRun) {
            emit(COPY_PROGRESS_END, (dryRun ? "Dry run: " : "Copy complete: ") + copied
                    + (dryRun ? " file(s) would be transferred" : " file(s) transferred"));
        }

        void verifyStart(boolean afterCopy) {
            emit(afterCopy ? COPY_PROGRESS_END : 0, "Verified 0 of " + workTotal);
        }

        void verifiedFile(boolean afterCopy, String relPath) {
            double ratio = processed / (double) workTotal;
            int percent = afterCopy
                    ? (int) Math.round(COPY_PROGRESS_END + ratio * (100 - COPY_PROGRESS_END))
                    : (int) Math.round(ratio * 100);
            emit(percent, "Verified " + processed + " of " + workTotal + " | just compared: " + relPath);
        }

        void finish(String label) {
            emit(100, label);
        }

        /** A size in the unit a person would read for it, not a raw byte count. */
        private static String bytesText(long bytes) {
            if (bytes >= 1L << 30) {
                return String.format("%.2f GB", bytes / (double) (1L << 30));
            }
            if (bytes >= 1L << 20) {
                return String.format("%.1f MB", bytes / (double) (1L << 20));
            }
            if (bytes >= 1L << 10) {
                return String.format("%.1f kB", bytes / (double) (1L << 10));
            }
            return bytes + " B";
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
