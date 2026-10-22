package com.fileverifier;

import com.fileverifier.FileSyncEngine.JobResult;
import com.fileverifier.FileSyncEngine.JobStatus;
import com.fileverifier.FileSyncEngine.JobType;
import com.fileverifier.FileSyncEngine.Selection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.fileverifier.TestSupport.digests;
import static com.fileverifier.TestSupport.assertUnchanged;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four write-safety rules from the audit, each with a fixture that would have destroyed data before
 * the fix: a job pointed at its own source, a path that climbs out of the destination, a report written
 * over the files it describes, and two names that are one file on the destination volume.
 *
 * <p>Every refusal here is checked the same way: the job fails, the message names the problem, and the
 * filesystem is <b>byte for byte what it was before the run</b>. "It failed" is not enough - a job that
 * fails after half-overwriting the source is worse than one that never started.
 */
class WriteSafetyTest {

    @TempDir
    Path root;

    private final TestSupport.Recorder rec = new TestSupport.Recorder();

    private JobResult run(JobType type, Path source, Path dest, Selection selection) throws Exception {
        try (FileSyncEngine engine = new FileSyncEngine()) {
            return engine.execute(TestSupport.request(source, dest, type, selection, Selection.none()), rec);
        }
    }

    /** The audit's own fixture (A01), kept close to how it was reported. */
    @Test
    void aCopyWhoseDestinationContainsTheSourceIsRefusedBeforeAnythingIsWritten() throws Exception {
        Path src = Files.createDirectories(root.resolve("src/sub"));
        TestSupport.write(src.resolve("a.txt"), "src-original");
        Path dest = src.getParent();
        Map<String, String> before = digests(dest);

        JobResult result = run(JobType.COPY_ONLY, src, dest, TestSupport.selection("a.txt"));

        assertEquals(JobStatus.FAILED, result.status(),
            "a job whose destination contains its source is not a job that completed");
        assertTrue(result.message().contains("Source is inside the destination"), result.message());
        assertTrue(result.message().contains("half overwritten"), result.message());
        assertEquals(0, result.filesCopied(), result.message());
        assertFalse(Files.exists(dest.resolve("a.txt")),
            "the refusal happens before rsync runs, not after a copy has landed next to the tree it came from");
        assertUnchanged(dest, before);
    }

    @Test
    void aCopyIntoASubdirectoryOfTheSourceIsRefusedTheOtherWayRoundToo() throws Exception {
        Path src = Files.createDirectories(root.resolve("data"));
        TestSupport.write(src.resolve("a.txt"), "src-original");
        Path dest = Files.createDirectories(src.resolve("backup"));
        Map<String, String> before = digests(src);

        JobResult result = run(JobType.COPY_ONLY, src, dest, TestSupport.selection("a.txt"));

        assertEquals(JobStatus.FAILED, result.status(), result.message());
        assertTrue(result.message().contains("Destination is inside the source"), result.message());
        assertTrue(result.message().contains("cannot say which copy is the original"), result.message());
        // The copy would land inside the tree the scan is reading, and the next run would find its own
        // output listed as a source file.
        assertFalse(Files.exists(dest.resolve("a.txt")), result.message());
        assertUnchanged(src, before);
    }

    @Test
    void aCopyFromASubdirectoryUpIntoItsParentKeepsTheSourceItWasReadingIntact() throws Exception {
        Path parent = Files.createDirectories(root.resolve("tree"));
        Path src = Files.createDirectories(parent.resolve("sub"));
        TestSupport.write(src.resolve("a.txt"), "src-original");
        TestSupport.write(parent.resolve("keep.txt"), "keep-me");
        Map<String, String> before = digests(parent);

        // The audit's A01: the source is inside the destination, so the copy writes over the files it is
        // reading and a run stopped half way leaves the source half overwritten by its own copy.
        JobResult result = run(JobType.COPY_ONLY, src, parent, TestSupport.selection("a.txt"));

        assertEquals(JobStatus.FAILED, result.status(), result.message());
        assertTrue(result.message().contains("Source is inside the destination"), result.message());
        assertTrue(result.message().contains("half overwritten"), result.message());
        assertEquals(0, result.filesCopied(), result.message());
        assertNotNull(Files.readString(parent.resolve("sub/a.txt")));
        assertUnchanged(parent, before);
    }

