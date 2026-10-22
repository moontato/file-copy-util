package com.fileverifier;

import com.fileverifier.FileSyncEngine.JobResult;
import com.fileverifier.FileSyncEngine.JobType;
import com.fileverifier.FileSyncEngine.Selection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Contract tests for audit findings A02, A05, A03(A13) and for the name rules of
 * plan/2026-10-21_audit-remediation.md sections 2.1, 2.2, 2.9 and 2.10.
 *
 * <p>These were written red-first, against the 1.4.0 build, and each failure named the rule that was
 * missing. They are the regression net for the rule that a file's name is its identity: every stage of
 * a job - selection, the list handed to rsync, the parsing of what rsync prints back, the log line and
 * the export - has to carry the same bytes.
 *
 * <p>Do not "fix" a test here to make it pass. The failure mode these tests exist for is a program that
 * quietly asks for different files than the user selected.
 */
class AuditNameFidelityTest {

    private static final FileSyncEngine.ProgressListener QUIET = new FileSyncEngine.ProgressListener() {
        @Override
        public void onCompletion(FileSyncEngine.JobResult completion) {
        }
    };

    /** The engine's name parser, as the job sees it. */
    private static String parsedName(String itemizeLine) {
        return FileSyncEngine.itemizePath(itemizeLine);
    }

    // ---------------------------------------------------------------- identity (A02)

    @Test
    void aNameThatDiffersFromAnotherOnlyBySurroundingSpacesIsItsOwnFile(@TempDir Path tmp)
        throws Exception {
        assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve(" a "), "selected, and its name starts and ends with a space\n");
        TestSupport.write(src.resolve("a"), "deliberately excluded\n");

        // The job is asked for " a " and nothing else. Trimming the scanned name turns it into "a",
        // which is the file the user excluded, so the excluded file is the one that gets copied.
        Selection selected = TestSupport.knownRows(Set.of(" a ", "a"), " a ");
        JobResult result;
        try (FileSyncEngine engine = new FileSyncEngine()) {
            result = engine.execute(
                TestSupport.request(src, dst, JobType.COPY_ONLY, selected, Selection.none()), QUIET);
        }

