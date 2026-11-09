package app.cloudies.android.storage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.cloudies.android.R
import app.cloudies.android.MainActivity
import app.cloudies.android.cloudies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext.cloudies
        val account = inputData.getString("account") ?: return@withContext Result.failure()
        val direction = inputData.getString("direction") ?: "upload"
        val displayName = inputData.getString("name") ?: "File"
        var lastProgress = 0L
        try {
            setForeground(foreground(displayName, 0))
            val connection = app.connection(account)
            val client = app.client(connection)
            val job = coroutineContext
            val update: (Long, Long) -> Unit = { bytes, total ->
                job.ensureActive()
                val now = System.currentTimeMillis()
                if (now - lastProgress > 450 || bytes == total) {
                    val percent = if (total == 0L) 0 else (bytes.toDouble() * 100 / total).toInt().coerceIn(0, 100)
                    setProgressAsync(workDataOf("bytes" to bytes, "total" to total, "percent" to percent, "name" to displayName))
                    lastProgress = now
                }
            }
            if (direction == "upload") {
                val uri = Uri.parse(inputData.getString("uri") ?: throw IOException("Select the source file again."))
                val length = inputData.getLong("size", -1)
                if (length < 0) throw IOException("This source does not provide a file size. Save it locally and select it with Upload.")
                val item = client.upload(inputData.getString("parent").orEmpty(), displayName,
                    inputData.getString("mime") ?: "application/octet-stream", length,
                    { applicationContext.contentResolver.openInputStream(uri) ?: throw IOException("The source file is no longer accessible. Select it again.") }) { sent -> update(sent, length) }
                coroutineContext.ensureActive()
                app.catalog.put(account, item)
            } else {
                val remote = inputData.getString("remote") ?: throw IOException("The cloud file is missing.")
                val item = client.stat(remote)
                val target = app.catalog.offlineFile(account, remote)
                target.parentFile?.mkdirs()
                val partial = java.io.File(target.path + ".part")
                val versionFile = java.io.File(target.path + ".version")
                val version = "${item.etag}|${item.size}|${item.modified}"
                if ((item.etag.isBlank() && item.modified == 0L) || !versionFile.exists() || versionFile.readText() != version || partial.length() > item.size) {
                    partial.delete()
                    versionFile.writeText(version)
                }
                RandomAccessFile(partial, "rw").use { output ->
                    var position = output.length()
                    output.seek(position)
                    while (position < item.size) {
                        coroutineContext.ensureActive()
                        val bytes = client.read(remote, position, minOf(1024L * 1024, item.size - position).toInt())
                        if (bytes.isEmpty()) throw IOException("The cloud file ended before the download finished.")
                        output.write(bytes)
                        position += bytes.size
                        update(position, item.size)
                    }
                    output.fd.sync()
                }
                val confirmed = client.stat(remote)
                coroutineContext.ensureActive()
                if (confirmed.size != item.size || confirmed.etag != item.etag || confirmed.modified != item.modified) {
                    partial.delete(); versionFile.delete()
                    throw IOException("The cloud file changed while downloading. Retry to save the latest version.")
                }
                if (!partial.renameTo(target)) throw IOException("Could not finish saving the offline file.")
                versionFile.delete()
                app.catalog.put(account, item)
                app.catalog.markOffline(account, remote)
            }
            applicationContext.contentResolver.notifyChange(android.provider.DocumentsContract.buildRootsUri(CloudDocumentsProvider.AUTHORITY), null)
            Result.success(workDataOf("name" to displayName))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(androidx.work.Data.Builder().putAll(inputData)
                .putString("error", error.message ?: "Transfer failed. Reconnect and retry.").putString("name", displayName).build())
        }
    }
    private fun foreground(name: String, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("transfers", "Cloudies transfers", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "transfers")
            .setSmallIcon(R.drawable.ic_cloud_notification).setContentTitle("Cloudies · $name")
            .setContentText("Transfer in progress. Open Cloudies to view progress or cancel.")
            .setContentIntent(PendingIntent.getActivity(applicationContext, 0,
                Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", androidx.work.WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .setProgress(100, percent, percent == 0).setOngoing(true).build()
        val notificationId = (id.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, notification)
    }
}
