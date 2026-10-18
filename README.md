# File Copy Util

A Java GUI application for copying and verifying files between two directories using `rsync` and SHA-256 hashing.

## Features

- **Copy & Verify** — Uses `rsync` to copy the selected files, then verifies integrity with SHA-256 hashes
- **Verify Only** — Compares SHA-256 hashes of the selected files between source and destination
- **File Selection** — Recursively scans directories and displays all files with checkboxes to include/exclude
- **Explicit Selection Semantics** — A job only ever touches the files you ticked; unticking everything means *nothing*, never *everything*
- **One Job At A Time** — The engine owns a single job: while it runs, Browse/Scan/mode controls are disabled and **Cancel** becomes available; cancelling terminates `rsync` (and its children) and reports the partial progress reached
- **Job Timeout** — An optional per-job timeout (default: none) kills a runaway `rsync` instead of hanging forever
- **Measured Reporting** — Copied/matched/mismatched/missing counts come from rsync's itemized output and from hashing exactly the selected files
- **Progress Tracking** — Progress bar against the selected file count, plus a "still running, last activity Ns ago" status when rsync is silent during a long transfer
- **Safe Scanning** — `.git` and similar directories are ignored; symlinks are skipped rather than followed (no infinite recursion)
- **Smart Directory Selection** — File navigator opens at the previously selected path's parent for quick nearby folder picking
- **Wayland Support** — Automatically detects Wayland sessions and falls back to X11 toolkit

## Requirements

- **Java 17+** (OpenJDK recommended)
- **Linux/Unix** (Windows not supported)
- **`rsync`** installed on the system
- **Full JRE** (not headless) — GUI libraries are required

### Ubuntu/Debian

```bash
sudo apt install openjdk-21-jre rsync
```

## Build

```bash
mvn clean package
```

Output: `target/file-copy-util-1.0.0.jar`

## Usage

```bash
java -jar target/file-copy-util-1.0.0.jar
```

### Workflow

1. **Select Source Directory** — Click "Browse..." next to the source field. The file navigator opens at the last selected path (or its parent) for easy nearby selection
2. **Select Destination Directory** — Click "Browse..." next to the destination field. The navigator opens at the source directory's parent so you can quickly pick a sibling or nearby folder
3. **Scan Files** — Click "Scan Files" to recursively scan both directories and populate the file lists
4. **Select Files** — Use checkboxes to include/exclude files. Use "Select All" / "Deselect All" for convenience
5. **Choose Job Type** — Toggle between "Copy & Verify" and "Verify Only"
6. **Run Job** — Start the operation. While a job runs, the other controls are locked; use **Cancel** to stop it, or set **Timeout (s, 0 = none)** before starting to cap how long the job may take

### Job Types

| Mode | What it does | Which files it covers |
|------|--------------|------------------------|
| **Copy & Verify** | Copies files with `rsync` (`-a --checksum --itemize-changes --files-from=<selection>`), then compares SHA-256 hashes | the files ticked in the **source** list |
| **Verify Only** | Compares SHA-256 hashes without copying | files ticked in the **source** list, minus rows you explicitly unticked in the destination list |

A file that has no row in the destination list was not unticked there — it is absent, and Verify Only reports it as `MISSING`. A destination list that is out of date never silently excludes a file from verification.

An empty selection is refused rather than treated as "all files": use **Select All** to include everything.

### Reading the results

```
Copy & Verify Complete: 512 file(s) checked, 3 copied, 509 matched, 0 mismatched, 0 missing
Totals: checked=512 copied=3 matched=509 mismatched=0 missing=0
```

- **checked** — the selected files that were verified (`checked = matched + mismatched + missing`)
- **copied** — files `rsync` reported as transferring; unchanged files are not counted. On a cancelled job this can overstate what actually finished, because rsync announces a file before its data has moved
- **mismatch** — present at both ends with different SHA-256 (`src=` / `dst=` prefixes are shown)
- **missing** — selected in the source, not present at the destination

A **failed** job (cancelled, timed out, or an rsync error) reports the partial counters it reached rather than zeros.

`rsync` exit code 24 ("some files vanished before they could be transferred") is reported as a warning, not a failure; any other non-zero exit code fails the job and `rsync`'s own error lines are logged. Cancelling or timing out kills the `rsync` process (exit 137) and may leave a hidden temporary file in the destination directory.

## Tests

```bash
mvn test
```

45 tests, headless (no display needed): scanner, hasher, copy/verify semantics, and the job lifecycle (single-job guard, cancellation with no orphaned `rsync`, timeout). The rsync-dependent tests are skipped automatically if `rsync` is not installed. The lifecycle tests create 1.5 GB sparse files, so that suite takes a few seconds.

## Project Structure

```
.
├── pom.xml
├── README.md
├── plan/                            # roadmap / phase notes
└── src/main/java/com/fileverifier/
    ├── FileVerifierApp.java         # Main entry point, headless/Wayland handling
    ├── FileVerifierFrame.java       # GUI frame (selectors, lists, progress, log)
    ├── DirectoryScanner.java        # Recursive scanner -> FileEntry (relative paths)
    ├── FileEntry.java               # (relPath, absolutePath) - the app's single file identity
    ├── FileSyncEngine.java          # Copy/verify logic using rsync + SHA-256 (no UI dependency)
    └── FileHasher.java              # SHA-256 hash computation
```

`FileSyncEngine` has no Swing dependency, so the copy/verify logic can be run and tested headlessly.

## Troubleshooting

### "No display available" error

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

Not specified.
