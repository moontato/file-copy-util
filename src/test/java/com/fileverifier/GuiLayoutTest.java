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
}
