# Changelog

## Unreleased — audit remediation, first phases

### Added — `--strict`, and an ignore pattern that does not compile says so

- `--strict` makes a run nobody is watching fail when it did not do everything it was named for. It fails for one
  case: a name this run was given (`--files`, `--files-from`, …) had no file behind it when the work set was built
  — **exit 5**. The other things in the documented list (a refused name, a missing or vanished file, a report that
  could not be written) already failed the run without the switch. It does not fail a run for an observation: a file
  that needed no transfer, and a destination-only file, stay observations.
- Those names are now a figure rather than only a sentence: `selectedButAbsent` in JSON, `# selectedAbsent,N` in the
  CSV metadata block. `--strict` changes the exit code and nothing else — same job, same copies.
- An ignore pattern with invalid glob syntax used to be dropped silently, which turned a typo into a directory being
  copied when the reader believed it was excluded. The job now warns and names the pattern; the other patterns still
  apply, and the file the broken one would have excluded is judged on what it is — a broken rule is not a verdict.
- When one run has both a strict finding and a report that could not be written, the report failure is the exit code:
  a run that cannot record itself is the worse of the two failures.
- A job stopped by its time limit names the limit that passed whatever phase noticed it. Two of the three wording
  paths did already; the one at admission did not, which left someone who set 250 ms guessing which 250 ms expired.

### Changed — a CSV cell that a spreadsheet would evaluate is disarmed

- A CSV field whose first character is `=`, `+`, `-`, `@`, a tab or a carriage return is written with a leading
  apostrophe, the convention spreadsheets read as "text, not a formula". A folder containing a file named
  `=HYPERLINK("http://attacker")` used to export a cell that a spreadsheet might evaluate for whoever opened the
  report; the job's own `message` field goes through the same rule.
- **CSV-only.** The name as the program matches on it, the name in the window's Results table and the name in JSON
  stay exactly as they are on disk: putting an apostrophe in front of a file's name would name a different file. A
  non-leading formula character (`mid=equals.txt`) is ordinary text and is left alone.
- This closes the item the earlier changelog and code comments said had not been done.

### Changed — a report says which job it came from, and how much of it is missing

- The CSV begins with a `#` comment block naming the job: `status`, `abortCause`, `message`, `truncated`,
  `rowsListed`, `rowsOmitted`, every count, `rsyncExitCode`, and how many `errors` and `warnings` the job
  reported. Then the columns row, then the rows. **Reading the CSV changes:** the columns row is no longer the
  first line, so skip lines starting with `#`. Nothing in the row format itself changed.
- The JSON gains `abortCause`, `truncated`, `rowsListed` and `rowsOmitted` beside the `totals` object.
- `truncated` is now derived from `rowsOmitted`, so a report cannot say it is complete while naming rows it left
  out. The engine counts the rows a job produced whether or not the results limit let them into the report.
- A stopped job whose per-file rows were never collected says how many rows are missing rather than only warning
  that some are.
- The window titles the Results tab `Results (3 of 12)` when the list is short, and logs the same in words; the
  command line prints a `note:` line under its totals. A forced terminal report from a job whose thread would not
  finish states the number of rows it left out.

### Changed — the figures count what their names say

- `checked` is now the sum of `matched + mismatched + missing`. A destination-only file was **reported**, not
  compared against anything, so it no longer counts as a check: a job that found three extras and compared nothing
  checked nothing. The row count that does include extras is reported separately, as `processed`.
