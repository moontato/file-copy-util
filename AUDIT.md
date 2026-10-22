# Project audit — File Copy Util

**Date:** 2026-10-21  
**Target:** current working tree, based on `7cef119fbe60b7908a50f21324fc0c927de096d5`, including the existing uncommitted 1.4.0 changes and untracked tests.  
**Disposition:** fix the high-priority safety findings before treating this as a dependable general-purpose file copier. Ordinary disjoint-directory copies work, but passing tests currently do not establish the advertised selection, isolation, and cancellation guarantees.

> **Note added 2026-10-22, when this file was put under version control.** This is the audit exactly as it
> was performed; findings are numbered A01-A15 and are referenced by those numbers in
> `plan/2026-10-21_audit-remediation.md`, which records the reproduction of each one, the design decisions
> and the phases. One recommendation here is incomplete and is corrected there rather than here: A05's
> "write NUL-delimited names and pass `--from0`" does not by itself fix the case. Measured on rsync 3.1.3
> and 3.4.0, `--from0` keeps `#secret` and `;semi` in the list as **comment** syntax, so they still vanish;
> prefixing every entry with `./` is what defeats it (see plan section 2.2).

No application code was changed. This report is the only added project file. Generated `target/` artifacts were rebuilt. Two bounded subagent reviews were completed **serially**: build/release/test configuration, then UI/preferences/export. Their findings were independently checked; unsupported observations were not adopted.

## Validation and scope

Reviewed the transfer engine, scanning and path model, hashing, process lifecycle, CLI, Swing UI/preferences, reporting, Maven packaging, CI/release workflows, documentation contracts, and relevant tests.

The environment initially lacked Java, Maven, rsync, fonts, and font libraries. Temporary tools/dependencies were downloaded/extracted/built in the session scratch directory, not installed system-wide:

- Temurin JDK 17.0.20.1;
- Maven 3.9.9;
- rsync 3.4.1, locally built;
- temporary fontconfig, FreeType, libpng, and DejaVu font dependencies.

**Fresh validation:** compilation with `-Xlint:all -Werror`, followed by `mvn -B -ntp verify`: **170 tests, 0 failures, 0 errors, 0 skipped; SpotBugs 0 findings; package built successfully.** Earlier attempts had 22 font-environment errors; those disappeared after providing the font runtime. The documented 176-test count is not the count observed in this environment; parameterized rsync tests depend on configured binaries.

Additional probes were run against the freshly built jar and classes using disposable fixtures. They reproduced root overlap/source modification, whitespace selection corruption, comment-prefixed filename omission, destination symlink escape, link/file verification confusion, GUI scan/path races, ineffective hash cancellation, CLI process orphaning, report overwrite/failure behavior, incomplete link reporting, counter inconsistency, and thin-jar theme failure.

**Limits:** Linux/local filesystem only; no interactive desktop session, macOS/Windows shares, case-insensitive filesystem, network-disconnect or power-loss testing. The complete historical rsync version matrix was not rerun. This is a source/runtime audit, not a dependency-CVE certification or proof against adversarial concurrent filesystem mutation.

Severity: **P1** = data-safety/selection/verification issue to fix before relying on broad file-copy guarantees; **P2** = substantive correctness, lifecycle, reporting, or responsiveness problem; hardening suggestions are separated below.

## High-priority findings

### A01 — P1: destination containing source can overwrite source files and report success

**Location:** `src/main/java/com/fileverifier/FileSyncEngine.java:519-527`.

Preflight rejects destination-inside-source, but not source-inside-destination. The latter can map an output onto a different selected input.

**Reproduced fixture:**

```text
root/src/a       = ORIGINAL source a
root/src/src/a   = nested replacement
source = root/src
 destination = root
selection = all
```

Copy & Verify returned exit **0**, `2 matched`, `2 copied`. Afterwards `root/src/a` contained **nested replacement**. Thus a nominal copy modified a source file while verification still reported success: the saved pre-copy digests matched the two output paths, not an invariant that inputs remained untouched.

**Fix:** for copy jobs, reject overlapping canonical roots in either direction, or implement and validate a complete source/output alias analysis before writing. Include symlink aliases and nested paths in regression tests. Verify-only may have a separately defined overlap policy.

### A02 — P1: filename trimming changes the selected file identity

**Location:** `FileSyncEngine.java:1739-1757`; also CLI list parsing in `Cli.java:354-362`.

`normalize()` applies `.trim()` to actual scanned filenames, not merely to user-entered syntax. POSIX names may legitimately begin/end with whitespace. The resulting name can denote a different existing file.

