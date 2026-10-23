package com.fileverifier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.Container;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The interface is checked here as a structure, not as pixels: the window is a panel
 * ({@link FileVerifierView}) so it can be built with no display server, which means CI can actually
 * assert that the controls exist and still behave.
 */
class FileVerifierViewTest {

    private final Preferences prefs = Preferences.userRoot().node("com/fileverifier");
    private Map<String, String> savedPrefs;

    @BeforeEach
    void rememberPreferences() {
        // The view restores and saves settings, so a test run must leave the developer's own values
        // exactly as it found them.
        savedPrefs = new java.util.HashMap<>();
        for (String key : List.of("sourceDir", "destDir", "ignorePatterns", "includeHidden",
            "symlinkPolicy", "timeoutSeconds", "dryRun", "detectExtra", "jobType", "verifyAfterCopy",
            "darkMode")) {
            savedPrefs.put(key, prefs.get(key, null));
        }
    }

    @AfterEach
    void restorePreferences() {
        for (Map.Entry<String, String> entry : savedPrefs.entrySet()) {
            if (entry.getValue() == null) {
                prefs.remove(entry.getKey());
            } else {
                prefs.put(entry.getKey(), entry.getValue());
            }
        }
    }

    @Test
    void theWholeWindowBuildsWithoutADisplay() throws Exception {
        FileVerifierView view = build();
        assertNotNull(view.getLayout());
        assertTrue(view.getComponents().length == 3,
            "the window is three regions: paths and options, the two lists, status plus output");
    }

    @Test
    void everyControlIsStillThere() throws Exception {
        FileVerifierView view = build();
        // A redesign that quietly drops a feature shows up here first.
        for (String name : List.of(
            "sourcePathField", "destPathField", "sourceBrowseButton", "destBrowseButton",
            "ignoreField", "hiddenCheck", "extraCheck", "symlinkCombo", "scanButton",
            "copyButton", "verifyOnlyButton", "verifyCheck", "workSetLabel", "sourceList", "destList",
            "sourceFilterField", "destFilterField", "progressBar", "statusLabel", "phaseLabel",
            "logArea", "outputTabs", "resultsTable", "onlyProblemsCheck", "exportCsvButton",
            "exportJsonButton", "dryRunCheck", "jobTimeoutField", "darkModeCheck", "runButton",
            "cancelButton", "versionLabel")) {
            assertNotNull(field(view, name), "missing control: " + name);
        }
    }

    @Test
    void modeChoiceIsOneControlAndTheWorkSetLineStartsHonest() throws Exception {
        FileVerifierView view = build();
        JToggleButton copy = (JToggleButton) field(view, "copyButton");
        JToggleButton verify = (JToggleButton) field(view, "verifyOnlyButton");
        JLabel workSet = (JLabel) field(view, "workSetLabel");

        copy.doClick();
        assertTrue(copy.isSelected());
        assertFalse(verify.isSelected());
        assertEquals("Work set: nothing scanned yet", workSet.getText());

        verify.doClick();
        assertTrue(verify.isSelected());
        assertFalse(copy.isSelected(), "both halves of a segmented control cannot be selected");
    }

    @Test
    void verificationIsASwitchOnTheCopyJobRatherThanAThirdMode() throws Exception {
        // Starting from no stored settings, so "what the default is" is a fact about the program and not
        // about whatever the last test or the developer left behind.
        prefs.remove("jobType");
        prefs.remove("verifyAfterCopy");

        FileVerifierView view = build();
        JToggleButton copy = (JToggleButton) field(view, "copyButton");
        JToggleButton verifyOnly = (JToggleButton) field(view, "verifyOnlyButton");
        JCheckBox verify = (JCheckBox) field(view, "verifyCheck");
        JButton run = (JButton) field(view, "runButton");

        assertTrue(verify.isSelected(),
            "the default job compares: not comparing is the user's choice to make, not the program's");
        assertEquals(FileSyncEngine.JobType.COPY_AND_VERIFY, field(view, "currentJobType"));
        assertEquals("Run job", run.getText());

        SwingUtilities.invokeAndWait(verify::doClick);
        assertEquals(FileSyncEngine.JobType.COPY_ONLY, field(view, "currentJobType"));
        assertEquals("Run copy", run.getText(),
            "a button that still says 'job' while quietly comparing nothing is the kind of vagueness"
                + " this tool is supposed to remove");

        SwingUtilities.invokeAndWait(verifyOnly::doClick);
        assertEquals(FileSyncEngine.JobType.VERIFY_ONLY, field(view, "currentJobType"));
        assertEquals("Run verify", run.getText());
        assertFalse(verify.isEnabled(),
            "the switch means nothing for a job that copies nothing, so it cannot look adjustable");

        SwingUtilities.invokeAndWait(copy::doClick);
        assertTrue(verify.isEnabled());
        assertFalse(verify.isSelected(), "the switch keeps the setting the user last chose");
        assertEquals(FileSyncEngine.JobType.COPY_ONLY, field(view, "currentJobType"));
    }

