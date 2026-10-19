package com.fileverifier;

import com.fileverifier.FileSyncEngine.JobRequest;
import com.fileverifier.FileSyncEngine.JobResult;
import com.fileverifier.FileSyncEngine.JobType;
import com.fileverifier.FileSyncEngine.ProgressListener;
import com.fileverifier.FileSyncEngine.Selection;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Headless front end for {@link FileSyncEngine} (roadmap 4.6): the same engine, the same selection
 * and work-set rules, no Swing. It exists so the semantics the GUI guarantees can be scripted, and
 * so those semantics have one implementation rather than two.
 */
public final class Cli {

    public static final int EXIT_OK = 0;
    /** The job itself failed: preflight, an rsync error, cancellation. */
    public static final int EXIT_JOB_FAILED = 1;
    /** Bad command line. */
    public static final int EXIT_USAGE = 2;
    /** The job completed, but verification found mismatched or missing files. */
    public static final int EXIT_VERIFICATION_PROBLEMS = 3;

    private static final String USAGE = """
        File Verifier & Copier

        GUI:      java -jar file-verifier.jar
        Headless: java -jar file-verifier.jar --cli --source DIR --dest DIR [options]

          --cli                     run without a display instead of opening the GUI
          --source DIR              source directory (required)
          --dest DIR                destination directory (required)
          --verify-only             compare hashes instead of copying
          --files a.txt,sub/b.txt   relative paths to include; repeatable
          --all                     every file the scan finds (see --exclude)
          --exclude a.txt,...       drop these relative paths from --all; repeatable
          --ignore "GLOB,GLOB"      add ignore patterns to the default list (%s)
          --no-default-ignore       start from an empty ignore list instead
          --no-hidden               exclude hidden files (they are included by default)
          --symlinks skip|link|follow
                                    skip symbolic links (default), copy them as links,
                                    or copy the file they point at
          --no-report-extra         do not report files that exist only at the destination
          --timeout SECONDS         kill a job that runs longer than this (default: no limit)
          --bwlimit KB/s            cap the transfer rate (rsync --bwlimit); 0 = unlimited
          --dry-run                 show what would happen and write nothing (rsync --dry-run)
          --csv PATH                write the job report as CSV
          --json PATH               write the job report as JSON
          --quiet                   no progress output
          --help                    this text

        An empty selection is never treated as "all files": either --files or --all is required.

        Exit codes: 0 = nothing to report, 1 = the job failed (preflight, rsync error),
                    2 = bad command line, 3 = files mismatched or missing.
        """;

    private Cli() {
    }

    public static final class UsageException extends Exception {
        public UsageException(String message) {
            super(message);
        }
    }

    /** A parsed command line: one job request plus how to run and report it. */
    public record Options(JobRequest request, long timeoutMillis, long bandwidthLimitKbps,
                          Path csvPath, Path jsonPath, boolean quiet) {
    }

