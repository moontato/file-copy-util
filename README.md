# File Copy Util

A Java GUI application for copying and verifying files between two directories using `rsync` and SHA-256 hashing.

## Features

- **Copy & Verify** — Uses `rsync` to copy files, then verifies integrity with SHA-256 hashes
- **Verify Only** — Compares SHA-256 hashes between source and destination without copying
- **File Selection** — Recursively scans directories and displays all files with checkboxes to include/exclude
- **Progress Tracking** — Real-time progress bar and detailed file-by-file logging with SHA-256 hash prefixes
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

| Mode | Description |
|------|-------------|
| **Copy & Verify** | Copies selected files using `rsync`, then verifies integrity by comparing SHA-256 hashes |
| **Verify Only** | Compares SHA-256 hashes of selected files between source and destination |

## Project Structure

```
.
├── pom.xml
├── README.md
└── src/main/java/com/fileverifier/
    ├── FileVerifierApp.java      # Main entry point, headless/Wayland handling
    ├── FileVerifierFrame.java    # GUI frame (selectors, lists, progress, log)
    ├── DirectoryScanner.java     # Recursive file scanner
    ├── FileSyncEngine.java       # Copy/verify logic using rsync + SHA-256
    └── FileHasher.java           # SHA-256 hash computation
```

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
