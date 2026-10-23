package com.fileverifier;

import static com.fileverifier.FileSyncEngine.JobType.COPY_AND_VERIFY;
import static com.fileverifier.FileSyncEngine.JobType.COPY_ONLY;
import static com.fileverifier.FileSyncEngine.JobType.VERIFY_ONLY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fileverifier.FileSyncEngine.JobResult;
import com.fileverifier.FileSyncEngine.TransferDisposition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the headline figures mean. Each one has to be a tally of the rows it is reported beside, from the field it
 * is about: verdict figures from verdicts, transfer figures from transfer dispositions. An extra file in the
 * destination was reported, not compared, so it belongs in neither "checked" nor a verdict. And a number that
 * counts what rsync started is never presented as a number that finished.
 */
class CounterTruthTest {

    @TempDir
    Path tmp;

    private static void assumeRealRsync() {
        assumeTrue(TestSupport.rsyncAvailable(), "needs a real rsync");
    }

    private static FileSyncEngine.JobRequest request(Path src, Path dst, FileSyncEngine.JobType type,
                                                     String... names) throws IOException {
        Files.createDirectories(src);
        Files.createDirectories(dst);
        // Destination-only files are looked for in every fixture here: the point of the first one
        // is that an extra is reported without being counted as a check.
        return TestSupport.request(src, dst, type, TestSupport.selection(names),
                TestSupport.selection(), ScanOptions.DEFAULTS, true);
    }

    private static long tally(JobResult result, TransferDisposition disposition) {
        return result.outcomes().stream().filter(o -> o.transfer() == disposition).count();
    }

    @Test
    void anExtraFileInTheDestinationIsReportedAndNotChecked() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        TestSupport.write(dst.resolve("a.txt"), "a\n");
        TestSupport.write(dst.resolve("stray.txt"), "not selected, not compared\n");