    @Test
    void aDestinationThatIsALinkToTheSourceIsRefusedAsTheSameDirectory() throws Exception {
        Path src = Files.createDirectories(root.resolve("data"));
        TestSupport.write(src.resolve("a.txt"), "src-original");
        Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        Files.createSymbolicLink(elsewhere.resolve("alias"), src);
        Map<String, String> before = digests(src);

        // Spelled differently, lands on the same directory. Containment that only compares spellings passes
        // this and then overwrites the source through the link.
        JobResult result = run(JobType.COPY_ONLY, src, elsewhere.resolve("alias"),
            TestSupport.selection("a.txt"));

        assertEquals(JobStatus.FAILED, result.status(), result.message());
        assertTrue(result.message().contains("same directory"), result.message());
        assertUnchanged(src, before);
    }

    @Test
    void aSourceReachedThroughALinkInsideTheDestinationIsRefusedToo() throws Exception {
        Path dest = Files.createDirectories(root.resolve("backup"));
        Path src = Files.createDirectories(root.resolve("data"));
        TestSupport.write(src.resolve("a.txt"), "src-original");
        Files.createSymbolicLink(dest.resolve("mirror"), src);
        Map<String, String> before = digests(src);

        // dest/mirror is the source. "Source is not inside the destination" is true of the spelling and
        // false of the directory.
        JobResult result = run(JobType.COPY_ONLY, dest.resolve("mirror"), dest,
            TestSupport.selection("a.txt"));

        assertEquals(JobStatus.FAILED, result.status(), result.message());
        assertTrue(result.message().contains("Source is reached through the destination"), result.message());
        assertTrue(result.message().contains("by way of"), result.message());
        assertUnchanged(src, before);
        assertFalse(Files.exists(dest.resolve("a.txt")), "nothing may have been written: " + result.message());
    }

