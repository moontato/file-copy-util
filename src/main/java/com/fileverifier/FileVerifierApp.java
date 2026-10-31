package com.fileverifier;

import javax.swing.*;
import java.awt.*;
import java.util.prefs.Preferences;

public class FileVerifierApp {

   public static void main(String[] args) {
        if (Cli.isCliRequest(args)) {
            System.exit(Cli.run(args, System.out));
            return;
        }
        System.setProperty("java.awt.headless", "false");
        if ("wayland".equals(System.getenv("XDG_SESSION_TYPE"))) {
            System.setProperty("awt.toolkit", "sun.awt.X11.XToolkit");
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Error: No display available. Ensure X11 is running and DISPLAY is set,");
            System.err.println("or run without a display: java -jar file-copy-util.jar --cli --source DIR --dest DIR --all");
            System.exit(1);
        }
        try {
            UiTheme.install(prefsDarkDefault());
        } catch (IllegalStateException failure) {
            System.err.println("No usable look and feel on this machine: " + failure.getMessage());
            System.err.println("Run without a display: java -jar file-copy-util.jar --cli "
                + "--source DIR --dest DIR --all");
            System.exit(1);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                FileVerifierFrame frame = new FileVerifierFrame();
                frame.setVisible(true);
            } catch (Exception e) {
                System.err.println("Failed to start application: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /** Read before any window exists: a light window that repaints dark a second later looks broken. */
    private static boolean prefsDarkDefault() {
        try {
            return Preferences.userRoot().node("com/fileverifier").getBoolean("darkMode", false);
        } catch (Exception e) {
            return false;
        }
    }
}