    /** True when the command line is meant for the headless mode rather than the GUI. */
    public static boolean isCliRequest(String[] args) {
        for (String arg : args) {
            if ("--cli".equals(arg) || "--help".equals(arg) || "-h".equals(arg)
                    || "--source".equals(arg) || "--dest".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    public static String usage() {
        return String.format(USAGE, String.join(", ", ScanOptions.DEFAULT_IGNORED_DIRS));
    }

    public static Options parse(String[] args) throws UsageException {
        String source = null;
        String dest = null;
        JobType jobType = JobType.COPY_AND_VERIFY;
        List<String> fileArgs = new ArrayList<>();
        List<String> excludeArgs = new ArrayList<>();
        boolean all = false;
        List<String> extraIgnores = new ArrayList<>();
        boolean useDefaultIgnores = true;
        boolean includeHidden = true;
        ScanOptions.SymlinkPolicy policy = ScanOptions.SymlinkPolicy.SKIP;
        boolean detectExtra = true;
        long timeoutMillis = FileSyncEngine.NO_TIMEOUT;
        long bandwidthLimitKbps = 0L;
        boolean dryRun = false;
        String csvPath = null;
        String jsonPath = null;
        boolean quiet = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--cli", "--help", "-h", "--quiet" -> {
                    if ("--quiet".equals(arg)) {
                        quiet = true;
                    }
                }
                case "--source", "--dest" -> {
                    String value = value(args, i, arg);
                    if ("--source".equals(arg)) {
                        source = value;
                    } else {
                        dest = value;
                    }
                    i++;
                }
                case "--verify-only" -> jobType = JobType.VERIFY_ONLY;
                case "--files", "--exclude" -> {
                    String value = value(args, i, arg);
                    if ("--files".equals(arg)) {
                        fileArgs.add(value);
                    } else {
                        excludeArgs.add(value);
                    }
                    i++;
                }
                case "--all" -> all = true;
                case "--ignore" -> {
                    extraIgnores.addAll(splitList(value(args, i, arg)));
                    i++;
                }
                case "--no-default-ignore" -> useDefaultIgnores = false;
                case "--no-hidden" -> includeHidden = false;
                case "--symlinks" -> {
                    String value = value(args, i, arg);
                    i++;
                    try {
                        policy = ScanOptions.SymlinkPolicy.valueOf(value.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        throw new UsageException("--symlinks must be skip, link or follow, got '" + value + "'");
                    }
                }
                case "--no-report-extra" -> detectExtra = false;
                case "--timeout" -> {
                    String value = value(args, i, arg);
                    i++;
                    long seconds;
                    try {
                        seconds = Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        throw new UsageException("--timeout must be a number of seconds, got '" + value + "'");
                    }
                    if (seconds < 0) {
                        throw new UsageException("--timeout cannot be negative");
                    }
                    timeoutMillis = seconds == 0 ? FileSyncEngine.NO_TIMEOUT : seconds * 1000L;
                }
                case "--dry-run" -> dryRun = true;
                case "--bwlimit" -> {
                    String value = value(args, i, arg);
                    i++;
                    long kbps;
                    try {
                        kbps = Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        throw new UsageException("--bwlimit must be KB/s, got '" + value + "'");
                    }
                    if (kbps < 0) {
                        throw new UsageException("--bwlimit cannot be negative");
                    }
                    bandwidthLimitKbps = kbps;
                }
                case "--csv", "--json" -> {
                    String value = value(args, i, arg);
                    if ("--csv".equals(arg)) {
                        csvPath = value;
                    } else {
                        jsonPath = value;
                    }
                    i++;
                }
                default -> throw new UsageException("Unknown option: " + arg);
            }
        }

        if (source == null || source.isBlank()) {
            throw new UsageException("--source is required");
        }
        if (dest == null || dest.isBlank()) {
            throw new UsageException("--dest is required");
        }
        if (fileArgs.isEmpty() && !all) {
            throw new UsageException("Nothing was selected: pass --files a.txt,b.txt or --all. "
                    + "An empty selection means no files, not all files.");
        }
        if (!fileArgs.isEmpty() && all) {
            throw new UsageException("--files and --all cannot both be used");
        }

        Path sourceDir = Path.of(source).toAbsolutePath();
        Path destDir = Path.of(dest).toAbsolutePath();
        // --ignore adds: dropping the built-in VCS/OS ignores because you added one pattern would
        // quietly copy .git objects. --no-default-ignore is the explicit way to start clean.
        List<String> ignorePatterns = new ArrayList<>();
        if (useDefaultIgnores) {
            ignorePatterns.addAll(ScanOptions.DEFAULT_IGNORED_DIRS);
        }
        ignorePatterns.addAll(extraIgnores);
        ScanOptions scanOptions = ScanOptions.parse(String.join(", ", ignorePatterns), includeHidden, policy);

        Set<String> selected = new LinkedHashSet<>();
        if (all) {
            // --all still has to become an explicit list: the engine's work set is built from a
            // selection, and expanding it here is what the GUI's "Scan Files" does for a user.
            for (FileEntry entry : DirectoryScanner.scanFiles(sourceDir, scanOptions).files()) {
                selected.add(entry.relPath());
            }
            Set<String> excluded = new LinkedHashSet<>();
            for (String raw : excludeArgs) {
                excluded.addAll(FileSyncEngine.normalize(new LinkedHashSet<>(splitList(raw))));
            }
            selected.removeAll(excluded);
        } else {
            for (String raw : fileArgs) {
                selected.addAll(FileSyncEngine.normalize(new LinkedHashSet<>(splitList(raw))));
            }
            if (!excludeArgs.isEmpty()) {
                throw new UsageException("--exclude only applies to --all; --files already says exactly "
                        + "which files to touch");
            }
        }
        if (selected.isEmpty()) {
            throw new UsageException("The selection resolved to 0 files: nothing to do.");
        }

        JobRequest request = new JobRequest(sourceDir, destDir, jobType,
                new Selection(selected, selected), Selection.none(), scanOptions, detectExtra, dryRun);
        return new Options(request, timeoutMillis, bandwidthLimitKbps,
                csvPath == null ? null : Path.of(csvPath),
                jsonPath == null ? null : Path.of(jsonPath), quiet);
    }

    private static List<String> splitList(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                parts.add(part.trim());
            }
        }
        return parts;
    }

    private static String value(String[] args, int index, String option) throws UsageException {
        if (index + 1 >= args.length) {
            throw new UsageException(option + " needs a value");
        }
        String value = args[index + 1];
        if (value.startsWith("--")) {
            throw new UsageException(option + " needs a value (got " + value + ")");
        }
        return value;
    }

    /** Runs one job and maps the outcome onto an exit code. */
    public static int run(String[] args, PrintStream out) {
        return run(args, out, System.err);
    }

    /** Both streams are parameters so the whole front end is testable without touching System.err. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (containsHelp(args)) {
            out.println(usage());
            return EXIT_OK;
        }
        Options options;
        try {
            options = parse(args);
        } catch (UsageException e) {
            err.println("Usage error: " + e.getMessage());
            err.println();
            err.println(usage());
            return EXIT_USAGE;
        }

        FileSyncEngine engine = new FileSyncEngine();
        engine.setJobTimeoutMillis(options.timeoutMillis());
        engine.setBandwidthLimitKbps(options.bandwidthLimitKbps());
        ProgressReporter reporter = new ProgressReporter(out, options.quiet());
        JobResult result;
        try {
            result = engine.execute(options.request(), reporter);
        } finally {
            engine.close();
        }

        for (String warning : result.warnings()) {
            out.println("warning: " + warning);
        }
        for (String error : result.errors()) {
            out.println("error: " + error);
        }
        out.println(result.message());
        out.printf("totals: checked=%d copied=%d matched=%d mismatched=%d missing=%d destination-only=%d%n",
                result.filesChecked(), result.filesCopied(), result.filesMatched(),
                result.filesMismatched(), result.filesMissing(), result.filesExtra());

        int exit = exitCode(result);
        writeReports(options, result, out);
        return exit;
    }

    static int exitCode(JobResult result) {
        if (result.status() != FileSyncEngine.JobStatus.COMPLETED) {
            return EXIT_JOB_FAILED;
        }
        return result.filesMismatched() + result.filesMissing() > 0 ? EXIT_VERIFICATION_PROBLEMS : EXIT_OK;
    }

    private static void writeReports(Options options, JobResult result, PrintStream out) {
        if (options.csvPath() != null) {
            write(options.csvPath(), ReportExporter.toCsv(result), out, "CSV");
        }
        if (options.jsonPath() != null) {
            write(options.jsonPath(), ReportExporter.toJson(result), out, "JSON");
        }
    }

    private static void write(Path target, String content, PrintStream out, String label) {
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
            out.println(label + " report written: " + target.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("could not write " + label + " report " + target + ": " + e.getMessage());
        }
    }

    private static boolean containsHelp(String[] args) {
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /** Progress goes to stdout as text; problems are printed the moment they are known. */
    private static final class ProgressReporter implements ProgressListener {
        private final PrintStream out;
        private final boolean quiet;
        private int lastPercent = -1;

        private ProgressReporter(PrintStream out, boolean quiet) {
            this.out = out;
            this.quiet = quiet;
        }

        @Override
        public void onCompletion(JobResult result) {
            // The caller prints the result; nothing to do here.
        }

        @Override
        public void onPhase(String phase) {
            if (!quiet) {
                out.println("[" + phase + "]");
            }
        }

        @Override
        public void onProgress(int percent, String statusText) {
            if (!quiet && percent != lastPercent) {
                lastPercent = percent;
                out.printf("  %3d%% %s%n", percent, statusText);
            }
        }

        @Override
        public void onFileProcessed(String relativePath, String kind, String sourceHash, String destHash) {
            if (quiet || "OK".equals(kind) || "COPIED".equals(kind)) {
                return;
            }
            out.println("  " + kind + "  " + relativePath
                    + (sourceHash == null || destHash == null ? "" : "  src=" + sourceHash + " dst=" + destHash));
        }
    }
}
