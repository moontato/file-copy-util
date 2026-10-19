package com.fileverifier;

import javax.swing.*;
import java.awt.*;

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
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
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
}
