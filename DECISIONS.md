# DECISIONS

## Post-build UX changes requested by the user (deviations from the literal instruction text)

- **No hairline between individual entry rows** (instruction section 6 literally says "hairline
  between every row, including blank pad rows"). Removed after real-device testing showed
  descenders and underscores in filenames visually colliding with a 1px line sitting immediately
  under them. User cited FAR Manager (a real Norton Commander port) as not having row separators
  either. Hairlines are kept everywhere else: around the pane box, and between header/list/footer.
- **Bottom bar: OPEN replaced by UP.** UP goes up one directory in the active pane with a single
  tap, no selection required. OK now also performs OPEN's old job (open the active pane's single
  selection) when idle with nothing else pending -- chosen over the alternative of dropping NONE
  or EXPL instead, per explicit user preference.
- **Switching the active pane (TAB/tap-header/swipe-focus) is now blocked while the *leaving*
  pane has a selection**, instead of instruction section 7.2's implicit allowance of independent
  per-pane selections coexisting simultaneously. User found ending up with selections active in
  both panes at once confusing. SWAP is unaffected (it doesn't create that situation -- it moves
  each pane's whole state, selection included, rather than adding a second independent one).
- **Blocked/no-op actions now say why, via the stripe.** Instruction section 7.3 explicitly calls
  empty-selection mutations and 0/2+-selected OPEN "silent" no-ops; user asked for a reason shown
  instead, matching the pattern used for the new pane-switch block above. There's no toast/timer
  mechanism in this app's stripe protocol (section 0.2/9 ban both), so this reuses `Result`,
  dismissed by OK exactly like any other Result. Applied to: COPY/MOVE/DELETE/RENM with nothing
  selected ("NOTHING SELECTED"), OPEN/OK-as-OPEN with 2+ selected ("SELECT ONLY ONE ITEM TO
  OPEN"), and the new pane-switch block ("CLEAR SELECTION BEFORE SWITCHING PANES"). The single
  case left silent is OPEN/OK-as-OPEN with *zero* selected, since OK now fires on every idle tap
  (see the OPEN/OK merge above) and flashing a message for that specific case would fire on
  essentially every idle button press -- a judgment call, not a literal reading of "same for all
  the preventions we made"; worth revisiting if the user wants that case covered too.

## Crash fix: zero-sized focus-requested BasicTextField (MKDR/RENM)

`StripeView.kt`'s hidden IME host (used to drive the keyboard for MKDR's name input, RENM's batch
editor, and multi-option Confirm prompts) was wrapped in `Modifier.size(0.dp)` with
`.focusRequester(...).size(0.dp)` on the field itself. A zero-area component that's also asked to
take focus is a known Compose Foundation landmine -- the focus/IME-positioning system can throw
on a degenerate zero-size target. Both MKDR and RENM go through this exact same code path (both
set `wantsIme = true`), which is why both crashed identically. Fixed by sizing it to 1.dp instead
of 0.dp; the text/cursor colors are already fully transparent so it's still visually invisible.

Decisions made while building this app from `ANDROIDCOMMANDER_BUILD_INSTRUCTION.md`, without
asking the user, per the instruction file's own directive.

## Naming (name conflict override)

The instruction file specifies package `com.nccmd` / app name `NCCMD`. The user explicitly
overrode this: the working directory and project are named "androidcommander", and Android
Commander is to be followed over `nccmd` wherever the two disagree. Applied throughout:

- Package: `com.androidcommander` (not `com.nccmd`)
- App label: `Android Commander` (not `NCCMD`)
- Module/class prefixes, manifest authority (`com.androidcommander.fileprovider`), temp-file
  prefix (`.ac_<uuid>.tmp` instead of `.nccmd_<uuid>.tmp`), partial-copy suffix stays `.ncpart`
  as specified in §4 verbatim (it's a file-format detail, not a product name) — kept as-is since
  the instruction gives it as a literal suffix, not derived from "nccmd".
- Probe-file prefix: `.ac_probe_<uuid>` (was `.nccmd_probe_<uuid>`).
- Everything else in the instruction file (architecture, algorithms, layout, acceptance tests)
  is followed as specified.

## Build environment

Updated after a real build: no JDK/Android SDK/Gradle were on PATH initially, and this sandbox's
network egress is heavily throttled for large downloads (observed well under 1 KB/s at times), so
fetching Gradle (~136MB) and the SDK cmdline-tools (~154MB) took a long resumable retry loop until
the user switched networks, after which it went at ~450-500 KB/s. Toolchain used: the JDK bundled
with the already-installed Android Studio (`Android Studio\jbr`, OpenJDK 21), a manually-fetched
Gradle 8.9 distribution (invoked directly via its own `bin/gradle.bat`, since the wrapper jar
still isn't committed here), and Android SDK platforms 34+35 with build-tools 34.0.0 installed via
`sdkmanager` from a manually-fetched cmdline-tools package. `local.properties` (gitignored,
machine-specific `sdk.dir`) now exists in the repo root pointing at that SDK.

`./gradlew` still won't work standalone until someone runs `gradle wrapper` once (or opens the
project in Android Studio, which regenerates `gradle-wrapper.jar`) — the wrapper jar binary
still isn't committed. Use Android Studio's own bundled Gradle for that, or the same
`gradle.bat`-direct approach used here in the meantime.

**Compile errors found and fixed** (this codebase had never been run through a compiler before):
- `PaneView.kt`, `BottomBar.kt`, `AppScreen.kt`: an explicit `import androidx.compose.foundation.layout.weight`
  was wrongly resolving to Compose Foundation's *internal* `RowColumnParentData.weight` property
  instead of the `RowScope`/`ColumnScope` member extension function. `Modifier.weight(...)` needs
  no import at all inside a `Row`/`Column` lambda (it resolves via the implicit scope receiver);
  removing the import fixed all three files.
- `SafOps.kt`: `resolve()`'s `var doc = DocumentFile.fromTreeUri(...) ?: return null` inferred
  `doc`'s type as non-null `DocumentFile` from the first assignment, so the later
  `doc = next ?: ...` (nullable) reassignment failed to type-check. Fixed with an explicit
  `var doc: DocumentFile?` annotation.
- `Theme.kt`: the `Indication`/`IndicationInstance` API used for the no-ripple override is
  deprecated at `DeprecationLevel.ERROR` in the Compose Foundation version pulled in by BOM
  2024.09.02, and `LocalIndication` lives in `androidx.compose.foundation`, not
  `androidx.compose.ui.platform`. Rewrote `NoIndication` as an `IndicationNodeFactory` returning
  a no-op `Modifier.Node()` (the modern replacement), which also requires explicit `equals`/
  `hashCode` overrides since `Indication` declares them abstract.

**Verified**: `assembleDebug` succeeds (`app/build/outputs/apk/debug/app-debug.apk`, ~22.7MB,
`com.androidcommander`, minSdk 30 / targetSdk 35, signed with the standard debug key — confirmed
via `apksigner verify`). `testDebugUnitTest` passes all 6 local tests (5 `PaneStateTest` +
`SourceScanTest`, the grep-for-banned-APIs test). The instrumented tests under `androidTest`
(rename/move bug-fix tests, touch-down dispatch tests) still need a real or emulated device to
run — not exercised in this pass, no emulator available here.

## Architecture choices left open by the spec

- State container: a single `MainViewModel` (plain `ViewModel`, no DI framework per §1) owns
  two `PaneState`s, the `StripeState`, and the rename-mode batch. `AndroidCommanderState` is the
  root UI state, recomposed from a single `StateFlow`.
- Command dispatch: an unbounded `Channel<Command>` per §7.1, drained by a single coroutine on
  the ViewModel scope, so taps queue in arrival order regardless of frame rate.
- `OperationQueue` (§2) is a separate `Channel<Operation>` with its own single-consumer worker on
  `Dispatchers.IO`; it is distinct from the command channel so navigation/selection commands
  (which do not mutate the filesystem) are never blocked behind a running copy/move.
- Routing between `JavaFileOps` and `SafOps` (§2) is a `RoutingFsOps` that pattern-matches the
  canonical path prefix (`/Android/data`, `/Android/obb`) and falls back to SAF on `EACCES` from
  `JavaFileOps` elsewhere (e.g. some removable volumes), surfacing `EACCES_NEEDS_SAF` if no SAF
  grant is held for that tree yet.
- Font/PAGE_SIZE sizing (§5): computed once in `AppScreen`'s first `onGloballyPositioned` /
  `Layout` pass using `TextMeasurer`, then held in a `remember { mutableStateOf }` seeded once —
  never recomputed after the first measurement, per "hold it constant for the process lifetime".
- `ConflictPolicy` "remembered action for the remainder of the operation" is modeled as a
  per-operation mutable field (`rememberedAction: Action?`) checked before prompting; the stripe
  question offers `OVERWRITE` / `SKIP` / `RENAME` plus `...ALL` variants that set it.
- Minimum instrumented test coverage targets the two named bug fixes (§3 case-only rename, §4
  merge-not-abort move) plus the source-scan test for banned APIs (§11.9), the touch-down-only /
  no-drop-under-rapid-taps dispatch tests (§11.10-11), and pure-logic coverage of selection and
  pagination math (§11.12-14). Tests 15-17 (OPEN no-op on 0/2 selected, rename-mode geometry
  invariance, cross-state stripe/bar position invariance) are not covered by an automated test in
  this pass — the layout code is structured so those invariants hold by construction (the stripe/
  panes/bar are always-present composables whose *content* changes, never conditionally-composed
  subtrees, except rename mode's deliberate blanking), but that has not been verified on a device.

## Known gaps / what to check first

I could not run a compiler in this environment (no JDK/Android SDK/Gradle on PATH), so nothing
here has been build-verified. Open the project in Android Studio (already installed) and let it
sync/provision the SDK; expect to fix compiler errors on a codebase this size written blind.
Specific known simplifications, in rough priority order:

1. **Conflict-prompt UX for >2-way answers is a designed extrapolation, not spec text.** The
   instruction gives only OK/CNCL as bar buttons but describes six-way conflict resolution
   (SKIP/OVERWRITE/RENAME x single/ALL). I resolved this by opening the IME for a single digit
   keypress when a `Confirm` has more than two options, with OK defaulting to option 1 and CNCL
   mapped to whichever option is flagged `isCancel` (see `StripeView.kt`, `MainViewModel.kt`).
   Reasonable, but worth confirming against user intent.
2. **EACCES-to-SAF handoff is heuristic.** `RoutingFsOps.writable()` guesses accessibility from
   `File.canWrite()` rather than parsing a real `EACCES` errno, and `MainViewModel.needsSafGrant`
   triggers a SAF picker on *any* listing failure without an existing grant, not just genuine
   permission walls. Functional but coarse.
3. **`LayoutMetrics` sizing math is unverified.** The one-time font-size/PAGE_SIZE measurement in
   `computeLayoutMetrics` follows the algorithm in §5 but has never been run against a real
   `TextMeasurer` on a device; the row-height reconciliation between that measurement and the
   `weight(1f)`-based Compose layout in `PaneView`/`AppScreen` is a reasonable approximation, not
   a pixel-exact implementation of "measured once."
4. **SAF backend (`SafOps`) is functionally complete but less battle-tested** than the primary
   `JavaFileOps` path, since the two acceptance-bar bug fixes (§3, §4) only need to hold on the
   primary backend to satisfy the instruction's own framing ("the reason this app exists").
5. Gradle wrapper jar (`gradle/wrapper/gradle-wrapper.jar`) is not included — binary jars aren't
   hand-writable here. Either run `gradle wrapper` once with a local Gradle install, or just let
   Android Studio's sync regenerate it.
