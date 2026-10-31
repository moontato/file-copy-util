package com.fileverifier;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.prefs.Preferences;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;

/** A dependency-free main launched with only the packaged thin jar and test classes. */
public final class ThinJarProbe {
    private ThinJarProbe() { }

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toRealPath();
        for (Class<?> type : new Class<?>[] {FileVerifierApp.class, FileVerifierView.class, UiTheme.class}) {
            Path origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            require(origin.equals(jar), type.getName() + " loaded from " + origin + ", not " + jar);
            System.out.println(type.getName() + " origin: " + origin);
        }
        try {
            Class.forName("com.formdev.flatlaf.FlatLaf");
            throw new AssertionError("FlatLaf must not be on the thin classpath");
        } catch (ClassNotFoundException expected) {
            System.out.println("FlatLaf absent from thin classpath");
        }
        Preferences.userRoot().node("com/fileverifier").putBoolean("darkMode", false);
        SwingUtilities.invokeAndWait(() -> {
            FileVerifierView view = new FileVerifierView();
            try {
                JCheckBox theme = find(view, JCheckBox.class, "Dark mode");
                JButton run = find(view, JButton.class, "Run job");
                require(theme != null && run != null, "theme toggle and Run button must exist");
                require(UiTheme.palette().equals(UiTheme.LIGHT), "initial palette must be light");
                require(run.getBackground().equals(UiTheme.LIGHT.accent()), "initial button is light");
                theme.doClick();
                require(UiTheme.palette().equals(UiTheme.DARK), "toggle must select dark palette");
                require(run.getBackground().equals(UiTheme.DARK.accent()), "button restyled dark");
                theme.doClick();
                require(UiTheme.palette().equals(UiTheme.LIGHT), "toggle must restore light palette");
                require(run.getBackground().equals(UiTheme.LIGHT.accent()), "button restyled light");
                System.out.println("Headless view: light -> dark -> light, button restyled");
            } finally {
                view.shutdown();
            }
        });
    }

    private static <T extends javax.swing.AbstractButton> T find(Container root, Class<T> type, String text) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && text.equals(type.cast(component).getText())) {
                return type.cast(component);
            }
            if (component instanceof Container nested) {
                T match = find(nested, type, text);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
