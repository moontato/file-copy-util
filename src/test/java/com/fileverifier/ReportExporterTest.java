package com.fileverifier;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 3 export formats: the CSV must be readable by a spreadsheet, the JSON by a machine. */
class ReportExporterTest {

    private static FileSyncEngine.JobResult sample() {
        return new FileSyncEngine.JobResult(
                FileSyncEngine.JobStatus.COMPLETED,
                "Job complete: 1 matched, 1 mismatched, 1 missing, 2 copied, 1 destination-only.",
                4, 1, 1, 1, 1, 2, 0,
                List.of(
                        new FileSyncEngine.FileOutcome("ok.txt", "OK", "aaaa1111bbbb2222", "aaaa1111bbbb2222"),
                        new FileSyncEngine.FileOutcome("diff.txt", "MISMATCH", "cccc3333dddd4444", "eeee5555ffff6666"),
                        new FileSyncEngine.FileOutcome("gone.txt", "MISSING", "ffff7777eeee8888", null),
                        new FileSyncEngine.FileOutcome("only/at/dest.txt", "EXTRA", null, "dddd9999cccc0000")),
                List.of("rsync error: something (code 23)"),
                List.of("some files vanished"));
    }

    @Test
    void csvHasAHeaderAndOneRowPerOutcomeWithFullDigests() {
        String csv = ReportExporter.toCsv(sample());
        String[] lines = csv.split("\n");

        assertEquals("status,path,source_sha256,destination_sha256", lines[0]);
        assertEquals(5, lines.length, csv);
        assertEquals("\"OK\",\"ok.txt\",\"aaaa1111bbbb2222\",\"aaaa1111bbbb2222\"", lines[1]);
        assertEquals("\"MISSING\",\"gone.txt\",\"ffff7777eeee8888\",\"\"", lines[3],
                "a file with no destination copy exports an empty digest, not a fake one");
        assertTrue(csv.contains("\"EXTRA\",\"only/at/dest.txt\""), csv);
    }

    @Test
    void csvQuotesAreDoubledNotBackslashEscaped() {
        assertEquals("\"we\"\"ird.txt\"", ReportExporter.csvField("we\"ird.txt"));
        assertEquals("\"a,b\"", ReportExporter.csvField("a,b"));
        assertEquals("\"\"", ReportExporter.csvField(null));
        // A backslash is data in CSV, not an escape character.
        assertEquals("\"c:\\path\"", ReportExporter.csvField("c:\\path"));
    }

    @Test
    void jsonEscapesWhatJsonCannotCarryRaw() {
        assertEquals("\"a\\\"b\"", ReportExporter.field("a\"b"));
        assertEquals("\"a\\\\b\"", ReportExporter.field("a\\b"));
        assertEquals("\"a\\nb\"", ReportExporter.field("a\nb"));
        assertEquals("\"a\\u0001b\"", ReportExporter.field("a\u0001b"));
    }

    @Test
    void jsonCarriesTotalsOutcomesErrorsAndWarnings() {
        String json = ReportExporter.toJson(sample());

        assertTrue(json.contains("\"status\": \"COMPLETED\""), json);
        assertTrue(json.contains("\"checked\": 4"), json);
        assertTrue(json.contains("\"copied\": 2"), json);
        assertTrue(json.contains("\"extra\": 1"), json);
        assertTrue(json.contains("\"rsyncExitCode\": 0"), json);
        assertTrue(json.contains("\"errors\": [\"rsync error: something (code 23)\"]"), json);
        assertTrue(json.contains("\"warnings\": [\"some files vanished\"]"), json);
        assertTrue(json.contains("\"path\": \"only/at/dest.txt\""), json);
        assertTrue(json.contains("\"destinationSha256\": null"), json);
        // Every outcome present, and the array closes the object.
        assertEquals(4, json.split("\"status\": \"(OK|MISMATCH|MISSING|EXTRA)\"", 0).length - 1, json);
        assertTrue(json.trim().endsWith("}"), json);
    }

    @Test
    void anEmptyJobExportsAHeaderOnlyCsv() {
        FileSyncEngine.JobResult empty = new FileSyncEngine.JobResult(
                FileSyncEngine.JobStatus.FAILED, "Preflight failed: nothing selected.",
                0, 0, 0, 0, 0, 0, 0, List.of(), List.of("nothing selected"), List.of());

        assertEquals("status,path,source_sha256,destination_sha256\n", ReportExporter.toCsv(empty));
        assertTrue(ReportExporter.toJson(empty).contains("\"results\": [\n  ]"), ReportExporter.toJson(empty));
    }
}
