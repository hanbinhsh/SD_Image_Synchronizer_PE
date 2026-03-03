package com.ice.sd_image_synchronizer_pe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class SyncService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID = "sd_sync_service"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        SyncManager.init(this) // 确保单例被初始化

        // 注册回调，当 SyncManager 状态改变时更新通知
        SyncManager.serviceStateCallback = {
            updateNotification()
        }

        startForegroundService()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("ACTION")
        when (action) {
            "CONNECT" -> SyncManager.connect()
            "DISCONNECT" -> SyncManager.disconnect()
        }
        // 每次收到命令也尝试更新通知
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // 服务销毁时，释放锁，断开连接
        wakeLock?.release()
        SyncManager.serviceStateCallback = null
    }

    private fun startForegroundService() {
        val channelId = "sd_sync_service"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "SD Sync Service", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val isConnected = SyncManager.isConnected.value
        val ip = SyncManager.serverIp.value

        val title = if (isConnected) "SD Sync: 已连接" else "SD Sync: 未连接"
        val content = if (isConnected) "正在与 $ip 同步" else "等待连接..."

        // 打开 App 的 Intent
        val contentIntent = Intent(this, MainActivity::class.java)
        val pendingContentIntent = PendingIntent.getActivity(
            this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // 操作按钮 Intent
        val actionIntent = Intent(this, SyncService::class.java).apply {
            putExtra("ACTION", if (isConnected) "DISCONNECT" else "CONNECT")
        }
        val pendingActionIntent = PendingIntent.getService(
            this,
            if (isConnected) 2 else 3, // 不同的 RequestCode 避免冲突
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actionTitle = if (isConnected) "断开连接" else "连接"
        // 图标：你可以换成具体的 drawable 资源
        val actionIcon = if (isConnected) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_add

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingContentIntent)
            .setOngoing(true) // 常驻
            .addAction(actionIcon, actionTitle, pendingActionIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SDSync::WakeLock")
        wakeLock?.acquire()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}