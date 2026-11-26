package com.androidcommander.fs

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fallback [FsOps] backend over a persisted ACTION_OPEN_DOCUMENT_TREE grant (instruction
 * section 2): used only where [JavaFileOps] is denied by the platform -- /Android/data,
 * /Android/obb, and removable volumes that return EACCES. Routed to per-path by [RoutingFsOps];
 * the UI never sees this class.
 */
class SafOps(
    private val context: Context,
    private val grants: SafGrantStore,
) : FsOps {

    private fun resolve(file: File, createDirs: Boolean = false): DocumentFile? {
        val (root, treeUri) = grants.grantFor(file.path) ?: return null
        var doc: DocumentFile? = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val rel = file.path.removePrefix(root).trim('/')
        if (rel.isEmpty()) return doc
        for (seg in rel.split('/')) {
            val next = doc?.findFile(seg)
            doc = next ?: if (createDirs) doc?.createDirectory(seg) else null
            if (doc == null) return null
        }
        return doc
    }

    private fun parentDoc(file: File, createDirs: Boolean = false): DocumentFile? =
        file.parentFile?.let { resolve(it, createDirs) }

    override suspend fun list(dir: File): Result<List<FsEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val doc = resolve(dir) ?: throw java.io.IOException("no SAF grant covers ${dir.path}")
            doc.listFiles()
                .filter { !(it.name ?: "").endsWith(".ncpart") && !(it.name ?: "").startsWith(".ac_") }
                .map { child ->
                    FsEntry(
                        file = File(dir, child.name ?: ""),
                        name = child.name ?: "",
                        isDirectory = child.isDirectory,
                        isSymlink = false, // SAF exposes no symlink concept
                        sizeBytes = if (child.isDirectory) 0 else child.length(),
                        mtimeMillis = child.lastModified(),
                    )
                }
        }
    }

    override suspend fun freeSpaceBytes(dir: File): Long = withContext(Dispatchers.IO) {
        // SAF exposes no portable free-space query; best effort only, else unknown.
        runCatching { dir.usableSpace }.getOrDefault(-1L)
    }

    override suspend fun mkdir(parent: File, name: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(name.isValidFsName()) { "invalid name" }
            val parentDoc = resolve(parent) ?: throw java.io.IOException("no SAF grant")
            if (parentDoc.findFile(name) != null) error("conflict")
            parentDoc.createDirectory(name) ?: error("mkdir failed")
            File(parent, name)
        }
    }

    override suspend fun probeCaseSensitivity(dir: File): CaseMode = CaseMode.UNKNOWN

    // ---- rename: same case-only care as JavaFileOps, expressed over DocumentFile. ----

    override suspend fun rename(src: File, newName: String): RenameOutcome = withContext(Dispatchers.IO) {
        if (!newName.isValidFsName()) return@withContext RenameOutcome.Failure(FsError.RenameFailed)
        if (newName == src.name) return@withContext RenameOutcome.Success(src)

        val parent = parentDoc(src) ?: return@withContext RenameOutcome.Failure(FsError.EaccesNeedsSaf)
        val srcDoc = parent.findFile(src.name) ?: return@withContext RenameOutcome.Failure(FsError.RenameFailed)
        val target = File(src.parentFile, newName)
        val caseOnly = newName.equals(src.name, ignoreCase = true)
        val existing = parent.findFile(newName)

        if (!caseOnly) {
            if (existing != null) return@withContext RenameOutcome.Conflict(src, target)
            return@withContext if (srcDoc.renameTo(newName)) {
                RenameOutcome.Success(target)
            } else {
                RenameOutcome.Failure(FsError.RenameFailed)
            }
        }

        // Case-only: a DocumentsProvider on a case-insensitive backing volume may resolve
        // `existing` back to srcDoc itself. Treat same-uri as same-identity, not a conflict.
        if (existing != null && existing.uri != srcDoc.uri) {
            return@withContext RenameOutcome.Conflict(src, target)
        }

        val tmpName = ".ac_${UUID.randomUUID()}.tmp"
        if (!srcDoc.renameTo(tmpName)) return@withContext RenameOutcome.Failure(FsError.RenameTempFailed)
        if (!srcDoc.renameTo(newName)) {
            if (!srcDoc.renameTo(src.name)) return@withContext RenameOutcome.Failure(FsError.OrphanedTemp)
            return@withContext RenameOutcome.Failure(FsError.RenameFinalFailed)
        }

        val stored = parent.findFile(newName)?.name
        if (stored != newName) return@withContext RenameOutcome.Failure(FsError.CaseNotStorable)
        RenameOutcome.Success(target)
    }

    // ---- move / copy: recursive merge, mirroring JavaFileOps' semantics over DocumentFile. ----

    override suspend fun move(
        sources: List<File>,
        destDir: File,
        policy: ConflictPolicy,
        onProgress: suspend (BatchProgress) -> Unit,
    ): BatchResult = withContext(Dispatchers.IO) {
        val reports = mutableListOf<OpReport>()
        var ok = 0
        var done = 0
        val total = sources.size
        for (src in sources) {
            val d = destDir.canonicalFile
            val s = src.canonicalFile
            if (s.parentFile == d) { reports += OpReport.Noop(FsError.NoopSameParent, s); continue }
            if (d.path == s.path || d.path.startsWith(s.path + File.separator)) {
                reports += OpReport.Fail(FsError.ErrIntoSelf, s); continue
            }
            done++
            onProgress(BatchProgress("MOVE", done, total, s.path))
            val moved = try {
                moveOne(s, File(d, s.name), policy, reports)
            } catch (e: Exception) {
                reports += OpReport.Fail(FsError.Unknown(e.message ?: "move failed"), s)
                false
            }
            if (moved) ok++
        }
        BatchResult(ok, reports)
    }

    override suspend fun copy(
        sources: List<File>,
        destDir: File,
        policy: ConflictPolicy,
        onProgress: suspend (BatchProgress) -> Unit,
    ): BatchResult = withContext(Dispatchers.IO) {
        val reports = mutableListOf<OpReport>()
        var ok = 0
        var done = 0
        val total = sources.size
        for (src in sources) {
            val d = destDir.canonicalFile
            val s = src.canonicalFile
            done++
            onProgress(BatchProgress("COPY", done, total, s.path))
            try {
                copyOne(s, File(d, s.name), policy, reports)
                ok++
            } catch (e: Exception) {
                reports += OpReport.Fail(FsError.Unknown(e.message ?: "copy failed"), s)
            }
        }
        BatchResult(ok, reports)
    }

    private suspend fun moveOne(
        src: File,
        target: File,
        policy: ConflictPolicy,
        reports: MutableList<OpReport>,
    ): Boolean {
        val srcDoc = resolve(src) ?: run { reports += OpReport.Fail(FsError.EaccesNeedsSaf, src); return false }
        val targetParent = parentDoc(target, createDirs = true)
            ?: run { reports += OpReport.Fail(FsError.EaccesNeedsSaf, src); return false }
        val existing = targetParent.findFile(target.name)

        if (existing == null) {
            copyOne(src, target, policy, reports)
            deleteOne(src)
            return true
        }

        return when {
            srcDoc.isDirectory && existing.isDirectory -> {
                var allOk = true
                for (child in srcDoc.listFiles()) {
                    val ok = moveOne(File(src, child.name ?: ""), File(target, child.name ?: ""), policy, reports)
                    allOk = allOk && ok
                }
                val remaining = resolve(src)?.listFiles()?.isNotEmpty() == true
                if (remaining) {
                    reports += OpReport.Warn(FsError.WarnSourceNotEmpty, src)
                    allOk = false
                } else {
                    deleteOne(src)
                }
                allOk
            }
            !srcDoc.isDirectory && !existing.isDirectory -> when (resolveConflict(policy, src, target)) {
                Action.SKIP -> { reports += OpReport.Noop(FsError.Skipped, src); false }
                Action.OVERWRITE -> { existing.delete(); copyOne(src, target, policy, reports); deleteOne(src); true }
                Action.RENAME -> moveOne(src, autoSuffix(targetParent, target), policy, reports)
            }
            else -> when (resolveConflict(policy, src, target)) {
                Action.SKIP -> { reports += OpReport.Noop(FsError.Skipped, src); false }
                Action.RENAME -> moveOne(src, autoSuffix(targetParent, target), policy, reports)
                Action.OVERWRITE -> { reports += OpReport.Fail(FsError.ErrTypeClash, src); false }
            }
        }
    }

    private suspend fun copyOne(
        src: File,
        target: File,
        policy: ConflictPolicy,
        reports: MutableList<OpReport>,
    ) {
        val srcDoc = resolve(src) ?: throw java.io.IOException("no SAF grant for ${src.path}")
        val targetParentDoc = parentDoc(target, createDirs = true) ?: throw java.io.IOException("no SAF grant for ${target.path}")
        val existing = targetParentDoc.findFile(target.name)

        if (srcDoc.isDirectory) {
            val dirDoc = existing ?: targetParentDoc.createDirectory(target.name)
            ?: throw java.io.IOException("mkdir failed")
            for (child in srcDoc.listFiles()) {
                copyOne(File(src, child.name ?: ""), File(target, child.name ?: ""), policy, reports)
            }
            return
        }

        if (existing != null) {
            when (resolveConflict(policy, src, target)) {
                Action.SKIP -> { reports += OpReport.Noop(FsError.Skipped, src); return }
                Action.OVERWRITE -> existing.delete()
                Action.RENAME -> { copyOne(src, autoSuffix(targetParentDoc, target), policy, reports); return }
            }
        }
        val newDoc = targetParentDoc.createFile(
            "application/octet-stream",
            target.name,
        ) ?: throw java.io.IOException("create failed for ${target.path}")
        context.contentResolver.openInputStream(srcDoc.uri)?.use { input ->
            context.contentResolver.openOutputStream(newDoc.uri)?.use { output ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                }
            }
        }
    }

    private fun deleteOne(f: File) {
        resolve(f)?.delete()
    }

    private fun autoSuffix(parent: DocumentFile, target: File): File {
        val name = target.name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        var candidateName: String
        do {
            candidateName = "$base ($n)$ext"
            n++
        } while (parent.findFile(candidateName) != null)
        return File(target.parentFile, candidateName)
    }

    private suspend fun resolveConflict(policy: ConflictPolicy, src: File, target: File): Action =
        policy.rememberedAction ?: policy.resolve(src, target)

    override suspend fun delete(
        targets: List<File>,
        onProgress: suspend (BatchProgress) -> Unit,
    ): BatchResult = withContext(Dispatchers.IO) {
        val reports = mutableListOf<OpReport>()
        var ok = 0
        var done = 0
        val total = targets.size
        for (t in targets) {
            done++
            onProgress(BatchProgress("DEL", done, total, t.path))
            val doc = resolve(t)
            if (doc != null && doc.delete()) ok++
            else reports += OpReport.Fail(FsError.Unknown("delete failed"), t)
        }
        BatchResult(ok, reports)
    }
}
