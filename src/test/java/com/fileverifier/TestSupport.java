package com.fileverifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Shared helpers for the test suite. */
final class TestSupport {

    private TestSupport() {
    }

    static boolean rsyncAvailable() {
        return FileSyncEngine.isRsyncAvailable();
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
