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
| 2 | bad command line — **including a report path that is already taken, in a directory that does not exist, named by both `--csv` and `--json`, or inside the copied files without `--allow-report-in-tree`**. Report paths are checked before the job starts, so these runs copy nothing |
| 3 | job completed, but files mismatched or were missing at the destination (for `--dry-run`: files that *would* be mismatched or missing) |
| 4 | the job succeeded, but a report could not be written (refused, or the write failed) — the copies happened, the record of them did not |

Other CLI options: `--verify-only`, `--no-verify` (copy without comparing; the two flags contradict each
other and using both is a usage error), `--dry-run`, `--exclude a.txt,...` (with `--all`),
`--exclude-dest a.txt,...` (with `--verify-only`), `--ignore "GLOB,GLOB"`
(adds to the default ignore list), `--no-default-ignore`, `--no-hidden`, `--symlinks skip|link|follow`,
`--no-report-extra`, `--timeout SECONDS`, `--bwlimit KB/s`, `--max-results N`, `--quiet`,
`--allow-report-in-tree` (permit a *new* report file inside the source or destination tree; never permits
overwriting anything, and a report path that is no good is refused **before the copy starts**),
`--allow-collision-names` (accept colliding names and copy them; without it a copy that finds collisions refuses
and writes nothing — in the window, accepting covers the names you were shown, so a different pair in a later job
is asked about again).

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
Totals: checked=512 copied=3 matched=509 mismatched=0 missing=0 extra=0
  transfers: copied=3 would-copy=0 uncertain=0 not-transferred=0, rsync announced=3, rsync said it completed=3; rows reported=512
```

- **checked** — the files a verdict answered something about: `checked = matched + mismatched + missing`. A
  destination-only file was reported, not compared against anything, so it is not one of them; the rows the job
  reported on, extras included, are counted separately as *rows reported*
- **copied** — files this run can **show** it wrote: a file that was not at the destination before and is
  afterwards, at the size its source had, or one rsync replaced outright (a copied symbolic link counts, an
  unchanged file does not)
- **uncertain** — transfers `rsync` started that the destination cannot account for. A file that was already there,
  of the same size, looks the same whether the copy finished or never did, so those transfers are reported as
  uncertain rather than claimed as copies. Re-run with verification to settle them
- **would-copy** — files a `--dry-run` would have written. A dry run never reports `copied`
- **rsync announced / rsync said it completed** — `rsync`'s own counts (the itemize lines, and its `xfr#` figure).
  They are reported beside this program's figures, never merged into them: an announcement says a transfer started.
  Where they disagree with what the destination shows, the message says so — "announced 3, completed 1 of them" —
  and the files in between are listed as uncertain
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

**A job that copies also records what it is about to overwrite** (see *When a copy is interrupted*), and
that record is one small entry per selected file. Measured on this container's local disk, re-measured
after the record was added:

| Tree | Snapshot before the copy | Memory the snapshot adds | Peak RSS of the JVM |
|---|---|---|---|
| 100k files | 0.8 s (destination empty), 0.5 s (destination already holds every file) | 13–19 MB | ~410 MB during a 100k copy |
| 300k files | 2.5 s | 38 MB | ~410 MB for the snapshot alone |

These are timings on one local filesystem in one container, not a general bound: on a network mount the
metadata calls can be several times slower, which is why the snapshot is cancellable — Cancel during it
stops the job rather than walking the whole list, and the files it had not reached are reported as
unconfirmable rather than assumed.

Time scales linearly. Memory is the constraint, and it is deliberately bounded: results are decided one
file at a time (no digest map per side), the destination is scanned only when destination-only reporting
is on, and the results list is capped at 50000 entries — raise it with `--max-results N` when the export
has to list every file, and the counters stay exact either way. If a selection is too large for the JVM,
the job fails with that advice rather than crashing.

## Tests

```bash
mvn test
```

