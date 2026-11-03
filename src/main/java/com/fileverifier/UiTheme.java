package com.fileverifier;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTable;
import javax.swing.plaf.ColorUIResource;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.SwingUtilities;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.LayoutManager2;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Window;

/**
 * Every colour, font, and gap the window is allowed to use, in one place.
 *
 * <p>The point is not decoration: when the palette is a value instead of a literal sprinkled through
 * the layout, dark mode is a palette swap instead of a rewrite, contrast is checkable (see
 * {@link #contrastRatio}), and a redesign does not mean hunting {@code new Color(...)} calls across a
 * thousand-line file. The only colour literals in the codebase live here.</p>
 *
 * <p>{@link #install(boolean)} sets up FlatLaf and applies the widget polish; every token below is
 * plain Swing so the theme works even if FlatLaf is ever removed.</p>
 */
public final class UiTheme {

    /** A complete palette: no token is left to fall back to a look-and-feel default. */
    public record Palette(
        Color window,
        Color surface,
        Color alternateRow,
        Color text,
        Color muted,
        Color line,
        Color accent,
        Color onAccent,
        Color selection,
        Color danger,
        Color onDanger,
        Color logBackground,
        Color logText,
        Color ok,
        Color warn,
        Color bad,
        Color info) {

        public Palette {
            java.util.Objects.requireNonNull(window, "window");
            java.util.Objects.requireNonNull(text, "text");
            java.util.Objects.requireNonNull(accent, "accent");
        }
    }

    /**
     * Light palette. Text and badge colours are chosen to clear 4.5:1 against {@code window}
     * (UiThemeTest asserts this), which is why the greens and ambers are darker than they look
     * fashionable.
     */
    public static final Palette LIGHT = new Palette(
        new Color(0xFF, 0xFF, 0xFF),
        new Color(0xF8, 0xF9, 0xFA),
        new Color(0xF3, 0xF4, 0xF6),
        new Color(0x11, 0x18, 0x27),
        new Color(0x4B, 0x55, 0x63),
        new Color(0xD7, 0xDC, 0xE2),
        new Color(0x25, 0x63, 0xEB),
        new Color(0xFF, 0xFF, 0xFF),
        new Color(0xDB, 0xE4, 0xFF),
        new Color(0xB9, 0x1C, 0x1C),
        new Color(0xFF, 0xFF, 0xFF),
        new Color(0xF3, 0xF4, 0xF6),
        new Color(0x1F, 0x29, 0x37),
        new Color(0x16, 0x65, 0x34),
        new Color(0x92, 0x40, 0x0E),
        new Color(0xB9, 0x1C, 0x1C),
        new Color(0x1D, 0x4E, 0xD8));

    /** Dark palette, same tokens, same contrast margins. */
    public static final Palette DARK = new Palette(
        new Color(0x1E, 0x1E, 0x1E),
        new Color(0x24, 0x24, 0x26),
        new Color(0x26, 0x27, 0x2A),
        new Color(0xE5, 0xE7, 0xEB),
        new Color(0x9C, 0xA3, 0xAF),
        new Color(0x3F, 0x41, 0x45),
        new Color(0x60, 0xA5, 0xFA),
        new Color(0x0B, 0x12, 0x20),
        new Color(0x1E, 0x3A, 0x6E),
        new Color(0xF8, 0x71, 0x71),
        new Color(0x18, 0x0B, 0x0B),
        new Color(0x14, 0x15, 0x17),
        new Color(0xD1, 0xD5, 0xDB),
        new Color(0x4A, 0xDE, 0x80),
        new Color(0xFB, 0xBF, 0x24),
        new Color(0xF8, 0x71, 0x71),
        new Color(0x60, 0xA5, 0xFA));

    private static Palette active = LIGHT;
    /** The palette that was current before the last {@link #install}, used by {@link #restyle}. */
    private static Palette previous = LIGHT;

    private UiTheme() {
    }

    /**
     * Installs FlatLaf in the requested mode, applies the widget polish, and makes the matching
     * palette current. Safe to call again on a theme change.
     */
    public static void install(boolean dark) {
        install(dark, dark ? "com.formdev.flatlaf.FlatDarkLaf" : "com.formdev.flatlaf.FlatLightLaf");
    }

