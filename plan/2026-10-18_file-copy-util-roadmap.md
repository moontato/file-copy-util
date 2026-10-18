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
