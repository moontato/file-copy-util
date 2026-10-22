package com.fileverifier;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What this job did to each file it transferred, and how much of that the destination can prove.
 *
 * <p>rsync announces a file when it <b>starts</b> the transfer - measured on 3.1.3 and 3.4.0, where a
 * transfer cut short after a third of a 300 MB copy still printed that file's itemize line - so an
 * announcement says nothing about completion. The only two things that can prove a transfer finished
 * are a <b>completion report</b> (the job ran to the end with a clean exit) and the <b>destination
 * itself</b>, compared against what was there before rsync started:
 *
 * <ul>
 *   <li>a file that did not exist before and exists now, of the expected type and size, was created
 *       by this job;</li>
 *   <li>a file that existed before and whose identity changed - the device and inode number, which a
 *       copy-over replaces because rsync writes a temporary file and renames it - was replaced by
 *       this job.</li>
 * </ul>
 *
 * <p>Anything else is {@link FileSyncEngine.TransferDisposition#UNCERTAIN}, and an interrupted
 * overwrite is exactly that case. Measured on rsync 3.4.0: interrupting a copy that overwrites a
 * 40 MB destination file with a same-sized source leaves the destination with the <b>original</b>
 * bytes, the <b>same inode</b> and the <b>same digest</b> as before the job, while rsync had already
 * printed the itemize line for it. Size, type, containment and permissions all match the source; not
 * one of them can show what happened. So the honest report names that file and says the transfer is
 * uncertain, instead of writing "copied" over a fact nobody observed.
 *
 * <p>Both proofs assume this program is the only writer of the destination - see the single-writer
 * rule in DESIGN.md section 3.4. Nothing here coordinates with other processes; that was considered
 * and declined, so a concurrent writer can make an attribution wrong.
 */
final class TransferLedger {

    /** How the destination looked before rsync was started. */
    private record Before(boolean exists, Object fileKey, long size, boolean regular, boolean link) {
        static final Before ABSENT = new Before(false, null, -1, false, false);
    }

    private final Path destDir;
    private final Map<String, Before> before;
    private final transient List<String> unreadableNames = new ArrayList<>();

    /**
     * Records the destination state for every name the job may write. One stat per selected file:
     * unavoidable, because a fact about "what this job changed" needs a before-and-after comparison.
     */
    TransferLedger(Path destDir, List<FileEntry> workSet, List<String> warnings) {
        this.destDir = destDir;
        this.before = new java.util.HashMap<>(Math.max(16, workSet.size() * 2));
        for (FileEntry entry : workSet) {
            String rel = entry.relPath();
            try {
                BasicFileAttributes attrs = Files.readAttributes(destDir.resolve(rel),
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                before.put(rel, new Before(true, attrs.fileKey(), attrs.size(),
                        attrs.isRegularFile(), attrs.isSymbolicLink()));
            } catch (java.nio.file.NoSuchFileException absent) {
                before.put(rel, Before.ABSENT);
            } catch (IOException problem) {
                // No snapshot for this name: the after-state can then prove nothing, and the report
                // says so instead of guessing.
                before.put(rel, null);
                if (unreadableNames.size() < 20) {
                    unreadableNames.add(rel);
                }
            }
        }
        if (!unreadableNames.isEmpty()) {
            warnings.add("The destination could not be inspected before the copy for "
                    + unreadableNames.size() + " file(s), starting with "
                    + unreadableNames.get(0) + ": their transfer result cannot be confirmed afterwards.");
        }
    }

    /**
     * The verdict for one file that rsync announced.
     *
     * @param rel            the file's path relative to the source directory
     * @param sourceSize     size of the source file, or -1 when it is not known
     * @param cleanCompletion the copy phase ran to the end with an exit rsync considers clean
     * @param dryRun          nothing was written at all
     */
    FileSyncEngine.TransferDisposition disposition(String rel, long sourceSize, boolean cleanCompletion,
                                                   boolean dryRun) {
        if (dryRun) {
            return FileSyncEngine.TransferDisposition.WOULD_COPY;
        }
        if (cleanCompletion) {
            return FileSyncEngine.TransferDisposition.COPIED;
        }
        Before previous = before.get(rel);
        if (previous == null) {
            return FileSyncEngine.TransferDisposition.UNCERTAIN;
        }
        Path target = destDir.resolve(rel);
        BasicFileAttributes now;
        try {
            now = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException cannotRead) {
            // Nothing there, or unreadable. Either way there is nothing to compare against the pre-state,
            // and a file rsync started and cannot be found now is exactly the case that must not be
            // reported as copied.
            return FileSyncEngine.TransferDisposition.UNCERTAIN;
        }
        if (!previous.exists) {
            // Creation: a new file of the expected kind and size. The size is what says the whole file
            // arrived; a half-written transfer is shorter than the source.
            boolean kindMatches = sourceSize >= 0 ? now.isRegularFile() : (now.isRegularFile()
                    || now.isDirectory() || now.isSymbolicLink());
            boolean sizeMatches = sourceSize < 0 || now.size() == sourceSize;
            return kindMatches && sizeMatches
                ? FileSyncEngine.TransferDisposition.COPIED
                : FileSyncEngine.TransferDisposition.UNCERTAIN;
        }
        // Overwrite: rsync writes a temporary file and renames it, so a completed replacement changes
        // the inode. The same identity means the bytes on disk are the ones that were already there.
        Object nowKey = now.fileKey();
        if (nowKey != null && previous.fileKey != null && !nowKey.equals(previous.fileKey)) {
            return FileSyncEngine.TransferDisposition.COPIED;
        }
        if (previous.link != now.isSymbolicLink() || previous.regular != now.isRegularFile()) {
            return FileSyncEngine.TransferDisposition.COPIED;
        }
        return FileSyncEngine.TransferDisposition.UNCERTAIN;
    }

    /** Names whose pre-state could not be recorded; their transfer can never be confirmed. */
    List<String> unconfirmableNames() {
        return List.copyOf(unreadableNames);
    }

    /** Only used for error messages, so a missing destination directory is not fatal here. */
    boolean destinationExists() {
        return Files.isDirectory(destDir);
    }
}
