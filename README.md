# File Copy Util

A Java GUI application for copying and verifying files between two directories using `rsync` and SHA-256 hashing.

## Features

- **Copy & Verify** — Uses `rsync` to copy the selected files, then verifies integrity with SHA-256 hashes
- **Verify Only** — Compares SHA-256 hashes of the selected files between source and destination
- **File Selection** — Recursively scans directories and displays all files with checkboxes to include/exclude
- **Explicit Selection Semantics** — A job only ever touches the files you ticked; unticking everything means *nothing*, never *everything*
- **Measured Reporting** — Copied/matched/mismatched/missing counts come from rsync's itemized output and from hashing exactly the selected files
- **Progress Tracking** — Real-time progress bar (against the selected file count) and detailed file-by-file logging with SHA-256 hash prefixes
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
6. **Run Job** — Click "Run Job" to start the operation

### Job Types

| Mode | What it does | Which files it covers |
|------|--------------|------------------------|
| **Copy & Verify** | Copies files with `rsync` (`-a --checksum --itemize-changes --files-from=<selection>`), then compares SHA-256 hashes | the files ticked in the **source** list |
| **Verify Only** | Compares SHA-256 hashes without copying | files ticked in the **source** list, minus any you unticked in the destination list |

A file that has no row in the destination list was not unticked there — it is absent, and Verify Only reports it as `MISSING`.

An empty selection is refused rather than treated as "all files": use **Select All** to include everything.

### Reading the results

```
Copy & Verify Complete: 512 file(s) checked, 3 copied, 509 matched, 0 mismatched, 0 missing
Totals: checked=512 copied=3 matched=509 mismatched=0 missing=0
```

- **checked** — the selected files that were verified (`checked = matched + mismatched + missing`)
- **copied** — files `rsync` actually transferred; unchanged files are not counted
- **mismatch** — present at both ends with different SHA-256 (`src=` / `dst=` prefixes are shown)
- **missing** — selected in the source, not present at the destination

`rsync` exit code 24 ("some files vanished before they could be transferred") is reported as a warning, not a failure; any other non-zero exit code fails the job and `rsync`'s own error lines are logged.

## Tests

```bash
mvn test
```

33 unit/integration tests, headless (no display needed). `FileSyncEngineTest` drives the real `rsync` binary; those tests are skipped automatically if `rsync` is not installed.

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
