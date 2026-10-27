package com.fileverifier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the ledger knows about the destination before and after a copy - and what it refuses to claim when a
 * stat fails for a reason that is not "this name is not here". An access error, a filesystem error or an
 * ancestor that cannot be resolved says that the question could not be asked; answering it with "absent" is
 * how a job comes to report a file as not copied when nothing was actually known.
 */
class TransferLedgerTest {

    @TempDir
    Path tmp;

    @Test
    void theLedgerTellsAbsentFromPresentFromUninspectable() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("plain.txt"), "1\n");
        TestSupport.write(src.resolve("new.txt"), "2\n");
        TestSupport.write(src.resolve("loop/x.txt"), "3\n");
        TestSupport.write(dst.resolve("plain.txt"), "already there\n");

        List<FileEntry> workSet = List.of(
                new FileEntry("plain.txt", src.resolve("plain.txt")),
                new FileEntry("new.txt", src.resolve("new.txt")),
                new FileEntry("loop/x.txt", src.resolve("loop/x.txt")));

        // The snapshot runs with dst/loop not existing at all, so "held before" can answer absent.
        List<String> warnings = new ArrayList<>();
        TransferLedger ledger = new TransferLedger(src, dst, workSet, warnings, () -> false);

        // Asking what is there <i>now</i> has to fail with an error rather than answer "nothing": dst/loop is a
        // symbolic link that points at itself, so resolving dst/loop/x.txt is impossible.
        Files.createSymbolicLink(dst.resolve("loop"), Path.of("loop"));

        assertAll(
                () -> assertEquals(TransferLedger.Existence.PRESENT, ledger.heldBefore("plain.txt")),
                () -> assertEquals(TransferLedger.Existence.ABSENT, ledger.heldBefore("new.txt")),
                () -> assertEquals(TransferLedger.Existence.ABSENT, ledger.heldBefore("loop/x.txt")),
                () -> assertEquals(TransferLedger.Existence.PRESENT, ledger.existsNow("plain.txt")),
                () -> assertEquals(TransferLedger.Existence.ABSENT, ledger.existsNow("new.txt")),
                () -> assertEquals(TransferLedger.Existence.UNKNOWN, ledger.existsNow("loop/x.txt")));
    }

    /**
     * The ledger's memory of the destination is only as good as the stat that made it. A name it could not read
     * before the copy is not a name that was empty before the copy: remembering it as absent is how a job comes
     * to claim "nothing was there, and nothing arrived" without having looked.
     */
    @Test
    void aDestinationThatCouldNotBeReadBeforeTheCopyIsNotRememberedAsEmpty() throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src2"));
        Path dst = Files.createDirectories(tmp.resolve("dst2"));
        TestSupport.write(src.resolve("loop/x.txt"), "1\n");
        Files.createSymbolicLink(dst.resolve("loop"), Path.of("loop"));   // already unusable when the ledger looks

        List<FileEntry> workSet = List.of(new FileEntry("loop/x.txt", src.resolve("loop/x.txt")));
        List<String> warnings = new ArrayList<>();
        TransferLedger ledger = new TransferLedger(src, dst, workSet, warnings, () -> false);

        assertAll(
                () -> assertEquals(TransferLedger.Existence.UNKNOWN, ledger.heldBefore("loop/x.txt")),
                () -> assertEquals(TransferLedger.Existence.UNKNOWN, ledger.existsNow("loop/x.txt")),
                () -> assertFalse(ledger.showsNothingAt("loop/x.txt")));
    }
}
