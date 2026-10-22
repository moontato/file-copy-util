package com.fileverifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Shared helpers for the test suite. */
final class TestSupport {

    private TestSupport() {
    }

    /**
     * A pinned rsync is not optional. When {@code FCU_RSYNC} names a binary, a test run that cannot use it
     * has to fail: otherwise every rsync-dependent test skips itself and the run quietly becomes a partial
     * check, which is what a version matrix must never do.
     */
    static {
        String pinned = System.getenv("FCU_RSYNC");
        if (pinned != null && !pinned.isBlank() && !FileSyncEngine.isRsyncAvailable(pinned)) {
            throw new IllegalStateException("FCU_RSYNC names " + pinned + " but that rsync cannot be run;"
                    + " refusing to run the rsync tests against anything else.");
        }
    }

    /**
     * Every regular file under {@code root}, as relative path to digest. Take it before a job runs and
     * hand it to {@link #assertUnchanged} afterwards: a refused or failed job must leave the tree exactly
     * as it found it. "The job reported failure" is not the same statement as "nothing was destroyed".
     */
    static Map<String, String> digests(Path root) throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    String digest;
                    try {
                        digest = FileHasher.computeHash(path);
                    } catch (IOException unreadable) {
                        // A fixture that makes a file unreadable on purpose still has to be comparable
                        // before and after: the marker says "this one could not be read", and it says the
                        // same thing both times.
                        digest = "<unreadable>";
                    }
                    hashes.put(root.relativize(path).toString().replace('\\', '/'), digest);
                }
            }
        }
        return hashes;
    }

    static void assertUnchanged(Path root, Map<String, String> before) throws Exception {
        assertEquals(before, digests(root),
            "the tree at " + root + " is not what it was before this job ran");
    }

    static boolean rsyncAvailable() {
        return FileSyncEngine.isRsyncAvailable();
    }

    /**
     * The rsync this process was started with - {@code FCU_RSYNC} if it was set, otherwise the plain
     * {@code rsync} on the PATH. Captured once, before any test replaces it.
     */
    static final String CONFIGURED_RSYNC = FileSyncEngine.rsyncBinary();

    /** A block of test code that may throw. */
    interface RsyncBody {
        void run() throws Exception;
    }

    /**
     * Run a block with the process-wide rsync replaced, and put back <b>exactly the value that was
     * replaced</b> afterwards.
     *
     * <p>Restoring the literal {@code "rsync"} instead is a bug with a delayed fuse: a test that swaps in a
     * fake, then restores {@code "rsync"}, silently drops a pinned {@code FCU_RSYNC} build. Everything that
     * runs later in the same JVM - which is how one Maven run is organised - then tests a different rsync
     * version than the one it was started with, and nothing reports that. The startup check cannot catch it
     * because the change happens after the check.
     *
     * <p>Where a test only needs one engine to use another binary, {@link FileSyncEngine#useRsync} is the
     * better tool: it changes that engine and nothing else. This is for the cases that genuinely need the
     * process default, which is a fake binary reached through a static helper.
     */
    static void withRsyncBinary(String binary, RsyncBody body) throws Exception {
        String previous = FileSyncEngine.rsyncBinary();
        FileSyncEngine.setRsyncBinary(binary);
        try {
            body.run();
        } finally {
            FileSyncEngine.setRsyncBinary(previous);
        }
    }

    /** A job request with default scan options and no destination-only detection. */
    static FileSyncEngine.JobRequest request(Path source, Path dest, FileSyncEngine.JobType type,
                                             FileSyncEngine.Selection sourceSelection,
                                             FileSyncEngine.Selection destSelection) {
        return request(source, dest, type, sourceSelection, destSelection, ScanOptions.DEFAULTS, false);
    }

    static FileSyncEngine.JobRequest request(Path source, Path dest, FileSyncEngine.JobType type,
                                             FileSyncEngine.Selection sourceSelection,
                                             FileSyncEngine.Selection destSelection,
                                             ScanOptions options, boolean detectExtraFiles) {
        return request(source, dest, type, sourceSelection, destSelection, options, detectExtraFiles, false);
    }

    static FileSyncEngine.JobRequest request(Path source, Path dest, FileSyncEngine.JobType type,
                                             FileSyncEngine.Selection sourceSelection,
                                             FileSyncEngine.Selection destSelection,
                                             ScanOptions options, boolean detectExtraFiles, boolean dryRun) {
        return new FileSyncEngine.JobRequest(source, dest, type, sourceSelection, destSelection,
                options, detectExtraFiles, dryRun);
    }

    /**
     * Writes a stand-in rsync that prints the given lines and exits with the given code, so the
     * engine's failure handling can be tested without a real transfer.
     */
    static Path fakeRsync(Path dir, String output, int exitCode) throws IOException {
        Path script = dir.resolve("fake-rsync");
        Files.writeString(script, "#!/bin/sh\nprintf '%s\\n' " + shellQuote(output) + "\nexit " + exitCode + "\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, java.util.EnumSet.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * A stand-in rsync that prints a scripted output (progress lines included) and then really
     * copies the tree, so progress scaling and verification can be tested with an exact,
     * timing-independent sequence. The engine always passes source and destination as the last two
     * arguments, which is what this script reads.
     */
    static Path copyingFakeRsync(Path dir, String output) throws IOException {
        Path script = dir.resolve("fake-copy-rsync");
        String body = """
                #!/bin/sh
                printf '%%s\\n' %s
                src=$(eval echo "\\${$(($#-1))}")
                dst=$(eval echo "\\${$#}")
                cp -R "$src/." "$dst/"
                exit 0
                """.formatted(shellQuote(output));
        Files.writeString(script, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, java.util.EnumSet.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    /**
     * A stand-in rsync that prints {@code output}, leaves {@code partialFile} behind in the destination
     * (the hidden file a killed transfer leaves), and then hangs so a timeout or a cancellation can cut
     * it off. Pass null for {@code partialFile} to model a run that cleaned up after itself.
     */
    static Path hangingFakeRsync(Path dir, String output, String partialFile) throws IOException {
        Path script = dir.resolve("fake-hanging-rsync");
        StringBuilder body = new StringBuilder();
        body.append("#!/bin/sh\n");
        body.append("printf '%s\\n' ").append(shellQuote(output)).append('\n');
        if (partialFile != null) {
            // The engine passes source and destination as the last two arguments; the eval turns
            // "\${N}" into positional argument N, which is the destination directory.
            body.append("dst=$(eval echo \"\\${$#}\")\n");
            body.append(": > \"$dst/").append(partialFile).append("\"\n");
        }
        body.append("sleep 20\n");
        body.append("exit 137\n");
        Files.writeString(script, body.toString(), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, java.util.EnumSet.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    /**
     * A stand-in rsync that fails with {@code exitCode} after leaving {@code partialFile} behind in the
     * destination, which is what a killed transfer actually does.
     */
    static Path failingFakeRsync(Path dir, String output, int exitCode, String partialFile) throws IOException {
        Path script = dir.resolve("fake-failing-rsync");
        StringBuilder body = new StringBuilder();
        body.append("#!/bin/sh\n");
        body.append("printf '%s\\n' ").append(shellQuote(output)).append('\n');
        if (partialFile != null) {
            body.append("dst=$(eval echo \"\\${$#}\")\n");
            body.append(": > \"$dst/").append(partialFile).append("\"\n");
        }
        body.append("exit ").append(exitCode).append('\n');
        Files.writeString(script, body.toString(), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, java.util.EnumSet.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    /**
     * A stand-in rsync that transfers nothing but edits a source file while it is "running", which is how
     * a file can move underneath a copy whose digests were taken before it started. The source directory
     * is the second-to-last argument.
     */
    static Path mutatingFakeRsync(Path dir, String output, String mutationRelPath, String newContent,
                                  int exitCode) throws IOException {
        Path script = dir.resolve("fake-mutating-rsync");
        StringBuilder body = new StringBuilder();
        body.append("#!/bin/sh\n");
        body.append("printf '%s\\n' ").append(shellQuote(output)).append('\n');
        body.append("src=$(eval echo \"\\${$(($# - 1))}\")\n");
        body.append("printf '%s' ").append(shellQuote(newContent)).append(" > \"$src/")
            .append(mutationRelPath).append("\"\n");
        body.append("exit ").append(exitCode).append('\n');
        Files.writeString(script, body.toString(), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, java.util.EnumSet.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    /**
     * A file of the given apparent size with no data behind it: a hole. Used by tests that need a transfer
     * long enough to interrupt, without writing gigabytes to the machine running the tests.
     */
    static Path sparse(Path file, long bytes) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(bytes);
        }
        return file;
    }

    /**
     * Processes whose own command is {@code program} - argv[0] equal to it, or ending in "/program". This
     * is how a test checks that a cancelled copy really stopped: a local rsync run is three processes, and
     * a program that killed only one of them leaves two reading the source disk after it has exited.
     */
    static List<String> processesRunning(String program) {
        List<String> found = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(handle -> handle.info().command().ifPresent(command -> {
            if (command.equals(program) || command.endsWith("/" + program)) {
                found.add("pid " + handle.pid() + ": " + command);
            }
        }));
        return found;
    }

    /** Wait briefly for the given program to disappear, then fail with what is still running. */
    static void assertNoProcessesRunning(String program, String when) throws InterruptedException {
        List<String> alive = List.of();
        for (int attempt = 0; attempt < 20; attempt++) {
            alive = processesRunning(program);
            if (alive.isEmpty()) {
                return;
            }
            Thread.sleep(250);
        }
        org.junit.jupiter.api.Assertions.assertTrue(processesRunning(program).isEmpty(),
            when + ": processes still running: " + alive);
    }

    static Path write(Path file, String content) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** A selection where every listed path is also a row in the list (i.e. all ticked). */
    static FileSyncEngine.Selection selection(String... relPaths) {
        Set<String> s = new LinkedHashSet<>(Arrays.asList(relPaths));
        return new FileSyncEngine.Selection(s, s);
    }

    /** Rows that exist in the list, of which only {@code selected} are ticked. */
    static FileSyncEngine.Selection knownRows(Set<String> known, String... selected) {
        return new FileSyncEngine.Selection(
                new LinkedHashSet<>(Arrays.asList(selected)), new LinkedHashSet<>(known));
    }

    static FileSyncEngine.Selection none() {
        return FileSyncEngine.Selection.none();
    }

    /** Convenience for "select everything the scanner found under dir". */
    static FileSyncEngine.Selection selectAllOf(Path dir) {
        Set<String> all = DirectoryScanner.scanFiles(dir).files().stream()
                .map(FileEntry::relPath)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new FileSyncEngine.Selection(all, all);
    }

    static List<String> relPathsUnder(Path dir) {
        return DirectoryScanner.scanFiles(dir).files().stream()
                .map(FileEntry::relPath)
                .toList();
    }

    static class Recorder implements FileSyncEngine.ProgressListener {
        record FileEvent(String relPath, String status, String srcHash, String dstHash) {
        }

        final List<String> phases = new ArrayList<>();
        final List<Integer> percents = new ArrayList<>();
        final List<String> statusTexts = new ArrayList<>();
        final List<FileEvent> files = new ArrayList<>();
        int completions;
        FileSyncEngine.JobResult result;
        private final CountDownLatch completionLatch = new CountDownLatch(1);

        /** For jobs started asynchronously: blocks until onCompletion has fired. */
        boolean awaitCompletion(long millis) {
            try {
                return completionLatch.await(millis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public void onPhase(String message) {
            phases.add(message);
        }

        @Override
        public void onProgress(int percent, String statusText) {
            percents.add(percent);
            statusTexts.add(statusText);
        }

        @Override
        public void onFileProcessed(String relPath, String status, String srcHash, String dstHash) {
            files.add(new FileEvent(relPath, status, srcHash, dstHash));
        }

        @Override
        public void onCompletion(FileSyncEngine.JobResult result) {
            completions++;
            this.result = result;
            completionLatch.countDown();
        }

        List<String> pathsWithStatus(String status) {
            return files.stream().filter(f -> f.status().equals(status)).map(FileEvent::relPath).toList();
        }

        FileEvent eventFor(String relPath) {
            return files.stream().filter(f -> f.relPath().equals(relPath)).findFirst().orElse(null);
        }

        int lastPercent() {
            return percents.isEmpty() ? -1 : percents.get(percents.size() - 1);
        }

        /** Progress callbacks must be coarse (integer-percentage changes only), not per-file. */
        void assertProgressIsCoarse() {
            if (percents.size() > 101) {
                throw new AssertionError("progress callbacks should be coalesced, got " + percents.size());
            }
            int previous = -1;
            for (int percent : percents) {
                if (percent < previous) {
                    throw new AssertionError("progress must be monotonic: " + percents);
                }
                previous = percent;
            }
        }
    }
}
