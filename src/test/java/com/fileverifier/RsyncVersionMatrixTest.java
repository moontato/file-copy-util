package com.fileverifier;

import com.fileverifier.FileSyncEngine.JobResult;
import com.fileverifier.FileSyncEngine.JobType;
import com.fileverifier.FileSyncEngine.ProgressListener;
import com.fileverifier.FileSyncEngine.Selection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5.5: the engine reads rsync's itemize output, its {@code --info=progress2} percentage and its
 * {@code xfr#} completed-transfer count. All three are output formats another version may render
 * differently, so the behaviour the engine promises is checked against every rsync this machine has.
 * <p>
 * The installed rsync is always tested. Additional binaries come from the {@code FCU_RSYNC_BINARIES}
 * environment variable (comma separated absolute paths); anything that does not run is skipped rather
 * than failing the build, so CI on one distro's rsync and a local run against a matrix both work.
 */
class RsyncVersionMatrixTest {

    @TempDir
    Path root;

    static List<String> configuredBinaries() {
        List<String> binaries = new ArrayList<>();
        String pinned = System.getenv("FCU_RSYNC");
        // A pinned binary is the one under test, not an extra: this is how CI runs the matrix against a
        // specific rsync build instead of whatever the machine happens to ship.
        binaries.add(pinned == null || pinned.isBlank() ? "rsync" : pinned);
        String extra = System.getenv("FCU_RSYNC_BINARIES");
        if (extra != null) {
            for (String candidate : extra.split(",")) {
                if (!candidate.isBlank() && !binaries.contains(candidate.trim())) {
                    binaries.add(candidate.trim());
                }
            }
        }
        return binaries;
    }

    static Stream<String> runnableBinaries() {
        List<String> runnable = new ArrayList<>();
        for (String binary : configuredBinaries()) {
            if (canRun(binary)) {
                runnable.add(binary);
            }
        }
        String pinned = System.getenv("FCU_RSYNC");
        if (pinned != null && !pinned.isBlank() && !canRun(pinned)) {
            throw new IllegalStateException("FCU_RSYNC names " + pinned + " but it cannot be run."
                    + " A run that pins a rsync and then tests a different one is not a matrix.");
        }
        if (runnable.isEmpty()) {
            // Nothing to test against: the rest of the suite skips rsync-dependent tests the same way.
            return Stream.empty();
        }
        return runnable.stream();
    }

    static boolean canRun(String binary) {
        try {
            Process probe = new ProcessBuilder(List.of(binary, "--version"))
                .redirectErrorStream(true).start();
            int exit = probe.waitFor();
            probe.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static String versionOf(String binary) {
        try {
            Process probe = new ProcessBuilder(List.of(binary, "--version"))
                .redirectErrorStream(true).start();
            String text = new String(probe.getInputStream().readAllBytes());
            probe.waitFor();
            String first = text.lines().findFirst().orElse(binary);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("version\\s+([0-9][0-9.]*)")
                .matcher(first);
            return m.find() ? m.group(1) : first;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** A small tree that exercises each thing the engine reads out of rsync, for a few kilobytes of writes. */
    private Path[] fixture(boolean withBigFile) throws IOException {
        return fixture(Files.createTempDirectory(root, "run"), withBigFile);
    }

    private Path[] fixture(Path base, boolean withBigFile) throws IOException {
        Path source = Files.createDirectory(base.resolve("src"));
        Path dest = Files.createDirectory(base.resolve("dst"));
        TestSupport.write(source.resolve("keep.txt"), "same content\n");
        TestSupport.write(source.resolve("changed.txt"), "the new content\n");
        TestSupport.write(source.resolve("new.txt"), "not at the destination yet\n");
        Files.createDirectories(source.resolve("sub"));
        TestSupport.write(source.resolve("sub/deep.txt"), "needs its directory created\n");
        Files.createSymbolicLink(source.resolve("link.txt"), source.resolve("keep.txt"));
        TestSupport.write(dest.resolve("keep.txt"), "same content\n");
        TestSupport.write(dest.resolve("changed.txt"), "the old content\n");
        if (withBigFile) {
            // 4 MB of real data: enough that a throttled copy cannot finish, small enough to not matter.
            try (var out = Files.newOutputStream(source.resolve("bulk.bin"))) {
                out.write(new byte[4 * 1024 * 1024]);
            }
        }
        return new Path[] {source, dest};
    }

    private static final ScanOptions LINK_POLICY =
        ScanOptions.parse("", true, ScanOptions.SymlinkPolicy.LINK);

    private static final Selection ALL = TestSupport.selection(
        "changed.txt", "keep.txt", "link.txt", "new.txt", "sub/deep.txt");

    /**
     * The claim 5.5 exists to back: the same tree and the same selection produce the same counters
     * whatever rsync is installed. A difference here is a difference in how an itemize line or a
     * progress line was read, not a difference in the trees.
     */
    @Test
    void theSameTreeProducesTheSameCountersOnEveryRsyncVersion() throws Exception {
        Map<String, String> numbers = new LinkedHashMap<>();
        String expected = null;
        String expectedFrom = null;

        for (String binary : configuredBinaries()) {
            if (!canRun(binary)) {
                continue;
            }
            Path[] tree = fixture(false);
            FileSyncEngine engine = new FileSyncEngine();
            JobResult result;
            try {
                FileSyncEngine.setRsyncBinary(binary);
                result = engine.execute(TestSupport.request(tree[0], tree[1], JobType.COPY_AND_VERIFY,
                    ALL, Selection.none(), LINK_POLICY, false), new TestSupport.Recorder());
            } finally {
                FileSyncEngine.setRsyncBinary("rsync");
                engine.close();
            }

            String counters = String.format("copied=%d checked=%d matched=%d mismatched=%d missing=%d",
                result.filesCopied(), result.filesChecked(), result.filesMatched(),
                result.filesMismatched(), result.filesMissing());
            String label = binary + " (" + versionOf(binary) + ")";
            System.out.println("rsync matrix " + label + " -> " + counters);
            numbers.put(label, counters);

            // Invariants first: they hold per version, so a later version failing names itself.
            // keep.txt is already identical, so rsync transfers four of the five selected files.
            assertEquals(4, result.filesCopied(), label + " copied " + counters);
            assertEquals(5, result.filesChecked(), label + " checked " + counters);
            assertEquals(5, result.filesMatched(), label + " matched " + counters);
            assertEquals(0, result.filesMissing(), label + " missing " + counters);
            assertTrue(result.isSuccess(), label + " " + result.message());

            if (expected == null) {
                expected = counters;
                expectedFrom = label;
            } else {
                assertEquals(expected, counters,
                    label + " disagrees with " + expectedFrom + " on the same tree");
            }
        }
        assertFalse(numbers.isEmpty(), "no rsync binary was runnable");
    }

    @ParameterizedTest
    @MethodSource("runnableBinaries")
    void aDryRunWritesNothingOnAnyRsyncVersion(String binary) throws Exception {
        Path[] tree = fixture(false);
        FileSyncEngine engine = new FileSyncEngine();
        JobResult result;
        try {
            FileSyncEngine.setRsyncBinary(binary);
            result = engine.execute(TestSupport.request(tree[0], tree[1], JobType.COPY_AND_VERIFY,
                ALL, Selection.none(), LINK_POLICY, false, true), new TestSupport.Recorder());
        } finally {
            FileSyncEngine.setRsyncBinary("rsync");
            engine.close();
        }

        assertTrue(Files.exists(tree[1].resolve("changed.txt")), binary);
        assertEquals("the old content\n", Files.readString(tree[1].resolve("changed.txt")),
            binary + " dry run must not overwrite");
        assertFalse(Files.exists(tree[1].resolve("new.txt")), binary + " dry run must not create files");
        assertFalse(Files.exists(tree[1].resolve("sub")), binary + " dry run must not create directories");
        assertFalse(Files.exists(tree[1].resolve("link.txt")), binary + " dry run must not create links");
        assertTrue(result.message().contains("Dry run"), binary + " " + result.message());
    }

    /**
     * The xfr# cap is the one counter that depends on parsing a progress line. A throttled copy of one
     * file cannot finish, so on every version the honest answer is "0 files copied" and the job must not
     * claim a transfer that never completed.
     */
    @ParameterizedTest
    @MethodSource("runnableBinaries")
    void anInterruptedJobCountsOnlyTransfersThatFinished(String binary) throws Exception {
        Path[] tree = fixture(true);
        FileSyncEngine engine = new FileSyncEngine();
        JobResult result;
        try {
            FileSyncEngine.setRsyncBinary(binary);
            engine.setBandwidthLimitKbps(300);
            engine.setJobTimeoutMillis(1_500);
            result = engine.execute(TestSupport.request(tree[0], tree[1], JobType.COPY_AND_VERIFY,
                TestSupport.selection("bulk.bin"), Selection.none()), new TestSupport.Recorder());
        } finally {
            FileSyncEngine.setRsyncBinary("rsync");
            engine.close();
        }

        assertFalse(result.isSuccess(), binary + " should have been cut short");
        assertTrue(result.message().contains("timeout"), binary + " " + result.message());
        assertEquals(0, result.filesCopied(),
            binary + " " + versionOf(binary) + " claimed " + result.filesCopied()
                + " completed transfer(s) " + result.message());
        assertEquals(0, result.filesMatched(), binary + " verified a file that never arrived");
    }

    @ParameterizedTest
    @MethodSource("runnableBinaries")
    void progressReachesItsEndOnAnyRsyncVersion(String binary) throws Exception {
        Path[] tree = fixture(false);
        List<Integer> percentages = new ArrayList<>();
        ProgressListener listener = new ProgressListener() {
            @Override
            public void onProgress(int percent, String detail) {
                percentages.add(percent);
            }

            @Override
            public void onPhase(String phase) {
            }

            @Override
            public void onCompletion(JobResult result) {
            }
        };

        FileSyncEngine engine = new FileSyncEngine();
        JobResult result;
        try {
            FileSyncEngine.setRsyncBinary(binary);
            result = engine.execute(TestSupport.request(tree[0], tree[1], JobType.COPY_AND_VERIFY,
                ALL, Selection.none(), LINK_POLICY, false), listener);
        } finally {
            FileSyncEngine.setRsyncBinary("rsync");
            engine.close();
        }

        assertTrue(result.isSuccess(), binary + " " + result.message());
        int highest = percentages.stream().max(Integer::compare).orElse(-1);
        assertEquals(100, highest, binary + " progress ended at " + percentages);
        // Progress is coalesced to whole percents, so a 5 file job must not report hundreds of updates.
        assertTrue(percentages.size() <= 20, binary + " reported " + percentages.size() + " progress values");
    }

    @Test
    void theMatrixNamesEveryBinaryItActuallyTested() throws Exception {
        List<String> tested = new ArrayList<>();
        for (String binary : configuredBinaries()) {
            if (canRun(binary)) {
                tested.add(binary + " " + versionOf(binary));
            }
        }
        assertFalse(tested.isEmpty());
        assertTrue(tested.size() >= 1, "tested: " + tested);
        System.out.println("rsync binaries exercised: " + String.join(", ", tested));
    }
}
