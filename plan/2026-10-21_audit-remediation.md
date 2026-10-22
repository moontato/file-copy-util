# Audit remediation plan — File Copy Util (revision 3)

**Date:** 2026-10-21
**Input:** `AUDIT.md` (A01–A15 + hardening), owner review of revision 2.
**Status:** for sign-off. Nothing implemented; nothing committed.

## Revision log (v4, from your second review)

| Your point | Change |
| --- | --- |
| 1. drop the stat-tuple / content-sample fallback as proof | **Accepted.** §2.5 now has one conservative rule: without reliable file-identity evidence an interrupted transfer stays `UNCERTAIN`. Metadata tuples and 64-byte samples are diagnostics only and can never produce `COPIED`; copy-only jobs gain no content reads. The sole-writer assumption is stated where the two attribution rules live. |
| 2. narrow "rsync provides no per-file completion output" | **Accepted, and your lead was right.** Measured `--out-format='%i\|%b\|%n'`: the line moves to the **end** of each file's transfer (t=+8.2/16.3/24.4 s for 8.2 s files), on **3.1.3 and 3.4.0**; terminated at 13 s it printed a line **only for the completed file**, and the destination held only that file. Symlinks get `cL…\|0\|name`, dry runs print `\|0\|` and write nothing. So the claim is narrowed to "the configurations tested did not produce one", and §2.5A records `%b` as a candidate validated completion signal - still not proof of atomic replacement or durability, and it costs the start-time announcement that the live "now writing" status depends on (§2.5A). Nothing in phases 1-2 waits on this. |
| 3. separate transfer accounting from verification verdicts | **Accepted.** A file now carries `transferDisposition` **and** `verificationVerdict`; `copied=1, checked=1, matched=1` is expressible again. `UNCHANGED` is gone as a verdict ("already identical" is `NOT_TRANSFERRED` + `OK`). Invariants rewritten to cover dry runs and files never reached by an interruption. §2.3. |
| 4. narrow the exact-input claims | **Accepted.** Newline-delimited lists **cannot represent** a name containing a newline (users are sent to `--files-from0`; no promise of refusing "by name"). Scope is now exact preservation of names representable by the JVM filesystem encoding, with unrepresentable input rejected rather than replacement-character decoded. §2.9, A3/A4 records. |
| small: "no hidden leftovers" in the created-file regression | Removed; leftovers may exist and must be reported accurately. |
| small: explicit CLI shutdown-hook task | Written out as its own task: registration, removal on normal completion, idempotent bounded cleanup, and no second cleanup from `finally`. §2.7. |
| small: a blocking channel close must not stall rsync termination | Abort sequence reordered: terminate the process tree first, then close channels and cancel workers, each with its own bound and on separate steps, so a close that never returns cannot delay termination or terminal-status delivery. §2.7. |
| small: typo "my v3 rule" | Corrected to **revision 2's** rule. |

## Revision log (v3, from your first review)

| Your point | Change |
| --- | --- |
| 1. size + object type do not prove an interrupted transfer completed | **Accepted, and reproduced as your counterexample predicts.** Measured: pre-existing destination file, same size as source, different content; rsync announces it (`>fc........`) and is terminated mid-transfer; afterwards the file is still there at the same size with the **same content digest**. Revision 2's rule (announced + contained + right type + size match) would have called it `COPIED`. Replaced with an **attribution rule**: completion is attributed only by rsync's clean exit (uninterrupted job) or by a recorded before/after change of the destination entry's identity (§2.5). Size/type/containment are supporting checks, never proof. |
| 2. `\#NNN` is octal, not decimal | **Accepted — my text was wrong.** Measured both forms on 3.1.3 and 3.4.0; `\#ooo` is octal (newline → `\#012`, backslash → `\#134`, é → `\#303\#251`), and a bare `\ooo` in a real filename is emitted **unescaped** (`plain\134name` round-trips as `plain\134name`). Decoder spec rewritten: single-pass, only `\#ooo`, ordinary backslashes preserved, byte-level reassembly then one text decode. §2.2. |
| 3. "not announced" is not proof of "unchanged" | **Accepted.** `NOT_ANNOUNCED` is now an observation only; `UNCHANGED` is claimed only where *this program* compared digests. Added terminal `WOULD_COPY` for dry runs, defined exit-23 and exit-24 handling (both measured), separated lifecycle facts from mutually exclusive final outcomes, and fixed my wrong sentence: a created symlink **bumps the announcement count, not `xfr#`**. §2.3. |
| 4. A5 must not allow overriding protection of data | **Accepted.** In-tree exception and protected-data overwrite are now separate rules; the flag/dialog can never permit overwriting a protected dataset file or an alias of one. §2.6, approval A5 rewritten. |
| 5. "files that already existed at the destination are unchanged" is wrong | **Accepted.** Replaced with the four-property version (completed replacements may hold new data; an interrupted replacement must not leave the final path truncated/partial; unrelated files unchanged; remaining partials accounted for). §2.7, and the same distinction is built into the overwrite regression. |
| Smaller: Phase 7 gate depends on Phase 11 accounting | Restructured: Phase 7 makes **observations only** and its gate asserts nothing about completion; per-file completion attribution moved into Phase 10 (interrupted-job accounting belongs with lifecycle). 1.5.0 therefore contains no unimplemented dependency. §3. |
| Smaller: validation parity must precede 1.5.0 | Added as **Phase 0b** (before the release), and Phase 12 keeps only the remaining supply-chain hardening. |
| Smaller: truncated-results wording | "the list below is complete" replaced with wording conditional on the result limit. §2.8. |
| Smaller: exact CLI list delimiter | Explicit convention, no autodetection: `--files-from` = newline-delimited (names containing newlines refused by name), `--files-from0` = NUL-delimited and exact. §2.9. |
| Smaller: supported matrix | 3.3.0 restored; gates run 3.1.3, 3.2.7, 3.3.0, 3.4.0, and CI (which currently builds only 3.1.3/3.2.7/3.4.0) gains 3.3.0 in Phase 0b. |

