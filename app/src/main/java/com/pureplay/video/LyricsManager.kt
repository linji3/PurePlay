package com.pureplay.video

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.regex.Pattern

data class LyricLine(
    val timeMs: Long,
    val text: String
)

object LyricsManager {

    private val TIME_TAG_PATTERN = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val OFFSET_PATTERN = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE)

    fun loadLyricsForMediaItem(context: Context, item: MediaItem?): List<LyricLine> {
        if (item == null) return emptyList()

        val uriStr = item.mediaId.ifEmpty { item.localConfiguration?.uri?.toString() ?: "" }
        if (uriStr.isEmpty()) return emptyList()
        val trackUri = Uri.parse(uriStr)

        val extras = item.mediaMetadata.extras
        val folderUriStr = extras?.getString(AudioPlaybackHelper.EXTRA_FOLDER_URI).orEmpty()
        val displayNameExtra = extras?.getString(AudioPlaybackHelper.EXTRA_DISPLAY_NAME).orEmpty()
        val titleExtra = item.mediaMetadata.title?.toString().orEmpty()

        val baseCandidates = buildBaseNameCandidates(context, trackUri, displayNameExtra, titleExtra)
        if (baseCandidates.isEmpty()) return emptyList()

        val targetNames = baseCandidates.map { "$it.lrc" }

        // 1. Check explicit SAF folderUri stored from folder import
        if (folderUriStr.isNotEmpty()) {
            readFromTreeChildrenUri(context, Uri.parse(folderUriStr), targetNames)?.let {
                return parseLrc(it)
            }
        }

        // 2. Check direct file path (file:// or MediaStore DATA column)
        resolveDirectFile(context, trackUri)?.let { audioFile ->
            val parent = audioFile.parentFile
            if (parent != null && parent.isDirectory) {
                val base = audioFile.nameWithoutExtension
                val files = parent.listFiles()
                if (files != null) {
                    val match = files.firstOrNull { f ->
                        f.isFile && f.name.equals("$base.lrc", ignoreCase = true)
                    }
                    if (match != null && match.canRead()) {
                        try {
                            return parseLrc(decodeBytes(match.readBytes()))
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

        // 3. Fallback: check any persisted SAF tree permissions granted by user
        val persisted = context.contentResolver.persistedUriPermissions
        for (perm in persisted) {
            if (!perm.isReadPermission) continue
            val treeUri = perm.uri
            val rootDocId = try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (_: Exception) {
                continue
            }
            findInTreeRecursive(context, treeUri, rootDocId, targetNames, depth = 0)?.let {
                return parseLrc(it)
            }
        }

        return emptyList()
    }

    private fun buildBaseNameCandidates(
        context: Context,
        trackUri: Uri,
        displayNameExtra: String,
        titleExtra: String
    ): List<String> {
        val list = linkedSetOf<String>()
        if (displayNameExtra.isNotBlank()) {
            list.add(displayNameExtra.substringBeforeLast('.').trim())
        }
        val resolved = FormatUtils.resolveDisplayName(context, trackUri)
        if (resolved.isNotBlank()) {
            list.add(resolved.substringBeforeLast('.').trim())
        }
        if (titleExtra.isNotBlank() && titleExtra != "未在播放") {
            list.add(titleExtra.trim())
        }
        return list.filter { it.isNotEmpty() }
    }

    private fun readFromTreeChildrenUri(
        context: Context,
        childrenUri: Uri,
        targetNames: List<String>
    ): String? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        try {
            val treeUri = childrenUri
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val docId = if (idCol >= 0) cursor.getString(idCol) else continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                    if (targetNames.any { name.equals(it, ignoreCase = true) }) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        context.contentResolver.openInputStream(docUri)?.use { input ->
                            return decodeBytes(input.readBytes())
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun findInTreeRecursive(
        context: Context,
        treeUri: Uri,
        dirDocId: String,
        targetNames: List<String>,
        depth: Int
    ): String? {
        if (depth > 3) return null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val subDirs = mutableListOf<String>()
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val docId = if (idCol >= 0) cursor.getString(idCol) else continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else ""
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        subDirs.add(docId)
                    } else if (targetNames.any { name.equals(it, ignoreCase = true) }) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        context.contentResolver.openInputStream(docUri)?.use { input ->
                            return decodeBytes(input.readBytes())
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        for (subId in subDirs) {
            findInTreeRecursive(context, treeUri, subId, targetNames, depth + 1)?.let { return it }
        }
        return null
    }

    private fun resolveDirectFile(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") {
            val p = uri.path
            if (!p.isNullOrEmpty()) return File(p)
        }
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DATA),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (idx >= 0) {
                            val path = cursor.getString(idx)
                            if (!path.isNullOrEmpty()) return File(path)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun decodeBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // Strip UTF-8 BOM if present
        val offset = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) 3 else 0

        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (_: Exception) {
        }

        return try {
            String(bytes, Charset.forName("GB18030"))
        } catch (_: Exception) {
            String(bytes, Charsets.UTF_8)
        }
    }

    fun parseLrc(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        var offsetMs = 0L
        val timedLines = mutableListOf<LyricLine>()
        val plainLines = mutableListOf<LyricLine>()

        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val offsetMatcher = OFFSET_PATTERN.matcher(line)
            if (offsetMatcher.find()) {
                offsetMs = offsetMatcher.group(1)?.toLongOrNull() ?: 0L
                continue
            }

            val matcher = TIME_TAG_PATTERN.matcher(line)
            val times = mutableListOf<Long>()
            var lastEnd = 0
            while (matcher.find()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val sec = matcher.group(2)?.toLongOrNull() ?: 0L
                val fracStr = matcher.group(3) ?: "0"
                val ms = when (fracStr.length) {
                    1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                    else -> fracStr.take(3).toLongOrNull() ?: 0L
                }
                times.add(min * 60_000L + sec * 1000L + ms)
                lastEnd = matcher.end()
            }

            if (times.isNotEmpty()) {
                val text = line.substring(lastEnd).trim()
                if (text.isNotEmpty()) {
                    for (t in times) {
                        timedLines.add(LyricLine((t + offsetMs).coerceAtLeast(0L), text))
                    }
                }
            } else if (!line.startsWith("[") || !line.endsWith("]")) {
                plainLines.add(LyricLine(-1L, line))
            }
        }

        return if (timedLines.isNotEmpty()) {
            timedLines.sortedBy { it.timeMs }
        } else {
            plainLines
        }
    }

    fun findActiveLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty() || lines[0].timeMs < 0L) return -1
        var active = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) {
                active = i
            } else {
                break
            }
        }
        return active
    }
}
