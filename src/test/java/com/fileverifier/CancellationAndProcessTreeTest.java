package com.fileverifier;

import com.fileverifier.FileSyncEngine.JobRequest;
import com.fileverifier.FileSyncEngine.JobResult;
import com.fileverifier.FileSyncEngine.JobStatus;
import com.fileverifier.FileSyncEngine.JobType;
import com.fileverifier.FileSyncEngine.ProgressListener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a person sees when they cancel a multi-gigabyte copy. Two things have to be true and were not: the
 * reading of the source disk has to stop - all of it, because a local rsync run is three processes
 * (measured on 3.1.3 and 3.4.0) - and the window has to say which file is being written right now, in a
 * phase that is not called "copying" while the program is only hashing the source.
 */
class CancellationAndProcessTreeTest {

    private static final long THROTTLE_KBPS = 1_500L;

    private static void assumeRealRsync() {
        assumeTrue(TestSupport.rsyncAvailable(), "rsync not installed");
    }

    @Test
    void aCancelledCopyLeavesNoRsyncReadingTheSource(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.sparse(src.resolve("big.bin"), 300_000_000L);
        TestSupport.write(src.resolve("small.txt"), "small\n");

        Poller poller = new Poller(dst);
        FileSyncEngine engine = new FileSyncEngine();
        try {
            engine.setBandwidthLimitKbps(THROTTLE_KBPS);
            assertTrue(engine.start(request(src, dst, JobType.COPY_AND_VERIFY), poller), "the job did not start");
            poller.start();

            assertTrue(poller.awaitFirstTransfer(30_000), "rsync never announced a file");
            // Measured behaviour of rsync: the file being copied sits in the destination the whole time,
            // under a hidden name, and only gets its real name when its transfer completes. The program
            // now says so instead of letting the user conclude the copy never started.
            List<List<String>> listings = poller.awaitListingContaining(30_000,
                name -> name.startsWith(".") && name.contains("big.bin"));
            assertTrue(listings.stream().anyMatch(names -> names.stream()
                    .anyMatch(name -> name.startsWith(".") && name.contains("big.bin"))),
                "a large file is in the destination under a hidden temporary name while it copies: " + listings);

            engine.cancel();
            assertTrue(poller.awaitCompletion(30_000), "the cancelled job never reported completion");

            JobResult result = poller.result();
            assertEquals(JobStatus.FAILED, result.status());
            assertTrue(result.message().contains("cancelled"), result.message());
            assertTrue(result.filesCopied() < 2, "cancellation stopped the transfer: " + result.filesCopied());

            TestSupport.assertNoProcessesRunning("rsync", "after cancelling mid-transfer");
            assertTrue(poller.leftovers().isEmpty(),
                "rsync answers SIGTERM by deleting the partial file it was writing, so a cancelled job"
                    + " should leave nothing behind in someone's destination: " + poller.leftovers());
        } finally {
            poller.stop();
            engine.close();
        }
    }

    @Test
    void closingTheProgramMidTransferStopsRsync(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.sparse(src.resolve("big.bin"), 300_000_000L);

        Poller poller = new Poller(dst);
        FileSyncEngine engine = new FileSyncEngine();
        engine.setBandwidthLimitKbps(THROTTLE_KBPS);

        assertTrue(engine.start(request(src, dst, JobType.COPY_AND_VERIFY), poller));
        assertTrue(poller.awaitFirstTransfer(30_000), "rsync never announced a file");

        // The window's shutdown path. Whatever rsync was doing belongs to this program: if it outlives the
        // program, the source disk goes on being read by a process nobody owns and nobody can cancel.
        engine.close();

        assertTrue(poller.awaitCompletion(30_000), "closing the engine never finished the job");
        TestSupport.assertNoProcessesRunning("rsync", "after closing the engine mid-transfer");
        assertFalse(engine.isRunning());
        poller.stop();
    }

