package com.fileverifier;

import javax.swing.JFrame;
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
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1150, 820);
        setMinimumSize(new java.awt.Dimension(820, 600));
        setLocationRelativeTo(null);
        // Read the theme before the view builds a single widget: a light window that repaints dark a
        // second after opening looks broken.
        UiTheme.install(darkPreferred());
        view = new FileVerifierView();
        setContentPane(view);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                view.shutdown();
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
