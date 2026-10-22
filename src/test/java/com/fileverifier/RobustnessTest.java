package com.fileverifier;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour found by attacking the program rather than by writing the tests it seemed to need: an
 * unusual file name, a null argument, a link where a file was expected, a tool that fails partway, and
 * a machine whose locale cannot spell a file name.
 */
class RobustnessTest {

    private static final FileSyncEngine.ProgressListener SILENT = new FileSyncEngine.ProgressListener() {
        @Override
        public void onCompletion(FileSyncEngine.JobResult result) {
        }
    };

    /**
     * A ProgressListener with one abstract method, so passing null used to surface as a job failure
     * whose message was an internal null dereference.
     */
    @Test
    void aCallerWithoutProgressCallbacksStillGetsAJob(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path dst = Files.createDirectory(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a");

        FileSyncEngine.JobResult result = FileSyncEngine.runOnce(
            TestSupport.request(src, dst, FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selectAllOf(src), FileSyncEngine.Selection.none()), null);

        assertEquals(FileSyncEngine.JobStatus.COMPLETED, result.status(), result.message());
        assertFalse(result.message().contains("null"), "internal defect leaked as a job failure: " + result.message());
        assertEquals(1, result.filesCopied());
        assertEquals(1, result.filesMatched());
    }

    /**
     * The rsync file list is a line list, so a carriage return in a name is as unusable as a newline.
     * It used to be accepted, handed to rsync, and produce "rsync failed (exit code 23)" with no clue
     * about the cause - and a copy that had already written files reported none as written.
     */
    @Test
    void aNameContainingACarriageReturnIsCopiedAndNamedExactly(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path dst = Files.createDirectory(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a\rb.txt"), "one");
        TestSupport.write(src.resolve("normal.txt"), "two");

        FileSyncEngine.JobResult result = FileSyncEngine.runOnce(
            TestSupport.request(src, dst, FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selectAllOf(src), FileSyncEngine.Selection.none()), SILENT);

        // A carriage return used to be refused because the list handed to rsync was a line list and this
        // character ends a line to no visible effect. The list is NUL-delimited now, so the name is a
        // name like any other; what matters is that it survives the round trip.
        assertEquals(FileSyncEngine.JobStatus.COMPLETED, result.status(), result.message());
        assertEquals(2, result.filesCopied(), result.message());
        assertEquals(2, result.filesMatched(), result.message());
        assertTrue(Files.isRegularFile(dst.resolve("a\rb.txt")),
            "the file must arrive under the name it has in the source");
        assertTrue(result.outcomes().stream().anyMatch(o -> o.relPath().equals("a\rb.txt")),
            "and be named exactly there: " + result.outcomes());
    }

    /** A failed copy is a partial copy. The counts must say so. */
    @Test
    void anRsyncFailureMidCopyReportsWhatItActuallyCopied(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path dst = Files.createDirectory(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a");
        TestSupport.write(src.resolve("b.txt"), "b");

        String previous = FileSyncEngine.rsyncBinary();
        try {
            FileSyncEngine.setRsyncBinary(TestSupport.fakeRsync(tmp,
                ">f+++++++++ a.txt\n"
                    + "   1,000,000 100%   1.23MB/s   00:00:00 (xfr#1, to-chk=1/2)\n"
                    + "rsync: [sender] write failed: whatever\n", 23).toString());
            FileSyncEngine.JobResult result = FileSyncEngine.runOnce(
                TestSupport.request(src, dst, FileSyncEngine.JobType.COPY_AND_VERIFY,
                    TestSupport.selection("a.txt", "b.txt"), FileSyncEngine.Selection.none()), SILENT);

            assertEquals(FileSyncEngine.JobStatus.FAILED, result.status());
            assertEquals(1, result.filesCopied(),
                "rsync completed one transfer; saying 0 hides a file that is now in the destination: "
                    + result.message());
            assertTrue(result.message().contains("completed 1"), result.message());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("partial")
                    || w.contains("left") || w.contains("hidden")),
                "a failed copy should also say whether it left half-written files: " + result.warnings());
        } finally {
            FileSyncEngine.setRsyncBinary(previous);
        }
    }

    /**
     * The scanner refuses to list symbolic links, but the destination side is reached by resolving a
     * relative path, so a link can appear during verification. Hashing it reads a file outside the
     * destination and calls the result a comparison.
     */
    @Test
    void aSymbolicLinkAtTheDestinationIsReportedNotRead(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path dst = Files.createDirectory(tmp.resolve("dst"));
        Path secret = TestSupport.write(tmp.resolve("secret.txt"), "content that is not ours to read");
        TestSupport.write(src.resolve("link.txt"), "source content");
        Files.createSymbolicLink(dst.resolve("link.txt"), secret);

        FileSyncEngine.JobResult result = FileSyncEngine.runOnce(
            TestSupport.request(src, dst, FileSyncEngine.JobType.VERIFY_ONLY,
                TestSupport.selectAllOf(src), FileSyncEngine.Selection.none()), SILENT);

        // The job itself ran fine; it is the comparison that found a problem, and the report has to keep
        // those two facts apart.
        assertEquals(FileSyncEngine.JobStatus.COMPLETED, result.status(), result.message());
        assertEquals(1, result.filesMismatched());
        FileSyncEngine.FileOutcome outcome = result.outcomes().get(0);
        assertEquals("MISMATCH", outcome.kind());
        assertNull(outcome.destHash(), "the digest behind the link must not be reported as the destination's");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("symbolic link") && w.contains("link.txt")),
            result.warnings().toString());
        assertTrue(result.filesMatched() == 0, "an unreadable destination is never a match");
    }

    /**
     * File-name encoding comes from the locale. Under LANG=C (cron, systemd services, minimal
     * containers, sudo) every non-ASCII byte in a name is unrepresentable, and the walk used to die
     * with InvalidPathException. Verified in a second JVM because the encoding is fixed at startup.
     */
    @Test
    void aLocaleThatCannotSpellAFileNameSaysSoInsteadOfCrashing(@org.junit.jupiter.api.io.TempDir Path tmp)
        throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path dst = Files.createDirectory(tmp.resolve("dst"));
        TestSupport.write(src.resolve("plain.txt"), "ascii name");
        // The file name is created by the shell from hex escapes, because this test's own JVM may have a
        // locale that cannot spell a Cyrillic name - which is exactly the situation being tested.
        ProcessBuilder maker = new ProcessBuilder("/bin/sh", "-c",
            "cd \"$1\" && printf x > \"$(printf '\\320\\277\\321\\200\\320\\270\\320\\262\\320\\265\\321\\202.txt')\"",
            "setup", src.toString());
        maker.redirectErrorStream(true);
        Process made = maker.start();
        assertTrue(made.waitFor(30, TimeUnit.SECONDS) && made.exitValue() == 0,
            "could not create the fixture name");
        assertTrue(Files.list(src).count() == 2, "fixture should hold two files");

        List<String> command = List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"),
            FileVerifierApp.class.getName(), "--cli", "--source", src.toString(), "--dest", dst.toString(),
            "--all", "--bwlimit", "1000");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("LANG", "C");
        builder.environment().put("LC_ALL", "C");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        List<String> output = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        }
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "the CLI hung under a POSIX locale");
        String text = String.join("\n", output);

        assertFalse(text.contains("InvalidPathException"),
            "an unusable locale must not end as an uncaught exception:\n" + text);
        boolean warnedInsteadOfGuessing = text.contains("UTF-8");
        boolean handledTheName = text.contains("copied=2");
        assertTrue(warnedInsteadOfGuessing || handledTheName,
            "the run must either name the encoding problem and its fix, or handle the name:\n" + text);
        try (var entries = Files.list(dst)) {
            assertTrue(entries.noneMatch(entry -> entry.getFileName().toString().contains("?")),
                "a name this JVM could not decode must not be written to the destination as a guess");
        }
    }

    /** Names that look like shell or rsync syntax are ordinary file names. */
    @Test
    void shellAndRsyncSyntaxInFileNamesIsNotSpecial(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path dst = Files.createDirectory(tmp.resolve("dst"));
        List<String> names = List.of("-dash.txt", "trailing .txt", "a,b.txt", "[bracket].txt",
            "quote'.txt", "dollar$name.txt", "back\\slash.txt", "semi;colon.txt", "tab\tname.txt");
        for (String name : names) {
            TestSupport.write(src.resolve(name), name);
        }

        FileSyncEngine.JobResult result = FileSyncEngine.runOnce(
            TestSupport.request(src, dst, FileSyncEngine.JobType.COPY_AND_VERIFY,
                TestSupport.selectAllOf(src), FileSyncEngine.Selection.none()), SILENT);

        assertEquals(FileSyncEngine.JobStatus.COMPLETED, result.status(), result.message());
        assertEquals(9, result.filesCopied());
        assertEquals(9, result.filesMatched());
        for (String name : names) {
            assertTrue(Files.exists(dst.resolve(name)), "missing on disk: " + name);
        }
    }
}
