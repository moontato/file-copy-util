package com.fileverifier;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

class UntickUnmatchedTest {
    private final java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userRoot().node("com/fileverifier");
    private final java.util.Map<String, String> savedPrefs = new java.util.HashMap<>();

    @BeforeEach void isolatePreferences() throws Exception {
        for (String key : prefs.keys()) savedPrefs.put(key, prefs.get(key, null));
        prefs.clear();
    }

    @AfterEach void restorePreferences() throws Exception {
        prefs.clear();
        for (var entry : savedPrefs.entrySet()) prefs.put(entry.getKey(), entry.getValue());
    }

    private static final String TOOLTIP = "Untick files whose relative path is absent from the other folder. "
        + "File contents are checked when verification runs.";

    @Test
    void exactPathsUseBothCompleteModelsDespiteFiltersAndTicks(@TempDir Path root) throws Exception {
        Path src = Files.createDirectory(root.resolve("src"));
        Path dst = Files.createDirectory(root.resolve("dst"));
        for (String p : List.of("shared.txt", "manual.txt", "hidden-common.txt", "source-only.txt",
                "one/same.txt", "Case.txt", "kind.txt")) TestSupport.write(src.resolve(p), "source");
        for (String p : List.of("shared.txt", "manual.txt", "hidden-common.txt", "dest-only.txt",
                "two/same.txt", "case.txt")) TestSupport.write(dst.resolve(p), "destination");
        Files.createSymbolicLink(dst.resolve("kind.txt"), Path.of("shared.txt"));
        FileVerifierView view = onEdt(FileVerifierView::new);
        try {
            onEdt(() -> {
                field(view, "sourcePathField", JTextField.class).setText(src.toString());
                field(view, "destPathField", JTextField.class).setText(dst.toString());
                field(view, "symlinkCombo", javax.swing.JComboBox.class).setSelectedItem(ScanOptions.SymlinkPolicy.LINK);
                field(view, "verifyOnlyButton", JToggleButton.class).doClick();
                view.applyFinishedScan(view.currentScanGeneration(), src, dst,
                    new ScanOptions(ScanOptions.DEFAULT_IGNORED_DIRS, true, ScanOptions.SymlinkPolicy.LINK));
                assertNull(view.runRefusal());
                JButton source = field(view, "sourceUntickUnmatchedButton", JButton.class);
                JButton dest = field(view, "destUntickUnmatchedButton", JButton.class);
                assertTrue(source.isEnabled()); assertTrue(dest.isEnabled());
                assertEquals(TOOLTIP, source.getToolTipText()); assertEquals(TOOLTIP, dest.getToolTipText());
                // A manually unticked shared path remains unticked, and the other pane still sees it as present.
                toggle(view, "sourceModel", "manual.txt");
                toggle(view, "destModel", "hidden-common.txt");
                field(view, "sourceFilterField", JTextField.class).setText("shared");
                field(view, "destFilterField", JTextField.class).setText("shared");
                source.doClick(); dest.doClick();
                assertEquals(Set.of("manual.txt", "source-only.txt", "one/same.txt", "Case.txt"),
                    unticked(view, "sourceModel"));
                assertEquals(Set.of("hidden-common.txt", "dest-only.txt", "two/same.txt", "case.txt"),
                    unticked(view, "destModel"));
                assertTrue(field(view, "workSetLabel", JLabel.class).getText().contains("2 file(s)"),
                    field(view, "workSetLabel", JLabel.class).getText());
                // The count labels reflect hidden rows too; a second click is idempotent.
                assertTrue(countLabel(view, "sourceModel").getText().contains("3 selected"));
                assertTrue(countLabel(view, "destModel").getText().contains("3 selected"));
                source.doClick(); dest.doClick();
                assertEquals(4, unticked(view, "sourceModel").size());
                assertEquals(4, unticked(view, "destModel").size());
                return null;
            });
        } finally { view.shutdown(); }
    }

    @Test
    void emptyIntersectionKeepsTheExistingEmptySelectionRefusal(@TempDir Path root) throws Exception {
        Path src = Files.createDirectory(root.resolve("src"));
        Path dst = Files.createDirectory(root.resolve("dst"));
        TestSupport.write(src.resolve("one/a"), "a"); TestSupport.write(dst.resolve("two/a"), "b");
        FileVerifierView view = onEdt(FileVerifierView::new);
        try {
            scan(view, src, dst);
            onEdt(() -> {
                field(view, "sourceUntickUnmatchedButton", JButton.class).doClick();
                field(view, "destUntickUnmatchedButton", JButton.class).doClick();
                assertEquals(Set.of("one/a"), unticked(view, "sourceModel"));
                assertEquals(Set.of("two/a"), unticked(view, "destModel"));
                assertTrue(field(view, "workSetLabel", JLabel.class).getText().contains("0 file(s)"));
                view.runRequested();
                assertNotNull(view.requestInUse());
                assertFalse(view.engineInUse().isRunning(), "the empty selection was not started");
                assertNull(view.completedJobInUse());
                return null;
            });
        } finally { view.shutdown(); }
    }