    @Test
    void theVerificationSwitchSurvivesRestart() throws Exception {
        prefs.remove("jobType");
        prefs.remove("verifyAfterCopy");

        FileVerifierView view = build();
        JCheckBox verify = (JCheckBox) field(view, "verifyCheck");
        SwingUtilities.invokeAndWait(verify::doClick);

        FileVerifierView restarted = build();
        assertFalse(((JCheckBox) field(restarted, "verifyCheck")).isSelected(),
            "a switch the user turned off should not silently turn itself back on next time");
        assertEquals(FileSyncEngine.JobType.COPY_ONLY, field(restarted, "currentJobType"));
    }

    @Test
    void copiedRowsReachTheTableAndAreNotCountedAsProblems() throws Exception {
        FileVerifierView view = build();
        JTable table = (JTable) field(view, "resultsTable");
        JCheckBox onlyProblems = (JCheckBox) field(view, "onlyProblemsCheck");
        javax.swing.JTabbedPane tabs = (javax.swing.JTabbedPane) field(view, "outputTabs");
        JButton exportCsv = (JButton) field(view, "exportCsvButton");

        // A copy with verification off has verdicts for nothing, and its whole record is the transfer. An
        // empty table for a job that wrote two files would read as "nothing happened".
        FileSyncEngine.JobResult result = new FileSyncEngine.JobResult(
            FileSyncEngine.JobStatus.COMPLETED,
            "Copy complete: 2 file(s) copied. Verification was turned off, so none of them were compared.",
            0, 0, 0, 0, 0, 2, 0,
            List.of(new FileSyncEngine.FileOutcome("a.txt", "COPIED", null, null),
                new FileSyncEngine.FileOutcome("b.txt", "COPIED", null, null)),
            List.of(), List.of());

        java.lang.reflect.Method showResults = FileVerifierView.class
            .getDeclaredMethod("showResults", FileSyncEngine.JobResult.class);
        showResults.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try {
                showResults.invoke(view, result);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertEquals(2, table.getRowCount(), "what was written is this job's record");
        assertEquals("COPIED", table.getValueAt(0, 0));
        assertEquals("", table.getValueAt(0, 2), "no hashes were computed, so the row cannot invent any");
        assertEquals("Results (2)", tabs.getTitleAt(1));
        assertTrue(exportCsv.isEnabled(), "an export of what was copied is the point of listing it");

        SwingUtilities.invokeAndWait(() -> {
            onlyProblems.setSelected(true);
            try {
                showResults.invoke(view, result);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals(0, table.getRowCount(),
            "'only problems' means a comparison that failed; a file that was merely copied is not one");
    }

    @Test
    void resultsTableHasAHeaderSoColumnsCanBeSorted() throws Exception {
        FileVerifierView view = build();
        JTable table = (JTable) field(view, "resultsTable");
        JScrollPane scroll = (JScrollPane) table.getParent().getParent();
        assertNotNull(scroll.getRowHeader(),
            "JScrollPane(JTable) does not attach the header on its own, and a table without column "
                + "titles has no way to reach its sorter");
        assertNotNull(table.getRowSorter());
        // "Transfer" is the fifth column and the last one, so anything that already read the first four
        // columns still reads the same thing.
        assertEquals(5, table.getColumnCount());
        assertEquals(java.util.List.of("Status", "Path", "Source SHA-256", "Destination SHA-256", "Transfer"),
            java.util.List.of(table.getColumnName(0), table.getColumnName(1), table.getColumnName(2),
                table.getColumnName(3), table.getColumnName(4)));
    }

    @Test
    void filtersAndOptionsAreIndependentOfEachOther() throws Exception {
        FileVerifierView view = build();
        JCheckBox hidden = (JCheckBox) field(view, "hiddenCheck");
        JCheckBox extra = (JCheckBox) field(view, "extraCheck");
        JCheckBox dryRun = (JCheckBox) field(view, "dryRunCheck");
        // These are four separate decisions; a click on one must not imply another.
        boolean hiddenBefore = hidden.isSelected();
        extra.setSelected(!extra.isSelected());
        assertEquals(hiddenBefore, hidden.isSelected());
        assertFalse(dryRun.isSelected());
    }

    @Test
    void shutdownReleasesTheEngineSoNoJobOutlivesTheWindow() throws Exception {
        FileVerifierView view = build();
        FileSyncEngine engine = (FileSyncEngine) field(view, "engine");
        view.shutdown();
        assertFalse(engine.isRunning());
        FileSyncEngine.JobRequest request = new FileSyncEngine.JobRequest(
            Path.of("."), Path.of("."), FileSyncEngine.JobType.VERIFY_ONLY,
            FileSyncEngine.Selection.of(List.of("a.txt")), FileSyncEngine.Selection.of(List.of("a.txt")),
            ScanOptions.DEFAULTS, false, false);
        assertFalse(engine.start(request, new FileSyncEngine.ProgressListener() {
            @Override
            public void onCompletion(FileSyncEngine.JobResult result) {
                // Nothing to check here: start() must refuse before any callback runs.
            }
        }), "a closed engine must refuse new work");
    }

    /**
     * Dark mode used to leave ordinary buttons painted light while their labels were switched to the
     * dark palette's light text - invisible text. A control with no background of its own inherits one
     * from its parent, so the regions have to follow the palette too. This is the regression test for
     * that.
     */
    @Test
    void aThemeSwitchLeavesEveryControlReadable() throws Exception {
        FileVerifierView view = build();
        assertReadable(view);   // as-built, before any theme switch
        JCheckBox dark = (JCheckBox) field(view, "darkModeCheck");
        boolean wasDark = dark.isSelected();
        try {
            dark.setSelected(false);
            UiTheme.install(false);
            UiTheme.restyle(view);
            assertReadable(view);

            dark.setSelected(true);
            UiTheme.install(true);
            com.formdev.flatlaf.FlatLaf.updateUI();
            UiTheme.restyle(view);
            assertReadable(view);
        } finally {
            UiTheme.install(false);
            UiTheme.restyle(view);
            dark.setSelected(wasDark);
        }
    }

    /** Every visible control's own label must clear 4.5:1 against whatever it sits on. */
    private static void assertReadable(FileVerifierView view) throws Exception {
        java.util.List<String> controls = java.util.List.of(
            "sourceBrowseButton", "scanButton", "runButton", "cancelButton", "exportCsvButton",
            "hiddenCheck", "extraCheck", "dryRunCheck", "darkModeCheck", "statusLabel", "workSetLabel",
            "sourcePathField");
        for (String name : controls) {
            javax.swing.JComponent component = (javax.swing.JComponent) field(view, name);
            java.awt.Color foreground = component.getForeground();
            java.awt.Color background = UiTheme.backgroundBehind(component);
            double ratio = UiTheme.contrastRatio(foreground, background);
            assertTrue(ratio >= 4.5,
                name + " (" + component.getClass().getSimpleName() + ") label " + foreground + " on "
                    + background + " is " + String.format("%.2f", ratio) + ":1 in "
                    + (UiTheme.palette() == UiTheme.DARK ? "dark" : "light") + " mode");
        }
    }

    /** The colour rule of the redesign: only UiTheme is allowed to name a colour. */
    @Test
    void noColourLiteralsOutsideTheTheme() throws IOException {
        Path root = Path.of("src", "main", "java", "com", "fileverifier");
        if (!Files.isDirectory(root)) {
            return; // not running from the project base directory
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.getFileName().toString().equals("UiTheme.java"))
                .forEach(p -> {
                    try {
                        for (String line : Files.readAllLines(p)) {
                            if (line.contains("new Color(")) {
                                fail(p.getFileName() + " hard-codes a colour: " + line.trim());
                            }
                        }
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
        }
    }

    /** Swing wants its widgets built on the event dispatch thread; the tests honour that. */
    @Test
    void directoriesAreTypedInNotOnlyChosenWithABrowseButton(@org.junit.jupiter.api.io.TempDir Path tmp)
        throws Exception {
        Path source = Files.createDirectories(tmp.resolve("src"));
        Path destination = Files.createDirectory(tmp.resolve("dst"));
        Files.writeString(source.resolve("a.txt"), "a");
        Files.writeString(destination.resolve("b.txt"), "b");

        FileVerifierView view = build();
        JTextField sourceField = (JTextField) field(view, "sourcePathField");
        JTextField destField = (JTextField) field(view, "destPathField");
        JList<?> sourceList = (JList<?>) field(view, "sourceList");
        JTextArea logArea = (JTextArea) field(view, "logArea");

        assertTrue(sourceField.isEditable() && destField.isEditable(),
            "a directory whose path you already know should not need a chooser opened first");

        SwingUtilities.invokeAndWait(() -> {
            sourceField.setText(source.toString());
            destField.setText(destination.toString());
        });
        // What Enter does.
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.event.ActionListener listener : sourceField.getActionListeners()) {
                listener.actionPerformed(new java.awt.event.ActionEvent(sourceField,
                    java.awt.event.ActionEvent.ACTION_PERFORMED, null));
            }
        });
        waitUntil(() -> sourceList.getModel().getSize() == 1, "Enter did not load the typed directory");

        // The rows on screen are ticks against paths in the directory the field used to name. Changing the
        // field has to drop them, or a job would run with a selection from somewhere else.
        SwingUtilities.invokeAndWait(() -> destField.setText(destination.resolve("elsewhere").toString()));
        assertEquals(0, sourceList.getModel().getSize(),
            "a changed path must not leave the previous directory's rows behind");
        assertTrue(logArea.getText().contains("Path changed"), logArea.getText());
    }

    @Test
    void aTypedPathIsReadTheWayAShellWould() {
        Path home = Path.of(System.getProperty("user.home", "")).toAbsolutePath().normalize();
        assertEquals(home, FileVerifierView.userPath("~"));
        assertTrue(FileVerifierView.userPath("~/notes/2026").startsWith(home),
            "a leading ~ means the home directory, not a directory called ~");
        assertTrue(FileVerifierView.userPath("notes").isAbsolute(),
            "a relative path is taken from where the program was started");
        assertEquals(Path.of("/data/src"), FileVerifierView.userPath("  /data/src  "));
        assertNull(FileVerifierView.userPath("   "));
        assertNull(FileVerifierView.userPath(null));
    }

    @Test
    void theWindowShowsTheVersionItWasBuiltFrom() throws Exception {
        FileVerifierView view = build();
        JLabel label = (JLabel) field(view, "versionLabel");
        String version = AppVersion.version();
        assertNotNull(version, "a build that stamps no version leaves the window with nothing truthful to show");
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+.*"), "not a version: " + version);
        assertEquals("v" + version, label.getText());
        assertTrue(label.getFont().getSize() <= UiTheme.baseFont().getSize(),
            "a version stamp is furniture, not a headline");
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String message) throws Exception {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), message);
    }


    /**
     * An acceptance answers a question, and the question names the job as well as the names at risk. So an
     * answer cannot be carried to another pair of directories, to a bigger selection, or to the next pair of
     * names the engine finds.
     */
    @Test
    void anAcceptedCollisionSetCoversTheJobItWasGivenForAndNoOther(@TempDir Path roots) throws Exception {
        Path src1 = Files.createDirectories(roots.resolve("src1"));
        Path src2 = Files.createDirectories(roots.resolve("src2"));
        Path dest = Files.createDirectories(roots.resolve("dest"));
        for (Path src : List.of(src1, src2)) {
            TestSupport.write(src.resolve("a.TXT"), "upper");
            TestSupport.write(src.resolve("a.txt"), "lower");
            TestSupport.write(src.resolve("c.TXT"), "upper");
            TestSupport.write(src.resolve("c.txt"), "lower");
        }
        FileVerifierView view = build();
        List<String> asked = new ArrayList<>();
        List<Boolean> answers = new ArrayList<>(List.of(false, true, false));
        view.setCollisionConfirmation(question -> {
            asked.add(question);
            return answers.get(asked.size() - 1);
        });

        typePaths(view, src1, dest);
        view.scanRequested();
        awaitScan(view);
        Object sourceModel = modelOf(view, "sourceModel");
        SwingUtilities.invokeAndWait(() -> tickOnly(sourceModel, List.of("a.TXT", "a.txt")));
        awaitEventQueue();
        java.nio.file.Files.createDirectories(roots.resolve("fake"));
        view.engineInUse().useRsync(TestSupport.fakeRsync(roots.resolve("fake"), "", 0).toString());

        view.runRequested();
        waitUntil(() -> asked.size() >= 1, "the job was refused and the window asked about it");
        awaitEventQueue();
        assertEquals(1, asked.size(), "the window asked once, and only once: " + logText(view));
        assertFalse(view.engineInUse().isRunning(), "refusing to answer really stopped the job");
        assertTrue(asked.get(0).contains("a.TXT"),
            "the question names the files at risk: " + asked.get(0));
        assertFalse(asked.get(0).toLowerCase().contains("identical"),
            "and it does not claim they are the same file: " + asked.get(0));

        // Nothing has been accepted yet: not for this job, not for any other.
        FileSyncEngine engine = view.engineInUse();
        String thisJob = FileSyncEngine.jobIdentityFor(view.requestInUse(), List.of("a.TXT", "a.txt"));
        assertFalse(engine.collisionAcceptanceCovers(thisJob, engine.collisionsDetectedByLastJob()),
            "a question answered no leaves nothing on record");

        // The same job again, this time answered yes. It is asked again rather than being waved through from
        // the previous refusal, and the answer belongs to this request.
        view.runRequested();
        waitUntil(() -> asked.size() >= 2,
            "the same job is asked about again rather than waved through on a saved answer");
        waitUntil(() -> logNow(view).contains("JOB COMPLETED"), "the answered job finished");
        awaitEventQueue();
        assertEquals(2, asked.size(), "the same job, answered no and then yes: asked again, not remembered");
        assertTrue(logText(view).contains("JOB COMPLETED"),
            "and the answered job did go ahead: " + logText(view));

        assertTrue(engine.collisionAcceptanceCovers(thisJob, engine.collisionsDetectedByLastJob()),
            "the answer covers the job it was given for");

        // The same two relative names under different roots: different files, so a different question, and the
        // yes does not reach them.
        FileSyncEngine.JobRequest elsewhere = TestSupport.request(src2, dest,
            FileSyncEngine.JobType.COPY_ONLY,
            new FileSyncEngine.Selection(Set.of("a.TXT", "a.txt"), Set.of("a.TXT", "a.txt")),
            new FileSyncEngine.Selection(Set.of(), Set.of()));
        assertFalse(engine.collisionAcceptanceCovers(
            FileSyncEngine.jobIdentityFor(elsewhere, List.of("a.TXT", "a.txt")),
            engine.collisionsDetectedByLastJob()),
            "the same relative names in another directory are another question, asked again");

        // The same roots with more files in the selection: a different job, so a different question.
        FileSyncEngine.JobRequest grown = TestSupport.request(src1, dest,
            FileSyncEngine.JobType.COPY_ONLY,
            new FileSyncEngine.Selection(Set.of("a.TXT", "a.txt", "c.TXT", "c.txt"),
                Set.of("a.TXT", "a.txt", "c.TXT", "c.txt")),
            new FileSyncEngine.Selection(Set.of(), Set.of()));
        assertFalse(engine.collisionAcceptanceCovers(
            FileSyncEngine.jobIdentityFor(grown, List.of("a.TXT", "a.txt", "c.TXT", "c.txt")),
            engine.collisionsDetectedByLastJob()),
            "a job asked to handle more files is another question again");

        // The next pair of colliding names is a fresh question, in the same window, for the same kind of job.
        SwingUtilities.invokeAndWait(() -> tickOnly(sourceModel, List.of("c.TXT", "c.txt")));
        awaitEventQueue();
        view.runRequested();
        waitUntil(() -> asked.size() >= 3, "the second collision set was refused and asked about");
        awaitEventQueue();
        assertEquals(3, asked.size(), "the next collision set is asked about on its own merits");
        assertTrue(asked.get(2).contains("c.TXT"), "and it names those: " + asked.get(2));
        assertFalse(view.engineInUse().isRunning(), "and the earlier yes did not carry into it");
        view.engineInUse().close();
    }

    /**
     * Run means something only about the lists the user can see. If nothing has been read, the selection is
     * empty, and an empty selection is no files - never "everything, hopefully".
     */
    @Test
    void runIsRefusedUntilTheDirectoriesHaveBeenScanned(@TempDir Path roots) throws Exception {
        FileVerifierView view = build();
        Path src = Files.createDirectories(roots.resolve("src"));
        Path dest = Files.createDirectories(roots.resolve("dest"));
        TestSupport.write(src.resolve("a.txt"), "1");

        typePaths(view, src, dest);
        assertEquals("Nothing has been scanned yet. Scan the two directories first: an empty selection means no"
            + " files, not all files, and this program will not guess what you meant.", view.runRefusal());
        assertEquals(0, view.loadedSourceRows());
        view.runRequested();
        assertEquals(null, view.requestInUse(), "and Run really did refuse: nothing was started");
        assertTrue(logText(view).contains("Refused to start"),
            "and the refusal is in the log, not only in a dialog: " + logText(view));

        view.scanRequested();
        awaitScan(view);
        assertEquals(null, view.runRefusal(), "a scan of the two directories named now is enough");
        assertEquals(1, view.loadedSourceRows());
        view.engineInUse().close();
    }

    /**
     * While a scan is reading the directories, the lists on screen are half-built. A job captured now would
     * run against a set of files nobody chose.
     */
    @Test
    void aScanStillUnderwayCannotBeRunAgainst(@TempDir Path roots) throws Exception {
        FileVerifierView view = build();
        Path src = Files.createDirectories(roots.resolve("src"));
        Path dest = Files.createDirectories(roots.resolve("dest"));
        TestSupport.write(src.resolve("a.txt"), "1");
        typePaths(view, src, dest);

        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        view.setScannerForTesting((root, options) -> {
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException wasInterrupted) {
                Thread.currentThread().interrupt();
            }
            return DirectoryScanner.scanFiles(root, options);
        });

        view.scanRequested();
        assertTrue(view.scanInProgress(), "the scan is reading the directories");
        assertTrue(view.runRefusal() != null && view.runRefusal().contains("still running"),
            "the lists are not complete, so a job started now would use half of them: " + view.runRefusal());
        view.runRequested();
        assertEquals(null, view.requestInUse(), "and Run really refused: no request was captured");
        assertFalse(view.engineInUse().isRunning(), "and no job was started anyway");
        release.countDown();
        awaitScan(view);
        assertFalse(view.engineInUse().isRunning());
        view.engineInUse().close();
    }