274 tests, headless (no display needed): counter truth (a destination-only file is reported without being
counted as checked, `checked = matched + mismatched + missing`, a copied-and-verified file counted once in each
family, dry runs reporting would-copy and never copied, an interrupted transfer reported uncertain rather than
copied, and the JSON export carrying the same figures as the job it describes), scanner and scan options, hasher, copy/verify semantics against real `rsync` (selection rules, symlink policies, the itemize-line grammar, dry runs, preflight), the job lifecycle (single-job guard, cancellation with no orphaned `rsync`, timeout, partial-transfer reporting), the process tree of a cancelled, closed or completed copy (a local `rsync` run is three processes; none may survive), and the live copy status (the file in flight, its own percentage, `xfr#` completed transfers), symlink verdicts (under LINK a link is judged by its kind and its link text with nothing read through it, dangling
and directory links are carried as objects, FOLLOW compares bytes and says so, and a link in the destination is
never followed), name fidelity end to end (comment-looking names, whitespace, newline and control-character names, exact
name channels, octal escape decoding), what may be reported as copied after an interrupted transfer, write
safety (a copy may not overlap what it copies, selected names contained in their tree for copies *and* for Verify
Only, reports that cannot displace a file and are validated before the job starts, collision acceptance bound to
the job it was given for), scan authority (Run refused before a scan, during a scan, after a typed path is
edited, or after the scan options change; a stale scan result discarded and said so; a running job keeping the
ticks that were on screen when Run was pressed), job ownership (a cancelled, timed-out or closed job says which
of the three it was; one job's cancel flag cannot be read as another job's; a job whose own thread never returns
is reported stopped and cannot block, stop, or rewrite the job after it - including when the program closes while
a callback is blocked, and with a second job's time limit still enforced behind a blocked callback; cancelling a
large read stops the thread that was doing it, and an interrupted comparison is never reported as a mismatch or a
missing file; a job asked for at the same moment the program is closing is either refused or captured and stopped -
never admitted behind a close that already returned - and a job stopped mid-admission gives its destination back;
cleanup runs once and the command line's shutdown hook is taken back afterwards), the CLI
front end, the bounded log buffer, the CSV/JSON report exporter, and the scale limits (the results
bound, result ordering under parallel hashing, a file that exists but cannot be read). The rsync-dependent tests are skipped automatically if `rsync` is not installed. Tests that kill a transfer throttle `rsync` with `--bwlimit` so they write only a few megabytes. rsync output parsing (itemize lines, `--info=progress2` percentages, `xfr#` completed-transfer counts) is
checked against rsync 3.1.3, 3.2.7, 3.3.0 and 3.4.0; CI builds the non-distro ones from source. Point
`FCU_RSYNC_BINARIES=path,path` at other binaries to extend the matrix, or `FCU_RSYNC=/path/to/rsync` to run
the whole suite against one specific build — which is what CI does per matrix entry, and a pinned binary that
cannot run fails the run instead of skipping the rsync tests.

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

## What a report row says

Each row answers two separate questions, because a job can know one and not the other:

- **`transfer`** — what happened to the bytes: `COPIED`, `WOULD_COPY` (dry run), `UNCERTAIN`, `NOT_COPIED`.
- **`verification`** — whether the two ends were compared: `compared`, `pending` (the job meant to compare
  this file and never got to it), `not-applicable` (verification was turned off).

CSV carries both as the `transfer` and `verification` columns, JSON as `transfer` and `verification`
fields, and the window shows the transfer answer in the Results table's `Transfer` column. Both are
appended after the columns that were already there, so anything already reading `status`, `path` and the
two digests still reads the same thing.

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
card, some macOS volumes — they are the same file, and the later transfer overwrites the earlier one.

A **copy** refuses until someone accepts that: nothing is written, and the message names the pairs. Accept it
with `--allow-collision-names`, or by answering *Copy them anyway?* in the window; the warning stays either
way, because accepting the risk is not the same as the risk being gone. In the window the answer belongs to the
job it was asked about - these directories, this kind of job, these files - and the job that runs afterwards is
the same request that was refused, not a new one built from the lists as they now stand. The same pair of names
under other directories, or a selection with extra files in it, is a different question and is asked again.
`--allow-collision-names` is deliberately the wider answer: you typed it for that run. **Verify Only** and **dry run** report
the same names without asking, because they write nothing. If the destination is case-insensitive, the real
fix is to rename one of the files.

### "Run refused - nothing scanned, or the scan no longer matches the window"

A job is started from the lists on screen, so if those lists are not the two directories currently named, Run
refuses and says why in the log:

- **nothing has been scanned** - an empty selection means no files, and guessing "everything" would be the
  opposite bug. Press *Scan Files*.
- **a scan is still reading the directories** - the lists are half-filled; wait for it.
- **a path was edited since the scan** - the rows belonged to the directory that used to be named in the field,
  so they are cleared with it. Scan the new pair.
- **the scan options changed** - hidden files, ignored directories and symbolic links decide which files exist
  in the list, so the rows describe a different set. Scan again to apply the new options.

The job itself runs from one request captured at the moment Run was pressed: unticking rows while a job is
running changes what the *next* job would do, not the one in flight.

A job that has finished keeps its own record. Its rows stay in Results even if you edit a path field afterwards,
its report is exported against the directories that job worked in (not wherever the fields point when you press
Export), and a later request that the pre-flight checks refuse does not take over that record.

### "A scan of ... finished after a newer scan had started; its results were discarded"

Two scans were in flight, or the directories were changed while one was running. The newer scan is the one that
matches the window, so the older result is thrown away rather than put on screen under the current
directory's name. The line is logged because a silently dropped result looks exactly like a scan that found
nothing.

### "Source is inside the destination directory" / "Destination is inside the source directory"

The job would write into the tree it is reading. A copy from a directory into its own subdirectory, or from a
subdirectory up into its parent, means the destination holds a copy of the source and the run cannot say
which copy is the original — and a run stopped half way leaves the source half overwritten by its own copy.
These are refused before `rsync` starts, so nothing on disk has changed. The same applies when the two
directories only *look* separate: a destination that is a symbolic link to the source, or a source reached
through a link inside the destination, is the same directory as far as the filesystem is concerned. Put the
destination outside the source tree. A **Verify Only** job is not refused for overlap: it reads and compares and
overwrites nothing.

### "Refused: N selected path(s) point outside the directory they belong to"

A selected name reaches a file outside the tree the job is working on — either it carries `..`, or it looks
contained and leaves through a linked directory on the way (`dest/sub` being a link is enough). The job refuses
and writes nothing. Fix the name, or remove that link from the destination.

Containment judges the **directories** a name would land in: a linked directory counts as where the write or read
actually goes, and a name for a file that does not exist yet is judged by the directories above it. **Verify Only
is held to the same rule** although it never writes, because comparing a file outside the destination and
reporting its digest as the backup's is a false verdict. What is *not* followed is the final component of a path:
a symbolic link there is an object, not a route, so a source link whose target lives elsewhere is copied as a
link, and a destination link left behind by an earlier copy is replaced by the copy.

### "Refused the CSV report: A file is already there"

A report is never written over something that exists: this program cannot tell a report it wrote earlier from
a file the job is protecting, and guessing is what put data in danger. Choose a path that does not exist yet.
This is noticed **before the copy starts**, so the run that hit it copied nothing and exits **2**. If the path you
wanted is *inside* the source or destination tree, that is a separate question — a new file there is allowed with
`--allow-report-in-tree` (or a confirmation in the window), but an existing file there is never replaced.

### "Refused the CSV report: Something appeared at the report path"

The path was free when the job started and was taken during it — usually by a copied file that happens to have
the report's name. The copies stay exactly as they are, no report appears, and the run exits **4**: a transfer
that happened without its record is not a finished job. Point `--csv`/`--json` somewhere else.

### "Refused before starting: --csv and --json name the same file"

Two formats, one path — one of them would have had to overwrite the other. Give them different paths. That
includes two **spellings** of one path: if `reports/alias` is a link to `reports/real`, then `--csv
reports/real/result` and `--json reports/alias/result` name the same file, and that is caught before the copy
starts. A missing parent directory is reported the same way: the program does not create directories for a
report.

### "This filesystem cannot publish a report without the possibility of replacing a file"
The report path is on a filesystem with no hard links — FAT, exFAT, and some network or FUSE mounts. Publishing a
report there cannot be guaranteed not to replace an existing file, so nothing was written. Choose a path on a
normal Linux/Unix filesystem, or run without `--csv`/`--json`.

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

### "A read of a source file is still running after being asked to stop"

The file is on something that will not answer an interrupt - a stalled mount, an unreadable device. Hashing only
reads, so nothing is written and nothing is damaged, but that disk may stay busy until the read finishes or the
mount comes back. Everything this program can control has been stopped; this is the one thing it cannot promise.

### "Job stopped: it passed the time limit."

The job ran out of the time it was given, which is a setting rather than a fault in the files: the copies were
not reported as different by the stop itself. Raise or clear the limit (`--timeout 0` on the command line, or
the timeout field in the window) and run it again. The log heading says `=== JOB STOPPED ===` rather than
`=== JOB FAILED ===`, and the totals show how far it got.

### "the job's own thread was still finishing when this was reported"

A job was stopped and its processes were killed, but the thread that had been reading `rsync`'s output or
hashing files did not come back within five seconds — usually because it is inside a call that is itself
blocked. The program does not pretend to be still running: it reports the figures that thread had reached,
marks the job stopped, and lets another job start. The per-file rows from that thread are deliberately absent,
and anything it tries to report afterwards is ignored rather than mixed into a later job's results.

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
