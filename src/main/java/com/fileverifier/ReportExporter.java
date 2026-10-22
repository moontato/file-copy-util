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
     * <p>The write itself is a unique temporary file in the target's directory, then a move without
     * replacement. A failed write leaves no half-written report and no temporary file behind. The window
     * between the check and the move is closed for this program's own writes and open to another process -
     * the same limit as everywhere else here, since no cross-process lock is held.
     *
     * @throws ReportRefusal the target was refused, with the reason
     * @throws IOException  the write failed; nothing partial is left behind
     */
    public static Path writeReport(Path target, FileSyncEngine.JobResult result, Format format,
            Path sourceDir, Path destDir, boolean allowInsideDataTrees) throws IOException {
        ReportTarget where = validatedReportPath(target, sourceDir, destDir, allowInsideDataTrees);
        String content = format == Format.CSV ? toCsv(result) : toJson(result);
        publish(temporarilyOwnedReport(where.parent(), content), where.absolute(), format, () -> { });
        return where.absolute();
    }

    /**
     * The same rules and the same publication, with a hook that runs after every check and immediately
     * before the report is published. It exists so the race the publication has to survive can be created on
     * purpose in a test; production calls pass nothing.
     */
    static Path writeReportForTesting(Path target, FileSyncEngine.JobResult result, Format format,
            Path sourceDir, Path destDir, boolean allowInsideDataTrees, Runnable justBeforePublishing)
            throws IOException {
        ReportTarget where = validatedReportPath(target, sourceDir, destDir, allowInsideDataTrees);
        String content = format == Format.CSV ? toCsv(result) : toJson(result);
        publish(temporarilyOwnedReport(where.parent(), content), where.absolute(), format,
            justBeforePublishing);
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
     * Publish the prepared report without replacing anything.
     *
     * <p>Omitting {@code REPLACE_EXISTING} from a move is not the guarantee it looks like: Java documents an
     * atomic move's behaviour when the target exists as implementation-dependent, and OpenJDK on Unix reaches
     * the syscall directly, where a rename replaces an existing target. So the guarantee is taken from
     * something that actually provides it - link creation, which fails with {@code FileAlreadyExistsException}
     * if the name is taken:
     *
     * <ol>
     *   <li>link the prepared file to the report name, then unlink the temporary. The published report is
     *       the fully written file, and a name that was taken in the meantime is refused rather than
     *       replaced.</li>
     *   <li>where hard links do not exist (FAT and exFAT volumes among them), create the report name with
     *       {@link Files#createFile}, which is exclusive for the same reason, copy the content into the file
     *       this call just created, and delete only what this call created.</li>
     * </ol>
     *
     * <p>If neither is available the report is refused. A program that cannot promise not to replace a file
     * should not write one over a possible file.
     */
    private static void publish(PreparedReport prepared, Path absolute, Format format,
            Runnable justBeforePublishing) throws IOException {
        justBeforePublishing.run();
        Path temp = prepared.temp();
        try {
            Files.createLink(absolute, temp);
        } catch (FileAlreadyExistsException taken) {
            deleteQuietly(temp);
            throw new ReportRefusal("A file appeared at the report path while the report was being prepared:"
                    + " " + absolute + ". It was left exactly as it was and the report was not written."
                    + " Choose a path that is not in use.");
        } catch (UnsupportedOperationException | IOException hardLinkImpossible) {
            publishWithoutHardLinks(prepared, absolute, format, hardLinkImpossible);
            return;
        }
        try {
            Files.delete(temp);
        } catch (IOException leftover) {
            // The report is published; the temporary file is ours to remove and this is the honest thing to
            // say when we could not.
            System.err.println("the report was written to " + absolute + " but this program could not remove"
                    + " its temporary file " + temp + ": " + leftover.getMessage());
        }
    }

    private static void publishWithoutHardLinks(PreparedReport prepared, Path absolute, Format format,
            Exception why) throws IOException {
        Path temp = prepared.temp();
        Path created = null;
        try {
            created = Files.createFile(absolute);
        } catch (FileAlreadyExistsException taken) {
            deleteQuietly(temp);
            throw new ReportRefusal("A file appeared at the report path while the report was being prepared:"
                    + " " + absolute + ". It was left exactly as it was and the report was not written."
                    + " Choose a path that is not in use.");
        } catch (IOException cannotCreate) {
            deleteQuietly(temp);
            throw new IOException("Could not write the " + format + " report to " + absolute + ": "
                    + cannotCreate.getMessage() + " (hard links unavailable: " + why.getMessage() + ")",
                    cannotCreate);
        }
        try {
            Files.copy(temp, created, StandardCopyOption.REPLACE_EXISTING);
            deleteQuietly(temp);
        } catch (IOException failure) {
            // Only the file this call created a moment ago, never whatever else might have been there.
            deleteQuietly(created);
            deleteQuietly(temp);
            throw new IOException("Could not write the " + format + " report to " + absolute + ": "
                    + failure.getMessage(), failure);
        }
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
        StringBuilder csv = new StringBuilder(
                "status,path,source_sha256,destination_sha256,transfer,verification\n");
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
        json.append("  \"totals\": {\"checked\": ").append(result.filesChecked())
                .append(", \"copied\": ").append(result.filesCopied())
                .append(", \"matched\": ").append(result.filesMatched())
                .append(", \"mismatched\": ").append(result.filesMismatched())
                .append(", \"missing\": ").append(result.filesMissing())
                .append(", \"extra\": ").append(result.filesExtra()).append("},\n");
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