    /**
     * The user typed a new directory into a field. The rows underneath belonged to the directory that used to
     * be named there, so they are gone - and Run has nothing to work from until the new one is scanned.
     */
    @Test
    void editingATypedPathTakesTheLoadedListsWithIt(@TempDir Path roots) throws Exception {
        FileVerifierView view = build();
        Path first = Files.createDirectories(roots.resolve("first"));
        Path second = Files.createDirectories(roots.resolve("second"));
        Path dest = Files.createDirectories(roots.resolve("dest"));
        TestSupport.write(first.resolve("old.txt"), "1");
        typePaths(view, first, dest);
        view.scanRequested();
        awaitScan(view);
        assertEquals(1, view.loadedSourceRows());

        JTextField sourceField = (JTextField) field(view, "sourcePathField");
        SwingUtilities.invokeAndWait(() -> sourceField.setText(second.toString()));
        awaitEventQueue();
        assertEquals(0, view.loadedSourceRows(), "the list went with the directory it came from");
        assertEquals("Nothing has been scanned yet. Scan the two directories first: an empty selection means no"
            + " files, not all files, and this program will not guess what you meant.", view.runRefusal(),
            "the list belonged to the directory that used to be named, so there is nothing to run from");
        view.runRequested();
        assertEquals(null, view.requestInUse(),
            "and Run really refused rather than running a dead list against a new directory");
        view.engineInUse().close();
    }

