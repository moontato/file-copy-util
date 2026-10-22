package com.fileverifier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
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
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
