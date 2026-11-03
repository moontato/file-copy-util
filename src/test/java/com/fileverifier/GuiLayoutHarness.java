package com.fileverifier;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.font.FontRenderContext;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;

/**
 * Shared measurements for the layout checks. Both the JUnit tests and the jar probes use it, so the
 * numbers quoted in the validation report mean the same thing in a test JVM and in a packaged jar.
 */
final class GuiLayoutHarness {

    /** 105 characters of ordinary nested path: the shape a backup tree actually produces. */
    static final String LONG_PATH = "quarterly/2026/11-01/archive/customer-orders/eu-west/"
        + "order-0000/attachments/invoice-2026-11-01-final-v3.csv";
    /** A running-status line of the kind the progress reporter really produces. */
    static final String RUNNING_STATUS = "Now writing: " + LONG_PATH
        + " - 412 of 1000 files, 3.2 GiB of 7.8 GiB, 84.1 MiB/s";

    private GuiLayoutHarness() { }

    static List<String> longPaths(int count) {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            paths.add(LONG_PATH.replace("order-0000", String.format("order-%04d", i)));
        }
        return paths;
    }

    /** Fills both lists, the results table, and the running-status line with the long nested paths. */
    static List<String> populate(FileVerifierView view) throws Exception {
        return populate(view, longPaths(40));
    }

    /** Fills both lists, the results table, and the running-status line with the given names. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static List<String> populate(FileVerifierView view, List<String> paths) throws Exception {
        for (String name : new String[]{"sourceList", "destList"}) {
            JList list = (JList) field(view, name);
            DefaultListModel model = (DefaultListModel) list.getModel();
            model.clear();
            for (String path : paths) {
                model.addElement(new FileVerifierView.DirectoryEntry(
                    new FileEntry(path, Path.of("/fixture", path)), true));
            }
        }
        JTable table = (JTable) field(view, "resultsTable");
        Method replaceRows = table.getModel().getClass().getDeclaredMethod("replaceRows", List.class);
        replaceRows.setAccessible(true);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(new Object[]{"MISMATCH", paths.get(i), "0123456789abcdef0123456789abcdef",
                "fedcba9876543210fedcba9876543210", "rsync -a --checksum"});
        }
        replaceRows.invoke(table.getModel(), rows);
        view.setStatusText(RUNNING_STATUS);
        return paths;
    }

    /**
     * The same 40 files, once with short names and once with 105 character names: the window may not care.
     */
    static String assertPathLengthDoesNotWidenTheForm(FileVerifierView view, String label) throws Exception {
        List<String> shortNames = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            shortNames.add(String.format("file-%04d.csv", i));
        }
        populate(view, shortNames);
        layoutAll(view);
        int shortWidth = view.getPreferredSize().width;
        populate(view, longPaths(40));
        layoutAll(view);
        int longWidth = view.getPreferredSize().width;
        require(longWidth <= shortWidth, label + ": naming the same 40 files with longer paths widened the form from "
            + shortWidth + " to " + longWidth + " pixels");
        return "40 files short names=" + shortWidth + " pixels, 105 character names=" + longWidth + " pixels";
    }

    /**
     * Long paths must widen their own list, not the window. The list reports content wider than the
     * region that was budgeted for it, the region scrolls horizontally, and the form's own preferred
     * width is unchanged by the content.
     */
    static void assertContentScrollsInsideItsOwnRegions(FileVerifierView view, String label) {
        for (String name : new String[]{"sourceList", "destList"}) {
            try {
                JList<?> list = (JList<?>) field(view, name);
                JScrollPane scroll = (JScrollPane) list.getParent().getParent();
                int content = list.getPreferredSize().width;
                int budget = scroll.getPreferredSize().width;
                require(content > budget, label + ": " + name + " content " + content
                    + " must be wider than its " + budget + " pixel budget, otherwise the check proves nothing");
                require(scroll.getHorizontalScrollBarPolicy() == ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                    label + ": " + name + " must be able to scroll its own paths horizontally");
                require(list.getFixedCellHeight() > 0, label + ": " + name + " rows must be measured");
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    /** Rows are at least as tall as the font the renderer paints them with. */
    static void assertRowsFitTheRenderer(FileVerifierView view, String label) {
        try {
            double bodyLine = lineHeight(UiTheme.baseFont());
            double monoLine = lineHeight(UiTheme.monoFont());
            for (String name : new String[]{"sourceList", "destList"}) {
                JList<?> list = (JList<?>) field(view, name);
                int cell = list.getUI().getCellBounds(list, 0, 0).height;
                require(cell + 1 >= bodyLine, label + ": " + name + " cell height " + cell
                    + " cannot render a " + bodyLine + " point-sized row (font "
                    + UiTheme.baseFont().getSize2D() + ")");
                require(list.getFixedCellHeight() == cell, label + ": " + name
                    + " fixed row height " + list.getFixedCellHeight() + " disagrees with the measured cell " + cell);
            }
            JTable table = (JTable) field(view, "resultsTable");
            double rowHeight = table.getRowHeight(0);
            double tallest = Math.max(monoLine, bodyLine);
            require(rowHeight + 1 >= tallest, label + ": results row height " + rowHeight
                + " cannot render a " + tallest + " point-sized row");
            require(table.getTableHeader().getPreferredSize().height + 1 >= lineHeight(UiTheme.captionFont()),
                label + ": the results header height " + table.getTableHeader().getPreferredSize().height
                    + " cannot render its caption");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Run, Cancel and the version label sit inside the window the layout asked for. */
    static void assertActionsReachable(FileVerifierView view, String label) {
        try {
            layoutToPreferred(view);
            Rectangle box = new Rectangle(0, 0, view.getWidth(), view.getHeight());
            for (String name : new String[]{"runButton", "cancelButton", "versionLabel", "jobTimeoutField"}) {
                Component control = (Component) field(view, name);
                Rectangle bounds = boundsIn(control, view);
                require(box.contains(bounds), label + ": " + name + " at " + bounds
                    + " falls outside the laid-out window " + box);
            }
            Component version = (Component) field(view, "versionLabel");
            Component timeout = (Component) field(view, "jobTimeoutField");
            require(!boundsIn(version, view).intersects(boundsIn(timeout, view)),
                label + ": the version label overlaps the timeout field");
            require(version.getFont().getSize2D() + 0.01 >= UiTheme.captionFont().getSize2D(),
                label + ": the version label kept font " + version.getFont().getSize2D()
                    + " while the interface caption is now " + UiTheme.captionFont().getSize2D());
            require(timeout.getFont().getSize2D() + 0.01 >= UiTheme.baseFont().getSize2D() - 1.0,
                label + ": the timeout field kept font " + timeout.getFont().getSize2D()
                    + " while the interface font is now " + UiTheme.baseFont().getSize2D());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /** The status line keeps its sentence and its numbers, and the whole line stays reachable. */
    static void assertStatusLineStaysOneLine(FileVerifierView view, String label) {
        try {
            JComponent status = (JComponent) field(view, "statusLabel");
            String shown = ((javax.swing.JLabel) status).getText();
            require(shown.length() < RUNNING_STATUS.length(),
                label + ": the status line was not shortened (" + shown.length() + " characters)");
            require(shown.contains("…"), label + ": the shortened status line does not say it was shortened: " + shown);
            require(RUNNING_STATUS.equals(status.getToolTipText()),
                label + ": the full status text is not reachable from the status line");
            require(RUNNING_STATUS.equals(status.getAccessibleContext().getAccessibleDescription()),
                label + ": the full status text is not in the accessible description");
            double budget = UiTheme.widthInColumns(40, status.getFont());
            require(status.getPreferredSize().width <= budget + 1, label + ": the status line asks for "
                + status.getPreferredSize().width + " pixels, more than its " + budget + " pixel budget");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /**
     * Every component in a baseline-aligned row - the wrapping control rows and the directory grid - has to
     * read on one text line. Each component's own baseline is compared with the deepest baseline in its row,
     * and a component that reports no baseline has to sit centred against the row instead. Returns the worst
     * offset found, in pixels; more than one pixel is a label floating above the control beside it.
     */
    static int assertRowComponentsShareTheirTextBaseline(FileVerifierView view, String label) {
        return assertRowComponentsShareTheirTextBaseline((Container) view, label);
    }

    /**
     * Every component on one line of a baseline-aligned row - the wrapping control rows and the directory
     * grid - has to read on one text line. Children of such a row are grouped into the lines they were laid
     * out on, because a row that had to wrap holds several. Within each line, a component that reports a text
     * baseline is compared with the deepest baseline in that line, and a component that reports none has to
     * sit centred against the line. Returns the worst offset in pixels; more than one pixel means a label
     * floating above the control beside it.
     */
    static int assertRowComponentsShareTheirTextBaseline(Container root, String label) {
        int worst = 0;
        String offender = "";
        int linesChecked = 0;
        List<Container> containers = new ArrayList<>();
        collectContainers(root, containers);
        for (Container container : containers) {
            java.awt.LayoutManager layout = container.getLayout();
            if (!(layout instanceof UiTheme.WrapLayout) && !(layout instanceof java.awt.GridBagLayout)) {
                continue;
            }
            List<Component> children = new ArrayList<>();
            for (Component child : container.getComponents()) {
                // A component with no box - a caption whose text has not been set yet - has no text line of
                // its own to compare, and must not decide where the line's middle is.
                if (child.isVisible() && child.getWidth() > 0 && child.getHeight() > 0) {
                    children.add(child);
                }
            }
            if (children.size() < 2) {
                continue;
            }
            children.sort((left, right) -> Integer.compare(left.getY(), right.getY()));
            List<List<Component>> lines = new ArrayList<>();
            List<Component> line = new ArrayList<>();
            int bandBottom = Integer.MIN_VALUE;
            for (Component child : children) {
                // A line never overlaps the next one, and the first component of the next line sits exactly
                // where this line's box ends, so the boundary belongs to the new line.
                if (!line.isEmpty() && child.getY() >= bandBottom) {
                    lines.add(line);
                    line = new ArrayList<>();
                }
                line.add(child);
                bandBottom = Math.max(bandBottom, child.getY() + child.getHeight());
            }
            lines.add(line);
            for (List<Component> one : lines) {
                if (one.size() < 2) {
                    continue;
                }
                linesChecked++;
                int top = Integer.MAX_VALUE;
                int bottom = Integer.MIN_VALUE;
                int lineBaseline = 0;
                List<Integer> baselines = new ArrayList<>();
                for (Component child : one) {
                    top = Math.min(top, child.getY());
                    bottom = Math.max(bottom, child.getY() + child.getHeight());
                    int baseline = UiTheme.baselineOf(child);
                    baselines.add(baseline);
                    if (baseline >= 0) {
                        lineBaseline = Math.max(lineBaseline, child.getY() + baseline);
                    }
                }
                int middle = top + (bottom - top) / 2;
                for (int i = 0; i < one.size(); i++) {
                    Component child = one.get(i);
                    int baseline = baselines.get(i);
                    int offset;
                    String what;
                    if (baseline >= 0) {
                        offset = Math.abs(child.getY() + baseline - lineBaseline);
                        what = "baseline";
                    } else {
                        offset = Math.abs(child.getY() + child.getHeight() / 2 - middle);
                        what = "centre of a component with no baseline";
                    }
                    if (offset > worst) {
                        worst = offset;
                        offender = describe(child) + " (" + what + " in " + layout.getClass().getSimpleName() + ")";
                    }
                }
            }
        }
        System.out.println(label + ": baseline-aligned lines=" + linesChecked + " worst offset=" + worst + "px"
            + (offender.isEmpty() ? "" : " (" + offender + ")"));
        require(worst <= 1,
            label + ": controls on one line are " + worst + "px apart on the text line (" + offender + ")");
        return worst;
    }

    private static void collectContainers(Container container, List<Container> found) {
        found.add(container);
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                collectContainers(nested, found);
            }
        }
    }

    private static String describe(Component component) {
        if (component instanceof javax.swing.JLabel text) {
            return "label \"" + text.getText() + "\"";
        }
        if (component instanceof javax.swing.AbstractButton button) {
            return button.getClass().getSimpleName() + " \"" + button.getText() + "\"";
        }
        return component.getClass().getSimpleName();
    }

    static void layoutToPreferred(Container container) {
        container.setSize(container.getPreferredSize());
        layoutAll(container);
    }

    static void layoutAll(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutAll(nested);
            }
        }
    }

    static Rectangle boundsIn(Component target, Component ancestor) {
        Point point = SwingUtilities.convertPoint(target.getParent(), target.getLocation(), ancestor);
        return new Rectangle(point.x, point.y, target.getWidth(), target.getHeight());
    }

    /** Line height the font actually needs, measured the way Swing measures it for painting. */
    static double lineHeight(Font font) {
        FontRenderContext context = new FontRenderContext(null, true, true);
        return font.getLineMetrics("Agxy", context).getHeight();
    }

    static Dimension sizeOf(Component component) {
        return component.getPreferredSize();
    }

    static Object field(Object target, String name) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field declared = type.getDeclaredField(name);
                declared.setAccessible(true);
                return declared.get(target);
            } catch (NoSuchFieldException notHere) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
