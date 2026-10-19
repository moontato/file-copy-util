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
- 93 tests, headless, driven against real `rsync` when it is installed.

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
