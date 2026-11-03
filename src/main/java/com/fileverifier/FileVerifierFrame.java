package com.fileverifier;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
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
        // Swing coordinates reflect the active display scale. On a small screen the viewport scrolls
        // rather than squeezing fixed controls into each other; never require a window larger than the
        // usable screen (which excludes taskbars and other desktop insets).
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setMinimumSize(new Dimension(Math.min(480, usable.width), Math.min(360, usable.height)));
        // The opening size follows the layout the interface actually asks for, clamped to the screen:
        // at a 12 point font the window opens around 1150x854 because that is what the rows need, and a
        // larger font opens a taller window instead of scrolling one row at a time.
        Dimension preferred = view.getPreferredSize();
        int width = Math.min(usable.width, Math.max(1150, preferred.width));
        int height = Math.min(usable.height, Math.max(820, preferred.height));
        setSize(width, height);
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

    private static boolean darkPreferred() {
        try {
            return Preferences.userRoot().node("com/fileverifier").getBoolean("darkMode", false);
        } catch (Exception e) {
            return false;
        }
    }
}
