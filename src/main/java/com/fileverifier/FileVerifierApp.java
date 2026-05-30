package com.fileverifier;

import javax.swing.*;
import java.awt.*;

public class FileVerifierApp {

   public static void main(String[] args) {
        System.setProperty("java.awt.headless", "false");
        if ("wayland".equals(System.getenv("XDG_SESSION_TYPE"))) {
            System.setProperty("awt.toolkit", "sun.awt.X11.XToolkit");
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Error: No display available. Ensure X11 is running and DISPLAY is set.");
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