- `copied` counts the transfers this job can **show** arrived, tallied per file by the rules in DESIGN §18, instead
  of being rsync's announcement count capped by its `xfr#`. What rsync announced (`transfersAnnounced`) and what it
  claimed to have completed (`transfersCompletedByRsync`) are reported beside it as observations of their own. A
  cut-short copy that used to claim `copied=1` on rsync's word now says `copied=0, uncertain=1,
  rsync-said-completed=1` when the destination cannot show which file arrived — and still counts a file that
  really was created at the size the source had.
- Dry runs report `wouldCopy` and report `copied=0`: an export saying a file was copied would record an event that
  never happened.
- A file that was copied and then verified contributes to both families, once each — `copied=1, checked=1,
  matched=1` is one file, not two.
- The command line prints a second totals line (`transfers: …`), the window logs the same figures, and both exports
  carry them, so whatever a reader adds up is what the report states. `JobStatus` per-file rows are unchanged: the
  verdict column answers the comparison question, the transfer column the copying one.

### Fixed — symbolic links are judged as the objects they are

- Under **LINK**, a link is compared by its **kind and its link text**, never by hashing: no digest is taken of a
  link or of anything at the end of one, because the target is not the link. A link whose text differs is
  reported as a mismatch with a warning naming what each side says.
- A link that reached the destination as a regular file, and a regular file whose destination is a link, are
  mismatches **in kind** - the warning says which object became which, and nothing is read through the link.
- Under **LINK** the scanner now carries a link to a directory (listed, never descended into) and a **dangling**
  link (an object with no target) instead of dropping both. They are copied as links and judged by their text, so
  a dangling link that arrived dangling is no longer reported as missing, and a directory link is no longer
  silently left out of the job.
- Under **FOLLOW** the verdict is about bytes at the end of a source link, and the report says so: the warning
  names the files whose comparison ran through a link, including a link that points outside the two directories.
- A link standing in the destination is never followed, under any policy. A copy replaces it (measured), and
  reading through it would compare a file that belongs to neither of the directories the job was given.
- Where the destination already held a different kind of object than the source has, the job now says so **before**
  transferring, because after a copy there is nothing left in the destination that shows what it replaced.

### Fixed — closing the program can no longer miss a job that was being admitted

`execute()` used to ask "is the engine closed?" outside the lock that admits a job, and closing did not enter that
lock at all. So a job could pass its checks, wait its turn, and be admitted *after* `close()` had returned having
seen nothing to stop - and it then copied files, finished, and reported `COMPLETED` while the program was closed and
its watchdog had been retired. Asking for the engine and marking it closed now happen in one critical section, so
either the close was marked first and the job is refused, or the job got in and the close captured and stopped it.
A job whose result arrives too late to be published also gives its destination claim back, rather than leaving that
directory claimed forever with no job alive to release it.

### Fixed — four lifecycle holes an independent review found in that work

- **Closing the program while a progress callback was blocked left the job running with no result.** Closing
  shut down the watchdog, and the watchdog was the only thing that would ever finish reporting a job whose own
  thread was stuck in a caller's callback. Closing now stops the job, gives its thread a bounded moment, and
  publishes the stopped job itself before retiring the watchdog.
- **A job stopped while it was still listing files had no report at all**, because reporting a stopped job
  required counters that did not exist yet. It now reports zero figures honestly and names the phase it was in.
- **A new job could be stamped over by the job that came before it.** Admission reopened before the finished job
  had written its own status, so a second job's "running" could be replaced by the first job's failure. Admitting
  a job and publishing a terminal state are now one serialized step: the completed state is written first, and
  admission reopens last.
- **A forced completion ran the callback on the watchdog.** A callback that blocks therefore stopped the *next*
  job's time limit from ever being enforced, and its hung rsync ran on to completion by itself. Completion is
  delivered on another thread whenever the publisher is the watchdog or a program on its way out.
- **Cancelling a job did not stop a hash that was reading.** The hash pools and futures belonged to nobody,
  waiting on a digest was unbounded, and the digest loop never looked for a cancellation, so cancelling a job
  during a large read left a thread reading tens of gigabytes for nobody. A job now owns its hashing: a stop
  drops queued work, interrupts running tasks, and ends the read between buffers. A read that cannot be
  interrupted is reported out loud - "still running after being asked to stop, and cannot be interrupted" -
  rather than counted quietly as stopped.
- **An interrupted comparison could be reported as a mismatch or a missing file.** A file whose digest was never
  taken gets no verdict, not a finding.

### Fixed — a job belongs to one owner, and for as long as it takes

- **Cancellation, a time limit and the program closing are three different answers now.** A job result carries
  why it stopped, the window says "Job cancelled.", "Job stopped: it passed the time limit." or
  "Job stopped: the program was closing." and logs the stopped job as stopped. A timeout message names the
  limit that was passed ("1.5 s"), the phase it was in, and says plainly that the files were not reported as
  different by the stop - a limit that was too short is not a fault in anyone's data.
- **The engine no longer keeps one cancel flag, one deadline and one process for everybody.** They belonged to
  the engine and were cleared when the next job started, so a leftover reader, hashing thread or reporting
  thread read a clean slate that belonged to another job: it could run on believing it had never been cancelled,
  or kill the process of the job that had just started. Each job now owns its own state, and a result from a job
  the engine had already let go is dropped and counted instead of rewriting the next job's facts.
- **A job whose own thread cannot finish no longer holds the program open.** The engine marks the job stopped
  about five seconds after its process tree was killed, reports the counters that thread had published, releases
  the destination, and can start another job; the abandoned thread's late result reaches nobody. Jobs run on
  their own thread, so an abandoned one cannot block the next job behind it in a queue.
- **A timed-out copy leaves no `rsync` reading the source.** Cancellation and closing were checked for this;
  the time limit was not.
- **A stopped job's reader is not left waiting forever.** Terminating a job closes `rsync`'s output pipes too,
  because a process that left the process tree (a double fork, a `setsid`) holds them open and no signal can
  reach it.
- **A signalled command line stops the copies it owns.** `--cli` registers a shutdown hook that performs the
  same cleanup as a normal exit, and removes it once the job is over; the cleanup runs exactly once however
  many callers ask, and is bounded, so the program exits instead of waiting on itself.

### Fixed — the file you selected is the file that gets copied

- **A name with leading or trailing spaces selected a different file.** Selection lists were trimmed, so
  choosing `" a "` when `a` also existed chose `a` — including when `a` was the file you had excluded.
  Syntax is still rewritten (path separators, a leading `/` or `./`); the whitespace that belongs to a name
  is not touched.
- **A file whose name starts with `#` or `;` was never copied, while the job reported success.** In the list
  handed to rsync those entries are comment syntax, and asking for NUL-delimited names does not change
  that. Entries are now NUL-delimited *and* prefixed with `./`, which defeats the comment and option
  syntax of a file list.
- **Names containing a newline or a carriage return were skipped silently.** With a NUL-delimited list they
  are ordinary data: they reach the destination and are named exactly in the report. They are escaped only
  where they are shown, so a name cannot forge an extra log line.
- **Names in the report were mangled.** rsync's mention lines are requested with a fixed separator and the
  name last, and its octal `\#ooo` byte escapes are decoded — bytes reassembled first, then decoded once,
  so a character that arrives as several escapes survives. A backslash followed by digits inside a real
  filename stays exactly that.