**Reproduced:** source has `" a "` and `"a"`; run `--all --exclude a`. The surviving selected name `" a "` is normalized back to `"a"`. The program copies **the explicitly excluded `a`**, omits `" a "`, and exits **0** with a matching verification result. With only a whitespace-named file plus a normal file, the former is omitted with a warning and the job still exits 0.

**Fix:** preserve exact scanned relative-path identities end to end. Separate CLI syntax parsing from filesystem identity normalization. Test real leading/trailing spaces and tabs, including collisions with trimmed names. The existing `RobustnessTest` fixture `"trailing .txt"` contains an internal space, not trailing whitespace.

### A03 — P1: a completed scan can attach old selections to newly entered directories

**Location:** `FileVerifierView.java:681-729`, `:907-930`, `:1174-1185`.

Scanning captures old paths in a `SwingWorker`, but leaves the directory fields editable. Editing a path clears currently loaded rows; the old worker's `done()` then unconditionally repopulates them. `runJob()` combines those rows with the **current** path fields. It also does not refuse Run while `scanning` is true.

**Reproduced headlessly:** within one EDT turn, start scanning `old/`, then change the source field to `new/`. After completion the field names `new/` but its displayed `a` row came from `old/`. Running copies the unseen `new/a`. No second scan is required.

**Fix:** bind each result to a scan generation and immutable source/destination/options snapshot. Discard obsolete results; prohibit Run during scanning and when displayed rows do not belong to the current snapshot. Alternatively lock relevant controls throughout scanning, with cancellation support.

### A04 — P1: verification follows destination ancestor symlinks outside the destination

**Location:** `FileSyncEngine.java:1230-1246`; `FileHasher.java:22-30`.

Only `Files.isSymbolicLink(destPath)` checks the final component. For `dest/sub/a`, a symlink at `dest/sub` is traversed by hashing. This violates the explicit DESIGN guarantee that nothing outside the destination is read and can give a false backup-verification success.

**Reproduced:** source contains `sub/a`; destination contains only `sub -> ../outside`; `outside/a` has identical data. Default `--verify-only` returned **0**, `1 matched`, although there was no independently stored copy under the destination tree.

**Fix:** enforce the destination containment/symlink policy across the entire path, not just the leaf. Reject unexpected symlinked ancestors and verify resolved containment. For adversarial concurrent modification, use descriptor-relative/no-follow traversal rather than relying exclusively on check-then-open path tests. Test ancestor links, final links, and replacement races.

### A05 — P1: rsync treats selected `#`/`;` filenames as comments

**Location:** `FileSyncEngine.java:1374-1426` (`writeFilesFrom` and command construction).

The selection is a newline-delimited rsync file list. In this format, entries beginning with `#` or `;` are comments. These are legal, scanned filenames; the serializer does not escape or avoid that grammar.

**Reproduced:** select `#secret`, `;secret`, and `normal`. Only `normal` is copied. Copy & Verify reports two missing files; **Copy Only exits 0, reports one copy, and gives no warning that two selected files were omitted**.

**Fix:** write NUL-delimited names and pass `--from0`. This also permits newline/carriage-return names without changing selection semantics. Add copy-only and verified-copy cases for comment prefixes, actual trailing whitespace, newlines, carriage returns, and control characters.

## Lifecycle and correctness findings

### A06 — P2: cancellation/deadlines do not interrupt an active hash

**Location:** `FileHasher.java:22-30`; `FileSyncEngine.java:1142-1178`, `:1322-1365`, `:1769-1786`.

Hashing checks cancellation before tasks, not within the file read loop. The owning job blocks in unbounded `Future.get()`, while the watchdog only terminates rsync. Hash pools are shut down for new submissions but not actively cancelled/awaited on abort. Scanning also has no cancellation/deadline checks.

**Reproduced with a regular sparse 8 GiB file:** a 300 ms deadline was exceeded; the engine remained RUNNING 700 ms into hashing; explicit Cancel did not complete within another 500 ms. The job only returned after about **5.17 seconds**, when hashing finished. Slow disks/network filesystems make the delay much larger; blocked reads can be indefinite.

The final message was **“Job cancelled before rsync started”**, not timeout: the pre-transfer cancellation branch at `:763-770` precedes the timeout branch, while the watchdog sets both flags.

**Fix:** give each job an owned cancellation token and interrupt/close its active hash I/O; check between reads and scan visits; cancel queued futures and terminate/await pools on every exit. Preserve timeout as the causal reason. Test cancellation during a single large file, blocked I/O, scan, verification, close, and immediate engine reuse.

### A07 — P2: terminating the CLI leaves the rsync process tree running

