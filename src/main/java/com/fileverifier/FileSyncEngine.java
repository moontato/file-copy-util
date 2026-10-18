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

/**
 * Copy/verify engine. Deliberately free of any UI dependency so it can be driven headlessly
 * (see {@code FileSyncEngineTest}).
 *
 * <p><b>Path model.</b> Every file is identified by its {@link FileEntry#relPath()} - relative
 * to the scanned root, '/' separated. No absolute-path string arithmetic happens here.
 *
 * <p><b>Selection semantics.</b> Callers pass the *selected* relative paths. An empty set means
 * "zero files", never "no filter"; an empty work set is refused. Copying everything requires an
 * explicit Select All.
 *
 * <p><b>Work set.</b> COPY_AND_VERIFY: the source-side selection. VERIFY_ONLY: the source-side
 * selection, minus files the user unticked in the destination list - a file that has no row in
 * the destination list was not unticked, it is absent, and stays in the work set as "missing".
 *
 * <p><b>Counters are measured.</b> {@code filesCopied} counts rsync itemize transfer lines;
 * matched/mismatched/missing come from hashing exactly the work set, so
 * {@code filesChecked == filesMatched + filesMismatched + filesMissing}.
 *
 * <p>Phase 2 of the roadmap replaces the per-call executor in {@link #runJob} with an
 * engine-owned job lifecycle (start/cancel, {@code Process.destroy()}, IDLE/RUNNING states).
 */
public final class FileSyncEngine {

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

    private FileSyncEngine() {
    }

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

