package com.androidcommander

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.androidcommander.fs.Action
import com.androidcommander.fs.BatchProgress
import com.androidcommander.fs.BatchResult
import com.androidcommander.fs.ConflictPolicy
import com.androidcommander.fs.FsError
import com.androidcommander.fs.FsOps
import com.androidcommander.fs.JavaFileOps
import com.androidcommander.fs.OpReport
import com.androidcommander.fs.OperationQueue
import com.androidcommander.fs.RenameOutcome
import com.androidcommander.fs.RoutingFsOps
import com.androidcommander.fs.SafGrantStore
import com.androidcommander.fs.SafOps
import com.androidcommander.fs.isValidFsName
import com.androidcommander.model.Command
import com.androidcommander.model.ConfirmOption
import com.androidcommander.model.PaneId
import com.androidcommander.model.PaneState
import com.androidcommander.model.SideEffect
import com.androidcommander.model.StripeState
import com.androidcommander.model.selectionKey
import com.androidcommander.util.ellipsiseHead
import com.androidcommander.util.sortedForDisplay
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppState(
    val paneA: PaneState,
    val paneB: PaneState,
    val active: PaneId,
    val stripe: StripeState,
    val hasAllFilesAccess: Boolean,
)

/**
 * The app's fixed navigation root (matches the "A> /storage/emulated/0" example in instruction
 * section 5). ".." is never offered above this -- going further up leads into paths like
 * /storage/emulated, which aren't meaningfully browsable and previously fell through to an
 * unexpected SAF picker with no way back.
 */
