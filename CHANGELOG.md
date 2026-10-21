# Changelog

## 1.2.2 — collisions, races, and engine bookkeeping

Things the copy cannot see from inside, plus two pieces of state the engine should have owned all along.

- **Name collisions are named.** A work set containing `A.txt` and `a.txt` (or an accent written two ways) is
  two files on your filesystem and one file on a case-insensitive or normalising destination — a Windows share,
  exFAT, some macOS volumes. The pairs are reported before anything is written, with the consequence stated:
  the later transfer would overwrite the earlier one. A warning, not a refusal, because on a case-sensitive
  destination both names genuinely are separate files. Without this the tool overwrites one file with the other
  and then reports a mismatch as if the copy had been honest.
- **A source file that moves mid-copy is described as that.** A copy verifies against the digest taken before
  the transfer; when a mismatch appears the source is re-hashed once, and if it changed the job names the file
  as "changed in the source while the job ran" instead of leaving an unexplained MISMATCH that reads like
  damage. Verify Only has no pre-transfer digest, so it makes no such claim.
- **Half-written files are reported on a failed copy**, not only on a cancel or a timeout — the gap left by the
  1.2.1 fix to failure counters. Tested with a stand-in rsync that leaves `.data.AAAAAA` behind and exits 23.
- **Each engine chooses its own rsync.** The binary was a process-wide static; now an engine can override it
  (`useRsync`) and preflight checks the binary that engine would actually run. The process default remains a
  default.
- **One live job per destination per program.** A second job pointed at the same destination fails immediately:
  two jobs writing one directory interleave, each reporting the other's files as its own results, and a
  partial-transfer warning can name a file the other job is still writing. This is an in-process guard, not a
  lock — a second *process* would need a lock file written into your destination tree, which is left as the
  owner's decision rather than a default.

Tests: 160, plus the rsync matrix on 3.1.3 / 3.2.7 / 3.4.0.
## 1.2.1 — robustness audit

Five defects found by attacking the program instead of writing the tests it seemed to need. Each now has a
regression test (`RobustnessTest`).

- **A machine whose locale cannot spell a file name crashed the scan.** File-name encoding comes from the
  locale, and under `LANG=C` — cron jobs, systemd services, minimal containers, `sudo` — every non-ASCII byte
  in a name is unrepresentable: `DirectoryScanner` died with `InvalidPathException`, and a job threw out of
  the engine. Such entries are now skipped with one clear warning naming the encoding and the fix
  (`LC_ALL=C.UTF-8`), and no guessed name is ever written to the destination.
- **An rsync failure partway through a copy reported "0 copied"** while the files it had already written sat
  in the destination. Every failure path returned zeroed counters. A failed copy now gets the same
  accounting as a cancelled one: what rsync announced, how many it completed, and whether it left
  half-written files behind.
- **A symbolic link at the destination was followed and hashed.** The scanner refuses to list links, but the
  destination side is reached by resolving a relative path, so a link can appear during verification. It used
  to read a file outside the destination and report its digest as the destination's. Now the file is reported
  as MISMATCH with an explicit "not compared, this tool does not follow links" warning, and nothing outside
  the destination is read.
- **A carriage return in a file name was accepted**, handed to rsync's line-based `--files-from`, and came
  back as "rsync failed (exit code 23)" with nothing explaining why. It is refused up front like a newline or
  a NUL byte, named in the warnings, and reported as a file that was not transferred.
- **A `null` `ProgressListener` turned the job into a failure** whose message was an internal null
  dereference. Silence is now what it means.

Also checked, and correct: names containing `-`, `$`, `\`, `;`, `'`, `[`, `,`, tabs and trailing spaces survive
a copy and a verify; a selected file deleted between scan and run is named rather than silently dropped; a
symlinked directory inside the destination is not written through.
## 1.2.0

The interface phase. What the program does with files is unchanged; what the window looks like, and how
much of it can be tested without a screen, is not.

### Interface

- **FlatLaf theme** (light and dark), with a *Dark mode* checkbox that takes effect immediately and is
  remembered. Colours, fonts and gaps live in one class (`UiTheme`); the layout code contains no colour
  literals at all, and a test enforces that.