    /**
     * Async wrapper used by the GUI. Prefer {@link #execute} (synchronous, returns the result).
     */
    public static void runJob(File sourceDir, File destDir, JobType jobType,
                              Set<String> selectedSourceRel, Set<String> selectedDestRel,
                              ProgressListener listener) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                execute(sourceDir, destDir, jobType, selectedSourceRel, selectedDestRel, listener);
            } catch (Throwable t) {
                listener.onCompletion(failed("Job failed: " + t.getMessage(),
                        List.of(String.valueOf(t))));
            } finally {
                executor.shutdownNow();
            }
        });
    }

    /** Runs the job on the calling thread and reports through the listener. Never throws. */
    public static JobResult execute(File sourceDir, File destDir, JobType jobType,
                                    Set<String> selectedSourceRel, Set<String> selectedDestRel,
                                    ProgressListener listener) {
        JobResult result;
        try {
            result = doExecute(sourceDir, destDir, jobType, selectedSourceRel, selectedDestRel, listener);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = failed("Job interrupted: " + e.getMessage(), List.of(String.valueOf(e.getMessage())));
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            result = failed("Job failed: " + e.getMessage(), List.of(String.valueOf(e.getMessage())));
        }
        listener.onCompletion(result);
        return result;
    }

    private static JobResult doExecute(File sourceDir, File destDir, JobType jobType,
                                       Set<String> selectedSourceRel, Set<String> selectedDestRel,
                                       ProgressListener listener)
            throws IOException, InterruptedException, NoSuchAlgorithmException {

        if (sourceDir == null || destDir == null) {
            return failed("Both source and destination directories must be set.", List.of());
        }
        Path source = sourceDir.toPath().toAbsolutePath().normalize();
        Path dest = destDir.toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            return failed("Source directory does not exist: " + source, List.of());
        }
        if (!Files.isDirectory(dest)) {
            return failed("Destination directory does not exist: " + dest, List.of());
        }
        if (source.equals(dest)) {
            return failed("Source and destination are the same directory: " + source, List.of());
        }

        DirectoryScanner.ScanResult scan;
        try {
            scan = DirectoryScanner.scanFiles(source);
        } catch (UncheckedIOException e) {
            return failed("Could not scan source directory " + source + ": " + e.getMessage(),
                    List.of(String.valueOf(e.getMessage())));
        }

        Set<String> srcSel = normalize(selectedSourceRel);
        Set<String> dstSel = normalize(selectedDestRel);

        // Verify Only honours unticking a row in the destination list, but only for files that
        // actually exist there. A file with no destination row cannot have been unticked - it is
        // simply absent, which is the "missing" case and must stay reachable.
        Set<String> destSeen;
        if (jobType == JobType.VERIFY_ONLY) {
            try {
                destSeen = new java.util.HashSet<>();
                for (FileEntry entry : DirectoryScanner.scanFiles(dest).files()) {
                    destSeen.add(entry.relPath());
                }
            } catch (UncheckedIOException e) {
                return failed("Could not scan destination directory " + dest + ": " + e.getMessage(),
                        List.of(String.valueOf(e.getMessage())));
            }
        } else {
            destSeen = java.util.Set.of();
        }

        List<FileEntry> workSet = new ArrayList<>();
        for (FileEntry entry : scan.files()) {
            if (!srcSel.contains(entry.relPath())) {
                continue;
            }
            if (jobType == JobType.VERIFY_ONLY
                    && !dstSel.contains(entry.relPath())
                    && destSeen.contains(entry.relPath())) {
                continue;
            }
            workSet.add(entry);
        }

        List<String> warnings = new ArrayList<>(scan.warnings());
        if (workSet.isEmpty()) {
            return failed("Nothing to do: the selection is empty, so no files were copied or verified. "
                    + "Use Select All to include every file.",
                    List.of("Empty selection (" + scan.files().size() + " file(s) in source)."));
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
            if (run.fatal()) {
                return new JobResult(JobStatus.FAILED,
                        "rsync failed with exit code " + run.exitCode() + ".",
                        0, 0, 0, 0, copied, run.exitCode(), errors, warnings);
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
            String rel = entry.relPath();
            String srcHash;
            try {
                srcHash = FileHasher.computeHash(entry.absolutePath());
            } catch (IOException e) {
                missing++;
                warnings.add("Source file could not be read (vanished during job?): " + rel);
                listener.onFileProcessed(rel, "missing", "", "");
                progress.update(++done, statusText(done, workSet.size(), matched, mismatched, missing));
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
        }
        progress.complete(statusText(done, workSet.size(), matched, mismatched, missing));

        String message = jobType == JobType.COPY_AND_VERIFY
                ? String.format("Copy & Verify Complete: %d file(s) checked, %d copied, %d matched, %d mismatched, %d missing",
                    workSet.size(), copied, matched, mismatched, missing)
                : String.format("Verification Complete: %d file(s) checked, %d matched, %d mismatched, %d missing",
                    workSet.size(), matched, mismatched, missing);

        return new JobResult(JobStatus.COMPLETED, message, workSet.size(), matched, mismatched, missing,
                copied, rsyncExitCode, errors, warnings);
    }

    private static String statusText(int done, int total, int matched, int mismatched, int missing) {
        return String.format("Verifying %d/%d - matched: %d, mismatched: %d, missing: %d",
                done, total, matched, mismatched, missing);
    }

    // ---------------------------------------------------------------- rsync

    private record RsyncRun(int exitCode, int filesCopied, int dirsCreated, boolean fatal,
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

    /** The path printed after the 11-char itemize prefix; it is relative to the transfer root. */
    static String itemizePath(String line) {
        int space = line.indexOf(' ');
        return space < 0 || space == line.length() - 1 ? "" : line.substring(space + 1).trim();
    }

    private static RsyncRun runRsync(Path source, Path dest, List<FileEntry> workSet,
                                     ProgressListener listener)
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

        Path listFile = Files.createTempFile("file-copy-util-files-", ".txt");
        int exitCode;
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

            Process process;
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                process = pb.start();
            } catch (IOException e) {
                return new RsyncRun(-1, 0, 0, true, List.of(
                        "Could not start rsync (" + e.getMessage() + "). Is rsync installed and on PATH?"),
                        warnings);
            }

            ProgressReporter progress = new ProgressReporter(listener, listable.size(), 0, COPY_PROGRESS_END);
            int processed = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
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
                }
            }
            exitCode = process.waitFor();
        } finally {
            try {
                Files.deleteIfExists(listFile);
            } catch (IOException ignored) {
                // best effort
            }
        }

        return new RsyncRun(exitCode, copied, dirsCreated, !isSuccessfulExit(exitCode), errors, warnings);
    }

    // ---------------------------------------------------------------- helpers

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

    private static JobResult failed(String message, List<String> errors) {
        return new JobResult(JobStatus.FAILED, message, 0, 0, 0, 0, 0, 0, errors, List.of());
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
            lastPercent = 100;
            listener.onProgress(100, statusText);
        }
    }
}