**Location:** `Cli.java:404-416`; `FileVerifierApp.java:10-12`.

`engine.close()` is in a Java `finally`, but JVM termination by SIGTERM does not execute that application's normal stack unwinding. There is no shutdown hook to cancel the owned process tree.

**Reproduced:** launch a bandwidth-limited copy-only CLI job, record its three rsync descendants, send SIGTERM to the **Java PID**. Java exits 143; all three rsync processes remain live afterwards. The probe then explicitly cleaned up its process group.

**Fix:** register/remove a bounded JVM shutdown hook around CLI engine ownership. Ensure normal completion and concurrent shutdown remain idempotent. Test signals to the Java PID, not just `engine.cancel()`. Killing the entire foreground process group is a different scenario; SIGKILL cannot be made hook-safe.

### A08 — P2: changed scan options are used without the promised rescan

**Location:** `FileVerifierView.java:635-640`, `:925-930`.

The UI says “press Scan Files to apply” and sets `scanOptionsStale`, but Run passes `currentScanOptions()` and never checks that flag. The engine rescans with the new settings. Existing visible selections can therefore disappear under new ignores/hidden settings, or a link can change from copying-as-link to following its target without a new displayed scan.

**Evidence:** direct source/control-flow inspection.

**Fix:** retain the options belonging to the displayed scan, or refuse Run until a new scan completes. Combine this with A03's scan snapshot/generation model. Test ignore, hidden, and symlink-policy changes between Scan and Run.

### A09 — P2: LINK verification does not consistently verify link identity

**Location:** `FileSyncEngine.java:1231-1265`; `DirectoryScanner.java:172-186`.

When both leaves are symlinks, verification compares link targets. When source is a symlink and destination is a regular file, the other branch hashes the target contents and may report OK. The outcome depends on destination object type rather than a consistent job policy. LINK scanning also excludes directory symlinks and dangling links, despite the UI label “Copy and verify as links.”

**Reproduced:** `source/link -> target`, regular `destination/link` containing the target's bytes, and matching `target` files. `--symlinks link --verify-only` returned **0**, `2 matched`; it did not detect that the link had become a regular file.

**Fix:** carry the symlink policy and source entry type into verdict calculation. Under LINK, compare object types and link text without hashing link targets; preserve valid directory/dangling links as link objects or clearly document/refuse them. Under FOLLOW, use explicitly different content semantics.

### A10 — P2: copy-only per-file records omit links and completed work on failures

**Location:** `FileSyncEngine.java:1472-1484`, `:1520-1531`, `:790-802`, `:812-825`.

`classifyLine()` counts `cL` link creations as copies, but `itemizePath()` accepts only `<`/`>` lines. The counter increases without a retained path. A misleading “raise the results limit” warning follows even for a two-item job.

**Reproduced:** copy a regular target and its symlink with `--no-verify --symlinks link`. Both exist at the destination; total copied is 2; only the regular file appears in results; the program claims the result limit caused the omission.

Additionally, cancel/timeout/rsync-error returns precede creation of COPIED outcomes. Partial jobs can therefore contain a positive copied count but an empty per-file report. This second behavior is confirmed by control flow; it was not independently measured in the targeted probes.

**Fix:** parse names for the complete supported itemize grammar, including link-target syntax and rsync escaping. Track announced/completed/uncertain transfers separately, and preserve honest per-file partial outcomes without converting announcements into proof of completion.

## Reporting and presentation findings

### A11 — P2: exports can destroy the data just verified, and export failure still exits 0

**Location:** `Cli.java:436-466`; `FileVerifierView.java:1117-1152`.

Both exporters truncate the chosen output without checking whether it aliases a selected source/destination file. The GUI has no application-level overwrite confirmation. Report construction/writing is synchronous on the EDT. CLI export exceptions are printed to global `System.err`, swallowed, and do not affect the previously computed exit status.

**Reproduced:** Verify Only with `--json <source>/a` returned **0** and replaced the verified input with JSON. An unwritable report path `/proc/nonexistent/report.json` likewise returned **0**, with no report created. A CLI user explicitly supplies the export path, but accepting a path that destroys the just-verified dataset is an avoidable safety hazard; it also undermines “write nothing”/verify-only expectations.

**Fix:** reject report aliases of input/output data, check both formats against each other, confirm GUI overwrites, and write reports atomically off the EDT. Propagate export failure to the caller/exit code and use the injected error stream. Document that report export itself writes even for dry-run/verify-only.

### A12 — P2: UI work is not bounded by the bounded log/result storage

