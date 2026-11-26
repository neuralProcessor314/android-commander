# Claude Code Task: build `nccmd` — a Norton Commander–style Android file manager

Build a complete, compiling Android app in Kotlin. Package `com.nccmd`, app name `NCCMD`.
Make every decision yourself; do not ask questions. Record decisions in `DECISIONS.md`.
Deliverable: `./gradlew assembleDebug` produces an installable APK, plus passing instrumented tests.

---

## 0. Prime directives

1. **Static layout above all.** The screen composition is identical at every instant of the app's
   life. No element appears, disappears, resizes, reflows, or moves. State changes only ever change
   *the text inside a box*, never the box. The one blanking exception is §8 (rename input mode),
   where boxes stay in place and render nothing.
2. **No overlays.** `AlertDialog`, `Dialog`, `Toast`, `Snackbar`, `PopupMenu`, `ModalBottomSheet`,
   `DropdownMenu` are banned. Every question, error, and progress report is rendered as text inside
   the fixed top stripe. The soft keyboard is the only permitted overlay.
3. **No animation**, with exactly one exception: the block/underbar text cursor blinks at 500 ms.
   Nothing else. No transitions, no ripples, no fades, no scroll fling, no spinners, no animated
   progress bars, no scroll physics. Set `LocalIndication` to a no-op; no `animate*AsState` in the
   tree except the cursor blink.
4. **Speed is bounded only by the user's APM.** No long presses, no double-tap windows, no hold
   gestures, no debounce, no repeat delay, no confirmation timeouts, no minimum interval between
   actions, no disabled-while-busy buttons. See §7.1 — this is a hard requirement.
5. **Two bug fixes below (§3, §4) are the reason this app exists.** They are the acceptance bar.

## 1. Project setup

- Kotlin 2.x, Jetpack Compose (BOM current), single `:app` module, `minSdk 30`, `targetSdk 35`.
- No DI framework, no Room, no navigation library, no third-party file libs. Coroutines + Compose.
- `android:windowSoftInputMode="adjustNothing"` — the layout must never resize for the IME.
- Locked to portrait. `configChanges` handles orientation/size so nothing ever re-lays out.
- Not for Play distribution; ignore the all-files-access policy lint warning.

## 2. Storage backend

`interface FsOps` with the operations in §3–§4. Two implementations behind it:

- **`JavaFileOps` (primary)** — `java.io.File` + `android.system.Os`. Requires all-files access:
  declare `MANAGE_EXTERNAL_STORAGE`, check `Environment.isExternalStorageManager()`, request via
  `Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)`. Until granted, the stripe reads
  `NO ALL-FILES ACCESS — PRESS OK TO GRANT` and the panes render empty rows; nothing moves.
- **`SafOps` (fallback)** — `DocumentFile` over a persisted `ACTION_OPEN_DOCUMENT_TREE` grant.
  Used only where the primary backend is denied by the platform: `/Android/data`, `/Android/obb`
  (blocked for all-files-access apps since Android 11/12), and removable volumes that return EACCES.
  Route per-path at the `FsOps` layer; the UI must not know which backend is in play.

All I/O on `Dispatchers.IO`, funnelled through a single `OperationQueue` (a `Channel<Operation>`
consumed by one worker) so exactly one mutating operation runs at a time. The queue never blocks
input: commands issued while an operation runs are enqueued, not dropped and not greyed out.

After any mutation call `MediaScannerConnection.scanFile` on both the old and the new path so
stale MediaStore rows do not resurrect the old name.

---

## 3. BUG FIX 1 — case-only rename (`Foo` → `foo`)

Naive implementations do a case-insensitive existence test, find the source itself, and report
"name already in use". Additionally the emulated volume may be genuinely case-insensitive
(casefolded ext4/f2fs, or a vendor sdcardfs shim), and SD cards on exFAT/FAT32 always are.

### Required algorithm

