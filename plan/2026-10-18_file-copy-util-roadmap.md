# File Copy Util — Remediation Roadmap

Date: 2026-10-18 · Baseline commit: `aeef29c` · Verified toolchain: OpenJDK 17.0.20, Maven 3.8.7, rsync 3.2.7

## 0. Baseline state (measured, not assumed)

| Check | Result |
|---|---|
| `mvn clean package` on `aeef29c` | **Succeeds**, emits `target/file-copy-util-1.0.0.jar` (+ `original-*.jar` from shade) |
| Compile warnings / errors | none |
| Tests | **none exist** (no `src/test`, no JUnit, no surefire) |
| CI / license / packaging hygiene | none |
| GUI runnability in this container | not verifiable (no display) → engine must become testable headlessly |

Conclusion: the application compiles and the GUI wiring is intact. What is unfinished is the **copy/verify semantics** and the **job lifecycle**.

Root cause: commit `64b2dc3` deleted `DirectoryScanner.getRelativePaths()` / `buildPathMap()`, leaving three independent notions of "a path" — absolute strings from the scanner, relative keys in `FileHasher`, and ad-hoc rel strings rebuilt in the frame (`FileVerifierFrame.java:498-517`). Most Phase 1 bugs follow from that.

---

## Phase 0 — Make it verifiable (implement now)

**Goal:** a build + test harness that can prove engine behaviour headlessly, so later phases are regression-guarded rather than eyeballed.

