package com.fileverifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The log tail buffer (3.6): a 100k-file job must not grow the on-screen log without bound, and
 * trimming must cut whole lines - never in the middle of one.
 */
class TailLogTest {

    @Test
    void keepsTheNewestLinesAndDropsTheOldest() {
        TailLog log = new TailLog(100, 10);
        for (int i = 0; i < 500; i++) {
            log.append("line " + i);
        }

        assertTrue(log.trackedLines() <= 112, "tracked=" + log.trackedLines());
        assertTrue(log.text().contains("line 499"), "newest line must survive");
        assertFalse(log.text().contains("line 0\n"), "oldest line must be gone");
        assertTrue(log.text().contains("older log lines dropped"), "the drop must be visible");
        assertTrue(log.droppedLines() >= 390, "dropped=" + log.droppedLines());
    }

    @Test
    void trimmingNeverCutsALineInHalf() {
        TailLog log = new TailLog(40, 5);
        for (int i = 0; i < 400; i++) {
            log.append("[INFO]     file-" + i + ".txt  src=01234567  dst=89abcdef");
        }

        for (String line : log.text().split("\n")) {
            boolean intact = line.matches("\\[INFO\\]     file-\\d+\\.txt  src=01234567  dst=89abcdef")
                    || line.equals("(older log lines dropped - last 40 kept)");
            assertTrue(intact, "corrupted line: [" + line + "]");
        }
    }

    @Test
    void longSingleLinesAreCountedByCharactersNotByVisualWrapping() {
        TailLog log = new TailLog(5, 2);
        for (int i = 0; i < 20; i++) {
            log.append("x".repeat(4000) + " " + i);
        }
        assertTrue(log.text().contains("19"), "newest line must survive");
        assertFalse(log.text().contains("x 0\n"), "oldest line must be gone");
        assertEquals(0, log.text().chars().filter(c -> c == 'x').count() % 4000,
                "a kept line must still be a whole line");
    }

    @Test
    void retainedTextIsBoundedEvenForOversizedIndividualLines() {
        TailLog log = new TailLog(10, 2);
        for (int i = 0; i < 100; i++) log.append("x".repeat(100_000));
        assertTrue(log.trackedLines() <= 12);
        assertTrue(log.text().length() <= 12 * (TailLog.MAX_LINE_CHARS + 30));
        assertTrue(log.text().contains("display text shortened"));
        assertTrue(log.text().contains("older log lines dropped"));
    }

    @Test
    void nothingIsDroppedBelowTheLimit() {
        TailLog log = new TailLog(5000, 1000);
        for (int i = 0; i < 200; i++) {
            log.append("line " + i);
        }
        assertEquals(200, log.trackedLines());
        assertEquals(0, log.droppedLines());
        assertFalse(log.text().contains("dropped"));
        assertTrue(log.text().startsWith("line 0\n"));
    }

    @Test
    void clearForgetsBothTextAndAccounting() {
        TailLog log = new TailLog(50, 5);
        for (int i = 0; i < 200; i++) {
            log.append("line " + i);
        }
        log.clear();
        assertEquals("", log.text());
        assertEquals(0, log.trackedLines());
        assertEquals(0, log.droppedLines());
        log.append("after clear");
        assertEquals("after clear\n", log.text());
    }
}
