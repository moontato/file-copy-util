package com.fileverifier;

import java.util.List;

/**
 * Serialises a {@link FileSyncEngine.JobResult} for export (Phase 3). CSV is the flat results table
 * (one row per verified file, full digests); JSON additionally carries the totals, warnings, errors
 * and rsync exit code.
 *
 * <p>No dependencies: the project has none, and a job report does not need a JSON library.
 */
public final class ReportExporter {

    private ReportExporter() {
    }

    /** One row per verified file: status, path, full source digest, full destination digest. */
    public static String toCsv(FileSyncEngine.JobResult result) {
        StringBuilder csv = new StringBuilder("status,path,source_sha256,destination_sha256\n");
        for (FileSyncEngine.FileOutcome outcome : result.outcomes()) {
            csv.append(csvField(outcome.kind())).append(',')
                    .append(csvField(outcome.relPath())).append(',')
                    .append(csvField(outcome.sourceHash())).append(',')
                    .append(csvField(outcome.destHash())).append('\n');
        }
        return csv.toString();
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
