package com.fileverifier;

import static com.fileverifier.FileSyncEngine.JobType.COPY_AND_VERIFY;
import static com.fileverifier.FileSyncEngine.JobType.VERIFY_ONLY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fileverifier.FileSyncEngine.JobResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a symbolic link is judged by. Under {@code LINK} the object is the link: its kind and its text are
 * compared, and nothing is ever read through it - which matters out loud when the link points outside the two
 * directories the job was given. Under {@code FOLLOW} the verdict is about bytes reached through the link, and
 * the report says so. Under {@code SKIP} a link is not in the job at all.
 */
class SymlinkVerdictTest {

    @TempDir
    Path tmp;

    private static void assumeRealRsync() {
        assumeTrue(TestSupport.rsyncAvailable(), "needs a real rsync");
    }

    private static ScanOptions policy(ScanOptions.SymlinkPolicy p) {
        return ScanOptions.DEFAULTS.withSymlinkPolicy(p);
    }

    private static FileSyncEngine.JobRequest request(Path src, Path dst, FileSyncEngine.JobType type,
                                                     ScanOptions options, String... names) throws IOException {
        Files.createDirectories(src);
        Files.createDirectories(dst);
        return TestSupport.request(src, dst, type, TestSupport.selection(names), TestSupport.selection(),
                options, false);
    }

    private static FileSyncEngine.FileOutcome outcome(JobResult result, String name) {
        return result.outcomes().stream().filter(o -> o.relPath().equals(name)).findFirst().orElse(null);
    }

    /**
     * Nothing was reported as unreadable. The engine's read problems all begin with the same words, so the
     * check is for that and not for the word "read" anywhere - a link warning legitimately says that nothing
     * was read through the link.
     */
    private static void assertNoReadProblem(JobResult result, String name) {
        // Two wordings exist for a failed read - the verdict path says "Could not read file:", the source and
        // destination hashing passes say "Could not read source file:" and "Could not read destination file:".
        // A job that is not supposed to open the object must not produce either one, whichever pass it comes from.
        assertTrue(result.warnings().stream()
                        .noneMatch(w -> (w.startsWith("Could not read file: " + name)
                                        || w.contains("Could not read file: " + name + " ")
                                        || w.contains("Could not read source file: " + name)
                                        || w.contains("Could not read destination file: " + name))),
                "a job that never opened the target must not report a read problem: " + result.warnings());
    }

