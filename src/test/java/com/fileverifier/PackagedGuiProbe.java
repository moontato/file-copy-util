package com.fileverifier;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.prefs.Preferences;
import javax.swing.JCheckBox;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/** Manual headless packaging probe: the application classes must come from the supplied jar. */
public final class PackagedGuiProbe {
    private PackagedGuiProbe() { }

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toRealPath();
        for (Class<?> type : new Class<?>[]{FileVerifierView.class, UiTheme.class, FileVerifierApp.class}) {
            Path origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if (!jar.equals(origin)) throw new AssertionError(type.getName() + " loaded from " + origin);
            System.out.println(type.getName() + " origin=" + origin);
        }
        Preferences.userRoot().node("com/fileverifier").putBoolean("darkMode", false);
        SwingUtilities.invokeAndWait(() -> {
            FileVerifierView view = new FileVerifierView();
            try {
                JCheckBox theme = findTheme(view);
                if (theme == null) throw new AssertionError("theme control missing");
                for (boolean dark : new boolean[]{false, true}) {
                    if (dark) theme.doClick();
                    if (!UiTheme.palette().equals(dark ? UiTheme.DARK : UiTheme.LIGHT)) {
                        throw new AssertionError("wrong palette");
                    }
                    for (Dimension size : new Dimension[]{new Dimension(480, 360), new Dimension(820, 600),
                            new Dimension(1150, 820)}) {
                        JScrollPane scroll = new JScrollPane(view);
                        scroll.setSize(size);
                        layout(scroll);
                        BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D graphics = image.createGraphics();
                        try { scroll.paint(graphics); } finally { graphics.dispose(); }
                        System.out.println("painted headless dark=" + dark + " viewport=" + size.width + "x" + size.height
                                + " content=" + view.getSize());
                    }
                }
            } finally {
                view.shutdown();
            }
        });
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) layout(nested);
        }
    }

    private static JCheckBox findTheme(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JCheckBox check && "Dark mode".equals(check.getText())) return check;
            if (child instanceof Container nested) {
                JCheckBox found = findTheme(nested);
                if (found != null) return found;
            }
        }
        return null;
    }
}