    /**
     * The scan options decide which files the lists can hold. Change them and the rows on screen describe a
     * different set of files, so Run is refused until the scan is redone.
     */
    @Test
    void changingTheScanOptionsAfterAScanRefusesTheRunUntilTheScanIsRedone(@TempDir Path roots)
            throws Exception {
        FileVerifierView view = build();
        Path src = Files.createDirectories(roots.resolve("src"));
        Path dest = Files.createDirectories(roots.resolve("dest"));
        TestSupport.write(src.resolve("a.txt"), "1");
        Files.createSymbolicLink(src.resolve("link.txt"), src.resolve("a.txt"));
        typePaths(view, src, dest);
        view.scanRequested();
        awaitScan(view);
        assertEquals(1, view.loadedSourceRows(), "the link was skipped, as the options said");
        assertEquals(null, view.runRefusal(), "the lists match the options on screen");

        JComboBox<?> policyCombo = (JComboBox<?>) field(view, "symlinkCombo");
        SwingUtilities.invokeAndWait(() -> policyCombo.setSelectedItem(ScanOptions.SymlinkPolicy.FOLLOW));
        awaitEventQueue();
        assertTrue(view.runRefusal().startsWith("The scan options changed since the scan"),
            view.runRefusal());
        view.runRequested();
        assertEquals(null, view.requestInUse(),
            "and Run really refused: the job would have covered files that are not listed");

        view.scanRequested();
        awaitScan(view);
        assertEquals(2, view.loadedSourceRows(), "the redo sees the link");
        assertEquals(null, view.runRefusal(), "and now the lists and the options agree again");
        view.engineInUse().close();
    }

