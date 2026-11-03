package com.fileverifier;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.prefs.Preferences;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The opening-size arithmetic, checked where no window exists: no display, no window manager, so no real
 * decorations. What these tests can prove is that the client area and the insets are both accounted for and
 * that clamping leaves scrolling intact. What the decorations actually measure on a desktop it cannot prove.
 */
class FrameOpeningSizeTest {

    private static final Insets REPRESENTATIVE_DECORATIONS = new Insets(30, 8, 8, 8);
    private static final Rectangle BIG_SCREEN = new Rectangle(0, 0, 1920, 1080);
    private static final Dimension FLOOR = new Dimension(FileVerifierFrame.FLOOR_WIDTH,
        FileVerifierFrame.FLOOR_HEIGHT);

    @Test
    void decorationsAreAddedToTheClientAreaInsteadOfEatenOutOfIt() {
        Dimension content = new Dimension(1122, 959);
        // pack() before the window is realized reports no insets at all: exactly the mistake this replaces.
        Dimension packedWithoutPeer = new Dimension(1122, 959);
        Dimension opening = FileVerifierFrame.openingSize(packedWithoutPeer, content,
            REPRESENTATIVE_DECORATIONS, BIG_SCREEN, FLOOR);
        assertTrue(opening.height >= content.height + REPRESENTATIVE_DECORATIONS.top
                + REPRESENTATIVE_DECORATIONS.bottom,
            "a 959 pixel client area opened in a " + opening.height + " pixel window: the title bar was "
                + "taken out of the content, not added to the window");
        assertTrue(opening.width >= content.width + REPRESENTATIVE_DECORATIONS.left
                + REPRESENTATIVE_DECORATIONS.right,
            "a " + content.width + " pixel client area opened in a " + opening.width + " pixel window");
    }

    @Test
    void intendedDefaultSurvivesAContentSmallerThanIt() {
        Dimension opening = FileVerifierFrame.openingSize(new Dimension(616, 546), new Dimension(600, 500),
            REPRESENTATIVE_DECORATIONS, BIG_SCREEN, FLOOR);
        assertEquals(new Dimension(FileVerifierFrame.INTENDED_WIDTH, FileVerifierFrame.INTENDED_HEIGHT),
            opening, "a small layout should not open a window smaller than the intended default");
    }

    @Test
    void aScreenTooSmallClampsTheWindowAndKeepsTheScrollingEscapeHatch() {
        Dimension content = new Dimension(1122, 959);
        Dimension opening = FileVerifierFrame.openingSize(content, content, REPRESENTATIVE_DECORATIONS,
            new Rectangle(0, 0, 900, 640), FLOOR);
        assertEquals(new Dimension(900, 640), opening,
            "the window may not be bigger than the usable screen, so its viewport has to scroll instead");

        Rectangle tiny = new Rectangle(0, 0, 400, 300);
        Dimension smallFloor = new Dimension(Math.min(FileVerifierFrame.FLOOR_WIDTH, tiny.width),
            Math.min(FileVerifierFrame.FLOOR_HEIGHT, tiny.height));
        Dimension onTiny = FileVerifierFrame.openingSize(content, content, REPRESENTATIVE_DECORATIONS, tiny,
            smallFloor);
        assertEquals(new Dimension(400, 300), onTiny, "a 400x300 usable screen must not be asked for 1150x820");
    }

    @Test
    void theClientAreaTheOpeningSizeLeavesHoldsThePopulatedFormWithoutOuterScrollbars() throws Exception {
        Preferences prefs = Preferences.userRoot().node("com/fileverifier");
        boolean previous = prefs.getBoolean("darkMode", false);
        try {
            Throwable[] failure = new Throwable[1];
            SwingUtilities.invokeAndWait(() -> {
                FileVerifierView view = new FileVerifierView();
                try {
                    GuiLayoutHarness.populate(view);
                    GuiLayoutHarness.layoutAll(view);
                    Dimension content = view.getPreferredSize();
                    // Built the way FileVerifierFrame builds it: the viewport carries no border of its own,
                    // because a border is 13 pixels taken out of the client area and is enough to make a
                    // scrollbar appear that nothing needs.
                    JScrollPane viewport = new JScrollPane(view);
                    viewport.setBorder(null);
                    viewport.getVerticalScrollBar().setUnitIncrement(24);
                    viewport.getHorizontalScrollBar().setUnitIncrement(24);
                    Dimension packed = viewport.getPreferredSize();
                    Dimension opening = FileVerifierFrame.openingSize(packed, content,
                        REPRESENTATIVE_DECORATIONS, BIG_SCREEN, FLOOR);
                    Dimension client = new Dimension(
                        opening.width - REPRESENTATIVE_DECORATIONS.left - REPRESENTATIVE_DECORATIONS.right,
                        opening.height - REPRESENTATIVE_DECORATIONS.top - REPRESENTATIVE_DECORATIONS.bottom);
                    assertTrue(client.width >= content.width && client.height >= content.height,
                        "the window leaves a client area of " + client + " for content of " + content);

                    viewport.setSize(client);
                    GuiLayoutHarness.layoutAll(viewport);
                    Rectangle visible = viewport.getViewport().getViewRect();
                    assertTrue(visible.width >= content.width && visible.height >= content.height,
                        "a window of " + opening + " still needed outer scrolling for content of " + content);
                    assertTrue(viewport.getHorizontalScrollBar().getModel().getExtent()
                            >= viewport.getHorizontalScrollBar().getModel().getMaximum(),
                        "an unnecessary horizontal scrollbar was reserved");
                    assertTrue(viewport.getVerticalScrollBar().getModel().getExtent()
                            >= viewport.getVerticalScrollBar().getModel().getMaximum(),
                        "an unnecessary vertical scrollbar was reserved");
                    System.out.println("opening size " + opening + " from packed " + packed + " and content "
                        + content + " leaves client area " + client + " which shows " + visible);

                    viewport.setSize(new Dimension(900 - REPRESENTATIVE_DECORATIONS.left
                            - REPRESENTATIVE_DECORATIONS.right,
                        640 - REPRESENTATIVE_DECORATIONS.top - REPRESENTATIVE_DECORATIONS.bottom));
                    GuiLayoutHarness.layoutAll(viewport);
                    Rectangle cramped = viewport.getViewport().getViewRect();
                    assertTrue(cramped.width < content.width || cramped.height < content.height,
                        "on a 900x640 screen the viewport no longer needed to scroll, so the escape hatch "
                            + "has been designed away");
                    System.out.println("clamped to a 900x640 screen: viewport shows " + cramped
                        + " of content " + content);
                } catch (Throwable problem) {
                    failure[0] = problem;
                } finally {
                    view.shutdown();
                }
            });
            if (failure[0] != null) {
                if (failure[0] instanceof Exception exception) {
                    throw exception;
                }
                throw new RuntimeException(failure[0]);
            }
        } finally {
            prefs.putBoolean("darkMode", previous);
        }
    }
}