| # | Task | Acceptance |
|---|---|---|
| 0.1 | Install toolchain: `openjdk-17-jdk`, `maven`, `rsync` | done; versions recorded above |
| 0.2 | Record baseline build (pre-change) | done; baseline jar builds |
| 0.3 | Add JUnit 5 (`junit-jupiter` 5.10.x) + declare `maven-surefire-plugin` 3.2.x explicitly (Maven 3.8.7's default surefire 2.12.4 cannot run JUnit 5) | `mvn test` runs and reports |
| 0.4 | `.gitignore`: allow `plan/*.md` (currently `*.md` is ignored with a single `!README.md` exception), add trailing newline | roadmap doc is tracked |
| 0.5 | Unit tests: `FileHasherTest` (known SHA-256 vectors, empty file, large file, relative-key mapping), `DirectoryScannerTest` (nested layout → normalized rel paths, sorted order, `.git` ignored, symlink cycle does **not** blow the stack, missing dir → empty) | green |
| 0.6 | Integration test: `FileSyncEngineTest` driving real `rsync` against temp dirs, guarded by `Assumptions.assumeTrue(rsync available)` | green |
| 0.7 | Minimal headless seam so the engine is callable without a `JFrame` (synchronous `FileSyncEngine.execute(...)` core; `runJob(...)` stays an async wrapper until Phase 2) | tests call `execute` directly |

**Test matrix to be green after Phase 1**

1. `copyAndVerify_selectsOnlySelectedFiles` — dest contains only the selected files.
2. `copyAndVerify_verifiesOnlyTheWorkSet` — regression for bug B2 (unselected pre-existing mismatches must not appear).
3. `verifyOnly_countersAreTheCheckedSet` — `checked == matched + mismatched + missing` over the selection, not the whole tree.
4. `emptySelection_copiesNothing` — regression for the data-safety bug B1.
5. `missingDestFile_isCountedAsMissing` in **both** modes (regression for B4).
6. `corruptedDestFile_isMismatchWithHashes`.
7. `copiedCount_isRealTransferCount` — regression for B3.
8. `rsyncExit24_isWarningNotFailure`.
9. `progressReaches100WithCorrectDenominator` — regression for B5.
10. `missingRsyncBinary_givesActionableError`.

---

## Phase 1 — Correctness of copy/verify semantics (implement now)

### Design decisions adopted

**D1 — One path model.** New `FileEntry(String relPath, Path absolutePath)` record produced by the scanner; `relPath` is always relative to the scan root with `/` separators. `FileHasher` keys by the same `relPath`. The frame stores `FileEntry` in its list models and passes `Set<String>` of `relPath` to the engine. No `startsWith`/`substring` path arithmetic anywhere.

**D2 — Empty selection means zero files.** Never "no filter". `FileSyncEngine` refuses an empty work set; the frame pre-checks with a dialog. "Copy everything" is now only reachable via *Select All*.

**D3 — Work set definition** *(amended during implementation - see "Phase 0 / Phase 1 completion record").*
- Copy & Verify: the source-side selection.
- Verify Only: the source-side selection, minus files explicitly unticked in the destination list. A file with no row in the destination list was not unticked there - it is absent - and stays in the work set so it can be reported as `missing`.

**D4 — Counters are measured, not inferred.** `filesCopied` comes from rsync `--itemize-changes` transfer lines (`>f` for a local copy, `<f` for a push), not from a directory file count. `filesMissing` is its own category in both modes. `filesChecked == matched + mismatched + missing` for the work set.

**D5 — rsync invoked once, parsed structurally.** One code path, always `--files-from` (guaranteed non-empty by D2), `-a --checksum -i`. Drop `--progress` and the keyword sniffing that never matched real output; use itemize prefixes (`<f`, `cd`) plus explicit `rsync:`/`rsync error`/`rsync warning` classification. Exit 0 and 24 are success (24 → warning); all other codes fail.

**D6 — Progress is phase-based with correct denominators.** Listener split: `onPhase(String)` for log-worthy milestones, `onProgress(int, String)` for the bar/status label only, `onFileProcessed(...)` for per-file results. Denominator = work-set size, so the bar reaches 100% regardless of tree size. Copy phase 0–70, verify phase 70–100.

**D7 — Engine stops depending on Swing.** Remove the unused `javax.swing.*` import; engine is plain Java + `ProcessBuilder`, which is what makes 0.6 possible.

### Fixes being implemented (traceability)

| ID | Issue (baseline) | Location | Fix |
|---|---|---|---|
| B1 | Empty selection ⇒ copy/verify the **entire** tree (data safety) | `FileSyncEngine.java:104`, `:150-172`, `:236`, `:244` | D2 |
| B2 | Copy & Verify ignores the selection when verifying ⇒ wrong totals | `:180-215`, `:71` | D3/D4 |
| B3 | `filesCopied` fabricated as `totalFiles` | `:213` | D4 |
| B4 | Absent dest file counted as `mismatched`, `filesMissing` hardcoded 0 | `:196-201`, `:213` | D4 |
| B5 | `filesChecked` = whole-tree count; progress denominator wrong (bar never completes) | `:275`, `:281`, `:284` | D4/D6 |
| B6 | rsync output parsing never matches (`"transferring"`, `"generating"`); heartbeat inside `readLine()` cannot signal liveness | `:118-133`, `:164` | D5/D6 |
| B7 | Exit code 24 treated as fatal; unfiltered branch ignores exit code entirely | `:139-147`, `:174` | D5 |
| B8 | Temp `--files-from` file leaked on exception | `:98`, `:140` | try-with-resources / `finally` |
| B9 | `--files-from` list written with platform default, no validation of newline-bearing names | `:99-103` | explicit UTF-8 + reject `\n`/`\0` entries with a warning |
| B10 | `startsWith(root)` + `substring` path arithmetic (symlink/`..` hazard) | `FileVerifierFrame.java:498-517` | D1 |
| B11 | Scanner/hashers follow symlinked dirs, no cycle guard ⇒ `StackOverflowError`; no ignore filters (`.git` gets scanned, hashed and offered for copy) | `DirectoryScanner.java:14-27`, `FileHasher.java:44` | `Files.walkFileTree` without `FOLLOW_LINKS`, default ignore list |
| B12 | Dead code: `FileHasher.filesMatch()` (no callers) | `FileHasher.java:53` | removed |
| B13 | Log/progress spam: a progress line appended per file with `setCaretPosition` | `FileVerifierFrame.java:540-560`, `:593` | D6 (progress → status label only) |

**Partial Phase 2 pre-work included in Phase 1:** a `jobRunning` guard + disabled Run button, so overlapping jobs cannot interleave. Full lifecycle (cancel button, `Process.destroy()`, engine-owned state machine, `JobStatus.IDLE/RUNNING` wiring) stays in Phase 2.

### Out of scope for Phase 1 (deliberate)

Job cancellation, `Process.destroy()`, engine lifecycle/state machine, hash parallelism, hashing-selected-only optimisation before the copy (perf), `JFileChooser` `getSelectedFile()` fix, results table, log line cap, packaging cleanup.

---

## Phase 2 — Job lifecycle and cancellation

| # | Task | Acceptance |
|---|---|---|
| 2.1 | Replace `runJob`'s per-call `ExecutorService` with an engine instance owning one job: `start(...)`, `cancel()`, `isRunning()`, `JobStatus` transitions (`IDLE → RUNNING → COMPLETED/FAILED`) | only one job per engine ever; `IDLE`/`RUNNING` actually used |
| 2.2 | Keep the live `Process` handle; `cancel()` ⇒ `process.destroy()` + partial `JobResult(status=FAILED, message="cancelled", partial counters)` | cancelling mid-copy leaves no orphan rsync |
| 2.3 | Wire the frame: Run → Cancel swap, disable Browse/Scan/Run while running, re-enable on completion | UI cannot start or reconfigure a running job |
| 2.4 | Watchdog liveness: last-output timestamp surfaced as "still running, last output Ns ago" from a timer, not from inside the `readLine()` loop | progress bar is honest during a long silent transfer |
| 2.5 | Timeout option (configurable, default none) with destroy + FAILED result | hung rsync cannot block forever |
| 2.6 | Tests: cancel during transfer, engine re-use for a second job, `IDLE/RUNNING` transitions | green |

## Phase 3 — Robustness and UI usability

| # | Task | Acceptance |
|---|---|---|
| 3.1 | Fix the directory chooser: `getSelectedFile()` when present, else `getCurrentDirectory()` (today the highlighted folder is ignored and its parent is chosen — `FileVerifierFrame.java:340-354`); delete the redundant `capturedPath` listener | highlighting a subfolder and pressing *Select Folder* picks that folder |
| 3.2 | Preflight checks: `rsync` present/executable, dest writable, source readable, source ≠ dest and dest not nested inside source | actionable dialogs instead of mid-job IOException |
| 3.3 | Mismatch results table (rel path, src hash, dst hash, status) + "Export report" (CSV/JSON) | mismatches inspectable without reading a log |
| 3.4 | Dest-only file detection (`extra` category) and an explicit per-side selection mode replacing D3's "untick-at-dest excludes it" rule | dest list gains a defined role |
| 3.5 | Scan filters UI: ignore globs, include/exclude hidden files, optional symlink policy (`--links` vs dereference) | large repos scannable safely |
| 3.6 | Log cap (keep last N lines, e.g. 5000) + coalesced progress; virtualized list or `JTable` for 100k+ entries | 100k-file job stays responsive |
| 3.7 | Perf: hash only the work set (both modes), reuse pre-copy source hashes for post-copy verification, bounded hash thread pool | Copy & Verify hashes each selected file once |
| 3.8 | Search/filter box on both lists; relative paths shown in the lists | large selections usable |
| 3.9 | Preferences: persist last source/dest/ignore list (`Preferences` API) | restart keeps context |
| 3.10 | Real transfer progress: parse `rsync --progress` per-file completion instead of announcement counts, so the bar moves during a single large file | bar advances during a 10 GB file, not just at announcements |

## Phase 4 — Packaging, docs, hygiene

| # | Task | Acceptance |
|---|---|---|
| 4.1 | Declare `maven-compiler-plugin` with `<release>17</release>`; drop `maven.compiler.source/target` | release-correct compilation |
| 4.2 | Remove `maven-shade-plugin` (zero dependencies) or keep only if a dep is ever added; remove duplicated `mainClass` | one jar, no `original-*.jar` |
| 4.3 | CI (GitHub Actions: `mvn -B verify`, headless, JDK 17 + rsync) | build + tests gate every push |
| 4.4 | LICENSE (GPL-3.0-or-later suggested for an rsync-driven tool; needs an explicit owner decision) | `License: Not specified` resolved |
| 4.5 | Naming alignment: groupId `com.filecopyutil` vs package `com.fileverifier` vs title "File Copy Util"; pick one | consistent coordinates |
| 4.6 | `--cli` / headless mode reusing the engine (Phase 0's seam already exists) | scriptable parity with GUI |
| 4.7 | Docs: `CHANGELOG.md`, `DESIGN.md` (path model + work-set rules), `.gitignore` `*.md` exception strategy, trailing-newline fixes | new docs committable without per-file exceptions |

---

## Risks / open questions

1. **rsync itemize output format** — `<f` transfer lines are stable across rsync 3.x, but the exact path shown in the line varies with `--relative`; counters will therefore count itemize *prefixes* and treat the line tail as display-only text. Verified by test 7 rather than assumed.
2. **Symlink policy** — Phase 1 excludes symlinks from scans (hashing a link target while rsync copies the link is inconsistent). If symlinked trees matter to you, 3.5 makes it explicit.
3. **Verify-Only dest-selection semantics (D3)** — originally planned as an intersection; implementation showed that makes `missing` unreachable from the GUI, so it was amended (see completion record). Phase 3 (3.4) still replaces it with an explicit model.
4. **Which rsync to test against** — 3.2.7 here; older 3.1.x differs in `-i` path rendering. CI (4.3) pins one version.

## Execution order for this session

Phase 0 (0.3 → 0.7 scaffolding) → Phase 1 (D1-D7 / B1-B13) → run `mvn verify` until the Phase-0 test matrix is green → report the diff.

---

## Phase 0 / Phase 1 completion record (2026-10-18)

**Done.** `mvn -B clean verify` is green: 33 tests (9 scanner, 6 hasher, 18 engine), 0 failures. The jar builds as before.

| Phase 0 item | Result |
|---|---|
| 0.3 JUnit 5 + surefire | added (`junit-jupiter` 5.10.2, surefire 3.2.5 pinned - Maven 3.8.7 defaults to 2.12.4, which cannot run JUnit 5) |
| 0.4 `.gitignore` | `!plan/*.md` added, trailing newline fixed |
| 0.5 scanner/hasher tests | `DirectoryScannerTest` (9), `FileHasherTest` (6) |
| 0.6 engine tests against real rsync | `FileSyncEngineTest` (18), guarded by `assumeTrue(rsync available)` |
| 0.7 headless seam | `FileSyncEngine.execute(...)` synchronous core returns `JobResult`; `runJob(...)` kept as the GUI's async wrapper until Phase 2 |
| matrix #10 (`missingRsyncBinary`) | **not implemented**: the rsync binary is hardcoded and adding an override seam was judged out of scope. Superseded by 3.2 (preflight check), which makes it properly testable |

Matrix items 1-9 are covered, including regressions for B1 (empty selection copies nothing), B2 (only the work set is verified), B3 (a second identical run reports 0 copied), B4 (missing vs mismatch in both modes), B5 (progress denominator + coarse coalescing), B7 (exit-code classification plus a real exit-23 failure), B9 (newline in a file name).

### Findings that changed the design

1. **A local rsync copy reports `>f`, not `<f`** (verified against rsync 3.2.7). Counting only `<f` would have reported zero copies on every local copy.
2. **`--files-from` implies `--relative`/`--dirs`** in rsync 3.x (verified: subdirectory structure recreated without the flag), so the redundant `--relative` was dropped.
3. **Unchanged files emit no itemize output at all** - that is what makes a real `filesCopied` count possible, and a test now pins it.
4. **`visitFileFailed` fires for a directory that can be traversed but not listed** (execute-only permissions), so unreadable subdirectories become scan warnings instead of aborting the scan.
5. **The planned intersection rule for Verify Only made `missing` unreachable from the GUI**: a file absent from the destination has no destination row, so it can never be ticked there and was silently never checked. Found by driving the real frame under Xvfb; D3 amended accordingly.

### GUI verification

Engine tests are headless. The GUI was additionally smoke-driven under Xvfb with a scratch harness (not committed): frame constructs, lists relative paths, ignores `.git`, skips a symlink cycle, leaves a deselected file uncopied, reports correct counters and a 100% bar, re-enables Run, and Verify Only now reports `MISSING`.

### Deferred by design

Phase 2 (cancel, engine-owned lifecycle, watchdog liveness, `IDLE`/`RUNNING` wiring - Phase 1 added only the `jobRunning` guard plus disabling Run), then Phases 3 and 4 as listed.

---

## Phase 2 completion record (2026-10-18)

**Done.** `mvn -B clean verify` is green: 45 tests (9 scanner, 6 hasher, 20 semantics, 10 lifecycle), 0 failures.

| Phase 2 item | Result |
|---|---|
| 2.1 engine-owned lifecycle | `FileSyncEngine` is now an instance (`AutoCloseable`) with `start()` (refused while a job runs), `execute()`, `cancel()`, `isRunning()`, `status()`, `lastResult()`, `close()`; `IDLE → RUNNING → COMPLETED/FAILED` all used, enforced by an `AtomicBoolean` CAS so only one job can ever hold the engine |
| 2.2 cancellation | the live `Process` is retained; `cancel()` kills it plus `process.descendants()`; the job finishes with `FAILED`, the partial counters it reached, and a warning about hidden rsync temp files. Test asserts **no rsync process outlives the job** |
| 2.3 GUI wiring | one `setJobControlsEnabled(boolean)` switches Run↔Cancel and disables Scan, both Browse buttons, both mode toggles and the timeout field; closing the window calls `engine.close()` |
| 2.4 liveness | engine records `lastActivity` + timestamp (rsync output line or "verified <path>"); a `javax.swing.Timer` in the frame reports "Still running - last activity Ns ago (…)" after 3s of silence |
| 2.5 timeout | `setJobTimeoutMillis` / GUI "Timeout (s, 0 = none)" field, default `NO_TIMEOUT`; a 250 ms scheduled watchdog kills the process past the deadline, and the job fails with a timeout message |
| 2.6 tests | `FileSyncEngineLifecycleTest`: status transitions, second `start()` refused + `execute()` throwing while busy, engine reuse, `close()` rejects jobs, cancel mid-transfer (+ no orphan rsync), cancel mid-verification, timeout mid-copy, timeout mid-verification, default no-timeout |

### Findings that changed the design

6. **rsync prints every itemize line before it moves the data** (measured: all 3 lines at 0.469 s, job finished at 1.559 s). So `filesCopied` counts *announced* transfers - accurate for completed jobs, potentially overstated for cancelled ones (documented in `JobResult`). The copy phase of the bar is therefore capped at 40% by announcements and only reaches 70% when rsync actually exits.
7. **A stale destination list silently excluded files from Verify Only** (found by driving the real frame: after a copy job, the freshly copied files had no destination rows and were skipped). Fixed by replacing bare sets with `Selection(selected, known)`: exclusion now requires a row the user's list actually contains but left unticked. The engine also stopped rescanning the destination (it now trusts the caller's `known` rows), which removes a full directory walk per verify job.
8. **The timeout has to be enforced per work unit, not at loop tops**: a 1 s timeout did not fail a job whose hashing overran the deadline. Checks were added after each file's work and after the loop.

### GUI verification (Xvfb, scratch harness)

Idle controls enabled → job started: Run/Scan/Browse/mode/timeout disabled, Cancel enabled, engine `RUNNING`; second Run Job ignored; bar held at 40% during a silent 8 GB transfer with "Still running - last activity Ns ago (rsync: >f+++++++++ zz-huge.bin)"; after Cancel: controls restored, engine `FAILED`, exit 137, no orphan rsync; timeout job (`Timeout = 1`) aborted with partial counters.

### Roadmap update

Added 3.10 (true transfer progress via `rsync --progress` per-file completion) to Phase 3, since 2.4/finding 6 show that announcement-based progress cannot fill the bar during a long transfer.

---

## Phase 3 completion record (2026-10-18)

**Done.** `mvn -B clean verify` is green: 75 tests (9 scanner, 6 hasher, 30 copy/verify semantics, 10 lifecycle, 10 scan options, 5 report exporter, 5 log buffer), 0 failures. The GUI was driven under Xvfb with a scratch harness: 27/27 checks pass.

| Phase 3 item | Result |
|---|---|
| 3.1 chooser | `getSelectedFile()` is used (falling back to `getCurrentDirectory()`, then to the parent if a file was picked); the chooser starts in the directory already in that field, else the other field, else home; the redundant `capturedPath` listener is gone |
| 3.2 preflight | `FileSyncEngine.validate(JobRequest)` runs before any work: source is a readable directory, destination is a readable directory (and writable for a copy), source ≠ destination and destination not inside source, and `rsync` present. A missing `rsync` is reported as "rsync was not found (looked for 'rsync'). Install rsync, or use Verify Only, which needs no rsync." This also closed the deferred Phase 0 matrix item #10 - the binary is now overridable (`setRsyncBinary`) and the case has a test |
| 3.3 results + export | `FileOutcome(relPath, kind, sourceHash, destHash)` with **full** digests, capped at `MAX_OUTCOMES = 50_000`; a Results tab table (status, path, src hash, dst hash) with truncated hashes for display; *Only problems* filter; `ReportExporter.toCsv/toJson` (RFC 4180 quoting, JSON with totals/errors/warnings/outcomes, `null` for a hash that could not exist). CSV/JSON export buttons |
| 3.4 destination-only files | `EXTRA` outcome + `filesExtra()` counter + "Report destination-only files" checkbox; extras are excluded when the user's destination list contains the row and left it unticked. D3's rule is now expressed as `Selection(selected, known)` instead of a bare ticked set; a full per-side selection mode is still open |
| 3.5 scan options UI | `ScanOptions(ignorePatterns, includeHidden, symlinkPolicy)`; GUI fields for a comma-separated glob list, hidden toggle, and symlink policy (SKIP / LINK / FOLLOW). `Follow` passes `--copy-links`; `Link` keeps `-a` behaviour; `Skip` leaves links out and warns how many |
| 3.6 log cap + large lists | `TailLog` keeps the newest 5000 lines, drops in 1000-line batches, tracks every line's length (including its own "(older log lines dropped…)" marker) and is unit-tested headlessly. Lists use a filtered view model over a flat row list, so a `JList` still paints only visible rows |
| 3.7 hashing | only the work set is hashed in both modes; Copy & Verify hashes the source once *before* rsync and reuses those digests for post-copy verification; hashing runs on a bounded daemon pool (`min(MAX_HASH_THREADS, availableProcessors)`) that checks the cancel flag and the deadline per submitted unit |
| 3.8 filter boxes | a filter field above each list narrows what is visible without changing what is ticked; the lists show relative paths |
| 3.9 preferences | source, destination, ignore patterns, hidden flag, symlink policy, timeout, job mode and extra-file detection are saved and restored (`Preferences.userRoot().node("com/fileverifier")`) |
| 3.10 real transfer progress | implemented as `rsync --info=progress2`: the copy phase of the bar follows the parsed transfer percentage (0-70%), not itemize announcements, so a single huge file moves the bar. Announcement counting no longer drives progress at all |

### Findings that changed the design

9. **The itemize grammar was being misread, so copied symlinks were never counted.** rsync reports a created/changed symlink as `cL+++++++++ link.txt -> a.txt` (update type `c` = local create/change, item type `L`), and the old classifier mapped any `c` line that was not `cd` to IGNORED. `classifyLine` now follows the man page's update-type × item-type matrix: `>f`/`<f`/`cL`/`hf`/`cD`/`>S` are copied items, `cd`/`>d`/`<d`/`.d` are directories, `.f` is an untouched item (not a transfer), and `*` message lines ("* Skipping any contents from this failed directory *") are surfaced as warnings instead of swallowed. `isDirectoryItemizeLine`'s treatment of `D` as a directory was wrong too - `D` is a device.
10. **`--info=progress2` is the only honest copy-progress signal.** A probe showed itemize lines arriving before the data moved (finding 6), so announcement-based progress was removed rather than capped.
11. **`JCheckBox` fires `ActionEvent` only for a real click.** A programmatic `setSelected()` fires a `ChangeEvent`, so `addActionListener` silently missed it; the hidden-files toggle and the *Only problems* filter were switched to `ChangeListener` after the harness exposed both.
12. **Re-scanning has to remember unticks, not ticks.** Keeping the previously *ticked* paths made a file that had been filtered out and then came back reappear unticked (and therefore silently unverified). The frame now records which rows the user explicitly unticked and ticks everything else.
13. **Scan options must not re-walk behind the user's back.** An auto-rescan on every option change (and per keystroke in a long ignore field) was removed; the frame says "Scan options changed - press Scan Files to apply them." once, when a completed scan has gone stale.
14. **Measuring the log cap through `JTextArea.getLineCount()` is unreliable** - the display line cache lags behind document edits (it reported 41 lines while the buffer held ~5000), which is why the cap lives in `TailLog` with its own bookkeeping and headless tests instead of inline in the frame.
15. **Persisted preferences make GUI tests non-deterministic**: job mode and symlink policy are preferences, so a leftover "Verify Only" from one harness run changed the next run's copy semantics. The harness clears the prefs node before starting.

### GUI verification (Xvfb, scratch harness)

Ignore globs honoured (`.git, node_modules, *.bin`) and reported; filter box narrows the visible list while every row stays selectable; option change reports a stale scan without rescanning; hidden off/on and SKIP/LINK applied by Scan Files; Copy & Verify over 5 rows (including a symlink) reported `5 checked, 5 copied, 5 matched` and kept the link a link; Results tab titled `Results (5)`; hashes displayed truncated; Verify Only reported 1 destination-only file and the table showed an `EXTRA` row; *Only problems* filtered 6 rows to 1; CSV export wrote 7 lines with full digests; 9000 log lines capped to the newest 5047 with a drop notice; chooser fell back to a real directory from a stale field; paths, ignore patterns and timeout restored in a second window.

### Deferred / known limits

- `Selection(selected, known)` models the per-side rule but there is still no separate per-side selection *mode* (3.4's original ambition).
- `MAX_OUTCOMES = 50_000` bounds memory; a job larger than that keeps its counters but truncates the reportable outcomes (logged as a warning).
- GUI hangs on truly enormous lists are mitigated (view model, log cap) but not measured at 100k files here.
- `serialVersionUID` still missing on `FileVerifierFrame` and its cell renderer (Phase 4 hygiene).

---

## Phase 4 completion record (2026-10-19)

**Done except 4.4 (license - an owner decision, not an engineering one).** `mvn -B clean verify` is green: 94 tests, 0 failures, one jar (`target/file-verifier-1.1.0.jar`, no `original-*.jar`). The packaged jar was run for real: the GUI under Xvfb and `--cli` against a live tree (copy, verify, mismatch exit code).

| Phase 4 item | Result |
|---|---|
| 4.1 compiler release | `maven-compiler-plugin` 3.13.0 with `<release>17</release>`; `maven.compiler.source/target` removed. `<release>` compiles against the JDK 17 API, which source/target alone does not |
| 4.2 no shading | `maven-shade-plugin` deleted - the project has one test-scope dependency and no runtime dependency, so the shaded jar was a copy of the plain jar plus a second `mainClass` declaration. `maven-jar-plugin` alone now produces the runnable jar (verified: `java -jar` starts the GUI and runs `--cli`) |
| 4.3 CI | `.github/workflows/ci.yml`: temurin JDK 17 + maven cache, installs `rsync` explicitly (without it the rsync-dependent tests would silently skip and the build would become a partial check), `mvn -B -ntp verify`, then a CLI smoke test against the packaged jar: `--help`, a real copy+verify, checks the files landed and the CSV is non-empty, a clean verify exits 0, and a deliberately corrupted copy exits non-zero |
| 4.4 license | **done - MIT**, decided by the owner (see the decision note below). `LICENSE` (SPDX MIT text, "Copyright (c) 2026 moontato" - the git author identity; one edit away from any other name), `<licenses>` in `pom.xml`, README License section, roadmap item closed |
| 4.5 naming | one name everywhere: groupId `com.fileverifier` (was `com.filecopyutil`), matching the package; product name "File Verifier & Copier" matching the window title; artifactId `file-verifier` (was `file-copy-util`); version 1.0.0 → 1.1.0 because 1.0.0's selection semantics were unsafe and changed |
| 4.6 CLI | `Cli` front end: `--cli --source DIR --dest DIR` with `--files`/`--all`/`--exclude`/`--ignore`/`--no-default-ignore`/`--no-hidden`/`--symlinks`/`--no-report-extra`/`--timeout`/`--csv`/`--json`/`--quiet`/`--help`. No GUI flags → no GUI. Exit codes 0/1/2/3 (completed / job failed / bad command line / mismatched-or-missing). 16 tests |
| 4.7 docs + hygiene | `DESIGN.md` (path identity, selection and work-set rules, hashing, the rsync command and itemize grammar, counters and progress, lifecycle, two front ends one engine, scanning, bounded UI, known limits), `CHANGELOG.md` (1.1.0 behaviour changes first, because they change what a job does to your files), `.gitignore` rewritten so markdown is tracked by default - the old `*.md` with per-file exceptions silently untracked any new document |

### Findings that changed the design

16. **A ticked path with no file behind it used to vanish from the job.** Designing `--files` made this visible: a typo (`--files typo.txt`) selected nothing real, and the job would have reported success on an empty work set. `doExecute` now compares the selection against the scan and either fails naming the missing paths, or continues with a warning naming them. A test pins both.
17. **A file missing at the destination was reported twice** - once as `MISSING` and once as a per-file "Could not read ... NoSuchFileException" warning. Absence is the counter's job; `NoSuchFileException` no longer produces a warning, while real read failures (permissions) still do. Without this, verifying 100k absent files buries the log.
18. **Path identity had to absorb what people type.** `--files ./a.txt` normalized to `./a.txt`, which never matched the scanned `a.txt`. `normalize` now strips leading `./` as well as leading `/` and converts separators.
19. **`--ignore` is additive in the CLI, replacing in the GUI.** In the GUI the ignore field shows the full effective list, so editing it *is* the decision. In a CLI, `--ignore "*.log"` silently dropping the built-in VCS/OS ignores would quietly copy `.git` objects, so it adds instead, and `--no-default-ignore` is the explicit way to start from an empty list.
20. **The CLI gets no weaker selection rule than the GUI.** `--all` is not a flag that makes the engine scan-everything: the CLI scans first, expands `--all` (minus `--exclude`) into an explicit `Selection`, and hands that to the engine - so there is one work-set implementation.

### License decision (4.4), and what it means for later

MIT was chosen over Apache-2.0 and GPL-3.0-or-later. The reasoning, kept here because the choice is a
one-way door in one direction only:

- Nothing in the shipped jar is third-party runtime code (JUnit is test-scope; rsync runs as a separate
  process and is not linked), so no dependency forced or discouraged any license.
- MIT is the option that is easiest to leave: code released as MIT can be re-released at a later version
  under a stricter license (including GPL-3.0), and MIT is compatible with GPL code either way. Going the
  other way - GPL first, then MIT - needs consent from every contributor.
- What cannot be undone: recipients of a version already distributed keep their MIT rights to that
  version forever, and the git history/tags carrying the MIT text stay that way. Relicensing applies
  forward, from the release where the change is announced (CHANGELOG).
- If outside contributors ever matter, add a DCO or CLA at that point if you want to keep relicensing
  trivial in practice rather than merely defensible.

### Deferred / open

- The per-side selection *mode* (3.4's original ambition) is still modelled only as `Selection.selected`/`known`.
- GUI behaviour at 100k+ files is mitigated (view model, log cap, coalesced progress, `MAX_OUTCOMES`) but never measured at that size.

---

## Phase 5 completion record (2026-10-19)

Owner constraint for this phase: **limit large and frequent writes** (the work runs on the owner's SSD). That shaped both the features and the test suite.

| Item | Result |
|---|---|
| 5.1 partial-transfer reporting | after a cancelled or timed-out copy the engine lists the directories this job would have written to (no destination walk) and matches `.name.XXXXXX` against the names in the work set. Leftovers are reported with size - `1 partial rsync file(s) left in the destination: .big.bin.QafPvi (for big.bin, 3.8 MB). They are incomplete transfers - delete them or re-run the copy to finish them.` - and when rsync cleaned up, the warning says so explicitly. Nothing is deleted |
| 5.2 completed-transfer accounting | `--info=progress2` carries `xfr#N`, rsync's count of transfers it has **finished**. A cut-short job now reports `min(announced, xfr#)` with the message "announced N file(s) for copy, of which at most M finished". If rsync never reported a count, the announcement count is kept and the wording stays "announced" |
| 5.3 dry run | `JobRequest.dryRun` → `rsync --dry-run`, same work set, same hashing, same comparison. GUI: *Dry run (write nothing)* checkbox (persisted). CLI: `--dry-run`. Message `Dry run - nothing was written: 0 matched, 0 mismatched, 5 missing, 5 would be copied.` plus a warning that nothing was written. `copied` means "would be copied"; CLI exit 3 means "there *would* be problems" |
| also shipped | `--bwlimit KB/s` (`setBandwidthLimitKbps`), used by the tests to keep writes bounded and available to users for a slow link |

### Findings that changed the design

21. **`xfr#` counts finished transfers, itemize lines announce started ones** (measured with `--bwlimit`: at 49% of a 25 MB job the counter read `xfr#1` with one 12 MB file complete and the second announced). Capping by `xfr#` is right for cut-short jobs but wrong for completed ones: `xfr#` never counts locally created links and devices (`cL`, `cD`), and Phase 3 established that a copied symlink *is* a copied item. So a completed job keeps the itemize count and only an interrupted job is capped.
22. **Leftovers are conditional, so they must be detected rather than assumed.** A gracefully SIGTERM'd rsync removed its partial file and exited 20 in one measured case; killing the process group left `.big.bin.YC9p2z` (11.2 MB) behind in another. The README used to warn "may leave a hidden temporary file"; the engine now looks and reports which case happened. It never deletes anything.
23. **`rsync --dry-run` is not a normal run with the writes removed.** It writes nothing and prints the same itemize lines, but its progress line claims 100% instantly (`23.44GB/s`), so a dry run ignores rsync's percentage instead of driving the bar from it.
24. **Unbounded test I/O was worth fixing on its own.** The lifecycle suite used 1.5 GB sparse sources that rsync copied at full speed (a sparse file becomes real data at the destination). It now uses 300 MB sparse files with `--bwlimit=2000`, which bounds each interrupted transfer to a few megabytes and cut the suite from 10.3 s to 3.0 s. Test I/O discipline is now part of the design, not an afterthought.

### Verification

- `mvn -B clean verify`: 104 tests, 0 failures (40 semantics incl. dry runs, leftover detection and the `xfr#` cap; 10 lifecycle; 19 CLI).
- Packaged jar, real trees: dry run left `dst` with 0 entries; a 2 s timeout against a 300 MB sparse file at 2 MB/s reported `announced 2, at most 1 finished` and named `.big.bin.QafPvi (for big.bin, 3.8 MB)`; a second interrupted run reported both leftovers. Total bytes written across the whole verification: under 10 MB.
- GUI under Xvfb: 32/32 checks, including a dry-run job over 5 rows reporting `5 would be copied` with 0 files written to the destination, and the dry-run checkbox disabled while a job runs.
- CI smoke sequence run locally end to end, including "dry run exits non-zero and leaves the destination empty".

25. **A latent lifecycle bug surfaced while building 5.1-5.3: a job could run past its deadline and be reported `COMPLETED`.** The deadline was enforced only by the watchdog (250 ms period), while the work loops merely *stopped* on `deadlineExceeded()`. So a phase that finished between two ticks fell through to the success path - and a verification whose hashing had been cut short reported the unhashed files as `missing`, i.e. a job that never happened reported as one that found problems. Fixed by making the check itself authoritative: `deadlineExceeded()` records the timeout the moment it sees the deadline passed, `abortRequested()` (used by every loop, on every thread) also kills a live `rsync`, and the watchdog is reduced to what only it can do - a blocked `process.waitFor()` or one huge file being hashed. Found because a test that looked flaky was rewritten to be deterministic: making it deterministic made the bug visible.

### Phase 5 remaining (not yet done)

| Item | Status | Note |
|---|---|---|
| 5.4 measure 100k-file scale | open | Scan + Verify Only timing and memory with ~100k files, GUI included. `MAX_OUTCOMES` and the coalesced progress are unproven at that size |
| 5.5 rsync version matrix | done | Verified against 3.1.3 / 3.2.7 / 3.3.0 / 3.4.0 built from source; `RsyncVersionMatrixTest` + CI gate. It found and fixed a real overstatement in interrupted-job copy counts |
| 5.6 per-side selection mode in the GUI | done | Rule in one place, stated on screen as a work-set line plus per-list selection counts, `--exclude-dest` on the CLI; GUI cost measured at 100k and 300k rows |
| 5.7 polish | partially done | `--bwlimit` shipped; static analysis (error-prone/SpotBugs) and a longer `--help` with examples still open |

---

## Naming revision and pre-release housekeeping (2026-10-19)

**4.5 naming was revised by the owner.** The earlier decision ("one name everywhere") pushed the product
name into the coordinates: `com.fileverifier:file-verifier`. The owner prefers the short name for the
repository and artifact, and is happy for the long title to live only in the window. Naming is now
deliberately two-tier:

| Layer | Name |
|---|---|
| Repository, Maven artifact, jar, CLI usage | `file-copy-util` (`target/file-copy-util-1.1.0.jar`) |
| GUI window title | *File Verifier & Copier* (`FileVerifierFrame.setTitle`) |
| Package and groupId | `com.fileverifier`, matching the source tree |

Recorded so nobody "fixes" the mismatch later: the mismatch is intentional, a title is not a coordinate.

**Housekeeping done:**
- `pom.xml`: `<artifactId>file-copy-util</artifactId>`, `<name>File Copy Util</name>`, plus `<url>` and
  `<scm>` pointing at `github.com/moontato/file-copy-util`.
- `README.md`: title back to `File Copy Util`, a note explaining the two-tier naming, CI status badge, all
  jar paths updated.
- `ci.yml`: tag pushes excluded (`tags-ignore: ["v*"]`) so a release does not build the same commit twice.
- `release.yml` added: on any `v*` tag it installs rsync, runs `mvn verify`, checks the **jar name**
  against the tag (the artifact being published, not the pom's claim), runs a headless copy+verify smoke
  test, and attaches the jar to a GitHub Release.
- Annotated tag `v1.1.0` created locally.

**Commit identity: no change, and a correction to my own earlier plan.** `origin` is
`github.com/moontato/file-copy-util` and its existing tip (`aeef29c`) is already public under
`moontato <christianhk0701@gmail.com>`. Rewriting authorship would mean force-pushing over commits that
are already public, breaking any other clone, for no benefit. The plan item "decide whether to rewrite the
commit author identity before the first push" is closed: there is no first push, and the identity is
consistent with where the repo lives. `LICENSE` holder stays `moontato` for the same reason.

**Owner action, blocked here:** the container has no git credentials (`could not read Username for
'https://github.com'`, no `gh`, no token in the environment), so the push must be run by the owner:
`git push origin main --follow-tags`.

### Group A: 5.4 scale measurement + 5.7 static analysis (2026-10-19)

**Fixture, chosen to respect the owner's SSD constraint:** 100 dirs x 1000 files, every entry a **hard
link** to one of 100 small pool files. A 100k tree and a 300k tree each occupy **400 KB on disk** and take
3-10 s to build, while still being a real 100k/300k-file tree for scan, hash and rsync measurements.
(An attempt to build it in `/dev/shm` failed: tmpfs there is 64 MB and gives ~7 KB per file to inodes.)

| Tree | Scan | Verify Only | Copy & Verify (dry) | Heap that survives it |
|---|---|---|---|---|
| 100k files | 0.6-1.3 s | 2.4 s | 5.9 s | fits at `-Xmx128m` |
| 300k files | 3.7 s | 6.8-8.3 s | 17.1 s | `-Xmx256m` verify, `-Xmx320m` copy |

Counters were exact at every size (100k: 95900 matched + 2100 mismatched + 2000 missing = 100000, and the
dry run independently reported exactly 4100 would-copy = the same 2000 missing + 2100 mismatched - two
different code paths agreeing). Progress stayed at 101 callbacks for 100000 files, so the coalescing works.

26. **Memory, not time, is what breaks a large tree.** Time is linear and unremarkable; the failure mode was
    OutOfMemoryError escaping a worker thread - a stack trace and an engine left looking busy. Two shapes
    caused it: the verify phase held a digest map per side plus a resolved-path entry list per side, and the
    destination was scanned for `EXTRA` rows even when the checkbox was off. Verification now decides one
    file at a time (digests kept only in the bounded results list) and skips the destination scan entirely
    when nothing asked for it; 300k Verify Only went from OOM at 256 MB to completing inside it.
27. **A scheduled task that throws is never rescheduled.** The watchdog enforced the deadline; if a tick had
    ever thrown, the deadline would have silently stopped working for the rest of the engine's life with no
    symptom. The tick is now wrapped and survives anything it throws. OutOfMemoryError in a job is now a
    FAILED job with the actionable advice, and an Error that escapes even that guard still releases the
    engine instead of leaving it reporting RUNNING forever.
28. **`Path.getFileName()` returns null for a root path.** Two places dereferenced it, so being asked to
    scan a filesystem root would have thrown. Found by static analysis, not by a test - which is the point
    of adding the gates: `javac -Xlint:all -Werror` plus SpotBugs at Medium severity, both wired into
    `mvn verify`. SpotBugs' first run produced 8 findings: 4 real null-dereference possibilities
    (`getFileName()`, `JFileChooser.getSelectedFile()`), 1 format-string issue, 1 constructor-escape,
    1 dead private method, and 1 intentional (`EI_EXPOSE_REP` on `TailLog.component()`, annotated as
    deliberate because the caller must receive the very component the log writes into). All 8 are addressed
    and the build is clean at Medium.
29. **The results bound is a real tradeoff, not a bug to hide.** A 300k-file job keeps 50000 rows, so an
    export lists 50000 of them with a warning naming the number. Chosen fix: make the bound the user's
    (`setResultLimit`, CLI `--max-results N`) rather than grow memory by default or drop digests from
    matched rows to save heap. Streaming the export straight out of the engine would remove the tradeoff
    entirely and stays an open candidate.

**Also in this group:** `--help` now carries examples, what each counter means, and the measured cost of
large trees. Suite is 109 tests; GUI harness under Xvfb still 32/32; jar unchanged in shape (the SpotBugs
annotations dependency is `provided`, so it is not in the jar).

**Open after Group A:** 5.5 (rsync version matrix, needs container pulls), 5.6 (per-side GUI selection,
needs an owner decision on the UI), streaming export, and a GUI knob for the results bound.

### Group B: 5.6 per-side selection made visible (2026-10-19)

Owner's decision, taken before coding: **no third list.** The two checkbox lists stay; what they mean
together is stated in words. A derived "effective files" list would have been a second thing to keep in
sync while still hiding the rule that produced it.

Shipped:

- Per-list counts: `100000 files, 100000 selected`.
- A work-set line under the mode buttons, e.g.
  `Work set: 99000 file(s) - 100000 ticked at source, 1000 unticked at the destination`, or for a copy
  `Work set: 100000 file(s) - source ticks only; a copy ignores the destination list`. It follows the mode
  button you are on, so switching modes re-explains the same tick state instead of silently changing numbers.
- Excluded paths named when few (cap `NAMED_EXCLUSIONS = 5`); with 9 exclusions nothing is named, because
  naming 5 of 9 implies the rest are unimportant.
- `--exclude-dest a.txt,...` for the CLI: the equivalent of an unticked destination row. On a copy it prints
  that it does not apply rather than doing nothing silently; naming the whole selection is a usage error.
- `FileSyncEngine.excludedAtDestination` is now the single statement of the rule, called by the job (both the
  work-set filter and the destination-only filter) and by both preview forms.

30. **The obvious implementation was 245 ms per click.** Computing the preview the direct way - rebuild the
    selection sets, normalize them, walk the source list - cost a quarter second per checkbox click in a
    100k row list, i.e. the feature made the GUI worse than the confusion it fixed. Measured before fixing
    it, then fixed: rows are indexed by relative path and unticked rows are tracked incrementally, and the
    engine has an equivalent fast form whose cost is proportional to how many rows the user *unticked*.
    After: 0 ms per click, 2-3 ms Select All, 0 ms mode switch, at 100k and 300k rows alike.
31. **Equivalence, not duplication.** Two forms of one rule is the kind of thing that drifts, so a test
    compares them over four random tick patterns on a 400 path list (counts and named exclusions), and
    another test compares the preview's numbers with the counters of the job it previewed - including the
    trap that running the copy first repairs the mismatch the Verify Only preview was predicting.
32. **GUI cost at scale, finally measured** (previously only mitigated): scan 1.3 s / 7.1 s for 100k / 300k
    rows; filter 15 ms / 26 ms; 382 MB resident right after a 100k scan, 489 MB peak; 573 MB / 706 MB at
    300k. Responsiveness is fine; the memory is the honest limit, and it is now written in DESIGN.md.
    The cross-check also caught a *harness* bug first (the authoritative comparison used sets captured
    before the untick loop, so it disagreed with the badge for the wrong reason).
33. **Records holding a `List` were handing out the stored list** (`EI_EXPOSE_REP`, and `..._REP2` for
    storing a caller's list). Fixed with a defensive `List.copyOf` in the canonical constructor and an
    annotated accessor. Note the Java detail: a compact constructor for a `public` nested record must itself
    be declared `public`, or javac rejects it as "attempting to assign stronger access privileges".

**Open after Group B:** 5.5 (rsync version matrix), streaming export, a GUI knob for the results bound.

### Group C: 5.5 rsync version matrix (2026-10-19)

Container images were the obvious route and the wrong one: apt offers only 3.2.7, and pulling four distro
images to get four rsyncs costs more time and more disk than it buys. The cheapest honest matrix was source:
`download.samba.org/pub/rsync/src` tarballs, built with `--disable-openssl --disable-xxhash --disable-zstd
--disable-lz4` (configure otherwise aborts on missing optional headers). **3.1.3, 3.2.7, 3.3.0 and 3.4.0**
then run side by side, selected per job with the existing rsync-binary override. Fixture writes stayed tiny:
one 4 MB file and a handful of text files, throttled with `--bwlimit`.

`RsyncVersionMatrixTest` asserts, per binary: the same tree and selection give the **same counters**; a dry
run writes nothing; progress reaches 100 and stays coalesced; an interrupted job claims only transfers that
finished. Binaries come from `FCU_RSYNC_BINARIES`, and a binary that cannot run is skipped rather than
failing the build, so the same class runs on CI's single distro rsync and on a local four-way matrix. CI now
builds 3.1.3, 3.3.0 and 3.4.0 and runs the class, making portability a gate.

34. **The matrix immediately found an overstatement.** Phase 5 assumed "no `xfr#` seen → no information →
    keep the announcement count". Measured on all four versions, `xfr#N` appears **only in the progress
    fragment printed when a transfer finishes**; the fragments of a transfer still in flight carry no `xfr#`
    at all. So progress-without-`xfr#` is evidence of *nothing completed*, not of silence: a throttled 4 MB
    copy cut off at 1.5 s was reporting **1 file copied while the destination held 0 complete files**, on
    every version. Fixed: three distinct cases now, each worded to name its evidence - "announced 3 and
    completed 1 of them", "announced 1 and completed none of them", "reported no progress, so how many
    finished is unknown". The old phrase "at most N finished" was dropped because when rsync had reported a
    count, it was a count and not a bound.
35. **Dry runs have to be exempt from the cap.** A `--dry-run` transfers nothing, so its `xfr#` is 0 by
    construction; capping it would have reported "0 file(s) copied" for the one job where the number is
    pure intent. It now reports "N file(s) would have been copied".
36. **`--info=progress2` is carriage-return output.** rsync rewrites one status line, so its "lines" are
    separated by `\\r`. Line-by-line parsing works only because the reader treats CR as a line end; had the
    output been read as newline-delimited text, real transfers would have produced one giant line and no
    percentages at all. Recorded in DESIGN.md because it is the kind of fact a rewrite forgets.
37. **`--files-from` sorts its list.** With `a1.txt … zbig.bin` requested in that order, the 4 MB file went
    first. No counter changes, but it explains why a throttled job appears to ignore the small files it was
    asked for first - worth knowing before debugging a "missing" file that was merely queued last. The first
    version of the matrix test also tripped over its own fixture (reusing one temp directory across four
    runs), which is what caught it.
38. **Counting what rsync counts.** `keep.txt`, already identical at the destination, is selected, scanned,
    verified - and never appears in the itemize output, so it is not "copied". The matrix's first assertion
    expected 5 copies for 5 selected files and was wrong; 4 is right, and that is the whole distinction
    between selection, work set and transfers in one number.

**Open after Group C:** streaming export, a GUI knob for the results bound, and anything the owner wants
from the backlog. Release prep (push `main` + `v1.1.0`) is still waiting on credentials.

## Phase 6 completion record (2026-10-20): the interface, and what it made testable

Packaging decision (item 0): **FlatLaf 3.7.2** (Apache-2.0), owner approved. The runnable jar is now
~1.1 MB instead of ~110 KB because the theme is bundled; `NOTICE` records it, and the thin jar
(`original-*.jar`) still runs the same program in the platform look and feel. Version moved to **1.2.0**.

### What shipped

- **6.1 theme infrastructure** - `UiTheme` holds two complete palettes (same tokens), three typography
  weights plus a monospaced face, the FlatLaf style keys, and `install(boolean dark)`. It is the only
  file in the program containing a `new Color(...)`; `UiThemeTest` and `FileVerifierViewTest` both enforce
  that by scanning the other sources.
- **Contrast is a gate, not an opinion.** Measured minimums: light palette **5.17:1** (button label on the
  accent), dark palette **6.03:1** (MISMATCH badge on the window), everything else between 6.5:1 and
  17.7:1 - all above the WCAG 2.1 4.5:1 bar, asserted for both palettes in `UiThemeTest`.
- **6.2 restyle through tokens** - cards with a hairline edge replace titled group boxes, labels sit above
  fields, scan options fit on one line, one padding rhythm (`UiTheme.SPACING`), lists get alternating row
  stripes, the log and list viewports get a `UiTheme.edge()`.
- **6.3 mode and action** - the two mode buttons are one FlatLaf segmented control (`left`/`right`), the
  work-set line sits beside it, *Run job* is the only filled button, *Cancel* is the only other coloured
  one, and the status row gained a phase ("Phase: copying/verifying/done").
- **6.4 results table, log, filters** - status column shows a colour dot **and** the word (never colour
  alone), hashes stay monospaced while the status column uses the UI face, columns have deliberate widths,
  each list got a *Clear* button for its filter, and the quiet instructions under each list stopped being
  clipped by the buttons.
- **6.5 keyboard + docs** - `Ctrl+S` scan, `Ctrl+R` run, `Escape` cancel (`Enter` left alone so it still
  activates the focused control), shortcuts in the tooltips, DESIGN.md §12 written, CHANGELOG 1.2.0.

### The structural change that paid for the rest

`FileVerifierView` (a panel) now owns the interface; `FileVerifierFrame` is a thin window that hosts it.
A `JFrame` cannot be constructed at all in a headless JVM, which had made the entire UI unreviewable in
CI. As a panel it can be built, driven, and painted with no display server, so the interface now has
committed tests: control inventory (nothing was removed to make the window look modern), mode exclusivity,
theme switching, contrast, no colour literals outside the theme.

### A defect the render review found

`JScrollPane(JTable)` does not attach the table header. The results table has had **no column titles
since 1.1.0** - which also meant the "click a column header to sort" the toolbar advertised was not
reachable. Fixed by setting `setRowHeaderView(resultsTable.getTableHeader())` explicitly, and asserted in
`FileVerifierViewTest.resultsTableHasAHeaderSoColumnsCanBeSorted`.

### Dark mode that actually repaints

`UiTheme` tags every widget it styles with a client property; `applyTheme` installs the look-and-feel,
calls `FlatLaf.updateUI()`, then `UiTheme.restyle(root)` walks the tree and re-applies the palette.
Without the walk, cards, the log and the buttons keep the colours they were built with - the usual way
"dark mode" ends up half working. The preference persists (`darkMode`).

### Verification, and its limits

- `mvn -B clean verify`: **137 tests, 0 failures**, `BugInstance size is 0`, `BUILD SUCCESS`
  (15 new: 8 theme, 7 interface structure).
- A scratch headless harness (`UiHarness`) drives the real panel through 46 checks: scan, click-to-toggle
  a row, work-set wording, select/deselect, filter+clear, a real throttled copy & verify job with control
  locking and exact totals, only-problems filtering, dry run writing nothing, `EXTRA` detection, dark/light
  switch, engine release on shutdown. All 46 pass.
- PNG renders of the panel in light and dark, plus a close-up of the results table, were produced offscreen
  and reviewed by eye - that is how the missing table header and the clipped hint text were caught.
- **What this container could not do**: it had no JDK, Maven, fonts, or X server at the start of the
  session. Toolchain was reinstalled to `/home/pi/tools`, and `libfontconfig` plus DejaVu fonts were
  unpacked from Debian packages into the scratch directory - without them every Swing text measurement
  throws `Fontconfig head is null`, and the suite cannot run. `Xvfb` could not be started (it insists on
  `/usr/bin/xkbcomp` and a writable `/var/lib/xkb`, neither available without root), so the interface was
  verified headlessly and offscreen rather than on a desktop. **The visual judgement is still the owner's.**

### Still open in Phase 6

- Tab-order/focus-traversal audit (shortcuts exist; traversal order was not deliberately chosen).
- The directory chooser is the platform's own; FlatLaf's system-chooser integration was not enabled.
- The 300k-row list is still ~710 MB resident (unchanged by this phase; it is a known limit, not a
  regression).
- A GUI knob for `--max-results`, and lazy rendering for very large lists, remain backlog items.

---

# Post-release robustness audit (shipped as 1.2.1)

Asked whether the program was bulletproof, the honest answer was: measured on the things I had measured, but
unmeasured on several things that a hostile file system will do. Ran an adversarial pass instead of reasoning
about it. Five defects found, all fixed, all covered by `RobustnessTest`.

| Case | What it did | What it does now |
| --- | --- | --- |
| Non-ASCII names under a `C`/POSIX locale (cron, systemd, minimal containers, `sudo`) | `InvalidPathException` escaped `DirectoryScanner` and the job; crash, no advice | entry skipped, one warning naming the encoding and the fix (`LC_ALL=C.UTF-8`), no guessed name written |
| rsync fails partway through a copy | failure returned **zeroed counters**: "0 copied" while files sat in the destination | same accounting as a cancelled job: announced, completed (`xfr#`), leftovers scanned |
| Symbolic link at the destination | followed and hashed: read a file outside the destination, reported its digest as the destination's | MISMATCH + "not compared, this tool does not follow links"; nothing outside read |
| Carriage return in a file name | accepted, handed to line-based `--files-from`, came back as "rsync failed (exit 23)" with no cause | refused up front like a newline/NUL, named in warnings, counted as not transferred |
| `null` `ProgressListener` | job FAILED with an internal null dereference as its message | means silence |

Checked and found correct, so no change: names containing `-`, `$`, `\`, `;`, `'`, `[`, `,`, tabs and trailing
spaces; a selected file deleted between scan and run (named in warnings, not dropped); a symlinked directory
inside the destination (rsync replaced the link, nothing was written outside); a source file edited mid-verify
(counters still added up); a real file whose name happens to look like an rsync partial (not accused — the
leftover scan only looks at directories the job wrote to).

What this audit does **not** cover: concurrent jobs from two windows onto one destination (no advisory lock),
filesystems that lie about `isRegularFile`, Windows paths (out of scope), and a destination on a filesystem
that runs out of space mid-transfer (rsync reports it, leftover detection has not been exercised against a real
full disk).

---

# 1.2.2 — the "still open" items that needed no disk

Chosen from the open list on the criterion of what could be verified here rather than reasoned about.

| Item | Shipped | Evidence |
| --- | --- | --- |
| Case / Unicode-normalisation collisions in the work set | detector plus a named warning before any write; not a refusal, because on a case-sensitive destination both names are valid | `CollisionAndRaceTest`: a real job with `A.txt` + `a.txt`; a filesystem-free unit test of the detector covering case, accent composition, `sub/` separation, and no false alarm on an ordinary tree |
| A source file edited mid-copy | source re-hashed once on mismatch; the file is named as one that moved during the job; the verdict itself is unchanged | `CollisionAndRaceTest` with a stand-in rsync that rewrites a source file while "running", plus a Verify-Only test asserting the tool does **not** make the claim where no pre-transfer digest exists |
| Partial-file reporting on the failure path | leftover scan runs on an rsync error too, not only on cancel/timeout | `CollisionAndRaceTest` with a stand-in rsync leaving `.data.AAAAAA` and exiting 23, rsync's own reason included in the report |
| `setRsyncBinary` was process-wide static | per-engine `useRsync()`, preflight checks the binary that engine would run, availability cached per binary; static default kept | `EngineIsolationTest` |
| No protection against two jobs on one destination | in-process reservation per canonical destination; second job fails with the reason; released when the job ends | `EngineIsolationTest`: busy destination rejected, an unrelated destination unaffected, destination free again after cancel |

Deliberately not done, and why:

- **Cross-process locking.** Would mean writing a lock file into the owner's destination tree. That is the
  owner's call, not a default. Documented instead.
- **Lazy rendering for very large lists.** Real work, measurable benefit only on trees the owner does not carry
  daily.
- **Full-disk behaviour.** Leftover detection is proven against a stand-in that reports ENOSPC, not against a
  genuinely full filesystem.
- **Focus-order audit, native file chooser, streaming export, GUI knob for `--max-results`.** Unchanged backlog.

Also worth stating plainly: the case-insensitive *consequence* is reasoning, not measurement. The detector is
tested; what a case-insensitive mount does to a transfer was not observed, because no such mount was available
here.

---

# 1.3.0 — interface changes the owner asked for

- **Editable source/destination fields.** Type a path, press Enter, both lists load. `~` and relative paths are
  resolved the way a shell would. Changing a field clears the loaded lists deliberately: they are ticks against
  paths in the directory that field *used* to name, and the alternative is a job whose selection came from
  somewhere else. Fields lock while a job writes. Unspellable paths (locale encoding) get a named error instead of
  an exception. `UiTheme.readOnlyField` deleted with it — its whole reason for existing was working around the
  look-and-feel's "disabled" wash on non-editable fields.
- **Version in the window**, muted at the bottom right, read from a build-stamped resource rather than a literal.
  The rule this establishes: any number the interface shows about the build comes from the build.

Verified: 160 tests, 0 SpotBugs findings, both palettes rendered offscreen to confirm the label placement and
that the now-editable fields follow the theme in dark mode.

---

# 1.4.0 (in progress) — verification became a switch

The owner asked for fewer modes: a copy job with verification as an optional checkbox. What was settled before
writing code, and what it turned out to mean:

- **Verify only stays a mode.** It is the only job that reads a destination without touching it, and no
  checkbox on a copy job produces that. So the control is `Copy | Verify only` plus `Verify after copying`
  (default on) - three modes became two plus a switch, which is the simplification asked for without deleting
  the audit path.
- **Default is verify on**, after the owner's own reconsideration: defaulting to no verification makes an
  unverified copy the surprise outcome of a tool whose name promises verification.
- **A copy that compares nothing has to say so**, everywhere it reports: button label (`Run copy`), status
  line, log line, the job summary, the CLI totals line, and a standing warning. Otherwise "verification off"
  is invisible precisely where the numbers are read.
- **`COPIED` rows**: each file such a job wrote is a result row, no hashes, coloured muted rather than as a
  passed check; a dry run writes `WOULD_COPY` because nothing was written. A verifying job still writes one
  row per file (its verdict) and never both.
- **Hashing is skipped entirely** when nothing will be compared. Proven by contrast rather than by reading the
  code: the same unreadable source file yields a "Could not read" warning in a verifying job and none in a
  copy-only job.

Known limit, stated rather than papered over: a copy-only job that is **cancelled or timed out** produces no
`COPIED` rows. rsync announced N files and completed at most M; which ones is not known, and an export that
listed the announced ones as copied would record transfers that may not have finished. Those jobs report the
count, the leftovers left in the destination, and that verification was off.

Verification so far: 171 tests, 0 SpotBugs findings, rsync 3.1.3/3.4.0 matrix green locally (CI runs 3.1.3 /
3.2.7 / 3.4.0), both palettes rendered offscreen to check the new mode row and the three button labels, and a
copy-only job run through the shipped jar with a CSV export showing the three `COPIED` rows.

---

# 1.4.0 (continued) — a large copy that reported nothing, and rsync processes that outlived the program

Reported by the owner from real use: a 15-30 GB copy sat on "Phase: copying" for over an hour with an empty
destination folder; after cancelling and closing the program the source HDD kept blinking and System Monitor
showed three rsync processes; killing them made several large files appear in the destination.

Measured before changing anything:

- **A local rsync copy is three processes.** With 3.4.0: pid A (spawned by Java), pid B (`ppid=A`), pid C
  (`ppid=B`). Killing only the first leaves two reading the source disk after the program has exited - which is
  what the owner saw, and the existing test helper `awaitNoRsyncProcesses()` was already asserting this in the
  cancellation and timeout paths, so the hole was in a path no test reached.
- **rsync writes a hidden partial file and renames at completion.** At 2 s into a 30 MB copy the destination
  held `.a.bin.v7asq2` at 2.8 MB, growing to 30.6 MB; `a.bin` appeared at 22 s, after `xfr#1` was reported.
  Nothing visible in an ordinary file manager during the transfer - the answer to "I don't see any file".
- **An itemize line arrives at the *start* of a file's transfer, not its end.** `>f+++++++++ a.bin` at 0.1 s;
  `xfr#1` and `>f+++++++++ b.bin` both at 20.4 s. So "which file is in flight" is answerable from the last
  announcement, and "how many finished" only from `xfr#`.
- **`--info=progress2` gives overall bytes/percentage only**, never the current file name, when its output is a
  pipe (tested progress1/2/3/4 and with itemize on/off: rsync suppresses the filename line when itemize is on).
  So the per-file figure has to be derived: overall bytes − bytes of finished files, over the announced file's
  size (sizes known from the scan).

What was wrong in the program:

1. **A cancelled job could still launch rsync.** Cancellation during the hashing pass set the flag, the copy
   phase checked nothing before the `ProcessBuilder`, and the engine then sat in `process.waitFor()` on a
   process nothing was watching. Now the copy phase refuses to start, and a cancellation landing between the
   check and the fork kills the process at once.
2. **The kill order left rsync unable to clean up** (children SIGKILLed before the parent was signalled): the
   destination kept `.a.bin.AG4rJt` after a cancel. Snapshot the tree first, SIGTERM everyone, SIGKILL only
   survivors. Measured after the change: destination empty after cancelling, no survivors.
3. **Nothing swept the tree after a *successful* copy.** rsync's parent can exit while a child is still
   finishing; an orphaned child is unreachable afterwards. A completed copy now sweeps it.
4. **"Phase: copying" covered the hashing pass**, which for 15-30 GB is the first hour, with an empty
   destination - the exact report. Phases are now `hashing source` → `copying` → `verifying`.
5. **The copy status said "Copying: 1 announced, 12% transferred"** - one overall number for an hour. It now
   names the file in flight and its own progress, verified through the shipped jar:
   `Copying 9.9 MB so far (8%) | now writing: big.bin (9.9 MB of 114.4 MB, 9%) | 0 of 1 done | 1.95MB/s, elapsed 0:00:54`
   and the done-count follows rsync's `xfr#` so it reaches the last file.
6. **rsync's hidden-temp-file behaviour was never stated to the user.** It is logged once per copy job in the
   window and the CLI, and documented in Troubleshooting.

New tests (5): no rsync survives a mid-transfer cancel and no partial file is left; none survives the engine
being closed mid-transfer; none survives a completed copy; a job cancelled during hashing never runs rsync (a
stand-in rsync records whether it was invoked, ignoring the engine's `--version` probe); the copy status names
the file in flight with its own percentage and rate. Suite: 176 tests, 0 SpotBugs findings.

Deliberate limit: the program cannot make rsync show the file under its real name during the transfer - that is
rsync's rename-on-completion behaviour, and forcing it (e.g. `--inplace`) risks damaged destination files on a
interrupted run. What the program can do is say which file is in flight, how far along it is, and that it is
sitting in the destination under a hidden name.
