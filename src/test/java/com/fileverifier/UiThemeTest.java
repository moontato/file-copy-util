package com.fileverifier;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertEquals(Boolean.FALSE, javax.swing.UIManager.get("Table.showGrid"));

        UiTheme.install(false);
        assertEquals(UiTheme.LIGHT, UiTheme.palette());
        assertEquals(Boolean.FALSE, javax.swing.UIManager.get("Table.showGrid"));
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
        assertEquals(UiTheme.LIGHT.ok(), UiTheme.outcomeColor("COPIED"));
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
}
