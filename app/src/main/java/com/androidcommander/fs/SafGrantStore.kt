package com.androidcommander.fs

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * Persisted ACTION_OPEN_DOCUMENT_TREE grants, keyed by the filesystem root path they cover.
 * Backs [SafOps] and [RoutingFsOps] (instruction section 2).
 */
class SafGrantStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("saf_grants", Context.MODE_PRIVATE)

    fun register(treeUri: Uri, rootPath: String) {
        appContext.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit().putString(normalize(rootPath), treeUri.toString()).apply()
    }

    /** Longest-prefix match: the most specific grant covering [path], if any. */
    fun grantFor(path: String): Pair<String, Uri>? {
        val norm = normalize(path)
        return prefs.all
            .mapNotNull { (root, uriStr) -> if (uriStr is String) root to Uri.parse(uriStr) else null }
            .filter { (root, _) -> norm == root || norm.startsWith("$root/") }
            .maxByOrNull { (root, _) -> root.length }
    }

    fun hasGrantFor(path: String): Boolean = grantFor(path) != null

    fun allRoots(): List<String> = prefs.all.keys.toList()

    private fun normalize(path: String): String = File(path).path.trimEnd('/')
}
