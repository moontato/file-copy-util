# Audit remediation plan — File Copy Util (revision 14)

**Date:** 2026-10-21, status updated 2026-10-23
**Input:** `AUDIT.md` (A01–A15 + hardening), owner review of revisions 2 and 3.
**Status:** approved and under way, revision 12 — **Phase 4 accepted by the owner** (the reviewed scope: `fbb9b61`,
`33737ab`, `da6b124`; the evidence wording of the close/admission fixture corrected in `eee48d0`), and **Phase 5 is
under way**: §2.13's symlink-verdict rule (item 1) and the §2.13 counter-truth rule (item 2) are implemented and
green, item 3 (report completeness) is implemented and green, and item 4 (CSV formula neutralisation) is
implemented and green — which closes the one hardening item the changelog had been carrying as "not done". Item 5
(`--strict` and invalid-pattern warnings) follows. Item 2 changes what two published figures *mean* (`copied`, `checked`) — the change is stated in
DESIGN §20 and the changelog, and the expectations that encoded the old meanings were rewritten to the approved
ones rather than loosened. Phase 6 responsiveness stays out of scope, as agreed. Earlier: Phase 4's job-ownership work was corrected twice: first for four lifecycle holes (closing disarming the watchdog; a stopped job with no counters having no report at all; admission racing terminal publication; a forced completion running on the watchdog) and for cancellation not stopping hashing; then for the remaining blocker from the next review, **a close that could miss a job being admitted**. The earlier four are confirmed fixed by the reviewer's own probes; the close-vs-admission race is closed by putting admission and closing in one critical section. Phase 4 remains awaiting acceptance. Phase 0a, Phase 0b, Phase 1, **Phase 2 (write safety, including the five
corrections from your source review)**, the two Phase 2 closeout items (publication that cannot replace, and alias-aware
report targets), **Phase 3 (selection authority, with collision acceptance bound to the job)** plus its export-pairing
correction, and the **Phase 4 remainder (job ownership: per-job abort controller, bounded terminal delivery, shutdown-hook
ownership, cancellation causality, and the measured completion-evidence protocol)** are implemented and green on this
machine, along with the part of Phase 4 that gates 1.5.0 (per-file transfer records for interrupted copy jobs) and the
matrix-isolation follow-up. Phase 0b's workflows are written and reviewed but **have not been run on
GitHub**, so its gate is specified rather than demonstrated. Matrix status is qualified as agreed: local evidence for
3.1.3 and 3.4.0, GitHub coverage pending for all four and local for 3.2.7/3.3.0. Next: your review of the Phase 4
checkpoint below; then Phase 5 (the scheduled §2.13 hardening, including CSV formula-character neutralisation) only on
go-ahead. Branch `remediation/audit`; `main` is held at the approved baseline commit `0069285`, `pom.xml` is still
1.4.0, and no merge, push, tag, version bump or release is authorised by this checkpoint.

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
- **Measured follow-up (2026-10-22), which changes the shape of the choice: `--progress` puts the name
  back.** `rsync -a --checksum --itemize-changes --progress --out-format='%i|%b|%n'` prints, for each
  transfer, a header line carrying the name before any progress for that file - `#secret`, ` lead`,
  `sub/b.txt`, then `>f+++++++++|45|#secret` - on 3.1.3 and 3.4.0. `--info=copy1` with `--progress` gives
  the same headers. So Option B no longer has to infer the in-flight name from a hidden temporary file:
  `--progress` supplies the start name, `%b` supplies completion, and the hidden temporary name is demoted
  to a display hint, never a correctness claim. 3.2.7 and 3.3.0 are **not** confirmed for this yet; the
  validate workflow's four-version matrix is where they will be.

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

The collision report is computed before `writeFilesFrom` and surfaced as a pre-transfer event, so it appears before any byte moves — **stated as notification, not prevention**. To *start* a copy whose work set contains names that would collide (case-fold/NFC), the job additionally requires `--allow-collision-names` (CLI) or a confirmation in the window. The message says the names may collide on some filesystems and that proceeding is not proof the transfer is safe. Read-only verification never needs the acknowledgement.

**The acceptance belongs to one job** (Phase 3). `CollisionAcceptance` is the collision signature *plus* a job identity: source directory, destination directory, job type and the sorted relative names of the work set. An acceptance counts only when both the names and the job match, so the same relative pair under other roots, and the same roots with a different selection, are different questions and are asked again. The window answers by calling `acceptDetectedCollisions()`, which grants exactly what the last job refused, and then re-runs **the request it had already captured** — not a fresh request assembled from the lists as they now stand. `acceptAnyCollisions()` remains the CLI's deliberately wider waiver (`--allow-collision-names` was typed for that run). The question text is `FileVerifierView.collisionCopyQuestion(names)`: it names the files and does not claim they are identical, because whether two names are one file is a property of the destination volume.

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
| **0b validation parity** *(A11)* — **implemented; GitHub-side execution not yet verified** (no authorised run yet) | reusable validation workflow (matrix 3.1.3 / 3.2.7 / 3.3.0 / 3.4.0, SpotBugs, lint, CLI smoke); `release.yml` depends on a successful validation run **for the same commit**; actions SHA-pinned; rsync tarballs checksum-verified; per-tag concurrency | a tag cannot be published unless that commit passed validation; the matrix runs four versions |
| **1. names exact** — **done** | §2.1, §2.2, §2.3 *observations*, §2.9, §2.10 | decoder round-trip tests green on all four rsyncs, both locales; A02/A05 fixtures fail-before/pass-after; no selected name can vanish without a warning and a nonzero exit; **no completion claims are made or required at this phase** |
| **2. write-safety** — **done, after a correction round** | §2.6 (split rules, validated before the job, published without replace), A01 overlap refusal scoped to copying, A04 containment across the path **including Verify Only**, §2.11 collisions with acceptance bound to the named set | 26 `WriteSafetyTest` fixtures with fail-before/pass-after for every guard: 4 failed with overlap containment off, 10 with containment off, 3 with export-over-existing restored, 1 with the in-tree rule off, 2 with collision refusal off, 2 with `ATOMIC_MOVE` restored, 5 with CLI pre-validation removed, 2 with Verify Only containment off, 4 with the final link resolved; source digests unchanged asserted in every copy and CLI fixture |
| **3. selection authority** | scan identity + live selection state, one immutable `JobRequest` captured at Run, single stale-options policy (refuse) | the audit's race refuses instead of running; checkbox, Select All/Deselect All, and destination-exclusion edits after scanning are honoured; out-of-order scans cannot install |
| **4. lifecycle + completion attribution** — *partly brought forward:* the §2.5 per-file transfer record is done, §2.7 is not | §2.7, §2.5 | interrupted-overwrite regression green (`UNCERTAIN`, not `COPIED`); SIGTERM-to-Java test passes the five properties; bounded cancel latency during a single large hash; blocked-I/O reported honestly; reuse after cancel, rejection after `close()`; timeout vs cancel wording |
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

## 6. Status and next actions

**Done**

- **A1 checkpoint:** `0069285 Checkpoint the audited 1.4.0 tree (approved as A1)` - the audited source,
  tests and documentation, no tag, no push. Fresh validation for that commit: 176 tests, 0 failures,
  0 errors, 0 skipped; SpotBugs 0 findings; `BUILD SUCCESS`. `main` is held at this commit.
- **Red-first regressions** were committed on `remediation/audit` as `95a3374` (suite deliberately red:
  183 tests, 7 failures, all in the two new files) before any fix, so each fix has a before/after record.
- **Phase 0b** landed as `b0deb72`: a reusable `validate.yml` (Maven verify, CLI smoke tests on the shaded
  jar, and an rsync matrix built from checksum-pinned tarballs of 3.1.3, 3.2.7, 3.3.0 and 3.4.0 - the four
  are required, not requested), called by `ci.yml` for branches and by `release.yml` **for the exact commit
  the tag names**, which also refuses when the tag does not resolve and when the jar's own version does not
  match the tag. Third-party actions are pinned by commit SHA. No tag was pushed and no release made.