    /**
     * A scan belongs to the window state it was started from. A result carrying an older scan number must not
     * replace the rows on screen: that would list one directory's files under another directory's name, and a
     * job started from them would write the wrong files.
     */
    @Test
    void aScanResultCarryingAnOlderScanNumberIsDiscardedAndSaidSo(@TempDir Path roots) throws Exception {
        FileVerifierView view = build();
        Path first = Files.createDirectories(roots.resolve("first"));
        Path second = Files.createDirectories(roots.resolve("second"));
        Path dest = Files.createDirectories(roots.resolve("dest"));
        TestSupport.write(first.resolve("old.txt"), "1");
        TestSupport.write(second.resolve("new.txt"), "2");

        ScanOptions options = ScanOptions.parse("", true, ScanOptions.SymlinkPolicy.SKIP);
        typePaths(view, first, dest);
        long generation = view.currentScanGeneration();
        view.applyFinishedScan(generation, first, dest, options);
        assertEquals(1, view.loadedSourceRows());
        assertEquals(List.of("old.txt"), relPaths(view, "sourceModel"));

        // The user moves on to another directory. The list goes with the directory it belonged to, and a scan
        // carrying the old number is not allowed to bring it back: that would relabel first/'s files as
        // second/'s.
        typePaths(view, second, dest);
        assertEquals(List.of(), relPaths(view, "sourceModel"), "leaving the directory took its list with it");
        view.applyFinishedScan(generation, first, dest, options);
        assertEquals(1, view.discardedScanCount(), "the older result was thrown away, not applied");
        assertEquals(List.of(), relPaths(view, "sourceModel"),
            "and the stale rows did not reappear under the new directory's name");
        assertTrue(logText(view).contains("discarded"),
            "and the window said what happened to it: " + logText(view));

        view.applyFinishedScan(view.currentScanGeneration(), second, dest, options);
        assertEquals(List.of("new.txt"), relPaths(view, "sourceModel"), "the current scan's rows do land");
        assertEquals(1, view.discardedScanCount(), "one discarded, not two");
        view.engineInUse().close();
    }

