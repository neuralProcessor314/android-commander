package com.androidcommander.fs

import android.net.Uri
import android.provider.DocumentsContract

/** Maps a granted ACTION_OPEN_DOCUMENT_TREE URI back to the filesystem path it covers. */
fun treeUriToPath(uri: Uri): String? {
    val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val parts = treeId.split(":", limit = 2)
    if (parts.size != 2) return null
    val (volume, relative) = parts
    val volumeRoot = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
    return if (relative.isEmpty()) volumeRoot else "$volumeRoot/$relative"
}