**Location:** `FileVerifierView.java:1001-1003`, `:1091-1109`, `:883-890`; `TailLog.java:50-57`.

Each file event enqueues a separate EDT task and document/caret update. The log tail limits retained text, **not queued callbacks**. Large/fast jobs can accumulate an unbounded event backlog ahead of Cancel, progress, and completion. Results are rebuilt row-by-row with an enabled table sorter, including each “Only problems” toggle. Sorted large tables compound the cost.

Cancel invokes the engine's synchronous process termination/wait path on the EDT; preflight's rsync probe and report I/O are also synchronous. Thus even outside a huge event backlog the window can freeze during cancellation or slow external operations.

**Evidence:** source inspection; no interactive responsiveness or latency benchmark is claimed.

**Fix:** bounded/batched log delivery with timer-driven drains, bulk table-model replacement/RowFilter, and nonblocking UI cancellation/preflight/export. Test queued-event growth and a slow or TERM-resistant process, not just UI structure.

### A13 — P2: live status updates are discarded whenever the integer percentage is unchanged

**Location:** `FileSyncEngine.java:2159-2170`; `Cli.java:503-508`.

`emit()` returns solely because the percentage matches the previous value, even when filename, per-file bytes, or transfer count changed. The initial “hashing source” and subsequent “copying” updates both use 0. For a very large work set, many file transitions occur in one percentage bucket; the displayed file/status can remain stale. The watchdog knows activity is ongoing, so the liveness timer may not replace the stale text either.

**Evidence:** direct control-flow inspection.

**Fix:** coalesce by time and status changes, not percentage alone. Keep percentage monotonic independently of the status-update channel. Test multiple announced filenames and byte changes within the same percent bucket.

### A14 — P2: checked totals include extras despite the documented invariant

**Location:** `FileSyncEngine.java:2050-2053`, `:898-900`; `DESIGN.md`, section 6.

`countExtra()` increments both `processed` and `extra`; `processed` is exported as `filesChecked`. The documented contract is `checked = matched + mismatched + missing`, extras separate.

**Reproduced:** one matching selected file and one destination-only file produce `checked=2 matched=1 mismatched=0 missing=0 extra=1`.

**Fix:** separate verification count from progress/work-unit count, or deliberately revise all consumers and documentation. A separate checked count matches the current public contract.

## Packaging finding

### A15 — P2: the advertised thin-jar GUI fallback still requires FlatLaf

**Location:** `FileVerifierApp.java:24-44`; `FileVerifierFrame.java:32`; `FileVerifierView.java:113`; `UiTheme.java:124-129`.

The entry point catches a missing theme and tries the platform look and feel, but both frame and view constructors unconditionally call `UiTheme.install()` again. That method directly invokes FlatLaf classes. The frame creation catch handles `Exception`, not `NoClassDefFoundError`.

**Reproduced:** headless panel construction with only `original-file-copy-util-1.4.0.jar` fails with `NoClassDefFoundError: com/formdev/flatlaf/FlatLightLaf`. This contradicts the README/DESIGN promise that the thin jar runs with the platform look and feel. The shaded jar is unaffected by this dependency-absence case.

**Fix:** centralize optional theme loading and ensure constructors/theme switching do not reintroduce an unavailable dependency; alternatively stop advertising the thin jar as a runnable GUI distribution. Add a thin-classpath test.

## Additional hardening and contract gaps

These are distinct from the reproduced high-priority defects above.