- **Phase 1** landed, name fidelity: `RsyncNameCodec` (first separator splits, `%n` last, only octal
  `\#ooo` decoded, bytes reassembled then decoded once, ordinary backslashes untouched) replaces the old
  parser; the list handed to rsync is NUL-delimited (`--from0`) with every entry prefixed `./`, which
  defeats the comment/option syntax of a file list; `normalize()` no longer trims, so `" a "` cannot become
  `a`; engine mention lines are requested with `--out-format='%i|%n'`; names are escaped where they are
  shown (log lines, CLI per-file lines, warning name lists, Results cells) and stay raw in exports; and the
  CLI gained `--files-from`, `--files-from0`, `--exclude-from`, `--exclude-from0`, `--exclude-dest-from`
  and `--exclude-dest-from0` with the delimiter fixed by the option name, no guessing, and undecodable
  bytes refused by name rather than replacement-decoded.
  The two policy tests that asserted refusal of newline/CR names were rewritten to the new contract, and
  the old normalization test now asserts that syntax is rewritten while a name's own spaces are not.
- **Part of Phase 4, brought forward** because `InterruptedOverwriteTest` gates 1.5.0: `TransferLedger`
  records the destination before rsync starts and `transferFacts()` decides each announced file's
  disposition per §2.5 rules 1-4; copy-only jobs emit one row per announced file named by that
  disposition, a verifying job stopped before verification emits `PENDING` rows, and `FileOutcome` carries
  transfer and verdict separately. The interrupted-overwrite fixture is green: the completed file is
  `COPIED`, the interrupted same-size overwrite is `UNCERTAIN` with its bytes and inode unchanged, and an
  unselected destination file is untouched. Its interruption trigger is the hidden temporary file appearing
  in the destination - an observation of the destination, not of a parsed output line - so it does not
  depend on the §2.5A A/B choice.
- **Suite now:** 189 tests, 0 failures, 0 errors, 0 skipped; SpotBugs 0 findings; `mvn clean verify`
  succeeds with `-Werror`. Nothing is still red.

**Source review of the first remediation commits, and what was fixed (2026-10-22)**

Five implementation gaps found by reading the code, each fixed with a regression case:

| Gap | Fix | Regression |
| --- | --- | --- |
| `Cli.readNameList` split the line channel on regex `\R`, which also ends a line at vertical tab, form feed, NEL, U+2028 and U+2029: `foo\u000Bbar` became the two selections `foo` and `bar` | the delimiter is defined as a line feed; a CR ends a line only when **every** line in the file ends CRLF; a mixed file is refused with a pointer to `--files-from0`; nothing else is a line ending | `theNewlineChannelSplitsOnLineFeedsOnly` (each of the five characters, parse level) and `aNameContainingATabLikeControlCharacterIsNotSplitIntoTheFilesBesideIt` (end to end, with `foo`, `bar`, `baz` present in the source as decoys and required absent from the destination), plus `aNameEndingInACarriageReturnIsRefusedInALineChannelRatherThanGuessedAt` (mixed refused, whole-file CRLF accepted, NUL channel carries it) |
| the legacy space-separated itemize form still dropped a name's leading space (`>f+++++++++  a ` → `a `) and treated a pipe inside a name as the column boundary (`>f+++++++++ pipe|name`) | the delimiter is the **first** separator after the itemize column - the itemize column contains neither a space nor a pipe - and exactly one character is removed | `theParserStillReadsTheFormWithoutAnOutFormat`, now `assertAll` with the two failing shapes and `>f+++++++++  ` (a name that is one space) |
| `TransferLedger` derived the expected kind from `Files.size()`, which follows a source symlink, so a completed link could fall to `UNCERTAIN`; and a change of object kind alone was accepted as replacement evidence | the ledger now records what the job *intended* - source kind, source size and link text read from the source **before** the transfer, not measured afterwards - and compares a created link by target text; a created regular file by expected size; and a replacement only by a changed `(dev, ino)` identity. No kind-change fallback | `aCompletedSymlinkInAnInterruptedJobIsCopiedNotUncertain` (link completed before the interrupted overwrite: `COPIED`, with the link text checked, while the overwrite stays `UNCERTAIN`), `aFileCreatedBeforeTheInterruptionIsConfirmedByTheSnapshot`, `aSnapshotCutShortByCancellationNamesItselfAndConfirmsNothingForTheRest` |
| CSV and JSON serialised only kind, path and digests, so a verifying job stopped before its comparison exported `PENDING` for a completed transfer and an unknown one identically | CSV gains `transfer` and `verification` columns appended after the existing four (order and meaning of the first four unchanged); JSON gains `transfer` and `verification` fields; the Results table gains a `Transfer` column, also last | `twoPendingRowsWithDifferentTransfersExportDifferently` and `aCopyOnlyRowExportsItsTransferAndSaysNoComparisonWasMade`, both asserting the emitted CSV lines and JSON text, not the in-memory records |
| the four-version CI job ran only `RsyncVersionMatrixTest`, and the safety fixtures therefore tested the runner's packaged rsync; the workflow ran `mvn verify` while the recorded command was `mvn clean verify` | `FCU_RSYNC` pins the binary a whole run uses (and `TestSupport` plus `RsyncVersionMatrixTest` refuse to run when a pinned binary cannot run, instead of skipping every rsync test); the matrix job now checks each built binary reports the version it was built as, then runs the safety fixtures once per binary with that binary pinned, with `-Dsurefire.failIfNoSpecifiedTests=true`; `mvn -B -ntp clean verify` | local equivalent run: `FCU_RSYNC=…/rsync-3.1.3/rsync mvn test -Dtest='RsyncVersionMatrixTest,AuditNameFidelityTest,InterruptedOverwriteTest,FileSyncEngineTest,CancellationAndProcessTreeTest,CollisionAndRaceTest,RobustnessTest,VerificationOptionalTest,CliTest'` → 121 tests, 0 failures; the same set pinned to 3.4.0 → green; `FCU_RSYNC=/nonexistent` → the run errors out rather than skipping |

