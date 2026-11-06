package io.legado.app.service

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.utils.IntentType
import io.legado.app.utils.openFileUri
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.downloadManager
import splitties.systemservices.notificationManager

/**
 * 下载文件
 */
class DownloadService : BaseService() {
    private val groupKey = "${appCtx.packageName}.download"
    private val downloads = hashMapOf<Long, DownloadInfo>()
    private val progressTracker = DownloadProgressTracker(NotificationId.Download)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private var upStateJob: Job? = null
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            requestState()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onDestroy() {
        upStateJob?.cancel()
        refreshRequests.close()
        unregisterReceiver(downloadReceiver)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> startDownload(
                intent.getStringExtra("url"),
                intent.getStringExtra("fileName")
            )

            IntentAction.play -> {
                val id = intent.getLongExtra("downloadId", 0)
                lifecycleScope.launch {
                    openDownload(id, intent.getStringExtra("fileName") ?: downloads[id]?.fileName)
                    if (progressTracker.activeIds().isEmpty()) stopSelf()
                }
            }

            IntentAction.stop -> {
                val downloadId = intent.getLongExtra("downloadId", 0)
                removeDownload(downloadId, intent.getIntExtra("notificationId", 0))
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 开始下载
     */
    private fun startDownload(url: String?, fileName: String?) {
        if (url == null || fileName == null) {
            if (progressTracker.activeIds().isEmpty()) {
                stopSelf()
            }
            return
        }
        if (progressTracker.activeIds().any { downloads[it]?.url == url }) {
            toastOnUi("已在下载列表")
            return
        }
        kotlin.runCatching {
            // 指定下载地址
            val request = DownloadManager.Request(Uri.parse(url))
            // 设置通知
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            // 设置下载文件保存的路径和文件名
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            // 添加一个下载任务
            val downloadId = downloadManager.enqueue(request)
            downloads[downloadId] =
                DownloadInfo(url, fileName)
            progressTracker.register(downloadId)
            requestState()
        }.onFailure {
            it.printStackTrace()
            val msg = when (it) {
                is SecurityException -> "下载出错,没有存储权限"
                else -> "下载出错,${it.localizedMessage}"
            }
            toastOnUi(msg)
            AppLog.put(msg, it)
            if (progressTracker.activeIds().isEmpty()) stopSelf()
        }
    }

    /**
     * 取消下载
     */
    private fun removeDownload(downloadId: Long, fallbackNotificationId: Int) {
        val notificationId = progressTracker.remove(downloadId) ?: fallbackNotificationId
        downloads.remove(downloadId)
        if (notificationId != 0) notificationManager.cancel("download:$downloadId", notificationId)
        lifecycleScope.launch {
            try {
                // A completed notification can outlive this service; dismissing it must preserve the file.
                withContext(NonCancellable + Dispatchers.IO) {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val completed = downloadManager.query(query).use { cursor ->
                        cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                    }
                    if (!completed) downloadManager.remove(downloadId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.put("取消下载失败", error)
            } finally {
                if (progressTracker.activeIds().isEmpty()) stopSelf()
            }
        }
        refreshRequests.trySend(Unit)
    }

    private fun requestState() {
        refreshRequests.trySend(Unit)
        if (upStateJob?.isActive == true) return
        if (progressTracker.activeIds().isEmpty()) return
        upStateJob = lifecycleScope.launch {
            while (isActive) {
                refreshRequests.tryReceive()
                val ids = progressTracker.activeIds()
                if (ids.isEmpty()) break
                try {
                    val progress = withContext(Dispatchers.IO) { queryProgress(ids) }
                    for ((id, state) in progress) {
                        val info = downloads[id] ?: continue
                        val change = progressTracker.update(id, state) ?: continue
                        val status = when (state.status) {
                            DownloadManager.STATUS_PAUSED -> getString(R.string.pause)
                            DownloadManager.STATUS_PENDING -> getString(R.string.wait_download)
                            DownloadManager.STATUS_RUNNING -> getString(R.string.downloading)
                            DownloadManager.STATUS_SUCCESSFUL -> getString(R.string.download_success)
                            DownloadManager.STATUS_FAILED -> getString(R.string.download_error)
                            else -> getString(R.string.unknown_state)
                        }
                        if (change.notify) upDownloadNotification(id, change.notificationId,
                            "${info.fileName} $status", state, info.startTime, info.fileName)
                        if (change.completed) openDownload(id, info.fileName)
                    }
                    for (id in ids) {
                        if (id !in progress) {
                            progressTracker.remove(id)?.let { notificationManager.cancel("download:$id", it) }
                            downloads.remove(id)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLog.put("查询下载进度失败", error)
                }
                if (progressTracker.activeIds().isEmpty()) {
                    stopSelf()
                    break
                }
                withTimeoutOrNull(1000) { refreshRequests.receive() }
            }
        }
    }

    private fun queryProgress(ids: List<Long>): Map<Long, DownloadProgress> {
        if (ids.isEmpty()) return emptyMap()
        val result = hashMapOf<Long, DownloadProgress>()
        val query = DownloadManager.Query().setFilterById(*ids.toLongArray())
        downloadManager.query(query).use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val progressColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val statusColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            while (cursor.moveToNext()) {
                val status = cursor.getInt(statusColumn)
                result[cursor.getLong(idColumn)] = DownloadProgress(status, cursor.getLong(progressColumn),
                    cursor.getLong(totalColumn), status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED,
                    status == DownloadManager.STATUS_SUCCESSFUL)
            }
        }
        return result
    }

    /**
     * 打开下载文件
     */
    private suspend fun openDownload(downloadId: Long, fileName: String?) {
        kotlin.runCatching {
            val uri = withContext(Dispatchers.IO) { downloadManager.getUriForDownloadedFile(downloadId) }
            if (uri == null) toastOnUi("未完成,下载的文件夹Download") else {
                val type = IntentType.from(fileName)
                openFileUri(uri, type)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.put("打开下载文件${fileName}出错", it)
        }
    }

    override fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setSubText(getString(R.string.action_download))
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setOngoing(true)
            .build()
        startForeground(NotificationId.DownloadService, notification)
    }

    /**
     * 更新通知
     */
    private fun upDownloadNotification(
        downloadId: Long,
        notificationId: Int,
        content: String,
        progress: DownloadProgress,
        startTime: Long,
        fileName: String
    ) {
        val notificationBuilder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setSubText(getString(R.string.action_download))
            .setContentTitle(content)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                servicePendingIntent<DownloadService>(IntentAction.play, downloadId.toInt()) {
                    data = Uri.parse("legado-download://$downloadId")
                    putExtra("downloadId", downloadId)
                    putExtra("fileName", fileName)
                    putExtra("notificationId", notificationId)
                }
            )
            .setDeleteIntent(
                servicePendingIntent<DownloadService>(IntentAction.stop, downloadId.toInt()) {
                    data = Uri.parse("legado-download://$downloadId")
                    putExtra("downloadId", downloadId)
                    putExtra("fileName", fileName)
                    putExtra("notificationId", notificationId)
                }
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(groupKey)
            .setWhen(startTime)
        if (!progress.terminal) {
            notificationBuilder.setProgress(1000, progress.notificationProgress, progress.indeterminate)
        }
        notificationManager.notify("download:$downloadId", notificationId, notificationBuilder.build())
    }

    private data class DownloadInfo(
        val url: String,
        val fileName: String,
        val startTime: Long = System.currentTimeMillis()
    )

}
