package com.ice.sd_image_synchronizer_pe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class SyncService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        SyncManager.init(this) // 确保单例被初始化

        // 只要服务启动，就开启前台通知
        startForegroundService()

        // 只有在开启了后台模式时，才申请 WakeLock (省电)
        // 或者策略：只要服务活着就持有锁，通过 Stop Service 来彻底关闭
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("ACTION")
        if (action == "CONNECT") SyncManager.connect()
        else if (action == "DISCONNECT") SyncManager.disconnect()

        // 如果用户关闭了后台模式，且没有连接任务，这里可以考虑停止
        // 但为了简单，我们由 SyncManager.setBackgroundMode 直接控制服务的存活

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // 服务销毁时，释放锁，断开连接
        wakeLock?.release()
        SyncManager.disconnect()
    }

    private fun startForegroundService() {
        val channelId = "sd_sync_service"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "SD Sync Service", NotificationManager.IMPORTANCE_LOW)
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("SD 同步服务")
            .setContentText("服务运行中...")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .build()

        startForeground(1, notification)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SDSync::WakeLock")
        // 设置一个较长的超时时间，或者不设置超时
        wakeLock?.acquire()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}