- **CSV formula injection:** `ReportExporter.java:104-109` performs CSV quoting, not spreadsheet formula neutralization. An untrusted filename beginning with `=`, `+`, `-`, or `@` can be interpreted as a formula by spreadsheet consumers. Quoting alone does not prevent that. Offer/document a spreadsheet-safe export; preserve raw identity in JSON. No spreadsheet execution was tested.
- **CSV completeness is not self-describing:** `ReportExporter.java:18-27` emits outcomes only. Job status, counters, truncation warnings, and errors appear in JSON but not CSV. A capped or failed job's CSV can resemble a complete report. Include companion metadata or an explicit completeness field/manifest.
- **Collision warnings arrive after the copy:** `FileSyncEngine.java:706-714` accumulates warnings; CLI/UI show them after completion (`Cli.java:418-420`, `FileVerifierView.java:1067-1069`). The docs promise an upfront warning, but users cannot act on it before overwrites on a case-insensitive/normalizing destination. Present detected collisions before mutation and require an explicit policy where appropriate. No case-insensitive volume was tested.
- **Malformed ignore patterns silently disappear:** `ScanOptions.java:75-85` catches invalid glob syntax without returning a diagnostic. The comment says the frame validates it, but neither frontend validates through a separate path. An intended exclusion can therefore stop protecting files. Reject invalid patterns or surface warnings before starting.
- **Missing explicitly requested source paths can still yield exit 0:** `FileSyncEngine.java:675-699` only warns if some work remains; `Cli.exitCode()` ignores that omission. Reproduced `--files a,missing --no-verify` returns 0 after copying `a`. This is documented policy, not an undiscovered implementation deviation, but a strict-completeness option would make unattended backups safer. Copy Only's newline/CR refusals have a similar completeness issue.
- **Release verification is weaker than branch CI:** `.github/workflows/ci.yml:5-7` excludes version tags; its multi-version rsync step at `:35-52` is absent from `release.yml`, which only runs the installed version. A tag need not have passed the branch matrix before publication. Share a reusable validation workflow or gate releases on the same commit's full checks.
- **Release/supply-chain hygiene:** SHA-pin external actions, verify downloaded rsync source archives, add dependency-update/advisory automation, narrow release token permissions to the publishing step/job, add same-tag release concurrency control, and remove the stale manual-dispatch `v1.1.0` default. These are hardening recommendations, not evidence of a compromised dependency or workflow.
- **Coverage measurement:** add JaCoCo reporting and targeted safety regressions. A raw test count is not sufficient. Existing CI does install/probe rsync, has a real-transfer smoke test, and the matrix asserts at least one runnable binary; therefore the broad claim that a missing rsync can simply make the entire current CI pass was not accepted.

## What is already sound

- Engine/CLI/UI separation makes realistic headless testing possible.
- Explicit selected/known sets and refusal of an empty selection are good safety foundations, notwithstanding the filename/snapshot bugs.
- `ProcessBuilder` argument lists avoid shell command construction from filenames.
- Full SHA-256 values are retained in structured outcomes; null/absent sides are represented distinctly in JSON.
- Verified and unverified copies have different result terminology; dry-run has a separate WOULD_COPY status.
- The tests exercise real rsync, cancellation, process trees, races, and UI structure; compiler and bytecode-analysis gates are active and passed here.
- Normal rsync temporary-file replacement is preferable to in-place truncation of a destination during transfer.
- Result storage and on-screen log history have deliberate limits. The remaining UI issue is event-delivery/storage outside those bounds.

## TeraCopy-inspired product assessment

The implemented product is currently a **selected-file copier/verifier built on rsync**, not yet a full TeraCopy-style transfer manager. There is no implemented move/source-deletion operation, so no move-delete protocol was audited. Queueing, pause/resume, per-file retries, conflict choices (skip/rename/replace), persistent recovery journals, and undo/rollback are not present.

For a trustworthy transfer utility, the next milestone should be safety rather than additional UI modes:

1. Lossless filenames and authoritative scan snapshots.
2. Disjoint/alias-safe source/output paths and explicit symlink semantics.
3. Per-file accounting that distinguishes selected, skipped, announced, completed, verified, and failed.
4. Cancellation that owns hash workers and subprocesses across every exit path.
5. Failures and incomplete reports that cannot masquerade as a fully successful unattended job.

Also state backup-preservation limits clearly: scanning only files omits empty directories; `rsync -a` alone does not preserve hard-link topology, ACLs, or extended attributes; the application does not promise an fsync/power-loss durability protocol or rollback of overwritten files. Cross-process destination locking and interrupted-temp-file cleanup are already documented limitations, not new findings.

## Recommended remediation order and regression gates

1. **Block unsafe inputs first:** A01, A02, A04, A05. Assert the exact files changed and source immutability, not just counts.
2. **Make the displayed selection authoritative:** A03 and A08. Exercise edits/options changes while scans are pending and immediately before Run.
3. **Own the whole lifecycle:** A06 and A07. Bound cancel/timeout latency, verify no workers/subprocesses survive, preserve timeout vs user-cancel causality.
4. **Make reports trustworthy:** A09–A11 and A14. Include object-type checks, copy-only links, partial-copy outcomes, export failures and data-path aliases.
5. **Then improve responsiveness and packaging:** A12, A13, A15; bring release gates to parity and add coverage reporting.

Acceptance should include adversarial filename fixtures (`#`, `;`, real leading/trailing whitespace, CR/LF, comma, escapes, Unicode), both containment directions, source/destination aliases, symlinked ancestors, LINK/FOLLOW object-type differences, cancellation during a single large hash, Java-PID termination, stale UI scan completion, and unwritable/overlapping report destinations. Run those against the supported rsync matrix and at least one case-insensitive/network destination before expanding compatibility claims.
