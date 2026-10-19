package com.fileverifier;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javax.swing.JTextArea;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A JTextArea that keeps only its newest N lines (Phase 3: a 100k-file job must not grow the log
 * without bound).
 *
 * <p>The line lengths of everything currently in the document are tracked in order, so trimming is
 * "remove the first K characters" rather than rescanning the text. Trimming happens in batches to
 * avoid a document mutation per line, and the notice line that announces a trim is itself tracked -
 * an untracked line inside the next trim range would cut the following removal in the middle of a
 * line.
 */
public final class TailLog {

    /** Lines retained on screen. The log is a tail, not an archive; the report export is the archive. */
    public static final int DEFAULT_MAX_LINES = 5000;
    /** Extra lines allowed past the limit before a batch is dropped, to avoid churn per line. */
    public static final int DEFAULT_DROP_BATCH = 1000;

    private final JTextArea area = new JTextArea();
    private final Deque<Integer> lineLengths = new ArrayDeque<>();
    private final int maxLines;
    private final int dropBatch;
    private int droppedLines;

    public TailLog() {
        this(DEFAULT_MAX_LINES, DEFAULT_DROP_BATCH);
    }

    public TailLog(int maxLines, int dropBatch) {
        this.maxLines = Math.max(1, maxLines);
        this.dropBatch = Math.max(1, dropBatch);
    }

    /**
     * The component to put in a scroll pane. Handing back the area itself is the point: a copy would be
     * a different text area from the one {@link #append} writes into, and the line accounting would no
     * longer describe what is on screen.
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP",
            justification = "Deliberate: the caller must get the same component the log writes into.")
    public JTextArea component() {
        return area;
    }

    public void append(String line) {
        String withNewline = line + "\n";
        area.append(withNewline);
        lineLengths.addLast(withNewline.length());
        if (lineLengths.size() > maxLines + dropBatch) {
            trimTo(maxLines);
        }
        area.setCaretPosition(area.getDocument().getLength());
    }

    private void trimTo(int keepLines) {
        int dropCount = lineLengths.size() - keepLines;
        int chars = 0;
        for (int i = 0; i < dropCount; i++) {
            Integer length = lineLengths.pollFirst();
            if (length == null) {
                break;
            }
            chars += length;
        }
        if (chars <= 0) {
            return;
        }
        area.replaceRange("", 0, chars);
        droppedLines += dropCount;
        String notice = "(older log lines dropped - last " + maxLines + " kept)\n";
        area.replaceRange(notice, 0, 0);
        // The notice sits at the top of the retained text and counts as a line from now on.
        lineLengths.addFirst(notice.length());
    }

    public void clear() {
        area.setText("");
        lineLengths.clear();
        droppedLines = 0;
    }

    public int trackedLines() {
        return lineLengths.size();
    }

    public int droppedLines() {
        return droppedLines;
    }

    public int maxLines() {
        return maxLines;
    }

    public String text() {
        return area.getText();
    }
}
