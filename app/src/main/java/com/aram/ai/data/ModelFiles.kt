package com.aram.ai.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

object ModelFiles {
    const val MODEL_ID = "spark-x2.6-1.7b"
    const val MODEL_NAME = "Spark-X2.6-1.7B-Q4_K_M.gguf"
    const val MODEL_URL = "https://huggingface.co/XHToken/Spark-X2.6-1.7B-GGUF/resolve/main/Spark-X2.6-1.7B-Q4_K_M.gguf"
    const val MIN_MODEL_BYTES = 1_500_000_000L
    private const val TEMP_NAME = "$MODEL_NAME.download"

    fun modelFile(context: Context): File {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "models")
        if (!directory.exists()) directory.mkdirs()
        return File(directory, MODEL_NAME)
    }

    fun isModelReady(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() >= MIN_MODEL_BYTES
    }

    /** Promote only a sufficiently large completed download; keep a previous valid file until then. */
    fun finalizeDownload(context: Context): Boolean {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return false
        val temporary = File(directory, TEMP_NAME)
        if (!temporary.isFile || temporary.length() < MIN_MODEL_BYTES) return false
        val destination = modelFile(context)
        val backup = File(directory, "$MODEL_NAME.previous")
        if (backup.exists()) backup.delete()
        val hadPrevious = destination.exists()
        if (hadPrevious && !destination.renameTo(backup)) return false
        if (temporary.renameTo(destination)) {
            backup.delete()
            return true
        }
        if (hadPrevious) backup.renameTo(destination)
        return false
    }

    fun enqueueDownload(context: Context): Long {
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, TEMP_NAME).delete() }
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Spark-X2.6-1.7B")
            .setDescription("تنزيل نموذج Aram AI الرسمي — Q4_K_M")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, TEMP_NAME)
        val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        AppPrefs.setDownloadId(context, id)
        return id
    }
}
