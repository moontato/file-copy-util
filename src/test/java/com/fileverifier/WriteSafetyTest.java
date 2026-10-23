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
import java.util.ArrayList;
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

    @Test
    void aVerifyOnlyComparisonReachedThroughALinkedDestinationDirectoryIsRefused() throws Exception {
        Path src = Files.createDirectories(root.resolve("src/sub"));
        Path dest = Files.createDirectories(root.resolve("data/dest"));
        Path outside = Files.createDirectories(root.resolve("data/outside"));
        TestSupport.write(src.resolve("sub/a"), "payload");
        TestSupport.write(outside.resolve("a"), "payload");
        Files.createSymbolicLink(dest.resolve("sub"), outside);
        Map<String, String> before = digests(outside);

        // The audit's A04 exactly as it was found, in the mode nobody thought about because it writes
        // nothing. The name is an ordinary relative name; the escape is in the destination's own ancestry.
        // Nothing here destroys a file - it reports somebody else's file as a verified backup copy, which is
        // worse than a crash because it looks like a pass.
        JobResult result = run(JobType.VERIFY_ONLY, src, dest, TestSupport.selection("sub/a"));

        assertEquals(JobStatus.FAILED, result.status(),
            "a comparison reached through a linked destination directory is not a verdict about the"
                + " destination: " + result.message());
        assertTrue(result.message().contains("sub/a"), result.message());
        assertEquals(0, result.filesChecked(),
            "the outside file may not appear in the counts as matched: " + result.message());
        assertTrue(outcomesOf(result).isEmpty(), "no verdict row may be recorded for it: " + result.message());
        assertUnchanged(outside, before);
    }

    @Test
    void aVerifyOnlyJobIsNotRefusedMerelyBecauseItsTreesOverlap() throws Exception {
        Path src = Files.createDirectories(root.resolve("data"));
        TestSupport.write(src.resolve("a.txt"), "same");
        TestSupport.write(src.resolve("backup/a.txt"), "same");

        // Overlap is a write hazard, and the refusals for it are scoped to jobs that write. A comparison of
        // a directory against part of itself is pointless but harmless, and refusing it would be this
        // program inventing a danger it is not protecting against.
        JobResult result = run(JobType.VERIFY_ONLY, src, src.resolve("backup"),
            TestSupport.selection("a.txt"));

        assertEquals(JobStatus.COMPLETED, result.status(), result.message());
        assertEquals(1, result.filesChecked(), result.message());
    }

    @Test
    void aLinkCopiedAsALinkIsNotRefusedBecauseItsTargetPointsElsewhere() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("dest"));
        Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        TestSupport.write(elsewhere.resolve("target.bin"), "target");
        Files.createSymbolicLink(src.resolve("link.bin"), elsewhere.resolve("target.bin").toAbsolutePath());
        ScanOptions linkPolicy = ScanOptions.DEFAULTS.withSymlinkPolicy(ScanOptions.SymlinkPolicy.LINK);

        // This is a legal job, and the containment helper must not make it illegal: under copy-as-link the
        // link is the object being copied. Its target is the link's business, not this program's.
        JobResult first;
        try (FileSyncEngine engine = new FileSyncEngine()) {
            first = engine.execute(TestSupport.request(src, dest, JobType.COPY_ONLY,
                TestSupport.selection("link.bin"), Selection.none(), linkPolicy, false), rec);
        }
        assertEquals(JobStatus.COMPLETED, first.status(),
            "a source link whose target is outside the source is still a link inside the source: "
                + first.message());
        assertTrue(Files.isSymbolicLink(dest.resolve("link.bin")), "copied as a link, as asked");
        assertEquals(elsewhere.resolve("target.bin").toAbsolutePath().toString(),
            Files.readSymbolicLink(dest.resolve("link.bin")).toString(), "the link text is what travelled");

        // The interesting half: the destination now holds a link pointing outside itself, and a second copy
        // must not refuse the very object the first copy created.
        JobResult second;
        try (FileSyncEngine engine = new FileSyncEngine()) {
            second = engine.execute(TestSupport.request(src, dest, JobType.COPY_ONLY,
                TestSupport.selection("link.bin"), Selection.none(), linkPolicy, false), rec);
        }
        assertEquals(JobStatus.COMPLETED, second.status(),
            "containment refused a destination link that a previous copy of the same link made: "
                + second.message());
        assertTrue(Files.isSymbolicLink(dest.resolve("link.bin")), second.message());
    }

    @Test
    void aSymlinkAtTheEndOfASelectedPathIsAnObjectNotARoute() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("data/dest"));
        Path outside = Files.createDirectories(root.resolve("data/outside"));
        TestSupport.write(src.resolve("a.txt"), "payload");
        TestSupport.write(outside.resolve("a.txt"), "innocent-data");
        Files.createSymbolicLink(dest.resolve("a.txt"), outside.resolve("a.txt"));
        Map<String, String> before = digests(outside);

        // Measured against rsync, not reasoned about: a copy replaces a link sitting at the destination path
        // and leaves the link's target alone. Refusing this job - which resolving the leaf would do - is a
        // program inventing a hazard, and it would also refuse the legitimate link cases above.
        JobResult result = run(JobType.COPY_ONLY, src, dest, TestSupport.selection("a.txt"));

        assertTrue(result.isSuccess(), result.message());
        assertFalse(Files.isSymbolicLink(dest.resolve("a.txt")), "the link was replaced by the copied file");
        assertEquals("payload", Files.readString(dest.resolve("a.txt")));
        assertEquals("innocent-data", Files.readString(outside.resolve("a.txt")),
            "the target of the link is what must not be touched, and it was not");
        assertUnchanged(outside, before);
    }

    @Test
    void aBrandNewFileIsNotCreatedThroughALinkedDestinationDirectory() throws Exception {
        Path src = Files.createDirectories(root.resolve("src/sub"));
        Path dest = Files.createDirectories(root.resolve("data/dest"));
        Path outside = Files.createDirectories(root.resolve("data/outside"));
        TestSupport.write(src.resolve("sub/brand-new.txt"), "payload");
        Files.createSymbolicLink(dest.resolve("sub"), outside);

        // The name does not exist at the destination yet, so there is no leaf to resolve and no file whose
        // identity could change: the escape is only visible in the directories above it. This is the case a
        // leaf-only containment check passes and rsync then acts on.
        JobResult result = run(JobType.COPY_ONLY, src, dest, TestSupport.selection("sub/brand-new.txt"));

        assertEquals(JobStatus.FAILED, result.status(), result.message());
        assertTrue(result.message().contains("sub/brand-new.txt"), result.message());
        assertFalse(Files.exists(outside.resolve("brand-new.txt")),
            "the whole point: rsync would have created the file at the far end of the link");
        assertFalse(Files.exists(dest.resolve("sub/brand-new.txt")), result.message());
        assertTrue(Files.isSymbolicLink(dest.resolve("sub")), "the link itself is not the thing being fixed");
    }

    @Test
    void aReportPathTakenBySomeoneElseBetweenCheckAndPublicationIsLeftAlone() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("dest"));
        Path reports = Files.createDirectories(root.resolve("reports"));
        Path target = reports.resolve("report.csv");
        TestSupport.write(src.resolve("a.txt"), "1");
        FileSyncEngine.JobResult job = runWithOneFile(src, dest);

        // The existence check and the publication are not one operation. Something can take the name in
        // between, and "we checked first" is not a promise. Publication has to fail on its own terms.
        ReportExporter.ReportRefusal refusal = org.junit.jupiter.api.Assertions.assertThrows(
            ReportExporter.ReportRefusal.class,
            () -> ReportExporter.writeReportForTesting(target, job, ReportExporter.Format.CSV, src, dest,
                false, () -> {
                    try {
                        Files.writeString(target, "another process wrote this first");
                    } catch (java.io.IOException couldNot) {
                        throw new IllegalStateException(couldNot);
                    }
                }));

        assertTrue(refusal.getMessage().contains("appeared at the report path"), refusal.getMessage());
        assertEquals("another process wrote this first", Files.readString(target),
            "the claim is that it was left as it was - that has to be what the file says");
        List<String> left;
        try (var files = Files.list(reports)) {
            left = files.map(f -> f.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("report.csv"), left, "no temporary report file left behind by the failed attempt");
    }

    @Test
    void aFilesystemThatCannotHardLinkIsRefusedRatherThanGivenAWeakerPromise() throws Exception {
        Path src = Files.createDirectories(root.resolve("src2"));
        Path dest = Files.createDirectories(root.resolve("dest2"));
        Path reports = Files.createDirectories(root.resolve("reports2"));
        Path target = reports.resolve("report.json");
        TestSupport.write(src.resolve("a.txt"), "1");
        FileSyncEngine.JobResult job = runWithOneFile(src, dest);

        // FAT, exFAT and some network and FUSE mounts have no hard links. The fallback that was here first
        // created the report name and then copied into it, which left a window in which another writer's file
        // could be replaced through that name, and a moment in which the report existed half-written. Both are
        // gone: the program refuses, writes nothing, and cleans up only the temporary it created.
        ReportExporter.ReportRefusal refusal = org.junit.jupiter.api.Assertions.assertThrows(
            ReportExporter.ReportRefusal.class,
            () -> ReportExporter.writeReportForTesting(target, job, ReportExporter.Format.JSON, src, dest,
                false, () -> { }, (prepared, reportPath) -> {
                    throw new UnsupportedOperationException("links not supported by this filesystem");
                }));

        assertTrue(refusal.getMessage().contains("cannot publish a report"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("was not written"), refusal.getMessage());
        assertFalse(Files.exists(target), "refused means the name was never created");
        try (var files = Files.list(reports)) {
            assertEquals(List.of(), files.map(f -> f.getFileName().toString()).toList(),
                "and no temporary file of ours is left in the report directory");
        }
    }

    @Test
    void aReportNameAppearsAlreadyHoldingTheWholeReport() throws Exception {
        Path src = Files.createDirectories(root.resolve("src3"));
        Path dest = Files.createDirectories(root.resolve("dest3"));
        Path reports = Files.createDirectories(root.resolve("reports3"));
        Path target = reports.resolve("report.csv");
        TestSupport.write(src.resolve("a.txt"), "1");
        FileSyncEngine.JobResult job = runWithOneFile(src, dest);

        // The moment the name exists, the bytes are the whole report: nothing is published incrementally,
        // because the name is a link to a file that was finished before it was linked.
        List<String> seenAtPublication = new ArrayList<>();
        ReportExporter.writeReportForTesting(target, job, ReportExporter.Format.CSV, src, dest, false,
            () -> { }, () -> {
                try {
                    seenAtPublication.add(Files.readString(target));
                    try (var files = Files.list(reports)) {
                        seenAtPublication.add(files.map(f -> f.getFileName().toString()).sorted()
                            .reduce("", (a, b) -> a + "," + b));
                    }
                } catch (java.io.IOException couldNot) {
                    throw new IllegalStateException(couldNot);
                }
            });

        assertEquals(Files.readString(target), seenAtPublication.get(0),
            "the report was complete the instant its name appeared");
        assertTrue(seenAtPublication.get(1).contains("report.csv")
                && seenAtPublication.get(1).contains(".fcu-report-"),
            "the published name and our own staged temporary, and nothing else: " + seenAtPublication.get(1));

        try (var files = Files.list(reports)) {
            assertEquals(List.of("report.csv"), files.map(f -> f.getFileName().toString()).toList(),
                "the temporary is ours and is removed");
        }
    }

    @Test
    void aReportReplacedAfterPublicationIsLeftAloneBecauseOnlyOurOwnTemporaryIsRemoved() throws Exception {
        Path src = Files.createDirectories(root.resolve("src4"));
        Path dest = Files.createDirectories(root.resolve("dest4"));
        Path reports = Files.createDirectories(root.resolve("reports4"));
        Path target = reports.resolve("report.csv");
        TestSupport.write(src.resolve("a.txt"), "1");
        FileSyncEngine.JobResult job = runWithOneFile(src, dest);

        // Somebody else wins the name right after we publish it. Everything this call does afterwards is
        // cleanup of a file it created itself - the staged temporary - so the other file stays where it is.
        ReportExporter.writeReportForTesting(target, job, ReportExporter.Format.CSV, src, dest, false,
            () -> { }, () -> {
                try {
                    Files.delete(target);
                    Files.writeString(target, "someone else's file, in the same name");
                } catch (java.io.IOException couldNot) {
                    throw new IllegalStateException(couldNot);
                }
            });

        assertEquals("someone else's file, in the same name", Files.readString(target),
            "this program does not write to a report path again after publishing it");
        try (var files = Files.list(reports)) {
            assertEquals(List.of("report.csv"), files.map(f -> f.getFileName().toString()).toList());
        }
    }

    private FileSyncEngine.JobResult runWithOneFile(Path src, Path dest) throws Exception {
        TestSupport.write(src.resolve("a.txt"), "1");
        try (FileSyncEngine engine = new FileSyncEngine()) {
            return engine.execute(TestSupport.request(src, dest, JobType.COPY_ONLY,
                TestSupport.selection("a.txt"), Selection.none()), rec);
        }
    }

        @Test
    void anAcceptedCollisionSetCoversThatSetAndNoOther() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("dest"));
        TestSupport.write(src.resolve("Report.txt"), "upper");
        TestSupport.write(src.resolve("report.txt"), "lower");
        TestSupport.write(src.resolve("Note.txt"), "note");
        TestSupport.write(src.resolve("note.txt"), "note too");

        try (FileSyncEngine engine = new FileSyncEngine()) {
            // The real window's flow: the job refuses, the engine reports the set it found, the user accepts
            // those names, and the same job then runs.
            JobResult refused = engine.execute(TestSupport.request(src, dest, JobType.COPY_ONLY,
                TestSupport.selection("Report.txt", "report.txt"), Selection.none()), rec);
            assertEquals(JobStatus.FAILED, refused.status(), refused.message());
            List<String> detected = engine.collisionsDetectedByLastJob();
            assertEquals(1, detected.size(), "one pair named, not one per file: " + detected);

            engine.acknowledgeCollisions(detected);
            JobResult accepted = engine.execute(TestSupport.request(src, dest, JobType.COPY_ONLY,
                TestSupport.selection("Report.txt", "report.txt"), Selection.none()), rec);
            assertEquals(JobStatus.COMPLETED, accepted.status(), accepted.message());

            // The answer was for those two names. A different pair in the same directory is a different
            // question and must not be covered by it.
            JobResult otherPair = engine.execute(TestSupport.request(src, dest, JobType.COPY_ONLY,
                TestSupport.selection("Note.txt", "note.txt"), Selection.none()), rec);
            assertEquals(JobStatus.FAILED, otherPair.status(),
                "accepting Report.txt/report.txt cannot silently accept Note.txt/note.txt: "
                    + otherPair.message());
            assertTrue(engine.collisionsDetectedByLastJob().stream().anyMatch(g -> g.contains("Note.txt")),
                "and it still says which pair it is asking about: "
                    + engine.collisionsDetectedByLastJob());
        }
    }

    @Test
    void aReportPathTheJobItselfTakesDuringTheRunIsRefusedAfterwardsAndExitsFour() throws Exception {
        PrintCapture out = new PrintCapture();
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("dest"));
        TestSupport.write(src.resolve("a.txt"), "1");
        TestSupport.write(src.resolve("report.csv"), "a file that happens to be called report.csv");
        Path target = dest.resolve("report.csv");

        // Nothing was wrong with the report path when the job started. The copy then created it. Publication
        // has to notice, refuse, and leave the copied file alone - and the run has to say that the record of
        // it did not arrive.
        int exit = Cli.run(List.of("--cli", "--quiet", "--source", src.toString(), "--dest", dest.toString(),
            "--files", "a.txt,report.csv", "--no-verify", "--allow-report-in-tree", "--csv",
            target.toString()).toArray(String[]::new), out.outStream, out.errStream);

        assertEquals(Cli.EXIT_REPORT_FAILED, exit,
            "a job that succeeded and could not write its report is not a clean run: " + out.err());
        assertTrue(out.err().contains("A file is already there")
                || out.err().contains("appeared at the report path"), out.err());
        assertEquals("a file that happens to be called report.csv", Files.readString(target),
            "the copy's own file is what is there, and it stays exactly as the copy left it");
        assertEquals(List.of("a.txt", "report.csv"), namesIn(dest),
            "no temporary report file left behind: " + namesIn(dest));
    }

    /**
     * Two findings in one run: a name it was given that was not there, which {@code --strict} fails for, and a
     * report that could not be written. The second is the worse of the two - a run that cannot record itself
     * leaves nothing to check afterwards - so it is the code the run reports.
     */
    @Test
    void aReportThatCouldNotBeWrittenOutranksAStrictFinding() throws Exception {
        Path base = Files.createDirectories(root.resolve("strict-report-race"));
        PrintCapture out = new PrintCapture();
        Path src = Files.createDirectories(base.resolve("src"));
        Path dest = Files.createDirectories(base.resolve("dest"));
        TestSupport.write(src.resolve("a.txt"), "1");
        TestSupport.write(src.resolve("report.csv"), "a file that happens to be called report.csv");
        Path target = dest.resolve("report.csv");

        int exit = Cli.run(List.of("--cli", "--quiet", "--strict", "--source", src.toString(),
            "--dest", dest.toString(), "--files", "a.txt,report.csv,not-there.txt", "--no-verify",
            "--allow-report-in-tree", "--csv", target.toString()).toArray(String[]::new),
            out.outStream, out.errStream);

        assertEquals(Cli.EXIT_REPORT_FAILED, exit,
            "the strict finding is real, but the missing record is worse: " + out.out() + out.err());
        assertTrue(out.out().contains("not-there.txt"), out.out());
    }

    @Test
    void twoFormatsNamingOnePathAreRefusedBeforeTheTransferRuns() throws Exception {
        PrintCapture out = new PrintCapture();
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("dest"));
        TestSupport.write(src.resolve("a.txt"), "1");
        Path shared = root.resolve("reports.csv");

        int exit = Cli.run(List.of("--cli", "--quiet", "--source", src.toString(), "--dest", dest.toString(),
            "--files", "a.txt", "--no-verify", "--csv", shared.toString(), "--json", shared.toString())
            .toArray(String[]::new), out.outStream, out.errStream);

        assertEquals(Cli.EXIT_USAGE, exit, out.err());
        assertTrue(out.err().contains("the same file"), out.err());
        assertFalse(Files.exists(shared), "the old behaviour copied everything, wrote one report, then"
            + " refused the second: a job half recorded");
        assertEquals(List.of(), namesIn(dest), "the transfer never started, so the destination is untouched");
    }

    private static List<String> namesIn(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    private static List<String> outcomesOf(JobResult result) {
        return result.outcomes().stream().map(o -> o.kind() + ":" + o.relPath()).toList();
    }

    // ---------------------------------------------------------------- §2.11 collisions

    @Test
    void twoSpellingsOfOneReportPathThroughALinkedDirectoryAreRefusedBeforeTheCopy() throws Exception {
        PrintCapture out = new PrintCapture();
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("dest"));
        Path real = Files.createDirectories(root.resolve("reports/real"));
        Files.createSymbolicLink(root.resolve("reports/alias"), real);
        TestSupport.write(src.resolve("a.txt"), "1");

        // Each spelling passes the "does this path already exist" check on its own while the target is absent,
        // and comparing the strings says they differ. Only the resolved directory says they are one file: the
        // first report would be written and the second refused because the first created it - after the
        // transfer had already run.
        int exit = Cli.run(List.of("--cli", "--quiet", "--source", src.toString(), "--dest", dest.toString(),
            "--files", "a.txt", "--no-verify", "--csv", root.resolve("reports/real/result").toString(),
            "--json", root.resolve("reports/alias/result").toString()).toArray(String[]::new),
            out.outStream, out.errStream);

        assertEquals(Cli.EXIT_USAGE, exit, out.err());
        assertTrue(out.err().contains("the same file"), out.err());
        assertTrue(out.err().contains("reports/real/result"),
            "the message has to name the file they both mean, not one of the spellings: " + out.err());
        assertEquals(List.of(), namesIn(dest), "refused means the transfer never started");
        try (var files = Files.list(real)) {
            assertEquals(List.of(), files.map(f -> f.getFileName().toString()).toList(),
                "and no report was written under either name");
        }
    }

    @Test
    void aReportPathInsideALinkedDirectoryIsJudgedByWhereItActuallyLands() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path dest = Files.createDirectories(root.resolve("dest"));
        Path outside = Files.createDirectories(root.resolve("data/outside"));
        Files.createSymbolicLink(dest.resolve("linkout"), outside);
        TestSupport.write(src.resolve("a.txt"), "1");

        // dest/linkout is a link to a directory outside the destination, so a report "inside the destination
        // tree" spelled through it is not inside the destination at all: it lands in the outside directory,
        // which neither tree check would catch. The resolution has to be done before the in-tree decision.
        PrintCapture out = new PrintCapture();
        int exit = Cli.run(List.of("--cli", "--quiet", "--source", src.toString(), "--dest", dest.toString(),
            "--files", "a.txt", "--no-verify", "--csv",
            dest.resolve("linkout/report.csv").toString()).toArray(String[]::new), out.outStream,
            out.errStream);

        assertEquals(Cli.EXIT_OK, exit, out.err() + out.out());
        assertTrue(Files.exists(outside.resolve("report.csv")),
            "the report went where the link says, which is outside both trees, so no permission was needed");
        assertFalse(Files.exists(dest.resolve("linkout/report.csv.tmp")));
    }

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
            engine.acknowledgeCollisions(engine.collisionsDetectedByLastJob());
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

        assertEquals(Cli.EXIT_USAGE, exit,
            "a report path that names an existing file is known before the job runs, so it is refused"
                + " before anything is copied: " + out.err() + out.out());
        assertEquals(original, Files.readString(src.resolve("notes.txt")),
            "the file the report was pointed at is the file the job was reading - it must be intact");
        assertTrue(out.err().contains("Refused before starting"), out.err());
        assertTrue(out.err().contains("A file is already there"), out.err());
        assertEquals(List.of("notes.txt"), namesIn(src),
            "a refused run is a run that never started: nothing was copied either");
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

        assertEquals(Cli.EXIT_USAGE, refused,
            "this one is knowable before the job, so the job never runs: " + out.err());
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

        assertEquals(Cli.EXIT_USAGE, exit,
            "the permission to put a report inside the trees is not permission to replace a file in them: "
                + out.err());
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

        assertEquals(Cli.EXIT_USAGE, exit, out.err());
        assertTrue(out.err().contains("does not exist"), out.err());
        assertFalse(Files.exists(missing.getParent()),
            "a report path that does not exist is a mistake to report, not a directory tree to create");
        assertFalse(Files.exists(dest.resolve("a.txt.bak")));

        // Same job, both formats, one protected file each: both refusals have to arrive.
        PrintCapture both = new PrintCapture();
        int exit2 = Cli.run(List.of("--cli", "--quiet", "--source", src.toString(), "--dest", dest.toString(),
            "--files", "a.txt", "--no-verify",
            "--csv", src.resolve("a.txt").toString(),
            "--json", src.resolve("a.txt").toString()).toArray(String[]::new), both.outStream, both.errStream);
        assertEquals(Cli.EXIT_USAGE, exit2, both.err());
        assertTrue(both.err().contains("the same file"), both.err());
        assertFalse(Files.exists(src.resolve("a.txt.bak")));
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
