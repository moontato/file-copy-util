# File Copy Util

[![build](https://github.com/moontato/file-copy-util/actions/workflows/ci.yml/badge.svg)](https://github.com/moontato/file-copy-util/actions/workflows/ci.yml)

Copy and verify files between two directories with `rsync` and SHA-256. One engine, two front ends: a
Swing GUI and a headless CLI. The repository, the artifact and the CLI all use the short name
`file-copy-util`; the GUI window carries the longer product title *File Verifier & Copier*.

See [DESIGN.md](DESIGN.md) for the rules the program guarantees (path model, selection semantics,
counters) and [CHANGELOG.md](CHANGELOG.md) for what changed between 1.1.0 and 1.2.0.

## Features

- **Copy** — Uses `rsync` to copy the selected files, with a *Verify after copying* switch (on by default)
  that compares SHA-256 hashes of what was written afterwards
- **Verify only** — Compares SHA-256 hashes of the selected files between source and destination without
  running `rsync` at all: the only job that looks at a destination without touching it
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
- **Dry Run** — *Dry run (write nothing)* runs `rsync --dry-run` and still compares hashes: you see what the job would copy and what already mismatches, and nothing is written
- **Partial-Transfer Reporting** — after a cancelled or timed-out copy, the engine looks for the hidden partial files `rsync` left behind and names them with their sizes; it never deletes them
- **Bandwidth Cap** — `--bwlimit KB/s` caps the transfer rate
- **Saved Preferences** — Directories, ignore patterns, hidden/symlink policy, timeout, job mode and extra-file detection are restored in the next window
- **Smart Directory Selection** — The chooser starts in the directory already in that field (falling back to the other field, then home) and takes the directory you actually clicked
- **Headless CLI** — `--cli` runs the same engine without a display, with CSV/JSON export and exit codes for scripting
- **Modern interface** — FlatLaf theme with light or dark mode (remembered between runs), cards instead of group boxes, labels above fields, a segmented *Copy / Verify only* control with a *Verify after copying* switch beside it, one filled action button whose label names the job it will run, and a results table whose status column carries a colour dot beside the word
- **Keyboard shortcuts** — `Ctrl+S` scan, `Ctrl+R` run, `Escape` cancel; `Enter` still activates whatever control has focus
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

Output: `target/file-copy-util-1.4.0.jar` - one runnable jar with the FlatLaf theme bundled (~1.1 MB).
`target/original-file-copy-util-1.4.0.jar` is the same program without the theme: it still runs, in the
platform look and feel.

## Usage

```bash
# GUI
java -jar target/file-copy-util-1.4.0.jar

# Headless
java -jar target/file-copy-util-1.4.0.jar --cli --source /data/src --dest /backup/dst --all
java -jar target/file-copy-util-1.4.0.jar --cli --source /data/src --dest /backup/dst --files a.txt,sub/b.txt --csv report.csv
java -jar target/file-copy-util-1.4.0.jar --cli --source /data/src --dest /backup/dst --all --verify-only --json report.json
java -jar target/file-copy-util-1.4.0.jar --cli --source /data/src --dest /backup/dst --all --no-verify
java -jar target/file-copy-util-1.4.0.jar --cli --source /data/src --dest /backup/dst --files-from0 names.lst
java -jar target/file-copy-util-1.4.0.jar --cli --source /data/src --dest /backup/dst --all --dry-run
java -jar target/file-copy-util-1.4.0.jar --cli --help
```

The CLI runs the same engine with the same selection rules: either `--files`, `--files-from(0)` or `--all` is
required, and `--all` is expanded into an explicit list by scanning first.

`--files a,b` is comma syntax with each name trimmed, which cannot express a name containing a comma,
starting with `#` or `;`, or padded with spaces. The list-file channels can: `--files-from FILE` reads one
name per line, `--files-from0 FILE` reads NUL-delimited names (the only form that can name a file whose own
name contains a newline), and the same two forms exist for `--exclude` and `--exclude-dest`. Names in those
files are used exactly as written; a file that is not valid UTF-8 is refused rather than guessed at.

| Exit code | Meaning |
|---|---|
| 0 | job completed, nothing mismatched or missing |
| 1 | the job failed: preflight problem, rsync error, cancellation |
| 2 | bad command line |
| 3 | job completed, but files mismatched or were missing at the destination (for `--dry-run`: files that *would* be mismatched or missing) |

Other CLI options: `--verify-only`, `--no-verify` (copy without comparing; the two flags contradict each
other and using both is a usage error), `--dry-run`, `--exclude a.txt,...` (with `--all`),
`--exclude-dest a.txt,...` (with `--verify-only`), `--ignore "GLOB,GLOB"`
(adds to the default ignore list), `--no-default-ignore`, `--no-hidden`, `--symlinks skip|link|follow`,
`--no-report-extra`, `--timeout SECONDS`, `--bwlimit KB/s`, `--max-results N`, `--quiet`.

### Workflow

The two directory fields are typed, not chooser-only: type a path (or paste one) and press Enter to load both
file lists. Browse is still there and starts in the directory already named. `~` means your home directory and a
relative path is read from where the program was started. Changing a directory clears the lists that belonged to
the old one. The version of the program is printed in muted text at the bottom right of the window.

1. **Select Source Directory** — Click "Browse..." next to the source field. The chooser opens in the directory already in that field (or the other field, or home)
2. **Select Destination Directory** — Click "Browse..." next to the destination field
3. **Set Scan Options** — Ignore patterns, hidden files, and how symbolic links are handled
4. **Scan Files** — Click "Scan Files" to recursively scan both directories and populate the file lists. Changing an option afterwards says the scan is stale; press **Scan Files** again to apply it
5. **Select Files** — Use checkboxes to include/exclude files. "Select All" / "Deselect All" for convenience; a filter box narrows what you see without changing what is ticked
6. **Choose what the job does** — *Copy* or *Verify only*, and for a copy whether *Verify after copying* is
   ticked (it is by default; untick it for a plain copy that hashes nothing)
7. **Run** — Start the operation. While it runs the status line says what is happening in the phase it names:
   `hashing source` (reading the source to hash it - nothing written yet), then `copying` with the file being
   written now and how far along it is (`now writing: big.bin (9.9 MB of 114.4 MB, 9%)`), then `verifying`
   with the file just compared Tick **Dry run (write nothing)** first to see what the job would do. While a job runs, the other controls are locked; use **Cancel** to stop it, or set **Timeout (s, 0 = none)** before starting to cap how long the job may take
8. **Read the Results tab** — One row per outcome with full hashes; tick *Only problems* to see just mismatches, missing and extra files, or export CSV/JSON

### Job Types

| Mode | What it does | Which files it covers |
|------|--------------|------------------------|
| **Copy** (verify ticked) | Copies files with `rsync` (`-a --checksum --itemize-changes --info=progress2 --files-from=<selection>`), then compares SHA-256 hashes | the files ticked in the **source** list |
| **Copy** (verify unticked) | Copies files with `rsync` and hashes nothing at all; each written file is reported as `COPIED` | the files ticked in the **source** list |
| **Verify only** | Compares SHA-256 hashes without copying | files ticked in the **source** list, minus rows you explicitly unticked in the destination list, plus (optionally) destination-only files |

A file that has no row in the destination list was not unticked there — it is absent, and Verify Only reports it as `MISSING`. A destination list that is out of date never silently excludes a file from verification.

An empty selection is refused rather than treated as "all files": use **Select All** to include everything.

### Reading the results

```
Copy & Verify Complete: 512 file(s) checked, 3 copied, 509 matched, 0 mismatched, 0 missing
Totals: checked=512 copied=3 matched=509 mismatched=0 missing=0
```

- **checked** — the selected files that were verified (`checked = matched + mismatched + missing`, extras counted separately)
- **copied** — items `rsync` created or changed at the destination (a copied symbolic link counts, an unchanged file does not). rsync announces a file before its data has moved, so on a job that was cut short this is capped by rsync's own completed-transfer count (`xfr#`) and the message says "announced N, of which at most M finished"
- **mismatch** — present at both ends with different SHA-256 (`src=` / `dst=` prefixes are shown)
- **missing** — selected in the source, not present at the destination
- **destination-only** (`EXTRA`) — present at the destination, not in the source selection (Verify Only, when *Report destination-only files* is on)
- **copied** (`COPIED`) — written by a job whose verification was turned off, listed with no hashes because
  none were computed. It is not coloured like a file that passed a check, because nothing checked it, and the
  job's own summary and warnings say that no comparison happened:
  `Copy complete: 214 file(s) copied. Verification was turned off, so none of them were compared.`
  A dry run of the same job lists `WOULD_COPY` instead, since nothing was written

A **failed** job (cancelled, timed out, or an rsync error) reports the partial counters it reached rather than zeros.

`rsync` exit code 24 ("some files vanished before they could be transferred") is reported as a warning, not a failure; any other non-zero exit code fails the job and `rsync`'s own error lines are logged.

An interrupted transfer is written to a hidden temporary name (`.big.bin.QafPvi`) that is renamed only when the transfer completes. After a cancelled or timed-out copy the engine reports what it finds:

```
warning: 1 partial rsync file(s) left in the destination: .big.bin.QafPvi (for big.bin, 3.8 MB).
         They are incomplete transfers - delete them or re-run the copy to finish them.
```

or, when rsync cleaned up after itself, `No partial rsync file was left in the destination.` The engine never deletes them: whether a hidden file in your directory is junk is your call.

## Scale

Measured on this build, on a tree of 100 directories x 1000 files (hard links, so the fixture itself
costs kilobytes):

| Tree | Scan | Verify Only | Copy & Verify | Heap needed | Window scan / rows resident |
|---|---|---|---|---|---|
| 100k files | ~1 s | ~2.4 s | ~6 s (dry run) | fits in 128 MB | 1.3 s, ~490 MB |
| 300k files | ~3.6 s | ~7 s | ~17 s (dry run) | ~256 MB verify, ~320 MB copy | 7.1 s, ~710 MB |

Time scales linearly. Memory is the constraint, and it is deliberately bounded: results are decided one
file at a time (no digest map per side), the destination is scanned only when destination-only reporting
is on, and the results list is capped at 50000 entries — raise it with `--max-results N` when the export
has to list every file, and the counters stay exact either way. If a selection is too large for the JVM,
the job fails with that advice rather than crashing.

## Tests

```bash
mvn test
```

176 tests, headless (no display needed): scanner and scan options, hasher, copy/verify semantics against real `rsync` (selection rules, symlink policies, the itemize-line grammar, dry runs, preflight), the job lifecycle (single-job guard, cancellation with no orphaned `rsync`, timeout, partial-transfer reporting), the process tree of a cancelled, closed or completed copy (a local `rsync` run is three processes; none may survive), and the live copy status (the file in flight, its own percentage, `xfr#` completed transfers), the CLI front end, the bounded log buffer, the CSV/JSON report exporter, and the scale limits (the results
bound, result ordering under parallel hashing, a file that exists but cannot be read). The rsync-dependent tests are skipped automatically if `rsync` is not installed. Tests that kill a transfer throttle `rsync` with `--bwlimit` so they write only a few megabytes. rsync output parsing (itemize lines, `--info=progress2` percentages, `xfr#` completed-transfer counts) is
checked against rsync 3.1.3, 3.2.7, 3.3.0 and 3.4.0; CI builds the non-distro ones from source. Point
`FCU_RSYNC_BINARIES=path,path` at other binaries to extend the matrix.

`mvn verify` also runs the static-analysis gates: `javac -Xlint:all -Werror` and SpotBugs at Medium
severity. CI (`.github/workflows/ci.yml`) runs `mvn -B verify` plus a CLI smoke test against the packaged
jar; pushing a `v*` tag runs `.github/workflows/release.yml`, which attaches the jar to a GitHub Release.

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

## When a copy is interrupted

rsync writes each file as a hidden temporary in the destination and renames it when that transfer completes,
so a stopped copy does not leave a half-written file carrying your file's name. What it cannot tell you is
whether one particular transfer finished: if the destination already held a file of the same size as the
source, then after an interrupted overwrite it holds the old bytes, the old inode and the same size —
indistinguishable from a file the job never touched. So a stopped job names every file it started and marks
each one `COPIED`, `WOULD_COPY` (dry run) or `UNCERTAIN`; a verifying job stopped before the comparison says
`PENDING`. `UNCERTAIN` is not an error, it is the honest answer: re-run the job with verification on and
that file will be compared.

## Troubleshooting

### "1 entry(ies) could not be read because their names contain characters this JVM cannot represent"

File names are decoded using the JVM's locale. Under `LANG=C` / `LC_ALL=C` (common in cron jobs, systemd
services, minimal containers and `sudo`) every non-ASCII byte in a name is unrepresentable, so files with
accents, Cyrillic, CJK and similar names cannot be listed, copied or verified. The program skips them and
says so rather than guessing a name or crashing. To handle them, run with a UTF-8 locale:

```bash
LC_ALL=C.UTF-8 java -jar target/file-copy-util-1.4.0.jar --cli --source DIR --dest DIR --all
```

### "N name collision(s) in the work set"

The selected files include names that differ only by case (`A.txt` and `a.txt`) or only by how an accent is
composed. Both exist on Linux; on a case-insensitive or normalising destination — a Windows share, an exFAT
card, some macOS volumes — they are the same file, and the later transfer overwrites the earlier one. The job
runs and says so up front. If the destination is case-insensitive, rename one of them.

### "changed in the source while the job ran"

A copy verifies against the digest taken *before* the transfer started. If a source file was edited during the
job, the mismatch is that moving file rather than damage done by the copy, and the job names it. This cannot
happen in Verify Only, which has no before-and-after to compare.

### Nothing appears in the destination while a large file copies
`rsync` writes each file as a **hidden temporary file** in its destination folder - `.big.bin.qjX7kX` - and renames it to its
real name only when that file's transfer is complete. A 30 GB file is therefore being written the whole time, under a name your
file manager hides, and appears at the end of its transfer, not the start. Show hidden files to watch it grow; the status line
names the file in flight and how much of it has landed. The window logs this once per copy job.

### The source drive keeps reading after I cancelled or closed the window
It should not. A local `rsync` copy is three processes (parent, child, grandchild). Cancelling, a job timeout, or closing the
window snapshots that whole tree *before* signalling it, sends `rsync` SIGTERM so it deletes the partial file it was writing, and
SIGKILLs anything that survives; a completed copy sweeps the tree as well, because `rsync`'s parent can exit while a child is still
finishing. A job cancelled while the source is still being hashing never starts `rsync` at all. If you see `rsync` processes that
outlive the program, that is a bug: report it with `ps` output and what you were copying.

### "Another job in this program is already using the destination"

Two jobs writing the same directory interleave: each reports the other's files as its own, and a partial-transfer
warning can name a file the other job is still writing. The second job stops instead. This guard covers jobs in
one program — two windows, or a window and a CLI run in the same JVM. Two *separate* processes are not guarded;
don't run two copies into the same destination at once.

### A file name containing a carriage return is not copied

rsync's file list is a line list, so a name containing a newline, a carriage return or a NUL byte cannot be
passed safely. Such names are refused before anything is written, listed in the warnings, and reported as not
transferred. Rename them if you need them copied.

### "No display available" error

Only the GUI needs a display. On a machine without one, use `--cli`:

```bash
java -jar target/file-copy-util-1.4.0.jar --cli --source /data/src --dest /backup/dst --all
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

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 moontato. The runnable jar bundles FlatLaf
(Apache-2.0); see [NOTICE](NOTICE) and [DESIGN.md](DESIGN.md) for what that dependency does and does
not touch.

MIT was chosen because it keeps every later option open: this code can be re-released under a stricter
license at any future version, while copies already distributed stay MIT. The choice itself is recorded
in the roadmap (item 4.4).
