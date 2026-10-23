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
- Symlinks are judged as the objects they are, and one policy applies to both sides of one job.
  `SKIP` leaves them out of the scan entirely. `LINK` carries the link as an object — a link to a directory
  included (listed, never descended through) and a dangling link included (an object with no target) — copies
  it as a link, and compares **kind first, then link text** via `readLink`, taking **no digest of anything**:
  a link's target is not the link. A link that stands in the destination as a regular file, or a regular file
  whose destination is a link, is a `MISMATCH` **in kind**, with a warning naming which object became which.
  `FOLLOW` hands over the target's contents (`rsync --copy-links`) and compares bytes, and says so in the
  warnings: that verdict is about the contents at the end of a link, and a link pointing outside the two
  directories takes the read outside them.

## 4. rsync, and how its output is read

**A file's name is its identity, and it has to survive the round trip.** The list handed to rsync is
NUL-delimited (`--from0`) with every entry prefixed `./`. Both halves matter: a name containing a newline or
a space needs the NUL delimiter, and in a file list an entry starting with `#` or `;` is a comment - which
`--from0` does not change - so `#secret` would simply vanish from a job that reported success. Selection
lists are not trimmed either: a POSIX filename may begin or end with a space, and a trimmed `" a "` is the
identity of a different file, quite possibly the one the user unticked.

Mention lines are requested as `--out-format='%i|%n'`: the name last, after a fixed separator, split at the
first separator only. rsync escapes a byte it cannot print as octal `\#ooo`; decoding replaces only that
form, reassembles the bytes and decodes them to text once, because a multi-byte character may arrive as a
run of escapes. A backslash not followed by `#ooo` is part of the name - a filename containing `\134` is
printed that way and must not be turned into a backslash. Names are escaped only where a person reads them
(log line, table cell, CLI line); exports carry them raw, because an export is a record.

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
  field — so a job that is cut short reports what rsync proved had finished, while a completed job
  reports the itemize count (which includes locally created links and devices, something `xfr#` never
  counts). Progress follows the percentage, never the announcements; a `--dry-run` announces a
  meaningless 100% instantly, so its percentage is ignored, and a dry run's count is exempt from the
  cap because it is intent rather than completed transfers.
- **What absence of `xfr#` means** was measured on rsync 3.1.3, 3.2.7, 3.3.0 and 3.4.0 rather than
  assumed: the `xfr#N` field appears only in the progress fragment printed when a transfer finishes, and
  the fragments of a transfer still in flight carry none. So progress fragments with no `xfr#` mean
  nothing had finished yet — not that rsync stayed quiet. Three cases are reported differently: a count
  rsync reported ("announced 3 and completed 1 of them"), progress but no completed transfer ("announced 1
  and completed none of them"), and no progress at all ("how many finished is unknown", with the
  announcement count kept because nothing rules it out).
- **Progress fragments are separated by carriage returns, not newlines**, because rsync rewrites one
  status line. Reading rsync's output line-by-line works only because the reader treats `\r` as a line
  end; a byte-stream reader would see one enormous line and no percentages.
- **The `--files-from` list is sorted, not preserved**: a 4 MB `zbig.bin` is transferred before `a1.txt`
  if the file name says so. It changes no counter, but it is why a throttled job can appear to have
  ignored the small files listed first.

## 5. Two lists, one rule, and the line that explains them

The window has a source list and a destination list, and they mean different things in different modes: a
copy follows the source ticks and ignores the destination entirely, while Verify Only follows the source
ticks minus the destination rows the user was actually shown and left unticked. That is subtle enough to
get wrong silently, so it is stated on screen: each list says how many rows it has and how many are ticked,
and a line under the mode buttons says how many files the selected operation would process, which rule
produced that number, and - when it is a handful - which paths were excluded. A third "effective" list was
considered and rejected: it would be another thing to keep in sync, and it would show a derived fact while
hiding the rule that derived it.

The rule lives in one place, `FileSyncEngine.excludedAtDestination`, and both the job and the preview call
it, so the preview cannot promise a work set the job then ignores. Because the preview is recomputed on
every click, the engine also has an equivalent fast form: given the number of ticked source rows and the
set of paths unticked at the destination, it counts exclusions by asking "is this a ticked source row?"
through the window's path index. Cost is then proportional to how many rows the user unticked, not to how
many exist - a single click in a 100000 row list costs nothing instead of a quarter of a second. The two
forms are tested against each other over several tick patterns, which is what makes an equivalent
implementation acceptable instead of a second rule.

## 6. Counters, progress and memory

Verification decides one file at a time: hash whatever still needs hashing, compare, report, and let the
digests go. It is deliberately not "hash every source file into a map, hash every destination file into a
second map, then walk the maps" — that shape holds memory the comparison never asks for again, because
only a mismatch needs its digests kept afterwards. Verdicts are consumed in work-set order, so the results
list and any export stay deterministic even though hashing runs on a bounded pool.

The destination is scanned only when destination-only reporting is asked for; a Verify Only job that does
not want `EXTRA` rows does not walk the destination at all. The results list is bounded (`setResultLimit`,
CLI `--max-results`); counters stay exact whatever it is, and the report states how many rows it left out —
`rowsOmitted`, with `truncated` derived from it, in both exports, in the window's tab title and in a CLI note.



```
checked = matched + mismatched + missing        (destination-only files counted separately)
```

Progress is a percentage of the work set: copy phase 0–70%, verification 70–100%, with updates
coalesced to whole percentage changes so a 100k-file job does not spam the UI or the log.

