# File Verifier & Copier

A Java tool for copying and verifying files between two directories using `rsync` and SHA-256 hashing.
It has a Swing GUI and a headless CLI that share one engine.

See [DESIGN.md](DESIGN.md) for the rules the program guarantees (path model, selection semantics,
counters) and [CHANGELOG.md](CHANGELOG.md) for what changed in 1.1.0.

## Features

- **Copy & Verify** — Uses `rsync` to copy the selected files, then verifies integrity with SHA-256 hashes
- **Verify Only** — Compares SHA-256 hashes of the selected files between source and destination
- **File Selection** — Recursively scans directories and displays all files with checkboxes to include/exclude
- **Explicit Selection Semantics** — A job only ever touches the files you ticked; unticking everything means *nothing*, never *everything*
- **Scan Options** — Ignore patterns (globs, e.g. `.git, node_modules, *.bin`), include/exclude hidden files, and a symlink policy (skip / copy as link / follow target); options apply on the next **Scan Files**, never silently behind your back
- **Filter Boxes** — Narrow each list by substring without changing what is ticked
- **One Job At A Time** — The engine owns a single job: while it runs, Browse/Scan/mode controls are disabled and **Cancel** becomes available; cancelling terminates `rsync` (and its children) and reports the partial progress reached
- **Job Timeout** — An optional per-job timeout (default: none) kills a runaway `rsync` instead of hanging forever
- **Measured Reporting** — Copied/matched/mismatched/missing counts come from rsync's itemized output and from hashing exactly the selected files
- **Progress Tracking** — Progress bar against the selected work set, driven by `rsync --info=progress2` percentages (so a single huge file moves the bar), plus a "still running, last activity Ns ago" status when rsync is silent
- **Results Table and Export** — Every outcome listed with status, path and full SHA-256 on both sides, an *Only problems* filter, destination-only (`EXTRA`) detection, and CSV/JSON export
- **Preflight Checks** — Source readable, destination writable, source not equal to or containing the destination, and `rsync` present, all checked before anything is touched
- **Safe Scanning** — Unreadable subdirectories become warnings instead of aborting the scan; symlink cycles are reported, not followed
- **Bounded Log** — The on-screen log keeps its newest 5000 lines; the export is the archive
- **Saved Preferences** — Directories, ignore patterns, hidden/symlink policy, timeout, job mode and extra-file detection are restored in the next window
- **Smart Directory Selection** — The chooser starts in the directory already in that field (falling back to the other field, then home) and takes the directory you actually clicked
- **Headless CLI** — `--cli` runs the same engine without a display, with CSV/JSON export and exit codes for scripting
- **Wayland Support** — Automatically detects Wayland sessions and falls back to X11 toolkit

## Requirements

- **Java 17+** (OpenJDK recommended)
- **Linux/Unix** (Windows not supported)
- **`rsync`** installed on the system
- **Full JRE** (not headless) — required for the GUI; `--cli` works on a headless JRE

### Ubuntu/Debian

```bash
sudo apt install openjdk-21-jre rsync
```

## Build

```bash
mvn clean package
```

Output: `target/file-verifier-1.1.0.jar` (one jar - the project has no runtime dependencies).

## Usage

```bash
# GUI
java -jar target/file-verifier-1.1.0.jar

# Headless
java -jar target/file-verifier-1.1.0.jar --cli --source /data/src --dest /backup/dst --all
java -jar target/file-verifier-1.1.0.jar --cli --source /data/src --dest /backup/dst --files a.txt,sub/b.txt --csv report.csv
java -jar target/file-verifier-1.1.0.jar --cli --source /data/src --dest /backup/dst --all --verify-only --json report.json
java -jar target/file-verifier-1.1.0.jar --cli --help
```

The CLI runs the same engine with the same selection rules: either `--files` or `--all` is required, and
`--all` is expanded into an explicit list by scanning first.

| Exit code | Meaning |
|---|---|
| 0 | job completed, nothing mismatched or missing |
| 1 | the job failed: preflight problem, rsync error, cancellation |
| 2 | bad command line |
| 3 | job completed, but files mismatched or were missing at the destination |

Other CLI options: `--verify-only`, `--exclude a.txt,...` (with `--all`), `--ignore "GLOB,GLOB"` (adds to
the default ignore list), `--no-default-ignore`, `--no-hidden`, `--symlinks skip|link|follow`,
`--no-report-extra`, `--timeout SECONDS`, `--quiet`.

### Workflow

1. **Select Source Directory** — Click "Browse..." next to the source field. The chooser opens in the directory already in that field (or the other field, or home)
2. **Select Destination Directory** — Click "Browse..." next to the destination field
3. **Set Scan Options** — Ignore patterns, hidden files, and how symbolic links are handled
4. **Scan Files** — Click "Scan Files" to recursively scan both directories and populate the file lists. Changing an option afterwards says the scan is stale; press **Scan Files** again to apply it
5. **Select Files** — Use checkboxes to include/exclude files. "Select All" / "Deselect All" for convenience; a filter box narrows what you see without changing what is ticked
6. **Choose Job Type** — Toggle between "Copy & Verify" and "Verify Only"
7. **Run Job** — Start the operation. While a job runs, the other controls are locked; use **Cancel** to stop it, or set **Timeout (s, 0 = none)** before starting to cap how long the job may take
8. **Read the Results tab** — One row per outcome with full hashes; tick *Only problems* to see just mismatches, missing and extra files, or export CSV/JSON

