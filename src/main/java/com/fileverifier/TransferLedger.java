package com.fileverifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * What this job was meant to write, and what the destination held before it started.
 *
 * <p>rsync announces a file when it <b>starts</b> the transfer - measured on 3.1.3 and 3.4.0, where a
 * transfer cut short after a third of a 300 MB copy still printed that file's itemize line - so an
 * announcement says nothing about completion. Two things can show that a transfer finished: the job ran to
 * a clean end, or the destination changed in a way that only a completed transfer explains. Both need a
 * record taken <b>before</b> rsync ran, which is what this class is.
 *
 * <p>An interrupted overwrite is the case the record exists for. Measured on rsync 3.4.0: interrupt a copy
 * that overwrites a 40 MB destination file with a same-sized source and the destination keeps the
 * <b>original</b> bytes, the <b>same inode</b> and the <b>same digest</b>, while rsync had already printed
 * the itemize line for that file. Name, size, containment and permissions all match the source; not one of
 * them shows what happened. Reporting that as copied records an event nobody observed, so it is reported as
 * uncertain.
 *
 * <p>Both proofs assume this program is the only writer of the destination - the single-writer rule in
 * DESIGN.md section 7. Nothing here coordinates with other processes; that was considered and declined, so
 * a concurrent writer can make an attribution wrong.
 */
final class TransferLedger {

    /** What the job intended to write, read from the source before rsync started. */
    private record Expected(boolean present, boolean regular, boolean link, long size, String linkText) {
        static final Expected UNKNOWN = new Expected(false, false, false, -1, null);
    }

    /** What the destination held before rsync started. */
    private record Before(boolean exists, Object fileKey, long size, boolean regular, boolean link) {
        static final Before ABSENT = new Before(false, null, -1, false, false);
    }

    private final Path destDir;
    private final Map<String, Before> before;
    private final Map<String, Expected> expected;
    private final List<String> unreadableNames = new ArrayList<>();

    /** Whether a stat found the name, found it missing, or could not answer the question at all. */
    enum Existence { PRESENT, ABSENT, UNKNOWN }

    /**
     * Ask one question of one path, without following links: is the name there?
     *
     * <p>Only {@code NoSuchFileException} is an answer of "absent". An access error, a filesystem error or an
     * ancestor that cannot be resolved (a symbolic-link loop, say) means the question could not be asked, and
     * treating that as absence would let a job report a file as not copied on evidence that proves nothing.
     */
    static Existence probe(Path path) {
        try {
            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return Existence.PRESENT;
        } catch (java.nio.file.NoSuchFileException absent) {
            return Existence.ABSENT;
        } catch (IOException couldNotAsk) {
            return Existence.UNKNOWN;
        }
    }

    /** What stood at the destination for this name before the copy began. */
    Existence heldBefore(String rel) {
        Before previous = before.get(rel);
        if (previous == Before.ABSENT) {
            return Existence.ABSENT;
        }
        if (previous != null) {
            return Existence.PRESENT;
        }
        // The snapshot either could not read this name or never covered it: that is not "nothing was there".
        return Existence.UNKNOWN;
    }

    /** What stands at the destination for this name now. */
    Existence existsNow(String rel) {
        return probe(destDir.resolve(rel));
    }

    /**
     * Records the source intent and the destination state for every name the job may write.
     *
     * <p>Cost is deliberately stated: one metadata call per selected file, at both ends, before any byte is
     * transferred. There is no cheaper way to answer "did this job finish writing this file", because the
     * question is a before-and-after question. Metadata only - no file content is read here, and a copy job
     * with verification off reads no source data at all. On a network mount the metadata latency can be far
     * higher than on a local disk, which is why the loop is cancellable.
     *
     * @param abort checked periodically so a Cancel during a long snapshot stops the job instead of
     *              running to the end of a list the user has already asked to stop
     */
    TransferLedger(Path sourceDir, Path destDir, List<FileEntry> workSet, List<String> warnings,
                   BooleanSupplier abort) {
        this.destDir = destDir;
        int capacity = Math.max(16, workSet.size() * 2);
        this.before = new java.util.HashMap<>(capacity);
        this.expected = new java.util.HashMap<>(capacity);
        boolean snapshotStoppedEarly = false;
        int index = 0;
        for (FileEntry entry : workSet) {
            if ((index++ & 0xFF) == 0 && abort.getAsBoolean()) {
                snapshotStoppedEarly = true;
                break;
            }
            String rel = entry.relPath();
            expected.put(rel, readSource(sourceDir.resolve(rel)));
            before.put(rel, readDestination(destDir.resolve(rel), rel, warnings));
        }
        if (index < workSet.size()) {
            snapshotStoppedEarly = true;
        }
        if (snapshotStoppedEarly) {
            warnings.add("The snapshot taken before the copy was stopped early: the transfer result of the"
                    + " files it had not reached cannot be confirmed afterwards.");
        }
        if (!unreadableNames.isEmpty()) {
            warnings.add("The destination could not be inspected before the copy for "
                    + unreadableNames.size() + " file(s), starting with "
                    + RsyncNameCodec.forDisplay(unreadableNames.get(0)) + ": their transfer result cannot be"
                    + " confirmed afterwards.");
        }
    }

