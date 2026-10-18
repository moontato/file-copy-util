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
import java.util.stream.Collectors;

/** Shared helpers for the test suite. */
final class TestSupport {

    private TestSupport() {
    }

    static boolean rsyncAvailable() {
        try {
            Process process = new ProcessBuilder("rsync", "--version")
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    static Path write(Path file, String content) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    static Set<String> selection(String... relPaths) {
        return new LinkedHashSet<>(Arrays.asList(relPaths));
    }

    /** Convenience for "select everything the scanner found under dir". */
    static Set<String> selectAllOf(Path dir) {
        return DirectoryScanner.scanFiles(dir).files().stream()
                .map(FileEntry::relPath)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static List<String> relPathsUnder(Path dir) {
        return DirectoryScanner.scanFiles(dir).files().stream()
                .map(FileEntry::relPath)
                .toList();
    }

    static final class Recorder implements FileSyncEngine.ProgressListener {
        record FileEvent(String relPath, String status, String srcHash, String dstHash) {
        }

        final List<String> phases = new ArrayList<>();
        final List<Integer> percents = new ArrayList<>();
        final List<String> statusTexts = new ArrayList<>();
        final List<FileEvent> files = new ArrayList<>();
        int completions;
        FileSyncEngine.JobResult result;

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
