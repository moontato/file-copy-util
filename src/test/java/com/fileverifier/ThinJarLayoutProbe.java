package com.fileverifier;

import java.awt.Font;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;
import javax.swing.JCheckBox;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * A dependency-free main launched with only the packaged thin jar and the test classes. It checks the
 * populated layout in the platform look-and-feel fallback - no FlatLaf, no FlatLaf scale property - with
 * the interface font genuinely enlarged.
 */
public final class ThinJarLayoutProbe {
    private ThinJarLayoutProbe() { }

    public static void main(String[] args) throws Exception {
        java.nio.file.Path jar = java.nio.file.Path.of(args[0]).toRealPath();
        for (Class<?> type : new Class<?>[]{FileVerifierView.class, UiTheme.class}) {
            java.nio.file.Path origin = java.nio.file.Path
                .of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if (!origin.equals(jar)) throw new AssertionError(type.getName() + " loaded from " + origin);
            System.out.println(type.getName() + " origin=" + origin);
        }
        try {
            Class.forName("com.formdev.flatlaf.FlatLaf");
            throw new AssertionError("this probe must run without FlatLaf");
        } catch (ClassNotFoundException expected) {
            System.out.println("FlatLaf absent from thin classpath");
        }
        Preferences.userRoot().node("com/fileverifier").putBoolean("darkMode", false);

        List<String> keys = List.of("Label.font", "List.font", "Table.font", "TextField.font",
            "Button.font", "CheckBox.font");
        Map<String, Object> base = new java.util.HashMap<>();
        SwingUtilities.invokeAndWait(() -> {
            FileVerifierView view = new FileVerifierView();
            try {
                GuiLayoutHarness.populate(view);
                GuiLayoutHarness.layoutAll(view);
                for (String key : keys) {
                    base.put(key, UIManager.get(key));
                    UIManager.put(key, new Font(Font.SANS_SERIF, Font.PLAIN, 13));
                }
                UiTheme.refresh(view);
                UiTheme.restyle(view);
                view.applyContentBounds();
                GuiLayoutHarness.layoutAll(view);
                System.out.println("platform fallback at 13 point: " + describe(view));
                GuiLayoutHarness.assertRowComponentsShareTheirTextBaseline(view, "thin fallback at 13 point");

                for (String key : keys) {
                    UIManager.put(key, new Font(Font.SANS_SERIF, Font.PLAIN, 24));
                }
                UiTheme.refresh(view);
                UiTheme.restyle(view);
                view.applyContentBounds();
                GuiLayoutHarness.layoutAll(view);
                GuiLayoutHarness.assertRowsFitTheRenderer(view, "thin fallback at 24 point");
                GuiLayoutHarness.assertContentScrollsInsideItsOwnRegions(view, "thin fallback at 24 point");
                GuiLayoutHarness.assertActionsReachable(view, "thin fallback at 24 point");
                GuiLayoutHarness.assertRowComponentsShareTheirTextBaseline(view, "thin fallback at 24 point");
                System.out.println("platform fallback at 24 point: " + describe(view));

                JCheckBox theme = (JCheckBox) GuiLayoutHarness.field(view, "darkModeCheck");
                theme.doClick();
                for (String key : keys) {
                    UIManager.put(key, new Font(Font.SANS_SERIF, Font.PLAIN, 24));
                }
                view.applyContentBounds();
                GuiLayoutHarness.layoutAll(view);
                GuiLayoutHarness.assertRowsFitTheRenderer(view, "thin fallback, dark palette, 24 point");
                GuiLayoutHarness.assertRowComponentsShareTheirTextBaseline(view,
                    "thin fallback, dark palette, 24 point");
                System.out.println("after theme switch: " + describe(view));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            } finally {
                for (Map.Entry<String, Object> entry : base.entrySet()) {
                    if (entry.getValue() instanceof Font font) {
                        UIManager.put(entry.getKey(), font);
                    }
                }
                view.shutdown();
            }
        });
    }

    private static String describe(FileVerifierView view) throws Exception {
        JList<?> list = (JList<?>) GuiLayoutHarness.field(view, "sourceList");
        JTable table = (JTable) GuiLayoutHarness.field(view, "resultsTable");
        JScrollPane listScroll = (JScrollPane) list.getParent().getParent();
        return "baseFont=" + UiTheme.baseFont().getSize2D()
            + " line=" + GuiLayoutHarness.lineHeight(UiTheme.baseFont())
            + " listCell=" + list.getUI().getCellBounds(list, 0, 0).height
            + " tableRow=" + table.getRowHeight(0)
            + " header=" + table.getTableHeader().getPreferredSize().height
            + " listContent=" + list.getPreferredSize().width
            + " listBudget=" + listScroll.getPreferredSize().width
            + " form=" + view.getPreferredSize().width + "x" + view.getPreferredSize().height;
    }
}
