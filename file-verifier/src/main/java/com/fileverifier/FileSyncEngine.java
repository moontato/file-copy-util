package com.fileverifier;

import javax.swing.*;
import java.io.*;
import java.nio.file.*;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

public class FileSyncEngine {

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

    public static class JobResult {
        private final JobStatus status;
        private final String message;
        private final int filesChecked;
        private final int filesMatched;
        private final int filesMismatched;
        private final int filesMissing;
        private final int filesCopied;
        private final List<String> errors;

        public JobResult(JobStatus status, String message, int filesChecked, int filesMatched,
                         int filesMismatched, int filesMissing, int filesCopied, List<String> errors) {
            this.status = status;
            this.message = message;
            this.filesChecked = filesChecked;
            this.filesMatched = filesMatched;
            this.filesMismatched = filesMismatched;
            this.filesMissing = filesMissing;
            this.filesCopied = filesCopied;
            this.errors = errors;
        }

        public JobStatus getStatus() { return status; }
        public String getMessage() { return message; }
        public int getFilesChecked() { return filesChecked; }
        public int getFilesMatched() { return filesMatched; }
        public int getFilesMismatched() { return filesMismatched; }
        public int getFilesMissing() { return filesMissing; }
        public int getFilesCopied() { return filesCopied; }
        public List<String> getErrors() { return errors; }
    }

    public interface ProgressListener {
        void onProgressUpdate(String message, int percent);
        void onFileProcessed(String fileName, String status);
        void onCompletion(JobResult result);
    }