**Ledger cost, measured before any scale claim was restated** (one container, local filesystem; the numbers
are this environment's, not a bound): the pre-copy snapshot took **0.8 s for 100k selected files** when the
destination was empty and **0.5 s** when it already held every file, adding **13-19 MB** of heap; **2.5 s and
38 MB for 300k files**. Peak RSS during a 100k-file copy-only job: about **410 MB**. The snapshot loop is
cancellable (checked every 256 entries): Cancel during it stops the job, the files it had not reached are
named as unconfirmable, and that is stated in the warnings. README now says plainly that metadata latency on
a network mount can be several times higher, and no automatic size threshold was added - attribution is not
silently weakened for large jobs.

**Phase 2 — write safety (2026-10-22)**

Landed on `remediation/audit`; `main` untouched, `pom.xml` still 1.4.0, nothing tagged or pushed.

| Item | What the code now does | Fixture, and the sabotage that proves it |
| --- | --- | --- |
| A01 overlap | Refused in preflight, in both directions, and against the directory a path *lands on* as well as the path as given: destination inside source, source inside destination, same directory through a link, and source reached through a link inside the destination ("Source is reached through the destination directory … by way of …") | `WriteSafetyTest`: `aCopyWhoseDestinationContainsTheSourceIsRefusedBeforeAnythingIsWritten`, `aCopyIntoASubdirectoryOfTheSourceIsRefusedTheOtherWayRoundToo`, `aDestinationThatIsALinkToTheSourceIsRefusedAsTheSameDirectory`, `aSourceReachedThroughALinkInsideTheDestinationIsRefusedToo`. Switching the four containment branches off turns all four red (16 run, 4 failed); restoring them turns them green |
| A04 containment | **Corrected after your source review.** Every name the job was asked to handle is checked against its own tree, **for Verify Only as well as for copying** - the audit's actual escape was a comparison reading `dst/sub/a.txt` through `dst/sub -> /tmp/outside` and offering the outside file's digest as the backup copy's, and containment is now enforced before any digest is taken. The directories above the final component are resolved (deepest existing ancestor, remainder re-attached); **the final component is never resolved**, because a link there is an object the job treats as an object. Measured, not assumed: `rsync -a --checksum` against a destination whose `a.txt` was a symlink outside replaced the link with the copied file and left the target's contents untouched - so resolving the leaf would have refused lawful jobs, including copying a link whose target lives elsewhere and copying twice into a link the first copy created. Overlap refusals are scoped to jobs that write | `aSelectedPathThatClimbsOutOfTheDestinationIsRefusedBeforeWriting`, `aContainedLookingNameThatEscapesThroughALinkedDirectoryIsRefusedAsWell`, `verifyOnlyIsHeldToTheSameContainmentRuleEvenThoughItWritesNothingForever`, `aVerifyOnlyComparisonReachedThroughALinkedDestinationDirectoryIsRefused` (the audit shape, digest names asserted), `aVerifyOnlyJobIsNotRefusedMerelyBecauseItsTreesOverlap`, `aLinkCopiedAsALinkIsNotRefusedBecauseItsTargetPointsElsewhere` (absolute link target outside the source, then the same copy again so the link it created is accepted), `aBrandNewFileIsNotCreatedThroughALinkedDestinationDirectory`, `aSymlinkAtTheEndOfASelectedPathIsAnObjectNotARoute` (link replaced; target's contents and source unchanged). Containment off: 16 run, 10 failed; Verify Only containment off: 24 run, 2 failed; final component forced to resolve: 4 failed |
| §2.6 export | **Corrected after your source review.** Existing path refused (file, directory or link) with no override; a *new* report inside either tree needs `--allow-report-in-tree` or a GUI confirmation; no directory created for a report. Publication is **not** `ATOMIC_MOVE`: OpenJDK's Unix `MoveFile` for a plain atomic move calls `rename`, which may replace an existing target. Content is staged by `Files.createTempFile` in the **report's own directory** (a hard link cannot cross a filesystem) and published with `Files.createLink`, which cannot replace a name that appeared in the interval, and whose name already holds the complete report. Where hard links are unavailable (FAT, exFAT, some network and FUSE mounts) the report is **refused**: the first fallback — `Files.createFile` then `Files.copy(... REPLACE_EXISTING)` — was removed, because the copy could overwrite another writer's file through a name this program had just created, and left the report name present half-written while the copy ran. **Report paths are validated before the engine runs**, so an existing or forbidden target, a missing parent directory, `--csv` and `--json` naming one path — compared by resolved directory plus name rather than by string, so two spellings through a linked directory count as one target, or an un-allowed in-tree report is a usage error (**2**) and nothing is copied; a target taken *during* the run is caught at publication and that run exits **4** | `aReportIsNeverWrittenOverAFileTheJobIsProtecting`, `aReportInsideTheCopiedTreesNeedsAnExplicitDecisionAndSaysWhy`, `evenInsideTheTreesAnExistingFileIsNeverOverwritten`, `bothFormatsAreRefusedTheSameWayAndNeitherCreatesTheDirectoryItWasToldToWriteIn`, `aReportTargetThatIsALinkOrADirectoryIsRefusedRatherThanFollowed`. Writing over an existing target again (old behaviour): 43 run, 3 failed; in-tree rule off: 1 failed; `aReportPathTakenBySomeoneElseBetweenCheckAndPublicationIsLeftAlone` and `aReportPathTakenBySomeoneElseIsLeftAloneEvenWithoutHardLinks` (target created after validation: its contents **and inode** survive, no temporary left), `twoFormatsNamingOnePathAreRefusedBeforeTheTransferRuns`, `aReportPathTheJobItselfTakesDuringTheRunIsRefusedAfterwardsAndExitsFour` (the copy creates `dest/report.csv`; export refuses, the copied file keeps its contents, exit 4). Publication reverted to `Files.move(... ATOMIC_MOVE ...)`: 24 run, 2 failed; CLI pre-validation removed: 53 run, 5 failed |
| §2.11 collisions | A copy refuses until the caller accepts it (`--allow-collision-names`, or *Copy them anyway?* in the window), writes nothing, and keeps the warning after acceptance. **The window's acceptance is bound to the collision set it was shown** - `acknowledgeCollisions(names)` stores that set, and the confirmation is a seam (`CollisionConfirmation`) so its lifetime is testable without a dialog; `acceptAnyCollisions()` stays the CLI's explicit wider waiver because the flag was typed for that run. Verify Only and dry runs still just report the pairs | `aCopyThatFindsCollidingNamesRefusesUntilSomebodyAcceptsIt`, `aDryRunStillShowsWhatCollidingNamesWouldDo`, `verifyOnlyNamesCollidingFilesWithoutRefusingOrAsking`, and `CollisionAndRaceTest.namesThatDifferOnlyByCaseAreNamedAsARisk` rewritten to the new contract, `anAcceptedCollisionSetCoversThatSetAndNoOther` (refused, engine names the pair it found, accepting those names lets that job through, a **different** pair in the same directory is refused again), and `FileVerifierViewTest.acceptingOneCollisionSetDoesNotWaiveTheNextOne` (two questions asked, no licence carried for the session). Refusal switched off: 21 run, 2 failed; panel restored to window-wide acceptance: 15 run, 1 failed |
| Source immutability | `FileSyncEngineTest` (48 fixtures) and `CliTest` (27 fixtures) now assert the source tree is digest-for-digest unchanged after every run; a fixture that means to change the source must call the differently-named helper | the check is in the shared helpers, so a copy that damages its source fails even when the destination looks right; `TestSupport.digests`/`assertUnchanged` are the shared builders and mark unreadable fixtures as `<unreadable>` rather than skipping them |

Contract changes were recorded rather than smoothed over: CLI fixtures that used to write their report inside the
destination now use a separate reports directory (the refusal they hit is the new rule, tested in `WriteSafetyTest`,
and it is now expected at exit 2 before the job rather than 4 after it); the collision test that used to expect
"warning and a completed job" now expects a refusal followed by an accepted re-run; and the 1.2.1 behaviour of
**reporting** a symbolic link at the destination instead of reading it was preserved - the containment fix must not
turn that legal case into a refusal, which is what the first attempt did and what the final-component rule corrects.

Phase 2 validation: `mvn clean verify` with `FCU_RSYNC_BINARIES` = 3.1.3 + 3.4.0 - **232 tests, 0 failures, 0
errors, 1 skipped**, `BugInstance size is 0`, `-Werror` clean. Whole-suite runs pinned to one binary with
`FCU_RSYNC_BINARIES` cleared: **222 tests, 0 failures, 0 skipped** against 3.1.3 and the same against 3.4.0. The one
skip in the matrix run is the pinned-binary test, which means nothing when no binary is pinned - the single
conditional skip you accepted.

**Matrix-isolation follow-up (2026-10-22)**

`FCU_RSYNC` pinned the process default, and then six tests put the literal `"rsync"` back - not the value
they replaced - so everything after them in the same JVM silently ran against the binary on PATH while the
run was reported as pinned. Fixed by scoping the binary to the engine that needs it (`useRsync`, used by all
four matrix cases and four engine cases), by `TestSupport.withRsyncBinary` restoring exactly what it replaced
where a process-wide override is unavoidable, and by three regressions (`anOverridePutsBackExactlyTheBinaryItReplaced`,
`aFakeRsyncTestLeavesTheConfiguredBinaryToTheTestsAfterIt`, `aWholeRunPinnedToABinaryKeepsThatBinary`). Proof
they bite: restoring the old habit in `TestSupport` makes two of them fail while pinned to 3.1.3. CI clears
`FCU_RSYNC_BINARIES` for each per-version fixture run; the all-four run remains its own step. Pinned runs on
this machine: 124 tests green against 3.1.3 and 124 against 3.4.0, each with `FCU_RSYNC_BINARIES` cleared.

**Suite now:** 217 tests, 0 failures, 0 errors, 1 skipped (the `FCU_RSYNC`-specific test, which only means
something in a pinned run); SpotBugs 0; `-Werror` clean; `mvn -B -ntp clean verify` succeeds.

**Still open from Phase 0b:** the workflows are written and reviewed statically (jobs, step wiring, pinned
action SHAs, checksum-pinned tarballs), but **no GitHub Actions run has been executed or observed** - that
needs an authorised push. Until then the validation gate is *specified*, not *demonstrated*.

**Phase 2 closeout (2026-10-22)** — committed as `6ff14f3`, then completed by Phase 3

| Item | Outcome |
| --- | --- |
| 1. publication that cannot replace | The `Files.createFile` + `Files.copy(..., REPLACE_EXISTING)` fallback is **gone**: a filesystem without hard links gets a refusal naming the reason, not a weaker publication. Content is staged by `Files.createTempFile` in the report's own directory; publication is `Files.createLink`, which cannot take a name that appeared in the interval; `justBeforePublishing` and `justAfterPublication` seams and an injectable `Publication` make the interval testable. Javadoc corrected: staging is the report's directory, not the system temporary directory |
| 2. two spellings, one target | `--csv`/`--json` compare the **resolved directory plus the final name** (`ReportExporter.publicationName`) with `Files.isSameFile` as a second opinion, so `reports/real/result` and `reports/alias/result` (alias a symlink) are one target, refused before the copy; `publicationName` also resolves the directory portion of the report path, so a report path whose directory link leaves both trees is judged where it lands, and one that lands inside them is still held to the in-tree rule. Publication-time validation stays: a path that changes during the run is caught at publication (exit 4) |
| 3. collision acceptance lifetime | Carried into Phase 3 and done there (see below) |

Fixtures added with the closeout: `aFilesystemThatCannotHardLinkIsRefusedRatherThanGivenAWeakerPromise`,
`aReportNameAppearsAlreadyHoldingTheWholeReport`,
`aReportReplacedAfterPublicationIsLeftAloneBecauseOnlyOurOwnTemporaryIsRemoved`,
`twoSpellingsOfOneReportPathThroughALinkedDirectoryAreRefusedBeforeTheCopy`,
`aReportPathInsideALinkedDirectoryIsJudgedByWhereItActuallyLands`. Publication reverted to a
replace-allowing copy: 3 failures; the alias check reverted to lexical comparison: 2 failures; report directory not
resolved: 1 failure. Validation: **232 tests, 0 failures, 0 errors, 1 skipped**, SpotBugs 0, `-Werror` clean.

**Phase 3 — selection authority (2026-10-22)**

*The window decides from one scan, and an acceptance belongs to one job.*

- **A scan has an identity.** `LoadedScan` = generation number + source root + destination root + `ScanOptions` +
  row counts. The live ticks belong to that scan. `scanDirectories()` bumps the generation, and a completion
  carrying an older number is **discarded** by `finishScan(...)`, counted in `discardedScanCount()`, and logged - a
  silently dropped result is indistinguishable from a scan that found nothing.
- **Run is refused when the window is not answering for anything.** `runRefusal()` returns the reason;
  `runRequested()` logs it as well as showing it (the log is the part testable without a display, and the part that
  survives the dialog being closed). Four reasons: no scan yet; a scan in flight; a path field edited since the
  scan (which clears the loaded scan - the rows belonged to the directory that used to be named there); scan
  options changed since the scan.
- **One immutable request per job.** Run builds a single `JobRequest` with roots taken from the *scan* and the
  ticks as of that instant; `startRequest(request)` and everything downstream (poller, results, report export,
  collision answer) use it. Ticking rows during a job does not change the job in flight.
- **"Scanning" follows the count of scans in flight** (`inFlightScans`), so a discarded scan cannot leave the
  window stuck saying it is scanning, and cannot clear the flag under a newer scan that has not finished.
- **Collision acceptance is bound to the job it was granted for** (§2.11 above): `CollisionAcceptance(jobIdentity,
  collisionSignature)`, `jobIdentity` = roots + job type + sorted work-set names; `acceptDetectedCollisions()`
  grants exactly the last refusal; `lastCollisionRefusal` records it. The window re-runs the captured request.

Fixtures (`FileVerifierViewTest`, now 21 tests): `runIsRefusedUntilTheDirectoriesHaveBeenScanned`,
`aScanStillUnderwayCannotBeRunAgainst`, `editingATypedPathTakesTheLoadedListsWithIt`,
`changingTheScanOptionsAfterAScanRefusesTheRunUntilTheScanIsRedone`,
`aScanResultCarryingAnOlderScanNumberIsDiscardedAndSaidSo`,
`aRunningJobKeepsTheSelectionThatWasTickedWhenRunWasPressed`,
`anAcceptedCollisionSetCoversTheJobItWasGivenForAndNoOther` (asked, answered no, refused; asked again for the same
job, answered yes, completed; the same relative pair under **other roots** and a **larger selection** are still
refused; the next pair `c.TXT`/`c.txt` is asked about again). New seams: `FileVerifierView.Scanner`
(`setScannerForTesting`), `applyFinishedScan(...)`, `currentScanGeneration()`, `runRequested()`, `requestInUse()`,
`scanInUse()`, `discardedScanCount()`, `loadedSourceRows()`, `engineInUse()`; `FileSyncEngine.jobIdentityFor(...)`,
`collisionAcceptanceCovers(jobIdentity, names)`, `acceptDetectedCollisions()`.

Sabotage evidence: Run's authority check removed - 5 of 21 fail; the stale-scan discard disabled - 2 fail;
acceptance not bound to the job - 2 fail across `FileVerifierViewTest`, `WriteSafetyTest` and
`CollisionAndRaceTest`. Restoring each guard returns green; the whole view suite ran green three times in a row
(it is the suite's most timing-sensitive file, so it was repeated deliberately).

**Limitations Phase 3 does not remove, on purpose:** the check is *before* the copy, not a lock - a directory
re-pointed by another process between the check and `rsync` is the same cross-process race already recorded for
containment, and cross-process locking stays declined. A scan is not re-run automatically, so the remedy for every
refusal is to press Scan Files; that is a decision you approved. The GUI confirmation and the in-tree report
confirmation are exercised through seams, not as rendered dialogs (no display in this container).

**Phase 3 correction, from your review of `335ef2d` (2026-10-22)**

The claim "results, export … all use that object" was not true: `exportReport` still passed
`userPath(sourcePathField.getText().trim())` and the destination field to `ReportExporter`, so the export rules
were applied to whatever the fields said at press time rather than to the directories the job worked in. Fixed by
pairing a finished result with the request that produced it:

- `record CompletedJob(JobRequest request, JobResult result)`; `finishJob(request, result)` is called from the
  completion callback, which **names its own request** - deliberately not a `runningRequest`/`pendingRequest`
  field, because a later request refused by preflight would otherwise re-point an older job's report;
- results rendering and `writeCompletedReport(target, format, allowInsideDataTrees)` read the pair. The chooser
  only chooses a path; the policy is in the seam, so it is testable without showing a file chooser;
- two consequences recorded as behaviour, not accidents: a finished job's rows survive a path-field edit (a job
  that ran is not un-run by typing elsewhere), and a request refused by preflight returns before `engine.start`
  and leaves the pairing alone;
- dialogs are headless-safe (`inform(...)` catches `HeadlessException`; the chooser logs and tells the user to use
  `--csv`/`--json`). A refusal that cannot be displayed must not crash the window.

Fixtures: `aReportOfACompletedJobIsJudgedByTheDirectoriesThatJobWorkedIn` - a report among the copied files is
still refused after the fields move elsewhere, **and** a path inside the newly typed directories is written
because it is outside the job that ran (judging by the fields would refuse that in the other direction);
`aRequestThatNeverStartedDoesNotRepointTheReportOfTheJobThatRan` - after a preflight refusal, the pairing is the
same object (`assertSame`) and the report is still refused among the first job's files. Sabotage: export reading
the fields again - **2 failures**. `FileVerifierViewTest` is now 23.

Claim kept precise, as you asked: the deterministic seam establishes the **stale-result rule** (a completion
carrying an older generation is discarded), not every possible `SwingWorker` interleaving. A latch-controlled
worker integration test is listed as possible follow-up work, and a flaky race test is not a gate.

**Suite now:** **251 tests, 0 failures, 0 errors, 1 skipped** (the `FCU_RSYNC`-specific test, which only means
something in a pinned run); SpotBugs `BugInstance size is 0`; `javac -Xlint:all -Werror` clean;
`mvn -B -ntp clean verify` succeeds. Whole suite pinned to one binary with `FCU_RSYNC_BINARIES` cleared:
**245 green** against 3.1.3 and **245 green** against 3.4.0.

---

**Item 4, CSV formula neutralisation (§2.13) — implemented, green.**

Red first: `aNameThatASpreadsheetWouldEvaluateIsDisarmedInTheCsvAndStaysRawEverywhereElse` and
`theCsvFieldRuleLeavesALoneFormulaCharacterAlone` failed against the quoting-only `csvField` (6 suppressed
assertions between them); the window fixture `aNameThatLooksLikeASpreadsheetFormulaIsStillShownAsTheNameItIs` passed
before and after, because the table was never the format at risk.

- `csvField` prefixes `'` to a field whose first character is `=`, `+`, `-`, `@`, tab or CR. The job's `# message`
  line goes through the same rule.
- Only the CSV. Names in JSON, in the window's table, and in every comparison the program makes stay raw — an
  apostrophe in a filename would be a different file. A non-leading `=` is ordinary text.
- The class comment that told the next reader "do not describe this format as safe to open in a spreadsheet until
  it is" is replaced by what the rule now is.

## Phase 5 — truthful verdicts and counts (items 1–4 green)

**Item 3, a report that cannot look complete when it is not (§2.8, §2.13) — implemented, green.**

Red first: `theCsvNamesTheJobItDescribesAndSaysWhetherItsRowsAreTheWholeJob`, `aReportThatHoldsEveryRowSaysSo`,
`theJsonSaysWhenItsRowsAreOnlyPartOfTheJob` and `aCroppedResultSaysSoWhereTheRowsAreShown` were written against the
old exports and failed before the code changed; they pass now.

| Change | Why |
|---|---|
| `JobResult.rowsOmitted`, and `truncated()` derived from it | a report that names rows it left out cannot also claim to be whole; the old stored boolean could disagree with the list |
| the reporter counts rows whether or not the cap let them in | the omitted figure has to exist for a capped job, which is exactly the job that needs it |
| a forced terminal report states `counters.rowCount()` rows omitted | the abandoned thread had per-file rows; the report that replaced it holds none, and says how many are missing rather than only "some are missing" |
| CSV metadata block before the columns | `status`, `abortCause`, `message`, `truncated`, `rowsListed`, `rowsOmitted`, every count, `rsyncExitCode`, and the error/warning counts — a stopped or capped CSV no longer reads as a complete record |
| JSON gains `truncated`, `rowsListed`, `rowsOmitted` (already had `status`, `errors`, `warnings`) | the machine-readable form states the same facts as the human one |
| Results tab `Results (3 of 12)` and a log line in the window; `note:` line in the CLI | the cue belongs where the rows are being read, not only in a warning list that scrolls away |

**Format change, stated:** the CSV columns row is no longer the first line. Readers must skip `#` lines. The row
format itself is unchanged, and the change is in the changelog and README, not only in the code.

Three existing expectations were re-expressed rather than weakened: `csvHasAHeaderAndOneRowPerOutcomeWithFullDigests`
and `twoPendingRowsWithDifferentTransfersExportDifferently` now address rows through a `dataRows` helper (rows after
the columns row) and additionally assert the metadata; `anEmptyJobExportsAHeaderOnlyCsv` asserts the metadata says
FAILED with one error and that **no** data rows follow; `maxResultsBoundsTheExportedRowsButNotTheCounters` now
asserts three data rows plus `# truncated,true`, `# rowsListed,3`, `# rowsOmitted,2` and `# checked,5`, which is the
same promise stated with numbers instead of a line count.

Suite: **280 tests, 0 failures, 0 errors, 1 skipped** (matrix over 3.1.3 and 3.4.0), SpotBugs 0; **274 green** pinned
to 3.1.3. `aForcedReportNamesTheRowsItLeftOut` covers the one figure the mutations first found uncovered: a report
written for a thread that would not come back has to state the rows it left out.

Still to do in Phase 5: item 4 (CSV formula neutralisation, documented as a CSV-only transformation, JSON keeping raw identity) and then item 5 (`--strict` and invalid-pattern warnings that never invent a verdict). Item 4 is implemented and green, documented in the section above.

**Item 2, the `checked` equation and the separation of transfer figures (§2.13) — implemented, green,
`CounterTruthTest` (5 fixtures) plus the equation asserted in the existing engine, CLI and export fixtures.**

What was still broken: `filesCopied` was rsync's announcement count capped by `xfr#`, and `filesChecked` was the
reported-row count, extras included — so the extra that audit A14 counted as checked was still counted as checked
in the headline figure, and a cut-short job claimed copies it could not support.

| Figure | What it counts now |
|---|---|
| `checked` | `matched + mismatched + missing`, **derived**, so it cannot disagree with the verdicts |
| `extra` | destination-only rows: reported, never compared, therefore not in `checked` |
| `processed` | rows the job reported on (extras included) — the progress unit, reported beside the rest |
| `copied` | files whose transfer this job can show: disposition `COPIED`, tallied per file from the disposition map, so the `--max-results` cap on the row list cannot change the figure |
| `wouldCopy` | dry-run intent; a dry run's `copied` is 0 |
| `uncertain` | transfers rsync started that the destination cannot account for |
| `notTransferred` | rows the job knows about that were not transferred |
| `transfersAnnounced` | itemize lines seen — rsync started N transfers |
| `transfersCompletedByRsync` | rsync's own `(xfr#N)` claim, kept as an observation instead of becoming the copied figure |

Consumers now state the same figures: the CLI prints a second totals line (`transfers: …`), the window logs the same
line under its Totals, and the JSON export carries `checked`, `matched`, `mismatched`, `missing`, `extra`, `copied`,
`wouldCopy`, `uncertain`, `notTransferred`, `transfersAnnounced`, `transfersCompletedByRsync` and `processed`, plus
`truncated` (item 3's flag, needed by the same fixtures).

**The contract change, stated rather than buried.** A cut-short copy that used to report `copied=1` on rsync's word
now reports `copied=0` with `uncertain=1` and `rsync-said-completed=1` when the destination cannot show which file
arrived. Where a file really was created at the size its source had, it is still counted — that case is now proven
by a fixture with a stand-in that writes one file for real and then fails the way rsync does on a bad write
(`TestSupport.copyingThenFailingRsync`).

Six expectations encoded the old meanings and were rewritten to the approved ones, each with a comment saying why:
`destinationOnlyFilesAreReportedAsExtraWhenDetectionIsOn` (checked excludes the extra, processed counts both rows,
and the equation assertion no longer adds `extra` to `checked`), `dryRunReportsWhatWouldCopyAndWritesNothing` and
`aDryRunCutShortStillReportsWhatItWouldHaveCopied` (would-copy, and copied 0),
`aCutShortCopyReportsTransfersRsyncFinishedNotOnesItAnnounced` and
`aCutShortCopyThatCleanedUpAfterItselfSaysSoInsteadOfWarning` (rsync's claim and announcement kept as their own
figures; copied 0 because the stand-in wrote nothing), and the renamed
`anRsyncFailureMidCopyKeepsRsyncsClaimAndNamesWhatCannotBeProven` plus
`aFailedCopyNamesTheHalfWrittenFileItLeft` (the 1.2.1 lesson — rsync's claim is not hidden, leftovers are named —
asserted without claiming an unsupported copy). No assertion was deleted or weakened to make a new code path pass.

A second fixture on the same rule found a live defect while it was being written
(`aCappedResultListStillReportsTheFiguresForTheWholeJob`, `dff1450`): the announced-name list was itself capped by
the results limit, so with `--max-results 1` a three-file copy reported `copied=1` — the exact "counters stay exact
whatever the cap is" promise, broken. Announced names are now collected in full and only the reported rows are
capped; the warning that says the list is short is unchanged.

Suite after item 2: **275 tests, 0 failures, 0 errors, 1 skipped** (matrix over 3.1.3 and 3.4.0), SpotBugs 0.
Pinned single-binary runs: **269 green** pinned to 3.1.3 and **269 green** pinned to 3.4.0 (five full pinned-3.4.0
runs and four targeted repeats since item 2) — but one earlier pinned-3.4.0 run reported **1 failure whose identity
was not captured** and which has not reproduced in the runs since. Recorded here as an open loose end rather than smoothed over:
it will be caught by the release-gate run, and if it recurs the fixture gets fixed or re-specified.

Mutation coverage for item 2 — again in a disposable `git worktree` at `dff1450`, with both trees verified clean at
that commit afterwards (worktree `HEAD` and status printed before removal).

| Mutation | Caught? |
|---|---|
| `checked` returns the row count again | yes — 3 fixtures: the extras fixture in `CounterTruthTest`, the same rule in `FileSyncEngineTest`, and the JSON totals in `ReportExporterTest` |
| completed jobs report announcements as `copied` | yes — 2 dry-run fixtures (`CounterTruthTest`, `FileSyncEngineTest`) |
| announced-name list re-capped by `--max-results` | yes — the fixture written for it, and only that fixture: it is a one-line regression with a one-line guard |
| JSON drops the `wouldCopy` figure | yes — the JSON export fixture |
| CLI omits the `transfers:` totals line | yes — the end-to-end CLI fixture |
| transfer figures never tallied at all (`tally = NONE`) | yes — 37 fixtures, which is the measure of how much of the suite reads these figures |
| the ledger's dry-run early return removed | **not observable.** The engine builds no ledger for a dry run (`if (!dryRun)` around the snapshot), so `transferFacts`' own dry-run branch decides first and the ledger's check sits on a path no dry run reaches. Recorded as a second guard, not as covered behaviour. |

Mutation coverage for item 3, in a disposable `git worktree` at `d420419` (and the last line re-run at `66c6650`),
with both trees verified clean at the intended commit before the worktree was removed.

| Mutation | Caught? |
|---|---|
| `truncated()` always false | yes — 5 fixtures: the CSV block, the JSON, the capped-counters fixture, the window's tab title, the CLI note |
| `rowsOmitted` always 0 | yes — 2 fixtures: capped counters and the CLI `--max-results` export |
| the CSV metadata block removed | yes — 7 fixtures plus 1 error, across the exporter and both CLI export fixtures |
| JSON stops carrying `rowsListed` / `rowsOmitted` | yes — the JSON completeness fixture, and one engine fixture that reads the export incidentally |
| the window drops the `of 10` from the Results tab title | yes — the window fixture |
| a forced terminal report claims 0 rows omitted | **not caught at first.** The mutation found the gap; `aForcedReportNamesTheRowsItLeftOut` (`66c6650`) closes it, and the same mutation is then caught by that fixture alone. |

**Item 1, symlink verdicts (§2.13) — implemented, green, `SymlinkVerdictTest` (8 fixtures).**

- **LINK judges the object:** kind first, then link text via `readLink`, **no digest of anything**. Different link
  texts are a `MISMATCH` with a warning quoting both sides.
- **Kind changes are named:** a source link whose destination is a regular file, and a source file whose destination
  is a link, are mismatches *in kind*; the warning says which object became which, and no hash appears on the row.
- **The scanner carries what it used to drop** (under LINK): a link to a directory — listed, never descended
  through — and a **dangling** link. `linkPolicyCarriesADirectoryLinkAndCopiesItAsALink` and
  `linkPolicyCarriesADanglingLinkAndJudgesItByItsText` lock both; the dangling one also asserts it is *not* reported
  missing, and that its verdict row carries no hashes.
- **Nothing is read through a link at the destination, under any policy.**
  `aRegularFileWhoseDestinationIsALinkIsJudgedWithoutReadingThroughTheLink` runs the same fixture under LINK and SKIP
  with the link pointing at a file outside both roots, and requires `sourceHash == destHash == null`.
- **FOLLOW says what it did:** bytes through a source link, with a warning naming the files whose comparison ran
  through a link (`followPolicyComparesBytesThroughTheLinkAndSaysSo`).
- **A kind change is reported before the transfer**, from the pre-copy ledger (`TransferLedger.kindChanges()`),
  because a copy erases the evidence — `aLinkChangedUnderTheJobIsReportedAsAKindChangeNotAsUnreadable` also asserts
  the `copied=1, checked=1, matched=1` triple that Phase 5 item 2 generalises.
- **SKIP is unchanged** (`aLinkUnderSkipIsNotInTheJobAtAll`).

One committed behaviour changed, deliberately and per §2.13: `ScanOptionsTest.linkPolicyDoesNotDescendIntoSymlinkedDirectories`
used to expect a directory link to be *skipped* (`skippedLinks=1`); it now expects it to be **carried as an object**
(`skippedLinks=0`) while still not being descended into. The "does not descend" half of that promise is still asserted.

Red-first, as required: all 7 behaviour fixtures failed before the code changed (the SKIP fixture passed, being the
unchanged case), and pass after. Suite: **269 tests, 0 failures, 0 errors, 1 skipped**, SpotBugs 0.

Mutation coverage for item 1 — and the new process for it. **Mutations now run in a disposable `git worktree`** at
the commit under test, never in the working tree, and the restoration is verified against the intended baseline
before a green run is accepted (`HEAD` and a clean status in both trees afterwards). That closes the stale-restore
failure mode that bit this work twice: the mutation copy can no longer overwrite a live file, and the baseline is a
commit rather than a hand-refreshed backup.

| Mutation | Caught? |
|---|---|
| scanner drops directory links and dangling links under LINK again | yes — 2 fixtures (directory link, dangling link) |
| destination-link branch removed (falls through to hashing) | yes — 4 fixtures, including the outside-the-roots link whose row must carry no digests |
| source-link-under-LINK branch removed (falls through to hashing) | yes — the kind-change fixture names the digests it must not have |
| pre-transfer kind-change warning from the ledger not emitted | yes — the fixture that needs the warning **before** the copy erases the evidence |
| FOLLOW "compared by bytes" warning not emitted | yes — the FOLLOW fixture |

Still to do in Phase 5: item 2 (the `checked` equation asserted in engine, GUI, CLI and exports, with transfer
dispositions kept separate), item 3 (partial/capped reports: status, truncation, counters and errors exposed), item 4
(CSV formula neutralisation, documented as a CSV-only transformation, JSON keeping raw identity), item 5
(`--strict` and invalid-pattern warnings that never invent a verdict).

## Phase 4 checkpoint — who owns a running job (implemented, green, awaiting your acceptance)

The five things the phase asked for, and what each is now:

**1. One abort controller per job.** `FileSyncEngine.JobControl` holds that job's generation, deadline, cancel
flag, stop reason, its `rsync` process and that process's tree, its destination reservation, its published
counters and its own thread. Nothing about a job is kept engine-wide any more, and nothing is reset for the next
job. Every loop (`pumpRsyncOutput`, `verifyEntries`, `verdict`, `hashFiles`, the ledger snapshot, the copy phase)
holds the controller of the job it is working on.

**2. An old worker cannot see, stop, or rewrite a later job.** Publication goes through
`currentControl.compareAndSet(control, null)`: a result from a job the engine has let go is counted in
`lateResultsDropped()` and otherwise discarded — it cannot replace the next job's status, result, or destination
reservation. The destination reservation lives in the controller and is released at most once, by whoever
releases it first. A worker can only terminate the process its own controller started, which is what keeps a
thread that woke up late from killing the job that followed it.

**3. Terminal delivery is bounded.** The engine releases itself (idle, reservation gone) **before** the
completion callback runs, so a blocked callback cannot leave it reporting `RUNNING`. Jobs run on their own
thread instead of a shared serial queue — measured: with the queue, a job abandoned inside a caller's callback
blocked the next job from ever starting. If a job thread does not return within 5 s of its process tree being
killed, the watchdog publishes that job's own last-published counters as a stopped job, says plainly that the
per-file rows from that thread are missing, and lets go. Terminating a job also closes `rsync`'s output streams,
so a reader cannot be held open by a process that left the tree.

**4. Shutdown-hook ownership.** `closeOnce()` is idempotent and bounded; `close()` delegates to it; the window's
`shutdown()` calls it; `Cli` installs a shutdown hook running the same cleanup and removes it once the job is
over, and both the install and the removal are exercised by a test (`installEngineCleanup` returns the thread
because a `Thread` cannot be asked what it was told to run). Without that hook, a signal reaching only the JVM
left `rsync` and its children reading the source after the program exited.

**5. Causality stays three facts.** `JobResult.abortCause()` is `USER_CANCELLED`, `TIMED_OUT` or
`ENGINE_CLOSED`; the window's status line, the log heading (`=== JOB STOPPED ===`) and the message wording
differ per cause. `ReportExporterTest`'s existing 11-argument constructor calls still compile through a
compatibility constructor, so no test lost its assertion to accommodate the new field.

**Cancellation latency (measured, 300 MB throttled transfer in flight, `cancel()` → completion callback):**
**500–610 ms** on rsync 3.4.0 and on 3.1.3 (three runs each after the corrections below: 510/505/502 ms and
611/504/603 ms; before them, 412/402/403 and 413/403/503 — the wait is dominated by giving `rsync` time to die
after `SIGTERM`, which the completion-evidence probe showed happens within a second). A job whose own thread never
returns is reported stopped about **5.5 s** after its process tree was killed (5 s grace + the watchdog's 500 ms
tick).

**Owned-process cleanup (measured):** a cancelled copy, a closed engine, a completed copy **and now a timed-out
copy** each leave no `rsync` process running (`TestSupport.assertNoProcessesRunning`, and the timeout case is a
new fixture — previously only the other three were checked). A cancelled mid-transfer job also leaves no hidden
partial file behind, which is `rsync`'s own SIGTERM behaviour.

**Timeout wording:** *"Job stopped on timeout: it passed the 1.5 s limit this job was given, while copying. …
The files themselves were not reported as different by this stop."* plus the standing warning to raise or clear
the limit. The word "timeout" is kept so a log can be searched for it; the human duration (`900 ms`, `45.0 s`,
`5 min 3 s`) replaces the bare millisecond count. The GUI says "Job stopped: it passed the time limit." A
timeout never says "cancelled", and a cancellation never says "timeout" — asserted in both directions.

**Start/completion output protocol (measured, then decided):** the program keeps
`--info=progress2` + `--out-format='%i|%n'`, where the itemize line arrives **before** that file's progress, so a
name proves rsync started the file. The alternative (`--progress` + `'%i|%b|%n'`) was measured on 3.1.3 and 3.4.0:
the itemize line moves **after** that file's 100% line (a per-file completion record) and the bare file name
appears on its own line before its progress (a start name). Two assumptions were disproved by measuring: `%b` is
the bytes put on the wire for that transfer, **not** the file size (a 4-byte file reported 47; 4 MiB reported
4,194,855), so it cannot be checked against the expected size; and no `--info` variant (`name1`, `name0`,
`copy1`) produces a per-file start header alongside a whole-transfer progress line, so the whole-transfer bar and
a per-file start name do not come from one option set. Two measurements keep UNCERTAIN permanent: a SIGTERM'd
run exits **20**, prints start + progress but no completion line for the cut-short file, and deletes its hidden
temporary; and **a reader that stops reading does not stop rsync** — it finished every file with no error at all,
so the absence of a completion line is never evidence nothing was written, and stopping a job must mean
signalling the process tree rather than closing the reader. Decision: keep protocol A, because per-file completion
is already decided from the pre-transfer ledger and switching would trade the whole-transfer progress figure, plus
a protocol change through every fake-rsync fixture, for evidence the ledger already obtains conservatively.
Option B remains available and its exact shape is written in DESIGN §18; 3.2.7/3.3.0 confirmation is still owed
by the CI matrix either way.

**Tests, and whether they bite.** `JobOwnershipTest` adds 11 fixtures (three causes, distinguishability,
reuse-after-cancel on the same destination, engine idle before the completion callback, timeout leaves no
process, `closeOnce` idempotent and prompt, hook registered/removed and the hook's work, an abandoned thread
neither blocking nor rewriting the following job, and an abandoned job not handing back a destination another
job is using). Each deliberately reintroduced defect was checked to fail: removing the publication gate → 2
failures; disabling the terminal guard → 2 failures; collapsing the abort causes → 4 failures. The fourth
mutation tried (releasing the destination before the gate) is **not observable** through the suite, because the
reservation now lives in the job's own controller and is cleared at most once — recorded here rather than hidden,
with the outsider-refusal fixture as the lock on the consequence.

### What an independent review found in that work, and what it took

All four were real, all four are fixed, and each now has a fixture:

| Hole | How it shows | Closure |
|---|---|---|
| Closing disarmed the mechanism that finishes an abandoned job | `closeOnce()` shut the watchdog down before terminalising, so a job parked inside a caller's callback stayed reported `RUNNING` with no result; the CLI exited without printing a summary and without `EXIT_ENGINE` | close terminates the job, gives its own thread a bounded moment, then publishes the stopped job itself before retiring the watchdog — and delivers that completion off its own thread, with a bounded wait |
| A stopped job with no counters had no report at all | the terminal guard returned early when the progress reporter was null, which is normal during validation, the directory walk and the destination snapshot | the job's controller answers for its counters (`counters()`, zeros before any reporter exists); the forced result names the phase the job was in |
| Admission raced terminal publication | the winner of the publication compare-and-set reopened admission before writing `lastResult`/`status`, so a job admitted in between could have its `RUNNING` stamped over with the older job's `FAILED` | admission and terminal publication share one `lifecycleLock`; the completed state is written while admission is closed and admission reopens last; no listener callback and no process operation is inside the lock |
| Forced completion ran the listener on the watchdog | a listener entitled to block occupied the watchdog, so the **next** job's time limit was never enforced — measured before the fix as that job's hung `rsync` running to its own `exit 137` with cause `NONE` | delivery is chosen per publisher: on the job's own thread normally, fire-and-forget from the watchdog, briefly waited from a program on its way out |

The same review found that **cancellation did not stop hashing**: the pools, futures and open reads belonged to
nobody, `hashFiles` waited on an unbounded `Future.get()`, and `FileHasher.computeHash` never looked for a
cancellation. Fixed by giving the job controller ownership of its hashing (`adopt(...)`, `stopHashing()` reached
from the one terminate path), checking for interruption between digest buffers, distinguishing a cut short hash
(`FileHasher.HashAborted`, reported as a warning naming the stopped phase) from an unreadable file, and refusing to
invent a verdict for a comparison that was interrupted. A read that ignores interruption — a stalled mount, an
unreadable device — is now reported out loud ("*A read of a source file is still running after being asked to stop,
and cannot be interrupted*"), and that is the only case where forced reporting remains.

Mutation coverage for this round, with the honest result of each:

| Mutation | Caught? |
|---|---|
| `stopHashing()` removed from the terminate path | yes — 2 fixtures (source hashing, verification) |
| interruption check removed from the digest loop | yes — the same 2, but only after the fixture was resized to a 40 GiB sparse file with a 3 s bound; at 8 GiB the read simply finished first, which is a reminder that a timing fixture has to be sized against the thing it checks |
| close's terminal publication removed | yes — `closingTheProgramWhileACallbackIsBlockedStillEndsThatJob` |
| forced completion delivered on the watchdog | yes — `aForcedCompletionWithABlockedCallbackStillEnforcesTheNextJobsDeadline`, which is also the fixture that failed on the first un-fixed run and so found the hole |
| pre-fix admission ordering restored, window widened | yes — `anAdmittedJobIsNeverLeftDescribingItselfAsIdleByAnEarlierJob`, first round |
| abort guard in `verdict()` removed | **not observable** — the futures are cancelled before their results could be collected, so that guard is defence in depth, not a tested branch; recorded rather than counted |

Two coverage limits stated plainly:

- the forced-report path for a job with no progress reporter is covered at the outcome level (a job cancelled
  mid-listing reports zero figures and the right cause) rather than by forcing the grace branch, because every
  stage before the reporter finishes well inside the grace in this container;
- the admission fixture is a contention invariant — the natural window is nanoseconds, so it is a guard against
  re-ordering, not a schedule-forcing test.

**Suite now:** **257 tests, 0 failures, 0 errors, 1 skipped**; SpotBugs `BugInstance size is 0`; build success.
Whole suite pinned to one binary with `FCU_RSYNC_BINARIES` cleared: **251 green** against 3.1.3 and **251 green**
against 3.4.0. The count moved 245 → 251 with the first Phase 4 round and 251 → 257 with these corrections.
Protocol decision unchanged: **Option A**, with Option B's shape recorded and 3.2.7/3.3.0 still owed by the matrix.

### The remaining blocker from the next review: closing could miss a job being admitted

Reproduced independently: `execute()` checked `closed` outside the lock that admits a job and never rechecked it in
the way that mattered, and `closeOnce()` did not enter that lock at all. Holding the lifecycle lock with reflection
and releasing it after `close()` returned showed the sequence plainly — close returned with no controller to stop
and the watchdog retired, then `execute()` admitted the job and **copied**: `status=COMPLETED`, `abortCause=NONE`,
`copied after close=true`. Stale status would have been bad enough; this is writing destination data after the
program closed.

The correction is exactly the one required, and no redesign: **one critical section covers asking permission,
claiming the engine and installing the controller, and marking the engine closed while capturing the controller
closing must stop** (`admit(...)` and `closeOnce()` both under `lifecycleLock`). Termination, thread joins and
listener callbacks stay outside it. The two acceptable endings are the two the review named:

- **close wins** → admission is refused (`execute()` throws, `start()` returns false), nothing is copied;
- **admission wins** → close captured that controller and stops it (`abortCause=ENGINE_CLOSED`, no `rsync` left).

There is no third outcome. Two supporting invariants came with it: a job that loses the publication race gives its
**destination claim** back (a claim left by a job that no longer runs would shut that directory forever), and a job
already asked to stop while being admitted does not reach the destination or claim a reservation the terminal result
has already published past — the message is the same *"before rsync started"* wording used elsewhere, so one fact
has one phrasing.

Fixtures (4 new; `JobOwnershipTest` 17 → 21):

| Fixture | What it locks |
|---|---|
| `closingTakesItsTurnAtTheSameLockThatAdmitsAJob` | the mechanism: with the lifecycle section held elsewhere, `close()` is found `BLOCKED` behind it — a close that walked past the section fails here, deterministically |
| `aCloseMarkedBeforeAJobIsAskedForRefusesThatJob` | close-wins outcome for both `execute()` and `start()`, with nothing written |
| `aCloseAfterAJobIsRunningStopsThatJobInsteadOfMissingIt` | admission-wins outcome: `ENGINE_CLOSED`, file not written, no `rsync` survives, **and the same destination is usable by the next engine** (claim handed back) |
| `aJobAskedForWhileTheProgramIsClosingIsEitherRefusedOrStopped` | 40 rounds of the reproduced race, with a **fake-`rsync` stand-in**: every round ends refused or stopped, never admitted behind a returned close, never with a stranded destination claim (`destinationClaimsInUse()`, a package seam added for exactly this). What it does **not** do is physically copy a file - see the coverage note below |

Mutation results for this round, disclosed:

| Mutation | Caught? |
|---|---|
| `closeOnce()` walks past the lifecycle section again | **yes** — 2 fixtures; the contention one reproduced the reviewer's failure text ("`close()` had already returned with nothing copied, and the job then copied anyway") |
| `execute()`'s checks moved outside the section (the exact old shape) | **yes** — by the mechanism fixture; the contention fixture did not land in that build's 40 rounds, which is why the mechanism fixture is the real lock |
| the stop-check before claiming the destination reservation, removed | not observable on its own — the other guard alone suffices |
| the dropped-result destination release, removed | not observable on its own — the same |
| **both** of those guards removed together | **yes** — "the job in round 0 left its destination claim behind" |

Coverage of this round, stated plainly: the 40-round close-versus-admission fixture runs against a
**fake-`rsync` stand-in**, so it evidences admission, result status and claim cleanup only. Its stand-in never
writes a destination file, so it is **not** 40 rounds of real destination-write evidence — the evidence that no
copy happens after `close()` returned is the source correction plus the reviewer's independent real-`rsync`
scheduling probe (`copied after close=false`). The other two limits from the previous round stand unchanged: the
forced-report path for a job with no progress reporter is covered at outcome level only, and contention fixtures
are guards against re-ordering rather than schedule-forcing tests.

Counts: matrix `clean verify` **261 tests, 0 failures, 0 errors, 1 skipped**, SpotBugs **0**; whole suite pinned to
3.1.3 **255 green**, pinned to 3.4.0 **255 green** (251 → 255 pinned, 257 → 261 in the matrix run with the four new
fixtures).

**Still open after this phase**

1. Phase 0b's workflows have still never run on GitHub: an authorised push is needed before the validation gate
   can be called demonstrated, and before the four-version matrix is evidenced beyond 3.1.3/3.4.0 locally.
2. §2.13 hardening, including **CSV formula-character neutralisation** (the exporter still only quotes; the class
   comments say so).
3. The §2.5A Option-B protocol switch, if you want per-file completion names instead of ledger-derived ones.
4. GUI dialogs are exercised through seams, not rendered dialogs; cross-process locking stays declined by you, so
   the check-then-`rsync` race across processes stays documented rather than closed.
5. Nothing here is released: `pom.xml` is 1.4.0, `main` is still `0069285`, no tag or push. 1.5.0 needs your
   separate go-ahead (A10) after the merge gate is green.