- **Layout rebuilt for legibility**: labels above their fields, scan options on one line, cards instead of
  titled group boxes, a hairline edge on the lists and log, one vertical rhythm for padding.
- **Mode choice is one control**: *Copy & Verify* and *Verify only* are now the two halves of a single
  segmented control, with the work-set line that follows from the choice next to them.
- **One primary action**: *Run job* is the only filled button in the window. Cancel is red, everything
  else is quiet.
- **Results table fixed**: `JScrollPane(JTable)` does not attach a table header, so the column titles -
  and the click-to-sort affordance the toolbar advertised - were missing since 1.1.0. The header is
  attached now, columns have deliberate widths, and the status column shows a colour dot *and* the word
  (a dot alone would fail for a colour-blind reader and for a printed export).
- **Progress line gained a phase**: the status row now says which phase the job is in.
- **Keyboard shortcuts**: `Ctrl+S` scan, `Ctrl+R` run, `Escape` cancel. `Enter` is deliberately left
  alone so it keeps activating whichever control has focus.
- **Window title unchanged** (*File Verifier & Copier*): the long name is a title, not a coordinate.

### Structure

- The Swing front end is now a panel (`FileVerifierView`) hosted by a thin window (`FileVerifierFrame`).
  A `JFrame` cannot be constructed at all when the JVM is headless, which had made the entire UI
  untestable in CI. The panel can be built, driven, and painted offscreen with no display server.
- New tests: 8 for the theme (every text/badge pairing clears 4.5:1 in both palettes, a palette switch
  actually repaints widgets built under the old one) and 7 for the interface structure (every control
  still exists, mode halves are mutually exclusive, the results table has a header, no colour literals
  outside `UiTheme`). Suite: 137 tests, SpotBugs clean.

### Packaging

- Version 1.2.0. The runnable jar now bundles FlatLaf 3.7.2 (Apache-2.0), so it is ~1.1 MB instead of
  ~110 KB; `NOTICE` records the bundling. If FlatLaf cannot load, the window falls back to the platform
  look and feel, and if the machine has no display libraries at all the program says so and points at
  `--cli` instead of printing a stack trace.

## 1.1.0

A remediation release. The GUI looks much the same; what it does with your files is different, and
there is now a test suite that says what it is supposed to do.

### Behaviour changes (read these before re-running an old workflow)

- **An empty selection means zero files.** It used to be treated as "everything", so ticking nothing
  and pressing Run copied or verified the whole tree. Select All is now the only way to include all
  files.
- **Copy & Verify verifies exactly the files it was asked to copy.** It used to compare a set that was
  not the selected set.
- **`checked = matched + mismatched + missing`.** Files absent at the destination are `missing`, not
  `mismatch`. A file that is present but different is `mismatch` with both digests.
- **`copied` comes from rsync's itemized output**, so an unchanged file is not counted, and a copied
  or changed symlink (`cL`) *is* counted. The itemize grammar was previously misread: symlink
  transfers were never counted, and `D` (a device) was treated as a directory.
- **A selected file that no longer exists is reported.** It used to disappear from the job without a
  word, which turned a typo in a `--files` list, or a file deleted after scanning, into a job that
  reported success.
- **A file missing at the destination is reported once**, as `missing`, and no longer also as a
  per-file "could not read" warning.
- **Symbolic links are a policy, not a accident**: skip (default), copy as a link, or copy the target.
  Skipping says how many links were left out.
- **A stale destination list can no longer silently exclude files from Verify Only.** Exclusion now
  requires a row the list actually contains and leaves unticked.
- **`copied` on a job that was cut short is capped by rsync's own completed-transfer count.** rsync
  announces a file before its data moves, so a cancelled job used to report every announced file as
  copied. It now reports "announced N, of which at most M finished", where M comes from rsync's `xfr#`
  counter.
- **After a cancelled or timed-out copy, the engine reports the partial files `rsync` left behind**, by
  name and size (`.big.bin.QafPvi (for big.bin, 3.8 MB)`), or says explicitly that none were left. It
  does not delete them.
