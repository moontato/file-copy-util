package com.fileverifier;

import com.fileverifier.FileSyncEngine.JobType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The headless front end (4.6): it must apply the same selection rules as the GUI, refuse to guess,
 * and report a completed-but-problematic verification differently from a failed job.
 */
class CliTest {

    @TempDir
    Path source;

    @TempDir
    Path dest;

    private static class Output {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        final PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);

        String out() {
            return out.toString(StandardCharsets.UTF_8);
        }

        String err() {
            return err.toString(StandardCharsets.UTF_8);
        }
    }

    private int run(Output o, String... args) {
        return Cli.run(args, o.outStream, o.errStream);
    }

    private static void assumeRsync() {
        assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
    }

    @Test
    void nothingSelectedIsAUsageErrorNotAnImplicitEverything() {
        Output o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString()));
        assertTrue(o.err().contains("Nothing was selected"), o.err());
        assertTrue(o.err().contains("no files, not all files"), o.err());
    }

    @Test
    void sourceAndDestAndSelectionFormAreRequired() {
        Output o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--dest", dest.toString(), "--all"));
        assertTrue(o.err().contains("--source is required"), o.err());

        o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--source", source.toString(), "--all"));
        assertTrue(o.err().contains("--dest is required"), o.err());

        o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--files", "a.txt"));
        assertTrue(o.err().contains("cannot both be used"), o.err());
    }

    @Test
    void unknownOptionsAndMissingValuesAreRefused() {
        Output o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--delete-everything"));
        assertTrue(o.err().contains("Unknown option: --delete-everything"), o.err());

        o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--timeout"));
        assertTrue(o.err().contains("--timeout needs a value"), o.err());

        o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--timeout", "soon"));
        assertTrue(o.err().contains("must be a number of seconds"), o.err());

        o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--symlinks", "yolo"));
        assertTrue(o.err().contains("--symlinks must be skip, link or follow"), o.err());

        // A value that is actually the next flag must not be swallowed as the previous flag's value.
        o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--ignore", "--quiet"));
        assertTrue(o.err().contains("--ignore needs a value"), o.err());
    }

    @Test
    void allExpandsToTheScannedSelectionAndExcludeRemovesFromIt() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");
        TestSupport.write(source.resolve("sub/b.txt"), "b");
        TestSupport.write(source.resolve("skip.log"), "log");
        TestSupport.write(source.resolve(".git/config"), "git");

        Cli.Options options = Cli.parse(new String[]{"--cli", "--source", source.toString(),
                "--dest", dest.toString(), "--all", "--ignore", "*.log", "--exclude", "sub/b.txt"});

        assertEquals(java.util.Set.of("a.txt"), options.request().sourceSelection().selected());
        assertEquals(JobType.COPY_AND_VERIFY, options.request().jobType());
        assertEquals(FileSyncEngine.NO_TIMEOUT, options.timeoutMillis());
    }

    @Test
    void excludeCannotBeUsedToSubtractFromAnExplicitFileList() {
        Output o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--files", "a.txt,b.txt", "--exclude", "b.txt"));
        assertTrue(o.err().contains("--exclude only applies to --all"), o.err());
    }

    @Test
    void flagsMapOntoEngineSettings() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");

        Cli.Options options = Cli.parse(new String[]{"--cli", "--source", source.toString(),
                "--dest", dest.toString(), "--files", "./a.txt", "--verify-only", "--no-hidden",
                "--symlinks", "follow", "--no-report-extra", "--ignore", "node_modules, *.tmp",
                "--timeout", "7", "--csv", "report.csv", "--json", "report.json", "--quiet"});

        assertEquals(JobType.VERIFY_ONLY, options.request().jobType());
        // The engine's relative-path form is applied to --files too: "./a.txt" and "a.txt" are one file.
        assertEquals(java.util.Set.of("a.txt"), options.request().sourceSelection().selected());
        assertFalse(options.request().scanOptions().includeHidden());
        assertEquals(ScanOptions.SymlinkPolicy.FOLLOW, options.request().scanOptions().symlinkPolicy());
        assertFalse(options.request().detectExtraFiles());
        assertTrue(options.request().scanOptions().ignorePatterns().containsAll(List.of("node_modules", "*.tmp")),
                String.valueOf(options.request().scanOptions().ignorePatterns()));
        assertEquals(7_000L, options.timeoutMillis());
        assertEquals(Path.of("report.csv"), options.csvPath());
        assertEquals(Path.of("report.json"), options.jsonPath());
        assertTrue(options.quiet());
    }

    @Test
    void zeroTimeoutMeansNoLimit() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");
        Cli.Options options = Cli.parse(new String[]{"--cli", "--source", source.toString(),
                "--dest", dest.toString(), "--all", "--timeout", "0"});
        assertEquals(FileSyncEngine.NO_TIMEOUT, options.timeoutMillis());
    }

    @Test
    void copyAndVerifyEndToEndWritesReportsAndExitsClean() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "alpha\n");
        TestSupport.write(source.resolve("sub/b.txt"), "beta\n");
        Path csv = dest.resolve("report.csv");
        Path json = dest.resolve("report.json");

        Output o = new Output();
        int exit = run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--csv", csv.toString(), "--json", json.toString());

        assertEquals(Cli.EXIT_OK, exit);
        assertTrue(o.out().contains("Job complete"), o.out());
        assertTrue(o.out().contains("totals: checked=2 copied=2 matched=2 mismatched=0 missing=0"), o.out());
        assertTrue(Files.isRegularFile(dest.resolve("a.txt")) && Files.isRegularFile(dest.resolve("sub/b.txt")),
                o.out());

        String csvText = Files.readString(csv);
        // The CSV carries the full digests, not the 8-character ones the GUI table shows.
        assertTrue(csvText.contains("\"OK\",\"a.txt\""), csvText);
        assertTrue(csvText.contains("b6a98d9ce9a2d9149288fa3df42d377c3e42737afdcdaf714e33c0a100b51060"), csvText);
        String jsonText = Files.readString(json);
        assertTrue(jsonText.contains("\"matched\": 2") && jsonText.contains("sub/b.txt"), jsonText);
    }

    @Test
    void quietSuppressesProgressButNotResults() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "alpha\n");

        Output o = new Output();
        assertEquals(Cli.EXIT_OK, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--quiet"));
        assertFalse(o.out().contains("copying"), o.out());
        assertTrue(o.out().contains("totals:"), o.out());
    }

    @Test
    void mismatchedFilesExitWithTheVerificationProblemCode() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "source content\n");
        TestSupport.write(dest.resolve("a.txt"), "different content\n");

        Output o = new Output();
        int exit = run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(), "--all",
                "--verify-only");

        assertEquals(Cli.EXIT_VERIFICATION_PROBLEMS, exit);
        assertTrue(o.out().contains("MISMATCH"), o.out());
        assertTrue(o.out().contains("mismatched=1"), o.out());
    }

    @Test
    void missingAtDestinationAlsoExitsNonZero() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "alpha\n");

        Output o = new Output();
        int exit = run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(), "--all",
                "--verify-only");

        assertEquals(Cli.EXIT_VERIFICATION_PROBLEMS, exit);
        assertTrue(o.out().contains("missing=1"), o.out());
    }

    @Test
    void aPreflightProblemIsAJobFailureNotAVerificationProblem() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");
        Output o = new Output();
        int exit = run(o, "--cli", "--source", source.toString(), "--dest", source.toString(), "--all");

        assertEquals(Cli.EXIT_JOB_FAILED, exit);
        assertTrue(o.out().contains("Preflight failed"), o.out());
        assertTrue(o.out().contains("same directory"), o.out());
    }

    @Test
    void helpIsNotAnError() {
        Output o = new Output();
        assertEquals(Cli.EXIT_OK, run(o, "--help"));
        assertTrue(o.out().contains("--verify-only"), o.out());
        assertTrue(o.out().contains("Exit codes"), o.out());
    }

    @Test
    void theGuiIsOnlyStartedWhenNoCliFlagIsPresent() {
        assertTrue(Cli.isCliRequest(new String[]{"--cli", "--source", "/a", "--dest", "/b"}));
        assertTrue(Cli.isCliRequest(new String[]{"--source", "/a", "--dest", "/b"}));
        assertTrue(Cli.isCliRequest(new String[]{"--help"}));
        assertFalse(Cli.isCliRequest(new String[0]));
        assertFalse(Cli.isCliRequest(new String[]{"--quiet"}));
    }

    @Test
    void selectionOfGoneFilesNamesThemAndFailsTheJob() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");

        Output o = new Output();
        int exit = run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--files", "typo.txt");

        assertEquals(Cli.EXIT_JOB_FAILED, exit);
        assertTrue(o.out().contains("typo.txt"), o.out());
        assertTrue(o.out().contains("nothing to do"), o.out());
    }

    @Test
    void aGoneSelectedFileAlongsideRealOnesWarnsButStillRuns() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "alpha\n");

        Output o = new Output();
        int exit = run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--files", "a.txt,typo.txt", "--quiet");

        assertEquals(Cli.EXIT_OK, exit);
        assertTrue(o.out().contains("warning:"), o.out());
        assertTrue(o.out().contains("typo.txt"), o.out());
        assertNotNull(dest.resolve("a.txt"));
        assertTrue(Files.isRegularFile(dest.resolve("a.txt")), o.out());
    }

    @Test
    void dryRunFlagReachesTheRequestAndReportsLimit() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");
        Cli.Options options = Cli.parse(new String[]{"--cli", "--source", source.toString(),
                "--dest", dest.toString(), "--all", "--dry-run", "--bwlimit", "1500"});
        assertTrue(options.request().dryRun());
        assertEquals(1500L, options.bandwidthLimitKbps());
    }

    @Test
    void bwlimitMustBeANumber() {
        Output o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--bwlimit", "fast"));
        assertTrue(o.err().contains("--bwlimit must be KB/s"), o.err());
    }

    @Test
    void dryRunReportsWhatWouldHappenAndWritesNothing() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "alpha\n");
        TestSupport.write(source.resolve("sub/b.txt"), "beta\n");

        Output o = new Output();
        int exit = run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(), "--all",
                "--dry-run", "--quiet");

        // The would-be missing files are exactly what a dry run is for, so they are reported the same
        // way a real verify would report them.
        assertEquals(Cli.EXIT_VERIFICATION_PROBLEMS, exit);
        assertTrue(o.out().contains("Dry run - nothing was written"), o.out());
        assertTrue(o.out().contains("2 would be copied"), o.out());
        try (var walk = Files.walk(dest)) {
            assertTrue(walk.noneMatch(Files::isRegularFile), o.out());
        }
    }

    @Test
    void maxResultsBoundsTheExportedRowsButNotTheCounters() throws Exception {
        for (int i = 0; i < 5; i++) {
            TestSupport.write(source.resolve("f" + i + ".txt"), "c" + i + "\n");
            TestSupport.write(dest.resolve("f" + i + ".txt"), "c" + i + "\n");
        }
        Path csv = source.resolve("report.csv");

        Output o = new Output();
        int exit = run(o, "--cli", "--quiet", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--max-results", "3", "--csv", csv.toString());

        assertEquals(Cli.EXIT_OK, exit, o.out());
        assertTrue(o.out().contains("checked=5"), o.out());
        List<String> lines = Files.readAllLines(csv);
        assertEquals(4, lines.size(), "header plus the 3 kept results: " + o.out());
        assertTrue(o.out().contains("truncated at 3"), o.out());
    }

    @Test
    void maxResultsMustBeAPositiveWholeNumber() {
        Output o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--max-results", "0"));
        assertTrue(o.err().contains("--max-results must be at least 1"), o.err());

        Output o2 = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o2, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--max-results", "many"));
        assertTrue(o2.err().contains("--max-results must be a whole number"), o2.err());
    }
}