    /** A class name also lets the failure path be exercised without breaking the bundled dependency. */
    static void install(boolean dark, String lookAndFeel) {
        try {
            UIManager.setLookAndFeel(lookAndFeel);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException
                 | UnsupportedLookAndFeelException | RuntimeException failure) {
            installPlatformFallback(failure);
        } catch (NoClassDefFoundError missing) {
            // An incomplete FlatLaf is optional too; do not hide linkage errors in unrelated code.
            if (missing.getMessage() == null || !missing.getMessage().startsWith("com/formdev/flatlaf/")) {
                throw missing;
            }
            installPlatformFallback(missing);
        }
        previous = active;
        active = dark ? DARK : LIGHT;
        applyStyleKeys();
    }

    private static void installPlatformFallback(Throwable reason) {
        System.err.println("Theme unavailable (" + reason.getClass().getSimpleName()
            + "): " + reason.getMessage() + " - trying the platform look and feel.");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException
                 | UnsupportedLookAndFeelException | RuntimeException failure) {
            throw new IllegalStateException("No usable platform look and feel", failure);
        }
    }

    /** Refresh every live window, plus a detached headless view, without depending on FlatLaf. */
    public static void refresh(Container root) {
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
        }
        if (SwingUtilities.getWindowAncestor(root) == null) {
            SwingUtilities.updateComponentTreeUI(root);
        }
    }

    /**
     * The colour a label actually sits on. A transparent component paints no background of its own: what
     * is behind it is whatever its nearest opaque ancestor paints. Asking the component itself is a
     * different question, and answers it wrongly.
     */
    public static Color backgroundBehind(Component component) {
        for (Component at = component; at != null; at = at.getParent()) {
            if (at instanceof javax.swing.JComponent candidate && paintsOwnBackground(candidate)) {
                return candidate.getBackground();
            }
        }
        return active.window();
    }

    /**
     * Whether a component paints a background of its own. A Swing button is not "opaque" but FlatLaf
     * still fills it, so asking isOpaque() alone reports the wrong surface for a button.
     */
    private static boolean paintsOwnBackground(Component component) {
        if (component instanceof javax.swing.AbstractButton button) {
            return button.isContentAreaFilled();
        }
        if (component instanceof javax.swing.JComboBox<?> || component instanceof javax.swing.JTextField) {
            return true;
        }
        return component instanceof javax.swing.JComponent view && view.isOpaque();
    }

    /**
     * A palette colour marked as a look-and-feel colour. A plain colour handed to a component is
     * overwritten the next time the look-and-feel installs its defaults for that component - that is
     * what turned the primary button's blue into the look-and-feel's white. Marking it says "this one is
     * the theme's, leave it alone", and {@link #restyle} is what updates it afterwards.
     */
    private static Color asThemeColor(Color color) {
        return color instanceof ColorUIResource ? color : new ColorUIResource(color);
    }

    public static Palette palette() {
        return active;
    }

    /**
     * FlatLaf knobs. Set after {@code setup()} because installing a look-and-feel replaces the
     * defaults {@code UIManager} held before. Unknown keys are inert, so this list is limited to the
     * ones that visibly matter: corner radius, focus ring, grid removal, alternating rows, tab height.
     */
    private static void applyStyleKeys() {
        UIManager.put("Panel.background", asThemeColor(active.window()));
        UIManager.put("Label.foreground", asThemeColor(active.text()));
        UIManager.put("Button.background", asThemeColor(active.surface()));
        UIManager.put("Button.foreground", asThemeColor(active.text()));
        UIManager.put("CheckBox.background", asThemeColor(active.surface()));
        UIManager.put("CheckBox.foreground", asThemeColor(active.text()));
        UIManager.put("ToggleButton.foreground", asThemeColor(active.text()));
        UIManager.put("ComboBox.foreground", asThemeColor(active.text()));
        UIManager.put("List.background", asThemeColor(active.window()));
        UIManager.put("List.foreground", asThemeColor(active.text()));
        UIManager.put("Table.background", asThemeColor(active.window()));
        UIManager.put("Table.foreground", asThemeColor(active.text()));
        UIManager.put("TextField.background", asThemeColor(active.window()));
        UIManager.put("TextField.foreground", asThemeColor(active.text()));
        UIManager.put("ScrollPane.background", asThemeColor(active.window()));
        UIManager.put("Viewport.background", asThemeColor(active.window()));
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("Component.focusWidth", 2);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("Component.borderWidth", 1);
        UIManager.put("Button.minimumWidth", 76);
        UIManager.put("CheckBox.iconSize", 16);
        UIManager.put("ToggleButton.selectedBackground", asThemeColor(active.accent()));
        UIManager.put("List.selectionBackground", asThemeColor(active.selection()));
        UIManager.put("List.selectionForeground", asThemeColor(active.text()));
        UIManager.put("Table.showGrid", false);
        UIManager.put("Table.alternateRowColor", asThemeColor(active.alternateRow()));
        UIManager.put("Table.headerBackground", asThemeColor(active.surface()));
        UIManager.put("Table.headerSeparatorColor", asThemeColor(active.line()));
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("TabbedPane.tabHeight", 32);
        UIManager.put("TabbedPane.selectedBackground", asThemeColor(active.window()));
        UIManager.put("TabbedPane.contentAreaColor", asThemeColor(active.line()));
        UIManager.put("SplitPane.background", asThemeColor(active.line()));
        UIManager.put("ProgressBar.background", asThemeColor(active.alternateRow()));
        UIManager.put("ProgressBar.foreground", asThemeColor(active.accent()));
        UIManager.put("ProgressBar.selectionBackground", asThemeColor(active.text()));
        UIManager.put("ProgressBar.selectionForeground", asThemeColor(active.text()));
    }

    /** Relative luminance of a colour, per WCAG 2.1. */
    public static double luminance(Color color) {
        return 0.2126 * channel(color.getRed())
            + 0.7152 * channel(color.getGreen())
            + 0.0722 * channel(color.getBlue());
    }

    private static double channel(int value) {
        double srgb = value / 255.0;
        return srgb <= 0.03928 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
    }

    /** Contrast ratio between two colours: 1.0 (identical) to 21.0 (black on white). */
    public static double contrastRatio(Color a, Color b) {
        double light = Math.max(luminance(a), luminance(b));
        double dark = Math.min(luminance(a), luminance(b));
        return (light + 0.05) / (dark + 0.05);
    }

    // ------------------------------------------------------------- typography

    public static Font baseFont() {
        Font ui = UIManager.getFont("Label.font");
        Font foundation = ui != null ? ui : Font.getFont("Dialog");
        return foundation.deriveFont(Font.PLAIN, Math.max(13.0f, foundation.getSize2D()));
    }

    public static Font headingFont() {
        return baseFont().deriveFont(Font.BOLD, baseFont().getSize2D() * 15.0f / 13.0f);
    }

    public static Font captionFont() {
        return baseFont().deriveFont(Font.PLAIN, baseFont().getSize2D() * 12.0f / 13.0f);
    }

    public static Font monoFont() {
        return new Font(Font.MONOSPACED, Font.PLAIN, 12).deriveFont(baseFont().getSize2D() * 12.0f / 13.0f);
    }

    // ------------------------------------------------------------- components

    public static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(headingFont());
        label.setForeground(asThemeColor(active.text()));
        label.putClientProperty(LABEL, "heading");
        return label;
    }

    /** A quiet label: instructions, units, "what these ticks mean" notes. */
    public static JLabel caption(String text) {
        JLabel label = new JLabel(text);
        label.setFont(captionFont());
        label.setForeground(asThemeColor(active.muted()));
        label.putClientProperty(LABEL, "caption");
        return label;
    }

    /** Body text: the status line, anything that should read at the same weight as a control. */
    public static JLabel bodyLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(baseFont());
        label.setForeground(asThemeColor(active.text()));
        label.putClientProperty(LABEL, "body");
        return label;
    }

    /** Field labels sit above their field, left aligned, so scanning the form is vertical. */
    public static JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(captionFont());
        label.setForeground(asThemeColor(active.muted()));
        label.putClientProperty(LABEL, "field");
        return label;
    }

    public static JTextField field(int columns) {
        JTextField field = new JTextField(columns);
        field.setFont(baseFont());
        field.setBackground(asThemeColor(active.window()));
        field.setForeground(asThemeColor(active.text()));
        field.putClientProperty(SURFACE, Boolean.TRUE);
        return field;
    }

    public static JButton button(String text) {
        JButton button = new JButton(text);
        button.setFont(baseFont());
        // Stated, not inherited. A control that leaves its background to the look-and-feel keeps the
        // colour that the *previous* theme chose for it, which is how dark mode ends up with light
        // buttons carrying light text; a palette colour is one restyle() can find again.
        button.setBackground(asThemeColor(active.surface()));
        button.setForeground(asThemeColor(active.text()));
        button.putClientProperty(TEXT, Boolean.TRUE);
        return button;
    }

    /** The one button that starts the job: the only filled control in the window. */
    public static JButton primaryButton(String text) {
        JButton button = button(text);
        button.setBackground(asThemeColor(active.accent()));
        button.setForeground(asThemeColor(active.onAccent()));
        button.putClientProperty(FILL, "primary");
        return button;
    }

    public static JButton dangerButton(String text) {
        JButton button = button(text);
        button.setBackground(asThemeColor(active.danger()));
        button.setForeground(asThemeColor(active.onDanger()));
        button.putClientProperty(FILL, "danger");
        return button;
    }

    /**
     * One half of a segmented control. FlatLaf draws the pair as a single pill with the halves joined
     * at the shared edge, which is how a mode choice reads today: two labels, one control.
     */
    public static javax.swing.JToggleButton segmentButton(String text, String position) {
        javax.swing.JToggleButton button = new javax.swing.JToggleButton(text);
        button.setFont(baseFont());
        button.putClientProperty("JButton.buttonType", "segmented");
        button.putClientProperty("segmentLocation", position);
        button.putClientProperty(FILL, "segment");
        button.setFocusPainted(false);
        return button;
    }

    /** Style a mode segment for whether it is the active mode. */
    public static void paintSegment(javax.swing.AbstractButton button, boolean selected) {
        button.setBackground(selected ? active.accent() : active.surface());
        button.setForeground(selected ? active.onAccent() : active.text());
    }

    public static JCheckBox check(String text) {
        JCheckBox check = new JCheckBox(text);
        check.setFont(baseFont());
        check.setBackground(asThemeColor(active.surface()));
        check.setForeground(asThemeColor(active.text()));
        check.setOpaque(false);
        check.putClientProperty(TEXT, Boolean.TRUE);
        return check;
    }

    public static <E> JComboBox<E> comboBox(E[] items) {
        JComboBox<E> box = new JComboBox<>(items);
        box.setFont(baseFont());
        return box;
    }

    public static <E> JList<E> list(javax.swing.ListModel<E> model) {
        JList<E> list = new JList<>(model);
        list.setFont(baseFont());
        list.setBackground(asThemeColor(active.window()));
        list.setForeground(asThemeColor(active.text()));
        list.putClientProperty(SURFACE, Boolean.TRUE);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        // No row height is pinned here: the view measures the cell renderer that paints these rows and
        // sizes rows from that, at whatever font is current.
        return list;
    }

    public static JTable table(javax.swing.table.TableModel model) {
        JTable table = new JTable(model);
        table.setFont(monoFont());
        table.setBackground(asThemeColor(active.window()));
        table.setForeground(asThemeColor(active.text()));
        table.putClientProperty(SURFACE, Boolean.TRUE);
        table.setShowGrid(false);
        table.setIntercellSpacing(new java.awt.Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(captionFont());
        return table;
    }

    public static JProgressBar progressBar() {
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        bar.setFont(captionFont());
        bar.setPreferredSize(new java.awt.Dimension(240, 22));
        return bar;
    }

    /** A panel with a hairline edge and inner padding: the card, replacing the group boxes. */
    public static JPanel card() {
        JPanel panel = new JPanel();
        panel.setBackground(asThemeColor(active.surface()));
        panel.setBorder(cardBorder());
        panel.putClientProperty(CARD, Boolean.TRUE);
        return panel;
    }

    private static Border cardBorder() {
        return new CompoundBorder(
            BorderFactory.createLineBorder(active.line(), 1),
            new EmptyBorder(10, 12, 10, 12));
    }

    /** Client properties naming everything this class styled, so a palette swap can find them again. */
    private static final String CARD = "fcu.card";
    private static final String EDGE = "fcu.edge";
    private static final String LABEL = "fcu.label";
    private static final String FILL = "fcu.fill";
    private static final String TEXT = "fcu.text";
    private static final String SURFACE = "fcu.surface";
    private static final String LOG = "fcu.log";

    /** The log area: monospaced, recessed against the rest of the window, word wrapped. */
    public static void asLog(javax.swing.JTextArea area) {
        area.setFont(monoFont());
        area.setBackground(asThemeColor(active.logBackground()));
        area.setForeground(asThemeColor(active.logText()));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.putClientProperty(LOG, Boolean.TRUE);
    }

    /**
     * Mark a container as a surface of the window (the root panel, a region panel). A transparent panel
     * still hands its background to the controls inside it - AWT resolves a null background by walking
     * up the parents - so leaving one painted with the old palette is how dark mode ends up with light
     * buttons carrying light text.
     */
    public static void surface(javax.swing.JComponent component) {
        component.setBackground(asThemeColor(active.window()));
        component.putClientProperty(SURFACE, Boolean.TRUE);
    }

    /** A hairline panel edge (list viewports), tagged so it follows the palette too. */
    public static void edge(javax.swing.JScrollPane scroll) {
        scroll.setBorder(BorderFactory.createLineBorder(active.line(), 1));
        scroll.setBackground(asThemeColor(active.window()));
        scroll.getViewport().setBackground(asThemeColor(active.window()));
        scroll.putClientProperty(EDGE, Boolean.TRUE);
    }

    /**
     * Where this component's text sits inside its own preferred height, measured the way Swing measures it
     * for painting: {@code JComponent.getBaseline} asked at the component's preferred size. -1 when the
     * component has no text baseline to align to - a scroll pane, a panel, a progress bar.
     */
    public static int baselineOf(Component component) {
        if (!(component instanceof javax.swing.JComponent cell)) {
            return -1;
        }
        Dimension size = cell.getPreferredSize();
        int baseline = cell.getBaseline(size.width, size.height);
        return baseline >= 0 && baseline <= size.height ? baseline : -1;
    }

    /**
     * The deepest ascent in one row of components: the text line its row-mates have to be dropped to. A
     * field and a button reach lower than a small caption label does, so the label is the one that moves.
     */
    public static int rowAscent(javax.swing.JComponent... components) {
        int ascent = 0;
        for (javax.swing.JComponent component : components) {
            ascent = Math.max(ascent, Math.max(0, baselineOf(component)));
        }
        return ascent;
    }

    /**
     * GridBagLayout has no baseline constraint, so the alignment is expressed as a top inset: drop each
     * component by the difference between the row's deepest ascent and its own and their text lines up.
     * A component with no baseline keeps the insets it was given and is aligned by the row's own centring.
     */
    public static Insets baselineRowInsets(Insets base, int rowAscent, javax.swing.JComponent component) {
        int baseline = baselineOf(component);
        int extra = baseline >= 0 ? Math.max(0, rowAscent - baseline) : 0;
        return new Insets(base.top + extra, base.left, base.bottom, base.right);
    }

    /**
     * A row of controls that wraps instead of widening the window. {@code FlowLayout} reports a minimum
     * width equal to its preferred width - it never breaks a line - so a row holding two path fields and
     * two buttons pins the whole form to 700 pixels and a narrow screen has to scroll to reach Run.
     * Here the preferred size is still one line, but the minimum is the widest single control: the row
     * breaks at the width it is given, and Run and Cancel stay on screen.
     */
    public static final class WrapLayout implements java.awt.LayoutManager2 {
        private final int alignment;
        private final int hgap;
        private final int vgap;

        public WrapLayout(int alignment, int hgap, int vgap) {
            this.alignment = alignment;
            this.hgap = hgap;
            this.vgap = vgap;
        }

        @Override
        public void addLayoutComponent(Component component, Object constraints) {
            // Order comes from the container itself: JComponent reports children in the order they were added.
        }

        @Override
        public void addLayoutComponent(String name, Component component) {
            addLayoutComponent(component, null);
        }

        @Override
        public void removeLayoutComponent(Component component) {
            // Nothing is cached, so there is nothing to drop.
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            int naturalWidth = 0;
            LineMetrics oneLine = new LineMetrics();
            for (Component child : visible(parent)) {
                Dimension size = child.getPreferredSize();
                naturalWidth += size.width + (naturalWidth == 0 ? 0 : hgap);
                oneLine.add(child, size);
            }
            int inner = parent.getWidth() - insets.left - insets.right;
            // Height is reported for the width this row actually has. A row that has had to break gets
            // the height its lines need, so a wrapped control is never cut in half by its own panel.
            int height = inner > 0 && inner < naturalWidth
                ? stackedHeight(parent, inner, false) : oneLine.height();
            return new Dimension(naturalWidth + insets.left + insets.right,
                Math.max(oneLine.height(), height) + insets.top + insets.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            int widest = 0;
            int tallest = 0;
            for (Component child : visible(parent)) {
                Dimension size = child.getMinimumSize();
                widest = Math.max(widest, size.width);
                tallest = Math.max(tallest, size.height);
            }
            // One control wide and one line tall. Below the row's natural width the window scrolls - the
            // frame's viewport gives a view at least its preferred width - rather than the window claiming
            // it can show a whole row of controls at half their width.
            return new Dimension(widest + insets.left + insets.right,
                tallest + insets.top + insets.bottom);
        }

        /** How tall the row becomes when it is laid out at this width, line by line. */
        private int stackedHeight(Container parent, int width, boolean minimum) {
            int height = 0;
            int x = 0;
            LineMetrics line = new LineMetrics();
            for (Component child : visible(parent)) {
                Dimension size = minimum ? child.getMinimumSize() : child.getPreferredSize();
                if (line.count > 0 && size.width > 0 && x + size.width > width) {
                    height += line.height() + vgap;
                    line = new LineMetrics();
                    x = 0;
                }
                line.add(child, size);
                x += size.width + hgap;
            }
            return height + line.height();
        }

        /**
         * What one line of this row needs once its components share a text line: the deepest ascent and the
         * deepest descent among the components that have a baseline, never less than the tallest component.
         */
        private static final class LineMetrics {
            private int ascent;
            private int descent;
            private int tallest;
            private int count;

            void add(Component component, Dimension size) {
                count++;
                if (size.width <= 0 || size.height <= 0) {
                    // A caption with nothing to say yet occupies no box: it must not force a line break and
                    // must not raise the line's height. It gets its size and revalidates when text arrives.
                    return;
                }
                tallest = Math.max(tallest, size.height);
                int baseline = baselineOf(component);
                if (baseline >= 0) {
                    ascent = Math.max(ascent, baseline);
                    descent = Math.max(descent, size.height - baseline);
                }
            }

            int height() {
                return Math.max(tallest, ascent + descent);
            }

            int ascent() {
                return ascent;
            }
        }

        @Override
        public Dimension maximumLayoutSize(Container parent) {
            return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        @Override
        public float getLayoutAlignmentX(Container parent) {
            return alignment == FlowLayout.LEFT ? 0f : alignment == FlowLayout.RIGHT ? 1f : 0.5f;
        }

        @Override
        public float getLayoutAlignmentY(Container parent) {
            return 0.5f;
        }

        @Override
        public void invalidateLayout(Container parent) {
            // Stateless: sizes are read from the children every time.
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int available = parent.getWidth() - insets.left - insets.right;
            int rowTop = insets.top;
            int x = insets.left;
            java.util.List<Component> row = new java.util.ArrayList<>();
            java.util.List<Dimension> sizes = new java.util.ArrayList<>();
            LineMetrics line = new LineMetrics();
            for (Component child : visible(parent)) {
                Dimension size = child.getPreferredSize();
                if (!row.isEmpty() && size.width > 0 && x + size.width > insets.left + available) {
                    rowTop += placeRow(row, sizes, line, rowTop, available, insets);
                    row.clear();
                    sizes.clear();
                    line = new LineMetrics();
                    x = insets.left;
                }
                row.add(child);
                sizes.add(size);
                line.add(child, size);
                x += size.width + hgap;
            }
            if (!row.isEmpty()) {
                placeRow(row, sizes, line, rowTop, available, insets);
            }
        }

        /**
         * Lays one finished line. Components that report a text baseline are dropped so their baselines
         * coincide - a caption label beside a combo or a field then reads on the same line as the text
         * inside it instead of floating above it. Components with no baseline are centred against the line.
         * The whole line is then shifted sideways for the row's alignment. Returns the height it used.
         */
        private int placeRow(java.util.List<Component> row, java.util.List<Dimension> sizes, LineMetrics line,
                             int top, int available, Insets insets) {
            int used = 0;
            for (int i = 0; i < row.size(); i++) {
                used += sizes.get(i).width + (i == 0 ? 0 : hgap);
            }
            int shift = alignment == FlowLayout.LEFT ? 0
                : alignment == FlowLayout.RIGHT ? Math.max(0, available - used)
                : Math.max(0, (available - used) / 2);
            int height = line.height();
            int ascent = line.ascent();
            int x = insets.left + shift;
            for (int i = 0; i < row.size(); i++) {
                Component child = row.get(i);
                Dimension size = sizes.get(i);
                int baseline = baselineOf(child);
                int y = baseline >= 0 && ascent > 0 ? top + (ascent - baseline)
                    : top + (height - size.height) / 2;
                child.setBounds(x, y, size.width, size.height);
                x += size.width + hgap;
            }
            return height;
        }

        private static java.util.List<Component> visible(Container parent) {
            java.util.List<Component> shown = new java.util.ArrayList<>();
            for (Component child : parent.getComponents()) {
                if (child.isVisible()) {
                    shown.add(child);
                }
            }
            return shown;
        }
    }
    /** The narrowest row this window will use for a list or table row: a pointer target, not a font. */
    public static final int MIN_ROW_HEIGHT = 20;

    /**
     * How tall a row must be for the component that actually paints it. Rows used to be pinned to 20
     * pixels, which clips a 24 point interface; asking the renderer its own preferred height keeps the
     * row honest when the font, the palette or the look and feel changes.
     */
    public static int rowHeightFor(javax.swing.JComponent cell) {
        return Math.max(MIN_ROW_HEIGHT, cell.getPreferredSize().height + 2);
    }

    /**
     * A width expressed as a number of character columns in one font. Regions that must not grow with
     * their content - a file list, the results table, the status line - are sized this way, so a bigger
     * font widens them proportionally instead of leaving them unusably narrow.
     */
    public static int widthInColumns(int columns, Font font) {
        return columns * Math.max(1, textWidth("0", font));
    }

    /** How wide this text is in this font, measured the same way every bounded region is sized. */
    public static int textWidth(String text, Font font) {
        java.awt.font.FontRenderContext context = new java.awt.font.FontRenderContext(null, true, true);
        return (int) Math.ceil(font.getStringBounds(text, context).getWidth());
    }

    /**
     * Re-apply the current palette to components already on screen. Colours chosen at construction
     * time would otherwise survive a theme switch, which is the usual way "dark mode support" ends up
     * half working. Everything {@code UiTheme} created is tagged, so this walks and re-paints.
     */
    public static void restyle(Container root) {
        if (root == null) {
            return;
        }
        // First the blunt pass: anything painted with a colour from the palette that was current is
        // given this palette's version of it. Tag-based handling below covers the things a colour
        // comparison cannot see (card edges, the log, segment selection state).
        if (root instanceof javax.swing.JComponent component) {
            if (paintsOwnBackground(component)) {
                Color background = remapBackground(component.getBackground());
                if (background != null) {
                    component.setBackground(background);
                }
            }
            Color foreground = remapForeground(component.getForeground());
            if (foreground != null) {
                component.setForeground(foreground);
            }
        }
        if (root instanceof JPanel panel && Boolean.TRUE.equals(panel.getClientProperty(CARD))) {
            panel.setBackground(asThemeColor(active.surface()));
            Border border = panel.getBorder();
            if (border instanceof CompoundBorder compound && compound.getOutsideBorder() instanceof LineBorder) {
                panel.setBorder(cardBorder());
            }
        }
        if (root instanceof javax.swing.JScrollPane scroll && Boolean.TRUE.equals(scroll.getClientProperty(EDGE))) {
            scroll.setBorder(BorderFactory.createLineBorder(active.line(), 1));
            scroll.setBackground(asThemeColor(active.window()));
            scroll.getViewport().setBackground(asThemeColor(active.window()));
        }
        if (root instanceof javax.swing.JComponent component
            && Boolean.TRUE.equals(component.getClientProperty(SURFACE))) {
            component.setBackground(asThemeColor(active.window()));
            component.setForeground(asThemeColor(active.text()));
            if (component instanceof JTable table) {
                table.setFont(monoFont());
                table.getTableHeader().setFont(captionFont());
                table.getTableHeader().setBackground(asThemeColor(active.surface()));
                table.getTableHeader().setForeground(asThemeColor(active.text()));
            }
        }
        if (root instanceof javax.swing.JTextArea area && Boolean.TRUE.equals(area.getClientProperty(LOG))) {
            area.setFont(monoFont());
            area.setBackground(asThemeColor(active.logBackground()));
            area.setForeground(asThemeColor(active.logText()));
        }
        // Fonts follow the interface font too. A control built at 13 point stays at 13 point through a
        // look-and-feel switch unless somebody says so, which is how a scaled display ends up with
        // 24 point headings above 13 point fields and rows.
        if (root instanceof javax.swing.text.JTextComponent text
            && Boolean.TRUE.equals(((javax.swing.JComponent) root).getClientProperty(SURFACE))) {
            text.setFont(baseFont());
        }
        if (root instanceof JComboBox<?> box) {
            box.setFont(baseFont());
        }
        if (root instanceof JList<?> list) {
            list.setFont(baseFont());
        }
        if (root instanceof javax.swing.AbstractButton button
            && (Boolean.TRUE.equals(button.getClientProperty(TEXT))
                || "primary".equals(button.getClientProperty(FILL))
                || "danger".equals(button.getClientProperty(FILL))
                || "segment".equals(button.getClientProperty(FILL)))) {
            button.setFont(baseFont());
        }
        if (root instanceof JLabel label) {
            Object kind = label.getClientProperty(LABEL);
            if ("heading".equals(kind)) {
                label.setFont(headingFont());
                label.setForeground(asThemeColor(active.text()));
            } else if ("body".equals(kind)) {
                label.setFont(baseFont());
                label.setForeground(asThemeColor(active.text()));
            } else if ("caption".equals(kind) || "field".equals(kind)) {
                label.setFont(captionFont());
                label.setForeground(asThemeColor(active.muted()));
            }
        }
        if (root instanceof javax.swing.AbstractButton button) {
            Object fill = button.getClientProperty(FILL);
            if ("primary".equals(fill)) {
                button.setBackground(asThemeColor(active.accent()));
                button.setForeground(asThemeColor(active.onAccent()));
            } else if ("danger".equals(fill)) {
                button.setBackground(asThemeColor(active.danger()));
                button.setForeground(asThemeColor(active.onDanger()));
            } else if ("segment".equals(fill)) {
                paintSegment(button, button.getModel().isSelected());
            } else if (Boolean.TRUE.equals(button.getClientProperty(TEXT))) {
                button.setForeground(asThemeColor(active.text()));
            }
        }
        for (Component child : root.getComponents()) {
            if (child instanceof Container nested) {
                restyle(nested);
            }
        }
    }

    /** A background painted with the old palette, in the new palette. Unknown colours are left alone. */
    private static Color remapBackground(Color color) {
        if (color == null) {
            return null;
        }
        if (color.equals(previous.window())) {
            return active.window();
        }
        if (color.equals(previous.surface())) {
            return active.surface();
        }
        if (color.equals(previous.alternateRow())) {
            return active.alternateRow();
        }
        if (color.equals(previous.logBackground())) {
            return active.logBackground();
        }
        if (color.equals(previous.line())) {
            return active.line();
        }
        if (color.equals(previous.selection())) {
            return active.selection();
        }
        return null;
    }

    private static Color remapForeground(Color color) {
        if (color == null) {
            return null;
        }
        if (color.equals(previous.text())) {
            return active.text();
        }
        if (color.equals(previous.muted())) {
            return active.muted();
        }
        if (color.equals(previous.onAccent())) {
            return active.onAccent();
        }
        if (color.equals(previous.onDanger())) {
            return active.onDanger();
        }
        if (color.equals(previous.logText())) {
            return active.logText();
        }
        if (color.equals(previous.ok())) {
            return active.ok();
        }
        if (color.equals(previous.warn())) {
            return active.warn();
        }
        if (color.equals(previous.bad())) {
            return active.bad();
        }
        if (color.equals(previous.info())) {
            return active.info();
        }
        return null;
    }

    public static Border gutter() {
        return new EmptyBorder(SPACING);
    }

    /** Outer margin of each region: one value, so the window has a single rhythm. */
    public static final java.awt.Insets SPACING = new java.awt.Insets(12, 16, 12, 16);

    /** Colour a status word or badge by outcome kind. */
    public static Color outcomeColor(String kind) {
        return switch (kind) {
            case "OK", "MATCHED" -> active.ok();
            // Nothing was checked about a copied file, so it is not coloured like a file that passed a
            // check; the green would be read as "verified" by someone who never verified anything.
            case "COPIED" -> active.muted();
            case "WOULD_COPY" -> active.info();
            case "MISMATCH" -> active.bad();
            case "MISSING" -> active.warn();
            case "EXTRA" -> active.info();
            case "TRUNCATED", "INFO", "WARNING", "TRANSFERRING" -> active.muted();
            default -> active.text();
        };
    }
}
