package com.juya.promostudio

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** 앱 안에 저장한 사진·영상을 틱톡 앱에 읽기 전용으로 넘겨주는 통로 */
class MediaProvider : ContentProvider() {

    companion object {
        const val AUTH = "com.juya.promostudio.media"
        fun uriFor(f: File): Uri = Uri.parse("content://$AUTH/${f.name}")
    }

    private fun fileOf(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (name.contains("/") || name.contains("..")) throw FileNotFoundException()
        val f = File(File(context!!.filesDir, "media"), name)
        if (!f.exists()) throw FileNotFoundException()
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri): String {
        val n = uri.lastPathSegment ?: ""
        return when {
            n.endsWith(".mp4", true) -> "video/mp4"
            n.endsWith(".mov", true) -> "video/quicktime"
            n.endsWith(".png", true) -> "image/png"
            else -> "image/jpeg"
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileOf(uri)
        val cols = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