### Added

- `--files-from`, `--files-from0`, `--exclude-from`, `--exclude-from0`, `--exclude-dest-from`,
  `--exclude-dest-from0`: names read from a file, delimiter fixed by the option name, no comma syntax, no
  trimming. A list that is not valid UTF-8 is refused by name instead of being decoded into a name full of
  replacement characters. The newline-delimited form cannot name a file whose own name contains a newline;
  the `0` form can.

### Fixed — an interrupted transfer says what it is

- A copy job stopped part way names every file it started, and says which of them it can prove finished:
  `COPIED`, `WOULD_COPY` (dry run) or `UNCERTAIN`. The case that motivates this is an overwrite interrupted
  half way where the destination file was already the same size as the source: afterwards the destination
  holds the old bytes, the old inode and the same size, so nothing on disk shows what happened, and the
  report says so instead of counting it as copied.
- A verifying job stopped before the comparison reports `PENDING` rows with the transfer answer, instead of
  an empty Results tab under a "1 copied" summary.
- A file's transfer and its verdict are recorded separately, so a copied-and-verified file is one file with
  `copied=1, checked=1, matched=1`.

### Fixed — after source review of the first remediation commits

- **The line-delimited CLI channels no longer split a name at the wrong character.** They used a regex
  `\R`, which treats vertical tab, form feed, NEL, line separator and paragraph separator as line endings
  too: a file named `foo\u000Bbar` became two selections, `foo` and `bar`. The delimiter is now defined - a
  line feed, and a carriage return before it only when the whole file uses CRLF - and a file that mixes the
  two is refused with a pointer to `--files-from0` rather than guessed at. Every other character stays part
  of the name.
- **The older rsync output format no longer loses filename data.** Without an out-format, the delimiter is
  the space after the itemize column: `>f+++++++++  a ` is the file `" a "`, and `>f+++++++++ pipe|name` is
  the file `pipe|name`. Only the delimiter itself is removed.
- **An interrupted symlink is no longer reported as uncertain when it completed.** What a copied symlink
  must match is its target text, not a size - and the expected kind and size are now taken from the source
  *before* the transfer, not measured afterwards, so a source that changed mid-job cannot be matched
  against its own new size. Where a replacement cannot be shown by a changed file identity, the answer is
  `UNCERTAIN`: a change of object kind alone is not evidence this job did it.
- **Exports carry the transfer answer.** CSV gains `transfer` and `verification` columns (appended, so the
  existing four columns keep their meaning and order) and JSON gains `transfer` and `verification` fields.
  The Results table gains a `Transfer` column. Before this, a verifying job stopped before its comparison
  exported `PENDING` for a file whose transfer completed and for a file whose transfer is unknown - the
  distinction the record exists to keep, lost on the way out.
- **The snapshot the copy record needs is cancellable.** Cancel during it stops the job instead of walking
  the rest of the list, and the files it had not reached are named as unconfirmable.

### Added

- `FCU_RSYNC`: pin the rsync binary this program (or one test run) uses, instead of taking whatever is on
  `PATH`. A test run with `FCU_RSYNC` set to a binary it cannot run now refuses to run at all rather than
  quietly skipping every rsync-dependent test.

### Added — the job cannot write where it has no business writing

- **A copy whose source and destination overlap is refused before anything is written.** In both directions,
  and against the directory a path *lands on* rather than the path as spelled: a destination inside the source,
  a source inside the destination (the audit's overwrite fixture), a destination that is a symbolic link to the
  source, and a source reached through a link inside the destination. Each refusal names the relationship and
  what to do instead; a refused job has changed no file. Verify Only is **not** refused for overlap — it writes
  nothing, and refusing a read-only comparison of a directory with itself would be this program inventing a
  danger.
- **Selected paths are contained in both trees, ancestors included** (A04). A name carrying `..` is refused,
  and so is a name that looks contained but leaves through a linked directory: containment resolves the deepest
  existing ancestor before judging it. **Verify Only is held to the same rule**, which is where the audit's
  escape actually was - a comparison that read `dst/sub/a.txt` through `dst/sub -> /tmp/outside` reported an
  outside file's digest as the backup copy's. A read can fabricate a verdict as well as a write can damage a
  file. The **final component is never resolved**, measured rather than assumed: a copy replaces a symbolic link
  sitting at a destination path and leaves the link's target alone, so resolving the leaf would refuse lawful
  jobs - a source link copied as a link because its target lives elsewhere, or a second copy into a destination
  link the first copy had created. Documented limit: the check happens before the job, not atomically at each
  write, and no cross-process lock is held.
- **A report can no longer displace the files it describes** (A11). A report path that already exists is
  refused - this program cannot tell a report it wrote earlier from a file the job protects, so it does not
  guess; the same for a directory or a symbolic link. Writing a *new* report inside the source or destination
  tree is a separate decision and needs `--allow-report-in-tree` (CLI) or a confirmation in the window, and even
  then it never overwrites. Content is staged by `Files.createTempFile` in the report's **own** directory (a hard
  link cannot cross a filesystem) and published with `Files.createLink` - creating a link cannot replace an
  existing name, and the name it creates already holds the complete report, so a half-written report is never
  visible. On a filesystem without hard links (FAT, exFAT, some network and FUSE mounts) **the report is
  refused**. The fallback written first - create the name exclusively, then copy into it - was removed: by the
  time the copy ran that name could belong to another writer, and a copy with replacement would overwrite their
  file through a path this program had just created, while the report name sat there half-written. `ATOMIC_MOVE` was the first attempt and is gone: an atomic rename
  is still `rename(2)`, which is permitted to replace an existing target, so it was never the guarantee it was
  taken for - measured here: on OpenJDK 17.0.20.1+1 (`sun.nio.fs.LinuxFileSystemProvider`),
  `Files.move(temp, existing, ATOMIC_MOVE)` against an existing target succeeded and the target took the temporary
  file's bytes; `UnixCopyFile.move` calls `rename` in its atomic branch and returns before the
  `FileAlreadyExistsException` check. `--csv`/`--json` no longer create a missing directory: a path that does not
  exist is a mistake to report.