    public static void runJob(File sourceDir, File destDir, JobType jobType,
                               Set<String> selectedSourceDirs, Set<String> selectedDestDirs,
                               ProgressListener listener) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                if (jobType == JobType.COPY_AND_VERIFY) {
                    runCopyAndVerify(sourceDir, destDir, selectedSourceDirs, listener);
                } else {
                    runVerifyOnly(sourceDir, destDir, selectedSourceDirs, selectedDestDirs, listener);
                }
            } catch (Exception e) {
                listener.onCompletion(new JobResult(JobStatus.FAILED,
                    "Job failed: " + e.getMessage(), 0, 0, 0, 0, 0,
                    Arrays.asList(e.getMessage())));
            }
        });
        executor.shutdown();
    }

    private static void runCopyAndVerify(File sourceDir, File destDir,
                                          Set<String> selectedSourceDirs,
                                          ProgressListener listener) {
        try {
            String destPath = destDir.getAbsolutePath();
            List<String> rsyncArgs = new ArrayList<>();
            rsyncArgs.add("rsync");
            rsyncArgs.add("-av");
            rsyncArgs.add("--delete");
            rsyncArgs.add("--checksum");
            rsyncArgs.add("--progress");

            if (selectedSourceDirs != null && !selectedSourceDirs.isEmpty()) {
                // Build include/exclude filters
                List<String> allDirs = DirectoryScanner.scanDirectories(sourceDir);
                for (String dir : allDirs) {
                    String relDir = dir.substring(sourceDir.getAbsolutePath().length());
                    if (relDir.startsWith(File.separator)) {
                        relDir = relDir.substring(1);
                    }
                    if (!selectedSourceDirs.contains(relDir)) {
                        rsyncArgs.add("--exclude");
                        rsyncArgs.add(relDir + "/");
                    }
                }
            }

            rsyncArgs.add(sourceDir.getAbsolutePath() + "/");
            rsyncArgs.add(destPath + "/");

            listener.onProgressUpdate("Starting rsync copy...", 0);

            ProcessBuilder pb = new ProcessBuilder(rsyncArgs);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Show progress from rsync output
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    if (line.contains("transfering")) {
                        listener.onProgressUpdate("Transferring files...", 25);
                    }
                    if (line.contains("generating")) {
                        listener.onProgressUpdate("Generating checksums...", 50);
                    }
                    if (line.contains("sent") || line.contains("total size")) {
                        listener.onProgressUpdate("Verifying copy...", 75);
                    }
                    if (line.startsWith("rsync: ") || line.startsWith("ERROR") ||
                        line.startsWith("failed") || line.startsWith("error")) {
                        listener.onFileProcessed(line.trim(), "error");
                    }
                }
            }

            int exitCode = process.waitFor();

            if (exitCode == 0) {
                listener.onProgressUpdate("Copy completed. Verifying...", 80);

                // Verify by comparing hashes of a sample
                Map<String, String> sourceHashes = FileHasher.computeHashesForDirectory(sourceDir);
                int totalFiles = sourceHashes.size();
                int matched = 0;
                int mismatched = 0;

                for (Map.Entry<String, String> entry : sourceHashes.entrySet()) {
                    File destFile = new File(destDir, entry.getKey());
                    if (destFile.exists()) {
                        String destHash = FileHasher.computeHash(destFile);
                        if (destHash.equals(entry.getValue())) {
                            matched++;
                        } else {
                            mismatched++;
                            listener.onFileProcessed(entry.getKey(), "mismatch");
                        }
                    } else {
                        mismatched++;
                        listener.onFileProcessed(entry.getKey(), "missing");
                    }
                    int percent = 80 + (int)((double)matched / Math.max(totalFiles, 1) * 20);
                    listener.onProgressUpdate("Verifying: " + matched + "/" + totalFiles + " files matched",
                        Math.min(percent, 100));
                }

                String msg = String.format("Copy & Verify Complete: %d files copied, %d matched, %d mismatched",
                    totalFiles, matched, mismatched);
                listener.onProgressUpdate(msg, 100);
                listener.onCompletion(new JobResult(JobStatus.COMPLETED, msg,
                    totalFiles, matched, mismatched, 0, totalFiles, new ArrayList<>()));
            } else {
                String msg = "rsync failed with exit code: " + exitCode;
                listener.onProgressUpdate(msg, -1);
                listener.onCompletion(new JobResult(JobStatus.FAILED, msg,
                    0, 0, 0, 0, 0, Arrays.asList("rsync exit code: " + exitCode)));
            }
        } catch (IOException | InterruptedException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Copy/Verify operation failed: " + e.getMessage(), e);
        }
    }

    private static void runVerifyOnly(File sourceDir, File destDir,
                                       Set<String> selectedSourceDirs,
                                       Set<String> selectedDestDirs,
                                       ProgressListener listener) {
        try {
            listener.onProgressUpdate("Starting verification...", 0);

            Map<String, String> sourceHashes = FileHasher.computeHashesForDirectory(sourceDir);
            int totalFiles = sourceHashes.size();
            int matched = 0;
            int mismatched = 0;
            int missing = 0;
            List<String> errors = new ArrayList<>();

            for (Map.Entry<String, String> entry : sourceHashes.entrySet()) {
                File destFile = new File(destDir, entry.getKey());
                String fileName = entry.getKey();

                // Check if parent directory is selected
                String parentDir = new File(fileName).getParent();
                if (parentDir != null && selectedSourceDirs != null && !selectedSourceDirs.isEmpty()) {
                    boolean parentSelected = false;
                    for (String sel : selectedSourceDirs) {
                        if (parentDir.startsWith(sel) || sel.startsWith(parentDir + File.separator) ||
                            parentDir.equals(sel)) {
                            parentSelected = true;
                            break;
                        }
                    }
                    if (!parentSelected) {
                        continue;
                    }
                }

                if (!destFile.exists()) {
                    missing++;
                    listener.onFileProcessed(fileName, "missing");
                } else {
                    String destHash = FileHasher.computeHash(destFile);
                    if (destHash.equals(entry.getValue())) {
                        matched++;
                        listener.onFileProcessed(fileName, "match");
                    } else {
                        mismatched++;
                        listener.onFileProcessed(fileName, "mismatch");
                    }
                }

                int percent = (int)((double)(matched + mismatched + missing) / Math.max(totalFiles, 1) * 100);
                listener.onProgressUpdate(
                    String.format("Verifying: %d/%d files - Matched: %d, Mismatched: %d, Missing: %d",
                        matched + mismatched + missing, totalFiles, matched, mismatched, missing),
                    Math.min(percent, 100));
            }

            String msg = String.format("Verification Complete: %d files checked, %d matched, %d mismatched, %d missing",
                totalFiles, matched, mismatched, missing);
            listener.onProgressUpdate(msg, 100);
            listener.onCompletion(new JobResult(JobStatus.COMPLETED, msg,
                totalFiles, matched, mismatched, missing, 0, errors));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Verification failed: " + e.getMessage(), e);
        }
    }
}