        FileSyncEngine engine = new FileSyncEngine();
        try {
            JobResult result = engine.execute(request(src, dst, VERIFY_ONLY, "a.txt"), null);
            assertEquals(1, result.filesExtra(), result.message());
            assertEquals(1, result.filesMatched(), result.message());
            assertEquals(1, result.filesChecked(),
                    "one file was compared; the extra was reported, not compared: " + result.message());
            assertEquals(result.filesMatched() + result.filesMismatched() + result.filesMissing(),
                    result.filesChecked(), "checked is the verdict tally and nothing else: " + result);
            assertEquals(2, result.filesProcessed(),
                    "the progress count is the one that includes the extra row: " + result);
            assertNotEquals(result.filesProcessed(), result.filesChecked(), result.toString());
        } finally {
            engine.close();
        }
    }

    @Test
    void eachHeadlineFigureIsTheTallyOfTheRowsItIsReportedBeside() throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        TestSupport.write(src.resolve("b.txt"), "b\n");

        FileSyncEngine engine = new FileSyncEngine();
        try {
            JobResult result = engine.execute(request(src, dst, COPY_AND_VERIFY, "a.txt", "b.txt"), null);
            assertTrue(result.isSuccess(), result.message());
            assertEquals(tally(result, TransferDisposition.COPIED), result.filesCopied(),
                    "the copied figure is the count of rows this job can vouch for: " + result);
            assertEquals(2, result.filesCopied(), result.toString());
            assertEquals(0, result.filesUncertain(), result.toString());
            assertEquals(0, result.filesWouldCopy(), "nothing was a dry-run would-copy: " + result);
            assertEquals(2, result.filesChecked(), result.toString());
            assertEquals(2, result.filesMatched(), result.toString());
            // The file was both written and compared: it contributes to both tallies, and to neither twice.
            // Each of the two files carries one transfer fact and one verdict, so both tallies
            // count 2, and neither file is counted twice inside either tally.
            assertEquals(2, tally(result, TransferDisposition.COPIED), result.outcomes().toString());
            assertEquals(2, (int) result.outcomes().stream().filter(o -> "OK".equals(o.kind())).count(),
                    "each copied file is also a verdict row, so it counts once in each tally");
        } finally {
            engine.close();
        }
    }

    @Test
    void aDryRunCountsWouldCopyAndNeverCopied() throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        TestSupport.write(src.resolve("b.txt"), "b\n");

        FileSyncEngine engine = new FileSyncEngine();
        try {
            assertTrue(engine.start(TestSupport.request(src, dst, COPY_ONLY,
                    TestSupport.selection("a.txt", "b.txt"), TestSupport.selection(),
                    ScanOptions.DEFAULTS, false, true), null));
            JobResult result = awaitResult(engine);
            assertEquals(0, result.filesCopied(), "a dry run wrote nothing: " + result.message());
            assertEquals(2, result.filesWouldCopy(), result.message());
            assertEquals(tally(result, TransferDisposition.WOULD_COPY), result.filesWouldCopy(), result.toString());
            assertEquals(0, result.filesUncertain(), result.toString());
            assertFalse(Files.exists(dst.resolve("a.txt")));
        } finally {
            engine.close();
        }
    }

    @Test
    void aTransferThatStartedButCannotBeConfirmedIsUncertainNotCopied() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Path marker = dst.resolve("inflight.marker");
        TestSupport.write(src.resolve("big.bin"), "payload\n");
        Path fake = TestSupport.hangingFakeRsync(Files.createDirectories(tmp.resolve("fake")),
                ">f+++++++++|big.bin", "inflight.marker");

        FileSyncEngine engine = new FileSyncEngine();
        try {
            engine.useRsync(fake.toString());
            assertTrue(engine.start(request(src, dst, COPY_ONLY, "big.bin"), null));
            // Wait until the stand-in has announced the transfer and touched its marker: that is the moment
            // where the transfer has started and provably has not finished.
            assertTrue(TestSupport.waitFor(() -> Files.exists(marker), 30_000),
                    "the stand-in never got as far as the transfer");
            engine.cancel();
            JobResult result = awaitResult(engine);

            assertTrue(result.transfersAnnounced() >= 1,
                    "rsync did announce the file; that fact stays visible as an announcement: " + result);
            assertEquals(0, result.filesCopied(),
                    "an unconfirmed transfer is not a copy this job can vouch for: " + result.message());
            assertEquals(1, result.filesUncertain(), result.message());
            assertEquals(0, result.filesWouldCopy(), result.message());
        } finally {
            engine.close();
        }
    }

    @Test
    void aCappedResultListStillReportsTheFiguresForTheWholeJob(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        TestSupport.write(src.resolve("b.txt"), "b\n");
        TestSupport.write(src.resolve("c.txt"), "c\n");

        FileSyncEngine engine = new FileSyncEngine();
        engine.setResultLimit(1);
        try {
            JobResult result = engine.execute(request(src, dst, COPY_ONLY, "a.txt", "b.txt", "c.txt"), null);
            assertTrue(result.isSuccess(), result.message());
            // The list was capped; the figures were not. A reader who counts rows sees one and is told the
            // list is short, and the totals still describe all three files.
            assertEquals(1, result.outcomes().size(), result.outcomes().toString());
            assertTrue(result.truncated(), result.toString());
            assertEquals(3, result.filesCopied(), result.toString());
            assertEquals(3, result.transfersAnnounced(), result.toString());
            assertEquals(0, result.filesUncertain(), result.toString());
        } finally {
            engine.close();
        }
    }

    @Test
    void theJsonReportCarriesTheSameFiguresAsTheResultItDescribes() throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        TestSupport.write(dst.resolve("stray.txt"), "extra\n");

        FileSyncEngine engine = new FileSyncEngine();
        try {
            JobResult result = engine.execute(request(src, dst, COPY_ONLY, "a.txt"), null);
            Path json = tmp.resolve("run.json");
            ReportExporter.writeReport(json, result, ReportExporter.Format.JSON, src, dst, false);
            String text = Files.readString(json);
            for (String field : List.of("\"checked\"", "\"matched\"", "\"mismatched\"", "\"missing\"",
                    "\"extra\"", "\"copied\"", "\"wouldCopy\"", "\"uncertain\"", "\"notTransferred\"",
                    "\"processed\"", "\"transfersAnnounced\"", "\"truncated\"", "\"errors\"", "\"status\"")) {
                assertTrue(text.contains(field), "the JSON omits " + field + ": " + text);
            }
            assertTrue(text.contains("\"checked\": " + result.filesChecked()), text);
            assertTrue(text.contains("\"uncertain\": " + result.filesUncertain()), text);
        } finally {
            engine.close();
        }
    }

    private static JobResult awaitResult(FileSyncEngine engine) throws InterruptedException {
        long until = System.currentTimeMillis() + 30_000L;
        while (engine.isRunning() && System.currentTimeMillis() < until) {
            Thread.sleep(50L);
        }
        JobResult result = engine.lastResult();
        assertTrue(result != null, "the job never reported a result");
        return result;
    }

    private static void assertFalse(boolean condition) {
        org.junit.jupiter.api.Assertions.assertFalse(condition);
    }
}