```kotlin
fun rename(src: File, newName: String): Result<File> {
    require(newName.isValidFsName())            // no '/', no NUL, not "." / "..", not blank
    if (newName == src.name) return Result.success(src)   // exact match: no-op success
    val target = File(src.parentFile, newName)

    val caseOnly = newName.equals(src.name, ignoreCase = true)

    if (!caseOnly) {
        if (target.exists()) return conflict(target)      // real conflict -> stripe prompt
        return if (src.renameTo(target)) Result.success(target) else fail(RENAME_FAILED)
    }

    // ---- case-only path ----
    // Do NOT use exists(): on a case-insensitive volume it resolves back to src.
    // Identity is decided by (st_dev, st_ino), not by name.
    if (target.exists() && !isSameInode(src, target)) return conflict(target)

    val tmp = uniqueTemp(src.parentFile)        // ".nccmd_<uuid>.tmp", loop until !exists()
    if (!src.renameTo(tmp)) return fail(RENAME_TEMP_FAILED)
    if (!tmp.renameTo(target)) {
        tmp.renameTo(src)                       // rollback; if this also fails, report ORPHANED_TEMP
        return fail(RENAME_FINAL_FAILED)
    }
    // Verify the stored case actually changed (casefolding volumes may keep the old case).
    val stored = src.parentFile.list()?.firstOrNull { it.equals(newName, true) }
    if (stored != newName) return fail(CASE_NOT_STORABLE)
    return Result.success(target)
}

fun isSameInode(a: File, b: File): Boolean {
    val sa = Os.lstat(a.path); val sb = Os.lstat(b.path)
    return sa.st_dev == sb.st_dev && sa.st_ino == sb.st_ino
}
```

Also implement `probeCaseSensitivity(dir: File): CaseMode` — create `.nccmd_probe_<uuid>A`, test
whether `.nccmd_probe_<uuid>a` resolves to the same inode, delete the probe. Cache the result per
storage-volume root; invalidate on `ACTION_MEDIA_MOUNTED`/`UNMOUNTED`. Use it only for the
`CASE_NOT_STORABLE` message wording, never to pre-emptively refuse the rename.

---

## 4. BUG FIX 2 — moving `~/foo` into `~/bar` when `~/bar/foo` exists

The correct behaviour is a **recursive merge with per-entry conflict resolution**, never a
whole-operation abort.

### Required algorithm

```kotlin
suspend fun move(sources: List<File>, destDir: File, policy: ConflictPolicy) {
    for (src in sources) {
        val s = src.canonicalFile; val d = destDir.canonicalFile
        if (s.parentFile == d) { report(NOOP_SAME_PARENT, s); continue }
        if (d == s || d.path.startsWith(s.path + File.separator)) { report(ERR_INTO_SELF, s); continue }
        moveOne(s, File(d, s.name), policy)
    }
}

suspend fun moveOne(src: File, target: File, policy: ConflictPolicy) {
    if (!target.exists()) {
        if (src.renameTo(target)) return                 // same volume: atomic
        copyTree(src, target); verifyTree(src, target); deleteTree(src)   // EXDEV fallback
        return
    }
    when {
        src.isDirectory && target.isDirectory -> {        // <-- THE FIX: merge, do not error
            target.mkdirs()
            for (child in src.listFiles().orEmpty()) moveOne(child, File(target, child.name), policy)
            if (src.listFiles().isNullOrEmpty()) src.delete() else report(WARN_SOURCE_NOT_EMPTY, src)
        }
        src.isFile && target.isFile -> when (policy.resolve(src, target)) {
            SKIP      -> report(SKIPPED, src)
            OVERWRITE -> { target.delete(); moveOne(src, target, policy) }
            RENAME    -> moveOne(src, autoSuffix(target), policy)   // "name (2).ext", then (3)…
        }
        else -> when (policy.resolve(src, target)) {      // dir-vs-file type clash
            SKIP   -> report(SKIPPED, src)
            RENAME -> moveOne(src, autoSuffix(target), policy)
            OVERWRITE -> report(ERR_TYPE_CLASH, src)      // never silently destroy a tree
        }
    }
}
```

- `ConflictPolicy` carries an optional remembered action for the remainder of the operation
  (`OVERWRITE ALL` / `SKIP ALL` / `RENAME ALL`), asked once in the stripe.
- Never follow symlinks when walking (`Os.lstat`); move/copy the link itself.
- `copyTree` writes to `<name>.ncpart`, `fd.sync()`s, then renames into place; preserves
  `lastModified`; 1 MiB buffer; never leaves a partial file on cancellation.