    @Test
    void buttonsFollowModeScanStalenessAndRunningJob(@TempDir Path root) throws Exception {
        Path src = Files.createDirectory(root.resolve("src"));
        Path dst = Files.createDirectory(root.resolve("dst"));
        TestSupport.write(src.resolve("shared"), "one"); TestSupport.write(dst.resolve("shared"), "two");
        FileVerifierView view = onEdt(FileVerifierView::new);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            onEdt(() -> {
                field(view, "sourcePathField", JTextField.class).setText(src.toString());
                field(view, "destPathField", JTextField.class).setText(dst.toString());
                field(view, "verifyOnlyButton", JToggleButton.class).doClick();
                assertEnabled(view, false);
                view.setScannerForTesting((path, options) -> {
                    started.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("scanner timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                    return DirectoryScanner.scanFiles(path, options);
                });
                view.scanRequested();
                assertEnabled(view, false);
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            release.countDown();
            await(() -> !view.scanInProgress());
            onEdt(() -> {
                assertEnabled(view, true);
                field(view, "copyButton", JToggleButton.class).doClick(); assertEnabled(view, false);
                field(view, "verifyOnlyButton", JToggleButton.class).doClick(); assertEnabled(view, true);
                JCheckBox hidden = field(view, "hiddenCheck", JCheckBox.class);
                hidden.setSelected(!hidden.isSelected()); assertEnabled(view, false);
                hidden.setSelected(!hidden.isSelected()); assertEnabled(view, true);
                // Starting on the EDT prevents completion from draining before these assertions.
                view.runRequested();
                assertNotNull(view.requestInUse());
                assertEnabled(view, false);
                return null;
            });
            await(() -> view.completedJobInUse() != null);
            onEdt(() -> {
                assertEnabled(view, true);
                field(view, "sourcePathField", JTextField.class).setText(root.resolve("other").toString());
                assertEnabled(view, false);
                return null;
            });
        } finally { release.countDown(); view.shutdown(); }
    }

    @Test
    void anEmptyScanInvalidatedByPathEditsCannotBeReused(@TempDir Path root) throws Exception {
        Path src = Files.createDirectory(root.resolve("src"));
        Path dst = Files.createDirectory(root.resolve("dst"));
        FileVerifierView view = onEdt(FileVerifierView::new);
        try {
            scan(view, src, dst);
            onEdt(() -> {
                assertEnabled(view, true);
                JTextField path = field(view, "sourcePathField", JTextField.class);
                path.setText(root.resolve("different").toString());
                assertEnabled(view, false);
                path.setText(src.toString());
                assertEnabled(view, false, "returning to the old path does not restore an invalidated scan");
                return null;
            });
        } finally { view.shutdown(); }
    }

    @Test
    void aSharedPathWithDifferentContentsIsStillVerified(@TempDir Path root) throws Exception {
        Path src = Files.createDirectory(root.resolve("src")); Path dst = Files.createDirectory(root.resolve("dst"));
        TestSupport.write(src.resolve("shared.txt"), "source content");
        TestSupport.write(dst.resolve("shared.txt"), "different content");
        TestSupport.write(src.resolve("source-only"), "x"); TestSupport.write(dst.resolve("dest-only"), "y");
        FileVerifierView view = onEdt(FileVerifierView::new);
        try {
            scan(view, src, dst);
            onEdt(() -> {
                field(view, "sourceUntickUnmatchedButton", JButton.class).doClick();
                field(view, "destUntickUnmatchedButton", JButton.class).doClick();
                view.runRequested();
                assertEquals(Set.of("shared.txt"), view.requestInUse().sourceSelection().selected());
                assertEquals(Set.of("shared.txt"), view.requestInUse().destinationSelection().selected());
                return null;
            });
            await(() -> view.completedJobInUse() != null);
            onEdt(() -> {
                FileSyncEngine.JobResult result = view.completedJobInUse().result();
                assertEquals(1, result.filesMismatched());
                assertTrue(result.outcomes().stream().anyMatch(o -> o.relPath().equals("shared.txt") && o.kind().equals("MISMATCH")));
                return null;
            });
        } finally { view.shutdown(); }
    }

    private static void scan(FileVerifierView view, Path src, Path dst) throws Exception {
        onEdt(() -> {
            field(view, "sourcePathField", JTextField.class).setText(src.toString());
            field(view, "destPathField", JTextField.class).setText(dst.toString());
            field(view, "verifyOnlyButton", JToggleButton.class).doClick();
            view.applyFinishedScan(view.currentScanGeneration(), src, dst, ScanOptions.DEFAULTS);
            assertNull(view.runRefusal());
            return null;
        });
    }
    private static void assertEnabled(FileVerifierView view, boolean expected) throws Exception {
        assertEnabled(view, expected, "both buttons follow the current scan and job state");
    }
    private static void assertEnabled(FileVerifierView view, boolean expected, String message) throws Exception {
        assertEquals(expected, field(view, "sourceUntickUnmatchedButton", JButton.class).isEnabled(), message);
        assertEquals(expected, field(view, "destUntickUnmatchedButton", JButton.class).isEnabled(), message);
    }
    private static void toggle(FileVerifierView view, String name, String path) throws Exception {
        Object model = field(view, name, Object.class);
        javax.swing.DefaultListModel<?> rows = (javax.swing.DefaultListModel<?>) method(model, "view").invoke(model);
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            if (method(row, "relPath").invoke(row).equals(path)) { method(model, "toggle", int.class).invoke(model, i); return; }
        }
        fail("missing row " + path);
    }
    @SuppressWarnings("unchecked")
    private static Set<String> unticked(FileVerifierView view, String name) throws Exception {
        Object model = field(view, name, Object.class);
        return Set.copyOf((Set<String>) method(model, "untickedPaths").invoke(model));
    }
    private static JLabel countLabel(FileVerifierView view, String name) throws Exception {
        return field(field(view, name, Object.class), "countLabel", JLabel.class);
    }
    private static Method method(Object o, String name, Class<?>... types) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m;
    }
    private static <T> T field(Object o, String name, Class<T> type) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(o));
    }
    private interface Checked<T> { T run() throws Exception; }
    private static <T> T onEdt(Checked<T> action) throws Exception {
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(action::run);
        SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 400; i++) {
            if (onEdt(condition::getAsBoolean)) return;
            Thread.sleep(25);
        }
        fail("timed out waiting for UI state");
    }
}
