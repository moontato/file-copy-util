# Phase 6 — modernize the UI without removing anything

Owner's brief: *minimal, clean, modern design; no feature removed.* So this phase changes how the
existing controls look and sit, not what they do. Behaviour is already tested (42 GUI harness checks plus
122 unit tests); the acceptance rule for this phase is that those checks keep passing while the window
stops looking like a 2005 Swing tutorial.

## 0. The one decision that changes packaging

Swing's default look and feel (Metal) cannot be made modern by recolouring. Colours and spacing are
adjustable; the **shapes** are not — bevelled buttons, gradient-ish titled borders, chunky scrollbars,
combobox arrows, table headers, tab shapes. Those need a look-and-feel.

| Option | What you get | Cost |
|---|---|---|
| **A. FlatLaf** (`com.formdev:flatlaf`, Apache-2.0) | flat widgets, real dark theme that follows the OS, modern scrollbars/tables/comboboxes, prompt text in fields, DPI scaling | the jar gains a runtime dependency, so the runnable jar becomes a shaded jar (~1.2 MB instead of ~108 KB) |
| **B. Hand-rolled on Metal** | recolouring, spacing, fonts, custom renderers only | keeps the jar dependency-free, but bevels, scrollbar and header shapes stay; "modern" will be partial and will look different on every OS |

**Recommendation: A.** The stated goal is design, and only a look-and-feel moves the needle. MIT and
Apache-2.0 are compatible. The engine stays dependency-free; only the GUI front end ships the look and
feel, and `maven-shade-plugin` returns for exactly that reason. If keeping a plain jar is a hard
requirement, say so and I'll do B and be honest about the ceiling.

## 1. Design tokens in one file

Everything visual comes from a single reviewable class, `UiTheme`: palette, spacing grid, type scale,
corner radii, status colours. No `new Color(...)` literals scattered through the frame — a static check
in the GUI harness will fail if ad-hoc colours reappear.

- **Neutrals**: canvas, surface, border, text primary/secondary, muted — one ramp, no pure black on pure
  white (reduces the harsh, dated look).
- **One accent** (used for primary action, selection, focus) and **four semantic status colours**
  (ok / mismatch / missing / extra). Status colour is never the only signal: text labels remain.
- **Type**: system UI font stack, 13 px body, 12 px secondary, 11 px monospaced for paths, hashes and the
  log. No italic labels; hierarchy from weight and colour, not slant.
- **Spacing**: 8 px grid — 12 px panel padding, 8 px between controls, 4 px label-to-field. Row heights
  uniform per control class (fields 28 px, buttons 30 px) so the toolbar reads as a line, not a pile.

## 2. Layout: same regions, calmer grouping

Regions stay (paths + scan options / the two lists / results + log + progress + actions). Changes:

1. **Titled borders out, section headings in.** "Directories", "Scan options", "Results" become quiet
   headings with a hairline separator, instead of boxes with etched frames.
2. **Labels above fields** rather than to their left: labels stop fighting for width, fields align in a
   column, and long labels stop pushing fields off-grid.
3. **Scan options in one compact row**: ignore field, hidden, symlinks, report destination-only, dry run,
   timeout — the same six controls, grouped as one strip under a heading.
4. **Mode becomes a segmented control** (Copy & Verify | Verify Only) instead of two independently
   coloured buttons: the choice reads as one control with two states.
5. **One primary action**: Run is the only filled accent button, bottom right. Cancel is a plain
   secondary button beside it. "Scan Files" is secondary. This makes "what do I press" obvious without a
   legend.
6. **One status line** instead of scattered labels: message on the left, work-set preview in the middle,
   elapsed / last-activity on the right.
7. **Progress gets context**: a thin bar with `Copying · 412 of 1 000 files · 300 kB/s` above it, instead
   of a 25 px bar with a percentage alone.
8. **Results table**: drop full-row tinting for subtle row separators and a status column with a colour
   dot plus the word; sortable columns; hash columns monospaced with fixed widths; "Only problems" stays,
   now styled as a filter chip row with the export buttons.