- **A job that ran past its deadline can no longer be reported as complete.** The deadline was enforced
  by a watchdog on a 250 ms period, so a phase that finished between two ticks fell through and reported
  `COMPLETED` - and a half-finished verification reported files it had not hashed as `missing`. The check
  now records the timeout itself, in every work loop, and kills a live `rsync` from any thread.
- `rsync` exit 24 is a warning, not a failure. Any other non-zero exit fails the job and rsync's own
  error lines are reported.

### Added

- Job lifecycle: the engine owns one job at a time, `IDLE → RUNNING → COMPLETED/FAILED`, Cancel kills
  `rsync` and its children (no orphaned process) and keeps the partial counters, optional timeout.
- Preflight checks: readable source, writable destination, source ≠ destination, destination not inside
  source, `rsync` present (with an actionable message that points at Verify Only).
- Scan options in the GUI: ignore globs, include/exclude hidden files, symlink policy.
- Filter boxes on both file lists; relative paths shown in the lists.
- Results tab: one row per outcome with status and path, *Only problems* filter, CSV and JSON export
  with full digests, destination-only (`EXTRA`) detection.
- Progress: the copy phase follows `rsync --info=progress2` transfer percentage, so a single huge file
  moves the bar; a "still running, last activity Ns ago" status appears when rsync goes quiet.
- Bounded log (newest 5000 lines) and preferences (directories, ignore patterns, hidden/symlink policy,
  timeout, job mode, extra-file detection).
- Directory chooser picks the directory you actually selected (`getSelectedFile()`), starting in the
  directory already in that field.
- **Headless CLI**: `java -jar file-copy-util.jar --cli --source DIR --dest DIR --all` (or `--files`)
  with the same engine and semantics, plus CSV/JSON export and exit codes
  (0 nothing to report, 1 job failed, 2 bad command line, 3 files mismatched or missing).
- **Dry run** (`--dry-run` in the CLI, *Dry run (write nothing)* in the GUI): runs rsync with
  `--dry-run` and still compares hashes, so you see what the job would copy and what already mismatches
  without writing anything. Its count means "would be copied" and its message says nothing was written.
- **`--bwlimit KB/s`** caps the transfer rate.
### rsync version matrix (Phase 5, Group C)

- **Behaviour is now verified against rsync 3.1.3, 3.2.7, 3.3.0 and 3.4.0**, not only against the one
  version this machine ships. `RsyncVersionMatrixTest` builds the same tree on each binary and requires
  the same counters, dry runs that write nothing, progress that reaches its end, and interrupted jobs that
  claim only the transfers rsync finished. Extra binaries are supplied with `FCU_RSYNC_BINARIES=path,...`;
  CI builds 3.1.3, 3.3.0 and 3.4.0 from source and runs the matrix, so portability is a gate rather than a
  hope.
- **Fixed: an interrupted copy claimed a transfer that never finished.** The engine treated "rsync never
  reported an `xfr#`" as "no information, keep the announcement count". Measured on all four versions, a
  transfer still in flight prints progress fragments that carry no `xfr#` at all, so progress without
  `xfr#` means *nothing completed*: a throttled 4 MB copy cut at 1.5 s reported 1 file copied while the
  destination held 0 complete files. It now reports 0.
- **Interrupted jobs say which evidence existed**: "announced 3 file(s) for copy and completed 1 of them",
  "announced 1 file(s) for copy and completed none of them", or "it reported no progress, so how many
  finished is unknown". "at most N finished" was dropped: when rsync reported a count it was a count, not a
  bound.
- **A dry run cut short is exempt from the cap** and reports "N file(s) would have been copied"; capping a
  run that was never meant to write anything would have reported 0.
- 122 tests.

### Per-side selection made visible (Phase 5, Group B)

- **The window states what its two checkbox lists mean.** Each list now reads `100000 files, 100000
  selected`, and a line under the mode buttons states the work set and the rule that produced it: "source
  ticks only; a copy ignores the destination list", or "100000 ticked at source, 1 unticked at the
  destination (old.bin)". Excluded paths are named while they are few, and never named when they are many,
  because listing 5 of 9000 would imply the rest do not matter.