### Interrupted transfers: what may be called copied

rsync announces a file when it **starts** the transfer, so an announcement proves intent, not completion.
The two things that can prove completion are the job running to a clean end, and the destination compared
against what it held before rsync started. So the engine records each work-set name's presence, kind, size
and `(dev, ino)` identity before the copy, and afterwards calls a transfer:

- `COPIED` — the job ended cleanly; or the name was absent before and is present afterwards at the expected
  kind and size; or it was present and its identity changed (rsync writes a temporary and renames it, so a
  completed replacement is a new inode).
- `WOULD_COPY` — a dry run wrote nothing.
  The expected kind and size come from the source read **before** the transfer: a source that changed
  mid-job must not be matched against its own new size. A symlink is compared by its target text, never by
  a size or by opening it.
  Where the object already standing in the destination is not the kind the source has — a link where a file
  belongs, or a file where a link belongs — the job says so **before** the transfer, because a copy erases that
  evidence: afterwards the destination shows what the job wrote, and nothing there reports what it replaced.
- `UNCERTAIN` — rsync started the file and the destination cannot show what happened. A change of object
  kind alone is not accepted as evidence: without a changed file identity the claim is dropped. The case this exists
  for is an interrupted overwrite of a file that was already the same size as the source: the destination
  then holds the old bytes, the old inode, the same size and matching permissions, and every "looks right"
  check passes. Size, containment and object kind are supporting observations, never proof.

The record is one entry per selected file at both ends, taken before any byte moves - see the measured
cost in README. Its snapshot loop is cancellable, because on a network mount it can be the longest thing a
job does before rsync starts, and a Cancel that lands during it must stop the job. A snapshot cut short is
reported: the files it never reached are named as unconfirmable.

Metadata tuples, mtimes and content samples are diagnostics: they may appear in a warning, and they can
never turn an `UNCERTAIN` into a `COPIED`. The attribution assumes nothing else was writing to that
destination — see the single-writer note in section 7.

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

## 7. Job lifecycle

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

## 8. Two front ends, one engine

`FileSyncEngine` has no Swing dependency. `FileVerifierFrame` (GUI) and `Cli` (headless, `--cli`) are
both thin adapters over `JobRequest` + `ProgressListener`, so the semantics are implemented once. The
CLI maps the outcome onto exit codes: `0` nothing to report, `1` job failed (preflight, rsync error),
`2` bad command line, `3` files mismatched or missing (for `--dry-run`: would be mismatched or missing).
`--all` expands to an explicit selection by scanning first — the CLI does not get a weaker selection
rule than the GUI. `--dry-run` and `--bwlimit` map onto the same `JobRequest.dryRun` /
`setBandwidthLimitKbps` the GUI uses.

## 9. Scanning

`Files.walkFileTree` with `ScanOptions(ignorePatterns, includeHidden, symlinkPolicy)`:

- Ignore patterns are globs matched against the relative path and the file name; the default list covers
  VCS and OS metadata (`.git`, `.svn`, `.hg`, `.bzr`, `lost+found`, `.Trash`, `$RECYCLE.BIN`).
- `FOLLOW` uses `FileVisitOption.FOLLOW_LINKS` and reports a symlink cycle as a warning instead of
  recursing; `SKIP`/`LINK` never descend through a link.
- An unreadable subdirectory is a warning (`visitFileFailed` fires for a directory that can be traversed
  but not listed), not an abort. Non-regular files (sockets, devices) are skipped.
- Results are sorted by relative path; the scan reports how many entries were ignored and how many links
  were skipped.

## 10. Bounded UI

The log keeps its newest 5000 lines (`TailLog`, which tracks every line's length so trimming removes
whole lines and batches the removals). Outcomes are capped at `MAX_OUTCOMES = 50_000`; beyond that the
counters remain exact and the report is truncated with a warning. Lists hold a flat row model with a
filtered view for the filter boxes.

## 11. Known limits

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
- A 300000 row list in the window costs about 710 MB of resident memory (573 MB right after the scan, the
  rest being the preview index and the table). It stays responsive - a click costs nothing, Select All
  3 ms, filter 26 ms - but it is not a low-memory design.
- The fast work-set preview is proportional to how many destination rows are unticked. If half of a
  300000 row destination is unticked, the preview costs a few hundred milliseconds per click.
- rsync output is verified against 3.1.3, 3.2.7, 3.3.0 and 3.4.0 (`RsyncVersionMatrixTest`: identical
  counters for the same tree, dry runs that write nothing, interrupted jobs that claim only finished
  transfers). A version outside that range is unverified; the matrix takes extra binaries through
  `FCU_RSYNC_BINARIES`, so a new release can be added without touching the tests. `FCU_RSYNC` pins the one
  binary the process uses; a test run with `FCU_RSYNC` naming an unusable binary refuses to run rather than
  skipping every rsync-dependent test, which is what a version matrix must never do silently.
- The per-side selection rule is modelled (`Selection.selected`/`known`) and stated in the window (each
  list reports how many of its rows are ticked, and the work-set line names the rule that produced the
  count). There is deliberately no third "effective files" list: a third thing to keep in sync was the
  failure mode being avoided (roadmap 5.6).
- The interface is verified structurally and offscreen. CI has no display server, so the UI checks assert
  structure (controls exist, modes are exclusive, the table has a header, no colour literals escape the
  theme) and paint the panel to an image. Whether the result *looks* right on a real desktop is still a
  human judgement.

## 12. The interface layer

### 12.1 A panel, not a window

