# Changelog

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
- **Headless CLI**: `java -jar file-verifier.jar --cli --source DIR --dest DIR --all` (or `--files`)
  with the same engine and semantics, plus CSV/JSON export and exit codes
  (0 nothing to report, 1 job failed, 2 bad command line, 3 files mismatched or missing).
- **Dry run** (`--dry-run` in the CLI, *Dry run (write nothing)* in the GUI): runs rsync with
  `--dry-run` and still compares hashes, so you see what the job would copy and what already mismatches
  without writing anything. Its count means "would be copied" and its message says nothing was written.
- **`--bwlimit KB/s`** caps the transfer rate.
- 104 tests, headless, driven against real `rsync` when it is installed. Tests that kill a transfer
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

- Coordinates renamed to match the code and the window title: `com.fileverifier:file-verifier`
  (was `com.filecopyutil:file-copy-util`), jar `target/file-verifier-1.1.0.jar`.
- `maven-shade-plugin` removed (the project has no runtime dependency), so there is one jar and no
  `original-*.jar`.
- `maven-compiler-plugin` pinned with `<release>17</release>`, surefire pinned to 3.2.5 (Maven 3.8.x
  defaults to 2.12.4, which cannot run JUnit 5).
- GitHub Actions CI: build + tests + a CLI smoke test against the packaged jar.

## 1.0.0

Initial release: Swing GUI, recursive scan with per-file checkboxes, Copy & Verify via `rsync`,
Verify Only, SHA-256 hashing, log pane and progress bar. No tests, no CI, no license.