- **`--exclude-dest a.txt,...`** is the command line's unticked destination row. On a copy it says so
  rather than doing nothing quietly. Naming every selected file is a usage error.
- **The preview uses the engine's own exclusion rule**, `excludedAtDestination`, so it cannot promise a
  work set the job then ignores; a test compares a job's counters with the numbers its preview showed.
- **Measured GUI cost** (100k and 300k hard-linked trees): scan 1.3 s / 7.1 s; Select All 2-3 ms; a single
  row click 0 ms; filter 15-26 ms; 490 MB / 710 MB resident. The first version of the preview recomputed
  the whole work set per click and cost ~245 ms per click at 100k rows; the preview now indexes rows by
  path and tracks unticked rows incrementally, with an engine test that the fast form and the walk-the-list
  form agree.
- Records that held a `List` were storing and returning it directly (SpotBugs `EI_EXPOSE_REP`/`EI_EXPOSE_REP2`);
  both now copy in the canonical constructor.
- 115 tests.

### Scale and static analysis (Phase 5, Group A)

- **Measured on 100k and 300k file trees** (scan ~1 s / ~3.6 s, Verify Only ~2.4 s / ~7 s). Time is linear;
  memory was the problem, so verification now decides one file at a time instead of building a digest map
  per side, and the destination is not scanned at all unless destination-only reporting is on. A 300k-file
  Verify Only used to run out of memory at 256 MB and now completes inside it.
- **Running out of memory is a failed job, not a crash**: it reports what to do (narrow the selection, turn
  off destination-only detection, or raise `-Xmx`) and exits 1.
- **The watchdog no longer dies silently.** A scheduled task that throws is never rescheduled, which would
  have disabled the job deadline for the rest of the engine's life; the tick now survives anything it throws.
- **`--max-results N`** raises the bound on how many individual results a job keeps. Counters were always
  exact; the export could only ever list what was kept, and now that bound is the user's to set.
- **`javac -Xlint:all -Werror` and SpotBugs at Medium run on every `mvn verify`.** Findings it raised and
  what was done: `Path.getFileName()` is null for a root path, so scanning a filesystem root could throw
  (`DirectoryScanner`, `FileSyncEngine`); `JFileChooser.getSelectedFile()` can return null in the export
  path; a format string with literal newlines in the CLI usage; `EI_EXPOSE_REP` on `TailLog.component()`
  is intentional and annotated as such. The dead `concat` helper was removed.
- **`--help` is longer**: examples, what the counters mean, and the measured cost of large trees.
- 109 tests, headless, driven against real `rsync` when it is installed. Tests that kill a transfer
  throttle rsync with `--bwlimit`, so they write a few megabytes instead of gigabytes.

### Fixed

- Path handling: one identity, `FileEntry(relPath, absolutePath)`. The previous absolute-path string
  arithmetic produced wrong relative paths and wrong selections.
- Scanning no longer follows symlinks by default (a symlink cycle used to be a `StackOverflowError`),
  and an unreadable subdirectory is a warning instead of aborting the whole scan.
- The `--files-from` list is written to a temporary file and always deleted, including on failure and
  cancellation. File names containing a newline or NUL are refused instead of corrupting the argument
  stream.

### Packaging

- Coordinates: `com.fileverifier:file-copy-util` (was `com.filecopyutil:file-copy-util`), jar
  `target/file-copy-util-1.1.0.jar`, matching the repository name. Package and groupId follow the code
  (`com.fileverifier`); the GUI window carries the longer product title *File Verifier & Copier*, which is
  a title rather than a coordinate.
- `maven-shade-plugin` removed (the project has no runtime dependency), so there is one jar and no
  `original-*.jar`.
- `maven-compiler-plugin` pinned with `<release>17</release>`, surefire pinned to 3.2.5 (Maven 3.8.x
  defaults to 2.12.4, which cannot run JUnit 5).
- GitHub Actions CI: build + tests + a CLI smoke test against the packaged jar.

## 1.0.0

Initial release: Swing GUI, recursive scan with per-file checkboxes, Copy & Verify via `rsync`,
Verify Only, SHA-256 hashing, log pane and progress bar. No tests, no CI, no license.