- **Report paths are checked before the job starts.** An existing or forbidden target, a missing parent
  directory, `--csv` and `--json` naming the same file, or an in-tree report that has not been allowed is a
  usage error (exit 2) and nothing is copied. The two-format check compares the directory each path resolves
  into plus the name, not the two strings: with `reports/alias` a link to `reports/real`,
  `--csv reports/real/result` and `--json reports/alias/result` are one target and are caught together. The old
  order - start the copy, write one report, then refuse the second - is what let `--csv x.csv --json x.csv` copy
  everything and record half of it. A report path that only
  becomes taken *during* the run (a copied file that happens to be called `report.csv`) is caught at publication:
  the copies stay as they are, no report appears, and the run exits 4.
- **New exit code 4**: the job did what it was asked to do and a report could not be published. A report problem
  knowable in advance is a usage error (2) and the job never runs.
- **Colliding names now need an answer before a copy runs** (A6). Two names differing only by case or by
  how an accent is composed are one file on a case-insensitive or normalising destination, so copying both
  overwrites one with the other: the job refuses, writes nothing, and says how to accept it
  (`--allow-collision-names`, or confirming in the window). The window's answer is bound to **the names it was
  shown**: a later job that finds a different pair is refused and asked again instead of riding on the earlier
  yes, so one dialog is not a licence for the rest of the session; the CLI flag is openly wider, since it was
  typed for that run. Verify Only still just warns, because it writes nothing, and a dry run still shows what the
  collision would do.
- GUI: the same two decisions are dialogs, not silent defaults - a report placed among the copied files is
  confirmed, and a collision refusal is followed by "Copy them anyway?" that re-runs the job with the risk
  accepted.
- Tests: every engine and CLI copy fixture now asserts the source tree is byte-for-byte unchanged after the
  run; a fixture that means to change the source says so by name. `WriteSafetyTest` (30 fixtures) covers the
  refusals, the report rules, the acceptance lifetime, and the case where a report path is taken mid-run. 227
  tests, 0 failures.

### CI and releases

- One reusable validation workflow - tests, CLI smoke tests, and an rsync matrix built from checksum-pinned
  tarballs of 3.1.3, 3.2.7, 3.3.0 and 3.4.0. Branch CI and the release workflow call the same one; a release
  validates the exact commit its tag names before publishing anything, and refuses when the tag does not
  resolve or when the jar's own version does not match the tag. Third-party actions are pinned by commit SHA.

### Added — the window decides from one scan, and an acceptance belongs to one job

**Run works from one captured request, not from whatever the tables show at the moment a background thread gets
to them.** A scan is now a thing with an identity — a number, the two directories, the options it was read with,
and how many rows it produced — and the ticked rows belong to *that* scan. Pressing Run takes the current ticks
once, puts them into one immutable request, and everything downstream (the engine, the collision question, the
re-run after a collision answer) works from that object. Run refuses, in the log as well as in a dialog, when a
job would not mean what the user can see:

- nothing has been scanned — *an empty selection means no files, not all files, and this program will not guess
  what you meant*;
- a scan is still reading the directories;
- a path field has been edited since the scan — the rows belonged to the directory that used to be named there,
  so they are cleared with it;
- the scan options have changed — the rows describe a different set of files, because hidden files, ignored
  directories and symbolic links decide what a directory contains.

A scan that finishes after a newer one has taken over is **discarded and counted**, with a line in the log: a
quietly dropped result is indistinguishable from a scan that found nothing. "Scanning" now follows the number of
scans in flight, so a discarded scan can neither leave the window stuck saying it is scanning nor clear the flag
out from under a newer one.

**A collision acceptance belongs to the job it was given for.** The acceptance carries the collision set *and* the
job: the two directories, the kind of job, and the files that job was asked to handle. So the same relative pair
under other roots (`src1/a.TXT` accepted does not cover `src2/a.TXT`), and the same roots with a bigger
selection, are different questions and get asked again. The window's *Copy them anyway?* re-runs the request it
had already captured, so the files that go are the files that were named in the question. The question itself
names the files and does not claim they are identical — whether two names are one file is a property of the
destination volume, which this program cannot see. `--allow-collision-names` stays the deliberately wider answer:
it was typed for that run.