    @Test
    void linkPolicyCarriesADirectoryLinkAndCopiesItAsALink() throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("dir/a.txt"), "1\n");
        TestSupport.write(src.resolve("real.txt"), "2\n");
        Files.createSymbolicLink(src.resolve("mirror"), src.resolve("dir"));

        DirectoryScanner.ScanResult scanned = DirectoryScanner.scanFiles(src,
                policy(ScanOptions.SymlinkPolicy.LINK));
        List<String> listed = scanned.files().stream().map(FileEntry::relPath).sorted().toList();
        assertEquals(List.of("dir/a.txt", "mirror", "real.txt"), listed,
                "a directory link is an object of its own under LINK - carried, not descended into");
        assertTrue(!listed.contains("mirror/a.txt"), listed.toString());

        FileSyncEngine engine = new FileSyncEngine();
        try {
            JobResult result = engine.execute(request(src, dst, COPY_AND_VERIFY,
                    policy(ScanOptions.SymlinkPolicy.LINK), "dir/a.txt", "mirror", "real.txt"), null);
            assertTrue(result.isSuccess(), result.message());
            assertEquals(3, result.filesCopied(), result.message());
            assertEquals(3, result.filesChecked(), result.message());
            assertEquals(3, result.filesMatched(), result.message());
            assertTrue(Files.isSymbolicLink(dst.resolve("mirror")), "the link must arrive as a link");
            assertNull(outcome(result, "mirror").sourceHash(),
                    "a link's verdict is its text; the directory it points at was never walked or hashed");
            assertNull(outcome(result, "mirror").destHash(), result.toString());
        } finally {
            engine.close();
        }
    }

    @Test
    void linkPolicyCarriesADanglingLinkAndJudgesItByItsText() throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("keep.txt"), "k\n");
        Files.createSymbolicLink(src.resolve("gone"), src.resolve("nowhere.txt"));

        DirectoryScanner.ScanResult scanned = DirectoryScanner.scanFiles(src,
                policy(ScanOptions.SymlinkPolicy.LINK));
        List<String> listed = scanned.files().stream().map(FileEntry::relPath).sorted().toList();
        assertTrue(listed.contains("gone"), "a dangling link is still an object: " + listed);
        assertTrue(scanned.warnings().stream().anyMatch(w -> w.contains("Dangling symlink: gone")),
                scanned.warnings().toString());

        FileSyncEngine engine = new FileSyncEngine();
        try {
            JobResult result = engine.execute(request(src, dst, COPY_AND_VERIFY,
                    policy(ScanOptions.SymlinkPolicy.LINK), "gone", "keep.txt"), null);
            assertTrue(result.isSuccess(), result.message());
            assertEquals(2, result.filesCopied(), result.message());
            assertEquals(2, result.filesChecked(), result.message());
            assertEquals(2, result.filesMatched(), result.message());
            assertEquals(0, result.filesMissing(), "a dangling link that arrived as a link is not missing: "
                    + result.message());
            assertTrue(Files.isSymbolicLink(dst.resolve("gone")));
            assertTrue(!Files.exists(dst.resolve("gone")), "it stays dangling, as the source had it");
            assertNull(outcome(result, "gone").sourceHash(), result.toString());
        } finally {
            engine.close();
        }
    }

    @Test
    void aLinkThatReachedTheDestinationAsARegularFileIsAMismatchThatNamesWhatChanged() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("target.txt"), "t\n");
        Files.createSymbolicLink(src.resolve("thing"), src.resolve("target.txt"));
        TestSupport.write(dst.resolve("thing"), "a plain file where a link belongs\n");

        FileSyncEngine engine = new FileSyncEngine();
        try {
            JobResult result = engine.execute(request(src, dst, VERIFY_ONLY,
                    policy(ScanOptions.SymlinkPolicy.LINK), "thing"), null);
            assertEquals(1, result.filesMismatched(), result.message());
            FileSyncEngine.FileOutcome judged = outcome(result, "thing");
            assertNotNull(judged, result.outcomes().toString());
            assertEquals("MISMATCH", judged.kind(), result.toString());
            assertNull(judged.sourceHash(), "no digest belongs to a link: " + judged);
            assertNull(judged.destHash(), result.toString());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("thing")
                            && w.contains("symbolic link") && w.contains("regular file")),
                    "the warning has to say which object became which: " + result.warnings());
        } finally {
            engine.close();
        }
    }

    @Test
    void aRegularFileWhoseDestinationIsALinkIsJudgedWithoutReadingThroughTheLink() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Path outside = tmp.resolve("outside.txt");
        TestSupport.write(outside, "not part of either directory\n");
        TestSupport.write(src.resolve("thing"), "plain source\n");
        Files.createSymbolicLink(dst.resolve("thing"), outside);

        for (ScanOptions.SymlinkPolicy p : List.of(ScanOptions.SymlinkPolicy.LINK,
                ScanOptions.SymlinkPolicy.SKIP)) {
            FileSyncEngine engine = new FileSyncEngine();
            try {
                JobResult result = engine.execute(request(src, dst, VERIFY_ONLY, policy(p), "thing"), null);
                assertEquals(1, result.filesMismatched(), p + ": " + result.message());
                FileSyncEngine.FileOutcome judged = outcome(result, "thing");
                assertNotNull(judged, p + ": " + result.outcomes());
                assertNull(judged.sourceHash(), p + ": a verdict made by comparing a link with a file needs no"
                        + " digest of either: " + judged);
                assertNull(judged.destHash(),
                        p + ": the link's target lies outside the destination and was never read: " + judged);
                assertTrue(result.warnings().stream().anyMatch(w -> w.contains("thing")
                                && w.contains("symbolic link") && w.contains("regular file")),
                        p + ": " + result.warnings());
            } finally {
                engine.close();
            }
        }
    }

    @Test
    void linkPolicyComparesLinkTextAndNeverTheThingPointedAt() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        // Both links say the same thing, and what they say points outside both directories. The verdict is the
        // text: the target is never opened, so no digest of anything outside these roots is produced.
        Files.createSymbolicLink(src.resolve("same"), Path.of("/etc/hostname"));
        Files.createSymbolicLink(dst.resolve("same"), Path.of("/etc/hostname"));
        Files.createSymbolicLink(src.resolve("other"), Path.of("/etc/hosts"));
        Files.createSymbolicLink(dst.resolve("other"), Path.of("/etc/hostname"));

        FileSyncEngine engine = new FileSyncEngine();
        try {
            JobResult result = engine.execute(request(src, dst, VERIFY_ONLY,
                    policy(ScanOptions.SymlinkPolicy.LINK), "same", "other"), null);
            assertEquals(1, result.filesMatched(), result.message());
            assertEquals(1, result.filesMismatched(), result.message());
            assertEquals(2, result.filesChecked(), result.message());
            assertNull(outcome(result, "same").sourceHash(), result.toString());
            assertNull(outcome(result, "other").destHash(), result.toString());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("other")
                    && (w.contains("link text") || w.contains("different text"))), result.warnings().toString());
            assertNoReadProblem(result, "same");
            assertNoReadProblem(result, "other");
        } finally {
            engine.close();
        }
    }

    /**
     * Two links carrying the same text do not have the same target. Under {@code FOLLOW} the verdict is about
     * bytes, and the object standing in the destination is a link, which no policy lets this program read
     * through - so the honest answer is that the two cannot be compared as bytes, never a match inferred from
     * the words in the link.
     */
    @Test
    void followPolicyDoesNotCallTwoLinksWithTheSameTextAMatch() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        // The same link text in both trees, and different bytes behind it.
        TestSupport.write(src.resolve("real"), "GOOD\n");
        TestSupport.write(dst.resolve("real"), "BAD!\n");
        Files.createSymbolicLink(src.resolve("a"), Path.of("real"));
        Files.createSymbolicLink(dst.resolve("a"), Path.of("real"));

        FileSyncEngine engine = new FileSyncEngine();
        try {
            JobResult result = engine.execute(request(src, dst, VERIFY_ONLY,
                    policy(ScanOptions.SymlinkPolicy.FOLLOW), "a"), null);
            assertEquals(0, result.filesMatched(),
                    "identical link text is not identical target content: " + result.message());
            assertEquals(1, result.filesMismatched(), result.message());
            assertEquals(0, result.filesExtra(), result.message());
            assertNull(outcome(result, "a").sourceHash(), "no digest was produced by reading through a link");
            assertNull(outcome(result, "a").destHash(), "no digest was produced by reading through a link");
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("a")
                            && w.toLowerCase().contains("symbolic link")
                            && w.contains("not") && w.toLowerCase().contains("read through")),
                    "the job says why the byte comparison it was asked for could not be made: "
                            + result.warnings());
            assertNoReadProblem(result, "a");
        } finally {
            engine.close();
        }
    }

    /**
     * Under {@code LINK} a link is an object with kind and text, not a window onto something else. The hashing
     * pass that runs before the copy must not open it: a link to a directory fails the open with "Is a
     * directory", and a link to a stalled FIFO would stop a job that has nothing to read at all.
     */
    @Test
    void linkPolicyDoesNotOpenTheThingALinkPointsAtWhenHashingTheSource() throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("dir/inner.txt"), "1\n");
        TestSupport.write(dst.resolve("dir/inner.txt"), "1\n");
        Files.createSymbolicLink(src.resolve("mirror"), Path.of("dir"));
        Files.createSymbolicLink(dst.resolve("mirror"), Path.of("dir"));

        FileSyncEngine engine = new FileSyncEngine();
        try {
            JobResult result = engine.execute(request(src, dst, COPY_AND_VERIFY,
                    policy(ScanOptions.SymlinkPolicy.LINK), "mirror"), null);
            assertEquals(1, result.filesMatched(), result.message());
            assertNoReadProblem(result, "mirror");
            assertTrue(result.warnings().stream().noneMatch(w -> w.contains("Is a directory")),
                    "nothing tried to read the link as a file: " + result.warnings());
        } finally {
            engine.close();
        }
    }

    @Test
    void followPolicyComparesBytesThroughTheLinkAndSaysSo() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("real.txt"), "shared bytes\n");
        Files.createSymbolicLink(src.resolve("alias"), src.resolve("real.txt"));
        TestSupport.write(dst.resolve("real.txt"), "shared bytes\n");
        TestSupport.write(dst.resolve("alias"), "shared bytes\n");

        FileSyncEngine engine = new FileSyncEngine();
        try {
            JobResult result = engine.execute(request(src, dst, VERIFY_ONLY,
                    policy(ScanOptions.SymlinkPolicy.FOLLOW), "alias", "real.txt"), null);
            assertEquals(2, result.filesChecked(), result.message());
            assertEquals(2, result.filesMatched(), result.message());
            assertNotNull(outcome(result, "alias").sourceHash(),
                    "under FOLLOW the verdict is about bytes, so a digest is taken: " + result.outcomes());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("FOLLOW") && w.contains("bytes")
                    && w.contains("alias")),
                    "the report must say the verdict is about bytes reached through a link: " + result.warnings());
        } finally {
            engine.close();
        }
    }

    @Test
    void aLinkUnderSkipIsNotInTheJobAtAll() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("keep.txt"), "k\n");
        Files.createSymbolicLink(src.resolve("gone"), src.resolve("nowhere.txt"));

        DirectoryScanner.ScanResult scanned = DirectoryScanner.scanFiles(src,
                policy(ScanOptions.SymlinkPolicy.SKIP));
        assertEquals(List.of("keep.txt"),
                scanned.files().stream().map(FileEntry::relPath).toList());
        assertEquals(1, scanned.skippedLinks());

        FileSyncEngine engine = new FileSyncEngine();
        try {
            JobResult result = engine.execute(request(src, dst, VERIFY_ONLY,
                    policy(ScanOptions.SymlinkPolicy.SKIP), "keep.txt"), null);
            assertEquals(1, result.filesChecked(), result.message());
            assertTrue(result.outcomes().stream().noneMatch(o -> o.relPath().equals("gone")),
                    result.outcomes().toString());
        } finally {
            engine.close();
        }
    }

    @Test
    void aLinkChangedUnderTheJobIsReportedAsAKindChangeNotAsUnreadable() throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("thing"), "plain\n");
        // The destination already holds a link where a regular file belongs. rsync replaces it (measured), and
        // the verdict is about the object that was there, so it is said before the transfer needs deciding.
        Files.createSymbolicLink(dst.resolve("thing"), Path.of("/etc/hostname"));

        FileSyncEngine engine = new FileSyncEngine();
        try {
            JobResult result = engine.execute(request(src, dst, COPY_AND_VERIFY,
                    policy(ScanOptions.SymlinkPolicy.LINK), "thing"), null);
            assertTrue(result.isSuccess(), result.message());
            assertTrue(Files.isRegularFile(dst.resolve("thing")) && !Files.isSymbolicLink(dst.resolve("thing")),
                    "the copy replaced the link: " + result.message());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("thing")
                            && w.contains("symbolic link")),
                    "what stood there before was a link, and the report should say so: " + result.warnings());
            assertNoReadProblem(result, "thing");
            // A file copied and then compared contributes to both tallies, and to neither twice.
            assertEquals(1, result.filesCopied(), result.message());
            assertEquals(1, result.filesChecked(), result.message());
            assertEquals(1, result.filesMatched(), result.message());
            assertEquals(FileSyncEngine.TransferDisposition.COPIED, outcome(result, "thing").transfer(),
                    result.outcomes().toString());
        } finally {
            engine.close();
        }
    }
}
