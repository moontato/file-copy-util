package com.fileverifier;

import javax.swing.*;
import java.awt.*;

public class FileVerifierApp {

    public static void main(String[] args) {
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
