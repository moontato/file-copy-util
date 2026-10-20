package com.fileverifier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
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
            "symlinkPolicy", "timeoutSeconds", "dryRun", "detectExtra", "jobType", "darkMode")) {
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
            "copyVerifyButton", "verifyOnlyButton", "workSetLabel", "sourceList", "destList",
            "sourceFilterField", "destFilterField", "progressBar", "statusLabel", "phaseLabel",
            "logArea", "outputTabs", "resultsTable", "onlyProblemsCheck", "exportCsvButton",
            "exportJsonButton", "dryRunCheck", "jobTimeoutField", "darkModeCheck", "runButton",
            "cancelButton")) {
            assertNotNull(field(view, name), "missing control: " + name);
        }
    }

    @Test
    void modeChoiceIsOneControlAndTheWorkSetLineStartsHonest() throws Exception {
        FileVerifierView view = build();
        JToggleButton copy = (JToggleButton) field(view, "copyVerifyButton");
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
    void resultsTableHasAHeaderSoColumnsCanBeSorted() throws Exception {
        FileVerifierView view = build();
        JTable table = (JTable) field(view, "resultsTable");
        JScrollPane scroll = (JScrollPane) table.getParent().getParent();
        assertNotNull(scroll.getRowHeader(),
            "JScrollPane(JTable) does not attach the header on its own, and a table without column "
                + "titles has no way to reach its sorter");
        assertNotNull(table.getRowSorter());
        assertEquals(4, table.getColumnCount());
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
