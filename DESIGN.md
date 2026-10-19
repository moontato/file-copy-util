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
rsync -a --checksum --itemize-changes --info=progress2 [--dry-run] [--bwlimit=N] [--copy-links] \
      --files-from=<temp file> <source>/ <dest>/
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
  moved. `--info=progress2` carries rsync's own counter of transfers it has *finished* — the `xfr#N`
  field — so a job that is cut short reports `min(announced, rsync's completed count)` and says so,
  while a completed job reports the itemize count (which includes locally created links and devices,
  something `xfr#` never counts). Progress follows the percentage, never the announcements; a
  `--dry-run` announces a meaningless 100% instantly, so its percentage is ignored.

## 5. Counters, progress and memory

Verification decides one file at a time: hash whatever still needs hashing, compare, report, and let the
digests go. It is deliberately not "hash every source file into a map, hash every destination file into a
second map, then walk the maps" — that shape holds memory the comparison never asks for again, because
only a mismatch needs its digests kept afterwards. Verdicts are consumed in work-set order, so the results
list and any export stay deterministic even though hashing runs on a bounded pool.

The destination is scanned only when destination-only reporting is asked for; a Verify Only job that does
not want `EXTRA` rows does not walk the destination at all. The results list is bounded (`setResultLimit`,
CLI `--max-results`); counters stay exact whatever it is, and a truncated list says so in the warnings.



```
checked = matched + mismatched + missing        (destination-only files counted separately)
```

Progress is a percentage of the work set: copy phase 0–70%, verification 70–100%, with updates
coalesced to whole percentage changes so a 100k-file job does not spam the UI or the log.

### Interrupted transfers

rsync writes a file into a hidden temporary name (`.big.bin.QafPvi`) and renames it into place only when
the transfer completes. Measured on rsync 3.2.7: a gracefully SIGTERM'd rsync usually removes that file
and exits 20; a killed process or killed child does not, and the partial stays. So the engine does not
assume either way — after a cancelled or timed-out copy it lists the directories this job would have
written to and reports the partials whose names match `.name.XXXXXX` for a file in the work set, with
sizes:

```
1 partial rsync file(s) left in the destination: .big.bin.QafPvi (for big.bin, 3.8 MB). They are
incomplete transfers - delete them or re-run the copy to finish them.
```

or `No partial rsync file was left in the destination.` when there is nothing to report. The engine
never deletes them: deciding that a hidden file in someone's directory is junk is the user's call.

### Dry run

`dryRun` on a `JobRequest` adds `--dry-run` to the rsync command and keeps everything else — same work
set, same hashing, same comparison — so a preview reports exactly what a real run would. Its `copied`
count means "would be copied", its message starts `Dry run - nothing was written:`, and it carries a
warning saying no file was written. A dry run is not a separate mode of thinking for the engine: it is
the same job with one flag.

## 6. Job lifecycle

`FileSyncEngine` is an instance and `AutoCloseable` and owns at most one job, guarded by an
`AtomicBoolean` CAS:

- `start(...)` submits asynchronously and returns `false` if the engine is busy.
- `execute(...)` is the synchronous equivalent and throws if the engine is busy.
- `cancel()` sets the flag, kills the live `rsync` process and `process.descendants()`, and the loops
  stop at the next unit of work. A cancelled job ends `FAILED` with the counters it reached and a report
  of the partial files the interrupted `rsync` actually left behind.
- An optional deadline is enforced from two sides. Every work loop calls `abortRequested()`, which records
  the timeout the moment the deadline passes and kills a live `rsync` from any thread; a watchdog (250 ms
  period) covers the places a loop cannot check itself — a blocked `process.waitFor()`, or one huge file
  being hashed. Recording the timeout at the check matters for the *result*, not just for stopping the
  work: a phase that ran past the deadline used to fall through and report the job `COMPLETED`, and a
  half-finished verification reported files it never hashed as `missing`. A timed-out job fails with a
  message distinct from cancellation.
- `close()` refuses further jobs. Status is `IDLE → RUNNING → COMPLETED/FAILED`; the GUI's "still
  running, last activity Ns ago" line is driven by a Swing timer, not by the rsync reader.

## 7. Two front ends, one engine

`FileSyncEngine` has no Swing dependency. `FileVerifierFrame` (GUI) and `Cli` (headless, `--cli`) are
both thin adapters over `JobRequest` + `ProgressListener`, so the semantics are implemented once. The
CLI maps the outcome onto exit codes: `0` nothing to report, `1` job failed (preflight, rsync error),
`2` bad command line, `3` files mismatched or missing (for `--dry-run`: would be mismatched or missing).
`--all` expands to an explicit selection by scanning first — the CLI does not get a weaker selection
rule than the GUI. `--dry-run` and `--bwlimit` map onto the same `JobRequest.dryRun` /
`setBandwidthLimitKbps` the GUI uses.

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

- A selection large enough to exhaust the heap fails the job with that advice; the tool does not spill to
  disk or stream the report. Measured ceiling on this build: 300k files needs ~256 MB for Verify Only and
  ~320 MB for a copy.
- A results list beyond the limit is truncated, so an export taken from it lists only what was kept. The
  warning names the number; raising `--max-results` is the user's call.
- On a cut-short job, `copied` is capped by rsync's completed-transfer count; if rsync never reported
  one, the announcement count is kept and the wording says "announced". A cap can understate locally
  created links (`xfr#` does not count them).
- Interrupted transfers are reported, not cleaned up: partial files stay in the destination until the
  user removes them or re-runs the copy (a re-run starts a fresh temporary file, so leftovers from
  several interrupted runs can accumulate — each is reported).
- The GUI does not show per-file transfer progress within one large file beyond the global percentage.
- Older rsync 3.1.x renders itemize paths differently; behaviour here is verified against 3.2.7.
- The per-side selection rule is modelled (`Selection.selected`/`known`) but the GUI has no separate
  per-side selection mode yet.
