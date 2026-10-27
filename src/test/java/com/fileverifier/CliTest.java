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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
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

    /**
     * Reports go here, not inside the two trees: a report written among the files a job copies and verifies
     * is refused unless the caller says they meant it, so the ordinary end-to-end cases need a place of
     * their own. That refusal is tested in WriteSafetyTest.
     */
    @TempDir
    Path reports;

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

    /**
     * Every CLI fixture runs through here, and every one of them checks the same thing afterwards: the
     * source tree is byte-for-byte what it was before the run. A copy tool that damages what it reads is
     * the worst failure it can have, and a test that only looks at the destination and the exit code would
     * never see it.
     */
    /**
     * The same, with one stand-in rsync as the process default for the duration of the call - and without the
     * source-immutability check, because a fixture that reaches here on purpose changes the source (an input
     * that vanishes mid-transfer is the case). The previous binary is restored exactly as it was.
     */
    private int runWithRsync(Output o, String binary, String... args) throws Exception {
        int[] exit = new int[1];
        TestSupport.withRsyncBinary(binary, () -> exit[0] = Cli.run(args, o.outStream, o.errStream));
        return exit[0];
    }

    private int run(Output o, String... args) throws Exception {
        Map<String, String> sourceBefore = TestSupport.digests(source);
        Map<String, String> destBefore = TestSupport.digests(dest);
        int exit = Cli.run(args, o.outStream, o.errStream);
        TestSupport.assertUnchanged(source, sourceBefore);
        if (!mentionsCopying(args)) {
            TestSupport.assertUnchanged(dest, destBefore);
        }
        return exit;
    }

    /** Fixtures that ask for a copy are allowed to change the destination; nothing may change the source. */
    private static boolean mentionsCopying(String... args) {
        for (String arg : args) {
            if ("--csv".equals(arg) || "--json".equals(arg)) {
                return true;   // a report is written into the destination directory by some fixtures
            }
        }
        return !containsAny(args, "--verify-only", "--dry-run");
    }

    private static boolean containsAny(String[] args, String... wanted) {
        for (String arg : args) {
            for (String w : wanted) {
                if (w.equals(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void assumeRsync() {
        assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
    }

    @Test
    void nothingSelectedIsAUsageErrorNotAnImplicitEverything()throws Exception {
        Output o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString()));
        assertTrue(o.err().contains("Nothing was selected"), o.err());
        assertTrue(o.err().contains("no files, not all files"), o.err());
    }

    @Test
    void sourceAndDestAndSelectionFormAreRequired()throws Exception {
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
    void unknownOptionsAndMissingValuesAreRefused()throws Exception {
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
    void excludeDestLeavesNamedFilesOutOfTheComparison() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");
        TestSupport.write(source.resolve("b.txt"), "b");
        TestSupport.write(source.resolve("c.txt"), "c");
        TestSupport.write(dest.resolve("a.txt"), "a");
        TestSupport.write(dest.resolve("b.txt"), "different");

        Output o = new Output();
        int exit = run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--verify-only", "--exclude-dest", "b.txt");

        // Without the flag this job would report the mismatched b.txt; the flag is the unticked row.
        assertEquals(Cli.EXIT_VERIFICATION_PROBLEMS, exit);
        assertTrue(o.out().contains("checked=2"), o.out());
        assertTrue(o.out().contains("mismatched=0"), o.out());
        assertTrue(o.out().contains("missing=1"), o.out());

        Cli.Options options = Cli.parse(new String[]{"--cli", "--source", source.toString(),
                "--dest", dest.toString(), "--all", "--verify-only", "--exclude-dest", "b.txt"});
        assertEquals(java.util.Set.of("b.txt"), options.request().destinationSelection().known());
        assertTrue(options.request().destinationSelection().selected().isEmpty());
    }

    @Test
    void excludeDestOnACopyIsAnnouncedInsteadOfQuietlyIgnored() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");
        TestSupport.write(source.resolve("b.txt"), "b");

        Output o = new Output();
        assertEquals(Cli.EXIT_OK, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--exclude-dest", "b.txt"));
        assertTrue(o.err().contains("does not apply to a copy"), o.err());
        assertTrue(Files.exists(dest.resolve("b.txt")), "a copy follows the source selection");
    }

    @Test
    void excludeDestCannotRemoveTheWholeJob() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");

        Output o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--verify-only", "--exclude-dest", "a.txt"));
        assertTrue(o.err().contains("nothing left to verify"), o.err());
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
    void excludeCannotBeUsedToSubtractFromAnExplicitFileList()throws Exception {
        Output o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--files", "a.txt,b.txt", "--exclude", "b.txt"));
        assertTrue(o.err().contains("--exclude only applies to --all"), o.err());
    }

    @Test
    void copyingWithoutComparingIsItsOwnMode() throws Exception {
        TestSupport.write(source.resolve("a.txt"), "a");

        Cli.Options options = Cli.parse(new String[]{"--cli", "--source", source.toString(),
            "--dest", dest.toString(), "--all", "--no-verify"});

        assertEquals(JobType.COPY_ONLY, options.request().jobType());
        assertTrue(options.request().jobType().copies());
        assertFalse(options.request().jobType().verifies());
    }

    @Test
    void verifyOnlyAndNoVerifyCannotBothBeAskedFor() throws Exception {
        // Parsed in order, the later flag would silently win, and the two describe opposite jobs.
        TestSupport.write(source.resolve("a.txt"), "a");
        Output o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
            "--all", "--verify-only", "--no-verify"));
        assertTrue(o.err().contains("contradict each other"), o.err());
    }

    @Test
    void aHeadlessCopyThatVerifiesNothingSaysSoInsteadOfReportingZeroChecks() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
        TestSupport.write(source.resolve("a.txt"), "a");
        TestSupport.write(source.resolve("b.txt"), "b");

        Output o = new Output();
        int exit = run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
            "--all", "--no-verify");

        assertEquals(0, exit, o.out() + o.err());
        assertTrue(o.out().contains("Copy complete: 2 file(s) copied"), o.out());
        assertTrue(o.out().contains("verification was off"), o.out());
        assertFalse(o.out().contains("checked=0 matched=0"),
            "totals full of zeroes read as a report of problems, not as an absence of comparison: " + o.out());
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
        Path csv = reports.resolve("report.csv");
        Path json = reports.resolve("report.json");

        Output o = new Output();
        int exit = run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--csv", csv.toString(), "--json", json.toString());

        assertEquals(Cli.EXIT_OK, exit);
        assertTrue(o.out().contains("Job complete"), o.out());
        assertTrue(o.out().contains("totals: checked=2 copied=2 matched=2 mismatched=0 missing=0"), o.out());
        assertTrue(o.out().contains("transfers: copied=2 would-copy=0 uncertain=0 not-transferred=0,"
                        + " rsync-announced=2 rsync-said-completed=2"),
                "the transfer figures are stated beside the verdict figures, not merged into them: " + o.out());
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
    void helpIsNotAnError()throws Exception {
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
    void bwlimitMustBeANumber()throws Exception {
        Output o = new Output();
        assertEquals(Cli.EXIT_USAGE, run(o, "--cli", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--bwlimit", "fast"));
        assertTrue(o.err().contains("--bwlimit must be KB/s"), o.err());
    }

    /**
     * {@code --strict} is for runs nobody is watching: the exit code has to say that the job did not do
     * everything it was asked to do, rather than only mentioning it in a line of output that scrolls away.
     * The switch changes the exit code and nothing else - the same job, the same copies, the same verdicts.
     */
    @Test
    void strictTurnsAnAbsentRequestedNameIntoANonZeroExitAndChangesNothingElse() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "alpha\n");

        Output plain = new Output();
        int plainExit = run(plain, "--cli", "--quiet", "--source", source.toString(), "--dest", dest.toString(),
                "--files", "a.txt,not-there.txt", "--no-verify");
        Output strict = new Output();
        int strictExit = run(strict, "--cli", "--quiet", "--strict", "--source", source.toString(),
                "--dest", dest.toString(), "--files", "a.txt,not-there.txt", "--no-verify");

        assertAll(
                () -> assertEquals(Cli.EXIT_OK, plainExit,
                        "without the switch this stays the documented behaviour: " + plain.out()),
                () -> assertEquals(Cli.EXIT_STRICT, strictExit, strict.out()),
                // Same job either way: the file that was there was copied, and both runs say which one was not.
                () -> assertTrue(Files.exists(dest.resolve("a.txt")), plain.out()),
                () -> assertTrue(Files.exists(dest.resolve("a.txt")), strict.out()),
                () -> assertTrue(plain.out().contains("not-there.txt"), plain.out()),
                () -> assertTrue(strict.out().contains("not-there.txt"), strict.out()),
                () -> assertTrue(plain.out().contains("were not found in the source"), plain.out()),
                () -> assertTrue(strict.out().contains("were not found in the source"), strict.out()));
    }

    /** The names have to be in the report as well as the terminal, or a scheduled run loses them. */
    @Test
    void theReportNamesTheRequestedFilesThatWereNotThere() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "alpha\n");
        Path csv = reports.resolve("report.csv");
        Path json = reports.resolve("report.json");

        Output o = new Output();
        int exit = run(o, "--cli", "--quiet", "--source", source.toString(), "--dest", dest.toString(),
                "--files", "a.txt,not-there.txt", "--no-verify", "--csv", csv.toString(),
                "--json", json.toString());
        assertEquals(Cli.EXIT_OK, exit, o.out());

        String csvText = Files.readString(csv);
        String jsonText = Files.readString(json);
        assertAll(
                () -> assertTrue(csvText.contains("# selectedAbsent,1"), csvText),
                () -> assertTrue(jsonText.contains("\"selectedButAbsent\": [\"not-there.txt\"]"), jsonText));
    }

    /**
     * Strict means "did the job do what it was asked", not "did anything interesting happen". A file that
     * needed no transfer, and a file reported as extra in the destination, are observations: turning them
     * into a failure would push people away from the switch that reports the real gaps.
     */
    /**
     * A name that was there when the work set was built and gone by the time {@code rsync} reached it. That is
     * not the same event as a typo in a list, and a copy-only job has no verdict that would notice it, so
     * without a figure of its own the run reports success.
     */
    @Test
    void strictFailsANameThatVanishedDuringTheTransferNotOnlyOneThatWasNeverThere() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("keep.txt"), "keep\n");
        TestSupport.write(source.resolve("gone.txt"), "gone\n");
        Path fakeDir = Files.createDirectories(reports.resolve("fake-vanish"));
        Path fake = TestSupport.vanishingRsync(fakeDir, "keep.txt", "gone.txt");

        Output plain = new Output();
        int plainExit = runWithRsync(plain, fake.toString(), "--cli", "--quiet", "--source", source.toString(),
                "--dest", dest.toString(), "--files", "keep.txt,gone.txt", "--no-verify");
        // The stand-in deleted the source file it was handed, so the second run has to be given the same
        // starting point: this fixture is about a name that disappears during the transfer, not about one that
        // was never there (which has a fixture of its own above).
        TestSupport.write(source.resolve("gone.txt"), "gone\n");
        Output strict = new Output();
        int strictExit = runWithRsync(strict, fake.toString(), "--cli", "--quiet", "--strict", "--source",
                source.toString(), "--dest", dest.toString(), "--files", "keep.txt,gone.txt", "--no-verify");

        assertAll(
                () -> assertEquals(Cli.EXIT_OK, plainExit,
                        "exit 24 stays a warning, as it has always been: " + plain.out()),
                () -> assertEquals(Cli.EXIT_STRICT, strictExit, strict.out()),
                () -> assertTrue(plain.out().contains("gone.txt"), plain.out()),
                () -> assertTrue(strict.out().contains("gone.txt"), strict.out()),
                // The file that did arrive, arrived. The vanished name is a figure of its own, and it is not
                // counted as an uncertain transfer or as a verification verdict.
                () -> assertTrue(Files.exists(dest.resolve("keep.txt")), plain.out()),
                () -> assertTrue(Files.exists(dest.resolve("keep.txt")), strict.out()),
                () -> assertTrue(plain.out().contains("vanished: 1 input file"), plain.out()),
                () -> assertTrue(strict.out().contains("vanished: 1 input file"), strict.out()),
                () -> assertTrue(plain.out().contains("verification was off"), plain.out()),
                () -> assertTrue(strict.out().contains("verification was off"), strict.out()));
    }

    /** A report that does not name the vanished name is a report that says the job was complete. */
    @Test
    void theReportNamesTheInputThatVanishedDuringTheTransfer() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("keep.txt"), "keep\n");
        TestSupport.write(source.resolve("gone.txt"), "gone\n");
        Path fakeDir = Files.createDirectories(reports.resolve("fake-vanish-json"));
        Path fake = TestSupport.vanishingRsync(fakeDir, "keep.txt", "gone.txt");
        Path csv = reports.resolve("report.csv");
        Path json = reports.resolve("report.json");

        Output o = new Output();
        int exit = runWithRsync(o, fake.toString(), "--cli", "--quiet", "--source", source.toString(),
                "--dest", dest.toString(), "--files", "keep.txt,gone.txt", "--no-verify",
                "--csv", csv.toString(), "--json", json.toString());
        assertEquals(Cli.EXIT_OK, exit, o.out());

        String csvText = Files.readString(csv);
        String jsonText = Files.readString(json);
        assertAll(
                () -> assertTrue(csvText.contains("# vanished,1"), csvText),
                () -> assertTrue(jsonText.contains("\"vanishedInputs\": [\"gone.txt\"]"), jsonText));
    }

    /**
     * A results list that holds every row is not truncated, even when it happens to be exactly as long as the
     * limit allows. Only an omitted row makes a report incomplete.
     */
    @Test
    void aResultsListThatExactlyReachesTheLimitDoesNotClaimToBeTruncated() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "a\n");
        Path csv = reports.resolve("report.csv");

        Output one = new Output();
        assertEquals(Cli.EXIT_OK, run(one, "--cli", "--quiet", "--source", source.toString(),
                "--dest", dest.toString(), "--all", "--max-results", "1", "--csv", csv.toString()), one.out());
        String oneCsv = Files.readString(csv);
        assertAll(
                () -> assertTrue(oneCsv.contains("# truncated,false"), oneCsv),
                () -> assertTrue(oneCsv.contains("# rowsListed,1"), oneCsv),
                () -> assertTrue(oneCsv.contains("# rowsOmitted,0"), oneCsv),
                () -> assertFalse(one.out().contains("truncated"), one.out()));

        // Two rows at a limit of two: still complete. Three rows at the same limit: one row is missing, and
        // that is the first run that may say so.
        TestSupport.write(source.resolve("b.txt"), "b\n");
        Path csvTwo = reports.resolve("report-two.csv");
        Output two = new Output();
        assertEquals(Cli.EXIT_OK, run(two, "--cli", "--quiet", "--source", source.toString(),
                "--dest", dest.toString(), "--all", "--max-results", "2", "--csv", csvTwo.toString()), two.out());
        String twoCsv = Files.readString(csvTwo);
        assertAll(
                () -> assertTrue(twoCsv.contains("# truncated,false"), twoCsv),
                () -> assertTrue(twoCsv.contains("# rowsOmitted,0"), twoCsv),
                () -> assertFalse(two.out().contains("truncated"), two.out()));

        TestSupport.write(source.resolve("c.txt"), "c\n");
        Path csvThree = reports.resolve("report-three.csv");
        Output three = new Output();
        assertEquals(Cli.EXIT_OK, run(three, "--cli", "--quiet", "--source", source.toString(),
                "--dest", dest.toString(), "--all", "--max-results", "2", "--csv", csvThree.toString()),
                three.out());
        String threeCsv = Files.readString(csvThree);
        assertAll(
                () -> assertTrue(threeCsv.contains("# truncated,true"), threeCsv),
                () -> assertTrue(threeCsv.contains("# rowsOmitted,1"), threeCsv),
                () -> assertTrue(three.out().contains("truncated"), three.out()));
    }

    @Test
    void strictDoesNotTurnAnObservationIntoAFailure() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "same\n");
        TestSupport.write(dest.resolve("a.txt"), "same\n");
        TestSupport.write(dest.resolve("extra.txt"), "left over\n");

        Output o = new Output();
        int exit = run(o, "--cli", "--quiet", "--strict", "--verify-only", "--source", source.toString(),
                "--dest", dest.toString(), "--all");

        // The two observations the switch must not punish: a file that needed no transfer at all (nothing was
        // copied, and that is the good outcome), and a file reported as extra in the destination.
        assertEquals(Cli.EXIT_OK, exit, o.out());
        assertTrue(o.out().contains("copied=0"), o.out());
        assertTrue(o.out().contains("matched=1"), o.out());
        assertTrue(o.out().contains("destination-only=1"), o.out());
    }

    /** A refusal is already a failed job; --strict does not invent a second way to fail it. */
    @Test
    void strictLeavesARefusedNameFailingTheWayItAlreadyDoes() throws Exception {
        assumeRsync();
        TestSupport.write(source.resolve("a.txt"), "alpha\n");

        Output o = new Output();
        int exit = run(o, "--cli", "--quiet", "--strict", "--source", source.toString(), "--dest", dest.toString(),
                "--files", "a.txt,../escape");

        assertEquals(Cli.EXIT_JOB_FAILED, exit, o.out());
        assertTrue(o.out().contains("outside the directory"), o.out());
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
        Path csv = reports.resolve("report.csv");

        Output o = new Output();
        int exit = run(o, "--cli", "--quiet", "--source", source.toString(), "--dest", dest.toString(),
                "--all", "--max-results", "3", "--csv", csv.toString());

        assertEquals(Cli.EXIT_OK, exit, o.out());
        assertTrue(o.out().contains("checked=5"), o.out());
        List<String> lines = Files.readAllLines(csv);
        // Rows are bounded by --max-results; the counters, and the statement of how much is missing, are not.
        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("status,path,")) {
                header = i;
            }
        }
        assertTrue(header >= 0, String.valueOf(lines));
        assertEquals(3, lines.size() - header - 1, "three rows kept: " + lines);
        assertTrue(lines.contains("# truncated,true"), String.valueOf(lines));
        assertTrue(lines.contains("# rowsListed,3"), String.valueOf(lines));
        assertTrue(lines.contains("# rowsOmitted,2"), String.valueOf(lines));
        assertTrue(lines.contains("# checked,5"), String.valueOf(lines));
        assertTrue(o.out().contains("truncated at 3"), o.out());
        assertTrue(o.out().contains("the per-file list holds 3 of the job's 5 row(s)"), o.out());
    }

    @Test
    void maxResultsMustBeAPositiveWholeNumber()throws Exception {
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
