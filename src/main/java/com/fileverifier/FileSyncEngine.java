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
        void onFileProcessed(String fileName, String status, String srcHash, String dstHash);
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
            } finally {
                executor.shutdown();
            }
        });
    }

  private static void runCopyAndVerify(File sourceDir, File destDir,
                                           Set<String> selectedSourceFiles,
                                           ProgressListener listener) {
        try {
            listener.onProgressUpdate("Starting rsync copy...", 0);

            // Use rsync with --files-from to copy only selected files
            List<String> rsyncArgs = new ArrayList<>();
            rsyncArgs.add("rsync");
            rsyncArgs.add("-av");
            rsyncArgs.add("--checksum");
            rsyncArgs.add("--progress");

            if (selectedSourceFiles != null && !selectedSourceFiles.isEmpty()) {
                // Create a temporary file with the list of files to copy
                File filelist = File.createTempFile("filelist-", ".txt");
                try (java.io.FileWriter writer = new java.io.FileWriter(filelist)) {
                    for (String relPath : selectedSourceFiles) {
                        writer.write(relPath + "\n");
                    }
                }
                rsyncArgs.add("--files-from=" + filelist.getAbsolutePath());
                rsyncArgs.add("--relative");
                rsyncArgs.add(sourceDir.getAbsolutePath() + "/");
                rsyncArgs.add(destDir.getAbsolutePath() + "/");

                ProcessBuilder pb = new ProcessBuilder(rsyncArgs);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    long lastHeartbeat = System.currentTimeMillis();
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("transferring")) {
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
                            listener.onFileProcessed(line.trim(), "error", "", "");
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastHeartbeat > 5000) {
                            listener.onProgressUpdate("Copying files... (still running)", 30);
                            lastHeartbeat = now;
                        }
                    }
                }

                int exitCode = process.waitFor();
                filelist.delete();

                if (exitCode != 0) {
                    String msg = "rsync failed with exit code: " + exitCode;
                    listener.onProgressUpdate(msg, -1);
                    listener.onCompletion(new JobResult(JobStatus.FAILED, msg,
                        0, 0, 0, 0, 0, Arrays.asList("rsync exit code: " + exitCode)));
                    return;
                }
            } else {
                // No files selected, just copy everything
                List<String> fullRsyncArgs = new ArrayList<>(rsyncArgs);
                fullRsyncArgs.add(sourceDir.getAbsolutePath() + "/");
                fullRsyncArgs.add(destDir.getAbsolutePath() + "/");

                ProcessBuilder pb = new ProcessBuilder(fullRsyncArgs);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    long lastHeartbeat = System.currentTimeMillis();
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("transferring")) {
                            listener.onProgressUpdate("Transferring files...", 25);
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastHeartbeat > 5000) {
                            listener.onProgressUpdate("Copying files... (still running)", 30);
                            lastHeartbeat = now;
                        }
                    }
                }
                process.waitFor();
            }

            listener.onProgressUpdate("Copy completed. Verifying...", 80);

            // Verify by comparing hashes
            Map<String, String> sourceHashes = FileHasher.computeHashesForDirectory(sourceDir);
            int totalFiles = sourceHashes.size();
            int matched = 0;
            int mismatched = 0;

            for (Map.Entry<String, String> entry : sourceHashes.entrySet()) {
                File destFile = new File(destDir, entry.getKey());
                if (destFile.exists()) {
                    String destHash = FileHasher.computeHash(destFile);
                    String srcHash = entry.getValue();
                    if (destHash.equals(srcHash)) {
                        matched++;
                    } else {
                        mismatched++;
                        listener.onFileProcessed(entry.getKey(), "mismatch",
                            srcHash.length() >= 5 ? srcHash.substring(0, 5) : srcHash,
                            destHash.length() >= 5 ? destHash.substring(0, 5) : destHash);
                    }
                } else {
                    mismatched++;
                    listener.onFileProcessed(entry.getKey(), "missing",
                        entry.getValue().length() >= 5 ? entry.getValue().substring(0, 5) : entry.getValue(),
                        "");
                }
                int percent = 80 + (int)((double)(matched + mismatched) / Math.max(totalFiles, 1) * 20);
                listener.onProgressUpdate("Verifying: " + matched + "/" + totalFiles + " files matched",
                    Math.min(percent, 100));
            }

            String msg = String.format("Copy & Verify Complete: %d files checked, %d matched, %d mismatched",
                totalFiles, matched, mismatched);
            listener.onProgressUpdate(msg, 100);
            listener.onCompletion(new JobResult(JobStatus.COMPLETED, msg,
                totalFiles, matched, mismatched, 0, totalFiles, new ArrayList<>()));
        } catch (IOException | InterruptedException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Copy/Verify operation failed: " + e.getMessage(), e);
        }
    }

 private static void runVerifyOnly(File sourceDir, File destDir,
                                        Set<String> selectedSourceFiles,
                                        Set<String> selectedDestFiles,
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
                String fileName = entry.getKey();

                // Check if file is selected in source
                if (selectedSourceFiles != null && !selectedSourceFiles.isEmpty()) {
                    if (!selectedSourceFiles.contains(fileName)) {
                        continue;
                    }
                }

                // Check if file is selected in destination
                if (selectedDestFiles != null && !selectedDestFiles.isEmpty()) {
                    if (!selectedDestFiles.contains(fileName)) {
                        continue;
                    }
                }

                File destFile = new File(destDir, fileName);

                if (!destFile.exists()) {
                    missing++;
                    String srcHash = entry.getValue();
                    listener.onFileProcessed(fileName, "missing",
                        srcHash.length() >= 5 ? srcHash.substring(0, 5) : srcHash, "");
                } else {
                    String destHash = FileHasher.computeHash(destFile);
                    String srcHash = entry.getValue();
                    if (destHash.equals(srcHash)) {
                        matched++;
                        listener.onFileProcessed(fileName, "match",
                            srcHash.length() >= 5 ? srcHash.substring(0, 5) : srcHash,
                            destHash.length() >= 5 ? destHash.substring(0, 5) : destHash);
                    } else {
                        mismatched++;
                        listener.onFileProcessed(fileName, "mismatch",
                            srcHash.length() >= 5 ? srcHash.substring(0, 5) : srcHash,
                            destHash.length() >= 5 ? destHash.substring(0, 5) : destHash);
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
