package com.androidcommander.fs

import java.io.File

/** One line of uppercase ASCII, <= 56 chars, per instruction section 10. */
sealed interface FsError {
    fun stripeText(): String

    data object RenameTempFailed : FsError { override fun stripeText() = "RENAME_TEMP_FAILED" }
    data object RenameFinalFailed : FsError { override fun stripeText() = "RENAME_FINAL_FAILED" }
    data object OrphanedTemp : FsError { override fun stripeText() = "ORPHANED_TEMP" }
    data object CaseNotStorable : FsError { override fun stripeText() = "CASE_NOT_STORABLE" }
    data object ErrIntoSelf : FsError { override fun stripeText() = "ERR_INTO_SELF" }
    data object ErrTypeClash : FsError { override fun stripeText() = "ERR_TYPE_CLASH" }
    data object NoopSameParent : FsError { override fun stripeText() = "NOOP_SAME_PARENT" }
    data object WarnSourceNotEmpty : FsError { override fun stripeText() = "WARN_SOURCE_NOT_EMPTY" }
    data object EaccesNeedsSaf : FsError { override fun stripeText() = "EACCES_NEEDS_SAF" }
    data object Enospc : FsError { override fun stripeText() = "ENOSPC" }
    data object ExdevFallbackUsed : FsError { override fun stripeText() = "EXDEV_FALLBACK_USED" }
    data object NoExternalExplorer : FsError { override fun stripeText() = "NO_EXTERNAL_EXPLORER" }
    data object Cancelled : FsError { override fun stripeText() = "CANCELLED" }
    data object RenameFailed : FsError { override fun stripeText() = "RENAME_FAILED" }
    data object Skipped : FsError { override fun stripeText() = "SKIPPED" }
    data class Conflict(val target: File) : FsError {
        override fun stripeText() = "ALREADY EXISTS: ${target.name}".take(56)
    }
    data class Unknown(val raw: String) : FsError {
        override fun stripeText() = raw.uppercase().take(56)
    }
}

enum class CaseMode { SENSITIVE, INSENSITIVE, UNKNOWN }

data class FsEntry(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val sizeBytes: Long,
    val mtimeMillis: Long,
)

/** Resolution the user picked in the stripe for one conflicting entry, and whether it should stick. */
enum class Action { SKIP, OVERWRITE, RENAME }

class ConflictPolicy(
    /** Non-null once the user answers "...ALL" -- every subsequent conflict uses it without asking. */
    var rememberedAction: Action? = null,
    /** Invoked once per real conflict when no remembered action applies; must be answered via the stripe. */
    val resolve: suspend (src: File, target: File) -> Action,
)

data class BatchProgress(
    val verb: String,
    val done: Int,
    val total: Int,
    val current: String,
)

sealed interface OpReport {
    data class Noop(val error: FsError, val file: File) : OpReport
    data class Warn(val error: FsError, val file: File) : OpReport
    data class Fail(val error: FsError, val file: File) : OpReport
}

data class BatchResult(
    val okCount: Int,
    val reports: List<OpReport>,
) {
    val failCount get() = reports.count { it is OpReport.Fail }
}

sealed interface RenameOutcome {
    data class Success(val target: File) : RenameOutcome
    data class Conflict(val src: File, val target: File) : RenameOutcome
    data class Failure(val error: FsError) : RenameOutcome
}

/**
 * Filesystem operations abstraction (instruction section 2). Two backends -- [JavaFileOps]
 * (primary) and [SafOps] (fallback for /Android/data, /Android/obb, and EACCES volumes) -- sit
 * behind this so the UI layer never knows which one is in play. Routing is done by [RoutingFsOps].
 */
interface FsOps {
    suspend fun list(dir: File): Result<List<FsEntry>>
    suspend fun rename(src: File, newName: String): RenameOutcome
    suspend fun mkdir(parent: File, name: String): Result<File>
    suspend fun move(
        sources: List<File>,
        destDir: File,
        policy: ConflictPolicy,
        onProgress: suspend (BatchProgress) -> Unit,
    ): BatchResult
    suspend fun copy(
        sources: List<File>,
        destDir: File,
        policy: ConflictPolicy,
        onProgress: suspend (BatchProgress) -> Unit,
    ): BatchResult
    suspend fun delete(
        targets: List<File>,
        onProgress: suspend (BatchProgress) -> Unit,
    ): BatchResult
    suspend fun freeSpaceBytes(dir: File): Long
    suspend fun probeCaseSensitivity(dir: File): CaseMode
}

private const val NUL_CODE = 0

/** No '/' and no NUL, not blank, not "." or "..": instruction section 3's validity rule. */
fun String.isValidFsName(): Boolean =
    isNotBlank() && this != "." && this != ".." && none { it == '/' || it.code == NUL_CODE }
