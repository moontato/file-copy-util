package com.fileverifier;

import com.fileverifier.FileSyncEngine.JobResult;
import com.fileverifier.FileSyncEngine.JobType;
import com.fileverifier.FileSyncEngine.Selection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Red-first contract tests for audit findings A02 and A05 and for the name-parsing rules of
 * plan/2026-10-21_audit-remediation.md sections 2.1, 2.2 and 2.9.
 *
 * <p>These describe the behaviour the fixes must produce. Several of them fail on purpose until the
 * corresponding phase lands; each failure names the rule that is not yet implemented. Do not "fix" a
 * test to make it pass: fix the code, or record a decision that changes the rule.
 */
class AuditNameFidelityTest {

    private static final FileSyncEngine.ProgressListener QUIET = new FileSyncEngine.ProgressListener() {
        @Override
        public void onCompletion(FileSyncEngine.JobResult completion) {
        }
    };

    /** The engine's current name parser, which section 2.2 replaces. */
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

        // The job is asked for " a " and nothing else. Today normalize() trims the scanned name into
        // "a", which is the file the user excluded, so the excluded file is copied instead.
        Selection selected = TestSupport.knownRows(Set.of(" a ", "a"), " a ");
        JobResult result = new FileSyncEngine().execute(
            TestSupport.request(src, dst, JobType.COPY_ONLY, selected, Selection.none()),
            QUIET);

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

        JobResult result = new FileSyncEngine().execute(
            TestSupport.request(src, dst, JobType.COPY_ONLY, TestSupport.selectAllOf(src),
                Selection.none()), QUIET);

        List<String> written = TestSupport.relPathsUnder(dst);
        assertEquals(new TreeSet<>(Set.of("#secret", ";semi", "-dash", "normal")),
            new TreeSet<>(written),
            "A05: every selected name must arrive at the destination; a name that looks like option"
                + " or comment syntax to rsync used to be dropped without a word. message="
                + result.message() + " warnings=" + result.warnings());
        assertEquals(4, result.filesCopied(),
            "A05: the copy count must match what was asked for: " + result.message());
    }

    @Test
    void aSelectedNameIsNotSilentlyLeftOutOfTheJobBecauseItsNameIsAwkward(@TempDir Path tmp)
        throws Exception {
        assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("plain"), "carried fine\n");
        TestSupport.write(src.resolve("line\nbreak"), "a newline inside the name\n");

        JobResult result = new FileSyncEngine().execute(
            TestSupport.request(src, dst, JobType.COPY_ONLY, TestSupport.selectAllOf(src),
                Selection.none()), QUIET);

        List<String> written = TestSupport.relPathsUnder(dst);
        assertTrue(written.contains("line\nbreak"),
            "section 2.9/A3: with a NUL-delimited list a name containing a newline is ordinary data;"
                + " refusing it silently is how a selected file disappears. destination="
                + quoted(written) + " warnings=" + result.warnings());
    }

    // ---------------------------------------------------------------- parsing (section 2.2)

    @Test
    void parsingKeepsWhitespaceAndSeparatorsInsideAName() {
        // --out-format='%i|%n' with the name last: split at the first separator only, keep the rest.
        assertEquals(" a ", parsedName(">f+++++++++| a "),
            "a leading or trailing space is part of a POSIX filename, so it is part of the identity");
        assertEquals("pipe|name", parsedName(">f+++++++++|pipe|name"),
            "the name may contain the separator; only the first one divides the columns");
        assertEquals("tab\tand", parsedName(">f+++++++++|tab\tand"),
            "rsync passes a tab through unescaped, so trimming the remainder would corrupt it");
        assertEquals("arrow -> target", parsedName(">f+++++++++|arrow -> target"),
            "link arrows belong to %L, not to %n, so an arrow in a name stays in the name");
    }

    @Test
    void parsingDecodesRsyncOctalByteEscapesAndNothingElse() {
        // Measured on rsync 3.1.3 and 3.4.0: only '#ooo' (octal) is an escape.
        assertEquals("real\nnewline", parsedName(">f+++++++++|real\\#012newline"),
            "\\#012 is octal for byte 10, the newline rsync escaped");
        assertEquals("lit\\#012end", parsedName(">f+++++++++|lit\\#134#012end"),
            "\\#134 is octal for a backslash; the '#012' that follows it is literal text again");
        assertEquals("ctrl\u0001x", parsedName(">f+++++++++|ctrl\\#001x"),
            "a control byte arrives escaped and must not stay escaped");
        assertEquals("plain\\134name", parsedName(">f+++++++++|plain\\134name"),
            "a bare backslash-digit sequence in a real filename is emitted as-is; decoding it would"
                + " invent a different file");
        // Bytes are reassembled first and decoded to text once (section 2.2).
        assertEquals("clair\u00e9", parsedName(">f+++++++++|clair\\#303\\#251"),
            "\\#303\\#251 is the UTF-8 encoding of e-acute; the pair decodes to one character");
    }

    @Test
    void parsingRecognizesTheLineShapesTheEngineCountsCopiesFrom() {
        // The counter accepts these shapes; the name must survive for all of them (A10).
        assertEquals("lnk", parsedName("cL+++++++++|lnk"),
            "rsync creates a symbolic link with a 'cL' line; counting it and losing its name is A10");
        assertEquals("a b", parsedName(">fcs.......|a b"),
            "an updated existing file is announced with a 'c' in the flag column");
    }

    private static List<String> quoted(List<String> names) {
        return names.stream().map(n -> "<" + n + ">").toList();
    }
}
