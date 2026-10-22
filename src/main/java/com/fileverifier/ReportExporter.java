package com.fileverifier;

import java.util.List;

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
 * {@code verification} are appended. Names are written raw (an export is a record, not a display), and CSV
 * formula characters are neutralised in CSV only.
 *
 * <p>No dependencies: the project has none, and a job report does not need a JSON library.
 */
public final class ReportExporter {

    private ReportExporter() {
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