        assertTrue(result.isSuccess(), "the job itself has to succeed: " + result.message());
        List<String> written = TestSupport.relPathsUnder(dst);
        assertTrue(written.contains(" a "),
            "A02: the selected file is the one whose name has a leading and a trailing space; the"
                + " destination must hold exactly that name. destination=" + quoted(written)
                + " message=" + result.message());
        assertFalse(written.contains("a"),
            "A02: 'a' was excluded, and a trimmed identity made it the file that got copied."
                + " destination=" + quoted(written));
        assertEquals(1, result.filesCopied(), "exactly one file was asked for: " + result.message());
    }

    // ---------------------------------------------------------------- rsync list grammar (A05)

    @Test
    void everySelectedNameReachesTheDestinationEvenWhenRsyncWouldReadItAsAComment(@TempDir Path tmp)
        throws Exception {
        assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        // In a line-delimited --files-from list, a name starting with '#' or ';' is a comment, so
        // rsync silently reads a shorter list than the program asked for.
        TestSupport.write(src.resolve("#secret"), "hash first\n");
        TestSupport.write(src.resolve(";semi"), "semicolon first\n");
        TestSupport.write(src.resolve("-dash"), "dash first\n");
        TestSupport.write(src.resolve("normal"), "plain name\n");

        JobResult result;
        try (FileSyncEngine engine = new FileSyncEngine()) {
            result = engine.execute(
                TestSupport.request(src, dst, JobType.COPY_ONLY, TestSupport.selectAllOf(src),
                    Selection.none()), QUIET);
        }

        List<String> written = TestSupport.relPathsUnder(dst);
        assertEquals(new TreeSet<>(Set.of("#secret", ";semi", "-dash", "normal")),
            new TreeSet<>(written),
            "A05: every selected name must arrive at the destination; a name that looks like option"
                + " or comment syntax to rsync used to be dropped without a word. message="
                + result.message() + " warnings=" + result.warnings());
        assertEquals(4, result.filesCopied(),
            "A05: the copy count must match what was asked for: " + result.message());
        assertTrue(result.isSuccess(), result.message());
    }

    @Test
    void aSelectedNameIsNotSilentlyLeftOutOfTheJobBecauseItsNameIsAwkward(@TempDir Path tmp)
        throws Exception {
        assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("plain"), "carried fine\n");
        TestSupport.write(src.resolve("line\nbreak"), "a newline inside the name\n");

        JobResult result;
        try (FileSyncEngine engine = new FileSyncEngine()) {
            result = engine.execute(
                TestSupport.request(src, dst, JobType.COPY_ONLY, TestSupport.selectAllOf(src),
                    Selection.none()), QUIET);
        }

        assertTrue(result.isSuccess(), result.message());
        List<String> written = TestSupport.relPathsUnder(dst);
        assertTrue(written.contains("line\nbreak"),
            "section 2.9/A3: with a NUL-delimited list a name containing a newline is ordinary data;"
                + " refusing it silently is how a selected file disappears. destination="
                + quoted(written) + " warnings=" + result.warnings());
        assertEquals(2, result.filesCopied(), result.message());
    }

    // ---------------------------------------------------------------- parsing (section 2.2)

    @Test
    void parsingKeepsWhitespaceAndSeparatorsInsideAName() {
        // assertAll rather than a chain of assertEquals: one broken case must not hide the others.
        assertAll(
            () -> assertEquals(" a ", parsedName(">f+++++++++| a "),
                "a leading or trailing space is part of a POSIX filename, so it is part of the identity"),
            () -> assertEquals("pipe|name", parsedName(">f+++++++++|pipe|name"),
                "the name may contain the separator; only the first one divides the columns"),
            () -> assertEquals("tab\tand", parsedName(">f+++++++++|tab\tand"),
                "rsync passes a tab through unescaped, so trimming the remainder would corrupt it"),
            () -> assertEquals("arrow -> target", parsedName(">f+++++++++|arrow -> target"),
                "link arrows belong to %L, not to %n, so an arrow in a name stays in the name"));
    }

    @Test
    void parsingDecodesRsyncOctalByteEscapesAndNothingElse() {
        // Measured on rsync 3.1.3 and 3.4.0: only '#ooo' (octal) is an escape.
        assertAll(
            () -> assertEquals("real\nnewline", parsedName(">f+++++++++|real\\#012newline"),
                "\\#012 is octal for byte 10, the newline rsync escaped"),
            () -> assertEquals("lit\\#012end", parsedName(">f+++++++++|lit\\#134#012end"),
                "\\#134 is octal for a backslash; the '#012' that follows it is literal text again"),
            () -> assertEquals("ctrlx", parsedName(">f+++++++++|ctrl\\#001x"),
                "a control byte arrives escaped and must not stay escaped"),
            () -> assertEquals("plain\\134name", parsedName(">f+++++++++|plain\\134name"),
                "a bare backslash-digit sequence in a real filename is emitted as-is; decoding it would"
                    + " invent a different file"),
            // Bytes are reassembled first and decoded to text once (section 2.2).
            () -> assertEquals("clairé", parsedName(">f+++++++++|clair\\#303\\#251"),
                "\\#303\\#251 is the UTF-8 encoding of e-acute; the pair decodes to one character"));
    }

    @Test
    void parsingRecognizesTheLineShapesTheEngineCountsCopiesFrom() {
        // The counter accepts these shapes; the name must survive for all of them (A10).
        assertAll(
            () -> assertEquals("lnk", parsedName("cL+++++++++|lnk"),
                "rsync creates a symbolic link with a 'cL' line; counting it and losing its name is A10"),
            () -> assertEquals("a b", parsedName(">fcs.......|a b"),
                "an updated existing file is announced with a 'c' in the flag column"));
    }

    @Test
    void theEndOfALineWithNothingAfterItIsNotAFileNamedEmptyString() {
        assertAll(
            () -> assertEquals(null, parsedName(">f+++++++++|"), "no name, no file"),
            () -> assertEquals(null, parsedName(">f+++++++++ "), "the same, in the older form"));
    }

    // ---------------------------------------------------------------- CLI name channels (2.9)

    @Test
    void theExactNameChannelSelectsTheFilesTheCommaSyntaxCannot(@TempDir Path tmp) throws Exception {
        assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("#secret"), "one\n");
        TestSupport.write(src.resolve("with,comma"), "two\n");
        TestSupport.write(src.resolve(" spaced "), "three\n");
        TestSupport.write(src.resolve("plain"), "four\n");

        Path list = tmp.resolve("names.lst");
        Files.write(list, ("#secret\0with,comma\0 spaced \0plain\0").getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = Cli.run(new String[]{"--cli", "--source", src.toString(), "--dest", dst.toString(),
            "--no-verify", "--files-from0", list.toString()},
            new java.io.PrintStream(out, true, StandardCharsets.UTF_8));

        assertEquals(0, exit, out.toString(StandardCharsets.UTF_8));
        List<String> written = TestSupport.relPathsUnder(dst);
        assertEquals(new TreeSet<>(Set.of("#secret", "with,comma", " spaced ", "plain")),
            new TreeSet<>(written),
            "the exact channel must select exactly these names, spaces and all: "
                + quoted(written));
    }

    @Test
    void theNewlineChannelCannotNameAFileWhoseNameContainsANewline(@TempDir Path tmp) throws Exception {
        Path list = tmp.resolve("names.lst");
        Files.write(list, "two\nlines\n".getBytes(StandardCharsets.UTF_8));

        // Documented consequence of a newline-delimited channel: it splits where the name does. The two
        // halves are two names, not one, and that is why --files-from0 exists.
        List<String> names = Cli.readNameList(list, false, "--files-from");
        assertEquals(List.of("two", "lines"), names,
            "the newline channel splits on newlines; a name containing one needs --files-from0");
    }

    @Test
    void aNameListThatIsNotValidUtf8IsRefusedRatherThanDecodedIntoReplacementCharacters(@TempDir Path tmp)
        throws Exception {
        Path list = tmp.resolve("names.lst");
        // 0xC3 0xA9 alone would be a UTF-8 e-acute; a lone 0xC3 is not decodable at all.
        Files.write(list, new byte[] {'n', 'a', 'm', 'e', (byte) 0xC3, 0x00});

        Cli.UsageException refusal = org.junit.jupiter.api.Assertions.assertThrows(
            Cli.UsageException.class, () -> Cli.readNameList(list, true, "--files-from0"));
        assertTrue(refusal.getMessage().contains("not valid UTF-8"), refusal.getMessage());
    }

    @Test
    void aNameInTheLogCannotForgeASecondLine() {
        // 2.10: identity stays raw; the rendering escapes what would break a line or a table row.
        assertAll(
            () -> assertEquals("two\\nlines", RsyncNameCodec.forDisplay("two\nlines")),
            () -> assertEquals("tab\\there", RsyncNameCodec.forDisplay("tab\there")),
            () -> assertEquals("carriage\\rreturn", RsyncNameCodec.forDisplay("carriage\rreturn")),
            () -> assertEquals("bell\\#007bell", RsyncNameCodec.forDisplay("bell\u0007bell")),
            () -> assertEquals("clair\u00e9", RsyncNameCodec.forDisplay("clair\u00e9"),
                "an ordinary name, accented or not, is shown exactly as it is"));
    }

    private static List<String> quoted(List<String> names) {
        return names.stream().map(n -> "<" + n + ">").toList();
    }
}
