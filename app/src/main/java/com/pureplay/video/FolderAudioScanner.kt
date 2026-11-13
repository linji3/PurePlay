package com.pureplay.video

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.util.Locale

object FolderAudioScanner {

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "aac", "m4a", "wav", "ogg", "opus",
        "wma", "ape", "alac", "aiff", "amr", "mid", "midi", "mka"
    )

    fun getTreeFolderName(context: Context, treeUri: Uri): String {
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            return "文件夹"
        }
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        try {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {
        }
        return docId.substringAfterLast(':').substringAfterLast('/').ifEmpty { "文件夹" }
    }

    fun scanAudioFiles(context: Context, treeUri: Uri): List<AudioTrack> {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }

        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            return emptyList()
        }
        val rootFolderName = getTreeFolderName(context, treeUri)
        val results = mutableListOf<AudioTrack>()
        scanDirectory(context, treeUri, rootDocId, rootFolderName, results)
        return results.sortedWith(compareBy({ it.folderName.lowercase(Locale.getDefault()) }, { it.title.lowercase(Locale.getDefault()) }))
    }

    private fun scanDirectory(
        context: Context,
        treeUri: Uri,
        dirDocId: String,
        folderLabel: String,
        out: MutableList<AudioTrack>
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val subDirs = mutableListOf<Pair<String, String>>()

        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val docId = if (idCol >= 0) cursor.getString(idCol) else continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                    if (docId.isNullOrEmpty() || name.startsWith(".")) continue

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val subLabel = if (folderLabel.isNotEmpty()) "$folderLabel/$name" else name
                        subDirs.add(docId to subLabel)
                    } else if (isAudioFile(name, mime)) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val title = name.substringBeforeLast('.').ifEmpty { name }
                        out.add(
                            AudioTrack(
                                uri = fileUri.toString(),
                                title = title,
                                artist = "",
                                durationMs = 0L,
                                folderName = folderLabel,
                                folderUri = childrenUri.toString(),
                                displayName = name
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }

        for ((subDocId, subLabel) in subDirs) {
            scanDirectory(context, treeUri, subDocId, subLabel, out)
        }
    }

    private fun isAudioFile(name: String, mime: String): Boolean {
        if (mime.startsWith("audio/")) return true
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in AUDIO_EXTENSIONS
    }
}
