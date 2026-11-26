package com.androidcommander.fs

import java.io.File

/**
 * Dispatches every [FsOps] call to [JavaFileOps] or [SafOps] based on the path involved, so the
 * UI layer never has to know which backend is in play (instruction section 2). Forced to SAF for
 * /Android/data and /Android/obb (blocked for all-files-access apps since Android 11/12) and for
 * any path already covered by a persisted SAF grant (i.e. a removable volume the user granted
 * after hitting EACCES). Everything else goes to [JavaFileOps]; if that backend can't write
 * because of a platform-level permission wall we haven't seen before, we surface
 * [FsError.EaccesNeedsSaf] rather than fail silently, so the ViewModel can prompt for a grant.
 */
class RoutingFsOps(
    private val javaOps: JavaFileOps,
    private val safOps: SafOps,
    private val grants: SafGrantStore,
) : FsOps {

    private fun forcedSaf(path: String): Boolean {
        val norm = path.replace('\\', '/')
        return norm.contains("/Android/data") || norm.contains("/Android/obb")
    }

    private fun useSaf(file: File): Boolean = forcedSaf(file.path) || grants.hasGrantFor(file.path)

    private fun writable(dir: File): Boolean = dir.exists() && dir.canWrite() || (!dir.exists() && dir.parentFile?.canWrite() == true)

    override suspend fun list(dir: File): Result<List<FsEntry>> =
        if (useSaf(dir)) safOps.list(dir) else javaOps.list(dir)

    override suspend fun rename(src: File, newName: String): RenameOutcome {
        if (useSaf(src)) return safOps.rename(src, newName)
        val parent = src.parentFile
        if (parent != null && !writable(parent) && !grants.hasGrantFor(parent.path)) {
            return RenameOutcome.Failure(FsError.EaccesNeedsSaf)
        }
        return javaOps.rename(src, newName)
    }

    override suspend fun mkdir(parent: File, name: String): Result<File> {
        if (useSaf(parent)) return safOps.mkdir(parent, name)
        if (!writable(parent) && !grants.hasGrantFor(parent.path)) {
            return Result.failure(java.io.IOException(FsError.EaccesNeedsSaf.stripeText()))
        }
        return javaOps.mkdir(parent, name)
    }

    override suspend fun move(
        sources: List<File>,
        destDir: File,
        policy: ConflictPolicy,
        onProgress: suspend (BatchProgress) -> Unit,
    ): BatchResult {
        val safSources = sources.filter { useSaf(it) }
        val javaSources = sources.filter { !useSaf(it) }
        val reports = mutableListOf<OpReport>()
        var ok = 0
        if (useSaf(destDir) || safSources.isNotEmpty()) {
            // Cross-backend or forced-SAF destination: route the whole batch through SafOps so a
            // single tree walk owns the merge; SafOps.move re-checks each source's own grant.
            val r = safOps.move(sources, destDir, policy, onProgress)
            ok += r.okCount
            reports += r.reports
        } else {
            if (!writable(destDir) && !grants.hasGrantFor(destDir.path)) {
                return BatchResult(0, sources.map { OpReport.Fail(FsError.EaccesNeedsSaf, it) })
            }
            val r = javaOps.move(javaSources, destDir, policy, onProgress)
            ok += r.okCount
            reports += r.reports
        }
        return BatchResult(ok, reports)
    }

    override suspend fun copy(
        sources: List<File>,
        destDir: File,
        policy: ConflictPolicy,
        onProgress: suspend (BatchProgress) -> Unit,
    ): BatchResult {
        val mixedSaf = useSaf(destDir) || sources.any { useSaf(it) }
        if (mixedSaf) return safOps.copy(sources, destDir, policy, onProgress)
        if (!writable(destDir) && !grants.hasGrantFor(destDir.path)) {
            return BatchResult(0, sources.map { OpReport.Fail(FsError.EaccesNeedsSaf, it) })
        }
        return javaOps.copy(sources, destDir, policy, onProgress)
    }

    override suspend fun delete(
        targets: List<File>,
        onProgress: suspend (BatchProgress) -> Unit,
    ): BatchResult {
        val safTargets = targets.filter { useSaf(it) }
        val javaTargets = targets.filter { !useSaf(it) }
        val reports = mutableListOf<OpReport>()
        var ok = 0
        if (safTargets.isNotEmpty()) {
            val r = safOps.delete(safTargets, onProgress)
            ok += r.okCount; reports += r.reports
        }
        if (javaTargets.isNotEmpty()) {
            val r = javaOps.delete(javaTargets, onProgress)
            ok += r.okCount; reports += r.reports
        }
        return BatchResult(ok, reports)
    }

    override suspend fun freeSpaceBytes(dir: File): Long =
        if (useSaf(dir)) safOps.freeSpaceBytes(dir) else javaOps.freeSpaceBytes(dir)

    override suspend fun probeCaseSensitivity(dir: File): CaseMode =
        if (useSaf(dir)) safOps.probeCaseSensitivity(dir) else javaOps.probeCaseSensitivity(dir)
}