- `deleteTree` is post-order and only runs after `verifyTree` confirms size + count parity.
- A failing entry never aborts the queue. Failures accumulate; the stripe ends with
  `DONE 118 OK / 3 FAILED — OK TO VIEW`, and `OK` pages the error list through the stripe.

---

## 5. Layout (fixed, portrait, exact)

```
+==========================================================+
| STRIPE — fixed height, exactly 4 text lines, ALWAYS drawn |
| line 1: state tag   IDLE / CONFIRM / INPUT / BUSY / ERROR |
| line 2: question, message, or rename editor               |
| line 3: source path (ellipsised head, never wrapped)      |
| line 4: target path (ellipsised head, never wrapped)      |
+==========================================================+
| A> /storage/emulated/0                    [ACT] 2/7      |  pane A header, 1 line
|----------------------------------------------------------|
| ..                                                        |
| Documents/                                     <DIR>      |
| foo/                                           <DIR>      |
| readme.txt                          1.2K  2026-08-01      |
| (exactly PAGE_SIZE rows, blanks included, hairline each)  |
|----------------------------------------------------------|
| 134 ITEMS   3 SEL   1.4G FREE                             |  pane A footer, 1 line
+==========================================================+
| B> /storage/emulated/0/bar                      -   1/1  |  pane B, identical structure
| ...                                                       |
+==========================================================+
| TAB | SWAP| PREV| NEXT| OPEN| EXPL| ALL | NONE|   row 1
| COPY| MOVE| RENM| MKDR| DEL | OK  | CNCL| EXIT|   row 2
+==========================================================+
```

- Stripe height is computed once from the monospace line height × 4 and **hard-fixed**. Idle state
  fills unused lines with `-`. It never grows, shrinks, or scrolls.
- **Pagination, not scrolling.** Each pane shows exactly `PAGE_SIZE` rows, always. A directory with
  fewer entries pads with blank rows that still carry their hairline separators. There is no
  scrollable list anywhere in the app; `LazyColumn`/`verticalScroll` must not appear in the source.
- `PAGE_SIZE` = 20, fixed at first layout. If 2×20 rows + chrome + the 2×8 bar cannot fit at the
  minimum readable monospace size (8sp floor), reduce `PAGE_SIZE` to the largest value that fits
  exactly, then hold it constant for the process lifetime. Choose the font size by measuring once,
  not by scrolling or clipping.
- Page indicator `n/N` sits in the pane header at the right, immediately after the active marker.
  The active pane shows `[ACT]`, the inactive one shows `-` in the same column width. Both always
  show their page counter; a single-page directory reads `1/1`.
- The two panes split the remaining height 50/50, always. Bottom bar is a fixed **2 rows × 8
  columns** grid of equal cells — 16 cells, all filled, no blanks — never scrolled, never hidden,
  unaffected by IME insets. Cell width is exactly one eighth of the screen width.
- Long paths: ellipsise the **head** (`…/emulated/0/bar/baz`), never wrap to a second line.

## 6. Visual system

- Background `#000000`. Foreground `#FFFFFF`. The only other state is inversion (white background,
  black text), used for **selected entries** and for the active pane's `[ACT]` marker. No colour.
- Typeface: monospace only (`FontFamily.Monospace`). One size for entries, one for chrome.
- Separators: 1 px `#FFFFFF` horizontal hairline between every row, including blank pad rows, and
  around every box. No rounded corners, no elevation, no shadow, no padding beyond one char cell.
- No icons, no thumbnails, no MIME colouring. Directories are suffixed `/`, symlinks `@`.
- Entry row: `NAME` (flex) · `SIZE` (right, 6 ch) · `MTIME` (right, `yyyy-MM-dd`). Directories show
  `<DIR>` in the size column. Columns are fixed-width and always present.
- Sort is fixed: directories first, then files, case-insensitive name ascending. No sort UI.
- Button labels are bare verbs of **at most 4 characters**, exactly as listed in §7.3, centred in
  their cell and never truncated or wrapped. The bar has its own fixed label size, measured once so
  that a 4-character label fits one eighth of the screen width with one blank column either side;
  it may be smaller than the entry size but never varies at runtime.
- **No F-key numbers on the buttons.** The F-key
  bindings in §7.3 exist only as the hardware-keyboard analogy to the original; they are never
  drawn.

## 7. Interaction

