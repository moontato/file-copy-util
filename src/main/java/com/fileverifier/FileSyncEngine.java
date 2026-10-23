package com.fileverifier;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    /**
     * Why a job stopped before it was finished.
     *
     * <p>A timeout is a setting, a cancellation is a person pressing a button, and a close is the program
     * going away. They are three different facts and a reader of a report is entitled to know which one
     * happened: "FAILED - 12 of 400 checked" tells nobody whether the files are fine and the limit was
     * short, or the copy was stopped on purpose.
     */
    public enum AbortCause { NONE, USER_CANCELLED, TIMED_OUT, ENGINE_CLOSED }

    /** Copy phase occupies 0..70% of the bar, verification 70..100%. */
    static final int COPY_PROGRESS_END = 70;

    /** How long the tree gets to stop itself before it is killed outright. */
    private static final int TERM_GRACE_SECONDS = 3;

    /**
     * How long a job thread gets to finish publishing after its process tree had been killed. Beyond this the
     * engine reports the job as stopped rather than describing it as running indefinitely.
     */
    private static final long TERMINAL_GRACE_MILLIS = 5_000L;

    /** Closing does not wait the watchdog's grace: the program is going away. */
    private static final long CLOSE_TERMINATION_MILLIS = 1_000L;
    /** How long a closing program waits for a completion callback that may never return. */
    private static final long CLOSE_DELIVERY_MILLIS = 1_000L;
    /** How long an interrupted hash gets to prove it stopped before the report says a read may continue. */
    private static final long HASH_DRAIN_GRACE_MILLIS = 2_000L;
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

    /**
     * The destinations a job in this program currently holds a claim on. A seam for the lifecycle fixtures:
     * a claim left behind by a job that no longer runs would keep that directory shut to every job that comes
     * after, with nothing alive to hand it back.
     */
    static Set<String> destinationClaimsInUse() {
        return Set.copyOf(DESTINATIONS_IN_USE);
    }
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

    /**
     * What a job found, and what it can say it did.
     *
     * <p>Every figure is a tally of one kind of event, and no figure is a tally of another kind of event: the
     * verdict figures come from verdicts, the transfer figures come from per-file transfer dispositions, and
     * rsync's announcement count is kept apart because an announcement says a transfer started, not that it
     * finished. {@link #filesChecked()} is a sum of the verdict figures rather than a counter of its own, which
     * is what stops it drifting away from them.
     *
     * @param filesCopied          files this job can show it wrote (disposition {@code COPIED})
     * @param filesWouldCopy       files a dry run would have written
     * @param filesUncertain       files rsync started and the destination cannot account for
     * @param filesNotTransferred  rows the job knows about that were not transferred
     * @param transfersAnnounced   how many transfers rsync announced; an observation, never a completion
     * @param transfersCompletedByRsync how many transfers rsync's own {@code (xfr#N)} count claimed as
     *                             finished (0 also covers a run that printed no such count) - another
     *                             observation, kept apart from what this program can prove
     * @param filesProcessed       rows this job reported on, extras included - the progress-unit count
     * @param rowsOmitted          how many per-file rows this job produced but the report does not hold,
     *                             because of the results limit - so an incomplete report cannot read as a
     *                             complete one
     */
    public record JobResult(JobStatus status, String message,
                            int filesMatched, int filesMismatched, int filesMissing, int filesExtra,
                            int filesCopied, int filesWouldCopy, int filesUncertain,
                            int filesNotTransferred, int transfersAnnounced, int transfersCompletedByRsync,
                            int filesProcessed,
                            int rsyncExitCode, List<FileOutcome> outcomes, List<String> errors,
                            List<String> warnings, int rowsOmitted, AbortCause abortCause) {

        public JobResult {
            outcomes = List.copyOf(outcomes);
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
            abortCause = abortCause == null ? AbortCause.NONE : abortCause;
        }

        /**
         * The shape used before the transfer figures were kept apart from the verdict figures, where the
         * leading figure counted processed rows. That figure is {@code filesProcessed} now, and "checked" is a
         * sum rather than a stored count. Hand-built results announce exactly the transfers they claim, because
         * nothing else is known about them. New code uses the full constructor.
         */
        public JobResult(JobStatus status, String message,
                         int filesProcessed, int filesMatched, int filesMismatched,
                         int filesMissing, int filesExtra, int filesCopied,
                         int rsyncExitCode, List<FileOutcome> outcomes, List<String> errors,
                         List<String> warnings) {
            this(status, message, filesMatched, filesMismatched, filesMissing, filesExtra, filesCopied,
                    0, 0, 0, filesCopied, filesCopied, filesProcessed, rsyncExitCode, outcomes, errors,
                    warnings, 0, AbortCause.NONE);
        }

        /**
         * The verdicts that answered a question about a file: this file matched, mismatched or was missing.
         *
         * <p>An extra file in the destination was <b>reported</b>, not compared against anything the job was
         * asked to handle, so it is not part of this figure: a job that found three extras and compared nothing
         * checked nothing. This is a sum of the three verdict figures, so it cannot disagree with them.
         */
        public int filesChecked() {
            return filesMatched + filesMismatched + filesMissing;
        }

        /** How many per-file rows the report holds. */
        public int rowsListed() {
            return outcomes.size();
        }

        /**
         * Whether the per-file list is part of the job. Derived from {@link #rowsOmitted()} rather than stored
         * separately, so a report cannot claim to be whole while naming rows it left out.
         */
        public boolean truncated() {
            return rowsOmitted > 0;
        }

        public boolean wasAborted() {
            return abortCause != AbortCause.NONE;
        }

        public boolean isSuccess() {
            return status == JobStatus.COMPLETED;
        }
    }

    /**
     * Serializes job admission with terminal publication.
     *
     * <p>The controller's compare-and-set decides <i>which</i> finisher wins; it does not make the winner's
     * remaining writes atomic with somebody else's decision to admit a new job. Without this lock, a thread
     * finishing an old job could set the status after a new job had been admitted and set it to RUNNING, and
     * the window would then show a job that had already ended - or show a running job as idle. The lock covers
     * only the state changes: no listener callback and no process operation happens inside it.
     */
    private final Object lifecycleLock = new Object();
    private final AtomicReference<JobStatus> status = new AtomicReference<>(JobStatus.IDLE);
    private final AtomicBoolean jobRunning = new AtomicBoolean(false);
    /**
     * The job running now, or null between jobs. Every loop working on a job holds its own controller rather
     * than reading this, and the engine publishes state only for the controller it still regards as current.
     */
    private final AtomicReference<JobControl> currentControl = new AtomicReference<>();
    private final AtomicLong jobGeneration = new AtomicLong();
    /** Results published by a job the engine had already let go. Counted, never applied. */
    private final AtomicInteger lateResultsDropped = new AtomicInteger();
    /** Cleanup runs once, however many callers ask for it. */
    private final AtomicBoolean closeStarted = new AtomicBoolean(false);
    private volatile boolean closed;
    /** The one collision answer on record: that set, for that job. */
    private volatile CollisionAcceptance collisionAcceptance;
    /** What the last job refused, so an answer can be bound to it and to nothing else. */
    private volatile CollisionAcceptance lastCollisionRefusal;
    /** The CLI convenience: {@code --allow-collision-names} was typed for this run, so it covers this run. */
    private volatile boolean acceptAnyCollisions;
    /** What the last job actually found, so the front end can ask about <i>that</i> set and not a guess. */
    private volatile List<String> lastDetectedCollisions = List.of();

    /**
     * An acceptance and the job it belongs to. The job is named by the two directories, the kind of job, and
     * the files it was asked to handle - so the same relative pair under other roots, or a different selection
     * under these roots, is a different question and gets asked again.
     */
    private record CollisionAcceptance(String jobIdentity, String collisionSignature) {
    }

    /** The identity an acceptance is not tied to any one job - for a caller that accepts by name. */
    private static final String ANY_JOB = "*";
    private final AtomicLong startedAt = new AtomicLong(0L);
    private final AtomicLong lastActivityAt = new AtomicLong(0L);
    private final AtomicReference<String> lastActivity = new AtomicReference<>("");

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sync-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * The job's own thread, one per job rather than one queue for the engine.
     *
     * <p>A single-thread executor looked tidy and was wrong: a job thread stuck inside somebody else's
     * callback blocks every later job as well. The engine had already given up on that thread, published a
     * stopped result for it, released the destination - and still could not start the next job, because the
     * queue was serial. Only one job runs at a time because {@code jobRunning} says so, not because the
     * executor is serial, so an abandoned thread is now simply an abandoned thread: it cannot publish into a
     * later job and it cannot stop one.
     */

    private volatile JobResult lastResult;
    private volatile long jobTimeoutMillis = NO_TIMEOUT;
    private volatile long bandwidthLimitKbps;
    private volatile int resultLimit = MAX_OUTCOMES;

    /**
     * The abort state of one job: made when that job starts, held by every loop working on it, and never reset.
     *
     * <p>The engine used to keep one cancel flag, one deadline and one process reference for itself and clear
     * them when the next job began. Anything still running from the previous job - a reader on a pipe, a
     * hashing thread, a thread that was slow publishing its result - then read a clean slate that belonged to
     * somebody else: it could run on as though no cancel had ever been asked for, or terminate the process of
     * the job that had just started. A controller belongs to exactly one job, so an old worker can only ever
     * see (and only ever stop) the job that was asked about.
     */
    private static final class JobControl {

        private final long generation;
        /** Deadline in {@link System#nanoTime()} terms; 0 when no time limit was set. */
        private final long deadlineNanos;
        private final AtomicReference<ProgressListener> listener;
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicReference<AbortCause> cause = new AtomicReference<>(AbortCause.NONE);
        /**
         * The rsync this job started, and every process it had spawned, snapshotted while the parent was
         * still alive. A local rsync copy is three processes (measured on 3.1.3 and 3.4.0), and killing only
         * the one Java started leaves the other two reading the source after this program has exited.
         */
        private final AtomicReference<Process> process = new AtomicReference<>();
        private final AtomicReference<List<ProcessHandle>> tree = new AtomicReference<>(List.of());
        /** The destination reservation this job holds, or null if it never got one. */
        private final AtomicReference<String> reservation = new AtomicReference<>();
        /** The running job's counters, so a stop forced from outside can still report what was done. */
        private final AtomicReference<ProgressReporter> progress = new AtomicReference<>();
        /** The thread running this job, named for the job, so a stuck one can be identified in a dump. */
        private final AtomicReference<Thread> worker = new AtomicReference<>();
        /**
         * The hash pools and futures this job started. Cancellation has to reach them from wherever it was
         * asked for - a button, a time limit, a closing program - and not only from the loop that happened to
         * be submitting work. Without this, cancelling a job stops the job and leaves a thread reading a
         * 40 GiB file for nobody.
         */
        private final ConcurrentLinkedQueue<ExecutorService> hashPools = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Future<?>> hashFutures = new ConcurrentLinkedQueue<>();

        /** Take ownership of a hash pool so a stop can shut it down. */
        void adopt(ExecutorService pool) {
            hashPools.add(pool);
        }

        /** Take ownership of a queued hash task so a stop can drop or interrupt it. */
        void adopt(Future<?> future) {
            hashFutures.add(future);
        }

        /**
         * Stop the hashing this job started: queued work is dropped, running tasks are interrupted. A local
         * read checks for interruption between buffers, so this ends it; a read that the kernel will not
         * interrupt keeps going, which the caller reports as a warning rather than as a completed stop.
         */
        void stopHashing() {
            for (Future<?> future : hashFutures) {
                future.cancel(true);
            }
            for (ExecutorService pool : hashPools) {
                pool.shutdownNow();
            }
        }

        /** The counters as this job last published them, or none if it never got that far. */
        ProgressReporter.Counters counters() {
            ProgressReporter reporter = progress.get();
            return reporter == null ? ProgressReporter.emptyCounters() : reporter.snapshot();
        }
        /** When termination was first asked for; 0 until it was. */
        private volatile long terminateRequestedAtNanos;
        /** Set by the job thread; read by the watchdog to tell a live transfer from a hung engine. */
        private volatile String phase = "idle";

        JobControl(long generation, long deadlineNanos, ProgressListener listener) {
            this.generation = generation;
            this.deadlineNanos = deadlineNanos;
            this.listener = new AtomicReference<>(listener);
        }

        long generation() {
            return generation;
        }

        ProgressListener listener() {
            return listener.get();
        }

        /** Ask this job to stop, and say who asked. The first reason given is the one recorded. */
        void requestCancel(AbortCause why) {
            cause.compareAndSet(AbortCause.NONE, why);
            cancelRequested.set(true);
        }

        AbortCause cause() {
            return cause.get();
        }

        boolean cancelRequested() {
            return cancelRequested.get();
        }

        /** True when this job is past the time limit set for it. Asking is what records the reason. */
        boolean deadlineExceeded() {
            if (deadlineNanos == 0L || System.nanoTime() - deadlineNanos < 0) {
                return false;
            }
            cause.compareAndSet(AbortCause.NONE, AbortCause.TIMED_OUT);
            return true;
        }

        boolean timedOut() {
            return cause.get() == AbortCause.TIMED_OUT;
        }

        /** True when this job should stop at its next safe point. Never blocks, and never stops anything. */
        boolean abortRequested() {
            return cancelRequested.get() || deadlineExceeded();
        }

        /** Record the process this job started, and stop it at once if the job was already cancelled. */
        void spawn(Process spawned) {
            process.set(spawned);
            tree.set(snapshotTree(spawned));
            if (cancelRequested.get()) {
                terminate();
            }
        }

        Process process() {
            return process.get();
        }

        boolean terminationRequested() {
            return terminateRequestedAtNanos != 0L;
        }

        long millisSinceTerminationRequested() {
            long at = terminateRequestedAtNanos;
            return at == 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at);
        }

        /**
         * Stop this job's process and everything it spawned: TERM first, then KILL for whatever ignores it.
         * Only ever called for this job's own process, which is what keeps an old worker from killing the
         * process of a job that started after it.
         */
        void terminate() {
            if (terminateRequestedAtNanos == 0L) {
                terminateRequestedAtNanos = System.nanoTime();
            }
            // Hashing first: a job cancelled while it was reading the source has no process to kill, and the
            // only thing still doing work is a hash thread.
            stopHashing();
            Process spawned = process.get();
            if (spawned == null) {
                return;
            }
            List<ProcessHandle> snapshot = snapshotTree(spawned);
            tree.set(snapshot);
            signalTree(spawned, snapshot, false);
            if (!awaitTree(spawned, snapshot, TERM_GRACE_SECONDS)) {
                signalTree(spawned, snapshot, true);
                awaitTree(spawned, snapshot, TERM_GRACE_SECONDS);
            }
            // Close the pipes too. A reader blocked on rsync's output only sees end of file once every
            // writer has gone, and a process that left the tree (a double fork, a setsid) is not reachable
            // by the signals above. Closing the stream here is what keeps a stopped job's reader from
            // waiting forever for output that will never come.
            closeQuietly(spawned.getInputStream());
            closeQuietly(spawned.getErrorStream());
        }

        private static void closeQuietly(java.io.Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException | RuntimeException e) {
                // Nothing to do: the reader is being stopped, not served.
            }
        }
    }

    public FileSyncEngine() {
        watchdog.scheduleAtFixedRate(this::watchdogLoop,
                WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------- lifecycle

    public JobStatus status() {
        return status.get();
    }

    /** How many results arrived from a job the engine had already let go. Never zero in a healthy run. */
    public int lateResultsDropped() {
        return lateResultsDropped.get();
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
        final Admission admission = admit(request, progressListener);
        if (!admission.admitted()) {
            // Closed, or already busy. Either way the answer is the same shape from here: nothing happens.
            return false;
        }
        final JobControl control = admission.control();
        try {
            Thread worker = new Thread(() -> {
                JobResult result;
                try {
                    result = runGuarded(request, control, progressListener);
                } catch (Throwable t) {
                    // An Error that escaped the guard (usually an OutOfMemoryError while building its own
                    // message) must still release the engine: a job thread that dies quietly would leave
                    // it reporting RUNNING forever with no way to start another job.
                    result = failure("Job aborted: " + t, List.of(String.valueOf(t)), List.of(), 0);
                }
                finishJob(result, control, progressListener);
            }, "sync-job-" + control.generation());
            worker.setDaemon(true);
            control.worker.set(worker);
            worker.start();
            return true;
        } catch (RuntimeException e) {
            finishJob(failure("Job could not be started: " + e, List.of(), List.of(), 0), control,
                    progressListener);
            throw e;
        }
    }

    /** Synchronous variant, for tests and headless callers. Blocks until the job finishes. */
    public JobResult execute(JobRequest request, ProgressListener listener) {
        listener = withoutListener(listener);
        final Admission admission = admit(request, listener);
        if (admission.closed()) {
            throw new IllegalStateException("Engine is closed");
        }
        if (!admission.admitted()) {
            throw new IllegalStateException("Engine is busy: a job is already running");
        }
        final JobControl control = admission.control();
        return finishJob(runGuarded(request, control, listener), control, listener);
    }

    /** What asking for the engine got: a controller this call installed, or the reason it was refused. */
    private record Admission(JobControl control, boolean closed, boolean busy) {
        boolean admitted() {
            return control != null;
        }
    }

    /**
     * Ask for the engine, and install this job's controller for it, in <b>one</b> critical section. Closing
     * marks itself in the same section (see {@link #closeOnce()}), so exactly one of two things is true: this
     * call got the engine and closing has hold of the controller it installed, or closing was marked first and
     * this call is refused. An earlier version checked {@code closed} outside the lock and rechecked it inside,
     * which let {@code execute()} admit a job moments after {@code close()} had returned with no controller to
     * stop - a copy that ran, and finished, after the program had closed the engine and retired its watchdog.
     *
     * <p>The claim on {@code jobRunning} is taken here and released by {@code finishJob}, inside the same lock.
     * Nothing slow happens in here: no process, no listener, no disk.
     */
    private Admission admit(JobRequest request, ProgressListener listener) {
        synchronized (lifecycleLock) {
            if (closed) {
                return new Admission(null, true, false);
            }
            if (jobRunning.get()) {
                return new Admission(null, false, true);
            }
            jobRunning.set(true);
            return new Admission(beginJob(request, listener), false, false);
        }
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
        JobControl control = currentControl.get();
        if (control == null) {
            return false;
        }
        control.requestCancel(AbortCause.USER_CANCELLED);
        markActivity(control, "cancel requested");
        control.terminate();
        return true;
    }

    @Override
    public void close() {
        closeOnce();
    }

    /**
     * Shut the engine down exactly once, however many callers ask.
     *
     * <p>The CLI closes the engine in a finally block and also has a JVM shutdown hook; the GUI closes it when
     * the window closes. Whoever arrives first does the work and the rest return at once. Two cleanup passes
     * waiting on the same process tree is how a program hangs on exit instead of exiting, so this is bounded:
     * the tree is terminated, the workers are interrupted, and the wait for the job thread is capped. A job
     * thread that ignores all of that is left behind - it cannot publish into a later job, because its
     * controller is no longer the current one.
     */
    public void closeOnce() {
        if (!closeStarted.compareAndSet(false, true)) {
            return;
        }
        final JobControl control;
        synchronized (lifecycleLock) {
            // Marked closed, and the job to stop captured, in the same critical section that admits a job.
            // This is what makes the two sides agree: a job installed before this point is a job closing has
            // hold of and will stop; a job asked for after it is refused. Marking closed outside the lock left
            // a window in which close returned having never seen the controller, and that job then copied.
            closed = true;
            control = currentControl.get();
        }
        if (control != null) {
            control.requestCancel(AbortCause.ENGINE_CLOSED);
            control.terminate();
            // Give the job's own thread a bounded moment to publish what it knows while its process dies...
            Thread worker = control.worker.get();
            if (worker != null && worker != Thread.currentThread()) {
                try {
                    worker.join(1_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // ...and if it could not, finish the job here, before the watchdog is retired. The watchdog is the
            // only other thing that would have done it, so closing used to leave a job that was blocked in a
            // caller's callback reported as running forever, with no result at all - and a command-line run
            // that printed no summary. The delivery gets its own bounded wait, because the callback may block.
            if (currentControl.get() == control) {
                publishTerminalResult(control, CLOSE_TERMINATION_MILLIS, Delivery.ELSEWHERE_BRIEFLY);
            }
        }
        watchdog.shutdownNow();
    }

    /** Admit a job. Called with {@link #lifecycleLock} held, so no finisher can interleave with it. */
    private JobControl beginJob(JobRequest request, ProgressListener listener) {
        long timeout = jobTimeoutMillis;
        JobControl control = new JobControl(jobGeneration.incrementAndGet(),
                timeout > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout) : 0L, listener);
        currentControl.set(control);
        status.set(JobStatus.RUNNING);
        startedAt.set(System.nanoTime());
        markActivity(control, "starting: " + request.jobType());
        return control;
    }

    /** True while {@code control} is still the job the engine is answering for. */
    private boolean isCurrent(JobControl control) {
        return currentControl.get() == control;
    }

    /**
     * Publish a job's result, unless the engine has already let that job go.
     *
     * <p>The engine is marked idle, and the destination reservation released, before the listener is called:
     * a caller whose completion callback blocks must not be able to leave this engine reporting RUNNING. A
     * result from a job that is no longer current is counted and dropped - it does not rewrite the status, the
     * result or the reservation of the job that replaced it.
     */
    /** Where a completion callback may be run, and whether anyone may wait for it. */
    private enum Delivery {
        /** Run it here. Right for the job's own thread, which has nowhere else to be. */
        ON_THIS_THREAD,
        /** Run it elsewhere and do not wait. Right for the watchdog: a listener is entitled to block, and the
         *  watchdog has the next job's deadline to enforce. */
        ELSEWHERE_WITHOUT_WAITING,
        /** Run it elsewhere, waiting only briefly. Right for a program on its way out, which wants the summary
         *  printed but must not be held by a callback that never returns. */
        ELSEWHERE_BRIEFLY
    }

    private JobResult finishJob(JobResult result, JobControl control, ProgressListener listener) {
        return finishJob(result, control, listener, Delivery.ON_THIS_THREAD);
    }

    /**
     * Publish a job's result, unless the engine has already let that job go.
     *
     * <p>The engine is marked idle and the destination reservation released <b>before</b> the listener is
     * called, so a caller whose completion callback blocks cannot leave this engine reporting RUNNING. The
     * completed state is published while admission is closed, and admission reopens last: a new job admitted
     * by another thread cannot have its RUNNING status overwritten by a finisher that won the race a moment
     * earlier. A result from a job that is no longer current is counted and dropped.
     *
     * @param delivery where the completion callback runs. It is never run on the watchdog thread: doing that
     *     used to leave the watchdog inside a caller's blocked callback, so no later job's time limit was ever
     *     enforced - the hung {@code rsync} ran until it finished on its own.
     */
    private JobResult finishJob(JobResult result, JobControl control, ProgressListener listener,
                                Delivery delivery) {
        boolean mine;
        synchronized (lifecycleLock) {
            mine = currentControl.compareAndSet(control, null);
            if (mine) {
                String reserved = control.reservation.getAndSet(null);
                if (reserved != null) {
                    DESTINATIONS_IN_USE.remove(reserved);
                }
                control.phase = "idle";
                lastResult = result;
                status.set(result.status());
                jobRunning.set(false);
            }
        }
        if (!mine) {
            // The result is dropped, but its destination claim is not left behind: the winner may have
            // published the engine idle before this job got as far as claiming, and a claim belonging to no
            // running job would keep that directory shut to every job after it.
            String orphan = control.reservation.getAndSet(null);
            if (orphan != null) {
                DESTINATIONS_IN_USE.remove(orphan);
            }
            lateResultsDropped.incrementAndGet();
            return result;
        }
        switch (delivery) {
            case ON_THIS_THREAD -> listener.onCompletion(result);
            case ELSEWHERE_WITHOUT_WAITING -> deliverOffThread(listener, result, -1L);
            case ELSEWHERE_BRIEFLY -> deliverOffThread(listener, result, CLOSE_DELIVERY_MILLIS);
        }
        return result;
    }

    /**
     * Run a completion callback somewhere other than this thread, waiting no longer than {@code joinMillis}.
     * A listener is somebody else's code, and it is entitled to block; that entitlement cannot be allowed to
     * occupy the thread that has to enforce the next job's time limit.
     */
    private void deliverOffThread(ProgressListener listener, JobResult result, long joinMillis) {
        Thread delivery = new Thread(() -> {
            try {
                listener.onCompletion(result);
            } catch (RuntimeException | Error e) {
                // A callback that throws is the caller's bug; it must not reach the engine's own threads.
                markActivity("completion callback failed: " + e.getClass().getSimpleName());
            }
        }, "sync-completion-delivery");
        delivery.setDaemon(true);
        delivery.start();
        if (joinMillis < 0L) {
            return;      // fire and forget: the caller must not be held by somebody else's callback
        }
        try {
            delivery.join(joinMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private JobResult runGuarded(JobRequest request, JobControl control, ProgressListener listener) {
        try {
            return doExecute(request, control, listener);
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
            if (isCurrent(control)) {
                control.phase = "idle";
            }
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
            // Two spellings of each root matter. A source given as "dest/mirror", where mirror is a link to
            // the data, resolves to a directory that looks nowhere near the destination - but the job still
            // reads through the destination and writes into it. Containment is judged against the directory
            // a path lands on *and* the path as given.
            Path sourceKey = canonical(source);
            Path destKey = canonical(dest);
            Path sourceGiven = source.toAbsolutePath().normalize();
            Path destGiven = dest.toAbsolutePath().normalize();
            if (sourceKey.equals(destKey) && copies) {
                problems.add("Source and destination are the same directory: " + source
                        + (sourceKey.equals(source.toAbsolutePath().normalize()) ? ""
                            : " (this path reaches it through a link, as " + sourceKey + ")")
                        + ". Choose a destination outside the source tree.");
            } else if (copies && destKey.startsWith(sourceKey)) {
                problems.add("Destination is inside the source directory: " + dest + " is inside " + sourceKey
                        + ". The job would write into the tree it is reading - and the destination holds a copy"
                        + " of the source, so the job cannot say which copy is the original. Choose a"
                        + " destination outside the source tree.");
            } else if (copies && sourceKey.startsWith(destKey)) {
                problems.add("Source is inside the destination directory: " + sourceKey + " is inside " + dest
                        + ". Copying would write over the files being read, and a run stopped half way leaves"
                        + " the source half overwritten by its own copy. Choose a destination outside the"
                        + " source tree.");
            } else if (copies && sourceGiven.startsWith(destKey)) {
                problems.add("Source is reached through the destination directory: " + source
                        + " leads to " + sourceKey + " by way of " + dest + ". The job reads its own"
                        + " destination, so copying writes over the files it is reading - through a link,"
                        + " which is why it looks like two directories. Choose a source that is not reached"
                        + " through the destination.");
            } else if (copies && destGiven.startsWith(sourceKey)) {
                problems.add("Destination is reached through the source directory: " + dest + " leads to "
                        + destKey + " by way of " + source + ". The job would write into the tree it is"
                        + " reading. Choose a destination that is not reached through the source.");
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
    /**
     * Accept the name-collision risk for this engine's copies (&#167;2.11). Without it, a job that finds
     * colliding names refuses before writing anything. Verify Only is unaffected either way: it reports the
     * same names and writes nothing.
     *
     * <p>Accepting is not a guarantee of safety. It says the caller knows two of these names can be one
     * file on the destination volume and accepts that one overwrites the other.
     */
    public void acknowledgeCollisions(List<String> collisions) {
        collisionAcceptance = new CollisionAcceptance(ANY_JOB, collisionSignature(collisions));
    }

    /**
     * Accept exactly what the last job refused: that collision set, for that job.
     *
     * <p>This is what the window does when somebody answers "Copy them anyway?". Nothing is remembered about
     * the caller being agreeable in general. The acceptance names the directories, the kind of job and the
     * files it was about, and it stops meaning anything as soon as any of those change - which is why the
     * window re-runs the request it had already captured rather than a fresh one.
     */
    public void acceptDetectedCollisions() {
        collisionAcceptance = lastCollisionRefusal;
    }

    /** Accept whatever the job turns out to find. Only the CLI uses this; the window names the set. */
    public void acceptAnyCollisions() {
        acceptAnyCollisions = true;
    }

    public boolean collisionsAcknowledged() {
        return acceptAnyCollisions || collisionAcceptance != null;
    }

    /**
     * The accepted answer, matched against the set this job found. Two names in two different directories,
     * and the same two names in a different pair, are different questions: an acceptance granted for one
     * cannot answer the other.
     */
    private boolean collisionsAcceptedFor(String jobIdentity, List<String> collisions) {
        if (acceptAnyCollisions) {
            return true;
        }
        CollisionAcceptance accepted = collisionAcceptance;
        if (accepted == null || !accepted.collisionSignature().equals(collisionSignature(collisions))) {
            return false;
        }
        return ANY_JOB.equals(accepted.jobIdentity()) || accepted.jobIdentity().equals(jobIdentity);
    }

    /**
     * Which job this is, for the purpose of an acceptance: the two directories, the kind of job, and the
     * files it was asked to handle. Anything that changes what would be written changes the identity, so an
     * acceptance cannot be carried from one job to the next.
     */
    private static String jobIdentity(JobRequest request, Collection<String> workSetNames) {
        List<String> names = new ArrayList<>(workSetNames);
        Collections.sort(names);
        return request.sourceDir() + "\u0001" + request.destDir() + "\u0001" + request.jobType()
                + "\u0001" + String.join("\u0002", names);
    }

    private static String collisionSignature(List<String> collisions) {
        return String.join("\u0000", collisions);
    }

    /** The colliding names the last job found. The window asks about these, and accepts these. */
    public List<String> collisionsDetectedByLastJob() {
        return List.copyOf(lastDetectedCollisions);
    }

    /** Test window into the rule: does the acceptance on record cover this set for this job? */
    boolean collisionAcceptanceCovers(String jobIdentity, List<String> collisions) {
        return collisionsAcceptedFor(jobIdentity, collisions);
    }

    /** The identity an acceptance would have to match to cover a job like this one. */
    static String jobIdentityFor(JobRequest request, Collection<String> workSetNames) {
        return jobIdentity(request, workSetNames);
    }

    public String rsyncBinaryInUse() {
        String override = rsyncOverride;
        return override != null ? override : rsyncBinary();
    }

    /**
     * The directory or file this path actually lands on: links resolved, {@code ..} collapsed.
     *
     * <p>Containment has to be judged against this. Two paths that name different directories on paper but
     * reach the same one - a destination that is a symbolic link to the source, a {@code ..} that walks out
     * of the destination through a linked directory - are the same directory as far as the filesystem is
     * concerned, and a copy between them overwrites the files it is reading.
     */
    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            // Not on disk yet: the best available answer, and a job that has to create it will say so.
            return path.toAbsolutePath().normalize();
        }
    }

    /**
     * Names whose path leaves the tree it is supposed to stay inside.
     *
     * <p>The directories above the final component are resolved, whatever the symlink policy: a linked
     * directory is the directory a write or a read through it would land in, and that is the escape the audit
     * found - including the case where the destination's own ancestry contains the link, and the case where
     * the file at the end has not been created yet and so has nothing to resolve.
     *
     * <p>The final component is <b>not</b> resolved, and that is a measured decision, not a shortcut. A
     * symbolic link sitting at the end of a selected path is an object, not a route: rsync replaces a
     * destination link with the file it is copying (measured - the link's target was left untouched), and this
     * program reports a link it finds at the destination instead of reading it. Resolving the leaf would
     * refuse a legal job: it would reject a source link copied as a link because its target lives elsewhere,
     * and it would reject a destination link that a previous copy of the same link had created.
     */
    private static List<String> escapingPaths(Path root, Collection<String> relPaths) {
        Path inside;
        try {
            inside = root.toRealPath();
        } catch (IOException e) {
            return List.of();
        }
        List<String> escaping = new ArrayList<>();
        for (String rel : relPaths) {
            Path full = root.resolve(rel);
            Path parent = full.getParent();
            if (parent == null || !resolveAsFarAsPossible(parent).startsWith(inside)) {
                escaping.add(rel);
            }
        }
        return escaping;
    }

    /**
     * Resolve the part of a path that exists and re-attach the rest, so a name whose destination has not
     * been created yet is still judged against the resolved tree. A linked directory on the way is followed,
     * because that is the directory its write would land in.
     */
    static Path resolveAsFarAsPossible(Path path) {
        List<String> missing = new ArrayList<>();
        Path cursor = path;
        while (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = cursor.getParent();
            Path name = cursor.getFileName();
            if (parent == null || name == null) {
                // A root has no parent and no file name: nothing further to resolve, so judge the path as
                // spelled rather than guess at a partial one.
                return path.toAbsolutePath().normalize();
            }
            missing.add(name.toString());
            cursor = parent;
        }
        try {
            cursor = cursor.toRealPath();
        } catch (IOException e) {
            cursor = cursor.toAbsolutePath().normalize();
        }
        for (int i = missing.size() - 1; i >= 0; i--) {
            cursor = cursor.resolve(missing.get(i));
        }
        return cursor;
    }

    // ---------------------------------------------------------------- job body

    private JobResult doExecute(JobRequest request, JobControl control, ProgressListener listener)
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
        if (control.abortRequested()) {
            // Asked to stop while it was still being admitted - a close that captured this controller, most
            // likely. It must not touch the destination, and it must not claim a reservation that the terminal
            // result already published past: that claim would outlive the job that made it.
            return abortedBeforeTransfer("Job " + abortWords(control.cause())
                    + " before rsync started: no file was transferred.",
                    List.of(), List.of(), List.of(), request.jobType().verifies(), control.cause());
        }
        if (!DESTINATIONS_IN_USE.add(destinationKey)) {
            return failure("Another job in this program is already using the destination " + destDir
                    + ". Wait for it to finish, or point this job at a different directory: two jobs writing"
                    + " one directory produce numbers neither of them can vouch for.",
                    List.of("destination busy: " + destinationKey), List.of(), 0);
        }
        control.reservation.set(destinationKey);

        Selection src = normalize(request.sourceSelection());
        Selection dst = normalize(request.destinationSelection());
        ScanOptions options = request.scanOptions();
        boolean copies = request.jobType().copies();
        boolean verifies = request.jobType().verifies();

        if (src.selected().isEmpty() && dst.selected().isEmpty()) {
            return failure("No files were selected: an empty selection means no files, not all files.",
                    List.of(), List.of(), 0);
        }

        markActivity(control, "scanning " + sourceDir);
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
                ? scanDestinationSides(control, destDir, options, sourceRels, dst)
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

        // §2.2: containment in both trees, before anything is written or read. A name carrying ".." escapes
        // on its own; a name that looks contained escapes through a linked directory on the way. Both are
        // judged against the resolved tree, and a refusal here has changed nothing on disk.
        //
        // Verify Only is held to the destination rule as well: comparing a file reached through a linked
        // destination directory reports some other directory's file as a verified backup copy, which is the
        // audit's original A04 finding and the more dangerous of the two, because it looks like a pass.
        //
        // What this is not: a lock. It is checked before the job acts, not atomically at each write, so a
        // directory re-pointed by another process in between is a race this program does not close - by
        // design, since it takes no cross-process lock.
        // Every name the job was asked to handle, not only the ones the source happened to have: a name that
        // climbs out is refused for the reason that matters, not for the incidental one.
        Set<String> askedFor = new java.util.LinkedHashSet<>();
        for (FileEntry entry : workSet) {
            askedFor.add(entry.relPath());
        }
        askedFor.addAll(request.sourceSelection().selected());
        askedFor.addAll(request.destinationSelection().selected());
        List<String> escaping = new ArrayList<>(escapingPaths(sourceDir, askedFor));
        escaping.addAll(escapingPaths(destDir, askedFor));
        if (!escaping.isEmpty()) {
            String problem = "Refused: " + escaping.size() + " selected path(s) point outside the directory"
                    + " they belong to: " + describeNames(escaping) + ". A path that climbs out with"
                    + " \"..\","
                    + " or that reaches outside through a linked directory, names a file outside the tree this"
                    + " job is allowed to read or write - and a comparison of it would be reported as a"
                    + " verdict about the destination. Nothing was copied and nothing was compared.";
            return failure(problem, List.of(problem), List.of(), 0);
        }

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
        lastDetectedCollisions = List.copyOf(collisions);
        String identity = jobIdentity(request, namesOf(workSet));
        lastCollisionRefusal = new CollisionAcceptance(identity, collisionSignature(collisions));
        if (!collisions.isEmpty()) {
            // §2.11. Two of these names are one file on a case-insensitive or normalising destination, and
            // the later transfer overwrites the earlier one: a copy is a write, so it needs the caller to
            // have accepted that. Verification changes nothing, so it keeps warning without refusing.
            if (request.jobType().copies() && !request.dryRun()
                    && !collisionsAcceptedFor(identity, collisions)) {
                String problem = "Refused: " + collisions.size() + " colliding name(s) in the work set: "
                        + describeNames(collisions) + ". On a case-insensitive or normalising destination"
                        + " these are the same file, and copying one over the other is what the job would do."
                        + " Nothing was copied. Acknowledge it with --allow-collision-names, or confirm in"
                        + " the window; Verify Only reports the same names without writing anything.";
                return failure(problem, List.of(problem), List.of(), 0);
            }
            warnings.add(collisions.size() + " name collision(s) in the work set: "
                    + describeNames(collisions) + ". These names differ only by case or by how an accent is"
                    + " composed. On a case-insensitive or normalising destination they are the same file,"
                    + " and the later transfer overwrites the earlier one.");
        }

        int rsyncExitCode = 0;
        ProgressReporter progress = new ProgressReporter(workTotal, listener);
        control.progress.set(progress);
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
        TransferTally tally = TransferTally.NONE;

        if (copies && verifies) {
            // Its own phase, because it is its own wait: hashing reads the whole source before rsync has
            // written a single byte, and calling that "copying" is exactly how a job looks stalled on the
            // first file for an hour with an empty destination folder.
            listener.onPhase("hashing source");
            control.phase = "hash";
            progress.update(0, "Hashing " + workSet.size()
                    + " source file(s) - nothing is written to the destination during this part...");
            // Hashed once, before the copy: these same digests are reused for the comparison below.
            sourceHashes = hashFiles(control, workSet, "source", warnings);
        }

        if (copies) {
            listener.onPhase(dryRun ? "dry run" : "copying");
            control.phase = "copy";
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
                        () -> control.abortRequested());
            }

            Path filesFrom = writeFilesFrom(workSet, warnings);
            if (filesFrom == null) {
                return failure("No selected file name could be handed to rsync (each contains a newline, "
                        + "carriage return or NUL byte); deselect them and retry.", errors, warnings, 0);
            }
            try {
                // Asked to stop, and rsync has not started yet: starting it now would put a process on the
                // source disk that nothing in this program is watching any more.
                if (control.cancelRequested()) {
                    return abortedBeforeTransfer("Job " + abortWords(control.cause())
                            + " before rsync started: no file was transferred.",
                            outcomes, errors, warnings, verifies, control.cause());
                }
                if (control.timedOut()) {
                    return abortedBeforeTransfer("Job stopped on timeout: it passed the "
                            + humanDuration(jobTimeoutMillis) + " limit before rsync had started.",
                            outcomes, errors, warnings, verifies, AbortCause.TIMED_OUT);
                }

                Process process = new ProcessBuilder(
                        buildRsyncCommand(sourceDir, destDir, filesFrom, options, dryRun))
                        .redirectErrorStream(true)
                        .start();
                control.spawn(process);
                // A cancellation that landed between the check above and the spawn still has to reach this
                // process, because the person who pressed Cancel has already been told the job stopped.
                if (control.abortRequested()) {
                    control.terminate();
                }

                int copied = pumpRsyncOutput(control, process, sourceDir, progress, errors, warnings, listener,
                        dryRun,
                        announcedNames);
                rsyncExitCode = process.waitFor();
                sweepRsyncTree(control, process);
                markActivity(control, "rsync exit " + rsyncExitCode);

                // What happened to each announced file, decided before any early return below, because a
                // job that is about to be reported as cancelled or failed still owes the user a list of
                // the files it did and did not finish.
                boolean cleanCopy = !control.timedOut() && !control.cancelRequested()
                        && isSuccessfulExit(rsyncExitCode);
                transfers = transferFacts(ledger, announcedNames, cleanCopy, dryRun);
                tally = TransferTally.of(transfers);
                if (!cleanCopy) {
                    // A job that is about to be reported as cancelled, timed out or failed still owes the
                    // user a per-file answer. Without it the report is a count with no names, and a count
                    // of "1 copied" over two announced files says nothing about which one.
                    if (verifies) {
                        recordPendingTransfers(progress, outcomes, listener, transfers);
                    } else {
                        recordTransferRows(progress, outcomes, listener, transfers, warnings);
                    }
                }

                if (control.timedOut()) {
                    return timeoutResult(control, progress, tally, outcomes, errors, warnings, destinationTargets, dryRun,
                            verifies);
                }
                if (control.cancelRequested()) {
                    return cancelledDuringCopy(control, progress, tally, outcomes, rsyncExitCode, errors, warnings,
                            destinationTargets, dryRun, verifies);
                }
                if (!isSuccessfulExit(rsyncExitCode)) {
                    errors.add("rsync exited with code " + rsyncExitCode);
                    return rsyncFailedDuringCopy(progress, tally, outcomes, rsyncExitCode, errors, warnings,
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
            recordTransferRows(progress, outcomes, listener, transfers, warnings);
            if (progress.copied() > announcedNames.size()) {
                warnings.add("Only the first " + announcedNames.size() + " copied file(s) are listed; the copy"
                        + " reported " + progress.copied() + ". Raise the results limit (--max-results) if"
                        + " the export must name every file.");
            }
            if (control.timedOut()) {
                return timeoutResult(control, progress, tally, outcomes, errors, warnings, destinationTargets, dryRun, verifies);
            }
            if (control.cancelRequested()) {
                return cancelledDuringCopy(control, progress, tally, outcomes, rsyncExitCode, errors, warnings,
                        destinationTargets, dryRun, verifies);
            }
            progress.finish(dryRun ? "Dry run complete" : "Copy complete");
            listener.onPhase("done");
            return completedResult(progress, tally, outcomes, errors, warnings, rsyncExitCode, dryRun,
                    request.jobType());
        }

        listener.onPhase("verifying");
        control.phase = "verify";
        progress.verifyStart(copies);

        if (!copies) {
            sourceHashes = Map.of();
        }
        if (ledger != null && !dryRun) {
            // Told before anything moves, because a copy erases the evidence: after replacing a link with a
            // file the destination shows a file, and a verdict taken afterwards cannot say what it replaced.
            warnings.addAll(ledger.kindChanges());
        }
        verifyEntries(control, workSet, extras, destDir, sourceHashes, progress, outcomes, listener,
                warnings, !copies, transfers, options.symlinkPolicy());
        if (control.timedOut()) {
            return timeoutResult(control, progress, tally, outcomes, errors, warnings, destinationTargets, dryRun, verifies);
        }
        if (control.cancelRequested()) {
            return cancelledDuringVerify(control, progress, tally, outcomes, errors, warnings);
        }

        if (outcomes.size() >= resultLimit) {
            warnings.add("Results list truncated at " + resultLimit + " entries; counters are still exact. "
                    + (dryRun ? "" : "Raise it (CLI: --max-results N) if the export must list every file."));
        }

        if (control.timedOut()) {
            return timeoutResult(control, progress, tally, outcomes, errors, warnings, destinationTargets, dryRun, verifies);
        }
        if (control.cancelRequested()) {
            return cancelledDuringVerify(control, progress, tally, outcomes, errors, warnings);
        }

        progress.finish("Verification complete");
        listener.onPhase("done");
        return completedResult(progress, tally, outcomes, errors, warnings, rsyncExitCode, dryRun, request.jobType());
    }

    /**
     * rsync's own count of transfers it says it finished, from the {@code (xfr#N)} field on its progress
     * output. Kept as a figure of its own because it is an observation about what rsync said, not a claim this
     * program can support: where the two disagree the destination decides, and both numbers stay on the page.
     */
    private static int claimedByRsync(ProgressReporter progress) {
        return Math.max(0, progress.rsyncTransfers());
    }

    /**
     * The transfer figures, tallied from the per-file dispositions. They are taken from the disposition map
     * rather than from the reported rows, because the reported list can be capped by the result limit while
     * these figures must not be.
     */
    private record TransferTally(int copied, int wouldCopy, int uncertain, int notTransferred) {
        static final TransferTally NONE = new TransferTally(0, 0, 0, 0);

        static TransferTally of(Map<String, TransferDisposition> transfers) {
            int copied = 0;
            int would = 0;
            int unresolved = 0;
            int notMoved = 0;
            for (TransferDisposition disposition : transfers.values()) {
                switch (disposition) {
                    case COPIED -> copied++;
                    case WOULD_COPY -> would++;
                    case UNCERTAIN -> unresolved++;
                    case NOT_COPIED -> notMoved++;
                }
            }
            return new TransferTally(copied, would, unresolved, notMoved);
        }
    }

    /**
     * How many per-file rows this job produced that the report does not hold. Reported as a number rather than
     * as "the list may be short": 9 rows missing and 900 rows missing need different responses, and a reader
     * who counts the rows has to be able to tell that what they are looking at is part of the job.
     */
    private static int rowsOmitted(ProgressReporter progress, List<FileOutcome> outcomes) {
        return Math.max(0, progress.rowCount() - outcomes.size());
    }

    /**
     * The COMPLETED result, built the same way whether it came out of a copy, a comparison, or both.
     * A job whose verification was switched off says so here as well as in the warnings: a copy that was
     * never compared has to be readable as that, and not mistaken for a copy that passed.
     */
    private JobResult completedResult(ProgressReporter progress, TransferTally tally,
                                      List<FileOutcome> outcomes, List<String> errors,
                                      List<String> warnings, int rsyncExitCode, boolean dryRun, JobType jobType) {
        if (dryRun && jobType.copies()) {
            warnings.add(0, "Dry run: rsync ran with --dry-run, so no file was written or changed.");
        }
        if (!jobType.verifies()) {
            warnings.add("Verification was turned off: no file was hashed or compared. This job reports what"
                    + " was copied, not whether the copies are correct.");
        }
        return new JobResult(JobStatus.COMPLETED, completionMessage(progress, tally, dryRun, jobType),
                progress.matched(), progress.mismatched(), progress.missing(), progress.extra(),
                tally.copied(), tally.wouldCopy(), tally.uncertain(), tally.notTransferred(),
                progress.copied(), claimedByRsync(progress), progress.processed(), rsyncExitCode, outcomes,
                errors, warnings, rowsOmitted(progress, outcomes), AbortCause.NONE);
    }

    private static String completionMessage(ProgressReporter progress, TransferTally tally, boolean dryRun,
                                            JobType jobType) {
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
    private void recordOutcome(ProgressReporter progress, List<FileOutcome> outcomes,
                               ProgressListener listener, String rel,
                               String kind, String sourceHash, String destHash,
                               TransferDisposition transfer) {
        progress.countRow();
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
    private JobResult rsyncFailedDuringCopy(ProgressReporter progress, TransferTally tally,
                                            List<FileOutcome> outcomes,
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
        return new JobResult(JobStatus.FAILED, message, progress.matched(), progress.mismatched(),
                progress.missing(), progress.extra(), tally.copied(), tally.wouldCopy(), tally.uncertain(),
                tally.notTransferred(), announced, claimedByRsync(progress), progress.processed(), exitCode,
                outcomes, errors, extended, rowsOmitted(progress, outcomes), AbortCause.NONE);
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
    private void recordTransferRows(ProgressReporter progress, List<FileOutcome> outcomes,
                                    ProgressListener listener,
                                    Map<String, TransferDisposition> transfers, List<String> warnings) {
        List<String> unresolved = new ArrayList<>();
        for (Map.Entry<String, TransferDisposition> fact : transfers.entrySet()) {
            if (fact.getValue() == TransferDisposition.UNCERTAIN) {
                unresolved.add(fact.getKey());
            }
            recordOutcome(progress, outcomes, listener, fact.getKey(), fact.getValue().name(), null, null,
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
    private void recordPendingTransfers(ProgressReporter progress, List<FileOutcome> outcomes,
                                        ProgressListener listener,
                                        Map<String, TransferDisposition> transfers) {
        for (Map.Entry<String, TransferDisposition> fact : transfers.entrySet()) {
            recordOutcome(progress, outcomes, listener, fact.getKey(), "PENDING", null, null, fact.getValue());
        }
    }

    private JobResult cancelledDuringCopy(JobControl control, ProgressReporter progress, TransferTally tally,
                                          List<FileOutcome> outcomes, int exitCode, List<String> errors,
                                          List<String> warnings,
                                          List<Path> destinationTargets, boolean dryRun, boolean verifies) {
        int announced = progress.copied();
        int completed = completedTransfers(progress, announced, dryRun);
        String checked = verifies
                ? progress.processed() + " file(s) were verified (matched: " + progress.matched()
                    + ", mismatched: " + progress.mismatched() + ", missing: " + progress.missing() + ")"
                : "verification was off, so nothing was compared";
        String message = "Job " + abortWords(control.cause()) + " while copying: "
                + transfersPhrase(progress, announced, completed, dryRun)
                + ", and " + checked + ". rsync exit code: " + exitCode + ".";
        List<String> leftovers = interruptedTempFiles(destinationTargets);
        List<String> extended = new ArrayList<>(warnings);
        extended.add(leftoverWarning(leftovers));
        return new JobResult(JobStatus.FAILED, message, progress.matched(), progress.mismatched(),
                progress.missing(), progress.extra(), tally.copied(), tally.wouldCopy(), tally.uncertain(),
                tally.notTransferred(), announced, claimedByRsync(progress), progress.processed(), exitCode,
                outcomes, errors, extended, rowsOmitted(progress, outcomes), control.cause());
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

    private JobResult cancelledDuringVerify(JobControl control, ProgressReporter progress, TransferTally tally,
                                            List<FileOutcome> outcomes, List<String> errors,
                                            List<String> warnings) {
        String message = "Job " + abortWords(control.cause()) + " during verification after "
                + progress.processed() + " of " + progress.workTotal() + " file(s) were checked.";
        return new JobResult(JobStatus.FAILED, message, progress.matched(), progress.mismatched(),
                progress.missing(), progress.extra(), tally.copied(), tally.wouldCopy(), tally.uncertain(),
                tally.notTransferred(), progress.copied(), claimedByRsync(progress), progress.processed(), 0,
                outcomes, errors, warnings, rowsOmitted(progress, outcomes), control.cause());
    }

    private JobResult timeoutResult(JobControl control, ProgressReporter progress, TransferTally tally,
                                    List<FileOutcome> outcomes,
                                    List<String> errors, List<String> warnings, List<Path> destinationTargets,
                                    boolean dryRun, boolean verifies) {
        int announced = progress.copied();
        int completed = completedTransfers(progress, announced, dryRun);
        // A timeout is a setting that was too short, not a fault in anybody's files, and the message says so:
        // it is the difference between reading "the copies are wrong" and "raise the limit".
        String message = "Job stopped on timeout: it passed the " + humanDuration(jobTimeoutMillis)
                + " limit this job was given, while " + control.phase + ". "
                + transfersPhrase(progress, announced, completed, dryRun) + ", and "
                + (verifies ? progress.processed() + " file(s) were verified."
                    : "verification was off, so nothing was compared.")
                + " The files themselves were not reported as different by this stop.";
        List<String> extended = new ArrayList<>(warnings);
        extended.add("Increase or clear the job timeout if this job is expected to take longer.");
        if (!destinationTargets.isEmpty()) {
            extended.add(leftoverWarning(interruptedTempFiles(destinationTargets)));
        }
        return new JobResult(JobStatus.FAILED, message, progress.matched(), progress.mismatched(),
                progress.missing(), progress.extra(), tally.copied(), tally.wouldCopy(), tally.uncertain(),
                tally.notTransferred(), announced, claimedByRsync(progress), progress.processed(), 0,
                outcomes, errors, extended, rowsOmitted(progress, outcomes), AbortCause.TIMED_OUT);
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

    private DestinationSides scanDestinationSides(JobControl control, Path destDir, ScanOptions options,
                                                 Set<String> sourceRels, Selection dst) {
        markActivity(control, "scanning " + destDir + " for destination-only files");
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
    private void verifyEntries(JobControl control, List<FileEntry> workSet, List<FileEntry> extras, Path destDir,
                               Map<String, String> knownSourceHashes, ProgressReporter progress,
                               List<FileOutcome> outcomes, ProgressListener listener,
                               List<String> warnings, boolean verifyOnly,
                               Map<String, TransferDisposition> transfers,
                               ScanOptions.SymlinkPolicy linkPolicy)
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
        control.adopt(pool);
        List<Future<Verdict>> futures = new ArrayList<>(Math.min(total, 1024));
        Set<String> readProblems = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Set<String> kindChanges = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Set<String> comparedByBytes = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Set<String> sourceMoved = java.util.concurrent.ConcurrentHashMap.newKeySet();
        try {
            for (int index = 0; index < total; index++) {
                if (control.abortRequested()) {
                    break;
                }
                final int i = index;
                Future<Verdict> task = pool.submit(() -> verdict(control, i, workSet, extras, destDir,
                        knownSourceHashes, readProblems, kindChanges, comparedByBytes, sourceMoved,
                        linkPolicy));
                control.adopt(task);
                futures.add(task);
            }
        } finally {
            pool.shutdown();
            if (control.abortRequested()) {
                // Queuing hashes for a job that is already stopping wastes the read of every remaining
                // file; the tasks that had not started yet are dropped, and the ones that had are interrupted.
                control.stopHashing();
            }
        }

        for (Future<Verdict> future : futures) {
            if (control.abortRequested()) {
                break;
            }
            Verdict verdict;
            try {
                verdict = future.get();
            } catch (CancellationException stopped) {
                // The job stopped this comparison; the file is simply not among the files that were checked.
                continue;
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
            markActivity(control, "verified " + verdict.relPath());
            switch (verdict.kind()) {
                case "OK" -> progress.countMatched();
                case "MISSING" -> progress.countMissing();
                case "MISMATCH" -> progress.countMismatched();
                default -> progress.countExtra();
            }
            recordOutcome(progress, outcomes, listener, verdict.relPath(), verdict.kind(),
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
        for (String change : kindChanges) {
            warnings.add("Object kind differs: " + change);
        }
        for (String rel : comparedByBytes) {
            warnings.add("Compared by bytes reached through a symbolic link (FOLLOW): " + rel
                    + ". Under FOLLOW the verdict is about the contents at the end of the link, not about the"
                    + " link itself; a link pointing outside the two directories takes that read outside them.");
        }
    }

    private static String quoted(String value) {
        return value == null ? "(unreadable)" : "\"" + RsyncNameCodec.forDisplay(value) + "\"";
    }

    /** A file's verdict. {@code kind} is OK, MISMATCH, MISSING or EXTRA; hashes are null where absent. */
    private record Verdict(String kind, String relPath, String sourceHash, String destHash) {
    }

    private Verdict verdict(JobControl control, int index, List<FileEntry> workSet, List<FileEntry> extras,
                            Path destDir,
                            Map<String, String> knownSourceHashes, Set<String> readProblems,
                            Set<String> kindChanges, Set<String> comparedByBytes, Set<String> sourceMoved,
                            ScanOptions.SymlinkPolicy linkPolicy)
            throws NoSuchAlgorithmException {
        if (control.abortRequested()) {
            return null;
        }
        boolean destinationOnly = index >= workSet.size();
        FileEntry sourceEntry = destinationOnly ? null : workSet.get(index);
        String rel = destinationOnly ? extras.get(index - workSet.size()).relPath() : sourceEntry.relPath();

        // The object is judged before any content is. A link's own properties are its kind and its text: under
        // LINK that is the whole comparison, and nothing is read through a link, because its target may sit
        // outside the two directories this job was given. Under FOLLOW the verdict is about the bytes at the end
        // of a source link, and the report says so. A link standing in the destination is never followed under
        // any policy: a copy replaces it (measured), and reading through it would compare an object that belongs
        // to neither directory.
        Path destPath = destDir.resolve(rel);
        Path sourcePath = destinationOnly ? null : sourceEntry.absolutePath();
        boolean destLink = Files.isSymbolicLink(destPath);
        boolean sourceLink = sourcePath != null && Files.isSymbolicLink(sourcePath);

        if (destLink) {
            if (destinationOnly) {
                kindChanges.add(rel + ": the destination holds a symbolic link that the source does not have."
                        + " It is reported as an extra object, and nothing was read through it.");
                return new Verdict("EXTRA", rel, null, null);
            }
            if (sourceLink) {
                String sourceText = readLink(sourcePath);
                String destText = readLink(destPath);
                if (sourceText != null && sourceText.equals(destText)) {
                    return new Verdict("OK", rel, null, null);
                }
                kindChanges.add(rel + ": both sides are symbolic links but they carry different link text - the"
                        + " source says " + quoted(sourceText) + ", the destination says " + quoted(destText)
                        + ". Under LINK a link is judged by its text, and nothing was read through either one.");
                return new Verdict("MISMATCH", rel, null, null);
            }
            kindChanges.add(rel + ": the source has a regular file and the destination holds a symbolic link."
                    + " These are different objects, so this is a mismatch in kind rather than in content, and"
                    + " nothing was read through the link.");
            return new Verdict("MISMATCH", rel, null, null);
        }

        if (sourceLink && linkPolicy == ScanOptions.SymlinkPolicy.LINK) {
            // A link in the source, and something that is not a link (or nothing) where it should be.
            if (!Files.exists(destPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return new Verdict("MISSING", rel, null, null);
            }
            kindChanges.add(rel + ": the source has a symbolic link and the destination holds a regular file."
                    + " These are different objects, so this is a mismatch in kind rather than in content.");
            return new Verdict("MISMATCH", rel, null, null);
        }
        if (sourceLink) {
            comparedByBytes.add(rel);
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
        if (control.abortRequested()) {
            // This comparison was interrupted, not decided. A file whose digest was never taken has no
            // verdict, and answering MISSING or MISMATCH for it would invent a finding out of a cancellation.
            return null;
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
        } catch (FileHasher.HashAborted stopped) {
            // The job let go of this hash. Reporting it as a read problem would blame a file that was never
            // the problem.
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
    private Map<String, String> hashFiles(JobControl control, List<FileEntry> entries, String side,
                                         List<String> warnings)
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
        control.adopt(pool);
        Map<String, Future<String>> futures = new LinkedHashMap<>();
        try {
            for (FileEntry entry : entries) {
                if (control.abortRequested()) {
                    break;
                }
                Path path = entry.absolutePath();
                Future<String> task = pool.submit(() -> {
                    if (control.abortRequested()) {
                        return null;
                    }
                    return FileHasher.computeHash(path);
                });
                control.adopt(task);
                futures.put(entry.relPath(), task);
            }
        } finally {
            pool.shutdown();
        }

        Map<String, String> hashes = new LinkedHashMap<>();
        NoSuchAlgorithmException pending = null;
        boolean stoppedHashing = false;
        for (Map.Entry<String, Future<String>> future : futures.entrySet()) {
            if (control.abortRequested()) {
                // A stopping job does not wait for digests it no longer needs. The pool has been told to stop
                // as well, so the tasks that had not started are dropped rather than tying up this thread.
                stoppedHashing = true;
                break;
            }
            try {
                String hash = future.getValue().get();
                if (hash != null) {
                    hashes.put(future.getKey(), hash);
                }
            } catch (CancellationException stopped) {
                stoppedHashing = true;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof FileHasher.HashAborted) {
                    // Not a read problem: this is the job letting go of a hash it no longer wants.
                    stoppedHashing = true;
                } else if (e.getCause() instanceof NoSuchAlgorithmException algorithmException) {
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
        if (stoppedHashing) {
            warnIfAReadRefusesToStop(control, pool, side, warnings);
            warnings.add("Hashing of the " + side + " was stopped on request, so the figures cover only the "
                    + hashes.size() + " file(s) that had been read by then.");
        }
        if (pending != null) {
            throw pending;
        }
        return hashes;
    }

    /**
     * Interrupting a hash stops a normal local read, because the digest loop checks between buffers. A read
     * the kernel will not interrupt - a wedged mount, an unreadable device - is a different animal, and the
     * honest thing is to say that a read may still be going rather than to claim the stop was total.
     */
    private void warnIfAReadRefusesToStop(JobControl control, ExecutorService pool, String side,
                                         List<String> warnings) {
        try {
            if (!pool.awaitTermination(HASH_DRAIN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                warnings.add("A read of a " + side + " file is still running after being asked to stop, and "
                        + "cannot be interrupted (a stalled mount or an unreadable device). It writes nothing: "
                        + "hashing only reads; but it may still be holding the disk busy.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
    private int pumpRsyncOutput(JobControl control, Process process, Path sourceDir, ProgressReporter progress,
                                List<String> errors,
                                List<String> warnings, ProgressListener listener, boolean dryRun,
                                List<String> copiedNames)
            throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (control.abortRequested()) {
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
                    markActivity(control, line.trim());
                    continue;
                }
                int percent = rsyncTransferPercent(line);
                if (percent >= 0) {
                    // A stats line this rsync variant formats differently: the percentage is still usable,
                    // the bytes and rate are not, so the status line says less instead of guessing.
                    if (!dryRun) {
                        progress.transferPercentOnly(percent);
                    }
                    markActivity(control, line.trim());
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
                            if (copiedNames != null) {
                                // Every announced name is kept, whatever the results cap is: this list is what
                                // the per-file transfer dispositions are decided from, and a figure tallied from
                                // a capped list would be a capped figure. The row list is capped elsewhere.
                                copiedNames.add(rel);
                            }
                        }
                        markActivity(control, line.trim());
                    }
                    case DIRECTORY -> {
                        // Directory creation is implied by --files-from; nothing to count.
                    }
                    case ERROR -> {
                        String message = line.trim();
                        errors.add(message);
                        markActivity(control, message);
                    }
                    case WARNING -> {
                        warnings.add(line.trim());
                        markActivity(control, line.trim());
                    }
                    case IGNORED -> {
                        // no action
                    }
                }
            }
        } catch (IOException e) {
            if (!control.abortRequested()) {
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
    /** The relative paths of a work set, in the order the identity wants them. */
    private static Collection<String> namesOf(Collection<FileEntry> entries) {
        List<String> names = new ArrayList<>();
        for (FileEntry entry : entries) {
            names.add(entry.relPath());
        }
        return names;
    }

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
        JobControl control = currentControl.get();
        if (control == null) {
            return;
        }
        // The deadline is enforced here as well as in the job's own loops, because a job can be blocked in a
        // read, a hash or a wait for a process and never reach one of those checkpoints.
        if (control.deadlineExceeded()) {
            control.requestCancel(AbortCause.TIMED_OUT);
            if (!control.terminationRequested()) {
                control.terminate();
            }
        }
        terminalGuard(control);
    }

    /**
     * A job thread stuck inside a read, a hash or somebody else's callback cannot be made to return, and the
     * engine must not report RUNNING indefinitely because of it. Once this job's process tree had been told
     * to die and the grace period has passed, the engine publishes the job's own counters as a stopped job and
     * lets go of it. The result that thread publishes later is dropped, so it cannot overwrite this one -
     * which is only safe because each job owns its controller and its publication gate.
     *
     * <p>The destination reservation is released here on purpose: the only thing that wrote to the destination
     * was rsync, and that process tree has been killed by the time this runs. What may still be running is
     * Java code reading files or calling back, which writes nothing.
     */
    private void terminalGuard(JobControl control) {
        if (!control.terminationRequested() || control.millisSinceTerminationRequested() < TERMINAL_GRACE_MILLIS) {
            return;
        }
        // Delivered off this thread, and not waited for: a completion callback is allowed to block, and the
        // watchdog still has a deadline to enforce on whatever job comes next.
        publishTerminalResult(control, TERMINAL_GRACE_MILLIS, Delivery.ELSEWHERE_WITHOUT_WAITING);
    }

    /**
     * Report a job as stopped when its own thread cannot do it.
     *
     * <p>The figures come from the snapshot that thread last published, not from its fields, which belong to a
     * thread that is stuck somewhere and could be read half-updated. A job stopped before it had any counters
     * - during validation, the directory scan, or the destination snapshot - is reported with none, naming the
     * phase it was in: there is no honest number for work that had not begun counting, and inventing a
     * reporter for it would only dress that up.
     */
    private void publishTerminalResult(JobControl control, long graceMillis, Delivery delivery) {
        ProgressReporter.Counters counters = control.counters();
        AbortCause why = control.cause() == AbortCause.NONE ? AbortCause.USER_CANCELLED : control.cause();
        long seconds = Math.max(1L, graceMillis / 1000L);
        String message = "Job stopped (" + abortWords(why) + ") while " + control.phase
                + " and its own thread had not finished within " + seconds
                + " s of its process being stopped. The figures below are as far as that thread had got;"
                + " the process it was running is gone, so nothing more is being written.";
        JobResult forced = new JobResult(JobStatus.FAILED, message, counters.matched(),
                counters.mismatched(), counters.missing(), counters.extra(), 0, 0, 0, 0, counters.copied(),
                Math.max(0, counters.rsyncTransfers()), counters.processed(), 0, List.of(), List.of(),
                List.of("the job's own thread was still finishing when this was reported; per-file results"
                        + " from it are not included"),
                counters.rowCount(), why);
        finishJob(forced, control, control.listener(), delivery);
    }

    /**
     * A scheduled task that throws is never rescheduled, which would silently disable the deadline for
     * the rest of the engine's life - so the tick has to survive anything it can throw.
     */
    private void watchdogLoop() {
        try {
            watchdogTick();
        } catch (Throwable t) {
            lastActivityAt.set(System.currentTimeMillis());
            lastActivity.set("watchdog skipped: " + t.getClass().getSimpleName());
        }
    }

    /** Mark activity only for the job that is still current, so an old worker cannot rewrite the status line. */
    private void markActivity(JobControl control, String description) {
        if (isCurrent(control)) {
            markActivity(description);
        }
    }

    private void markActivity(String description) {
        lastActivityAt.set(System.currentTimeMillis());
        lastActivity.set(description == null ? "" : description);
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
    private void sweepRsyncTree(JobControl control, Process process) {
        List<ProcessHandle> tree = new ArrayList<>(control.tree.get());
        for (ProcessHandle handle : snapshotTree(process)) {
            if (!tree.contains(handle)) {
                tree.add(handle);
            }
        }
        control.tree.set(List.of());
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
        markActivity(control, "killing " + alive.size() + " rsync process(es) that outlived their parent");
        signalTree(process, alive, false);
        if (!awaitTree(process, alive, 2)) {
            signalTree(process, alive, true);
        }
    }

    /** A job that was stopped before rsync was ever run: nothing moved, and it must not claim otherwise. */
    private JobResult abortedBeforeTransfer(String message, List<FileOutcome> outcomes, List<String> errors,
                                            List<String> warnings, boolean verifies, AbortCause cause) {
        String full = verifies ? message : message + " Verification was off, so nothing was compared.";
        return new JobResult(JobStatus.FAILED, full, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, outcomes, errors,
                warnings, 0, cause);
    }

    private JobResult failure(String message, List<String> errors, List<String> warnings, int exitCode) {
        return new JobResult(JobStatus.FAILED, message, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, exitCode, List.of(),
                errors, warnings, 0, AbortCause.NONE);
    }

    /**
     * Progress bookkeeping against the work-set size, with integer-percent coalescing and a
     * monotonic bar (progress never moves backwards within a job).
     */
    /** A time limit in words: 900 ms, 45.0 s, 5 min 3 s. */
    static String humanDuration(long millis) {
        if (millis < 1_000L) {
            return millis + " ms";
        }
        if (millis < 60_000L) {
            return String.format("%.1f s", millis / 1000.0);
        }
        return (millis / 60_000L) + " min " + ((millis / 1_000L) % 60L) + " s";
    }

    /**
     * The reason a job stopped, in the words used in its own message. "cancelled" is kept for both kinds of
     * cancellation and "timeout" for the limit, so someone searching a log for the reason finds it.
     */
    static String abortWords(AbortCause cause) {
        return switch (cause) {
            case USER_CANCELLED -> "cancelled by the user";
            case ENGINE_CLOSED -> "cancelled as the program was closing";
            case TIMED_OUT -> "stopped on timeout";
            case NONE -> "ended";
        };
    }

    private static final class ProgressReporter {

        private final int workTotal;
        private final ProgressListener listener;
        int processed;
        int matched;
        int mismatched;
        int missing;
        int extra;
        /** Transfers rsync announced: an observation about what started, never about what finished. */
        int copied;
        /** Rows this job produced, counted whether or not the results limit let them into the report. */
        int rowCount;
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

        /**
         * The counters as they stood at the last checkpoint, published whole. The watchdog reads this to
         * report a job whose own thread cannot finish reporting it; publishing a record rather than letting a
         * reader pick up six separate fields is what keeps a half-updated set of figures out of a report.
         */
        private record Counters(int processed, int matched, int mismatched, int missing, int extra,
                                int copied, int rsyncTransfers, int rowCount) {

            /** The verdict tally, kept in one place so no consumer has to add three figures itself. */
            int checked() {
                return matched + mismatched + missing;
            }
        }

        private volatile Counters snapshot = new Counters(0, 0, 0, 0, 0, 0, -1, 0);

        Counters snapshot() {
            return snapshot;
        }

        private void publish() {
            snapshot = new Counters(processed, matched, mismatched, missing, extra, copied, rsyncTransfers,
                    rowCount);
        }

        /** For a job stopped before it had anything to count. */
        static Counters emptyCounters() {
            return new Counters(0, 0, 0, 0, 0, 0, -1, 0);
        }

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
            publish();
        }

        /**
         * One more per-file row. Counted here rather than from the row list, because the list is capped and
         * the number of rows left out has to be reported.
         */
        void countRow() {
            rowCount++;
            publish();
        }

        int rowCount() {
            return rowCount;
        }

        /**
         * Tally one file's transfer disposition. Kept beside the row rather than derived from the row list,
         * because the list can be capped while the figures must not be.
         */
        void countMatched() {
            processed++;
            matched++;
            publish();
        }

        void countMismatched() {
            processed++;
            mismatched++;
            publish();
        }

        void countMissing() {
            processed++;
            missing++;
            publish();
        }

        void countExtra() {
            processed++;
            extra++;
            publish();
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