`FileVerifierView` (a `JPanel`) holds every control, the models, the log buffer and the engine reference.
`FileVerifierFrame` is a `JFrame` that sizes a window, titles it, hosts the view, and tells it when the
window is closing.

The split exists for one reason: **a `JFrame` cannot be constructed when the JVM is headless**, so a UI
built inside one can only be reviewed by hand. As a panel the whole interface can be constructed, driven
(buttons clicked, rows clicked, jobs run against real `rsync`), and painted to a `BufferedImage` with no
display server - which is what `FileVerifierViewTest` and the CI job do.

### 12.2 One place for colour

`UiTheme` is the only file in the program that contains a `new Color(...)`; a test walks every other
source file and fails if one appears. It holds two complete palettes (light and dark) with the same
tokens, so dark mode is a palette swap rather than a second design, and the tokens are values a test can
check:

- every text/badge pairing must clear **4.5:1** against the surface it sits on (WCAG 2.1 body-text bar),
  asserted for both palettes - which is why the greens and ambers are darker than the fashionable ones;
- `outcomeColor(kind)` maps an outcome to a colour once, so the log, the table and any future view agree;
- typography is three weights (caption 12, body 13, heading 15) plus a monospaced face for digests and
  the log.

### 12.3 FlatLaf, and what it may not do

FlatLaf 3.7.2 (Apache-2.0, bundled in the runnable jar; see `NOTICE`) supplies the widget shapes: rounded
corners, focus rings, alternating table rows, tab styling, segmented buttons. `UiTheme.install()` sets a
short list of FlatLaf style keys after `setup()`, because installing a look-and-feel replaces the
defaults `UIManager` held before.

Theme changes are applied live with `FlatLaf.updateUI()` plus `UiTheme.restyle(root)`: colours chosen at
construction time would otherwise survive the switch, which is how "dark mode support" usually ends up
half working. Every widget `UiTheme` creates is tagged with a client property, and `restyle` walks for
those tags.

The theme is presentation only. The engine has no dependency on it, `--cli` never loads it, and the thin
jar (`original-*.jar`) runs the same program in the platform look and feel.

## 13. Failure honesty

A tool that copies files is trusted only if what it reports survives its bad days. These rules came out of
attacking the program rather than writing the tests it seemed to need, and each has a regression test.

**A cut-short copy is a partial copy, not nothing.** Every way a job can stop early — cancel, timeout, an
rsync error, an out-of-memory guard — reports what rsync announced, how many transfers rsync actually
completed (`xfr#`), and whether hidden half-written files were left behind. Until 1.2.1 the plain rsync-error
path returned zeroed counters, so a job that had already written files reported none.

**Names that cannot be handed to rsync are refused, not mangled.** `--files-from` is a line list. A newline,
carriage return or NUL in a name is refused before the transfer, named in the warnings, and counted as a file
that was not transferred. An error message that says only "rsync exit 23" is not a diagnosis.

**Nothing outside the destination is read.** A destination side is reached by resolving a relative path, so a
link can appear during verification under any policy. It is judged as the link it is — kind, then text — and is
never followed under any policy, including `FOLLOW`: its target may sit outside the destination, and a digest of
a file belonging to neither directory is not the destination's verdict. A copy replaces a link at that path
(measured), which is why `FOLLOW` follows the *source* link and still refuses to follow a destination one.

**An unreadable locale is a warning with a fix.** File-name encoding comes from the locale, so under `LANG=C`
non-ASCII names are unrepresentable. The scan skips those entries and says which encoding was in force and
what to set (`LC_ALL=C.UTF-8`), rather than dying with `InvalidPathException` or writing a guessed name.

**Names that look like syntax are ordinary names.** `-dash`, `$name`, `back\slash`, `semi;colon`, `quote'`,
`[bracket]`, `a,b`, tabs and trailing spaces all survive a copy and a verify, because the file list is a file,
not a shell command line.

Two more rules from the same audit.

**A name collision is named before anything is written.** `A.txt` and `a.txt`, or `café` composed and decomposed,
are two files on a case-sensitive filesystem and one file on a case-insensitive or normalising destination. The
work set is checked (Unicode NFC, then case-folded, over the whole relative path — the directory part is part of
the name) and the colliding pairs are reported with their consequence. Refusing would be wrong: on a normal
destination both are legitimate files. Silently overwriting one and then reporting a mismatch would be worse.

**A verdict and an explanation are different things.** A mismatch stays a mismatch: the destination really does
not match the digest taken before the copy. But the source is re-hashed once, and if it moved during the job the
warning says so. Same verdict, different story — and only where a before-and-after actually exists.

**Engine state belongs to the engine.** Which rsync a job runs was a process-wide static; now it is per engine,
with the static kept as a default. Which destination is busy was not tracked at all; it is now, per program, so a
second job on the same directory fails fast instead of interleaving. Cross-process concurrency remains unlocked
on purpose: a lock file in someone's destination tree is a decision for the owner of that tree. That choice
is also an assumption: the before/after attribution of a transfer ("this job replaced that file") holds only
while nothing else writes to the destination.

**A typed path is the program's own path parsing, and it is allowed to be wrong out loud.** The directory fields
are editable: `~` is home, a relative path is resolved from the working directory, surrounding spaces are
stripped. What is not allowed is keeping the file lists that were read from the directory the field used to name —
those rows are ticks against paths in a place that is no longer in play — so a changed field clears them and says
so in the log. A path the system's file-name encoding cannot spell gets a dialog naming the encoding, not an
exception.

