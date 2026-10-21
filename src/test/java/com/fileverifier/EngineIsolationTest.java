package com.fileverifier;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two pieces of bookkeeping an engine should own: which rsync it runs, and which destination it holds. */
class EngineIsolationTest {

    private static final FileSyncEngine.ProgressListener SILENT = new FileSyncEngine.ProgressListener() {
        @Override
        public void onCompletion(FileSyncEngine.JobResult result) {
        }
    };

    @Test
    void anEngineCanRunItsOwnRsyncWithoutMovingTheWholeProcess(@org.junit.jupiter.api.io.TempDir Path tmp)
        throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path dst = Files.createDirectory(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a");

        String defaultBinary = FileSyncEngine.rsyncBinary();
        FileSyncEngine mine = new FileSyncEngine();
        FileSyncEngine someoneElse = new FileSyncEngine();
        try {
            mine.useRsync(tmp.resolve("definitely-not-an-rsync").toString());

            assertEquals(tmp.resolve("definitely-not-an-rsync").toString(), mine.rsyncBinaryInUse());
            assertEquals(defaultBinary, someoneElse.rsyncBinaryInUse(),
                "an override belongs to one engine, not to everything in the JVM");

            FileSyncEngine.JobRequest request = TestSupport.request(src, dst,
                FileSyncEngine.JobType.COPY_AND_VERIFY, TestSupport.selectAllOf(src),
                FileSyncEngine.Selection.none());

            List<String> problems = mine.preflight(request);
            assertTrue(problems.stream().anyMatch(p -> p.contains("definitely-not-an-rsync")),
                "preflight must check the binary this engine would actually run: " + problems);

            FileSyncEngine.JobResult result = mine.execute(request, SILENT);
            assertEquals(FileSyncEngine.JobStatus.FAILED, result.status());
            assertTrue(result.message().contains("definitely-not-an-rsync"), result.message());
        } finally {
            mine.close();
            someoneElse.close();
        }
    }

    @Test
    void oneJobAtATimePerDestination(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path first = Files.createDirectory(tmp.resolve("dst1"));
        Path second = Files.createDirectory(tmp.resolve("dst2"));
        TestSupport.write(src.resolve("a.txt"), "a");

        String previous = FileSyncEngine.rsyncBinary();
        FileSyncEngine busy = new FileSyncEngine();
        try {
            // A stand-in rsync that announces one file and then stays running for 20 seconds.
            busy.useRsync(TestSupport.hangingFakeRsync(tmp, ">f+++++++++ a.txt\n", "a.txt.AAAAAA").toString());
            FileSyncEngine.JobRequest toFirst = TestSupport.request(src, first,
                FileSyncEngine.JobType.COPY_AND_VERIFY, TestSupport.selectAllOf(src),
                FileSyncEngine.Selection.none());
            assertTrue(busy.start(toFirst, SILENT));
            Thread.sleep(400);

            FileSyncEngine.JobResult clash = FileSyncEngine.runOnce(toFirst, SILENT);
            assertEquals(FileSyncEngine.JobStatus.FAILED, clash.status(),
                "a second job on the same destination must not run alongside the first");
            assertTrue(clash.message().contains("already using the destination"), clash.message());
            assertTrue(clash.message().contains("vouch for"), clash.message());

            // The guard is per destination: an unrelated directory is nobody else's business.
            FileSyncEngine.JobResult elsewhere = FileSyncEngine.runOnce(
                TestSupport.request(src, second, FileSyncEngine.JobType.COPY_AND_VERIFY,
                    TestSupport.selectAllOf(src), FileSyncEngine.Selection.none()), SILENT);
            assertEquals(FileSyncEngine.JobStatus.COMPLETED, elsewhere.status(), elsewhere.message());
            assertEquals(1, elsewhere.filesCopied());

            busy.cancel();
            for (int i = 0; i < 100 && busy.isRunning(); i++) {
                Thread.sleep(50);
            }
            assertEquals(false, busy.isRunning());

            FileSyncEngine.JobResult after = FileSyncEngine.runOnce(toFirst, SILENT);
            assertEquals(FileSyncEngine.JobStatus.COMPLETED, after.status(),
                "the destination has to be free again once the job ends: " + after.message());
        } finally {
            busy.close();
            FileSyncEngine.setRsyncBinary(previous);
        }
    }
}