### Job Types

| Mode | What it does | Which files it covers |
|------|--------------|------------------------|
| **Copy & Verify** | Copies files with `rsync` (`-a --checksum --itemize-changes --info=progress2 --files-from=<selection>`), then compares SHA-256 hashes | the files ticked in the **source** list |
| **Verify Only** | Compares SHA-256 hashes without copying | files ticked in the **source** list, minus rows you explicitly unticked in the destination list, plus (optionally) destination-only files |

A file that has no row in the destination list was not unticked there — it is absent, and Verify Only reports it as `MISSING`. A destination list that is out of date never silently excludes a file from verification.

An empty selection is refused rather than treated as "all files": use **Select All** to include everything.

### Reading the results

```
Copy & Verify Complete: 512 file(s) checked, 3 copied, 509 matched, 0 mismatched, 0 missing
Totals: checked=512 copied=3 matched=509 mismatched=0 missing=0
```

- **checked** — the selected files that were verified (`checked = matched + mismatched + missing`, extras counted separately)
- **copied** — items `rsync` reported as creating or changing at the destination (a copied symbolic link counts, an unchanged file does not). On a cancelled job this can overstate what actually finished, because rsync announces a file before its data has moved
- **mismatch** — present at both ends with different SHA-256 (`src=` / `dst=` prefixes are shown)
- **missing** — selected in the source, not present at the destination
- **destination-only** (`EXTRA`) — present at the destination, not in the source selection (Verify Only, when *Report destination-only files* is on)

A **failed** job (cancelled, timed out, or an rsync error) reports the partial counters it reached rather than zeros.

`rsync` exit code 24 ("some files vanished before they could be transferred") is reported as a warning, not a failure; any other non-zero exit code fails the job and `rsync`'s own error lines are logged. Cancelling or timing out kills the `rsync` process (exit 137) and may leave a hidden temporary file in the destination directory.

## Tests

```bash
mvn test
```

93 tests, headless (no display needed): scanner and scan options, hasher, copy/verify semantics against real `rsync` (selection rules, symlink policies, the itemize-line grammar, preflight), the job lifecycle (single-job guard, cancellation with no orphaned `rsync`, timeout), the CLI front end, the bounded log buffer, and the CSV/JSON report exporter. The rsync-dependent tests are skipped automatically if `rsync` is not installed. The lifecycle tests create 1.5 GB sparse files, so that suite takes a few seconds. CI (`.github/workflows/ci.yml`) runs `mvn -B verify` plus a CLI smoke test against the packaged jar.

## Project Structure

```
.
├── pom.xml
├── README.md
├── LICENSE                              # MIT (see the License section)
├── DESIGN.md                            # the rules the engine guarantees
├── CHANGELOG.md                         # what changed, behaviour first
├── .github/workflows/ci.yml               # build + tests + packaged-jar CLI smoke test
├── plan/                             # roadmap / phase notes
└── src/main/java/com/fileverifier/
    ├── FileVerifierApp.java          # Entry point: GUI, or --cli headless; Wayland/headless handling
    ├── Cli.java                      # Headless front end (--cli): same engine, exit codes
    ├── FileVerifierFrame.java        # GUI frame (selectors, lists, filters, results, export, log)
    ├── DirectoryScanner.java         # Recursive scanner -> FileEntry (relative paths)
    ├── ScanOptions.java              # Ignore patterns, hidden files, symlink policy
    ├── FileEntry.java                # (relPath, absolutePath) - the app's single file identity
    ├── FileSyncEngine.java           # Copy/verify logic using rsync + SHA-256 (no UI dependency)
    ├── FileHasher.java               # SHA-256 hash computation
    ├── ReportExporter.java           # CSV / JSON job reports with full digests
    └── TailLog.java                  # Bounded log tail buffer
```

`FileSyncEngine` has no Swing dependency, so the copy/verify logic can be run and tested headlessly.

## Troubleshooting

### "No display available" error

Only the GUI needs a display. On a machine without one, use `--cli`:

```bash
java -jar target/file-verifier-1.1.0.jar --cli --source /data/src --dest /backup/dst --all
```

On Wayland sessions, the app automatically falls back to X11. If you still see this error, ensure:
- `DISPLAY` is set (`echo $DISPLAY` should output `:0` or similar)
- You have the **full JRE** installed, not the headless version:
  ```bash
  dpkg -l | grep openjdk
  # Should show openjdk-XX-jre, NOT openjdk-XX-jre-headless
  ```

### "libawt_xawt.so" not found

This means the headless JRE is installed. Install the full JRE:

```bash
sudo apt install openjdk-21-jre
```

## License

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 moontato.

MIT was chosen because it keeps every later option open: this code can be re-released under a stricter
license at any future version, while copies already distributed stay MIT. The choice itself is recorded
in the roadmap (item 4.4).