**A version the interface shows must come from the build.** The corner label reads a resource the build stamps
with the project version. A hardcoded version string in the UI is a number that drifts out of sync with the jar
name, the tag, and the changelog, and then it is worse than nothing because it is trusted. A build that stamps
nothing says `version unknown`.

**A mode is for what a job does to your files; a switch is for how far it goes.** *Copy* and *Verify only* are
one two-way control because they are different acts - one writes into your destination, one does not - and no
checkbox attached to a copy job can produce "look without touching". Verification is a switch on the copy job,
on by default, because comparing is the reason this tool exists; turning it off is the user's decision, not the
program's. What is not allowed is a job that skipped a check and does not say so: the button label, the status
line, the log, the job's own summary, the CLI totals and the export all carry that fact.

**A result row states one fact about one file.** A verifying job writes the verdict, and not also a row for the
transfer. A job that compares nothing writes `COPIED` - or `WOULD_COPY` in a dry run, because nothing was
written - because the transfer is the only fact it has, and a job that wrote 214 files must not leave an empty
table that reads as "nothing happened". `COPIED` is not coloured like a check that passed, and it carries no
hashes because none were computed.

**A job that will not compare does not read the files.** Hashing is the expensive part of a large job, and a
digest nobody is going to use is not preparation, it is cost. This is observable rather than assumed: an
unreadable source file produces a "Could not read" warning in a verifying job and no such warning in a copy-only
one, because the copy-only job never opened it.

**Cancelling means the whole process tree, not the process we spawned.** A local `rsync` copy is three
processes (measured: parent, child, grandchild). The tree is snapshotted before anyone is signalled, because
after the parent dies its children are re-parented to init and a `descendants()` call then finds nothing at
all. SIGTERM goes to everyone first - rsync answers it by deleting the partial file it was writing, so a
cancelled job leaves nothing in the destination - and only survivors are killed outright. A *successful* copy
sweeps the tree too: rsync's parent can exit while a child is still finishing, and an orphaned child is
something this program can never reach again. A job that has been told to stop never starts rsync, and a
cancellation landing between that check and the fork kills the process immediately: a reader left on the
source disk with nothing watching it is the exact failure a person experiences as "I cancelled it, the window
closed, and the drive is still going".

**A phase is named for the work being done.** Hashing 30 GB of source before rsync writes a single byte is not
copying, and calling it copying - with an empty destination folder - is what makes a job look stalled. The
phases are `hashing source`, `copying`, `verifying`.

**Progress names the file in flight.** One overall percentage is not information during a 30 GB transfer: it
moves too slowly to tell you the program is alive. The status names the file being written, how much of it has
landed, how many files are finished, the rate and the elapsed time, derived from rsync's own stats lines and
announcements - measured on rsync 3.1.3 and 3.4.0, an itemize line arrives when a file's transfer *starts* and
the `xfr#` count is what says a file *finished*. Where the program cannot know something it says less, not
more: a stats line this rsync variant formats differently moves the bar and drops the per-file figures rather
than guessing.

## 14. Where a job may write, and where it may not

A verifier that damages the thing it is checking is worse than one that fails, so the write side is treated
as a set of prohibitions rather than a set of options. Each one below is enforced before anything is written.

**A copy may not overlap what it is copying - in either direction, and judged against resolved paths.** Three
cases, all refused in preflight for any job that writes: the destination inside the source, the source inside the destination, and
the two being the same directory. Resolution matters: a destination that is a symbolic link to the source, or
a source given as `backup/mirror` where `mirror` links to the data, look like two directories and are one.
Containment is therefore judged against the directory a path *lands on* and against the path as given, and
the refusal names which relationship was found. Why it is refused rather than warned: the destination ends up
holding a copy of the source, so the job cannot say which copy is the original, and a run stopped half way
leaves the source half overwritten by its own copy. There is no override - "copy a directory into itself" is
not a thing this program does. Verify Only is <i>not</i> refused for overlap: it writes nothing, and refusing a
read-only comparison of a directory with itself would be this program policing a shape that harms nothing.

**Selected paths stay inside their tree, ancestors included.** A name carrying `..` is refused; so is a name
that spells itself as contained and walks out through a linked directory (`dest/sub` being a link is enough).
Containment resolves the deepest existing ancestor and re-attaches the rest, because a destination file the
copy has not created yet must still be judged. It applies to Verify Only too, and that is not a technicality:
the audit's escape was a Verify Only job comparing `dst/sub/a.txt` where `dst/sub` linked outside, and the
digest it offered as "the backup copy" belonged to a file outside the backup. A read fabricates a verdict just
as surely as a write damages a file.