private val ROOT = File("/storage/emulated/0")

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val grants = SafGrantStore(app)
    private val javaOps = JavaFileOps()
    private val safOps = SafOps(app, grants)
    private val fsOps: FsOps = RoutingFsOps(javaOps, safOps, grants)
    private val opQueue = OperationQueue(viewModelScope)

    private val commandChannel = Channel<Command>(Channel.UNLIMITED)
    private val effectChannel = Channel<SideEffect>(Channel.UNLIMITED)
    val sideEffects = effectChannel.receiveAsFlow()

    @Volatile private var pendingConfirm: CompletableDeferred<ConfirmOption>? = null
    @Volatile private var pendingInput: CompletableDeferred<String?>? = null
    @Volatile private var pendingAck: CompletableDeferred<Unit>? = null

    var pageSize: Int = 20
        private set
    private var pageSizeLocked = false

    private val _state = MutableStateFlow(
        AppState(
            paneA = PaneState(PaneId.A, ROOT, hasParent = false),
            paneB = PaneState(PaneId.B, ROOT, hasParent = false),
            active = PaneId.A,
            stripe = StripeState.Idle,
            hasAllFilesAccess = false,
        ),
    )
    val state: StateFlow<AppState> = _state

    init {
        viewModelScope.launch {
            for (cmd in commandChannel) handle(cmd)
        }
    }

    fun dispatch(cmd: Command) {
        commandChannel.trySend(cmd)
    }

    fun lockPageSize(n: Int) {
        if (!pageSizeLocked) {
            pageSize = n
            pageSizeLocked = true
        }
    }

    fun setAllFilesAccess(granted: Boolean) {
        val was = _state.value.hasAllFilesAccess
        _state.update { it.copy(hasAllFilesAccess = granted) }
        if (granted && !was) {
            refreshPane(PaneId.A)
            refreshPane(PaneId.B)
        }
    }

    fun onSafGrantReceived(treeUri: android.net.Uri, rootPath: String) {
        grants.register(treeUri, rootPath)
        refreshPane(PaneId.A)
        refreshPane(PaneId.B)
    }

    fun onExploreResult(success: Boolean) {
        if (!success) {
            _state.update { it.copy(stripe = StripeState.Result("NO EXTERNAL EXPLORER", emptyList())) }
        }
    }

    fun onAllFilesAccessRequestFailed() {
        _state.update { it.copy(stripe = StripeState.Result("COULD NOT OPEN PERMISSION SETTINGS", emptyList())) }
    }

    // ---------------------------------------------------------------- command handling

    private suspend fun handle(cmd: Command) {
        // "NO ALL-FILES ACCESS -- PRESS OK TO GRANT" (instruction section 2): while ungranted
        // and idle, OK requests the permission instead of being inert.
        if (cmd == Command.Ok && !_state.value.hasAllFilesAccess && _state.value.stripe is StripeState.Idle) {
            effectChannel.trySend(SideEffect.RequestAllFilesAccess)
            return
        }

        // Answers to a pending stripe question are consumed here regardless of what else is
        // going on; everything else executes immediately (instruction section 7.1: no
        // disabled-while-busy anything).
        when (cmd) {
            is Command.Ok -> if (resolvePendingOk()) return
            is Command.Cancel -> if (resolvePendingCancel()) return
            is Command.ImeDigit -> if (resolvePendingDigit(cmd.digit)) return
            is Command.ImeDone -> if (resolvePendingDone()) return
            is Command.ImeCancel -> if (resolvePendingInputCancel()) return
            is Command.ImeText -> if (updatePendingBuffer(cmd.text)) return
            else -> {}
        }

        when (cmd) {
            Command.Tab -> trySwitchActive(_state.value.active.other())
            Command.Swap -> swapPanes()
            Command.Prev -> pageTurn(_state.value.active, forward = false)
            Command.Next -> pageTurn(_state.value.active, forward = true)
            is Command.SwipePane -> {
                if (trySwitchActive(cmd.pane)) pageTurn(cmd.pane, forward = cmd.forward)
            }
            is Command.FocusPane -> trySwitchActive(cmd.pane)
            is Command.TapEntry -> toggleSelection(cmd.pane, cmd.rowIndex)
            Command.SelectAll -> selectAll()
            Command.SelectNone -> selectNone()
            Command.Up -> navigateUp(_state.value.active)
            Command.Explore -> effectChannel.trySend(SideEffect.ExploreDir(activePaneState().path))
            Command.Copy -> startCopyOrMove(isMove = false)
            Command.Move -> startCopyOrMove(isMove = true)
            Command.Delete -> startDelete()
            Command.Mkdir -> startMkdir()
            Command.Rename -> startRenameBatch()
            Command.Exit -> startExit()
            Command.GrantAllFiles -> effectChannel.trySend(SideEffect.RequestAllFilesAccess)
            // Nothing was pending (checked above), so this OK is answering nothing -- it now
            // doubles as OPEN (per user request: the bar's OPEN slot became UP, and OK opens the
            // active pane's single selection instead). Idle-with-nothing-selected stays inert.
            Command.Ok -> openSelection()
            Command.Cancel, is Command.ImeDigit, Command.ImeDone,
            Command.ImeCancel, is Command.ImeText,
            -> {
                // Already handled above when a pending interaction existed; no pending
                // interaction means these are inert.
            }
        }
    }

    private fun PaneId.other() = if (this == PaneId.A) PaneId.B else PaneId.A

    private fun pane(id: PaneId): PaneState = if (id == PaneId.A) _state.value.paneA else _state.value.paneB

    private fun updatePane(id: PaneId, f: (PaneState) -> PaneState) {
        _state.update {
            if (id == PaneId.A) it.copy(paneA = f(it.paneA)) else it.copy(paneB = f(it.paneB))
        }
    }

    private fun activePaneState() = pane(_state.value.active)

    /**
     * Surfaces the reason a blocked action didn't happen (user request: preventions should say
     * why, not just silently do nothing). Reuses Result -- the stripe protocol has no toast/timed
     * mechanism (instruction section 0.2/9), so OK dismisses it same as any other Result. Only
     * flashes while Idle so it never clobbers a Busy/Confirm/Input display already in progress.
     */
    private fun flashReason(reason: String) {
        if (_state.value.stripe is StripeState.Idle) {
            _state.update { it.copy(stripe = StripeState.Result(reason, emptyList())) }
        }
    }

    /** Blocks switching the active pane while it has a selection (user request). */
    private fun trySwitchActive(newActive: PaneId): Boolean {
        if (newActive == _state.value.active) return true
        if (pane(_state.value.active).selected.isNotEmpty()) {
            flashReason("CLEAR SELECTION BEFORE SWITCHING PANES")
            return false
        }
        _state.update { it.copy(active = newActive) }
        return true
    }

    // ---------------------------------------------------------------- pending-answer plumbing

    private fun resolvePendingOk(): Boolean {
        pendingAck?.let { d ->
            d.complete(Unit)
            return true
        }
        pendingConfirm?.let { d ->
            val opts = (_state.value.stripe as? StripeState.Confirm)?.options ?: return true
            d.complete(opts.first())
            return true
        }
        pendingInput?.let { d ->
            // Input (e.g. MKDR): the bar is still live, so OK submits like IME "Done".
            // RenameEditor: the bar is blanked (section 8) and can't be reached to begin with,
            // but swallow defensively rather than falling through to Idle-only handling.
            val stripe = _state.value.stripe
            if (stripe is StripeState.Input) d.complete(stripe.buffer)
            return true
        }
        if (_state.value.stripe is StripeState.Result) {
            _state.update { it.copy(stripe = StripeState.Idle) }
            return true
        }
        return false
    }

    private fun resolvePendingCancel(): Boolean {
        pendingAck?.let { d ->
            d.complete(Unit)
            return true
        }
        pendingConfirm?.let { d ->
            val opts = (_state.value.stripe as? StripeState.Confirm)?.options ?: return true
            val cancelOpt = opts.firstOrNull { it.isCancel } ?: opts.last()
            d.complete(cancelOpt)
            return true
        }
        if (pendingInput != null) {
            pendingInput?.complete(null)
            return true
        }
        if (_state.value.stripe is StripeState.Result) {
            _state.update { it.copy(stripe = StripeState.Idle) }
            return true
        }
        return false
    }

    private fun resolvePendingDigit(digit: Char): Boolean {
        val d = pendingConfirm ?: return false
        val opts = (_state.value.stripe as? StripeState.Confirm)?.options ?: return true
        val opt = opts.firstOrNull { it.key == digit } ?: return true
        d.complete(opt)
        return true
    }

    private fun resolvePendingDone(): Boolean {
        val d = pendingInput ?: return false
        val buffer = when (val s = _state.value.stripe) {
            is StripeState.Input -> s.buffer
            is StripeState.RenameEditor -> s.buffer
            else -> ""
        }
        d.complete(buffer)
        return true
    }

    private fun resolvePendingInputCancel(): Boolean {
        val d = pendingInput ?: return false
        d.complete(null)
        return true
    }

    private fun updatePendingBuffer(text: String): Boolean {
        if (pendingInput == null) return false
        _state.update {
            when (val s = it.stripe) {
                is StripeState.Input -> it.copy(
                    stripe = s.copy(buffer = text, dstPreview = if (text.isBlank()) "" else File(s.src, text).path),
                )
                is StripeState.RenameEditor -> it.copy(
                    stripe = s.copy(buffer = text, dstPreview = File(File(s.srcPath).parentFile, text).path),
                )
                else -> it
            }
        }
        return true
    }

    private suspend fun askConfirm(
        question: String,
        src: String = "",
        dst: String = "",
        options: List<ConfirmOption>,
    ): ConfirmOption {
        val d = CompletableDeferred<ConfirmOption>()
        pendingConfirm = d
        _state.update { it.copy(stripe = StripeState.Confirm(question, src, dst, options)) }
        val result = d.await()
        pendingConfirm = null
        // Always return to Idle -- a caller that immediately shows something else (e.g. Busy)
        // just overwrites this a moment later. Without this, a cancelled confirm (DELETE, EXIT)
        // left the stripe permanently stuck showing the question with nothing able to answer it.
        _state.update { it.copy(stripe = StripeState.Idle) }
        return result
    }

    private suspend fun askInput(label: String, src: String = ""): String? {
        val d = CompletableDeferred<String?>()
        pendingInput = d
        _state.update { it.copy(stripe = StripeState.Input(label, "", src, "")) }
        val result = d.await()
        pendingInput = null
        // Always return to Idle (see askConfirm for why) -- this is what was stuck open after
        // MKDR: nothing ever cleared StripeState.Input, so the stripe (and the IME it keeps
        // focused) never let go even after the directory was created successfully.
        _state.update { it.copy(stripe = StripeState.Idle) }
        return result
    }

    // ---------------------------------------------------------------- navigation

    private fun refreshPane(id: PaneId) {
        viewModelScope.launch {
            val p = pane(id)
            val listResult = fsOps.list(p.path)
            if (listResult.isFailure && needsSafGrant(p.path)) {
                effectChannel.trySend(SideEffect.RequestSafGrant(p.path))
            }
            val listing = listResult.getOrElse { emptyList() }.sortedForDisplay()
            val free = fsOps.freeSpaceBytes(p.path)
            updatePane(id) { cur ->
                val validNames = listing.map { it.name }.toSet() + if (cur.hasParent) setOf("..") else emptySet()
                val maxPage = maxOf(0, run {
                    val rows = (if (cur.hasParent) 1 else 0) + listing.size
                    (rows + pageSize - 1) / pageSize - 1
                })
                cur.copy(
                    entries = listing,
                    hasParent = p.path != ROOT && p.path.parentFile != null,
                    selected = cur.selected.filter { it in validNames }.toSet(),
                    page = cur.page.coerceIn(0, maxOf(0, maxPage)),
                    freeBytes = free,
                )
            }
        }
    }

    /**
     * A listing failure is treated as a possible SAF-fallback case (instruction section 2:
     * /Android/data, /Android/obb, and EACCES removable volumes) only for paths that plausibly
     * need it -- not the plain external-storage root, which should never legitimately fail once
     * all-files access is granted, and where a spurious transient failure would otherwise pop a
     * surprise SAF picker right after the user just finished the all-files-access grant flow.
     */
    private fun needsSafGrant(dir: File): Boolean {
        if (grants.hasGrantFor(dir.path)) return false
        val norm = dir.path.replace('\\', '/')
        val forced = norm.contains("/Android/data") || norm.contains("/Android/obb")
        val removable = norm.startsWith("/storage/") && !norm.startsWith("/storage/emulated/")
        return forced || removable
    }

    private fun enterDir(id: PaneId, newPath: File) {
        updatePane(id) { it.copy(path = newPath, page = 0, selected = emptySet()) }
        refreshPane(id)
    }

    /** UP bar button: go up one directory in the active pane. Silent no-op at the nav root. */
    private fun navigateUp(id: PaneId) {
        val p = pane(id)
        if (p.path == ROOT) return
        p.path.parentFile?.let { enterDir(id, it) }
    }

    private fun swapPanes() {
        _state.update {
            it.copy(
                paneA = it.paneB.copy(id = PaneId.A),
                paneB = it.paneA.copy(id = PaneId.B),
            )
        }
    }

    private fun pageTurn(id: PaneId, forward: Boolean) {
        updatePane(id) { p ->
            val maxPage = p.pageCount(pageSize) - 1
            val next = if (forward) (p.page + 1).coerceAtMost(maxPage) else (p.page - 1).coerceAtLeast(0)
            p.copy(page = next)
        }
    }

    private fun toggleSelection(id: PaneId, rowIndex: Int) {
        updatePane(id) { p ->
            val row = p.rowsForPage(pageSize).getOrNull(rowIndex) ?: return@updatePane p
            val key = row.selectionKey() ?: return@updatePane p
            val sel = p.selected
            // ".." is exclusive: selecting it replaces any other selection (it's a navigation
            // shortcut, not a bulk-op target -- File(pane.path, "..") would otherwise resolve to
            // the *parent directory* inside COPY/MOVE/DELETE/RENAME, a real hazard), and
            // selecting anything else always drops ".." from the set first.
            val newSelected = if (key == "..") {
                if (".." in sel) emptySet() else setOf("..")
            } else {
                val withoutParent = sel - ".."
                if (key in withoutParent) withoutParent - key else withoutParent + key
            }
            p.copy(selected = newSelected)
        }
    }

    private fun selectAll() {
        val id = _state.value.active
        updatePane(id) { it.copy(selected = it.entries.map { e -> e.name }.toSet()) }
    }

    private fun selectNone() {
        updatePane(_state.value.active) { it.copy(selected = emptySet()) }
    }

    private fun openSelection() {
        val id = _state.value.active
        val p = pane(id)
        if (p.selected.isEmpty()) return // nothing pressed OK/OPEN "at": stays silent, this fires on every idle tap
        if (p.selected.size > 1) {
            flashReason("SELECT ONLY ONE ITEM TO OPEN")
            return
        }
        val key = p.selected.first()
        if (key == "..") {
            p.path.parentFile?.let { enterDir(id, it) }
            return
        }
        val entry = p.entries.firstOrNull { it.name == key } ?: return
        if (entry.isDirectory) {
            enterDir(id, entry.file)
        } else {
            effectChannel.trySend(SideEffect.ViewFile(entry.file))
        }
    }

    // ---------------------------------------------------------------- mutations

    private fun startCopyOrMove(isMove: Boolean) {
        val srcId = _state.value.active
        val src = pane(srcId)
        val selection = src.selected - ".." // never a bulk-op target -- see toggleSelection
        if (selection.isEmpty()) {
            flashReason("NOTHING SELECTED")
            return
        }
        val destId = srcId.other()
        val destDir = pane(destId).path
        val sourceFiles = selection.map { File(src.path, it) }

        lateinit var policy: ConflictPolicy
        policy = ConflictPolicy(
            resolve = { s, target ->
                val opts = listOf(
                    ConfirmOption('1', "SKIP"),
                    ConfirmOption('2', "OVERWRITE"),
                    ConfirmOption('3', "RENAME"),
                    ConfirmOption('4', "SKIP ALL"),
                    ConfirmOption('5', "OVERWRITE ALL"),
                    ConfirmOption('6', "RENAME ALL"),
                )
                val chosen = askConfirm("EXISTS: ${target.name} (1-6)", s.path, target.path, opts)
                val action = when (chosen.key) {
                    '1', '4' -> Action.SKIP
                    '2', '5' -> Action.OVERWRITE
                    else -> Action.RENAME
                }
                if (chosen.key in charArrayOf('4', '5', '6')) policy.rememberedAction = action
                action
            },
        )

        opQueue.enqueue {
            val verb = if (isMove) "MOVE" else "COPY"
            _state.update { it.copy(stripe = StripeState.Busy(verb, 0, sourceFiles.size, "")) }
            val onProgress: suspend (BatchProgress) -> Unit = { prog ->
                _state.update {
                    it.copy(stripe = StripeState.Busy(prog.verb, prog.done, prog.total, ellipsiseHead(prog.current, 56)))
                }
            }
            val result = if (isMove) {
                fsOps.move(sourceFiles, destDir, policy, onProgress)
            } else {
                fsOps.copy(sourceFiles, destDir, policy, onProgress)
            }
            reportBatch(verb, result)
            updatePane(srcId) { it.copy(selected = emptySet()) }
            refreshPane(srcId)
            refreshPane(destId)
        }
    }

    private fun startDelete() {
        val id = _state.value.active
        val p = pane(id)
        val selection = p.selected - ".." // never a bulk-op target -- see toggleSelection
        if (selection.isEmpty()) {
            flashReason("NOTHING SELECTED")
            return
        }
        viewModelScope.launch {
            val answer = askConfirm(
                "DELETE ${selection.size} ITEMS?",
                options = listOf(ConfirmOption('1', "OK"), ConfirmOption('2', "CANCEL", isCancel = true)),
            )
            if (answer.isCancel) return@launch
            opQueue.enqueue {
                val targets = selection.map { File(p.path, it) }
                _state.update { it.copy(stripe = StripeState.Busy("DEL", 0, targets.size, "")) }
                val result = fsOps.delete(targets) { prog ->
                    _state.update {
                        it.copy(stripe = StripeState.Busy(prog.verb, prog.done, prog.total, ellipsiseHead(prog.current, 56)))
                    }
                }
                reportBatch("DEL", result)
                updatePane(id) { it.copy(selected = emptySet()) }
                refreshPane(id)
            }
        }
    }

    private fun startMkdir() {
        val id = _state.value.active
        viewModelScope.launch {
            val name = askInput("New directory name:", pane(id).path.path) ?: return@launch
            if (name.isBlank()) return@launch
            opQueue.enqueue {
                val result = fsOps.mkdir(pane(id).path, name)
                result.onFailure {
                    _state.update { st -> st.copy(stripe = StripeState.Result("MKDIR FAILED", listOf(it.message ?: ""))) }
                }
                refreshPane(id)
            }
        }
    }

    private fun startRenameBatch() {
        val id = _state.value.active
        val p = pane(id)
        val selection = p.selected - ".." // never a bulk-op target -- see toggleSelection
        if (selection.isEmpty()) {
            flashReason("NOTHING SELECTED")
            return
        }
        val batch = p.entries.filter { it.name in selection }
        opQueue.enqueue {
            for ((index, entry) in batch.withIndex()) {
                val d = CompletableDeferred<String?>()
                pendingInput = d
                _state.update {
                    it.copy(
                        stripe = StripeState.RenameEditor(
                            currentName = entry.name,
                            index = index + 1,
                            total = batch.size,
                            buffer = "",
                            srcPath = entry.file.path,
                            dstPreview = entry.file.path,
                        ),
                    )
                }
                val typed = d.await()
                pendingInput = null

                if (typed == null) break // Esc/Back cancels the entire remaining batch
                if (typed.isBlank() || typed == entry.name) continue // no-op, advance

                when (val outcome = fsOps.rename(entry.file, typed)) {
                    is RenameOutcome.Success -> {}
                    is RenameOutcome.Conflict -> {
                        _state.update {
                            it.copy(stripe = StripeState.Result("EXISTS: ${outcome.target.name}", emptyList()))
                        }
                        awaitResultAck()
                    }
                    is RenameOutcome.Failure -> {
                        _state.update { it.copy(stripe = StripeState.Result(outcome.error.stripeText(), emptyList())) }
                        awaitResultAck()
                    }
                }
            }
            _state.update { it.copy(stripe = StripeState.Idle) }
            updatePane(id) { it.copy(selected = emptySet()) }
            refreshPane(id)
        }
    }

    private suspend fun awaitResultAck() {
        val d = CompletableDeferred<Unit>()
        pendingAck = d
        d.await()
        pendingAck = null
    }

    private fun startExit() {
        viewModelScope.launch {
            val answer = askConfirm(
                "EXIT?",
                options = listOf(ConfirmOption('1', "OK"), ConfirmOption('2', "CANCEL", isCancel = true)),
            )
            if (!answer.isCancel) effectChannel.trySend(SideEffect.FinishActivity)
        }
    }

    private fun reportBatch(verb: String, result: BatchResult) {
        val fails = result.reports.filterIsInstance<OpReport.Fail>()
        val summary = "DONE ${result.okCount} OK / ${fails.size} FAILED — OK TO VIEW"
        val errorLines = result.reports.map { r ->
            when (r) {
                is OpReport.Fail -> "${r.error.stripeText()}: ${r.file.name}"
                is OpReport.Warn -> "${r.error.stripeText()}: ${r.file.name}"
                is OpReport.Noop -> "${r.error.stripeText()}: ${r.file.name}"
            }
        }
        _state.update { it.copy(stripe = StripeState.Result(summary, errorLines)) }
    }
}
