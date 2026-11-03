package com.fileverifier;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The theme is the part of a UI redesign that can be checked without a screen: it is values, not
 * pixels. Two things matter and both are asserted here - every text/badge pairing clears the WCAG
 * contrast bar, and switching palette actually repaints the widgets that were built under the old one.
 */
class UiThemeTest {

    @Test
    void contrastMathIsTheStandardOne() {
        assertEquals(21.0, UiTheme.contrastRatio(Color.BLACK, Color.WHITE), 0.01);
        assertEquals(1.0, UiTheme.contrastRatio(Color.MAGENTA, Color.MAGENTA), 0.001);
    }

    @Test
    void lightPaletteIsReadable() {
        assertPalette(UiTheme.LIGHT);
    }

    @Test
    void darkPaletteIsReadable() {
        assertPalette(UiTheme.DARK);
    }

    /** 4.5:1 is the WCAG 2.1 bar for body text; badges and captions are held to the same bar. */
    private static void assertPalette(UiTheme.Palette palette) {
        assertRatio(palette.text(), palette.window(), "body text");
        assertRatio(palette.muted(), palette.window(), "muted text");
        assertRatio(palette.onAccent(), palette.accent(), "primary button label");
        assertRatio(palette.onDanger(), palette.danger(), "cancel button label");
        assertRatio(palette.logText(), palette.logBackground(), "log text");
        assertRatio(palette.ok(), palette.window(), "OK badge");
        assertRatio(palette.warn(), palette.window(), "MISSING badge");
        assertRatio(palette.bad(), palette.window(), "MISMATCH badge");
        assertRatio(palette.info(), palette.window(), "EXTRA badge");
        assertRatio(palette.text(), palette.alternateRow(), "text on an alternating row");
    }

    private static void assertRatio(Color foreground, Color background, String what) {
        double ratio = UiTheme.contrastRatio(foreground, background);
        assertTrue(ratio >= 4.5,
            what + " is " + String.format("%.2f", ratio) + ":1, below the 4.5:1 bar: "
                + foreground + " on " + background);
    }

    @Test
    void installingSelectsTheMatchingPaletteAndStylesWidgets() {
        UiTheme.install(true);
        assertEquals(UiTheme.DARK, UiTheme.palette());
        assertEquals("com.formdev.flatlaf.FlatDarkLaf", javax.swing.UIManager.getLookAndFeel().getClass().getName());
        assertEquals(Boolean.FALSE, javax.swing.UIManager.get("Table.showGrid"));

        UiTheme.install(false);
        assertEquals(UiTheme.LIGHT, UiTheme.palette());
        assertEquals("com.formdev.flatlaf.FlatLightLaf", javax.swing.UIManager.getLookAndFeel().getClass().getName());
        assertEquals(Boolean.FALSE, javax.swing.UIManager.get("Table.showGrid"));
    }

    @Test
    void unavailableOrUnsupportedThemeFallsBackButFatalErrorsEscape() {
        try {
            UiTheme.install(true, "not.present.Theme");
            assertEquals(javax.swing.UIManager.getSystemLookAndFeelClassName(),
                javax.swing.UIManager.getLookAndFeel().getClass().getName());
            assertEquals(UiTheme.DARK, UiTheme.palette());

            UiTheme.install(false, UnsupportedTheme.class.getName());
            assertEquals(javax.swing.UIManager.getSystemLookAndFeelClassName(),
                javax.swing.UIManager.getLookAndFeel().getClass().getName());
            assertEquals(UiTheme.LIGHT, UiTheme.palette());
            assertThrows(AssertionError.class, () -> UiTheme.install(false, FatalTheme.class.getName()));
            assertThrows(NoClassDefFoundError.class, () -> UiTheme.install(false, UnrelatedLinkageTheme.class.getName()));
        } finally {
            UiTheme.install(false);
        }
    }