    /**
     * Ticking and unticking belongs to the window; the job belongs to the request that was captured when Run
     * was pressed. Changing a tick mid-job changes what the next job would do, not this one.
     */
    @Test
    void aRunningJobKeepsTheSelectionThatWasTickedWhenRunWasPressed(@TempDir Path roots) throws Exception {
        TestSupport.assertNoProcessesRunning("rsync", "before");
        FileVerifierView view = build();
        Path src = Files.createDirectories(roots.resolve("src"));
        Path dest = Files.createDirectories(roots.resolve("dest"));
        TestSupport.write(src.resolve("a.txt"), "1");
        TestSupport.write(src.resolve("b.txt"), "2");
        typePaths(view, src, dest);
        view.scanRequested();
        awaitScan(view);

        java.nio.file.Files.createDirectories(roots.resolve("fake"));
        Path fake = TestSupport.hangingFakeRsync(roots.resolve("fake"), "", "");
        FileSyncEngine engine = view.engineInUse();
        engine.useRsync(fake.toString());

        view.runRequested();
        awaitEventQueue();
        FileSyncEngine.JobRequest captured = view.requestInUse();
        assertNotNull(captured, "Run captured one request");
        assertEquals(Set.copyOf(List.of("a.txt", "b.txt")), captured.sourceSelection().selected(),
            "Run captured the ticks that were on screen at that moment");

        Object untickEverything = modelOf(view, "sourceModel");
        SwingUtilities.invokeAndWait(() -> tickOnly(untickEverything, List.<String>of()));
        awaitEventQueue();

        assertEquals(Set.copyOf(List.of("a.txt", "b.txt")), view.requestInUse().sourceSelection().selected(),
            "the job in flight is still the job that was asked for, ticks and all");
        engine.cancel();
        awaitEventQueue();
        TestSupport.assertNoProcessesRunning("rsync", "after cancelling");
        engine.close();
    }

