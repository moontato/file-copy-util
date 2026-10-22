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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

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
        File Copy Util - copy and verify files between directories (window title: File Verifier & Copier)

        GUI:      java -jar file-copy-util.jar
        Headless: java -jar file-copy-util.jar --cli --source DIR --dest DIR [options]

          --cli                     run without a display instead of opening the GUI
          --source DIR              source directory (required)
          --dest DIR                destination directory (required)
          --verify-only             compare hashes instead of copying
          --no-verify               copy without comparing: nothing is hashed, and the report lists each
                                    written file as COPIED instead of as a verdict
          --files a.txt,sub/b.txt   relative paths to include; repeatable
          --all                     every file the scan finds (see --exclude)
          --exclude a.txt,...       drop these relative paths from --all; repeatable
          --files-from FILE         names to include, one per line, exactly as written. A line ends at a
                                    line feed; a CR ends a line only if every line in the file ends CRLF,
                                    and a file that mixes the two is refused. Nothing else - vertical tab,
                                    form feed, NEL, U+2028 - ends a line
          --files-from0 FILE        the same, NUL-delimited: the only channel that can name a file
                                    whose name contains a newline or a trailing carriage return
          --exclude-from FILE       names to drop, same two formats as above
          --exclude-from0 FILE
          --exclude-dest-from FILE  the same for --exclude-dest
          --exclude-dest-from0 FILE
          --exclude-dest a.txt,...  --verify-only only: leave these files out of the comparison, as
                                    unticking their row does in the window
          --ignore "GLOB,GLOB"      add ignore patterns to the default list (%s)
          --no-default-ignore       start from an empty ignore list instead
          --no-hidden               exclude hidden files (they are included by default)
          --symlinks skip|link|follow
                                    skip symbolic links (default), copy them as links,
                                    or copy the file they point at
          --no-report-extra         do not report files that exist only at the destination
          --timeout SECONDS         kill a job that runs longer than this (default: no limit)
          --bwlimit KB/s            cap the transfer rate (rsync --bwlimit); 0 = unlimited
          --max-results N           how many individual results to keep (default 50000); counters are
                                    exact either way, but an export can only list what was kept
          --dry-run                 show what would happen and write nothing (rsync --dry-run)
          --csv PATH                write the job report as CSV
          --json PATH               write the job report as JSON
          --quiet                   no progress output
          --help                    this text

        An empty selection is never treated as "all files": either --files, --files-from(0) or --all is
        required.

        Which rsync is used is the one on PATH, or the one FCU_RSYNC names. Pin it when you need the run to
        mean something: the itemize lines, the progress stats and the hidden temporary files are rsync
        behaviour, and behaviour differs between versions.

        Name syntax. --files/--exclude/--exclude-dest are comma separated and each name is trimmed, which
        cannot express a name with a comma in it, a leading or trailing space, or a name starting with '#'
        or ';'. The --*-from(0) channels have no such syntax: every byte between the delimiters is the name,
        trimming nothing. A list file must be UTF-8; undecodable bytes are an error, not a replacement
        character, because a name built out of U+FFFD names a different file. --*-from splits on newlines,
        so it cannot name a file whose own name contains a newline - use --*-from0 for that.

        Examples:
          --cli --source /data/src --dest /backup/dst --all                       copy everything, verify it
          --cli --source /data/src --dest /backup/dst --all --no-verify            copy everything, compare nothing
          --cli --source /data/src --dest /backup/dst --all --dry-run             show what would happen first
          --cli --source /data/src --dest /backup/dst --files a.txt,sub/b.txt --csv report.csv
          --cli --source /data/src --dest /backup/dst --all --verify-only --json report.json
          --cli --source /data/src --dest /backup/dst --all --exclude "*.tmp" --no-hidden --symlinks link
          --cli --source /data/src --dest /backup/dst --all --verify-only --exclude-dest old.bin
                                    compare everything except a file known to be stale at the destination
          --cli --source /data/src --dest /backup/dst --all --timeout 600 --bwlimit 20000

        The counters: checked = matched + mismatched + missing. copied counts items rsync created or
        changed (a copied symbolic link counts, an unchanged file does not, and a job that was cut short
        reports "announced N, of which at most M finished"). destination-only files are counted apart
        from the others and need Verify Only with destination-only reporting on.

        Large trees, measured on this build: a 100k-file tree finishes in about 2 s for Verify Only and
        needs roughly 150 MB of heap; 300k files takes about 7 s and needs about 256 MB for Verify Only
        or 320 MB for a copy. Out of memory is reported as a failed job with this advice, not a crash.

        Exit codes: 0 = nothing to report, 1 = the job failed (preflight, rsync error, out of memory),
                    2 = bad command line, 3 = files mismatched or missing (for --dry-run: would be).
        """;

    private Cli() {
    }

    public static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        public UsageException(String message) {
            super(message);
        }
    }

    /** A parsed command line: one job request plus how to run and report it. */
    public record Options(JobRequest request, long timeoutMillis, long bandwidthLimitKbps,
                          int maxResults, Path csvPath, Path jsonPath, boolean quiet,
                          List<String> destinationExcluded) {

        public Options {
            destinationExcluded = List.copyOf(destinationExcluded);
        }

        @SuppressFBWarnings(value = "EI_EXPOSE_REP",
                justification = "The canonical constructor stores a List.copyOf, so this is an immutable "
                        + "list the parsed command line owns.")
        @Override
        public List<String> destinationExcluded() {
            return destinationExcluded;
        }
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
        // Not String.format: the usage text is a text block full of literal newlines, and a format
        // string would make them platform-dependent for no reason.
        return USAGE.replace("%s", String.join(", ", ScanOptions.DEFAULT_IGNORED_DIRS));
    }

    public static Options parse(String[] args) throws UsageException {
        String source = null;
        String dest = null;
        JobType jobType = JobType.COPY_AND_VERIFY;
        boolean askedVerifyOnly = false;
        boolean askedNoVerify = false;
        List<String> fileArgs = new ArrayList<>();
        List<String> excludeArgs = new ArrayList<>();
        List<String> excludeDestArgs = new ArrayList<>();
        // Names read from a list file: taken exactly as written, with no comma syntax and no trimming.
        List<String> exactFiles = new ArrayList<>();
        List<String> exactExcludes = new ArrayList<>();
        List<String> exactExcludeDests = new ArrayList<>();
        boolean all = false;
        List<String> extraIgnores = new ArrayList<>();
        boolean useDefaultIgnores = true;
        boolean includeHidden = true;
        ScanOptions.SymlinkPolicy policy = ScanOptions.SymlinkPolicy.SKIP;
        boolean detectExtra = true;
        long timeoutMillis = FileSyncEngine.NO_TIMEOUT;
        long bandwidthLimitKbps = 0L;
        int maxResults = FileSyncEngine.MAX_OUTCOMES;
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
                case "--verify-only" -> {
                    jobType = JobType.VERIFY_ONLY;
                    askedVerifyOnly = true;
                }
                case "--no-verify" -> {
                    jobType = JobType.COPY_ONLY;
                    askedNoVerify = true;
                }
                case "--exclude-dest" -> {
                    String value = value(args, i, arg);
                    if (value.isBlank()) {
                        throw new UsageException("--exclude-dest needs at least one relative path");
                    }
                    excludeDestArgs.add(value);
                    i++;
                }
                case "--files", "--exclude" -> {
                    String value = value(args, i, arg);
                    if ("--files".equals(arg)) {
                        fileArgs.add(value);
                    } else {
                        excludeArgs.add(value);
                    }
                    i++;
                }
                case "--files-from", "--files-from0", "--exclude-from", "--exclude-from0",
                        "--exclude-dest-from", "--exclude-dest-from0" -> {
                    String value = value(args, i, arg);
                    List<String> names = readNameList(Path.of(value), arg.endsWith("0"), arg);
                    if (arg.startsWith("--files")) {
                        exactFiles.addAll(names);
                    } else if (arg.startsWith("--exclude-dest")) {
                        exactExcludeDests.addAll(names);
                    } else {
                        exactExcludes.addAll(names);
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
                case "--max-results" -> {
                    String value = value(args, i, arg);
                    i++;
                    int limit;
                    try {
                        limit = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        throw new UsageException("--max-results must be a whole number, got '" + value + "'");
                    }
                    if (limit < 1) {
                        throw new UsageException("--max-results must be at least 1");
                    }
                    maxResults = limit;
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

        if (askedVerifyOnly && askedNoVerify) {
            // Whichever came last would silently win, and the two flags describe opposite jobs.
            throw new UsageException("--verify-only and --no-verify contradict each other: one compares"
                    + " without copying, the other copies without comparing.");
        }

        if (source == null || source.isBlank()) {
            throw new UsageException("--source is required");
        }
        if (dest == null || dest.isBlank()) {
            throw new UsageException("--dest is required");
        }
        if (fileArgs.isEmpty() && exactFiles.isEmpty() && !all) {
            throw new UsageException("Nothing was selected: pass --files a.txt,b.txt, --files-from FILE or "
                    + "--all. An empty selection means no files, not all files.");
        }
        if ((!fileArgs.isEmpty() || !exactFiles.isEmpty()) && all) {
            throw new UsageException("--files (or --files-from) and --all cannot both be used");
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
            excluded.addAll(FileSyncEngine.normalize(new LinkedHashSet<>(exactExcludes)));
            selected.removeAll(excluded);
        } else {
            for (String raw : fileArgs) {
                selected.addAll(FileSyncEngine.normalize(new LinkedHashSet<>(splitList(raw))));
            }
            selected.addAll(FileSyncEngine.normalize(new LinkedHashSet<>(exactFiles)));
            if (!excludeArgs.isEmpty() || !exactExcludes.isEmpty()) {
                throw new UsageException("--exclude only applies to --all; --files already says exactly "
                        + "which files to touch");
            }
        }
        if (selected.isEmpty()) {
            throw new UsageException("The selection resolved to 0 files: nothing to do.");
        }

        // A CLI user cannot tick rows, so a named path is taken to mean exactly what an unticked row
        // means in the window: the file is known at the destination and deliberately left out.
        Set<String> excludedAtDestination = new LinkedHashSet<>();
        for (String raw : excludeDestArgs) {
            excludedAtDestination.addAll(FileSyncEngine.normalize(new LinkedHashSet<>(splitList(raw))));
        }
        excludedAtDestination.addAll(FileSyncEngine.normalize(new LinkedHashSet<>(exactExcludeDests)));
        if (excludedAtDestination.containsAll(selected)) {
            throw new UsageException("--exclude-dest removes every selected file from the job: nothing left "
                    + "to verify.");
        }
        Selection destination = excludedAtDestination.isEmpty()
            ? Selection.none()
            : new Selection(Set.of(), excludedAtDestination);

        JobRequest request = new JobRequest(sourceDir, destDir, jobType,
                new Selection(selected, selected), destination, scanOptions, detectExtra, dryRun);
        return new Options(request, timeoutMillis, bandwidthLimitKbps, maxResults,
                csvPath == null ? null : Path.of(csvPath),
                jsonPath == null ? null : Path.of(jsonPath), quiet,
                List.copyOf(excludedAtDestination));
    }

    /**
     * Names read from a list file, exactly as written.
     *
     * <p>The comma syntax of {@code --files} cannot express a name containing a comma, a leading or
     * trailing space, a {@code #} or a {@code ;}, and the point of these channels is to reach the files
     * it cannot reach. So nothing here is trimmed, split on commas or otherwise rewritten.
     *
     * <p>The delimiter is chosen by the option name and never guessed: {@code --*-from} is newline
     * delimited (and therefore cannot carry a name containing a newline), {@code --*-from0} is NUL
     * delimited and carries every name this program can represent. Guessing between the two would mean a
     * file named with a newline inside it decided how its own list was parsed.
     *
     * <p>Bytes are decoded with the replacement action turned off: undecodable input is a named refusal
     * rather than a name built out of U+FFFD, which would be a file nobody asked for.
     */
    static List<String> readNameList(Path file, boolean nulDelimited, String option) throws UsageException {
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException problem) {
            throw new UsageException(option + " cannot be read: " + problem.getMessage());
        }
        java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        String text;
        try {
            text = decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (java.nio.charset.CharacterCodingException problem) {
            throw new UsageException(option + " is not valid UTF-8 (" + file + "). Fix the file's encoding: "
                    + "a name decoded through a replacement character would name a different file.");
        }
        List<String> names = nulDelimited ? splitOnNul(text, file, option) : splitOnLineFeed(text, file, option);
        if (names.isEmpty()) {
            throw new UsageException(option + " lists no names (" + file + "): a list of nothing selects "
                    + "nothing.");
        }
        return names;
    }

    /** NUL-delimited: every byte between two NULs is the name, and there is nothing else to decide. */
    private static List<String> splitOnNul(String text, Path file, String option) throws UsageException {
        List<String> names = new ArrayList<>();
        for (String name : text.split("\0", -1)) {
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Line-delimited, and the line ending is <b>LF</b>. Nothing else is a line ending here.
     *
     * <p>A regex {@code \R} would split on vertical tab, form feed, NEL, line separator and paragraph
     * separator as well. A file named {@code foo\u000Bbar} would then become two selections, {@code foo}
     * and {@code bar} - and if those two files happen to exist, the "exact" channel has just selected the
     * wrong files, which is the exact failure this whole phase exists to remove. So the delimiter is the
     * one character the format is defined on, and every other character is part of the name.
     *
     * <p>CRLF is accepted, and accepted only as a whole-file decision: if every LF in the file is preceded
     * by a CR, the file is CRLF and that CR is part of the line ending. A file that mixes the two is
     * refused, because a name ending in a real CR is indistinguishable from a CRLF line ending, and the
     * answer for such a name is {@code --files-from0}, not a guess.
     */
    private static List<String> splitOnLineFeed(String text, Path file, String option) throws UsageException {
        int feeds = 0;
        int crlf = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                feeds++;
                if (i > 0 && text.charAt(i - 1) == '\r') {
                    crlf++;
                }
            }
        }
        boolean wholeFileIsCRLF = feeds > 0 && crlf == feeds;

        List<String> names = new ArrayList<>();
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            String entry = end < 0 ? text.substring(start) : text.substring(start, end);
            if (wholeFileIsCRLF && entry.endsWith("\r")) {
                entry = entry.substring(0, entry.length() - 1);
            } else if (entry.endsWith("\r") && !entry.isEmpty()) {
                throw new UsageException(option + " (" + file + ") has a name ending in a carriage return"
                        + " next to a line ending that is not CRLF. A trailing carriage return cannot be told"
                        + " apart from a line ending in a line-delimited file: use "
                        + option.replace("-from", "-from0") + " for that name.");
            }
            if (!entry.isEmpty()) {
                if (entry.indexOf('\0') >= 0) {
                    throw new UsageException(option + " contains a NUL byte inside a name (" + file
                            + "): no path can name that file.");
                }
                names.add(entry);
            }
            if (end < 0) {
                break;
            }
            start = end + 1;
        }
        return names;
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

        if (!options.destinationExcluded().isEmpty()
                && options.request().jobType().copies()) {
            // Say it rather than let a flag quietly do nothing.
            err.println("Note: --exclude-dest does not apply to a copy; a copy follows the source "
                    + "selection. Use --verify-only to leave files out of the comparison.");
        }

        FileSyncEngine engine = new FileSyncEngine();
        engine.setJobTimeoutMillis(options.timeoutMillis());
        engine.setBandwidthLimitKbps(options.bandwidthLimitKbps());
        engine.setResultLimit(options.maxResults());
        ProgressReporter reporter = new ProgressReporter(out, options.quiet(), options.request().jobType());
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
        if (!options.request().jobType().verifies()) {
            // The usual totals would read "checked=0 matched=0 mismatched=0" for a job that compared
            // nothing on purpose, which looks like a report of a problem rather than an absence of one.
            out.printf("totals: copied=%d (verification was off: nothing was hashed or compared)%n",
                result.filesCopied());
        } else {
            out.printf("totals: checked=%d copied=%d matched=%d mismatched=%d missing=%d destination-only=%d%n",
                    result.filesChecked(), result.filesCopied(), result.filesMatched(),
                    result.filesMismatched(), result.filesMissing(), result.filesExtra());
        }

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
        private final boolean compares;
        private boolean explained;
        private int lastPercent = -1;

        private ProgressReporter(PrintStream out, boolean quiet, JobType jobType) {
            this.out = out;
            this.quiet = quiet;
            this.compares = jobType.verifies();
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
            // A verifying job already names every file with its verdict, so the transfer announcement is
            // noise. A job that compares nothing has no verdict, and the announcement is the only per-file
            // record it produces, so it is printed.
            if (quiet || "OK".equals(kind)) {
                return;
            }
            if ("TRANSFERRING".equals(kind)) {
                if (!explained) {
                    explained = true;
                    out.println("  rsync writes each file as a hidden temporary file in its destination"
                        + " folder (.big.iso.qjX7kX) and renames it when that file's transfer completes.");
                }
                out.println("  > " + RsyncNameCodec.forDisplay(relativePath));
                return;
            }
            boolean transferNoise = compares && ("COPIED".equals(kind) || "WOULD_COPY".equals(kind));
            if (transferNoise) {
                return;
            }
            out.println("  " + kind + "  " + RsyncNameCodec.forDisplay(relativePath)
                    + (sourceHash == null || destHash == null ? "" : "  src=" + sourceHash + " dst=" + destHash));
        }
    }
}
