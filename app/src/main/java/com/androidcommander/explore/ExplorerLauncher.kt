package com.androidcommander.explore

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Instruction section 7.4: EXPL tries, in order, each guarded by resolveActivity. No vendor
 * package is hard-coded as the only path.
 */
object ExplorerLauncher {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun launch(context: Context, dir: File): Boolean {
        val docUri = buildDocUri(dir)

        // If dir isn't under the known SAF-mappable root (shouldn't happen now that navigation
        // is capped at that root, but kept as a safety net), still offer a bare document-tree
        // picker rather than failing outright -- it just won't be pre-scrolled to dir.
        val attempts = if (docUri != null) {
            listOf(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(docUri, "vnd.android.document/directory")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(docUri, "resource/folder")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri)
                },
            )
        } else {
            listOf(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
        }

        for (intent in attempts) {
            if (intent.resolveActivity(context.packageManager) != null) {
                return try {
                    context.startActivity(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
            }
        }
        return false
    }

    private fun buildDocUri(dir: File): Uri? {
        val root = "/storage/emulated/0"
        if (!dir.path.startsWith(root)) return null
        val relative = dir.path.removePrefix(root).trim('/')
        val docId = if (relative.isEmpty()) "primary:" else "primary:$relative"
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
    }
}
