package com.fileverifier;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/** Headless layout checks, not a substitute for a display-backed desktop test. */
class GuiLayoutTest {
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) layout(child);
        }
    }

    private static Rectangle boundsIn(Component component, Container ancestor) {
        return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), ancestor);
    }

    private static JButton buttonWithText(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container nested) {
                JButton found = buttonWithText(nested, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    void viewportAndSplitKeepControlsSeparateAtSmallAndNormalSizesInBothThemes() throws Exception {
        Preferences prefs = Preferences.userRoot().node("com/fileverifier");
        String previous = prefs.get("darkMode", null);
        try {
            for (boolean dark : new boolean[]{false, true}) {
                prefs.putBoolean("darkMode", dark);
                for (Dimension size : new Dimension[]{new Dimension(480, 360), new Dimension(820, 600),
                        new Dimension(1150, 820)}) {
                    SwingUtilities.invokeAndWait(() -> {
                        FileVerifierView view = new FileVerifierView();
                        try {
                            JScrollPane viewport = new JScrollPane(view);
                            viewport.setSize(size);
                            layout(viewport);
                            JButton run = (JButton) field(view, "runButton");
                            JButton cancel = (JButton) field(view, "cancelButton");
                            JLabel version = (JLabel) field(view, "versionLabel");
                            JTextField timeout = (JTextField) field(view, "jobTimeoutField");
                            assertFalse(boundsIn(version, view).intersects(boundsIn(timeout, view)), "footer overlap");
                            assertFalse(boundsIn(run, view).intersects(boundsIn(cancel, view)), "run/cancel overlap");
                            assertTrue(run.getWidth() >= run.getMinimumSize().width);
                            assertTrue(cancel.getWidth() >= cancel.getMinimumSize().width);
                            JTextField sourceFilter = (JTextField) field(view, "sourceFilterField");
                            JList<?> sourceList = (JList<?>) field(view, "sourceList");
                            JTextField destFilter = (JTextField) field(view, "destFilterField");
                            JList<?> destList = (JList<?>) field(view, "destList");
                            assertFalse(boundsIn(sourceFilter, view).intersects(boundsIn(sourceList, view)));
                            assertFalse(boundsIn(destFilter, view).intersects(boundsIn(destList, view)));
                            Container sourceCard = sourceList.getParent().getParent().getParent();
                            JButton selectAll = buttonWithText(sourceCard, "Select all");
                            JButton deselectAll = buttonWithText(sourceCard, "Deselect all");
                            assertNotNull(selectAll);
                            assertNotNull(deselectAll);
                            assertFalse(boundsIn(selectAll, view).intersects(boundsIn(deselectAll, view)));
                            assertFalse(boundsIn(selectAll, view).intersects(boundsIn(sourceList, view)));
                            assertFalse(boundsIn(selectAll, view).intersects(boundsIn(sourceFilter, view)));
                            assertTrue(sourceFilter.getWidth() >= sourceFilter.getMinimumSize().width);
                            assertTrue(destFilter.getWidth() >= destFilter.getMinimumSize().width);
                            assertTrue(viewport.getViewport().getViewSize().width >= view.getPreferredSize().width);
                            assertTrue(viewport.getViewport().getViewSize().height >= view.getPreferredSize().height);
                            JSplitPane split = (JSplitPane) java.util.Arrays.stream(view.getComponents())
                                .filter(c -> "listsAndOutputSplit".equals(c.getName())).findFirst().orElseThrow();
                            assertEquals(JSplitPane.VERTICAL_SPLIT, split.getOrientation());
                            assertTrue(split.getTopComponent().getHeight() >= split.getTopComponent().getMinimumSize().height);
                            assertTrue(split.getBottomComponent().getHeight() >= split.getBottomComponent().getMinimumSize().height);
                            int first = split.getDividerLocation();
                            int available = split.getHeight() - split.getDividerSize();
                            int target = Math.min(available - split.getBottomComponent().getMinimumSize().height,
                                split.getTopComponent().getMinimumSize().height + 20);
                            split.setDividerLocation(target);
                            layout(split);
                            assertEquals(target, split.getDividerLocation());
                            split.setDividerLocation(first);
                            layout(split);
                            System.out.println("layout dark=" + dark + " viewport=" + size.width + "x" + size.height
                                + " content=" + view.getSize() + " labelFont=" + version.getFont().getSize2D());
                            BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
                            Graphics2D graphics = image.createGraphics();
                            try { viewport.paint(graphics); } finally { graphics.dispose(); }
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        } finally {
                            view.shutdown();
                        }
                    });
                }
            }
        } finally {
            if (previous == null) prefs.remove("darkMode"); else prefs.put("darkMode", previous);
        }
    }

    private static void rethrow(Throwable problem) throws Exception {
        if (problem instanceof Exception exception) {
            throw exception;
        }
        throw new RuntimeException(problem);
    }

    @Test
    void longNestedPathsScrollInsideTheirListsAndKeepRunAndCancelReachable() throws Exception {
        Preferences prefs = Preferences.userRoot().node("com/fileverifier");
        boolean previous = prefs.getBoolean("darkMode", false);
        try {
            for (boolean dark : new boolean[]{false, true}) {
                prefs.putBoolean("darkMode", dark);
                Throwable[] failure = new Throwable[1];
                SwingUtilities.invokeAndWait(() -> {
                    FileVerifierView view = new FileVerifierView();
                    try {
                        String names = GuiLayoutHarness.assertPathLengthDoesNotWidenTheForm(view, "dark=" + dark);
                        String label = "populated dark=" + dark;
                        GuiLayoutHarness.assertContentScrollsInsideItsOwnRegions(view, label);
                        GuiLayoutHarness.assertStatusLineStaysOneLine(view, label);
                        GuiLayoutHarness.assertActionsReachable(view, label);

                        JList<?> sourceList = (JList<?>) field(view, "sourceList");
                        JScrollPane listScroll = (JScrollPane) sourceList.getParent().getParent();
                        JScrollPane constrained = new JScrollPane(view);
                        constrained.setSize(480, 360);
                        GuiLayoutHarness.layoutAll(constrained);
                        assertTrue(constrained.getViewport().getViewRect().width < view.getPreferredSize().width,
                            "a 480x360 screen no longer needs outer scrolling, so the small-screen escape hatch is gone");
                        System.out.println(label + " " + names
                            + " filled=" + view.getPreferredSize().width + "x" + view.getPreferredSize().height
                            + " listPreferredWidth=" + sourceList.getPreferredSize().width
                            + " listBudget=" + listScroll.getPreferredSize().width
                            + " listCellHeight=" + sourceList.getUI().getCellBounds(sourceList, 0, 0).height
                            + " constrainedViewportWidth=" + constrained.getViewport().getViewRect().width);
                        constrained.getViewport().setView(null);
                    } catch (Throwable problem) {
                        failure[0] = problem;
                    } finally {
                        view.shutdown();
                    }
                });
                if (failure[0] != null) {
                    rethrow(failure[0]);
                }
            }
        } finally {
            prefs.putBoolean("darkMode", previous);
        }
    }

    @Test
    void rowsFitAGenuinelyEnlargedInterfaceFontAndAreMeasuredAgainAfterAThemeSwitch() throws Exception {
        java.util.List<String> keys = java.util.List.of("Label.font", "List.font", "Table.font",
            "TextField.font", "Button.font", "CheckBox.font");
        java.util.Map<String, Object> saved = new java.util.HashMap<>();
        for (String key : keys) {
            saved.put(key, UIManager.get(key));
        }
        Font enlarged = new Font(Font.SANS_SERIF, Font.PLAIN, 24);
        try {
            Throwable[] failure = new Throwable[1];
            SwingUtilities.invokeAndWait(() -> {
                FileVerifierView view = new FileVerifierView();
                try {
                    GuiLayoutHarness.populate(view);
                    for (String key : keys) {
                        UIManager.put(key, enlarged);
                    }
                    UiTheme.refresh(view);
                    UiTheme.restyle(view);
                    view.applyContentBounds();
                    GuiLayoutHarness.assertRowsFitTheRenderer(view, "enlarged 24 point");
                    GuiLayoutHarness.assertContentScrollsInsideItsOwnRegions(view, "enlarged 24 point");
                    GuiLayoutHarness.assertActionsReachable(view, "enlarged 24 point");
                    JList<?> sourceList = (JList<?>) field(view, "sourceList");
                    JTable results = (JTable) field(view, "resultsTable");
                    System.out.println("enlarged baseFont=" + UiTheme.baseFont().getSize2D()
                        + " line=" + GuiLayoutHarness.lineHeight(UiTheme.baseFont())
                        + " listCell=" + sourceList.getUI().getCellBounds(sourceList, 0, 0).height
                        + " tableRow=" + results.getRowHeight(0)
                        + " header=" + results.getTableHeader().getPreferredSize().height
                        + " filled=" + view.getPreferredSize().width + "x" + view.getPreferredSize().height);

                    JCheckBox theme = (JCheckBox) field(view, "darkModeCheck");
                    theme.doClick();
                    for (String key : keys) {
                        UIManager.put(key, enlarged);
                    }
                    view.applyContentBounds();
                    GuiLayoutHarness.layoutAll(view);
                    GuiLayoutHarness.assertRowsFitTheRenderer(view, "dark palette at 24 point");
                    theme.doClick();
                    System.out.println("after theme switch listCell="
                        + sourceList.getUI().getCellBounds(sourceList, 0, 0).height
                        + " tableRow=" + results.getRowHeight(0));
                } catch (Throwable problem) {
                    failure[0] = problem;
                } finally {
                    view.shutdown();
                }
            });
            if (failure[0] != null) {
                rethrow(failure[0]);
            }
        } finally {
            for (String key : keys) {
                if (saved.get(key) instanceof Font original) {
                    UIManager.put(key, original);
                }
            }
        }
    }

    @Test
    void labelsAndControlsOnOneLineReadOnTheSameTextLineInBothThemes() throws Exception {
        Preferences prefs = Preferences.userRoot().node("com/fileverifier");
        boolean previous = prefs.getBoolean("darkMode", false);
        try {
            for (boolean dark : new boolean[]{false, true}) {
                prefs.putBoolean("darkMode", dark);
                Throwable[] failure = new Throwable[1];
                SwingUtilities.invokeAndWait(() -> {
                    FileVerifierView view = new FileVerifierView();
                    try {
                        GuiLayoutHarness.populate(view);
                        GuiLayoutHarness.layoutToPreferred(view);
                        GuiLayoutHarness.assertRowComponentsShareTheirTextBaseline(view,
                            "flatlaf, dark=" + dark);
                        assertSharedTextLine(view, "symlinkCombo", "Symbolic links");
                        assertSharedTextLine(view, "sourcePathField", "Source directory");
                        assertSharedTextLine(view, "destPathField", "Destination directory");
                        assertSharedTextLine(view, "ignoreField", "Ignore");
                    } catch (Throwable problem) {
                        failure[0] = problem;
                    } finally {
                        view.shutdown();
                    }
                });
                if (failure[0] != null) {
                    rethrow(failure[0]);
                }
            }
        } finally {
            prefs.putBoolean("darkMode", previous);
        }
    }

    @Test
    void labelsStayOnTheControlTextLineWhenTheRowWrapsAndWhenTheFontGrows() throws Exception {
        java.util.List<String> keys = java.util.List.of("Label.font", "List.font", "Table.font",
            "TextField.font", "Button.font", "CheckBox.font");
        java.util.Map<String, Object> saved = new java.util.HashMap<>();
        for (String key : keys) {
            saved.put(key, UIManager.get(key));
        }
        Font enlarged = new Font(Font.SANS_SERIF, Font.PLAIN, 24);
        try {
            for (boolean dark : new boolean[]{false, true}) {
                Throwable[] failure = new Throwable[1];
                SwingUtilities.invokeAndWait(() -> {
                    FileVerifierView view = new FileVerifierView();
                    try {
                        GuiLayoutHarness.populate(view);
                        for (String key : keys) {
                            UIManager.put(key, enlarged);
                        }
                        UiTheme.refresh(view);
                        UiTheme.restyle(view);
                        view.applyContentBounds();
                        GuiLayoutHarness.layoutToPreferred(view);
                        GuiLayoutHarness.assertRowComponentsShareTheirTextBaseline(view,
                            "enlarged 24 point, dark=" + dark + ", unwrapped");
                        assertSharedTextLine(view, "symlinkCombo", "Symbolic links");
                        assertSharedTextLine(view, "sourcePathField", "Source directory");
                        // Narrow enough that the control rows have to break into two lines.
                        JScrollPane constrained = new JScrollPane(view);
                        constrained.setSize(480, 360);
                        GuiLayoutHarness.layoutAll(constrained);
                        GuiLayoutHarness.layoutAll(view);
                        GuiLayoutHarness.assertRowComponentsShareTheirTextBaseline(view,
                            "enlarged 24 point, dark=" + dark + ", wrapped at 480px");
                        constrained.getViewport().setView(null);
                    } catch (Throwable problem) {
                        failure[0] = problem;
                    } finally {
                        view.shutdown();
                    }
                });
                if (failure[0] != null) {
                    rethrow(failure[0]);
                }
            }
        } finally {
            for (String key : keys) {
                if (saved.get(key) instanceof Font original) {
                    UIManager.put(key, original);
                }
            }
        }
    }

    /** The label in front of this control must sit on the control's own text line, not above it. */
    private static void assertSharedTextLine(FileVerifierView view, String controlField, String labelText)
            throws Exception {
        JComponent control = (JComponent) field(view, controlField);
        JLabel label = null;
        for (Component child : control.getParent().getComponents()) {
            if (child instanceof JLabel candidate && labelText.equals(candidate.getText())) {
                label = candidate;
                break;
            }
        }
        assertNotNull(label, "no \"" + labelText + "\" label beside " + controlField);
        int controlLine = control.getY() + UiTheme.baselineOf(control);
        int labelLine = label.getY() + UiTheme.baselineOf(label);
        System.out.println("text line check: \"" + labelText + "\" baseline " + labelLine
            + " vs " + controlField + " baseline " + controlLine);
        assertTrue(UiTheme.baselineOf(control) >= 0, controlField + " reports no text baseline");
        // Font metrics are integer pixels and GridBag distributes row space with rounding, so one pixel is
        // the floor of what "on the same text line" can mean here. More than that is a label above its control.
        assertTrue(Math.abs(controlLine - labelLine) <= 1,
            "\"" + labelText + "\" sits " + Math.abs(controlLine - labelLine) + "px off the text inside "
                + controlField);
    }

}
