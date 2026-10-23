package com.fileverifier;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertAll;
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

    /**
     * A report that is part of a job has to say so, with numbers: which job it came from, how that job ended,
     * how many rows are here and how many are missing, and the counters that describe the whole job. Four rows
     * with no such header read as the whole story.
     */
    @Test
    void theCsvNamesTheJobItDescribesAndSaysWhetherItsRowsAreTheWholeJob() {
        String csv = ReportExporter.toCsv(cutShortSample());
        assertTrue(csv.contains("# status,FAILED"), csv);
        assertTrue(csv.contains("# abortCause,USER_CANCELLED"), csv);
        assertTrue(csv.contains("# truncated,true"), csv);
        assertTrue(csv.contains("# rowsListed,1"), csv);
        assertTrue(csv.contains("# rowsOmitted,4"), csv);
        assertTrue(csv.contains("# checked,1"), csv);
        assertTrue(csv.contains("# mismatched,1"), csv);
        assertTrue(csv.contains("# processed,1"), csv);
        assertTrue(csv.contains("# uncertain,1"), csv);
        assertTrue(csv.contains("# transfersAnnounced,1"), csv);
        assertTrue(csv.contains("# transfersCompletedByRsync,1"), csv);
        assertTrue(csv.contains("# rsyncExitCode,20"), csv);
        assertTrue(csv.contains("# errors,1"), csv);
        assertTrue(csv.contains("# warnings,1"), csv);
        assertTrue(csv.contains("\nstatus,path,source_sha256,destination_sha256,transfer,verification\n"), csv);
        assertTrue(csv.indexOf("status,path,") > csv.indexOf("# status,FAILED"),
                "the reader meets the job before its rows: " + csv);
        assertTrue(csv.contains("\"PENDING\",\"half.txt\""), csv);
    }

    @Test
    void aReportThatHoldsEveryRowSaysSo() {
        String csv = ReportExporter.toCsv(sample());
        assertTrue(csv.contains("# status,COMPLETED"), csv);
        assertTrue(csv.contains("# truncated,false"), csv);
        assertTrue(csv.contains("# rowsListed,4"), csv);
        assertTrue(csv.contains("# rowsOmitted,0"), csv);
        assertTrue(csv.contains("# errors,1"), csv);
    }

    @Test
    void theJsonSaysWhenItsRowsAreOnlyPartOfTheJob() {
        String json = ReportExporter.toJson(cutShortSample());
        assertTrue(json.contains("\"truncated\": true"), json);
        assertTrue(json.contains("\"rowsListed\": 1"), json);
        assertTrue(json.contains("\"rowsOmitted\": 4"), json);
    }

    /**
     * The data rows of a CSV export - everything after the columns row. The metadata block before it is
     * asserted where it matters, and ignored by the fixtures that are about rows.
     */
    private static java.util.List<String> dataRows(String csv) {
        String[] lines = csv.split("\n");
        int header = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].equals("status,path,source_sha256,destination_sha256,transfer,verification")) {
                header = i;
            }
        }
        assertTrue(header >= 0, csv);
        return java.util.Arrays.asList(lines).subList(header + 1, lines.length);
    }

    private static FileSyncEngine.JobResult cutShortSample() {
        return new FileSyncEngine.JobResult(
                FileSyncEngine.JobStatus.FAILED,
                "Job cancelled by the user while copying: rsync announced 1 file(s).",
                0, 1, 0, 0, 0, 0, 1, 0, 1, 1, 1, 20,
                List.of(new FileSyncEngine.FileOutcome("half.txt", "PENDING", null, null,
                        FileSyncEngine.TransferDisposition.UNCERTAIN)),
                List.of("rsync: write failed on half.txt"),
                List.of("1 file(s) had a transfer that cannot be confirmed"),
                4, FileSyncEngine.AbortCause.USER_CANCELLED);
    }

    @Test
    void csvHasAHeaderAndOneRowPerOutcomeWithFullDigests() {
        String csv = ReportExporter.toCsv(sample());
        java.util.List<String> rows = dataRows(csv);

        assertEquals(4, rows.size(), csv);
        assertEquals("\"OK\",\"ok.txt\",\"aaaa1111bbbb2222\",\"aaaa1111bbbb2222\",\"COPIED\",\"compared\"",
                rows.get(0), "a copied-and-compared file exports both facts, in one row");
        assertEquals("\"MISSING\",\"gone.txt\",\"ffff7777eeee8888\",\"\",\"UNCERTAIN\",\"compared\"",
                rows.get(2), "a file with no destination copy exports an empty digest, not a fake one");
        assertTrue(csv.contains("\"EXTRA\",\"only/at/dest.txt\",\"\",\"dddd9999cccc0000\",\"NOT_COPIED\""),
                csv);
    }

    /**
     * A file whose name begins with a formula character is a spreadsheet attack surface: the person who opens
     * the CSV to check a copy gets a cell that evaluates. So the CSV disarms the leading character, and nothing
     * else does - the name on disk, the name in the table and the name in JSON are the name, untouched.
     */
    @Test
    void aNameThatASpreadsheetWouldEvaluateIsDisarmedInTheCsvAndStaysRawEverywhereElse() {
        FileSyncEngine.JobResult risky = new FileSyncEngine.JobResult(
                FileSyncEngine.JobStatus.COMPLETED, "=SUM(1,2) was the job's message",
                0, 0, 0, 0, 0, 0, 0,
                List.of(
                        new FileSyncEngine.FileOutcome("=cmd.txt", "OK", null, null,
                                FileSyncEngine.TransferDisposition.COPIED),
                        new FileSyncEngine.FileOutcome("+sum.txt", "OK", null, null,
                                FileSyncEngine.TransferDisposition.COPIED),
                        new FileSyncEngine.FileOutcome("-neg.txt", "OK", null, null,
                                FileSyncEngine.TransferDisposition.COPIED),
                        new FileSyncEngine.FileOutcome("@at.txt", "OK", null, null,
                                FileSyncEngine.TransferDisposition.COPIED),
                        new FileSyncEngine.FileOutcome("\ttab.txt", "OK", null, null,
                                FileSyncEngine.TransferDisposition.COPIED),
                        new FileSyncEngine.FileOutcome("mid=equals.txt", "OK", null, null,
                                FileSyncEngine.TransferDisposition.COPIED)),
                List.of(), List.of());

        String csv = ReportExporter.toCsv(risky);
        assertAll(
                () -> assertTrue(csv.contains("\"'=cmd.txt\""), csv),
                () -> assertTrue(csv.contains("\"'+sum.txt\""), csv),
                () -> assertTrue(csv.contains("\"'-neg.txt\""), csv),
                () -> assertTrue(csv.contains("\"'@at.txt\""), csv),
                () -> assertTrue(csv.contains("\"'\ttab.txt\""), csv),
                // A formula character that is not leading is ordinary text, and a name is not rewritten for
                // something that could not be evaluated.
                () -> assertTrue(csv.contains("\"mid=equals.txt\""), csv),
                // The job's own message goes through the same rule.
                () -> assertTrue(csv.contains("# message,\"'=SUM(1,2)"), csv),
                () -> assertFalse(csv.contains(",\"="),
                        "no CSV field may begin with an unquoted formula character: " + csv));

        String json = ReportExporter.toJson(risky);
        assertAll(
                () -> assertTrue(json.contains("\"path\": \"=cmd.txt\""), json),
                () -> assertTrue(json.contains("\"path\": \"@at.txt\""), json),
                () -> assertFalse(json.contains("'="),
                        "JSON is the record of the names as they are: " + json));
    }

    @Test
    void theCsvFieldRuleLeavesALoneFormulaCharacterAlone() {
        // Only a leading character can start an evaluation.
        assertAll(
                () -> assertEquals("\"'=1+1\"",
                        ReportExporter.csvField("=1+1")),
                () -> assertEquals("\"a=1\"",
                        ReportExporter.csvField("a=1")),
                () -> assertEquals("\"1=1\"",
                        ReportExporter.csvField("1=1")));
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
        java.util.List<String> rows = dataRows(csv);
        assertEquals("\"PENDING\",\"finished.bin\",\"\",\"\",\"COPIED\",\"pending\"", rows.get(0), csv);
        assertEquals("\"PENDING\",\"halfwritten.bin\",\"\",\"\",\"UNCERTAIN\",\"pending\"", rows.get(1), csv);
        assertTrue(csv.contains("# status,FAILED"), csv);

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

        String csv = ReportExporter.toCsv(empty);
        // A job that never got going still gets its header block - and no rows, which is the point.
        assertTrue(csv.contains("# status,FAILED"), csv);
        assertTrue(csv.contains("# errors,1"), csv);
        assertTrue(csv.contains("# rowsListed,0"), csv);
        assertTrue(csv.contains("# rowsOmitted,0"), csv);
        assertTrue(dataRows(csv).isEmpty(), csv);
        assertTrue(ReportExporter.toJson(empty).contains("\"results\": [\n  ]"), ReportExporter.toJson(empty));
    }
}