    private static Expected readSource(Path source) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(source, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            String linkText = attrs.isSymbolicLink() ? readLinkText(source) : null;
            return new Expected(true, attrs.isRegularFile(), attrs.isSymbolicLink(), attrs.size(), linkText);
        } catch (IOException problem) {
            // The source vanished between the scan and the copy. Nothing can then be shown about the
            // destination, which is the honest answer.
            return Expected.UNKNOWN;
        }
    }

    private Before readDestination(Path target, String rel, List<String> warnings) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return new Before(true, attrs.fileKey(), attrs.size(), attrs.isRegularFile(),
                    attrs.isSymbolicLink());
        } catch (java.nio.file.NoSuchFileException absent) {
            return Before.ABSENT;
        } catch (IOException problem) {
            unreadableNames.add(rel);
            // A name with no recorded pre-state can never be confirmed afterwards.
            return null;
        }
    }

    private static String readLinkText(Path path) {
        try {
            return Files.readSymbolicLink(path).toString();
        } catch (IOException problem) {
            return null;
        }
    }

    /**
     * Names where the object standing in the destination before the copy was not the kind the source has: a
     * link where a file belongs, or a file where a link belongs.
     *
     * <p>Said before the transfer, because a copy erases the evidence: after replacing a link with a file the
     * destination shows a file, and nothing there says what it replaced.
     */
    List<String> kindChanges() {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, Expected> entry : expected.entrySet()) {
            Expected intent = entry.getValue();
            Before previous = before.get(entry.getKey());
            if (previous == null || previous == Before.ABSENT || intent == null
                    || intent == Expected.UNKNOWN) {
                continue;
            }
            if (previous.link() == intent.link() && previous.regular() == intent.regular()) {
                continue;
            }
            found.add(RsyncNameCodec.forDisplay(entry.getKey()) + ": the destination held " + kind(previous)
                    + " where this job's source has " + kind(intent) + ". A copy replaces the object at that"
                    + " path, so what stands there afterwards is what this job wrote, not what was there"
                    + " before it.");
        }
        java.util.Collections.sort(found);
        return found;
    }

    private static String kind(Before state) {
        return state.link() ? "a symbolic link"
                : (state.regular() ? "a regular file" : "an object of another kind");
    }

    private static String kind(Expected intent) {
        return intent.link() ? "a symbolic link"
                : (intent.regular() ? "a regular file" : "an object of another kind");
    }

    /**
     * True when the destination held nothing at this name before the copy and holds nothing there now. That is
     * the pair of facts that lets a vanished input be reported as not transferred rather than as an unconfirmed
     * transfer: nothing arrived, and there was nothing there already for rsync to have replaced.
     *
     * <p>{@code false} here means either "something is at that name" or "this could not be established". A
     * caller that has to tell those apart asks {@link #heldBefore} and {@link #existsNow} directly.
     */
    boolean showsNothingAt(String rel) {
        return heldBefore(rel) == Existence.ABSENT && existsNow(rel) == Existence.ABSENT;
    }

    FileSyncEngine.TransferDisposition disposition(String rel, boolean cleanCompletion, boolean dryRun) {
        if (dryRun) {
            return FileSyncEngine.TransferDisposition.WOULD_COPY;
        }
        if (cleanCompletion) {
            // The completion itself is the evidence: rsync ran this file and the job ended cleanly.
            return FileSyncEngine.TransferDisposition.COPIED;
        }
        Before previous = before.get(rel);
        Expected intent = expected.get(rel);
        if (previous == null || intent == null || intent == Expected.UNKNOWN) {
            return FileSyncEngine.TransferDisposition.UNCERTAIN;
        }
        Path target = destDir.resolve(rel);
        BasicFileAttributes now;
        try {
            now = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException cannotRead) {
            // Nothing there, or unreadable. A file rsync started and that cannot now be found is exactly
            // the case that must not be reported as copied.
            return FileSyncEngine.TransferDisposition.UNCERTAIN;
        }

        if (!previous.exists) {
            return createdFileIsComplete(rel, intent, now, target)
                ? FileSyncEngine.TransferDisposition.COPIED
                : FileSyncEngine.TransferDisposition.UNCERTAIN;
        }

        // Overwrite. rsync writes a temporary file and renames it, so a completed replacement is a
        // different file identity. Where the filesystem reports no usable identity, or the identity is
        // unchanged, the bytes on disk are the ones that were already there and nothing can be claimed.
        Object nowKey = now.fileKey();
        if (previous.fileKey != null && nowKey != null && !nowKey.equals(previous.fileKey)) {
            return FileSyncEngine.TransferDisposition.COPIED;
        }
        return FileSyncEngine.TransferDisposition.UNCERTAIN;
    }

    /**
     * A file the destination did not have before. The object must be the kind the source was, and a regular
     * file must be the size the source was - the size is what shows the whole file arrived rather than half
     * of it. A symlink is compared by its target text, never by opening it.
     */
    private static boolean createdFileIsComplete(String rel, Expected intent, BasicFileAttributes now,
                                                 Path target) {
        if (intent.link) {
            if (!now.isSymbolicLink()) {
                return false;
            }
            String text = readLinkText(target);
            return intent.linkText != null && intent.linkText.equals(text);
        }
        if (intent.regular) {
            // The size the source had when the job planned the copy, not a size read after the transfer:
            // a source that changed mid-job must not be matched against its own new size.
            return now.isRegularFile() && intent.size >= 0 && now.size() == intent.size;
        }
        // A source that was neither a regular file nor a link (a device, a FIFO, or a source that vanished
        // before the copy) has no size or content to compare against, so nothing may be claimed.
        return false;
    }
}