    /** The log as it stands, for a wait condition that cannot throw. */
    private static String logNow(FileVerifierView view) {
        try {
            return logText(view);
        } catch (Exception shouldNotHappen) {
            return "";
        }
    }

    private static String logText(FileVerifierView view) throws Exception {
        JTextArea area = (JTextArea) field(view, "logArea");
        return area.getText();
    }

    /**
     * A report belongs to the job it describes. The rule about where it may be written - never over an existing
     * file, and not among the files that job copied unless somebody decides otherwise - is a rule about the
     * directories <i>that job</i> worked in, so it is judged by them and not by whatever the two fields happen
     * to say when somebody presses Export.
     */
    @Test
    void aReportOfACompletedJobIsJudgedByTheDirectoriesThatJobWorkedIn(@TempDir Path roots) throws Exception {
        FileVerifierView view = build();
        Path src = Files.createDirectories(roots.resolve("src"));
        Path dest = Files.createDirectories(roots.resolve("dest"));
        Path elsewhere = Files.createDirectories(roots.resolve("elsewhere"));
        TestSupport.write(src.resolve("a.txt"), "one");
        typePaths(view, src, dest);
        view.scanRequested();
        awaitScan(view);
        view.runRequested();
        awaitJob(view);
        FileVerifierView.CompletedJob jobA = view.completedJobInUse();
        assertEquals(src, jobA.request().sourceDir(), "the window knows which request produced these rows");
        assertEquals(1, jobA.result().outcomes().size());

        // The user moves on: the fields now name another pair of directories. The finished job's record - and
        // the rules about where its report may go - stay with the pair it actually worked in.
        typePaths(view, elsewhere, roots.resolve("nowhere"));
        awaitEventQueue();

        ReportExporter.ReportRefusal insideTheCopiedTree = assertThrows(ReportExporter.ReportRefusal.class,
            () -> view.writeCompletedReport(dest.resolve("report.csv"), "csv", false),
            "a report among the files that job copied is still refused, although no field names that pair any"
                + " more");
        assertTrue(insideTheCopiedTree.getMessage().contains("inside"),
            insideTheCopiedTree.getMessage());
        assertTrue(Files.notExists(dest.resolve("report.csv")),
            "and the refusal really wrote nothing: the copied tree holds only a.txt");
        assertEquals(1, countFiles(dest), "the copied file is still the only thing there");

        // The other direction: a path inside the directories the fields name *now* is outside the job that ran,
        // so it is not held to that job's rules. Judging by the fields instead would refuse this, and the user
        // would be told their own report directory was off limits.
        Path written = view.writeCompletedReport(elsewhere.resolve("report.csv"), "csv", false);
        assertTrue(Files.exists(written), "the report went where it was asked, outside the job's trees");

        JTable results = (JTable) field(view, "resultsTable");
        assertEquals(1, results.getRowCount(),
            "and the finished job's rows are still on screen: editing a path field does not un-happen a job"
                + " that already ran");
        view.engineInUse().close();
    }