### 7.1 APM ceiling — hard requirement

- **No long press anywhere.** No `combinedClickable`, no `detectTapGestures(onLongPress = …)`, no
  `setOnLongClickListener`, no timers gating any input path. A grep for `LongPress`/`onLongClick`
  in the source must return nothing; make this an actual test.
- Every button and entry fires on **touch-down**, via
  `pointerInput { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); fire() } }` — not
  `Modifier.clickable`, which waits for up and applies slop and ripple.
- No debounce, no throttle, no "ignore taps within N ms", no double-tap detection, no disabled
  state while an operation runs. Every down-event is one command, queued in order.
- Command dispatch is decoupled from recomposition: pushes go into an unbounded channel so that
  taps arriving faster than the frame rate are all processed in order.

### 7.2 Selection and navigation

- **Tap an entry to select it. Tap it again to deselect.** That is the whole model; there is no
  separate cursor. Multi-select is just repeated taps on different entries.
- Selection is per-pane, persists across page turns within a directory, and is cleared on directory
  change. Selected rows render inverted.
- `..` occupies row 1 of page 1 and is selectable like any other entry; `OPEN` on it goes to the
  parent directory.
- **Vertical swipe inside a pane turns one page** — down-swipe = next page, up-swipe = previous.
  It is a discrete page turn triggered the moment the drag passes a fixed distance threshold: no
  velocity, no fling, no partial offset, no animation, and it also focuses that pane. `PREV`/`NEXT`
  do the same on the active pane. Both clamp at the ends; they never wrap.

### 7.3 Commands

| Button | Action |
|---|---|
| `TAB`  | move focus to the other pane (hardware `Tab`) |
| `SWAP` | exchange contents of pane A and pane B: path, selection, page |
| `PREV` / `NEXT` | page the active pane (hardware `PgUp` / `PgDn`, arrows) |
| `OPEN` | with **exactly one** entry selected: enter it if a directory, else fire `ACTION_VIEW` with a `FileProvider` URI and `FLAG_GRANT_READ_URI_PERMISSION`. With zero or ≥2 selected: **do nothing**, silently. (hardware `Enter`) |
| `EXPL` | hand the active pane's current directory to the system file explorer (§7.4) |
| `ALL` / `NONE` | select every entry in the active pane's directory (all pages, `..` excluded) / clear the selection |
| `COPY` | copy the active pane's selection into the other pane's directory (hardware `F5`) |
| `MOVE` | move the active pane's selection into the other pane's directory (hardware `F6`) |
| `RENM` | rename the active pane's selection, one by one (§8) |
| `MKDR` | new directory in the active pane; stripe input (hardware `F7`) |
| `DEL`  | permanently delete the active pane's selection after stripe confirmation (hardware `F8`) |
| `OK` / `CNCL` | answer whatever the stripe is asking; `OK` is inert in `Idle` (hardware `Enter` / `Esc`) |
| `EXIT` | quit after stripe confirmation (hardware `F11`) |

Every mutating command with an empty selection is a silent no-op, not an error.

### 7.4 `EXPL` — open current directory in the system file explorer

Try in order, each guarded by `resolveActivity`, and verify on-device which one actually lands:

1. `ACTION_VIEW` on `DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
   "primary:<relative-path>")` with type `vnd.android.document/directory`.
2. `ACTION_VIEW` with type `resource/folder` on the same URI.
3. `ACTION_OPEN_DOCUMENT_TREE` with `EXTRA_INITIAL_URI` set to that document URI (documented and
   reliable, though it lands in a picker rather than the explorer proper).

If none resolve, report `NO_EXTERNAL_EXPLORER` in the stripe. Do not hard-code a vendor package as
the only path; if a Samsung MyFiles component is used as a step, keep it behind the same guard.

## 8. Rename mode (`RENM`)

- Renames the active pane's selection **sequentially**, in the pane's sort order, one prompt each.
- On entry, **every UI element except the stripe renders nothing**: both panes and all sixteen
  bar cells draw empty black boxes. They keep their exact geometry and hairlines — this is blanking,
  not removal, and nothing reflows when the mode ends.