**The final component of a name is never resolved - measured, not convenient.** A symbolic link at the end of a
selected path is an object, and the job treats it as one: rsync replaces a destination link with the file it is
copying (measured - the link's target kept its content), and this program reports a link it finds at a
destination rather than opening it. Resolving the leaf would refuse lawful jobs: a source link copied as a link
whose target lives elsewhere, and a second copy into a destination link that the first copy of that same link
created. Under `--links` such a link is compared by its target text, never traversed.

The limit is part of the rule: this is a check made before the copy, not a lock held across each write, and no
cross-process lock is taken - a directory re-pointed by another process in between is a race this program
does not close.

**A report may not displace the files it describes.** Two decisions, kept apart:

- *Protected data is never overwritten, and no option waives that.* A report path that already exists is
  refused - whether it is a source file, a destination file, a directory or a symbolic link. The program
  cannot tell a report it wrote earlier from a file the job protects, so it does not guess; a report path has
  to be a path that does not exist yet. The old behaviour (write anyway, truncating whatever was there) is
  the bug, not the convenience.
- *A report inside the job's own trees is the user's call.* A new file there displaces nothing, but it lands
  among the files being copied and verified, so the next scan reports it as an extra. `--allow-report-in-tree`
  or a confirmation in the window permits it, and permits nothing else: an existing file there is still
  refused.

**How a report is written, and why it is not a rename.** The content is staged by `Files.createTempFile` in
**the report's own directory** - a hard link cannot cross a filesystem, so staging it anywhere else would break
the publication - and only a file this call created is ever deleted: another writer's temporary file is never
this program's to clean up. Publication is `Files.createLink(report, staged)`. Creating a link cannot replace an
existing name (it fails with `FileAlreadyExistsException`), and the name it does create points at a file that is
already complete, so the report is never visible half-written.

Two costs of that choice are accepted out loud. If the process is killed between staging and publishing, one
`.fcu-report-*.tmp` is left in that directory. And on a filesystem with no hard links - FAT, exFAT, some network
and FUSE mounts - **the report is refused**: the guarantee cannot be provided there, and the rule of this program
is to say so rather than quietly substitute a weaker one. The fallback that was written first (create the name
exclusively, then copy the content into it) was removed for two reasons: by the time the copy ran, the name could
belong to somebody else, and a copy with replacement would overwrite their file through a path this program had
just created; and the report name existed, empty or half-written, while the copy was in progress.

`ATOMIC_MOVE` was the first implementation and is deliberately gone, because it was measured rather than assumed.
OpenJDK 17's `UnixCopyFile.move` (java.base/sun/nio/fs/UnixCopyFile.java) takes the atomic branch first - line
406 `if (flags.atomicMove)`, line 408 `rename(source, target)`, then `return` - so the no-replacement check at line
448 (`throw new FileAlreadyExistsException`) sits in the *non*-atomic path and is never reached. Measured on this
machine (OpenJDK 17.0.20.1+1, `sun.nio.fs.LinuxFileSystemProvider`): `Files.move(temp, existing, ATOMIC_MOVE)`
against an existing target **succeeded** and the target held the temporary file's bytes. A no-clobber guarantee
cannot be built on a rename; it has to be built on an operation that cannot replace, which is what link-and-create
does.

**Two spellings of one report path are one target.** The pre-flight check for `--csv` and `--json` naming the
same file cannot compare strings. With `reports/alias` a link to `reports/real`, the paths
`reports/real/result` and `reports/alias/result` are two spellings of one file, and each passes the "does this
name exist yet" test on its own while the target is absent - so the transfer runs, one report is written, and the
second is refused afterwards. The comparison is made on the resolved directory plus the name, which is also
where the report will actually be written.

**When a report problem shows up decides the exit code.** Report paths are validated before the job starts, so
an existing target, a missing parent directory, `--csv` and `--json` naming one path, or an in-tree report that
was not allowed are usage errors (**2**) and nothing is copied - the old order let a job run, write one report,
then refuse the other, which is a transfer with half its record. A target that only becomes taken *during* the
run is caught at publication, and that run exits **4**: the copies happened and the record of them did not. A
missing parent directory is reported, not created.

**Colliding names need an answer before a copy.** Two selected names that differ only by case, or only by how
an accent is composed, are two files here and one file on a case-insensitive or normalising destination -
copying both means one overwrites the other. Verification writes nothing, so it warns and carries on; a copy
refuses, writes nothing, and says how to accept it (`--allow-collision-names`, or *Copy them anyway?* in the
window). The window's answer is recorded as *those names*: a later job that finds a different pair is refused
and asked again, so one dialog is not a licence for the rest of the session; the command line's flag is openly
wider, because it was typed for this run. Accepting is the caller saying they know and accept the overwrite; it
is not a claim that the copy is safe, and the warning stays. A dry run reports the pairs without refusing, because a dry run writes
nothing to overwrite.

## 15. Which scan the window is answering for

A copy is a write, so the set of files it writes has to be a fact the user can point at — not something assembled
from whatever the tables show when a background thread gets around to asking.

**A scan is identified.** `LoadedScan` records the scan number, the two directories, the `ScanOptions` it was read
with, and its row counts. The ticked and unticked rows belong to that scan, not to the window in general.

**Run is refused when the window is not answering for anything.** `runRefusal()` returns the reason;
`runRequested()` writes it to the log as well as showing it — a refusal that only existed in a dialog is forgotten
the moment the dialog is closed, and the log is the part that can be tested without a display.

| Situation | Why a job would not mean what the user sees |
|---|---|
| no scan yet | an empty selection means no files; guessing "everything" is the opposite bug |
| scan in flight | the lists are half-filled, so a job captured now follows part of a directory |
| a path field edited | the rows belonged to the directory that used to be named in the field |
| scan options edited | hidden files, ignored directories and link policy decide what is in the list |

**One request, captured once.** Run builds a single immutable `JobRequest`: roots taken from the *scan* rather
than from the fields, plus the ticks as they are at that instant. Everything after that — engine call, progress
poller, collision answer — uses that object, and the collision re-run starts the same object, so "the job that ran
is the job that was asked about" is a fact rather than a hope. Ticking rows during a job changes what the *next*
job would do.

**A finished job keeps its request.** `finishJob(request, result)` pairs the two into `CompletedJob`. The request
is named by the completion callback that produced the result, so there is no "current request" field that a later
job — including one the engine refused before starting anything — could overwrite. The Results table and the
report export read the pair:

- export protection ("never over an existing file", "not among the files this job copied unless somebody says
  so") is judged by **the directories that job worked in**. `ReportExporter` is given `completedJob.request()`'s
  roots, never `userPath(sourcePathField.getText())`: by the time somebody presses Export, the fields may name
  another pair, and then the rule would be applied to the wrong trees in both directions — a report among the
  copied files allowed, and a report outside them refused;
- a request that preflight refuses returns before `engine.start`, so the pairing on screen is untouched;
- editing a path field clears the two file lists (they belong to the directory that used to be named) but keeps
  the finished job's rows and its pairing: a job that ran is not un-run by typing somewhere else.

**Stale scan results are discarded, and counted.** A result whose generation is not the current one is not
applied, and the log says so. "Scanning" follows a count of scans in flight rather than the fate of one of them,
so a discarded scan cannot leave the window stuck, and a finished one cannot clear the flag under a newer scan.

**What this does not do.** The refusal is a check before the copy, not a lock. A directory re-pointed by another
process between the check and `rsync` is the cross-process race already recorded under §14; cross-process locking
was considered and declined.

## 16. An acceptance answers one question

§2.11's collision refusal is answered by a person, so the answer needs a scope. `CollisionAcceptance` holds the
collision signature **and** a job identity: source directory, destination directory, job type, and the sorted
relative names of the work set. An acceptance counts only when both match — or when the caller used the
deliberately wider CLI waiver, `acceptAnyCollisions()`, which is what `--allow-collision-names` means.

That rules out two confusions by construction:

- the same relative pair under different roots — `/backup/src1/a.TXT` and `/backup/src2/a.TXT` are different
  files, and a yes for one says nothing about the other;
- the same roots with a different selection — a job asked to handle more files is a different job.

The window's *Copy them anyway?* then re-runs **the request it had already captured**. A question asked about
three names followed by a job started over thirty would be a lie about what was accepted.

The question text is built by `collisionCopyQuestion(names)`: it names the files at risk and does not claim they
are identical, because whether two names become one file is a property of the destination volume — a Windows
share, an exFAT card, some macOS volumes — which this program cannot see.

## 17. One job, one owner

A copy job outlives the moment it was started: a reader thread on rsync's output, a hashing thread, a thread
that was building the report, and the window's own callback are all still running when someone presses Cancel,
when a time limit bites, or when the program is told to exit. Until now the engine kept one cancel flag, one
deadline and one process reference for itself, and cleared them when the next job began. Anything still running
from the previous job then read a clean slate that belonged to somebody else - it could run on as though no
cancel had been asked for, terminate the process of the job that had just started, or release a destination
another job was using.

Now each job owns its state:

- **`JobControl`** - generation, deadline, cancel flag, cause, the process it started and that process's tree,
  the destination reservation, the published counters, and the thread running it. Made for one job, never reset.
  Every loop holds its own controller, so an old worker can only ever see, and only ever stop, the job it
  belongs to.
- **A publication gate.** `finishJob` hands the result over only if its controller is still the current one
  (`compareAndSet`). A late result is counted in `lateResultsDropped()` and otherwise thrown away: it does not
  rewrite the status, the last result, or the reservation of the job that replaced it.
- **Release before callback.** The engine marks itself idle and releases its own destination reservation
  *before* the completion callback runs, so a caller whose callback blocks cannot leave the engine describing
  itself as running. Measured by asserting `isRunning()` from inside `onCompletion`.
- **A thread per job, not a queue.** The single-thread executor looked tidy and was wrong: with a blocked
  listener, the abandoned thread held the queue and the next job never started. Only one job runs at a time
  because `jobRunning` says so, not because the queue is serial.
- **A terminal guard.** A thread stuck inside a read, a hash, or somebody else's callback cannot be made to
  return. Once the job's process tree had been killed and 5 s have passed, the watchdog publishes the job's own
  last published counters as a stopped job and lets go of it, with a warning that the per-file rows from that
  thread are not included. The reservation is released there deliberately: the only thing that wrote to the
  destination was rsync, and that tree is dead by then. What may still be running is Java code that reads and
  reports, and writes nothing.
- **Closing the pipes.** Terminating a job also closes rsync's output streams. A reader blocked on a pipe only
  sees end of file once every writer has gone, and a process that left the tree (a double fork, a `setsid`)
  cannot be reached by signals.
- **`closeOnce()`.** Cleanup is idempotent and bounded. The CLI installs a JVM shutdown hook that runs the
  same cleanup and takes the hook back once the job is over; the window's `shutdown()` calls `closeOnce()` too.
  Two cleanups waiting on one process tree is how a program hangs on exit instead of exiting. Without the hook,
  a signal that reaches only the JVM (plain `kill`, a closing terminal, a stopping container) leaves rsync and
  its children reading the source after this program has gone.

And one rule that a second review caught the first version breaking: **admission and closing share one critical
section**. Asking for the engine (check that admission is permitted, claim the engine, install this job's
controller) and marking the engine closed (capture the controller that closing has to stop) happen under the same
lock, so exactly one of two things is true: the job got in and closing has hold of it and will stop it, or closing
was marked first and the job is refused. Termination, thread joins and listener callbacks stay outside that lock.
An earlier version checked whether the engine was closed outside the section and never rechecked it in the way that
mattered, and closing did not enter the section at all - which let `execute()` admit a job moments after
`close()` had returned with nothing to stop, and that job then copied, finished and reported `COMPLETED` with the
watchdog already retired. Two endings are acceptable; a close that misses an admitted controller is not. The same
review's fix also made sure that a job which loses the publication race gives its destination claim back: a claim
belonging to a job that no longer runs would keep that directory shut to every job afterwards.

Four holes in that design were found by an independent review after the first version of it, and are part of what
the section now describes:

- **Closing used to disable the mechanism that finishes an abandoned job.** `closeOnce()` shut the watchdog down,
  and the watchdog was the only thing that would ever report a job whose thread was blocked in a caller's
  callback: closing left such a job reported as running, with no result, and a command-line run printed no
  summary. Closing now terminates, gives the job's own thread a bounded moment, and then publishes the stopped
  job itself before retiring the watchdog.
- **A job stopped before it had any counters had no report at all**, because terminal publication required a
  progress reporter, and there is none during validation, the directory walk, or the destination snapshot. It
  does not now: the figures are zero, named as such, and the message says which phase the job was in.
- **Terminal publication and admitting the next job were not one atomic step.** The controller's
  compare-and-set picks the winning finisher, but the winner reopened admission before writing its own status,
  so a job admitted a moment later could have its RUNNING stamped over with the older job's FAILED. Admission and
  terminal publication now happen under one lock; the completed state is written while admission is closed, and
  admission reopens last. No listener callback and no process operation happens inside the lock.
- **A forced completion used to run the listener on the watchdog thread.** A completion callback is entitled to
  block; occupying the watchdog with it meant the next job's time limit was never enforced, and its hung rsync
  ran until it finished by itself. Completion is now delivered off the watchdog - fire-and-forget from the
  watchdog, with a short bounded wait when a program is closing and wants its summary printed.

## 19. Stopping a hash that is still reading

Cancelling a job used to stop the job and leave a thread reading a 40 GiB file for nobody: the hash pools,
futures and open reads belonged to no one, source hashing waited on an unbounded `Future.get()`, and the digest
loop never looked for a cancellation. Now:

- the job's controller **owns the hash pools and futures**; stopping a job drops queued work and interrupts
  running tasks, from wherever the stop was asked for - a button, a time limit, a closing program;
- the digest loop checks for interruption **between buffers**, so an ordinary local read ends;
- a job that is stopping does not wait for digests it no longer needs, and an interrupted comparison produces
  **no verdict at all** - a file whose digest was never taken is neither mismatched nor missing;
- a hash cut short is named in the warnings ("Hashing of the source was stopped on request"), and a read that
  refuses to stop - a stalled mount, an unreadable device - is reported as what it is: "A read of a source file
  is still running after being asked to stop, and cannot be interrupted... It writes nothing: hashing only
  reads; but it may still be holding the disk busy." Forced reporting is a last resort, not a substitute for
  cancelling a cooperative read.

Measured here, on rsync 3.4.0 and 3.1.3, with a 300 MB throttled transfer in flight: `cancel()` to completion
is about **400-500 ms** on both versions. A job whose thread cannot finish is reported stopped about **5.5 s**
after its process was killed (the grace period plus the watchdog's 500 ms tick).

The eleven fixtures in `JobOwnershipTest` are checked against deliberately reintroduced defects: removing the
publication gate, disabling the terminal guard, and collapsing the causes each fail at least two fixtures. The
fourth mutation tried - releasing the destination before the gate - is *not* observable, because the
reservation now lives in the job's own controller and is cleared by whoever releases it first; that is the
stronger invariant, and the outsider-refusal fixture locks the consequence.

## 20. What each figure counts

A report figure is a tally of one kind of event. Three families are kept apart, because they answer three
different questions, and a number that mixes them cannot be acted on.

**Verdicts.** `matched`, `mismatched` and `missing` are the verdicts about files the job compared. `checked` is
their sum — a derived figure rather than a fourth counter, so it cannot drift away from them. An `extra` (a file
found in the destination that the job was not asked to handle) was **reported**, not compared against anything:
it counts in `extra` and in `processed`, never in `checked`. A job that found three extras and compared nothing
checked nothing.

**Transfers.** `copied` counts the files whose transfer this job can show (disposition `COPIED`), `wouldCopy` the
files a dry run would have written, `uncertain` the files rsync started that the destination cannot account for,
and `notTransferred` the rows the job knows about that were not transferred. Section 18 is what decides which of
those a file is. The tally is taken from the per-file dispositions rather than from the reported rows, so capping
the row list cannot change the figures.

**rsync's own observations.** `transfersAnnounced` (the itemize lines seen: a transfer started) and
`transfersCompletedByRsync` (rsync's `(xfr#N)`: it says it finished N) are kept as what rsync said, and are
reported beside this program's figures instead of being merged into them. Where the two disagree — a transfer
that died, a receiver error, or a stand-in that prints without writing — the destination decides what `copied`
says, and the disagreement stays on the page instead of being averaged away.

**Progress unit.** `processed` counts the rows the job reported on, extras included. That is what the progress bar
counts. It is not `checked`.

### Requests that went unanswered, and rules that could not be applied

Two silences that both read as success.

**A name the job was given and did not find.** The work set is built from what the source has, so a name in
`--files` that is not there is simply not in it. The job warns and carries on; the names are kept on the job
(`JobControl.selectedButAbsent`) and reported as a figure — `selectedButAbsent` in JSON, `# selectedAbsent,N` in the
CSV — so a scheduled run can decide that "did without" is not "did what was asked". That decision is `--strict`, and
it is deliberately narrow: exit 5 for the unanswered request and nothing else, because a switch that punished
harmless observations (an already-identical file needing no transfer, a destination-only file) would get switched
off. Refused names, missing/vanished files and an unwritable report already fail the run by themselves.

**An ignore pattern that cannot compile.** An ignore rule exists to keep something out; a rule with bad glob syntax
keeps nothing out, and dropping it quietly makes a typo look like a successful exclusion. `ScanOptions.compile()`
returns the rules it could build *and* the patterns it could not, and the scanner turns the second list into warnings
naming each pattern as it was typed (escaped only so it cannot forge a log line). One bad rule cannot take the good
ones down. Nothing is invented about the files: the file the broken rule would have excluded is scanned, copied and
compared like any other, and judged on what it is.

### A CSV cell that a spreadsheet would evaluate

A copied folder can contain a file named `=HYPERLINK("http://attacker")`. Quoting it is not enough: a spreadsheet
evaluates a quoted cell that begins with `=`, `+`, `-` or `@` (and a leading tab or carriage return can make text
look like a pasted command). So `csvField` prefixes a leading apostrophe — the convention spreadsheets already read
as "text literal", and visible, so a reader can undo it knowingly.

It applies to the CSV only, including the `#` metadata block's `message`. The name the program selects, copies,
compares and reports in JSON, and the name in the window's table, are the name on disk: an apostrophe in a filename
would be a different file. A formula character that is not leading cannot be evaluated, so it is left alone.

### A report introduces itself

Rows are not the job. A job can have been stopped, or its per-file list cut short by the results limit, and a
reader who counts four rows will otherwise take them for the whole story. So both exports put the job before the
rows: the CSV in a `#` comment block (`status`, `abortCause`, `message`, `truncated`, `rowsListed`, `rowsOmitted`,
every count, `rsyncExitCode`, and how many errors and warnings followed), the JSON in fields beside `totals`. The
window says it where the rows are looked at — `Results (3 of 12)` in the tab title, and a line in the log — and the
command line prints it as a note under the totals. `truncated` is derived from `rowsOmitted`, so a report cannot
claim to be complete and name rows it left out at the same time.

The equations the exports state and the fixtures assert:

```
checked   = matched + mismatched + missing
processed = checked + extra            (for a job that reports every file it handled)
a file that was copied and then verified contributes to copied and to checked, once each
```

### What this changes for someone reading the numbers

Earlier releases derived `filesCopied` by capping rsync's announcement count with its `xfr#` figure, and used the
reported-row count as `filesChecked`. Both meanings changed deliberately. `copied` is now the figure this program
can support, and `checked` is the verdict sum. A cut-short copy that used to report `copied=1` on rsync's word now
reports `copied=0`, `uncertain=1`, `rsync-said-completed=1` where the destination cannot show which file arrived —
and still reports `copied=1` where a file really was created and is the size it should be. Anyone reading exports
should use the new JSON fields (`wouldCopy`, `uncertain`, `notTransferred`, `transfersAnnounced`,
`transfersCompletedByRsync`, `processed`) instead of assuming `copied` means "files rsync mentioned".

## 18. What rsync's output can and cannot prove about a transfer

Completion evidence was the audit's hardest requirement: a transfer that was cut short must never be reported as
copied, and a report must say which files it cannot vouch for. Two candidate output protocols were measured on
rsync 3.1.3 and 3.4.0 in this container (3.2.7 and 3.3.0 are left to the CI matrix):

| asked for | where rsync puts the per-file line | what a name in that line proves |
| --- | --- | --- |
| `--info=progress2` + `--out-format='%i\|%n'` (what the program uses today) | before that file's progress lines | rsync had started that file - **not** that it finished |
| `--info=progress2` + `--out-format='%i\|%b\|%n'` | after that file's 100% line | the transfer completed, with the bytes rsync put on the wire for it |
| `--progress` + `--out-format='%i\|%n'` | before | started |
| `--progress` + `--out-format='%i\|%b\|%n'` | after; the bare file name also appears on its own line before that file's progress | completed, and a start name as well |

Two assumptions were disproved by measuring:

- **`%b` is not the file's size.** A 4-byte file reported 47; a 4 MiB file reported 4,194,855. It is the bytes
  transferred for that file, so it can witness a completed transfer but cannot be compared against the size the
  program expected.
- **A whole-transfer percentage and a per-file start name do not come together.** `--info=progress2,name1`,
  `name0` and `copy1` produce no per-file header at all. So the choice is between the whole-transfer bar with
  start lines (kept), and per-file progress with completion lines (which would mean computing the overall bar in
  this program, from the sizes of the selected files).

Two further measurements are why "uncertain" stays a permanent category:

- A run interrupted by `SIGTERM` exits with code **20**, prints the start line and progress for the file it was
  writing, prints **no** completion line for it, and deletes the hidden temporary file it had open.
- If the reading side stops reading, **rsync does not notice**: it went on to transfer and finish every file with
  no error message at all. So the absence of a completion line is never evidence that nothing was written.
  Stopping a job therefore means signalling the process tree, never closing the reader.

Decision: keep the current protocol. Per-file completion is already decided from the pre-transfer ledger
(what the job intended to write, and what the destination held beforehand), so switching to `%b` would trade
the whole-transfer progress figure - and a protocol change touching every fake-rsync fixture - for evidence the
ledger already obtains conservatively. If Option B is adopted later, the pair is `--progress` with
`--out-format='%i|%b|%n'`, and this program must compute the overall bar itself.