    /**
     * A request the engine refuses before it starts must not become the window's job. If it did, the report of
     * the job that really ran would be judged by the other request's directories - and might be written where
     * it has no business being.
     */
    @Test
    void aRequestThatNeverStartedDoesNotRepointTheReportOfTheJobThatRan(@TempDir Path roots) throws Exception {
        FileVerifierView view = build();
        Path src = Files.createDirectories(roots.resolve("src"));
        Path dest = Files.createDirectories(roots.resolve("dest"));
        TestSupport.write(src.resolve("a.txt"), "one");
        typePaths(view, src, dest);
        view.scanRequested();
        awaitScan(view);
        view.runRequested();
        awaitJob(view);
        FileVerifierView.CompletedJob jobA = view.completedJobInUse();

        // A second request the engine refuses before anything is written: the destination is inside the source.
        Path nested = Files.createDirectories(src.resolve("nested"));
        typePaths(view, src, nested);
        view.scanRequested();
        awaitScan(view);
        view.runRequested();
        awaitEventQueue();
        assertFalse(view.engineInUse().isRunning(), "the second request was refused before it started");
        assertTrue(logNow(view).contains("preflight"), "and said so: " + logNow(view));

        assertSame(jobA, view.completedJobInUse(),
            "the refused request did not take over the record of the job that ran");
        assertEquals(dest, view.completedJobInUse().request().destDir(),
            "the report is still judged by the first job's destination");
        assertThrows(ReportExporter.ReportRefusal.class,
            () -> view.writeCompletedReport(dest.resolve("report.csv"), "csv", false),
            "still refused among the copied files, even though the fields now name somewhere else");
        assertTrue(Files.notExists(dest.resolve("report.csv")), "and nothing was written there");
        view.engineInUse().close();
    }

    /** Wait for the job started by Run to finish and have its result paired with its request. */
    private static void awaitJob(FileVerifierView view) throws Exception {
        waitUntil(() -> view.completedJobInUse() != null && !view.engineInUse().isRunning(),
            "the job came back and was paired with the request it ran from");
        awaitEventQueue();
    }

    private static int countFiles(Path directory) throws Exception {
        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            return (int) entries.count();
        }
    }

    private static Object modelOf(FileVerifierView view, String name) throws Exception {
        return field(view, name);
    }

    /** Tick exactly the named rows in a list model, leaving the rest unticked. */
    @SuppressWarnings("unchecked")
    private static void tickOnly(Object model, List<String> names) {
        try {
            java.lang.reflect.Method all = model.getClass().getDeclaredMethod("all");
            all.setAccessible(true);
            java.lang.reflect.Method toggle = model.getClass().getDeclaredMethod("toggle", int.class);
            toggle.setAccessible(true);
            java.lang.reflect.Method unticked = model.getClass().getDeclaredMethod("untickedPaths");
            unticked.setAccessible(true);
            List<?> rows = (List<?>) all.invoke(model);
            Set<String> currentlyUnticked = (Set<String>) unticked.invoke(model);
            for (int i = 0; i < rows.size(); i++) {
                java.lang.reflect.Method entry = rows.get(i).getClass().getDeclaredMethod("entry");
                entry.setAccessible(true);
                String rel = ((FileEntry) entry.invoke(rows.get(i))).relPath();
                boolean want = names.contains(rel);
                boolean isTicked = !currentlyUnticked.contains(rel);
                if (want != isTicked) {
                    toggle.invoke(model, i);
                }
            }
        } catch (ReflectiveOperationException couldNot) {
            throw new IllegalStateException(couldNot);
        }
    }

    /** The relative paths a list model is holding, sorted. */
    private static List<String> relPaths(FileVerifierView view, String modelName) throws Exception {
        Object model = field(view, modelName);
        java.lang.reflect.Method all = model.getClass().getDeclaredMethod("all");
        all.setAccessible(true);
        List<String> names = new ArrayList<>();
        for (Object row : (List<?>) all.invoke(model)) {
            java.lang.reflect.Method entry = row.getClass().getDeclaredMethod("entry");
            entry.setAccessible(true);
            names.add(((FileEntry) entry.invoke(row)).relPath());
        }
        java.util.Collections.sort(names);
        return names;
    }

    /** Write the two path fields the way typing does, so the window's own change rules run. */
    private static void typePaths(FileVerifierView view, Path source, Path dest) throws Exception {
        JTextField sourceField = (JTextField) field(view, "sourcePathField");
        JTextField destField = (JTextField) field(view, "destPathField");
        SwingUtilities.invokeAndWait(() -> {
            sourceField.setText(source.toString());
            destField.setText(dest.toString());
        });
        awaitEventQueue();
    }

    private static void awaitScan(FileVerifierView view) throws Exception {
        for (int i = 0; i < 600 && view.scanInProgress(); i++) {
            Thread.sleep(25);
            awaitEventQueue();
        }
        assertFalse(view.scanInProgress(), "the scan did not finish");
        awaitEventQueue();
    }

    private static void awaitEventQueue() throws Exception {
        java.util.concurrent.Semaphore done = new java.util.concurrent.Semaphore(1);
        SwingUtilities.invokeAndWait(done::release);
        done.acquire();
    }

    private static FileVerifierView build() throws Exception {
        FileVerifierView[] holder = new FileVerifierView[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new FileVerifierView());
        return holder[0];
    }

    private static Object field(Object owner, String name) throws Exception {
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                Field declared = type.getDeclaredField(name);
                declared.setAccessible(true);
                return declared.get(owner);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