- Stripe line 2 is the editor, seeded empty, showing the literal form:

  ```
  Rename foo to (1/3): _
  ```

  `foo` is the current entry's name; `(k/n)` is position within the batch; `_` is a blinking
  underbar cursor at 500 ms, the app's only animation. Line 3 shows the full source path, line 4
  shows the resulting path live as it is typed.
- The IME is open for the whole mode. **Done/Enter** commits the current name via §3 and advances
  to `k+1`; **Back/Esc** cancels the entire remaining batch and restores the normal display.
  There are no buttons in this mode, by design.
- A conflict or failure at step `k` switches the stripe to `Confirm`/`Result` for that one entry,
  still in blanked mode, then resumes at `k+1`. One bad name never kills the batch.
- Empty input, or input identical to the current name, is a no-op that advances the batch.

## 9. Stripe protocol

`sealed interface StripeState { Idle; Confirm(q, src, dst, actions); Input(label, buffer, validator);
Busy(verb, done, total, current); Result(summary, errors) }`

- Exactly one `StripeState` at a time, owned by the operation queue.
- `Busy` updates render as plain text (`MOVE 34/128 bar/baz.txt`) and are coalesced to 4 Hz for
  output only — this must never gate or delay input handling.
- `Result` persists until `OK`; it does not time out or auto-dismiss.

## 10. Error taxonomy (all surfaced as stripe text, never as exceptions to the user)

`RENAME_TEMP_FAILED`, `RENAME_FINAL_FAILED`, `ORPHANED_TEMP`, `CASE_NOT_STORABLE`,
`ERR_INTO_SELF`, `ERR_TYPE_CLASH`, `NOOP_SAME_PARENT`, `WARN_SOURCE_NOT_EMPTY`,
`EACCES_NEEDS_SAF`, `ENOSPC`, `EXDEV_FALLBACK_USED`, `NO_EXTERNAL_EXPLORER`, `CANCELLED`.
Each maps to one line of ≤ 56 characters of uppercase ASCII.

## 11. Acceptance tests

Filesystem (instrumented, on `getExternalFilesDir` and on `/storage/emulated/0`):

1. `Foo/` → `foo/` succeeds; the listing afterwards contains exactly `foo`, not `Foo`.
2. `Foo/` → `foo/` on a volume that cannot store the new case yields `CASE_NOT_STORABLE`, with the
   directory and its contents intact and no orphaned `.nccmd_*.tmp`.
3. `Foo.txt` → `foo.txt` when a *distinct* `foo.txt` exists yields a conflict prompt, not silent loss.
4. Move `~/foo` → `~/bar` where `~/bar/foo` exists with disjoint children: result is a merged
   `~/bar/foo` holding the union; `~/foo` is gone.
5. Same as 4 with a colliding file: exactly one prompt; `OVERWRITE ALL` resolves the rest.
6. Move `~/foo` → `~/foo/sub`: `ERR_INTO_SELF`, nothing modified.
7. Cross-volume move: copy + verify + delete, source removed only after verification.
8. Process killed mid-copy: no `.ncpart` entry is ever visible (filtered from listings, swept on start).

UI:

9. Source contains no `onLongClick`, `combinedClickable`, `LazyColumn`, or `verticalScroll`.
10. Every button responds to a synthetic `ACTION_DOWN` alone, with no `ACTION_UP`.
11. 30 taps injected in 1 s produce 30 ordered commands, none dropped or coalesced.
12. Tapping an entry twice leaves it unselected; tapping three distinct entries leaves three selected.
13. `PAGE_SIZE` rows are laid out in every state, including an empty directory and a 1-entry directory.
14. Page indicator reads `1/1` for a directory with ≤ `PAGE_SIZE` entries and `⌈n/PAGE_SIZE⌉` otherwise;
    selection made on page 1 survives a trip to page 3 and back.
15. `OPEN` with 0 and with 2 selected entries produces no intent and no stripe change.
16. Entering rename mode changes no measured bound of the stripe, panes, or bar; leaving it restores
    identical pixel geometry.
17. The stripe and all sixteen bar cells occupy identical positions in `Idle`, `Confirm`, `Input`,
    `Busy`, and `Result`.

## 12. Non-goals — do not build

Trash/recycle, cloud, archives, search, thumbnails, sort options, themes, settings screen, tabs,
bookmarks, drag-and-drop, root/su, onboarding, splash, about box, telemetry, scrolling.