    @Test
    void aCompletedCopyLeavesNothingBehindEither(@TempDir Path tmp) throws Exception {
        assumeRealRsync();
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        TestSupport.write(src.resolve("a.txt"), "a\n");
        TestSupport.write(src.resolve("b.txt"), "b\n");

        Poller poller = new Poller(dst);
        FileSyncEngine engine = new FileSyncEngine();
        try {
            JobResult result = engine.execute(request(src, dst, JobType.COPY_AND_VERIFY), poller);
            assertTrue(result.isSuccess(), result.message());
            assertEquals(2, result.filesCopied());
        } finally {
            engine.close();
        }
        // rsync's parent can exit while its children are still finishing; a child that outlives it is
        // orphaned to init and this program can never reach it again, so nothing may be left here.
        TestSupport.assertNoProcessesRunning("rsync", "after a completed copy");
    }

    @Test
    void cancellingBeforeTheTransferStartsDoesNotStartIt(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        // A hole, not data: hashing 800 MB of nothing takes long enough that a cancellation issued the
        // moment the job starts lands while the source is still being read, which is the case that used to
        // launch rsync anyway.
        TestSupport.sparse(src.resolve("a.bin"), 800_000_000L);
        Path marker = tmp.resolve("rsync-was-run");

        // A stand-in rsync that records that it ran, then hangs. If the engine starts it after being told
        // to stop, the marker proves it and the survivor check proves nobody is watching it.
        Path fake = Files.createDirectories(tmp.resolve("tools")).resolve("counting-rsync");
        // The marker records a real copy attempt only: the engine also probes the binary with --version
        // before a job, and a probe is not a transfer.
        Files.writeString(fake, """
            #!/bin/sh
            if [ "$1" = "--version" ]; then
                echo 'rsync  version 0.0.0  (fake)'
                exit 0
            fi
            echo ran > "%s"
            printf '>f+++++++++ a.bin\\n'
            sleep 30
            exit 0
            """.formatted(marker));
        fake.toFile().setExecutable(true);

        Poller poller = new Poller(dst);
        FileSyncEngine engine = new FileSyncEngine();
        try {
            engine.useRsync(fake.toString());
            assertTrue(engine.start(request(src, dst, JobType.COPY_AND_VERIFY), poller));
            engine.cancel();

            assertTrue(poller.awaitCompletion(30_000), "the aborted job never reported completion");
            JobResult result = poller.result();
            assertEquals(JobStatus.FAILED, result.status());
            assertTrue(result.message().contains("before rsync started"), result.message());
            assertFalse(Files.exists(marker),
                "a job stopped while the source was being hashed must not then hand a reader to the source"
                    + " disk: " + result.message());
            TestSupport.assertNoProcessesRunning("counting-rsync", "after cancelling during hashing");
            try (var listed = Files.list(dst)) {
                assertEquals(0, listed.count(), "nothing should have been written: "
                    + TestSupport.relPathsUnder(dst));
            }
        } finally {
            engine.close();
        }
    }

    @Test
    void theCopyStatusNamesTheFileBeingWrittenAndHowFarAlongItIs(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        // A hole, not data: the engine asks the source its size, and the test needs a big file without
        // writing a big file.
        TestSupport.sparse(src.resolve("big.bin"), 300_000_000L);

        // A scripted rsync so the sequence is exact instead of timing-dependent. These are real
        // --info=progress2 line shapes from rsync 3.1.3 and 3.4.0.
        Path fake = TestSupport.copyingFakeRsync(Files.createDirectories(tmp.resolve("tools")),
            ">f+++++++++ big.bin\n"
                + "              3   0%    0.00kB/s    0:00:00\n"
                + "      100,000,000  30%   10.00MB/s    0:02:11\n"
                + "      200,000,000  60%   10.00MB/s    0:02:08\n"
                + "      300,000,000 100%   10.00MB/s    0:02:16 (xfr#1, to-chk=0/1)");

        Poller poller = new Poller(dst);
        FileSyncEngine engine = new FileSyncEngine();
        try {
            engine.useRsync(fake.toString());
            JobResult result = engine.execute(request(src, dst, JobType.COPY_AND_VERIFY), poller);

            assertTrue(result.isSuccess(), result.message());
            List<String> phases = poller.phases();
            int hashing = phases.indexOf("hashing source");
            int copying = phases.indexOf("copying");
            assertTrue(hashing >= 0 && copying > hashing,
                "reading the whole source to hash it is not copying, and calling it copying is what makes a"
                    + " job look stalled on the first file with an empty destination: " + phases);

            List<String> statuses = poller.statuses();
            assertTrue(statuses.stream().anyMatch(s -> s.contains("now writing: big.bin")
                            && s.contains("95.4 MB of 286.1 MB, 33%")),
                "the status has to name the file going into the destination and how much of it has landed - "
                    + "a 30 GB file otherwise shows one number that barely moves for an hour: " + statuses);
            assertTrue(statuses.stream().anyMatch(s -> s.contains("190.7 MB of 286.1 MB, 67%")),
                "and it has to keep up with the file in flight, not just the whole job: " + statuses);
            assertTrue(statuses.stream().anyMatch(s -> s.contains("1 of 1 done")),
                "rsync's own completed-transfer count has to reach the last file, not stall at zero while a"
                    + " 30 GB file finishes: " + statuses);
            assertTrue(statuses.stream().anyMatch(s -> s.contains("10.00MB/s")),
                "and how fast it is moving: " + statuses);
        } finally {
            engine.close();
        }
    }