    public static final class UnsupportedTheme extends javax.swing.plaf.metal.MetalLookAndFeel {
        private static final long serialVersionUID = 1L;
        @Override public boolean isSupportedLookAndFeel() { return false; }
    }

    public static final class FatalTheme extends javax.swing.plaf.metal.MetalLookAndFeel {
        private static final long serialVersionUID = 1L;
        @Override public boolean isSupportedLookAndFeel() { throw new AssertionError("fatal"); }
    }

    public static final class UnrelatedLinkageTheme extends javax.swing.plaf.metal.MetalLookAndFeel {
        private static final long serialVersionUID = 1L;
        @Override public boolean isSupportedLookAndFeel() { throw new NoClassDefFoundError("other/Dependency"); }
    }

    @Test
    void aCardCarriesItsOwnEdgeAndIsTaggedForRepainting() {
        UiTheme.install(false);
        JPanel card = UiTheme.card();
        assertTrue(card.getBorder() instanceof CompoundBorder, "a card is an edge plus inner padding");
        LineBorder outer = (LineBorder) ((CompoundBorder) card.getBorder()).getOutsideBorder();
        assertEquals(UiTheme.LIGHT.line(), outer.getLineColor());
    }

    /**
     * The part that usually breaks: a widget painted with light colours survives a switch to dark mode
     * unless something finds it again. UiTheme tags what it created, and restyle() walks for those tags.
     */
    @Test
    void switchingPaletteRepaintsWhatWasAlreadyBuilt() {
        UiTheme.install(false);
        JPanel card = UiTheme.card();
        JLabel heading = UiTheme.heading("Source files");
        JLabel note = UiTheme.caption("unticked is never copied or verified");
        JButton run = UiTheme.primaryButton("Run job");
        JButton cancel = UiTheme.dangerButton("Cancel");
        JPanel root = new JPanel();
        root.add(card);
        root.add(heading);
        root.add(note);
        root.add(run);
        root.add(cancel);

        assertEquals(UiTheme.LIGHT.surface(), card.getBackground());
        assertEquals(UiTheme.LIGHT.accent(), run.getBackground());

        UiTheme.install(true);
        UiTheme.restyle(root);

        assertEquals(UiTheme.DARK.surface(), card.getBackground());
        assertEquals(UiTheme.DARK.line(),
            ((LineBorder) ((CompoundBorder) card.getBorder()).getOutsideBorder()).getLineColor());
        assertEquals(UiTheme.DARK.text(), heading.getForeground());
        assertEquals(UiTheme.DARK.muted(), note.getForeground());
        assertEquals(UiTheme.DARK.accent(), run.getBackground());
        assertEquals(UiTheme.DARK.onAccent(), run.getForeground());
        assertEquals(UiTheme.DARK.danger(), cancel.getBackground());

        assertRatio(note.getForeground(), card.getBackground(), "note on a card after a theme switch");
        UiTheme.install(false);
    }

    @Test
    void outcomeKindsMapToColours() {
        UiTheme.install(false);
        assertEquals(UiTheme.LIGHT.ok(), UiTheme.outcomeColor("OK"));
        // A file that was copied but never compared must not wear the colour of a file that passed a
        // check: that green would be read as "verified" by someone who verified nothing.
        assertEquals(UiTheme.LIGHT.muted(), UiTheme.outcomeColor("COPIED"));
        assertEquals(UiTheme.LIGHT.info(), UiTheme.outcomeColor("WOULD_COPY"));
        assertEquals(UiTheme.LIGHT.bad(), UiTheme.outcomeColor("MISMATCH"));
        assertEquals(UiTheme.LIGHT.warn(), UiTheme.outcomeColor("MISSING"));
        assertEquals(UiTheme.LIGHT.info(), UiTheme.outcomeColor("EXTRA"));
        assertEquals(UiTheme.LIGHT.muted(), UiTheme.outcomeColor("TRUNCATED"));
    }

