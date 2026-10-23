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
                        new FileSyncEngine.FileOutcome("ok.txt", "OK", "aaaa1111bbbb2222", "aaaa1111bbbb2222",
                                FileSyncEngine.TransferDisposition.COPIED),
                        new FileSyncEngine.FileOutcome("diff.txt", "MISMATCH", "cccc3333dddd4444", "eeee5555ffff6666",
                                FileSyncEngine.TransferDisposition.COPIED),
                        new FileSyncEngine.FileOutcome("gone.txt", "MISSING", "ffff7777eeee8888", null,
                                FileSyncEngine.TransferDisposition.UNCERTAIN),
                        new FileSyncEngine.FileOutcome("only/at/dest.txt", "EXTRA", null, "dddd9999cccc0000",
                                FileSyncEngine.TransferDisposition.NOT_COPIED)),
                List.of("rsync error: something (code 23)"),
                List.of("some files vanished"));
    }

    @Test
    void csvHasAHeaderAndOneRowPerOutcomeWithFullDigests() {
        String csv = ReportExporter.toCsv(sample());
        String[] lines = csv.split("\n");

        assertEquals("status,path,source_sha256,destination_sha256,transfer,verification", lines[0]);
        assertEquals(5, lines.length, csv);
        assertEquals("\"OK\",\"ok.txt\",\"aaaa1111bbbb2222\",\"aaaa1111bbbb2222\",\"COPIED\",\"compared\"",
                lines[1], "a copied-and-compared file exports both facts, in one row");
        assertEquals("\"MISSING\",\"gone.txt\",\"ffff7777eeee8888\",\"\",\"UNCERTAIN\",\"compared\"",
                lines[3], "a file with no destination copy exports an empty digest, not a fake one");
        assertTrue(csv.contains("\"EXTRA\",\"only/at/dest.txt\",\"\",\"dddd9999cccc0000\",\"NOT_COPIED\""),
                csv);
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
        // Three verdicts and one extra: the extra was reported, not compared, so checked counts 3 while the
        // progress unit counts 4.
        assertTrue(json.contains("\"checked\": 3"), json);
        assertTrue(json.contains("\"processed\": 4"), json);
        assertTrue(json.contains("\"copied\": 2"), json);
        assertTrue(json.contains("\"transfersCompletedByRsync\": 2"), json);
        assertTrue(json.contains("\"extra\": 1"), json);
        assertTrue(json.contains("\"rsyncExitCode\": 0"), json);
        assertTrue(json.contains("\"errors\": [\"rsync error: something (code 23)\"]"), json);
        assertTrue(json.contains("\"warnings\": [\"some files vanished\"]"), json);
        assertTrue(json.contains("\"path\": \"only/at/dest.txt\""), json);
        assertTrue(json.contains("\"destinationSha256\": null"), json);
        assertTrue(json.contains("\"transfer\": \"COPIED\""), json);
        assertTrue(json.contains("\"verification\": \"compared\""), json);
        // Every outcome present, and the array closes the object.
        assertEquals(4, json.split("\"status\": \"(OK|MISMATCH|MISSING|EXTRA)\"", 0).length - 1, json);
        assertTrue(json.trim().endsWith("}"), json);
    }

    /**
     * The reason the ledger exists: a verifying job stopped before the comparison has two files that both
     * read PENDING, and one of them completed its transfer while the other is unknown. If the export cannot
     * tell them apart the distinction died inside the engine.
     */
    @Test
    void twoPendingRowsWithDifferentTransfersExportDifferently() {
        FileSyncEngine.JobResult stopped = new FileSyncEngine.JobResult(
                FileSyncEngine.JobStatus.FAILED,
                "Job cancelled by the user: rsync announced 2 file(s) for copy and completed 1 of them.",
                0, 0, 0, 0, 0, 1, 255,
                List.of(
                        new FileSyncEngine.FileOutcome("finished.bin", "PENDING", null, null,
                                FileSyncEngine.TransferDisposition.COPIED),
                        new FileSyncEngine.FileOutcome("halfwritten.bin", "PENDING", null, null,
                                FileSyncEngine.TransferDisposition.UNCERTAIN)),
                List.of(), List.of());

        String csv = ReportExporter.toCsv(stopped);
        String[] lines = csv.split("\n");
        assertEquals("\"PENDING\",\"finished.bin\",\"\",\"\",\"COPIED\",\"pending\"", lines[1], csv);
        assertEquals("\"PENDING\",\"halfwritten.bin\",\"\",\"\",\"UNCERTAIN\",\"pending\"", lines[2], csv);

        String json = ReportExporter.toJson(stopped);
        assertTrue(json.contains("\"status\": \"PENDING\", \"path\": \"finished.bin\", \"sourceSha256\": null,"
                + " \"destinationSha256\": null, \"transfer\": \"COPIED\", \"verification\": \"pending\""),
                json);
        assertTrue(json.contains("\"status\": \"PENDING\", \"path\": \"halfwritten.bin\", \"sourceSha256\": null,"
                + " \"destinationSha256\": null, \"transfer\": \"UNCERTAIN\", \"verification\": \"pending\""),
                json);
    }

    /** A job that compared nothing: the row answers the transfer question and says no check was done. */
    @Test
    void aCopyOnlyRowExportsItsTransferAndSaysNoComparisonWasMade() {
        FileSyncEngine.JobResult copyOnly = new FileSyncEngine.JobResult(
                FileSyncEngine.JobStatus.COMPLETED, "Copy complete: 2 file(s) copied.",
                0, 0, 0, 0, 0, 2, 0,
                List.of(
                        new FileSyncEngine.FileOutcome("a.bin", "COPIED", null, null,
                                FileSyncEngine.TransferDisposition.COPIED),
                        new FileSyncEngine.FileOutcome("b.bin", "WOULD_COPY", null, null,
                                FileSyncEngine.TransferDisposition.WOULD_COPY)),
                List.of(), List.of());

        String csv = ReportExporter.toCsv(copyOnly);
        assertTrue(csv.contains("\"COPIED\",\"a.bin\",\"\",\"\",\"COPIED\",\"not-applicable\""), csv);
        assertTrue(csv.contains("\"WOULD_COPY\",\"b.bin\",\"\",\"\",\"WOULD_COPY\",\"not-applicable\""), csv);
        String json = ReportExporter.toJson(copyOnly);
        assertTrue(json.contains("\"verification\": \"not-applicable\""), json);
    }

    @Test
    void anEmptyJobExportsAHeaderOnlyCsv() {
        FileSyncEngine.JobResult empty = new FileSyncEngine.JobResult(
                FileSyncEngine.JobStatus.FAILED, "Preflight failed: nothing selected.",
                0, 0, 0, 0, 0, 0, 0, List.of(), List.of("nothing selected"), List.of());

        assertEquals("status,path,source_sha256,destination_sha256,transfer,verification\n",
                ReportExporter.toCsv(empty));
        assertTrue(ReportExporter.toJson(empty).contains("\"results\": [\n  ]"), ReportExporter.toJson(empty));
    }
}
