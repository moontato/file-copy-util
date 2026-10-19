# Design

What the program guarantees, and where those guarantees come from. The README explains how to use it;
this explains why it is built this way, so a change can be judged against the rules instead of against
the code.

## 1. One path identity

Every file in the program is a `FileEntry(String relPath, Path absolutePath)`:

- `relPath` — path relative to the directory it was scanned from, `/`-separated, sorted, no leading
  `./` or `/`. This is the identity used for selections, results, reports, and for talking to rsync.
- `absolutePath` — where it actually is, used only for I/O.

`FileSyncEngine.normalize` applies that form to anything a caller supplies, so a GUI row, a CLI
`--files ./a.txt`, and a scanned file all agree. The original implementation compared and truncated
absolute path strings, which is how it produced selections that did not match the files it then copied.

**A relative path is never derived by string arithmetic on an absolute path.**

## 2. Selection and work set

`Selection(Set selected, Set known)` describes one list:

- `selected` — rows the user ticked.
- `known` — rows the list actually contains, ticked or not.

The pair matters because exclusion has to mean *"the user saw this row and left it unticked"*, which is
not the same as *"the list has never heard of this file"*.

The work set is then:

| Mode | Work set |
|---|---|
| Copy & Verify | source rows that are ticked |
| Verify Only | ticked source rows, minus rows that are **known** in the destination list and unticked there; plus destination-only rows when reporting extras is on |

Two consequences that are deliberate:

- **An empty selection is zero files, never all files.** A job with nothing ticked is refused. The
  original treated an empty selection as "everything", which made the safest-looking action the most
  destructive one.
- **A file absent from the destination has no destination row**, so it can never be unticked there. It
  stays in the work set and is reported `MISSING`. Before `known` existed, a destination list that had
  not been refreshed silently excluded freshly copied files from verification.

A ticked path with no scanned file behind it (deleted after the scan, typo in a CLI list) is not a
selection and not a file: it is reported as a warning naming it, and if it is the whole selection the
job fails instead of reporting success on an empty work set.

## 3. Hashing

SHA-256 over file contents, streamed, on a bounded daemon pool
(`min(MAX_HASH_THREADS, availableProcessors)`), with the cancel flag and the deadline checked before
each unit of work.

- Only the work set is hashed, in both modes.
- Copy & Verify hashes the source **before** `rsync` runs and reuses those digests for the comparison
  afterwards: one hash per selected file per side.
- Symlinks: `SKIP` leaves them out of the scan; `LINK` copies them as links (`rsync -a`) and hashes
  follow the target on both sides; `FOLLOW` hands over the target's contents (`rsync --copy-links`).
  Hashing a link target while copying the link as a link would compare unlike things, so the policy is
  explicit and applies to both sides of one job.

## 4. rsync, and how its output is read

One command, always, for a copy job:

```
rsync -a --checksum --itemize-changes --info=progress2 --files-from=<temp file> <source>/ <dest>/
```

- The selection is written to a temporary UTF-8 file and deleted in a `finally`. `--files-from` implies
  `--relative` and `--dirs` in rsync 3.x, so subdirectory structure is recreated without extra flags
  (verified, not assumed). Names containing a newline or NUL are refused: they would corrupt the list.
- Output lines are classified by rsync's itemize grammar — **update type** then **item type**:
  `>` received, `<` sent, `c` local create/change, `h` hard link, `.` not updated, `*` message;
  item types `f` file, `d` directory, `L` symlink, `D` device, `S` special file. So a local file copy
  is `>f`, a created directory is `cd`, a created symlink is `cL`, and an untouched file is `.f`.
  Errors are `rsync error:` / `rsync:` lines; `*` message lines are surfaced as warnings.
- Exit codes: 0 and 24 are success (24 becomes a warning: "some files vanished"); anything else fails
  the job with rsync's own error lines attached.
- **Announcements are not transfers.** rsync prints the itemize line for a file before its data has
  moved, so `copied` counts announced items (exact for a completed job, potentially overstated for a
  cancelled one — documented in `JobResult`), and progress follows the `--info=progress2` percentage
  instead.

## 5. Counters and progress

```
checked = matched + mismatched + missing        (destination-only files counted separately)
```

Progress is a percentage of the work set: copy phase 0–70%, verification 70–100%, with updates
coalesced to whole percentage changes so a 100k-file job does not spam the UI or the log.

## 6. Job lifecycle

`FileSyncEngine` is an instance and `AutoCloseable` and owns at most one job, guarded by an
`AtomicBoolean` CAS:

- `start(...)` submits asynchronously and returns `false` if the engine is busy.
- `execute(...)` is the synchronous equivalent and throws if the engine is busy.
- `cancel()` sets the flag, kills the live `rsync` process and `process.descendants()`, and the loops
  stop at the next unit of work. A cancelled job ends `FAILED` with the counters it reached and a note
  that rsync may leave a hidden temporary file behind.
- A watchdog (250 ms period) enforces an optional deadline; a timed-out job fails with a timeout
  message distinct from cancellation. The deadline is checked per work unit, not just at loop tops — a
  1 s timeout that only fired between files did not stop a job whose hashing overran it.
- `close()` refuses further jobs. Status is `IDLE → RUNNING → COMPLETED/FAILED`; the GUI's "still
  running, last activity Ns ago" line is driven by a Swing timer, not by the rsync reader.

## 7. Two front ends, one engine

`FileSyncEngine` has no Swing dependency. `FileVerifierFrame` (GUI) and `Cli` (headless, `--cli`) are
both thin adapters over `JobRequest` + `ProgressListener`, so the semantics are implemented once. The
CLI maps the outcome onto exit codes: `0` nothing to report, `1` job failed (preflight, rsync error),
`2` bad command line, `3` files mismatched or missing. `--all` expands to an explicit selection by
scanning first — the CLI does not get a weaker selection rule than the GUI.

## 8. Scanning

`Files.walkFileTree` with `ScanOptions(ignorePatterns, includeHidden, symlinkPolicy)`:

- Ignore patterns are globs matched against the relative path and the file name; the default list covers
  VCS and OS metadata (`.git`, `.svn`, `.hg`, `.bzr`, `lost+found`, `.Trash`, `$RECYCLE.BIN`).
- `FOLLOW` uses `FileVisitOption.FOLLOW_LINKS` and reports a symlink cycle as a warning instead of
  recursing; `SKIP`/`LINK` never descend through a link.
- An unreadable subdirectory is a warning (`visitFileFailed` fires for a directory that can be traversed
  but not listed), not an abort. Non-regular files (sockets, devices) are skipped.
- Results are sorted by relative path; the scan reports how many entries were ignored and how many links
  were skipped.

## 9. Bounded UI

The log keeps its newest 5000 lines (`TailLog`, which tracks every line's length so trimming removes
whole lines and batches the removals). Outcomes are capped at `MAX_OUTCOMES = 50_000`; beyond that the
counters remain exact and the report is truncated with a warning. Lists hold a flat row model with a
filtered view for the filter boxes.

## 10. Known limits

- `copied` is rsync's announcement count, so a cancelled job can overstate completed transfers.
- A cancelled or timed-out `rsync` can leave a hidden `.~rsyncXXXX` temporary file in the destination.
- The GUI does not show per-file transfer progress within one large file beyond the global percentage.
- Older rsync 3.1.x renders itemize paths differently; behaviour here is verified against 3.2.7.
- The per-side selection rule is modelled (`Selection.selected`/`known`) but the GUI has no separate
  per-side selection mode yet.