**Correction from review: a finished job's report is judged by that job's directories.** The export path still
asked the two text fields where the source and destination were, so a report could be judged against directories
the job never touched. The finished result is now paired with the one request that produced it
(`CompletedJob(request, result)`, paired inside the completion callback that names its own request - there is no
"current request" field for a later job to overwrite). Results rendering and export protection read the pair. Two
consequences, both intended: editing a path field no longer wipes the rows of a job that already ran, and a later
request that the engine refuses before starting cannot re-point the older job's report. Export goes through
`writeCompletedReport(target, format, allowInsideDataTrees)`, which is the policy seam - the file chooser only
picks a path, and the file chooser is not needed to test the rule. Dialogs are also headless-safe now: a refusal
that could not be shown no longer throws, because the log already carries the words.

`FileVerifierViewTest` grows to 23: `aReportOfACompletedJobIsJudgedByTheDirectoriesThatJobWorkedIn` (a report
among the copied files stays refused after the fields are moved elsewhere, and a path inside the *newly typed*
directories is written because it is outside the job that ran) and
`aRequestThatNeverStartedDoesNotRepointTheReportOfTheJobThatRan` (a request refused by preflight leaves the
pairing alone; the report is still refused among the first job's files). Export pointed back at the fields:
**both fail**.

Tests: `FileVerifierViewTest` grows to 21 — Run refused before any scan, refused during a scan, a typed path
taking its list with it, changed scan options refusing Run until the scan is redone, a scan result carrying an
older number discarded and said so, a running job keeping the ticks that were on screen when Run was pressed, and
the collision acceptance being asked again for another directory pair and a larger selection. Checked by removing
each guard: Run's authority check out — 5 of 21 fail; the stale-scan discard out — 2 fail; acceptance not bound to
the job — 2 fail across the view, write-safety and collision suites.

## 1.4.0 — verification is a switch, not a mode

- **The mode control is now `Copy | Verify only`, with a `Verify after copying` checkbox beside it**
  (default: on). Instead of choosing between three modes you choose whether the job copies, and separately
  whether it compares what it copied. Verify only stays a mode because it has no substitute: it is the only
  job that looks at a destination without touching it, and that cannot be a checkbox on a copy job.
- **A copy with verification off says so.** The button reads `Run copy`, the status line reads
  "Copy complete - nothing was compared.", the log carries
  "Verification is OFF: files will be copied but not compared", and the job's own summary is
  "Copy complete: 214 file(s) copied. Verification was turned off, so none of them were compared." A CLI job
  prints `totals: copied=214 (verification was off: ...)` instead of a line of zeroes that reads like trouble.
- **Every file such a job wrote is listed as `COPIED`** in the Results tab and in CSV/JSON exports, with no
  hashes, because the transfer is the only fact that job knows. A dry run names them `WOULD_COPY` instead: an
  export that says COPIED for a file nobody wrote records an event that did not happen. A verifying job still
  writes one row per file — its verdict — and never both a copy row and a verdict row. `COPIED` is deliberately
  not coloured like a check that passed.
- **Nothing is hashed when nothing will be compared.** A copy-only job does not read the source to compute
  digests, which on a large tree is most of the job's cost. Verified by contrast: the same unreadable source
  file produces a "Could not read" warning in a verifying job and no such warning in a copy-only one, because
  the copy-only job never opened it.
- Cancelled, timed-out and failed copies no longer claim files "were verified" when verification was off.
- CLI: `--no-verify`; `--verify-only` unchanged; the two together are a usage error rather than last-flag-wins.
- Settings remember the switch separately from the mode, so turning verification off survives switching
  between Copy and Verify only.

### Large copies: why a file appears at the end, and what cancelling now stops

- **The copy phase now names the file in flight.** The status line reads
  `Copying 9.9 MB so far (8%) | now writing: big.bin (9.9 MB of 114.4 MB, 9%) | 0 of 1 done | 1.95MB/s, elapsed 0:00:54`
  - which file is going into the destination, how much of *that file* has landed, how many files are finished,
  the rate, and rsync's elapsed time. Derived from rsync's own stats lines plus the announced file names:
  measured on rsync 3.1.3 and 3.4.0, an itemize line arrives when a file's transfer **starts**, and the
  `xfr#` count on the stats line is what says a file **finished**. The completed-file figure follows rsync's
  `xfr#` so it reaches the last file instead of stalling at zero.
- **`Phase: copying` no longer covers hashing the source.** A job now reports `hashing source` → `copying` →
  `verifying`. Hashing 15-30 GB before rsync writes anything is most of the first hour of a big copy, and
  calling that "copying" with an empty destination folder is exactly what makes a job look stalled.
- **Why the destination looked empty, said out loud.** Once per job the window and the CLI now log it: rsync
  writes each file as a **hidden temporary file** in its destination folder (`.big.bin.qjX7kX`) and renames it
  to its real name only when that file's transfer completes. Measured: at 2 s into a 30 MB copy the destination
  holds `.a.bin.v7asq2` at 2.8 MB growing to 30 MB; `a.bin` appears only when the transfer completes. A large
  file is therefore in the destination the whole time - show hidden files to watch it grow.
- **Cancelling stops the whole rsync tree, and it is tested.** A local copy is three processes (measured:
  parent, child, grandchild). The tree is snapshotted **before** anyone is signalled - after the parent dies
  its children are re-parented to init and a `descendants()` call then finds nothing - SIGTERM goes to all of
  them first (so rsync deletes the partial file it was writing), and only survivors get SIGKILL. After a
  *successful* copy anything still alive in the tree is killed too, because rsync's parent can exit while its
  children are finishing and an orphaned child is unreadable to this program afterwards.
- **A cancelled job can no longer start rsync.** Cancelling during the hashing pass used to let the job reach
  the spawn and launch a reader on the source disk that nothing was watching any more. The copy phase now
  refuses to start, and a cancellation that lands between the check and the fork kills the process
  immediately.
- **A cancelled copy leaves no partial file behind** (measured: the destination is empty after cancelling;
  previously `.a.bin.AG4rJt` stayed there). The leftover report still exists for the cases where rsync is
  killed outright and cannot clean up.

Tests: 176, five of them new for this: no rsync survives a mid-transfer cancel and no partial file is left; no
rsync survives the window being closed mid-transfer; nothing is left after a *completed* copy; a job cancelled
during hashing never launches rsync (a stand-in rsync records whether it ran); and the copy status names the
file in flight with its own percentage. Also measured against real rsync through the shipped jar.
Tests: 171, including 5 new engine tests for copying without comparing, 3 CLI tests, and window tests for
the switch, the button label, the copied rows reaching the results table, and the setting surviving a restart.
The theme test now asserts that `COPIED` is not coloured like a check that passed.
## 1.3.0 — two interface changes

- **The source and destination fields are editable.** Type a directory and press Enter to load both lists;
  Browse still works and still starts in the directory already named. A typed path means what a shell would
  mean: `~` is the home directory, a relative path is taken from where the program was started, surrounding
  spaces are ignored. Changing a field **clears the loaded file lists and says so** — the rows on screen are
  ticks against paths in the directory that field used to name, and keeping them would let a job run with a
  selection from somewhere else. The fields lock while a job is writing. A directory name this system's
  file-name encoding cannot spell now produces a dialog naming the encoding instead of an exception.
- **The window reports its own version** in the quiet corner, bottom right, in muted caption text: `v1.3.0`. It
  is read from a resource the build stamps with the project version
  (`com/fileverifier/version.properties`), not a hardcoded string, so the number in the window cannot disagree
  with the jar name, the tag, or the changelog. A build that stamps nothing prints `version unknown` rather than
  a number that might be wrong. Hover it for the full name.
- `UiTheme.readOnlyField` is gone: it existed to work around the "disabled" wash a non-editable field gets from
  the look-and-feel, and there are no longer any non-editable path fields.

Tests: 160, including a headless window test that types a path, presses Enter, waits for the lists to fill,
changes the path, and asserts the stale rows are gone; and one that asserts the version the window shows is the
version the build stamped.
## 1.2.2 — collisions, races, and engine bookkeeping

Things the copy cannot see from inside, plus two pieces of state the engine should have owned all along.

- **Name collisions are named.** A work set containing `A.txt` and `a.txt` (or an accent written two ways) is
  two files on your filesystem and one file on a case-insensitive or normalising destination — a Windows share,
  exFAT, some macOS volumes. The pairs are reported before anything is written, with the consequence stated:
  the later transfer would overwrite the earlier one. A warning, not a refusal, because on a case-sensitive
  destination both names genuinely are separate files. Without this the tool overwrites one file with the other
  and then reports a mismatch as if the copy had been honest.
- **A source file that moves mid-copy is described as that.** A copy verifies against the digest taken before
  the transfer; when a mismatch appears the source is re-hashed once, and if it changed the job names the file
  as "changed in the source while the job ran" instead of leaving an unexplained MISMATCH that reads like
  damage. Verify Only has no pre-transfer digest, so it makes no such claim.
- **Half-written files are reported on a failed copy**, not only on a cancel or a timeout — the gap left by the
  1.2.1 fix to failure counters. Tested with a stand-in rsync that leaves `.data.AAAAAA` behind and exits 23.
- **Each engine chooses its own rsync.** The binary was a process-wide static; now an engine can override it
  (`useRsync`) and preflight checks the binary that engine would actually run. The process default remains a
  default.
- **One live job per destination per program.** A second job pointed at the same destination fails immediately:
  two jobs writing one directory interleave, each reporting the other's files as its own results, and a
  partial-transfer warning can name a file the other job is still writing. This is an in-process guard, not a
  lock — a second *process* would need a lock file written into your destination tree, which is left as the
  owner's decision rather than a default.

Tests: 160, plus the rsync matrix on 3.1.3 / 3.2.7 / 3.4.0.
## 1.2.1 — robustness audit

Five defects found by attacking the program instead of writing the tests it seemed to need. Each now has a
regression test (`RobustnessTest`).

- **A machine whose locale cannot spell a file name crashed the scan.** File-name encoding comes from the
  locale, and under `LANG=C` — cron jobs, systemd services, minimal containers, `sudo` — every non-ASCII byte
  in a name is unrepresentable: `DirectoryScanner` died with `InvalidPathException`, and a job threw out of
  the engine. Such entries are now skipped with one clear warning naming the encoding and the fix
  (`LC_ALL=C.UTF-8`), and no guessed name is ever written to the destination.
- **An rsync failure partway through a copy reported "0 copied"** while the files it had already written sat
  in the destination. Every failure path returned zeroed counters. A failed copy now gets the same
  accounting as a cancelled one: what rsync announced, how many it completed, and whether it left
  half-written files behind.
- **A symbolic link at the destination was followed and hashed.** The scanner refuses to list links, but the
  destination side is reached by resolving a relative path, so a link can appear during verification. It used
  to read a file outside the destination and report its digest as the destination's. Now the file is reported
  as MISMATCH with an explicit "not compared, this tool does not follow links" warning, and nothing outside
  the destination is read.
- **A carriage return in a file name was accepted**, handed to rsync's line-based `--files-from`, and came
  back as "rsync failed (exit code 23)" with nothing explaining why. It is refused up front like a newline or
  a NUL byte, named in the warnings, and reported as a file that was not transferred.
- **A `null` `ProgressListener` turned the job into a failure** whose message was an internal null
  dereference. Silence is now what it means.

Also checked, and correct: names containing `-`, `$`, `\`, `;`, `'`, `[`, `,`, tabs and trailing spaces survive
a copy and a verify; a selected file deleted between scan and run is named rather than silently dropped; a
symlinked directory inside the destination is not written through.
## 1.2.0

The interface phase. What the program does with files is unchanged; what the window looks like, and how
much of it can be tested without a screen, is not.

### Interface

- **FlatLaf theme** (light and dark), with a *Dark mode* checkbox that takes effect immediately and is
  remembered. Colours, fonts and gaps live in one class (`UiTheme`); the layout code contains no colour
  literals at all, and a test enforces that.
- **Layout rebuilt for legibility**: labels above their fields, scan options on one line, cards instead of
  titled group boxes, a hairline edge on the lists and log, one vertical rhythm for padding.
- **Mode choice is one control**: *Copy & Verify* and *Verify only* are now the two halves of a single
  segmented control, with the work-set line that follows from the choice next to them.
- **One primary action**: *Run job* is the only filled button in the window. Cancel is red, everything
  else is quiet.
- **Results table fixed**: `JScrollPane(JTable)` does not attach a table header, so the column titles -
  and the click-to-sort affordance the toolbar advertised - were missing since 1.1.0. The header is
  attached now, columns have deliberate widths, and the status column shows a colour dot *and* the word
  (a dot alone would fail for a colour-blind reader and for a printed export).
- **Progress line gained a phase**: the status row now says which phase the job is in.
- **Keyboard shortcuts**: `Ctrl+S` scan, `Ctrl+R` run, `Escape` cancel. `Enter` is deliberately left
  alone so it keeps activating whichever control has focus.
- **Window title unchanged** (*File Verifier & Copier*): the long name is a title, not a coordinate.

### Structure

- The Swing front end is now a panel (`FileVerifierView`) hosted by a thin window (`FileVerifierFrame`).
  A `JFrame` cannot be constructed at all when the JVM is headless, which had made the entire UI
  untestable in CI. The panel can be built, driven, and painted offscreen with no display server.
- New tests: 8 for the theme (every text/badge pairing clears 4.5:1 in both palettes, a palette switch
  actually repaints widgets built under the old one) and 7 for the interface structure (every control
  still exists, mode halves are mutually exclusive, the results table has a header, no colour literals
  outside `UiTheme`). Suite: 137 tests, SpotBugs clean.

### Packaging

- Version 1.2.0. The runnable jar now bundles FlatLaf 3.7.2 (Apache-2.0), so it is ~1.1 MB instead of
  ~110 KB; `NOTICE` records the bundling. If FlatLaf cannot load, the window falls back to the platform
  look and feel, and if the machine has no display libraries at all the program says so and points at
  `--cli` instead of printing a stack trace.

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
- **`copied` on a job that was cut short is capped by rsync's own completed-transfer count.** rsync
  announces a file before its data moves, so a cancelled job used to report every announced file as
  copied. It now reports "announced N, of which at most M finished", where M comes from rsync's `xfr#`
  counter.
- **After a cancelled or timed-out copy, the engine reports the partial files `rsync` left behind**, by
  name and size (`.big.bin.QafPvi (for big.bin, 3.8 MB)`), or says explicitly that none were left. It
  does not delete them.
- **A job that ran past its deadline can no longer be reported as complete.** The deadline was enforced
  by a watchdog on a 250 ms period, so a phase that finished between two ticks fell through and reported
  `COMPLETED` - and a half-finished verification reported files it had not hashed as `missing`. The check
  now records the timeout itself, in every work loop, and kills a live `rsync` from any thread.
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
- **Headless CLI**: `java -jar file-copy-util.jar --cli --source DIR --dest DIR --all` (or `--files`)
  with the same engine and semantics, plus CSV/JSON export and exit codes
  (0 nothing to report, 1 job failed, 2 bad command line, 3 files mismatched or missing).
- **Dry run** (`--dry-run` in the CLI, *Dry run (write nothing)* in the GUI): runs rsync with
  `--dry-run` and still compares hashes, so you see what the job would copy and what already mismatches
  without writing anything. Its count means "would be copied" and its message says nothing was written.
- **`--bwlimit KB/s`** caps the transfer rate.
### rsync version matrix (Phase 5, Group C)

- **Behaviour is now verified against rsync 3.1.3, 3.2.7, 3.3.0 and 3.4.0**, not only against the one
  version this machine ships. `RsyncVersionMatrixTest` builds the same tree on each binary and requires
  the same counters, dry runs that write nothing, progress that reaches its end, and interrupted jobs that
  claim only the transfers rsync finished. Extra binaries are supplied with `FCU_RSYNC_BINARIES=path,...`;
  CI builds 3.1.3, 3.3.0 and 3.4.0 from source and runs the matrix, so portability is a gate rather than a
  hope.
- **Fixed: an interrupted copy claimed a transfer that never finished.** The engine treated "rsync never
  reported an `xfr#`" as "no information, keep the announcement count". Measured on all four versions, a
  transfer still in flight prints progress fragments that carry no `xfr#` at all, so progress without
  `xfr#` means *nothing completed*: a throttled 4 MB copy cut at 1.5 s reported 1 file copied while the
  destination held 0 complete files. It now reports 0.
- **Interrupted jobs say which evidence existed**: "announced 3 file(s) for copy and completed 1 of them",
  "announced 1 file(s) for copy and completed none of them", or "it reported no progress, so how many
  finished is unknown". "at most N finished" was dropped: when rsync reported a count it was a count, not a
  bound.
- **A dry run cut short is exempt from the cap** and reports "N file(s) would have been copied"; capping a
  run that was never meant to write anything would have reported 0.
- 122 tests.

### Per-side selection made visible (Phase 5, Group B)

- **The window states what its two checkbox lists mean.** Each list now reads `100000 files, 100000
  selected`, and a line under the mode buttons states the work set and the rule that produced it: "source
  ticks only; a copy ignores the destination list", or "100000 ticked at source, 1 unticked at the
  destination (old.bin)". Excluded paths are named while they are few, and never named when they are many,
  because listing 5 of 9000 would imply the rest do not matter.
- **`--exclude-dest a.txt,...`** is the command line's unticked destination row. On a copy it says so
  rather than doing nothing quietly. Naming every selected file is a usage error.
- **The preview uses the engine's own exclusion rule**, `excludedAtDestination`, so it cannot promise a
  work set the job then ignores; a test compares a job's counters with the numbers its preview showed.
- **Measured GUI cost** (100k and 300k hard-linked trees): scan 1.3 s / 7.1 s; Select All 2-3 ms; a single
  row click 0 ms; filter 15-26 ms; 490 MB / 710 MB resident. The first version of the preview recomputed
  the whole work set per click and cost ~245 ms per click at 100k rows; the preview now indexes rows by
  path and tracks unticked rows incrementally, with an engine test that the fast form and the walk-the-list
  form agree.
- Records that held a `List` were storing and returning it directly (SpotBugs `EI_EXPOSE_REP`/`EI_EXPOSE_REP2`);
  both now copy in the canonical constructor.
- 115 tests.

### Scale and static analysis (Phase 5, Group A)

- **Measured on 100k and 300k file trees** (scan ~1 s / ~3.6 s, Verify Only ~2.4 s / ~7 s). Time is linear;
  memory was the problem, so verification now decides one file at a time instead of building a digest map
  per side, and the destination is not scanned at all unless destination-only reporting is on. A 300k-file
  Verify Only used to run out of memory at 256 MB and now completes inside it.
- **Running out of memory is a failed job, not a crash**: it reports what to do (narrow the selection, turn
  off destination-only detection, or raise `-Xmx`) and exits 1.
- **The watchdog no longer dies silently.** A scheduled task that throws is never rescheduled, which would
  have disabled the job deadline for the rest of the engine's life; the tick now survives anything it throws.
- **`--max-results N`** raises the bound on how many individual results a job keeps. Counters were always
  exact; the export could only ever list what was kept, and now that bound is the user's to set.
- **`javac -Xlint:all -Werror` and SpotBugs at Medium run on every `mvn verify`.** Findings it raised and
  what was done: `Path.getFileName()` is null for a root path, so scanning a filesystem root could throw
  (`DirectoryScanner`, `FileSyncEngine`); `JFileChooser.getSelectedFile()` can return null in the export
  path; a format string with literal newlines in the CLI usage; `EI_EXPOSE_REP` on `TailLog.component()`
  is intentional and annotated as such. The dead `concat` helper was removed.
- **`--help` is longer**: examples, what the counters mean, and the measured cost of large trees.
- 109 tests, headless, driven against real `rsync` when it is installed. Tests that kill a transfer
  throttle rsync with `--bwlimit`, so they write a few megabytes instead of gigabytes.

### Fixed

- Path handling: one identity, `FileEntry(relPath, absolutePath)`. The previous absolute-path string
  arithmetic produced wrong relative paths and wrong selections.
- Scanning no longer follows symlinks by default (a symlink cycle used to be a `StackOverflowError`),
  and an unreadable subdirectory is a warning instead of aborting the whole scan.
- The `--files-from` list is written to a temporary file and always deleted, including on failure and
  cancellation. File names containing a newline or NUL are refused instead of corrupting the argument
  stream.

### Packaging

- Coordinates: `com.fileverifier:file-copy-util` (was `com.filecopyutil:file-copy-util`), jar
  `target/file-copy-util-1.1.0.jar`, matching the repository name. Package and groupId follow the code
  (`com.fileverifier`); the GUI window carries the longer product title *File Verifier & Copier*, which is
  a title rather than a coordinate.
- `maven-shade-plugin` removed (the project has no runtime dependency), so there is one jar and no
  `original-*.jar`.
- `maven-compiler-plugin` pinned with `<release>17</release>`, surefire pinned to 3.2.5 (Maven 3.8.x
  defaults to 2.12.4, which cannot run JUnit 5).
- GitHub Actions CI: build + tests + a CLI smoke test against the packaged jar.

## 1.0.0

Initial release: Swing GUI, recursive scan with per-file checkboxes, Copy & Verify via `rsync`,
Verify Only, SHA-256 hashing, log pane and progress bar. No tests, no CI, no license.