    @Test
    void typeHasThreeWeightsAndAMonoFaceForHashes() {
        UiTheme.install(false);
        assertTrue(UiTheme.headingFont().getSize() > UiTheme.baseFont().getSize());
        assertTrue(UiTheme.baseFont().getSize() > UiTheme.captionFont().getSize());
        assertEquals("Monospaced", UiTheme.monoFont().getName());
        assertNotNull(UiTheme.baseFont().getFamily());
    }

    /**
     * The thin jar has no FlatLaf, so the wrapping rows are laid out by the platform look and feel's metrics.
     * A caption label is shallower than a field or a button, and a row that pins every control to its own top
     * puts the label above the text inside the control next to it. Baseline alignment has to hold there too,
     * at normal and enlarged font sizes, and on both lines of a row that had to break.
     */
    @Test
    void wrappedRowsLineUpOnTheTextLineUnderThePlatformLookAndFeelAtBothFontSizes() throws Exception {
        javax.swing.LookAndFeel previous = UIManager.getLookAndFeel();
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            for (int points : new int[]{13, 24}) {
                JCheckBox check = new JCheckBox("Hidden files");
                JLabel caption = new JLabel("Symbolic links");
                JComboBox<String> combo = new JComboBox<>(new String[]{"Skip symbolic links"});
                JButton button = new JButton("Scan files");
                for (JComponent control : new JComponent[]{check, combo, button}) {
                    control.setFont(control.getFont().deriveFont((float) points));
                }
                caption.setFont(caption.getFont().deriveFont(Font.PLAIN, points * 12.0f / 13.0f));
                JComponent noText = new JComponent() { };
                noText.setPreferredSize(new Dimension(40, points * 2 + 6));

                JPanel row = new JPanel(new UiTheme.WrapLayout(FlowLayout.LEFT, 10, 0));
                row.add(check);
                row.add(caption);
                row.add(combo);
                row.add(button);
                row.add(noText);
                int natural = row.getPreferredSize().width;
                int oneLineHeight = row.getPreferredSize().height;
                assertTrue(natural > 260, "this row should be wide enough to have to break");

                row.setSize(row.getPreferredSize());
                row.doLayout();
                int captionLine = caption.getY() + UiTheme.baselineOf(caption);
                int comboLine = combo.getY() + UiTheme.baselineOf(combo);
                System.out.println("platform L&F " + points + "pt: label baseline " + captionLine
                    + " combo baseline " + comboLine + " label top " + caption.getY()
                    + " combo top " + combo.getY() + " row preferred height " + oneLineHeight
                    + " (label ascent " + UiTheme.baselineOf(caption)
                    + ", combo ascent " + UiTheme.baselineOf(combo) + ")");
                assertEquals(comboLine, captionLine, "caption dropped off the combo's text line at " + points + "pt");
                assertTrue(UiTheme.baselineOf(caption) < UiTheme.baselineOf(combo),
                    "the test is only meaningful if the caption's text starts higher than the combo's");
                assertTrue(caption.getY() > combo.getY(), "the shallower label should have been dropped, not pinned high");
                assertTrue(noText.getY() >= 0, "a component with no baseline should stay inside the row");
                int bandTop = Integer.MAX_VALUE;
                int bandBottom = Integer.MIN_VALUE;
                for (JComponent control : new JComponent[]{check, caption, combo, button, noText}) {
                    bandTop = Math.min(bandTop, control.getY());
                    bandBottom = Math.max(bandBottom, control.getY() + control.getHeight());
                }
                int middle = bandTop + (bandBottom - bandTop) / 2;
                assertTrue(Math.abs(noText.getY() + noText.getHeight() / 2 - middle) <= 1,
                    "a component with no baseline should be centred on the line: its middle "
                        + (noText.getY() + noText.getHeight() / 2) + " vs line middle " + middle);
                GuiLayoutHarness.assertRowComponentsShareTheirTextBaseline(row,
                    "platform L&F " + points + "pt, one line");

                row.setSize(new Dimension(natural / 2, oneLineHeight * 3));
                row.doLayout();
                assertTrue(combo.getY() > check.getY(),
                    "the row did not actually break into more than one line at " + (natural / 2) + "px: combo y "
                        + combo.getY() + " vs checkbox y " + check.getY());
                GuiLayoutHarness.assertRowComponentsShareTheirTextBaseline(row,
                    "platform L&F " + points + "pt, wrapped at " + (natural / 2) + "px");
            }
        } finally {
            UIManager.setLookAndFeel(previous);
            UiTheme.install(false);
        }
    }

    /**
     * GridBagLayout has no baseline constraint, so the directory rows express it as a top inset. Without the
     * correction the label is pinned to the top of the row band and reads above the path in the field.
     */
    @Test
    void gridBagDirectoryRowsPutTheirLabelOnTheFieldTextLine() throws Exception {
        UiTheme.install(false);
        JLabel label = UiTheme.fieldLabel("Source directory");
        JTextField field = UiTheme.field(24);
        JButton browse = UiTheme.button("Browse");
        java.awt.Insets base = new java.awt.Insets(2, 0, 2, 8);
        int ascent = UiTheme.rowAscent(label, field, browse);
        JPanel grid = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints constraints = new java.awt.GridBagConstraints();
        constraints.anchor = java.awt.GridBagConstraints.NORTHWEST;
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.insets = UiTheme.baselineRowInsets(base, ascent, label);
        grid.add(label, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0;
        constraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        constraints.insets = UiTheme.baselineRowInsets(base, ascent, field);
        grid.add(field, constraints);
        constraints.gridx = 2;
        constraints.weightx = 0;
        constraints.fill = java.awt.GridBagConstraints.NONE;
        constraints.insets = UiTheme.baselineRowInsets(base, ascent, browse);
        grid.add(browse, constraints);

        grid.setSize(grid.getPreferredSize());
        grid.doLayout();
        int labelLine = label.getY() + UiTheme.baselineOf(label);
        int fieldLine = field.getY() + UiTheme.baselineOf(field);
        System.out.println("grid row: row ascent " + ascent + " label baseline " + labelLine
            + " field baseline " + fieldLine + " label top " + label.getY() + " field top " + field.getY());
        assertTrue(UiTheme.baselineOf(label) < UiTheme.baselineOf(field));
        assertTrue(label.getY() > base.top, "the label should be dropped by the baseline correction");
        assertEquals(fieldLine, labelLine, "directory label sits off the field's text line");
        GuiLayoutHarness.assertRowComponentsShareTheirTextBaseline(grid, "grid directory row");
    }

    /**
     * The vertical gap between wrapped lines is part of the height this layout reports, so it has to be part
     * of where the next line starts as well. Two identical rows - one configured with no gap, one with 8 -
     * are laid out at the same width: every line below the first has to move down by one gap for each break
     * above it, the space between consecutive lines has to be at least that gap, and the preferred height has
     * to grow by gaps x the gap. The zero-gap row is the behaviour the window itself uses, since
     * every control row in the view is built with vgap 0, so it is pinned here rather than assumed.
     */
    @Test
    void wrappedLinesKeepTheConfiguredVerticalGap() throws Exception {
        UiTheme.install(false);
        JPanel[] rows = new JPanel[2];
        int[] gaps = new int[]{0, 8};
        for (int index = 0; index < rows.length; index++) {
            JPanel row = new JPanel(new UiTheme.WrapLayout(FlowLayout.LEFT, 10, gaps[index]));
            row.add(new JCheckBox("Hidden files"));
            row.add(new JLabel("Symbolic links"));
            row.add(new JComboBox<>(new String[]{"Skip symbolic links"}));
            row.add(new JButton("Scan files"));
            rows[index] = row;
        }
        int natural = rows[0].getPreferredSize().width;
        int width = natural / 2;
        for (JPanel row : rows) {
            row.setSize(new Dimension(width, 600));
        }
        Dimension zeroGap = rows[0].getPreferredSize();
        Dimension eightGap = rows[1].getPreferredSize();
        for (JPanel row : rows) {
            row.doLayout();
        }
        int[] zeroTops = lineTops(rows[0]);
        int[] gappedTops = lineTops(rows[1]);
        System.out.println("vertical gap check at " + width + "px: vgap 0 preferred=" + zeroGap
            + " line tops=" + java.util.Arrays.toString(zeroTops)
            + " | vgap 8 preferred=" + eightGap + " line tops=" + java.util.Arrays.toString(gappedTops)
            + " | space between line 1 box and line 2 = "
            + (gappedTops[1] - lineBottom(rows[1], 0)) + "px");

        assertTrue(zeroTops.length >= 2, "the row should wrap at " + width + "px, got tops "
            + java.util.Arrays.toString(zeroTops));
        assertEquals(zeroTops.length, gappedTops.length,
            "the same row at the same width broke into a different number of lines: "
                + java.util.Arrays.toString(zeroTops) + " vs " + java.util.Arrays.toString(gappedTops));
        int breaks = zeroTops.length - 1;
        for (int line = 0; line < zeroTops.length; line++) {
            assertEquals(zeroTops[line] + 8 * line, gappedTops[line],
                "line " + (line + 1) + " did not move down by one 8px gap per break above it: "
                    + zeroTops[line] + " -> " + gappedTops[line]);
        }
        for (int line = 0; line + 1 < gappedTops.length; line++) {
            int space = gappedTops[line + 1] - lineBottom(rows[1], line);
            assertTrue(space >= 8, "lines " + (line + 1) + " and " + (line + 2)
                + " are not separated by the configured gap: " + space + "px of space");
        }
        assertEquals(zeroGap.height + 8 * breaks, eightGap.height,
            "the preferred height does not carry " + breaks + " gaps of 8px: " + zeroGap.height + " -> "
                + eightGap.height);
        assertEquals(zeroGap.width, eightGap.width, "a vertical gap must not change the row's natural width");

        GuiLayoutHarness.assertRowComponentsShareTheirTextBaseline(rows[0], "wrapped lines, vgap 0");
        GuiLayoutHarness.assertRowComponentsShareTheirTextBaseline(rows[1], "wrapped lines, vgap 8");
    }

    /** Top edge of each line a row was laid out on, in order, using the same line grouping as the harness. */
    private static int[] lineTops(Container row) {
        java.util.List<Component> children = new java.util.ArrayList<>();
        for (Component child : row.getComponents()) {
            if (child.isVisible() && child.getWidth() > 0 && child.getHeight() > 0) {
                children.add(child);
            }
        }
        children.sort((left, right) -> Integer.compare(left.getY(), right.getY()));
        java.util.List<Integer> tops = new java.util.ArrayList<>();
        int bandBottom = Integer.MIN_VALUE;
        for (Component child : children) {
            if (tops.isEmpty() || child.getY() >= bandBottom) {
                tops.add(child.getY());
                bandBottom = child.getY() + child.getHeight();
            } else {
                bandBottom = Math.max(bandBottom, child.getY() + child.getHeight());
            }
        }
        return tops.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int lineBottom(Container row, int lineIndex) {
        int[] tops = lineTops(row);
        int top = tops[lineIndex];
        int bottom = top;
        for (Component child : row.getComponents()) {
            if (child.isVisible() && child.getWidth() > 0 && child.getHeight() > 0
                && child.getY() >= top && (lineIndex + 1 == tops.length || child.getY() < tops[lineIndex + 1])) {
                bottom = Math.max(bottom, child.getY() + child.getHeight());
            }
        }
        return bottom;
    }
}