---

## 0. Reproduction and measurement record

### 0.1 Findings against the built 1.4.0 jar

A01 **reproduced** (`root/src/a` ended up holding the nested replacement, `exit 0`, `2 matched`). A02 **reproduced** (captured `--files-from` literally contained `a`; trims at `FileSyncEngine.normalize()` `:1745` and `itemizePath()` `:1521-1530`; CLI cannot express a whitespace name at `Cli.java:361`; the scanner itself is correct). A03/A08/A12/A13 **confirmed by inspection**. A04 **reproduced** (`exit 0 matched=1` for a destination ancestor symlink escaping the tree). A05 **reproduced** (only `normal` copied, `exit 0`, no warning). A06 **reproduced** (8 GiB single file: cancel returns at once, job finishes **4 207 ms** later; a 300 ms **timeout** reports `Job cancelled before rsync started`). A07 **reproduced** (`kill -TERM` to the Java PID → 3 rsync processes alive 10 s later, `.big.bin.Ugp1sn` left behind). A09 **reproduced** (source link vs destination regular file → `matched`). A10 **reproduced** (`copied=2`, one CSV row, false results-limit warning). A11 **reproduced, including your correction** — successful job (`Job complete: 1 matched`) + unwritable report path → **`exit=0`**, message only on `System.err` from `Cli.write()`; my v1 `exit=1` was a preflight failure from a missing destination directory. A14 **reproduced** (`checked=2` for 1 match + 1 extra). A15 **reproduced** (`NoClassDefFoundError: com/formdev/flatlaf/FlatLightLaf` from view construction on the thin jar).

### 0.2 rsync behaviour that constrains the design (measured on 3.1.3 and 3.4.0)