    private static JobRequest request(Path src, Path dst, JobType type) throws IOException {
        return TestSupport.request(src, dst, type, TestSupport.selectAllOf(src),
            FileSyncEngine.Selection.none());
    }

    /**
     * A listener that records what the engine reported, and also keeps a running picture of what the
     * destination folder actually contains while the transfer is live - which is what a person looking at
     * the folder sees.
     */
    private static final class Poller implements ProgressListener {
        private final Path destination;
        private final List<String> phases = new ArrayList<>();
        private final List<String> statuses = new ArrayList<>();
        private final List<List<String>> listings = new ArrayList<>();
        private final AtomicInteger transfers = new AtomicInteger();
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicReference<JobResult> result = new AtomicReference<>();
        private final AtomicReference<List<String>> leftovers = new AtomicReference<>(List.of());
        private Thread pollerThread;

        private Poller(Path destination) {
            this.destination = destination;
        }

        private synchronized void start() {
            running.set(true);
            pollerThread = new Thread(() -> {
                while (running.get()) {
                    listings.add(listing());
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            pollerThread.setDaemon(true);
            pollerThread.start();
        }

        private void stop() {
            running.set(false);
        }

        @Override
        public synchronized void onPhase(String phase) {
            phases.add(phase);
        }

        @Override
        public synchronized void onProgress(int percent, String statusText) {
            statuses.add(statusText);
        }

        @Override
        public void onFileProcessed(String relPath, String kind, String srcHash, String dstHash) {
            if ("TRANSFERRING".equals(kind)) {
                transfers.incrementAndGet();
            }
        }

        @Override
        public void onCompletion(JobResult completion) {
            leftovers.set(listing());
            result.set(completion);
        }

        private List<String> listing() {
            Set<String> names = new LinkedHashSet<>();
            try (var stream = Files.walk(destination)) {
                stream.filter(Files::isRegularFile)
                    .forEach(p -> names.add(destination.relativize(p).toString()));
            } catch (IOException e) {
                // The job may be changing this directory right now; the next listing will catch up.
            }
            return new ArrayList<>(names);
        }

        private List<List<String>> snapshotListings() {
            return List.copyOf(listings);
        }

        private List<List<String>> awaitListingContaining(long millis,
            java.util.function.Predicate<String> nameMatch)
            throws InterruptedException {
            long until = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < until) {
                List<List<String>> seen = snapshotListings();
                for (List<String> names : seen) {
                    if (names.stream().anyMatch(nameMatch)) {
                        return seen;
                    }
                }
                Thread.sleep(200);
            }
            return snapshotListings();
        }

        private boolean awaitFirstTransfer(long millis) throws InterruptedException {
            long until = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < until && transfers.get() == 0) {
                Thread.sleep(100);
            }
            return transfers.get() > 0;
        }

        private boolean awaitCompletion(long millis) throws InterruptedException {
            long until = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < until && result.get() == null) {
                Thread.sleep(100);
            }
            return result.get() != null;
        }

        private synchronized List<String> phases() {
            return List.copyOf(phases);
        }

        private synchronized List<String> statuses() {
            return List.copyOf(statuses);
        }

        private List<String> leftovers() {
            return leftovers.get();
        }

        private JobResult result() {
            return result.get();
        }
    }
}
