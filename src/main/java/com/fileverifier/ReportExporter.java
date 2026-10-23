package com.fileverifier;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Serialises a {@link FileSyncEngine.JobResult} for export. CSV is the flat results table (one row per
 * reported file, full digests); JSON additionally carries the totals, warnings, errors and rsync exit code.
 *
 * <p>Each row answers its two questions separately, because a job can know one and not the other:
 * <b>what happened to the bytes</b> ({@code transfer}: COPIED, WOULD_COPY, UNCERTAIN, NOT_COPIED) and
 * <b>whether the two ends were compared</b> ({@code verification}: compared, pending, not-applicable). A
 * verifying job stopped before the comparison would otherwise export the same word - PENDING - for a file
 * whose transfer completed and a file whose transfer is unknown, which is exactly the distinction the
 * ledger exists to keep.
 *
 * <p>Compatibility: the four columns JSON's {@code status}/{@code path}/{@code sourceSha256}/
 * {@code destinationSha256} have always carried are unchanged and keep their order; {@code transfer} and
 * {@code verification} are appended. Names are written raw (an export is a record, not a display).
 *
 * <p>CSV quoting is not spreadsheet safety. A field beginning with {@code =}, {@code +}, {@code -}, or
 * {@code @} is quoted here, which keeps a spreadsheet from treating it as a separator or a quote - but a
 * spreadsheet that evaluates quoted formulas would still evaluate it. Neutralising the leading character
 * itself is scheduled (plan &#167;2.13) and has not been done; do not describe this format as safe to open
 * in a spreadsheet until it is.
 *
 * <p>No dependencies: the project has none, and a job report does not need a JSON library.
 */
public final class ReportExporter {

    private ReportExporter() {
    }

    /** Which report format to write. */
    public enum Format {
        CSV, JSON
    }

    /**
     * A report that was refused before anything was written. It is an IOException with a reason a user can
     * act on, because the alternative - writing anyway - is the bug this class exists to prevent.
     */
    public static final class ReportRefusal extends IOException {
        private static final long serialVersionUID = 1L;

        ReportRefusal(String message) {
            super(message);
        }
    }

    /**
     * Write a report, refusing first when the target would displace something the job is protecting.
     *
     * <p>Two different decisions, kept apart on purpose:
     *
     * <ul>
     *   <li><b>Protected data is never overwritten, and no option waives that.</b> A report path that names
     *       an existing file - a source file, a destination file, anything - is refused. This program cannot
     *       tell a report it wrote earlier from a file the job is meant to protect, so it does not guess:
     *       a report path must not already exist.</li>
     *   <li><b>A report location inside the job's own trees is a separate question.</b> A new file there
     *       displaces nothing, but it lands among the files the job copies and verifies, so the next run
     *       sees it as an extra. That is the user's call, and only that: {@code allowInsideDataTrees}
     *       permits it, with a warning naming what it means. It never permits an overwrite.</li>
     * </ul>
     *
     * <p>The write itself is staged in the report's own directory and published by a hard link, which cannot
     * replace an existing name - see {@link #publish}. A failed write leaves no half-written report and no
     * temporary file of ours behind. The window between the check and the publication is closed for this
     * program's own writes and open to another process, which is why publication re-checks by failing rather
     * than by overwriting; the same limit as everywhere else here, since no cross-process lock is held.
     *
     * @throws ReportRefusal the target was refused, with the reason
     * @throws IOException  the write failed; nothing partial is left behind
     */
    public static Path writeReport(Path target, FileSyncEngine.JobResult result, Format format,
            Path sourceDir, Path destDir, boolean allowInsideDataTrees) throws IOException {
        return writeReportForTesting(target, result, format, sourceDir, destDir, allowInsideDataTrees,
            () -> { }, HARD_LINK_PUBLICATION);
    }

    /**
     * The same rules and the same publication, with a hook that runs after every check and immediately
     * before the report is published. It exists so the race the publication has to survive can be created on
     * purpose in a test; production calls pass nothing.
     */
    static Path writeReportForTesting(Path target, FileSyncEngine.JobResult result, Format format,
            Path sourceDir, Path destDir, boolean allowInsideDataTrees, Runnable justBeforePublishing)
            throws IOException {
        return writeReportForTesting(target, result, format, sourceDir, destDir, allowInsideDataTrees,
            justBeforePublishing, HARD_LINK_PUBLICATION);
    }

    /** As above, with the publication itself replaced, to cover a filesystem this machine cannot mount. */
    static Path writeReportForTesting(Path target, FileSyncEngine.JobResult result, Format format,
            Path sourceDir, Path destDir, boolean allowInsideDataTrees, Runnable justBeforePublishing,
            Publication publication) throws IOException {
        ReportTarget where = validatedReportPath(target, sourceDir, destDir, allowInsideDataTrees);
        String content = format == Format.CSV ? toCsv(result) : toJson(result);
        publish(temporarilyOwnedReport(where.parent(), content), where.absolute(), format,
            justBeforePublishing, () -> { }, publication);
        return where.absolute();
    }

    /** As above, with the moment-of-publication seam, over this filesystem's real hard link. */
    static Path writeReportForTesting(Path target, FileSyncEngine.JobResult result, Format format,
            Path sourceDir, Path destDir, boolean allowInsideDataTrees, Runnable justBeforePublishing,
            Runnable justAfterPublication) throws IOException {
        return writeReportForTesting(target, result, format, sourceDir, destDir, allowInsideDataTrees,
            justBeforePublishing, justAfterPublication, HARD_LINK_PUBLICATION);
    }

    /** As above, and with a seam that runs the moment the report name exists. */
    static Path writeReportForTesting(Path target, FileSyncEngine.JobResult result, Format format,
            Path sourceDir, Path destDir, boolean allowInsideDataTrees, Runnable justBeforePublishing,
            Runnable justAfterPublication, Publication publication) throws IOException {
        ReportTarget where = validatedReportPath(target, sourceDir, destDir, allowInsideDataTrees);
        String content = format == Format.CSV ? toCsv(result) : toJson(result);
        publish(temporarilyOwnedReport(where.parent(), content), where.absolute(), format,
            justBeforePublishing, justAfterPublication, publication);
        return where.absolute();
    }

    /**
     * The reason this report path would be refused, or {@code null} if nothing is wrong with it yet.
     *
     * <p>For a caller that has to know before it starts a job - the CLI refusing {@code --csv} and
     * {@code --json} that name the same file, for instance, before one of them has been written. It answers
     * the predictable part only: publication re-checks, because a file can still appear in between.
     */
    public static String checkReportTarget(Path target, Path sourceDir, Path destDir,
            boolean allowInsideDataTrees) {
        try {
            validatedReportPath(target, sourceDir, destDir, allowInsideDataTrees);
            return null;
        } catch (ReportRefusal refusal) {
            return refusal.getMessage();
        } catch (IOException other) {
            return other.getMessage();
        }
    }

    /** Every check that can be answered before a byte is written. */
    private static ReportTarget validatedReportPath(Path target, Path sourceDir, Path destDir,
            boolean allowInsideDataTrees) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new ReportRefusal("Report path has no directory to write it in: " + absolute);
        }
        if (!Files.isDirectory(parent)) {
            throw new ReportRefusal("The directory for the report does not exist: " + parent
                    + ". This program does not create directories for a report.");
        }
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new ReportRefusal("A file is already there: " + absolute + ". A report is never written"
                    + " over an existing file: this program cannot tell a report it wrote earlier from a"
                    + " file the job is protecting. Choose a path that does not exist yet.");
        }

        List<String> inside = new ArrayList<>();
        Path realSource = realParentOf(sourceDir);
        Path realDest = realParentOf(destDir);
        if (realSource != null && insideTree(absolute, realSource)) {
            inside.add("the source tree " + realSource);
        }
        if (realDest != null && insideTree(absolute, realDest)) {
            inside.add("the destination tree " + realDest);
        }
        if (!inside.isEmpty() && !allowInsideDataTrees) {
            throw new ReportRefusal("The report would be written inside " + String.join(" and ", inside)
                    + " - the directories this job copies and verifies. Choose a path outside them, or say"
                    + " you meant it (--allow-report-in-tree, or confirm in the window). Either way an"
                    + " existing file there is never overwritten.");
        }
        return new ReportTarget(absolute, parent);
    }

    /** The report path and the directory it will be written in, together, so neither is re-derived. */
    private record ReportTarget(Path absolute, Path parent) {
    }

    /**
     * The report content, written to a temporary file this call created and owns.
     *
     * <p>{@link Files#createTempFile} rather than a name built from the clock: it opens with O_EXCL, so two
     * runs in the same directory cannot end up sharing - or deleting each other's - temporary file.
     */
    private static PreparedReport temporarilyOwnedReport(Path parent, String content) throws IOException {
        Path temp = Files.createTempFile(parent, ".fcu-report-", ".tmp");
        try {
            Files.writeString(temp, content, java.nio.charset.StandardCharsets.UTF_8);
            return new PreparedReport(temp, true);
        } catch (IOException failure) {
            // Ours, created a moment ago: safe to remove, and only this call's.
            deleteQuietly(temp);
            throw failure;
        }
    }

    private record PreparedReport(Path temp, boolean owned) {
    }

    /**
     * How the prepared file becomes the report. The default is a hard link; the seam exists so the case a
     * test cannot produce on its own filesystem - one without hard links - can still be tested.
     */
    interface Publication {
        void publish(Path prepared, Path reportPath) throws IOException;
    }

    private static final Publication HARD_LINK_PUBLICATION = (prepared, reportPath) ->
        Files.createLink(reportPath, prepared);

    /**
     * Publish the prepared report without replacing anything.
     *
     * <p>Omitting {@code REPLACE_EXISTING} from a move is not the guarantee it looks like. Java documents an
     * atomic move's behaviour when the target exists as implementation-dependent, and it was measured here:
     * OpenJDK 17's {@code UnixCopyFile.move} takes its atomic branch first (line 406 {@code if
     * (flags.atomicMove)}, line 408 {@code rename(source, target)}, then {@code return}), so the
     * no-replacement check further down is never reached - and {@code Files.move(temp, existing, ATOMIC_MOVE)}
     * against an existing file succeeded, leaving the target holding the temporary file's bytes.
     *
     * <p>So the guarantee is taken from something that cannot provide anything else: {@code link(2)}. It fails
     * with {@link FileAlreadyExistsException} when the name is taken, and the name it creates points at a file
     * that is already complete - the report is never visible half-written. The temporary file is staged in the
     * report's <i>own</i> directory, because a hard link cannot cross a filesystem; it is made by
     * {@link Files#createTempFile} (open with {@code O_EXCL}), so it is exclusively ours and only ours is ever
     * deleted. If this process is killed between staging and publishing, one
     * {@code .fcu-report-*.tmp} is left in that directory; that is the visible cost of this choice, and it is
     * smaller than the alternative of replacing a file.
     *
     * <p>When hard links are unavailable - FAT, exFAT, and some network and FUSE mounts - the report is
     * <b>refused</b>. The fallback that was here first (create the name exclusively, then copy the content into
     * it) is gone: {@code Files.copy} with {@code REPLACE_EXISTING} deletes and replaces whatever the name
     * points at by then, so a writer that moved in between those two calls would have had its file overwritten
     * through a path we had just created; and the name would have existed, empty or half-written, while the
     * copy ran. This program's rule is to refuse when it cannot provide the guarantee rather than to provide a
     * weaker one quietly.
     *
     * @param justBeforePublishing      runs after staging and before the name is created (test seam for the
     *                                  interval in which another writer can move in)
     * @param justAfterPublication      runs the moment the report name exists, while the temporary is still
     *                                  ours to remove (test seam proving the name appears holding the whole
     *                                  report)
     */
    private static void publish(PreparedReport prepared, Path absolute, Format format,
            Runnable justBeforePublishing, Runnable justAfterPublication, Publication publication)
            throws IOException {
        justBeforePublishing.run();
        Path temp = prepared.temp();
        boolean published = false;
        try {
            publication.publish(temp, absolute);
            published = true;
        } catch (FileAlreadyExistsException taken) {
            throw new ReportRefusal("A file appeared at the report path while the report was being prepared:"
                    + " " + absolute + ". It was left exactly as it was and the report was not written."
                    + " Choose a path that is not in use.");
        } catch (UnsupportedOperationException | IOException noLinks) {
            throw new ReportRefusal("This filesystem cannot publish a report without the possibility of"
                    + " replacing a file that is already there (" + noLinks.getClass().getSimpleName() + ": "
                    + noLinks.getMessage() + "). The report was not written. FAT, exFAT and some network or"
                    + " FUSE mounts are like this; choose a path on a filesystem that supports hard links, or"
                    + " run the job without a report.");
        } finally {
            if (!published) {
                // Staged by this call a moment ago under an exclusive name: ours to remove, and only ours.
                deleteQuietly(temp);
            }
        }

        justAfterPublication.run();

        try {
            Files.delete(temp);
        } catch (IOException leftover) {
            // The report is published; the temporary file is ours to remove and this is the honest thing to
            // say when we could not.
            System.err.println("the report was written to " + absolute + " but this program could not remove"
                    + " its temporary file " + temp + ": " + leftover.getMessage());
        }
    }

    /**
     * Where this path would actually be written: the directory it sits in, resolved through any linked
     * directory, plus the name itself. Two spellings that mean one file - {@code reports/real/result} and
     * {@code reports/alias/result} with {@code alias} a link to {@code real} - have one answer here, and each
     * spelling on its own passes the "does this path already exist" check while the target is absent.
     */
    static Path publicationName(Path target) {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Path name = absolute.getFileName();
        if (parent == null || name == null) {
            return absolute;
        }
        return FileSyncEngine.resolveAsFarAsPossible(parent).resolve(name);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException couldNot) {
            System.err.println("could not remove " + path + " after a failed report write: "
                    + couldNot.getMessage());
        }
    }

    private static Path realParentOf(Path directory) {
        try {
            return directory == null ? null : directory.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Would this file land inside that tree? The name is resolved as far as the filesystem allows first:
     * a report path that spells itself as inside through a linked directory is not inside anything.
     */
    private static boolean insideTree(Path file, Path treeRoot) {
        Path parent = file.getParent();
        return parent != null && FileSyncEngine.resolveAsFarAsPossible(parent).startsWith(treeRoot);
    }

    /**
     * One row per reported file: verdict, path, both full digests, then the transfer answer and the
     * verification state. The new columns are last so anything already reading the first four still works.
     */
    public static String toCsv(FileSyncEngine.JobResult result) {
        StringBuilder csv = new StringBuilder();
        // The block before the columns answers "which job is this, how did it end, and am I looking at all of
        // it". Rows without it read as a complete record, which a capped list or a stopped job is not. Each line
        // is a '#' comment holding one key and its value, so a reader can skip the block or read it, and the
        // columns row still arrives in the shape a spreadsheet expects.
        csv.append("# file-copy-util report\n");
        csv.append("# status,").append(result.status().name()).append('\n');
        csv.append("# abortCause,").append(result.abortCause().name()).append('\n');
        csv.append("# message,").append(csvField(result.message())).append('\n');
        csv.append("# truncated,").append(result.truncated() ? "true" : "false").append('\n');
        csv.append("# rowsListed,").append(result.rowsListed()).append('\n');
        csv.append("# rowsOmitted,").append(result.rowsOmitted()).append('\n');
        csv.append("# checked,").append(result.filesChecked()).append('\n');
        csv.append("# matched,").append(result.filesMatched()).append('\n');
        csv.append("# mismatched,").append(result.filesMismatched()).append('\n');
        csv.append("# missing,").append(result.filesMissing()).append('\n');
        csv.append("# extra,").append(result.filesExtra()).append('\n');
        csv.append("# processed,").append(result.filesProcessed()).append('\n');
        csv.append("# copied,").append(result.filesCopied()).append('\n');
        csv.append("# wouldCopy,").append(result.filesWouldCopy()).append('\n');
        csv.append("# uncertain,").append(result.filesUncertain()).append('\n');
        csv.append("# notTransferred,").append(result.filesNotTransferred()).append('\n');
        csv.append("# transfersAnnounced,").append(result.transfersAnnounced()).append('\n');
        csv.append("# transfersCompletedByRsync,").append(result.transfersCompletedByRsync()).append('\n');
        csv.append("# rsyncExitCode,").append(result.rsyncExitCode()).append('\n');
        csv.append("# errors,").append(result.errors().size()).append('\n');
        csv.append("# warnings,").append(result.warnings().size()).append('\n');
        csv.append("status,path,source_sha256,destination_sha256,transfer,verification\n");
        for (FileSyncEngine.FileOutcome outcome : result.outcomes()) {
            csv.append(csvField(outcome.kind())).append(',')
                    .append(csvField(outcome.relPath())).append(',')
                    .append(csvField(outcome.sourceHash())).append(',')
                    .append(csvField(outcome.destHash())).append(',')
                    .append(csvField(transferOf(outcome))).append(',')
                    .append(csvField(verificationOf(outcome))).append('\n');
        }
        return csv.toString();
    }

    /** What the job did to this file's bytes, or blank when the job had no transfer to report. */
    static String transferOf(FileSyncEngine.FileOutcome outcome) {
        return outcome.transfer() == null ? "" : outcome.transfer().name();
    }

    /**
     * Whether this row's verdict comes from an actual comparison.
     *
     * <p>{@code pending} means the job was going to compare this file and never got to it; a person reading
     * the export must not take a pending row for a passed one, and must not take it for a failed one either.
     */
    static String verificationOf(FileSyncEngine.FileOutcome outcome) {
        return switch (outcome.kind()) {
            case "OK", "MISMATCH", "MISSING", "EXTRA" -> "compared";
            case "PENDING" -> "pending";
            default -> "not-applicable";
        };
    }

    public static String toJson(FileSyncEngine.JobResult result) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"status\": ").append(field(result.status().name())).append(",\n");
        json.append("  \"message\": ").append(field(result.message())).append(",\n");
        // Whether this list is the whole list, and by how much it falls short: a capped report has to say so,
        // or 40 rows read as the lot.
        json.append("  \"truncated\": ").append(result.truncated() ? "true" : "false")
                .append(", \"rowsListed\": ").append(result.rowsListed())
                .append(", \"rowsOmitted\": ").append(result.rowsOmitted()).append(",\n");
        // Every figure here is the tally of the rows it is reported beside. "checked" counts verdicts, so an
        // extra file - reported, but not compared against anything - is not one of them. The transfer figures
        // are kept apart from the verdict figures because they answer different questions, and the count of
        // transfers rsync announced is kept apart from both: an announcement is not a completion.
        json.append("  \"totals\": {\"checked\": ").append(result.filesChecked())
                .append(", \"matched\": ").append(result.filesMatched())
                .append(", \"mismatched\": ").append(result.filesMismatched())
                .append(", \"missing\": ").append(result.filesMissing())
                .append(", \"extra\": ").append(result.filesExtra())
                .append(", \"copied\": ").append(result.filesCopied())
                .append(", \"wouldCopy\": ").append(result.filesWouldCopy())
                .append(", \"uncertain\": ").append(result.filesUncertain())
                .append(", \"notTransferred\": ").append(result.filesNotTransferred())
                .append(", \"transfersAnnounced\": ").append(result.transfersAnnounced())
                .append(", \"transfersCompletedByRsync\": ").append(result.transfersCompletedByRsync())
                .append(", \"processed\": ").append(result.filesProcessed())
                .append("},\n");
        json.append("  \"rsyncExitCode\": ").append(result.rsyncExitCode()).append(",\n");
        json.append("  \"errors\": ").append(array(result.errors())).append(",\n");
        json.append("  \"warnings\": ").append(array(result.warnings())).append(",\n");
        json.append("  \"results\": [\n");
        List<FileSyncEngine.FileOutcome> outcomes = result.outcomes();
        for (int i = 0; i < outcomes.size(); i++) {
            FileSyncEngine.FileOutcome outcome = outcomes.get(i);
            json.append("    {\"status\": ").append(field(outcome.kind()))
                    .append(", \"path\": ").append(field(outcome.relPath()))
                    .append(", \"sourceSha256\": ").append(nullableField(outcome.sourceHash()))
                    .append(", \"destinationSha256\": ").append(nullableField(outcome.destHash()))
                    .append(", \"transfer\": ").append(nullableField(transferOf(outcome)))
                    .append(", \"verification\": ").append(field(verificationOf(outcome)))
                    .append('}');
            if (i + 1 < outcomes.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  ]\n}\n");
        return json.toString();
    }

    private static String array(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            builder.append(field(values.get(i)));
            if (i + 1 < values.size()) {
                builder.append(", ");
            }
        }
        return builder.append(']').toString();
    }

    /** Quoted string for JSON, null becoming an empty string. */
    static String field(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** JSON value for a digest that may genuinely not exist: null, not an empty string. */
    static String nullableField(String value) {
        return value == null ? "null" : field(value);
    }

    /** CSV field: RFC 4180 quoting (a quote is doubled), which is not the same as JSON escaping. */
    static String csvField(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