| Probe | Result | Consequence |
| --- | --- | --- |
| `--from0` with `#secret`, `;semi`, `a` | only `a` copied | `--from0` alone does not defeat comment syntax (you confirmed this in rsync's source). |
| `--from0`, every entry prefixed `./` | 6/6 copied: `./#secret`, `./;semi`, `./-dash`, `./ a `, `./sub/b.txt`, names verbatim | the `./` prefix is the fix, and also defeats a leading `-`. |
| escapes, `--out-format='%i\|%n'` | real newline → `\#012`; control byte 1 → `\#001`; name containing `\#012` → `lit\#134#012end`; name containing a lone backslash → `bs\slash` unchanged; name `plain\134name` → **unchanged**; tab → emitted **raw**; `é` under `LC_ALL=C` → `\#303\#251` | octal `\#ooo` only; bare `\ooo` is literal text; tab must not be trimmed; and escaping of non-ASCII depends on the child's locale (raw UTF-8 under `LC_ALL=C.UTF-8`, measured). |
| `--out-format='%i\|%n\|%L'` with a link whose target contains `\|` | `cL+++++++++\|lnk\| -> pipe\|name` | `%L` breaks the delimiter → do not use it; read link text with `Files.readSymbolicLink`. |
| name containing `\|`, `%n` as the **last** field | `>f+++++++++\|pipe\|name` | splitting at the **first** `|` and taking the remainder is unambiguous only if `%n` is last. |
| `--info=copy1`, `copy2`, `copy2 --itemize-changes` on a throttled local copy | **no extra line**; itemize lines arrive at file **start** (t=0.0 s, 8.1 s for ~8 s files) | **the configurations tested produced no usable per-file completion signal.** Other output configurations were not ruled out - see the next row. |
| `--out-format='%i\|%b\|%n'` (a transfer-statistic escape), 8.2 s files, 3.1.3 **and** 3.4.0 | line moves to the **end** of each transfer (t=+8.2 / 16.3 / 24.4 s); SIGINT at 13 s printed a line for the **completed** file only, and the destination held only that file; symlinks print `cL…\|0\|name`; `--dry-run` prints `\|0\|name` and writes nothing | a per-file completion line **is** available this way. It is a candidate completion signal, not proof of atomic replacement or durability, and asking for it gives up the start-time announcement the live "now writing" status needs. §2.5A. |
| identical second copy | zero itemize lines, `xfr#0`, `exit 0` | count-vs-selected comparisons prove nothing. |
| symlink + its target | `cL+++++++++`, `>f+++++++++`, final `xfr#1` | created symlinks bump the **announcement** count, not `xfr#`. |
| **overwrite interrupted:** destination had same-sized different-content file, terminate mid-transfer | announced `>fc........`; file afterwards: **same inode, same content digest**, mtime unchanged, partial temp gone | a size/type/containment rule reports `COPIED` for a file that was **not** replaced — your counterexample, confirmed. |
| **overwrite completed** (terminated after the rename, and control run) | announced; **`(dev,ino)` changed**, ctime changed, content digest changed | object identity change distinguishes the two cases; mtime alone is **not** a signal (rsync preserves source mtime). |
| empty destination, terminated after file 1 | file 1 present at full size; file 2 absent, no leftovers | "absent before, present after" is attributable; "present after" alone is not. |
| selected name absent from the source at transfer time | `rsync: link_stat "…" failed` + **exit 23**; 3.1.3 prints it before the announcement, 3.4.0 after | vanished inputs are named, and output ordering must not be assumed. |
| `--dry-run` | itemize line emitted, nothing written, `exit 0` | terminal `WOULD_COPY` state; destination evidence must never be applied to a dry run. |
| SIGTERM to a copy parent mid-file | hidden temp removed, destination untouched | supports "an interrupted replacement leaves no truncated final path" as a *requirement we verify*, not an assumption. |

---

## 1. Approvals (decided by the owner on revision 3; scoped, not blanket sign-off)

| ID | Decision |
| --- | --- |
| A1 | **Approved.** One local checkpoint commit of the audited 1.4.0 source, tests and associated documentation, after reviewing the staged diff and a fresh passing validation run. No tag, no push, no release. No generated artifacts, no temporary tools. |
| A2 | **Approved.** Overlapping copy roots refused in both containment directions, resolved aliases included; no promise of future nested-destination support. |
| A3 | **Approved** within the representable-filename scope of §2.9, with the corrected decoder and safe display escaping. |
| A4 | **Approved.** Explicit newline versus NUL channels, no autodetection, with the documentation corrections in §2.9. |
| A5 | **Approved.** Protected-data overwrite is never waivable; only an otherwise safe in-tree report location may be allowed (§2.6). |
| A6 | **Approved.** Acknowledgement required before copying a detected collision candidate; never required for read-only verification; no claim that acknowledgement proves safety. |
| A7 | **Approved.** Exit code 4 for an otherwise successful job whose report failed; job failure takes precedence when both fail; both causes reported. |
| A8 | **Approved**, with the omission conditions listed in §2.12 documented and tested. |
| A9 | **Approved**, with transfer disposition and verification verdict kept separate (§2.3). |
| A10 | **Approved as a schedule only.** Creating any release still needs its gates and a separate go-ahead. |
| A11 | **Approved.** Publishing must depend on successful validation of the exact commit being released. |

Full-plan sign-off is **conditional** on the corrections in this revision (chiefly: no metadata/sample
fallback as proof of completion, and transfer disposition separated from verification verdict).

---

## 2. Design

### 2.1 Identity: names are never re-derived (A02)

1. A scanned `relPath` is final: nothing downstream re-normalizes it. `.trim()` is removed from every path the *identity* travels through (`normalize()`, `itemizePath()`/parser, log formatting).
2. `CanonicalPathSyntax` applies **only** to strings the user typed (`--files`, `--exclude`, `--exclude-dest`): separator translation, leading `/`, leading `./`. It never strips edge whitespace from a name that must match a scanned file.
3. A user-supplied name that canonicalizes onto more than one scanned name → **refuse the job up front** and name both files. Choosing silently is what A02 does today.
4. The GUI's table keys on the scanner's `relPath`; any display elision (long names, control characters) must never be used for identity.

### 2.2 The rsync list and the name decoder (A05, A03 of your review)

**Writing the list:** NUL-delimited + `--from0`, every entry prefixed `./`. Refuse only NUL.

**Reading output:** add `--out-format='%i|%n'` (never `%L`, never anything after `%n`).

**Decoder, single pass over the raw bytes:**
- `\#ooo` → the single byte given by **octal** `ooo` (three digits). Examples measured: `\#012` → newline, `\#001` → byte 1, `\#134` → backslash, `\#303\#251` → é's two UTF-8 bytes.
- a backslash **not** followed by `#` is literal text: `plain\134name`, `bs\slash` decode to themselves. No bare-octal decoding — that would corrupt exactly these names.
- decoded pieces and raw bytes are concatenated as **bytes**, then decoded to text once, using the charset the scan used.
- a name is then checked against the set of names we handed to rsync; a decoded name that matches none of them is reported as an unexpected announcement rather than silently accepted (this is the guard against a decoder bug).
- **locale pinned:** the rsync child gets a UTF-8 locale when one exists (`LC_ALL=C.UTF-8` measured to emit raw UTF-8 names, `LC_ALL=C` measured to escape them). Behaviour must not depend on the user's environment.
- **display, not identity:** control characters, newlines and lone `\#` sequences are escaped only when *shown* in the log, table, or messages; the stored identity stays raw.

Round-trip tests, both rsync versions and both locales: actual newline; literal `\#012`; literal `\134`; a lone backslash; tab; a name containing `|`; a name containing ` -> `; NFC and NFD non-ASCII names.

### 2.3 What happened to a selected file: three separate facts

**Fact 1 - lifecycle observations** (what was seen, never a conclusion)

| Observation | Meaning |
| --- | --- |
| `HANDED_OVER` | the name was in the list given to rsync |
| `REFUSED` | we could not hand it over (after A3: names we cannot represent) - always a warning, always affects the exit |
| `ANNOUNCED` | rsync printed a start-time itemize line naming it (arrival means the transfer **started**; measured) |
| `COMPLETION_REPORTED` | rsync printed a completion-time line naming it (`%b`-style out-format, §2.5A) - not requested by default |
| `NAMED_IN_ERROR` | rsync's own error output named it (e.g. `link_stat … failed`) |
| `JOB_ENDING` | `CLEAN` (exit 0), `WARNING_VANISHED` (24), `RSYNC_ERROR` (any other), `CANCELLED`, `TIMED_OUT`, `CLOSED` |

**Fact 2 - transfer disposition** (exactly one per selected name)

| Disposition | When it is allowed |
| --- | --- |
| `COPIED` | §2.5 attribution holds |
| `WOULD_COPY` | dry run, job ending `CLEAN`/`WARNING_VANISHED`: announced and nothing was written (measured: dry runs announce, print `\|0\|name`, and write nothing) |
| `NOT_TRANSFERRED` | clean job, no completion evidence for it, no error naming it - reported as an observation: "rsync reported nothing to transfer for this file". With `verificationVerdict = OK` this is what "already identical" looks like |
| `VANISHED` | `NAMED_IN_ERROR` with a vanished-file message (exit 23 or 24) |
| `FAILED` | an rsync error named it, or a clean job left an announced name absent at the destination |
| `UNCERTAIN` | announced, job ended `CANCELLED`/`TIMED_OUT`/`CLOSED`/`RSYNC_ERROR`, and §2.5 attribution does not hold. This is also the disposition of a selected file the job never reached, since "not reached" and "reached but incomplete" cannot be told apart from rsync's output |

**Fact 3 - verification verdict** (exactly one per selected or destination-only name)

`OK` / `MISMATCH` / `MISSING` / `EXTRA` (destination-only) / `NOT_CHECKED` (the job compared nothing, or stopped before reaching this name).

`UNCHANGED` is **gone**: matching digests after a copy do not mean the file was unchanged - they mean the destination matches the source now. A file that was copied and then compared reports `COPIED` + `OK` and contributes `copied=1, checked=1, matched=1`, which one shared enum could not express.

**Counters derive from the right field:** `copied` tallies dispositions, `checked`/`matched`/`mismatched`/`missing`/`extra` tally verdicts (§2.13). No counter is a tally of the other field.

**Exit codes defined:** 0 clean; **24** → `COMPLETED` with warnings and every name named as vanished gets `VANISHED`; **23** → `RSYNC_ERROR`: named names get `VANISHED`/`FAILED`, other announced names keep §2.5 attribution, job `FAILED`. Output ordering must not be assumed (measured: 3.1.3 prints `link_stat … failed` before the announcement, 3.4.0 after).

**Invariants asserted in tests** (no count arithmetic): a clean job gives every `HANDED_OVER` name one of `COPIED / WOULD_COPY / NOT_TRANSFERRED / VANISHED / FAILED`; a dry run gives no `COPIED` anywhere; an interrupted job gives every `ANNOUNCED` name one of `COPIED / UNCERTAIN / FAILED`, every non-announced selected name `UNCERTAIN` or `NOT_TRANSFERRED` depending on evidence, and `NOT_CHECKED` for verdicts it never reached; and each summary number equals the tally of its own field.

### 2.4 Implied directories

`filesCopied` counts files and links, never implied directories. Measured in 1.4.0: `cd+++++++++ sub/` did not inflate `copied=3` for a three-file copy. Locked by regression test; **no** new directory outcomes are added.

### 2.5 When may a name be called `COPIED`? (your blocker #1)

Containment under the destination root, expected object kind, and expected size are **supporting**
checks and never sufficient: measured, all four pass for an interrupted overwrite that replaced
nothing (same inode, same content digest, same size).

`COPIED` requires one of:

1. **Uninterrupted job** (`JOB_ENDING = CLEAN` or `WARNING_VANISHED`): the name was announced and no
   error line named it. The basis is rsync's clean exit for what it announced.
2. **A completion report** (§2.5A) naming that exact name, for a job that was not a dry run.
3. **Interrupted job, creation case:** the name did not exist at the destination in the pre-job scan,
   and after the job it exists with the expected object kind and size.
4. **Interrupted job, replacement case:** the name existed before the job and its recorded
   `(dev, ino)` identity changed during the job.

**Conservative rule where identity is unavailable (your correction #1):** if reliable file-identity
evidence is not available - the filesystem reports no meaningful inode numbers, the pre-job record is
missing, or the job was interrupted with no completion report - the name stays **`UNCERTAIN`** unless
some other independently validated completion mechanism (§2.5A) establishes more. Metadata tuples,
mtimes and content samples are **diagnostics only**: they may appear in a warning to help a person
understand what happened, and they can never produce `COPIED`. Copy-only jobs therefore gain no
content reads.

**Sole-writer assumption, stated where it matters:** rules 3 and 4 attribute the change to *this job*
only on the assumption that nothing else was writing to that destination. Cross-process locking is
deliberately absent (your 1.2.2 decision), so on a shared destination these rules show that
*something* replaced the file, not that we did.

Cost of rules 3-4: for copy jobs the engine records, before rsync starts, the `(dev, ino)` identity of
each work-set name that already exists at the destination. One metadata call per existing file; no
content is read.

Message for the unresolved case, naming what is missing: *"rsync was copying it when the job stopped;
the program cannot tell whether it finished."*

**§2.5A - a completion report is available and is worth taking seriously.** Measured on 3.1.3 and
3.4.0 with `--out-format='%i|%b|%n'`: each line appears at the **end** of its file's transfer
(t=+8.2 / 16.3 / 24.4 s for 8.2 s files); with SIGINT at 13 s a line appeared for the **completed**
file only and the destination held only that file; links print `cL…|0|name`; dry runs print
`|0|name` and write nothing. So such a line is a validated per-file completion signal from rsync
itself, and it covers links, which `xfr#` does not.

Its price: asking for the statistic escape moves the **only** line we get to the end of the transfer,
so the live "now writing: X" status from 1.4.0 - the thing that answered the original complaint - loses
its name source. Options, to be settled by measurement in phase 4 rather than by argument now:

- **A.** keep the start-time announcement (status names the in-flight file) and use §2.5 rules 3-4 for
  interrupted jobs;
- **B.** switch to the completion line and derive the in-flight name another way, e.g. by observing the
  hidden temporary file that rsync leaves in the destination while a file is in flight (observed
  repeatedly: `.big.bin.Ugp1sn`, `.a.bin.v7asq2`);
- **C.** both lines from one run, if a format exists that yields a start mention *and* a completion
  mention - `--info=copy1/copy2` combined with `--itemize-changes` was measured and produced no second
  line, so this option is unsupported so far.

A completion report still proves neither atomic replacement nor durability, and phase 4 must measure its
behaviour under interruption and under receiver errors before rule 2 is relied on. Phases 1-3 do not
wait on this question.

**Required regressions (red before implementation)**

- **Interrupted overwrite:** pre-existing, same-sized, different-content destination; cancel mid-transfer
  → disposition `UNCERTAIN`, never `COPIED`; the destination file's content digest unchanged; if a
  *different* file in the same job completed, that one is `COPIED` and holds the new data.
- **Created-file attribution:** empty destination, cancel after the first file → that file `COPIED`, the
  in-flight file `UNCERTAIN` (or `COPIED` only with a completion report), and any partial file left behind
  named in the report.
- **Links:** completion/attribution for a created symlink (`cL` + `readLink` equality + existence).
- **Dry run:** no `COPIED` anywhere; every announced name `WOULD_COPY`.
### 2.6 Export safety (A11), with the protection rule split (your point 4)

Two independent rules:

- **Protection (unconditional).** A report may never be written onto a protected dataset, and never onto anything that resolves to one: the source tree, the destination tree, every work-set file, and any file the job read or verified. **No flag, dialog, or confirmation can waive this.** Aliases are resolved first (`toRealPath()` of the nearest existing ancestor) so a symlinked parent directory cannot route a report onto protected data.
- **Location (waivable).** A report *inside* a source or destination tree, at a path that is not itself protected data, is refused by default and permitted only through `--allow-report-in-tree` (CLI) or the GUI's confirmation dialog. Both front ends share one implementation, so the rules cannot drift.

Plus, as agreed: CSV and JSON naming the same resolved path → usage error before the job; report destinations resolved and rejected during preflight so a long job does not end with a late failure; write to `.part` in the target directory then `ATOMIC_MOVE, REPLACE_EXISTING`, with `AtomicMoveNotSupportedException` falling back to a non-atomic move **only when the target does not already exist** (never silently truncating an existing file); `.part` deleted on every failure path; failures propagated per A7, printed through the injected `err` stream; GUI export off the EDT with a visible failure message.

### 2.7 Cancellation ownership (A06, A07), with your corrected criterion

`AbortController` per job: one reason (`USER_CANCEL`/`TIMED_OUT`/`CLOSED`, first writer wins, so a timeout
is reported as a timeout), a job generation, and a registry of owned resources (process tree, channels,
futures, executors). Abort order matters and is fixed:

1. record the reason;
2. **terminate the owned process tree** (snapshot, SIGTERM, SIGKILL survivors) - this never waits on any
   channel close;
3. close owned channels and cancel futures (`future.cancel(true)`, `executor.shutdownNow()`), each step on
   its own bounded wait, a step that does not return cannot block step 2 or the delivery of the terminal
   result;
4. deliver the terminal status to the listener;
5. await pool termination with its own bound and record any straggler as a warning.

Source hashing and verification use the same per-job executor and registration path; nothing static
survives a job, and a controller is never reset, so an old worker cannot run under a new job's clean
state. `FileHasher` reads through a `ReadableByteChannel` with an abort check every few MiB;
`DirectoryScanner` checks the controller in its visit methods. Every join is bounded; a job with no
deadline still responds to user cancellation.

**CLI shutdown hook - explicit task (A07).** On the CLI path: create the engine, then
`Runtime.addShutdownHook` a thread that calls the engine's abort path and waits for the process tree with a
hard bound (SIGTERM, then SIGKILL survivors), so `kill -TERM <java pid>` cannot leave rsync reading the
source disk. On normal completion `removeShutdownHook` the thread. Both the hook and the ordinary
`finally { engine.close(); }` path go through one idempotent `closeOnce()` on the engine, so the process
tree is never terminated twice and a hook racing with normal exit is harmless. The hook body is bounded
end-to-end and must not depend on the job thread being responsive.

**Acceptance for the SIGTERM-to-Java test - replaced per your point 5.** The test asserts:
1. no process owned by the job is still alive;
2. a replacement that **completed** before the interruption may hold the new data (measured: new inode, new content);
3. a replacement that was **interrupted** did not leave its destination path truncated or partially overwritten (measured: old inode and content intact, temp removed);
4. unrelated destination files are unchanged;
5. every remaining partial file is named in the report (a SIGKILLed rsync leaves its temp behind, and the program says so rather than claiming the destination is clean).

**Stated limit:** on uninterruptible filesystem I/O (a hung NFS/CIFS/SAN mount in the kernel), closing the channel does not release the blocked read. The job then ends with `FAILED`, partial counters, and a warning naming the worker still running; it will not claim the read stopped. SIGKILL of the JVM and power loss are outside any shutdown hook, and the CLI documents that.

### 2.8 Where truth lives (A12), with conditional truncation wording

The engine's outcome list is authoritative, subject to the result limit; the GUI reads it when the job ends. Live progress and log events are display-only: batched, coalesced, droppable, with a suppression notice. Guaranteed events: completion, phase transitions, problem counters.

Wording is conditional on the limit: `Showing 1 000 of 8 412 results (results limit reached - raise --max-results to see more).` and never claims completeness when the list was truncated.

Progress tests: the **latest** status reaches the UI within a bound; completion is never lost; EDT backlog bounded for a fast 10k-file job; a TERM-resistant rsync does not freeze the EDT; "Only problems" toggling is bounded. No test requires every filename to arrive.

### 2.9 Exact CLI name channels (A4), explicit delimiters, honest scope

- `--files-from PATH` / `--exclude-from PATH`: **newline-delimited**. A name containing a newline or CR
  **cannot be represented in this format at all** - a reader cannot tell two lines from one name - so the
  help text says so and sends the user to `--files-from0`. No promise of refusing such a name "by name":
  by the time anything is parsed the information is gone.
- `--files-from0 PATH` / `--exclude-from0 PATH`: **NUL-delimited**, byte-exact, and the supported way to
  name awkward files.
- No autodetection of the delimiter, ever.
- **Representable-name scope (your correction #4):** this program's identity model is Java `Path`/`String`.
  The guarantee is exact preservation of names representable by the JVM's filesystem encoding; input that
  the JVM cannot represent (an undecodable byte sequence under the active encoding) is **rejected with a
  named refusal**, never silently replacement-character decoded. `DirectoryScanner` already skips
  unrepresentable names with the 1.2.1 warning; the new channels must not reintroduce a silent path.
  Arbitrary POSIX filename bytes are therefore **not** claimed as supported end to end.
### 2.10 Display escaping (condition on A3)

Identity stays raw everywhere. Log lines, table cells and dialogs escape control characters, newlines and CRs (a printable stand-in plus the code point) so a filename cannot forge a log line or break a table row. The escaped form is used only for display; exports carry the raw name (JSON) and CSV quoting.

### 2.11 Collisions (A6)

The collision report is computed before `writeFilesFrom` and surfaced as a pre-transfer event, so it appears before any byte moves — **stated as notification, not prevention**. To *start* a copy whose work set contains names that would collide (case-fold/NFC), the job additionally requires `--allow-collisions` (CLI) or a GUI confirmation. The message says the names may collide on some filesystems and that proceeding is not proof the transfer is safe. Read-only verification never needs the acknowledgement.

### 2.12 `--strict` (A8)

Nonzero exit when: an explicitly requested path was absent; a selected name was `REFUSED`; any name ended `VANISHED`; or a report could not be written. Documented list, and `--strict` never turns a `NOT_TRANSFERRED` observation into a failure.

### 2.13 Verdicts and counters (A09, A13, A14)

- One symlink policy in verdicts: under `LINK`, compare object kind first, then link text via `readLink` (no hashing); a link that has become a regular file (either direction) is `MISMATCH` with a warning naming what changed; dangling and directory links are carried as link objects rather than dropped by the scanner. Under `FOLLOW`, content comparison, with the report saying the verdict is about bytes. `SKIP` unchanged.
- Progress: monotonic percentage decoupled from the status channel; a status update fires when the label changes or after a time floor, with a per-job cap (delivery semantics from §2.8).
- Counters: `checked = matched + mismatched + missing` tallied from **verdicts**, `copied`/`wouldCopy`/`uncertain`/`notTransferred` tallied from **dispositions** (§2.3); `filesProcessed` separately in JSON as the progress-unit count. GUI line, CLI totals, CSV metadata and DESIGN §6 all state the same equations, and `copied=1, checked=1, matched=1` is representable for a copied-and-compared file.
- CSV: formula neutralization for `=`, `+`, `-`, `@` in CSV only (JSON keeps raw identity, documented as deliberate); completeness metadata (job status, counters, `truncated`, error count) so a capped or failed report cannot look complete; `ScanOptions` invalid glob patterns produce a warning naming the pattern that was not applied instead of vanishing.

---

## 3. Phases, dependencies, and gates

| Phase | Content | Gate |
| --- | --- | --- |
| **0a baseline** *(A1)* | commit the verified 1.4.0 tree; no tag | suite green (176 tests, SpotBugs 0, lint clean) |
| **0b validation parity** *(A11)* | reusable validation workflow (matrix 3.1.3 / 3.2.7 / 3.3.0 / 3.4.0, SpotBugs, lint, CLI smoke); `release.yml` depends on a successful validation run **for the same commit**; actions SHA-pinned; rsync tarballs checksum-verified; per-tag concurrency | a tag cannot be published unless that commit passed validation; the matrix runs four versions |
| **1. names exact** | §2.1, §2.2, §2.3 *observations*, §2.9, §2.10 | decoder round-trip tests green on all four rsyncs, both locales; A02/A05 fixtures fail-before/pass-after; no selected name can vanish without a warning and a nonzero exit; **no completion claims are made or required at this phase** |
| **2. write-safety** | §2.6 (split rules), A01 overlap refusal, A04 containment across the path, §2.11 collisions | A01/A11 fixtures fail-before/pass-after; source digests unchanged asserted in every copy test; successful-job + failed-report exits 4 with the job half proven successful; ancestor-link case gives `MISMATCH` + warning + nonzero; no `.part` left behind |
| **3. selection authority** | scan identity + live selection state, one immutable `JobRequest` captured at Run, single stale-options policy (refuse) | the audit's race refuses instead of running; checkbox, Select All/Deselect All, and destination-exclusion edits after scanning are honoured; out-of-order scans cannot install |
| **4. lifecycle + completion attribution** | §2.7, §2.5 | interrupted-overwrite regression green (`UNCERTAIN`, not `COPIED`); SIGTERM-to-Java test passes the five properties; bounded cancel latency during a single large hash; blocked-I/O reported honestly; reuse after cancel, rejection after `close()`; timeout vs cancel wording |
| **5. truthful verdicts and counts** | §2.13, §2.4, §2.12 | `checked` equation asserted in every consumer; symlink verdicts; partial-job records; no limit-warning lies; CSV metadata and formula safety tested |
| **6. responsiveness** | §2.8 | EDT backlog bounded; completion never lost with a visible suppression notice; conditional truncation wording |
| **7. packaging claims** | A15 optional theme loading + thin-classpath test; supply-chain hardening left over from 0b | thin-jar test green |
| **8. claims consolidation** | README/DESIGN limits, CHANGELOG roll-up | every claim has a named test or an "untested here" label |

Releases (A10, your conditions met): **1.5.0 = 0b + 1 + 2 + 3**, **1.6.0 = 4 + 5**, **1.7.0 = 6 + 7 + 8**. Safety claims are updated in the CHANGELOG and README **at each release**, not deferred to phase 8.

---

## 4. Fixture harness

One builder in `TestSupport` producing, in one tree: `#secret`, `;semi`, `-dash`, `" leading"`, `"trailing "`, `tab<tab>space`, `a b`, `pipe|name`, `arrow -> target`, `lit\#012end`, `plain\134name`, a real-newline name, a CR name, `quote"and'quote`, `back\slash`, `comma,name`, NFC `éclair`, NFD `éclair`, and a small sparse file. Destination side: same name as regular file / symlink / directory, a symlink at an ancestor, a destination-only extra, a case/normalization collision candidate, and a **pre-existing same-sized different-content file** for the interrupted-overwrite regression.

Rules: capture source digests and sizes before and assert unchanged after; where identity capture matters, snapshot destination stat tuples (`dev`, `ino`, `size`, `mode`, `mtime_ns`, `ctime_ns`) and a 64-byte content sample before the job; assert the exact destination name set, not counts; run every gate against 3.1.3, 3.2.7, 3.3.0, 3.4.0; keep writes to a few hundred MB with `--bwlimit` on anything that copies; run name tests under both `LC_ALL=C` and a UTF-8 locale.

---

## 5. Limits, stated

- The A04 interim fix is check-then-open and **not race-proof** against a concurrent attacker; descriptor-relative traversal is the follow-up, and the interim state is labelled in DESIGN and CHANGELOG.
- Completion attribution (creation and replacement cases) assumes this program is the only writer of the destination (cross-process locking is your deliberate deferral); on a shared destination, "different file object" shows *something* replaced it.
- Filesystems without meaningful inode numbers (some FUSE/SMB/CIFS mounts) simply fall back to `UNCERTAIN` for interrupted transfers; inode stability cannot be verified from this container, and no weaker signal is promoted to proof.
- Uninterruptible kernel I/O can outlive a cancel; SIGKILL and power loss are outside any hook.
- A pre-transfer collision notice is notification; acknowledgement is not proof of safety.
- Case-insensitive and network destinations remain untested here: no such mount exists in this container, and a case-folding test double is not the real thing.

---

## 6. Next actions

1. Your sign-off on this revision, and explicit approvals for A1–A11 (A1 blocks the checkpoint commit only).
2. Red tests first for the two blockers: the decoder round-trip suite, and the interrupted-overwrite regression (`UNCERTAIN`, never `COPIED`) — plus A01, A02, A04, A11 fixtures.
3. Phase 0b, then phases 1 → 3, reporting the before/after reproduction evidence at each step; **1.5.0** cut only on your go-ahead.