9. **Filter boxes get affordances**: search label, clear (×) button, right-aligned muted count.
10. **Log**: theme-coloured instead of a black rectangle, monospaced, line numbers gutter off, newest at
    the bottom, `Ctrl+L` clears.

Nothing is removed, moved to a hidden menu, or renamed — the harness asserts on these controls today.

## 3. Theme choice and behaviour

- Default: **follow the OS** (FlatLaf detects dark mode), with an explicit override in preferences:
  System / Light / Dark. Existing preference keys are untouched; a `theme` key is added.
- Dark theme must be tested, not assumed: the log, table selection, status dots, disabled controls and
  focus outlines all get checked under Xvfb in both themes.
- High-DPI: with FlatLaf, `-Dflatlaf.uiScale=1.5` works; without it, document `-Dswing.aatext=true`.

## 4. Keyboard and focus

Enter runs (or scans, if nothing has been scanned yet), Esc cancels a running job, `Ctrl+R` rescans,
`Ctrl+F` focuses the filter of whichever list has focus, `Ctrl+E` exports CSV, `Ctrl+Shift+E` exports
JSON, Tab order left-to-right then top-to-bottom with visible focus rings. These are additions, and the
shortcuts are shown in tool tips so they are discoverable rather than folklore.

## 5. Order of work (status as of 2026-10-20)

Shipped: 6.1 (FlatLaf 3.7.2 + `UiTheme`), 6.2 (restyle through tokens), 6.3 (segmented mode control, one
primary action, consolidated status line with phase), 6.4 (results table with status dots and an attached
header, filter clear buttons, restyled log), 6.5 (keyboard shortcuts, contrast gate, offscreen renders,
DESIGN.md §12). The structural change that made all of this testable: the interface moved from
`FileVerifierFrame` into a panel, `FileVerifierView`.

Not done: focus/traversal audit, FlatLaf system file chooser, GUI knob for the results cap. See the
Phase 6 completion record in the roadmap.

## 6. Order of work as planned (the table the phases were executed from)

| Step | What lands | Done when |
|---|---|---|
| 6.1 | Dependency decision applied; `UiTheme` exists; FlatLaf installed with theme preference | window renders in both themes under Xvfb; 42 harness checks unchanged |
| 6.2 | All existing controls restyled through tokens; titled borders replaced by headings; labels above fields | harness unchanged; zero colour literals outside `UiTheme` |
| 6.3 | Segmented mode control, single primary action, consolidated status line, progress with context | harness unchanged; new harness checks for the segmented control and status line |
| 6.4 | Results table (status dot + word, sorting, column widths), filter affordances, log restyle | harness unchanged; results-table checks extended |
| 6.5 | Keyboard shortcuts, focus order, contrast checks (computed, not eyeballed), DESIGN.md UI section | harness ~60 checks, all passing; both themes screenshotted for the owner's review |

## 7. Acceptance checks, deliberately mechanical

- Behaviour is not allowed to change under cover of a restyle. The previous 42-check Xvfb harness could
  not run in this container (no X server), so its assertions were re-expressed in a headless harness
  (46 checks, all passing) plus committed tests: selection semantics, work-set wording, control locking
  during a job, exact totals, dry run writing nothing, `EXTRA` detection.
- New checks: no `Color(` literal outside `UiTheme`; every body font 13 px and every mono font 11 px;
  computed contrast ≥ 4.5:1 for text on its background; identical component count before and after
  (nothing removed); both themes render without a component falling back to a default colour.
- Screenshots so "minimal and modern" is judged on pixels, not on adjectives. Produced offscreen instead
  of under Xvfb: `printAll` on the laid-out panel, in light and dark, plus a close-up of the results
  table. A 100k-row render is not in the set - the panel is not validated by the toolkit when detached, so
  large-list pictures would not be representative.

## 8. Left open on purpose

- Whether the results table gets lazily rendered for very large lists (~700 MB resident at 300k rows) —
  a design question and a memory question at once, and a bigger change than a restyle.
- Whether the window should offer a two-pane mode for people who only ever copy (the full tool's options
  are a wall for that use case). Not in this phase; noted so it is a decision, not an oversight.
- Export button placement inside the results tab vs a dedicated export strip — cosmetic, decided in 6.4.