    @Test
    void aSelectedPathThatClimbsOutOfTheDestinationIsRefusedBeforeWriting() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("data/dest"));
        Path outside = Files.createDirectories(root.resolve("data/outside"));
        TestSupport.write(src.resolve("escape.bin"), "payload");
        TestSupport.write(outside.resolve("escape.bin"), "innocent-data");
        Map<String, String> before = digests(outside);

        // A name carrying ".." reaches a file the job has no business writing to. rsync would have followed
        // it happily; the refusal is ours.
        JobResult result = run(JobType.COPY_ONLY, src, dest, TestSupport.selection("../outside/escape.bin"));

        assertEquals(JobStatus.FAILED, result.status(), result.message());
        assertTrue(result.message().contains("outside the directory"), result.message());
        assertTrue(result.message().contains("outside/escape.bin"), result.message());
        assertEquals(0, result.filesCopied(), result.message());
        assertEquals("innocent-data", Files.readString(outside.resolve("escape.bin")),
            "the file outside the destination must be untouched, not overwritten with the payload");
        assertUnchanged(outside, before);
    }

    @Test
    void aContainedLookingNameThatEscapesThroughALinkedDirectoryIsRefusedAsWell() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("data/dest"));
        Path outside = Files.createDirectories(root.resolve("data/outside"));
        Files.createDirectories(src.resolve("sub"));
        TestSupport.write(src.resolve("sub/inner.bin"), "payload");
        TestSupport.write(outside.resolve("inner.bin"), "innocent-data");
        Files.createSymbolicLink(dest.resolve("sub"), outside);
        Map<String, String> before = digests(outside);

        // Nothing in the name says "outside": dest/sub is a link, and the write lands in data/outside.
        // Containment has to be checked all the way up the path, against the resolved tree.
        JobResult result = run(JobType.COPY_ONLY, src, dest, TestSupport.selection("sub/inner.bin"));

        assertEquals(JobStatus.FAILED, result.status(), result.message());
        assertTrue(result.message().contains("outside the directory"), result.message());
        assertEquals("innocent-data", Files.readString(outside.resolve("inner.bin")),
            "a write through a linked destination directory is a write outside the destination");
        assertUnchanged(outside, before);
    }

    @Test
    void verifyOnlyIsHeldToTheSameContainmentRuleEvenThoughItWritesNothingForever() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("data/dest"));
        Path outside = Files.createDirectories(root.resolve("data/outside"));
        TestSupport.write(src.resolve("a.txt"), "same");
        TestSupport.write(dest.resolve("a.txt"), "same");
        TestSupport.write(outside.resolve("a.txt"), "someone-elses-business");

        // Verify Only writes no file - but its comparison would be of a file outside the destination,
        // reported as a destination verdict. Same rule, same refusal.
        JobResult result = run(JobType.VERIFY_ONLY, src, dest, TestSupport.selection("../outside/a.txt"));

        assertEquals(JobStatus.FAILED, result.status(), result.message());
        assertTrue(result.message().contains("outside the directory"), result.message());
        assertEquals("someone-elses-business", Files.readString(outside.resolve("a.txt")));
    }

    // ---------------------------------------------------------------- §2.11 collisions

    @Test
    void aCopyThatFindsCollidingNamesRefusesUntilSomebodyAcceptsIt() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("dest"));
        TestSupport.write(src.resolve("Report.txt"), "upper");
        TestSupport.write(src.resolve("report.txt"), "lower");
        Map<String, String> before = digests(dest);

        try (FileSyncEngine engine = new FileSyncEngine()) {
            JobResult refused = engine.execute(TestSupport.request(src, dest, JobType.COPY_ONLY,
                TestSupport.selection("Report.txt", "report.txt"), Selection.none()), rec);

            assertEquals(JobStatus.FAILED, refused.status(),
                "two names that are one file on a case-insensitive destination: copying them is overwriting"
                    + " one with the other, and nobody was asked");
            assertTrue(refused.message().contains("colliding name(s)"), refused.message());
            assertTrue(refused.message().contains("--allow-collision-names"), refused.message());
            assertEquals(0, refused.filesCopied(), refused.message());
            assertEquals(List.of(), Files.list(dest).toList(), "a refusal writes nothing");
            assertUnchanged(dest, before);

            // Ask, accept, run: the same job, the same files, with the risk taken on purpose.
            engine.acknowledgeCollisions(true);
            JobResult accepted = engine.execute(TestSupport.request(src, dest, JobType.COPY_ONLY,
                TestSupport.selection("Report.txt", "report.txt"), Selection.none()), rec);

            assertEquals(JobStatus.COMPLETED, accepted.status(), accepted.message());
            assertTrue(accepted.warnings().stream().anyMatch(w -> w.contains("collision")),
                "accepting the risk does not make the warning disappear: " + accepted.warnings());
            assertTrue(Files.exists(dest.resolve("Report.txt")) && Files.exists(dest.resolve("report.txt")),
                "on this case-sensitive filesystem both files exist, which is the point of the warning");
        }
    }

    @Test
    void aDryRunStillShowsWhatCollidingNamesWouldDo() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("dest"));
        TestSupport.write(src.resolve("Report.txt"), "upper");
        TestSupport.write(src.resolve("report.txt"), "lower");

        try (FileSyncEngine engine = new FileSyncEngine()) {
            JobResult result = engine.execute(TestSupport.request(src, dest, JobType.COPY_ONLY,
                TestSupport.selection("Report.txt", "report.txt"), Selection.none(),
                ScanOptions.DEFAULTS, false, true), rec);

            assertTrue(result.isSuccess(), "a dry run writes nothing, so it has nothing to refuse: "
                + result.message());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("collision")),
                result.warnings().toString());
            assertEquals(List.of(), Files.list(dest).toList());
        }
    }

    @Test
    void verifyOnlyNamesCollidingFilesWithoutRefusingOrAsking() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("dest"));
        TestSupport.write(src.resolve("Report.txt"), "upper");
        TestSupport.write(src.resolve("report.txt"), "upper");
        TestSupport.write(dest.resolve("Report.txt"), "upper");
        TestSupport.write(dest.resolve("report.txt"), "upper");

        JobResult result = run(JobType.VERIFY_ONLY, src, dest, TestSupport.selection("Report.txt",
            "report.txt"));

        assertEquals(JobStatus.COMPLETED, result.status(),
            "verification writes nothing, so there is no overwrite to refuse: " + result.message());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("collision")),
            "it still has to say the names collide: " + result.warnings());
    }

    // ---------------------------------------------------------------- §2.6 exports

    @Test
    void aReportIsNeverWrittenOverAFileTheJobIsProtecting() throws Exception {
        PrintCapture out = new PrintCapture();
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("dest"));
        TestSupport.write(src.resolve("notes.txt"), "the only copy of these notes");
        String original = Files.readString(src.resolve("notes.txt"));

        // A01's pattern, one step along: the export pointed at a file the job is reading.
        int exit = Cli.run(List.of("--cli", "--quiet", "--source", src.toString(), "--dest", dest.toString(),
            "--files", "notes.txt", "--no-verify", "--csv", src.resolve("notes.txt").toString())
            .toArray(String[]::new), out.outStream, out.errStream);

        assertEquals(Cli.EXIT_REPORT_FAILED, exit,
            "the copies happened and the record did not arrive: that is not a clean run. " + out.err()
                + out.out());
        assertEquals(original, Files.readString(src.resolve("notes.txt")),
            "the file the report was pointed at is the file the job was reading - it must be intact");
        assertTrue(out.err().contains("A file is already there"), out.err());
        try (var files = Files.list(src)) {
            assertEquals(1, files.count(), "a refused export leaves no temporary file behind");
        }
    }

    @Test
    void aReportInsideTheCopiedTreesNeedsAnExplicitDecisionAndSaysWhy() throws Exception {
        PrintCapture out = new PrintCapture();
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("dest"));
        TestSupport.write(src.resolve("a.txt"), "1");

        int refused = Cli.run(List.of("--cli", "--quiet", "--source", src.toString(), "--dest",
            dest.toString(), "--files", "a.txt", "--no-verify", "--csv",
            dest.resolve("report.csv").toString()).toArray(String[]::new), out.outStream, out.errStream);

        assertEquals(Cli.EXIT_REPORT_FAILED, refused, out.err());
        assertTrue(out.err().contains("the destination tree"), out.err());
        assertFalse(Files.exists(dest.resolve("report.csv")), "refused means nothing was written");
        assertTrue(out.err().contains("the destination tree") && out.err().contains("Choose a path outside"),
            "the refusal has to say where it would have landed and what the alternative is: " + out.err());

        int allowed = Cli.run(List.of("--cli", "--quiet", "--source", src.toString(), "--dest",
            dest.toString(), "--files", "a.txt", "--no-verify", "--allow-report-in-tree", "--csv",
            dest.resolve("report.csv").toString()).toArray(String[]::new), out.outStream, out.errStream);

        assertEquals(Cli.EXIT_OK, allowed, out.err());
        assertTrue(Files.exists(dest.resolve("report.csv")), "the user asked for it in the right words");
        List<String> afterAllow;
        try (var files = Files.list(dest)) {
            afterAllow = files.map(f -> f.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("a.txt", "report.csv"), afterAllow,
            "a completed export leaves no .fcu-report-*.tmp behind");
    }

    @Test
    void evenInsideTheTreesAnExistingFileIsNeverOverwritten() throws Exception {
        PrintCapture out = new PrintCapture();
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("dest"));
        TestSupport.write(src.resolve("a.txt"), "1");
        TestSupport.write(dest.resolve("existing.txt"), "destination data");

        int exit = Cli.run(List.of("--cli", "--quiet", "--source", src.toString(), "--dest", dest.toString(),
            "--files", "a.txt", "--no-verify", "--allow-report-in-tree", "--csv",
            dest.resolve("existing.txt").toString()).toArray(String[]::new), out.outStream, out.errStream);

        assertEquals(Cli.EXIT_REPORT_FAILED, exit,
            "the permission to put a report inside the trees is not permission to replace a file in them");
        assertEquals("destination data", Files.readString(dest.resolve("existing.txt")));
        assertTrue(out.err().contains("A file is already there"), out.err());
    }

    @Test
    void bothFormatsAreRefusedTheSameWayAndNeitherCreatesTheDirectoryItWasToldToWriteIn() throws Exception {
        PrintCapture out = new PrintCapture();
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("dest"));
        TestSupport.write(src.resolve("a.txt"), "1");
        Path missing = root.resolve("nowhere/report.json");

        int exit = Cli.run(List.of("--cli", "--quiet", "--source", src.toString(), "--dest", dest.toString(),
            "--files", "a.txt", "--no-verify", "--json", missing.toString())
            .toArray(String[]::new), out.outStream, out.errStream);

        assertEquals(Cli.EXIT_REPORT_FAILED, exit, out.err());
        assertFalse(Files.exists(missing.getParent()),
            "a report path that does not exist is a mistake to report, not a directory tree to create");
        assertFalse(Files.exists(dest.resolve("a.txt.bak")));

        // Same job, both formats, one protected file each: both refusals have to arrive.
        PrintCapture both = new PrintCapture();
        int exit2 = Cli.run(List.of("--cli", "--quiet", "--source", src.toString(), "--dest", dest.toString(),
            "--files", "a.txt", "--no-verify",
            "--csv", src.resolve("a.txt").toString(),
            "--json", src.resolve("a.txt").toString()).toArray(String[]::new), both.outStream, both.errStream);
        assertEquals(Cli.EXIT_REPORT_FAILED, exit2, both.err());
        assertTrue(both.err().contains("CSV") && both.err().contains("JSON"),
            "one format silently succeeding while the other is refused would hide half of this: " + both.err());
        assertEquals("1", Files.readString(src.resolve("a.txt")));
    }

    @Test
    void aReportTargetThatIsALinkOrADirectoryIsRefusedRatherThanFollowed() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("dest"));
        Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        TestSupport.write(elsewhere.resolve("precious.txt"), "precious");
        Files.createSymbolicLink(dest.resolve("link.csv"), elsewhere.resolve("precious.txt"));
        FileSyncEngine.JobResult sample = runWithOneFile();

        // A link is not a report path: writing "to" it replaces the file the link points at, which is
        // nobody's idea of exporting a report.
        ReportExporter.ReportRefusal throughALink = org.junit.jupiter.api.Assertions.assertThrows(
            ReportExporter.ReportRefusal.class,
            () -> ReportExporter.writeReport(dest.resolve("link.csv"), sample,
                ReportExporter.Format.CSV, src, dest, false));
        assertTrue(throughALink.getMessage().contains("A file is already there"), throughALink.getMessage());
        assertEquals("precious", Files.readString(elsewhere.resolve("precious.txt")),
            "writing through the link would have replaced the file it points at");

        ReportExporter.ReportRefusal aDirectory = org.junit.jupiter.api.Assertions.assertThrows(
            ReportExporter.ReportRefusal.class,
            () -> ReportExporter.writeReport(dest, sample, ReportExporter.Format.JSON, src, dest, false));
        assertTrue(aDirectory.getMessage().contains("A file is already there"), aDirectory.getMessage());

        List<String> left;
        try (var files = Files.list(dest)) {
            left = files.map(f -> f.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("link.csv"), left, "refused exports leave no temporary file behind");
    }

    /** A real one-file job, to have something real to try exporting. */
    private FileSyncEngine.JobResult runWithOneFile() throws Exception {
        Path src = Files.createDirectories(root.resolve("one-file-src"));
        Path dest = Files.createDirectories(root.resolve("one-file-dest"));
        TestSupport.write(src.resolve("a.txt"), "1");
        try (FileSyncEngine engine = new FileSyncEngine()) {
            return engine.execute(TestSupport.request(src, dest, JobType.COPY_ONLY,
                TestSupport.selection("a.txt"), Selection.none()), rec);
        }
    }

    private static class PrintCapture {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        private final PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        private final PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);

        String out() {
            return out.toString(StandardCharsets.UTF_8);
        }

        String err() {
            return err.toString(StandardCharsets.UTF_8);
        }
    }
}
