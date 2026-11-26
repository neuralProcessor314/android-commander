package com.androidcommander.fs

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Primary [FsOps] backend: raw java.io.File + android.system.Os. Requires all-files access
 * (instruction section 2). Implements the two named bug fixes verbatim from sections 3 and 4.
 */
class JavaFileOps : FsOps {

    private val caseModeCache = mutableMapOf<String, CaseMode>()

    override suspend fun list(dir: File): Result<List<FsEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val names = dir.list() ?: throw java.io.IOException("cannot list ${dir.path}")
            names
                .filterNot { it.endsWith(".ncpart") || it.startsWith(".ac_") }
                .map { name ->
                    val f = File(dir, name)
                    val stat = runCatching { Os.lstat(f.path) }.getOrNull()
                    val isSymlink = stat != null && OsConstants.S_ISLNK(stat.st_mode)
                    val isDir = if (isSymlink) f.isDirectory else stat?.let { OsConstants.S_ISDIR(it.st_mode) } ?: f.isDirectory
                    FsEntry(
                        file = f,
                        name = name,
                        isDirectory = isDir,
                        isSymlink = isSymlink,
                        sizeBytes = stat?.st_size ?: f.length(),
                        mtimeMillis = (stat?.st_mtime ?: (f.lastModified() / 1000)) * 1000,
                    )
                }
        }
    }

    override suspend fun freeSpaceBytes(dir: File): Long = withContext(Dispatchers.IO) {
        dir.usableSpace
    }

    override suspend fun mkdir(parent: File, name: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(name.isValidFsName()) { "invalid name" }
            val target = File(parent, name)
            if (target.exists()) error("conflict")
            if (!target.mkdir()) error("mkdir failed")
            scan(target)
            target
        }
    }

    // ---- BUG FIX 1: case-only rename (instruction section 3), verbatim algorithm. ----

    override suspend fun rename(src: File, newName: String): RenameOutcome = withContext(Dispatchers.IO) {
        if (!newName.isValidFsName()) return@withContext RenameOutcome.Failure(FsError.RenameFailed)
        if (newName == src.name) return@withContext RenameOutcome.Success(src)

        val target = File(src.parentFile, newName)
        val caseOnly = newName.equals(src.name, ignoreCase = true)

        if (!caseOnly) {
            if (target.exists()) return@withContext RenameOutcome.Conflict(src, target)
            return@withContext if (src.renameTo(target)) {
                scan(src); scan(target)
                RenameOutcome.Success(target)
            } else {
                RenameOutcome.Failure(FsError.RenameFailed)
            }
        }

        // ---- case-only path ----
        // Do NOT use exists(): on a case-insensitive volume it resolves back to src.
        // Identity is decided by (st_dev, st_ino), not by name.
        if (target.exists() && !isSameInode(src, target)) {
            return@withContext RenameOutcome.Conflict(src, target)
        }

        val tmp = uniqueTemp(src.parentFile)
        if (!src.renameTo(tmp)) return@withContext RenameOutcome.Failure(FsError.RenameTempFailed)
        if (!tmp.renameTo(target)) {
            if (!tmp.renameTo(src)) return@withContext RenameOutcome.Failure(FsError.OrphanedTemp)
            return@withContext RenameOutcome.Failure(FsError.RenameFinalFailed)
        }

        // Verify the stored case actually changed (casefolding volumes may keep the old case).
        val stored = src.parentFile?.list()?.firstOrNull { it.equals(newName, ignoreCase = true) }
        scan(src); scan(target)
        if (stored != newName) {
            return@withContext RenameOutcome.Failure(FsError.CaseNotStorable)
        }
        RenameOutcome.Success(target)
    }

    fun isSameInode(a: File, b: File): Boolean = try {
        val sa = Os.lstat(a.path)
        val sb = Os.lstat(b.path)
        sa.st_dev == sb.st_dev && sa.st_ino == sb.st_ino
    } catch (_: ErrnoException) {
        false
    }

    private fun uniqueTemp(dir: File?): File {
        var candidate: File
        do {
            candidate = File(dir, ".ac_${UUID.randomUUID()}.tmp")
        } while (candidate.exists())
        return candidate
    }

    override suspend fun probeCaseSensitivity(dir: File): CaseMode = withContext(Dispatchers.IO) {
        val root = volumeRootOf(dir)
        caseModeCache[root.path]?.let { return@withContext it }

        val id = UUID.randomUUID().toString().take(8)
        val probeA = File(dir, ".ac_probe_${id}A")
        val probeLower = File(dir, ".ac_probe_${id}a")
        val mode = try {
            if (!probeA.createNewFile()) return@withContext CaseMode.UNKNOWN
            val same = probeLower.exists() && isSameInode(probeA, probeLower)
            if (same) CaseMode.INSENSITIVE else CaseMode.SENSITIVE
        } catch (_: Exception) {
            CaseMode.UNKNOWN
        } finally {
            probeA.delete()
        }
        caseModeCache[root.path] = mode
        mode
    }

    fun invalidateCaseCacheFor(root: File) {
        caseModeCache.remove(volumeRootOf(root).path)
    }

    private fun volumeRootOf(dir: File): File {
        // Best-effort: the external storage root or the removable-volume root two levels under
        // /storage. Good enough to key the per-volume cache; exact mount boundaries aren't
        // exposed to app-level code without StorageManager.getStorageVolume() plumbing.
        val parts = dir.path.split(File.separatorChar)
        val storageIdx = parts.indexOf("storage")
        return if (storageIdx in 0 until parts.size - 1) {
            File(parts.subList(0, storageIdx + 2).joinToString(File.separator))
        } else {
            dir
        }
    }

    // ---- BUG FIX 2: recursive merge move (instruction section 4). ----

    override suspend fun move(
        sources: List<File>,
        destDir: File,
        policy: ConflictPolicy,
        onProgress: suspend (BatchProgress) -> Unit,
    ): BatchResult = withContext(Dispatchers.IO) {
        val reports = mutableListOf<OpReport>()
        var ok = 0
        val total = sources.sumOf { countTree(it) }
        var done = 0
        for (src in sources) {
            val s = src.canonicalFile
            val d = destDir.canonicalFile
            if (s.parentFile == d) {
                reports += OpReport.Noop(FsError.NoopSameParent, s)
                continue
            }
            if (d == s || d.path.startsWith(s.path + File.separator)) {
                reports += OpReport.Fail(FsError.ErrIntoSelf, s)
                continue
            }
            val result = moveOne(s, File(d, s.name), policy, reports) { current ->
                done++
                onProgress(BatchProgress("MOVE", done, total, current))
            }
            if (result) ok++
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
        val total = sources.sumOf { countTree(it) }
        var done = 0
        for (src in sources) {
            val s = src.canonicalFile
            val d = destDir.canonicalFile
            if (d == s || d.path.startsWith(s.path + File.separator)) {
                reports += OpReport.Fail(FsError.ErrIntoSelf, s)
                continue
            }
            val target = File(d, s.name)
            try {
                copyOne(s, target, policy, reports) { current ->
                    done++
                    onProgress(BatchProgress("COPY", done, total, current))
                }
                ok++
            } catch (e: Exception) {
                reports += OpReport.Fail(FsError.Unknown(e.message ?: "copy failed"), s)
            }
        }
        BatchResult(ok, reports)
    }

    /** True if this source ended up fully moved (used only for the top-level ok-count). */
    private suspend fun moveOne(
        src: File,
        target: File,
        policy: ConflictPolicy,
        reports: MutableList<OpReport>,
        tick: suspend (String) -> Unit,
    ): Boolean {
        tick(src.path)
        if (!target.exists()) {
            if (src.renameTo(target)) {
                scan(src); scan(target)
                return true
            }
            return try {
                copyTree(src, target)
                verifyTree(src, target)
                deleteTree(src)
                scan(src); scan(target)
                true
            } catch (e: Exception) {
                reports += OpReport.Fail(FsError.Unknown(e.message ?: "EXDEV move failed"), src)
                false
            }
        }

        val srcIsDir = src.isDirectory && !isSymlink(src)
        val targetIsDir = target.isDirectory && !isSymlink(target)

        return when {
            srcIsDir && targetIsDir -> {
                var allOk = true
                for (child in src.listFiles().orEmpty()) {
                    val childOk = moveOne(child, File(target, child.name), policy, reports, tick)
                    allOk = allOk && childOk
                }
                if (src.listFiles().isNullOrEmpty()) {
                    src.delete()
                } else {
                    reports += OpReport.Warn(FsError.WarnSourceNotEmpty, src)
                    allOk = false
                }
                allOk
            }
            !srcIsDir && !targetIsDir -> when (resolveConflict(policy, src, target)) {
                Action.SKIP -> { reports += OpReport.Noop(FsError.Skipped, src); false }
                Action.OVERWRITE -> {
                    if (!target.delete()) {
                        reports += OpReport.Fail(FsError.Unknown("overwrite failed"), src)
                        false
                    } else {
                        moveOne(src, target, policy, reports, tick)
                    }
                }
                Action.RENAME -> moveOne(src, autoSuffix(target), policy, reports, tick)
            }
            else -> when (resolveConflict(policy, src, target)) {
                Action.SKIP -> { reports += OpReport.Noop(FsError.Skipped, src); false }
                Action.RENAME -> moveOne(src, autoSuffix(target), policy, reports, tick)
                Action.OVERWRITE -> {
                    reports += OpReport.Fail(FsError.ErrTypeClash, src)
                    false
                }
            }
        }
    }

    private suspend fun copyOne(
        src: File,
        target: File,
        policy: ConflictPolicy,
        reports: MutableList<OpReport>,
        tick: suspend (String) -> Unit,
    ) {
        tick(src.path)
        val srcIsDir = src.isDirectory && !isSymlink(src)
        if (!target.exists()) {
            copyTree(src, target)
            scan(target)
            return
        }
        val targetIsDir = target.isDirectory && !isSymlink(target)
        when {
            srcIsDir && targetIsDir -> {
                for (child in src.listFiles().orEmpty()) {
                    copyOne(child, File(target, child.name), policy, reports, tick)
                }
            }
            !srcIsDir && !targetIsDir -> when (resolveConflict(policy, src, target)) {
                Action.SKIP -> reports += OpReport.Noop(FsError.Skipped, src)
                Action.OVERWRITE -> { target.delete(); copyTree(src, target); scan(target) }
                Action.RENAME -> copyOne(src, autoSuffix(target), policy, reports, tick)
            }
            else -> when (resolveConflict(policy, src, target)) {
                Action.SKIP -> reports += OpReport.Noop(FsError.Skipped, src)
                Action.RENAME -> copyOne(src, autoSuffix(target), policy, reports, tick)
                Action.OVERWRITE -> reports += OpReport.Fail(FsError.ErrTypeClash, src)
            }
        }
    }

    private suspend fun resolveConflict(policy: ConflictPolicy, src: File, target: File): Action =
        policy.rememberedAction ?: policy.resolve(src, target)

    private fun isSymlink(f: File): Boolean =
        try { OsConstants.S_ISLNK(Os.lstat(f.path).st_mode) } catch (_: ErrnoException) { false }

    fun autoSuffix(target: File): File {
        val name = target.name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        var candidate: File
        do {
            candidate = File(target.parentFile, "$base ($n)$ext")
            n++
        } while (candidate.exists())
        return candidate
    }

    /** Writes to `<name>.ncpart`, fsyncs, renames into place; preserves lastModified. Never follows links. */
    fun copyTree(src: File, target: File) {
        if (isSymlink(src)) {
            val linkTarget = Os.readlink(src.path)
            Os.symlink(linkTarget, target.path)
            return
        }
        if (src.isDirectory) {
            target.mkdirs()
            for (child in src.listFiles().orEmpty()) {
                copyTree(child, File(target, child.name))
            }
            target.setLastModified(src.lastModified())
            return
        }
        val part = File(target.parentFile, "${target.name}.ncpart")
        FileInputStream(src).use { input ->
            FileOutputStream(part).use { output ->
                val buf = ByteArray(1 shl 20) // 1 MiB
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                }
                output.fd.sync()
            }
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw java.io.IOException("could not finalize ${target.path}")
        }
        target.setLastModified(src.lastModified())
    }

    /** Post-order delete; only ever called after [verifyTree] confirms parity. */
    fun deleteTree(f: File) {
        if (isSymlink(f)) { f.delete(); return }
        if (f.isDirectory) {
            for (child in f.listFiles().orEmpty()) deleteTree(child)
        }
        f.delete()
    }

    fun verifyTree(src: File, target: File) {
        val (srcCount, srcSize) = countAndSize(src)
        val (dstCount, dstSize) = countAndSize(target)
        if (srcCount != dstCount || srcSize != dstSize) {
            throw java.io.IOException("verify failed: $srcCount/$srcSize vs $dstCount/$dstSize")
        }
    }

    private fun countAndSize(f: File): Pair<Int, Long> {
        if (isSymlink(f)) return 1 to 0L
        if (!f.isDirectory) return 1 to f.length()
        var count = 1
        var size = 0L
        for (child in f.listFiles().orEmpty()) {
            val (c, s) = countAndSize(child)
            count += c
            size += s
        }
        return count to size
    }

    private fun countTree(f: File): Int = countAndSize(f).first

    // ---- delete ----

    override suspend fun delete(
        targets: List<File>,
        onProgress: suspend (BatchProgress) -> Unit,
    ): BatchResult = withContext(Dispatchers.IO) {
        val reports = mutableListOf<OpReport>()
        var ok = 0
        val total = targets.sumOf { countTree(it) }
        var done = 0
        for (t in targets) {
            try {
                deleteTreeCounting(t) { current ->
                    done++
                    onProgress(BatchProgress("DEL", done, total, current))
                }
                scan(t)
                ok++
            } catch (e: Exception) {
                reports += OpReport.Fail(FsError.Unknown(e.message ?: "delete failed"), t)
            }
        }
        BatchResult(ok, reports)
    }

    private suspend fun deleteTreeCounting(f: File, tick: suspend (String) -> Unit) {
        tick(f.path)
        if (!isSymlink(f) && f.isDirectory) {
            for (child in f.listFiles().orEmpty()) deleteTreeCounting(child, tick)
        }
        f.delete()
    }

    private fun scan(f: File) {
        // MediaScannerConnection needs a Context; hooked up by RoutingFsOps/App-level wrapper.
        MediaScanHook.scan(f)
    }
}

/** Set once at app start so plain-JVM code in this file can trigger a MediaStore rescan (instruction section 2). */
object MediaScanHook {
    var scanner: ((String) -> Unit)? = null
    fun scan(f: File) {
        scanner?.invoke(f.path)
    }
}
