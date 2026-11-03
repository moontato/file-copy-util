package com.fileverifier;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.prefs.Preferences;

/**
 * The window. Everything the user sees and touches lives in {@link FileVerifierView}; this class only
 * sizes a frame, names it, and tells the view when the window is closing.
 *
 * <p>The split is not cosmetic. A {@code JFrame} cannot be constructed at all when the JVM is headless,
 * so a UI built inside one can only be checked by hand on a desktop. As a panel the whole interface can
 * be built, driven, and painted in a test with no display server, which is how this window is verified
 * in CI.</p>
 */
public final class FileVerifierFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    /** What this window would like to open at, when both the layout and the screen allow it. */
    static final int INTENDED_WIDTH = 1150;
    static final int INTENDED_HEIGHT = 820;
    /** Never insist on more than this, and never more than the screen has. */
    static final int FLOOR_WIDTH = 480;
    static final int FLOOR_HEIGHT = 360;

    private final FileVerifierView view;

    public FileVerifierFrame() {
        // The long name stays here: it is the window's title, not the project's coordinate.
        setTitle("File Verifier & Copier");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        // Read the theme before the view builds a single widget: a light window that repaints dark a
        // second after opening looks broken.
        UiTheme.install(darkPreferred());
        view = new FileVerifierView();
        JScrollPane viewport = new JScrollPane(view);
        viewport.setBorder(null);
        viewport.getVerticalScrollBar().setUnitIncrement(24);
        viewport.getHorizontalScrollBar().setUnitIncrement(24);
        setContentPane(viewport);
        // Swing coordinates reflect the active display scale. Never require a window larger than the usable
        // screen (which excludes taskbars and other desktop insets).
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        Dimension floor = new Dimension(Math.min(FLOOR_WIDTH, usable.width), Math.min(FLOOR_HEIGHT, usable.height));
        setMinimumSize(floor);
        // Pack first. The frame's own preferred size is the only measurement that includes what the content
        // pane reserves for itself - the viewport's border and its scrollbars - and after the pack the window
        // is realized, so getInsets() reports the title bar and borders instead of nothing.
        pack();
        setSize(openingSize(getPreferredSize(), viewport.getPreferredSize(), getInsets(), usable, floor));
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            private boolean closing;
            @Override
            public void windowClosing(WindowEvent e) {
                if (closing) return;
                closing = true;
                view.shutdownAsync(FileVerifierFrame.this::dispose);
            }
        });
    }

    /** The view, so a test or a harness can drive the interface without a display. */
    FileVerifierView view() {
        return view;
    }

    /**
     * The outer window size to open at. The client area the content asks for is added to the insets the
     * window really has - title bar, borders - the result is raised to the intended default, and only then
     * clamped to the usable screen and the window's own floor. Clamping the outer bounds is what keeps
     * scrolling alive on a screen too small for the content: the layout is not squeezed to fit.
     *
     * <p>Package-private, with the insets passed in, because a headless JVM has no window manager. The
     * arithmetic below is testable; the decoration sizes a real desktop reports are not.</p>
     */
    static Dimension openingSize(Dimension packed, Dimension clientContent, Insets decorations,
                                 Rectangle usable, Dimension floor) {
        int width = Math.max(packed.width, clientContent.width + decorations.left + decorations.right);
        int height = Math.max(packed.height, clientContent.height + decorations.top + decorations.bottom);
        // floor is already clamped to the screen at the call site, so this can never exceed the usable area.
        width = Math.max(floor.width, Math.min(usable.width, Math.max(INTENDED_WIDTH, width)));
        height = Math.max(floor.height, Math.min(usable.height, Math.max(INTENDED_HEIGHT, height)));
        return new Dimension(width, height);
    }

    private static boolean darkPreferred() {
        try {
            return Preferences.userRoot().node("com/fileverifier").getBoolean("darkMode", false);
        } catch (Exception e) {
            return false;
        }
    }
}
