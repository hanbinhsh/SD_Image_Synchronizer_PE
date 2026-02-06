package com.ice.sd_image_synchronizer_pe

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

object SyncManager {
    // --- UI 状态 (Compose 会自动监听) ---
    var isConnected = mutableStateOf(false)
    var logText = mutableStateOf("就绪")

    // 图片数据源 (替代 FolderListModel)
    // 存储相对路径，例如 "Anime/01.jpg"
    var allFiles = mutableStateListOf<File>()
    var folderList = mutableStateListOf<File>()

    // 设置项
    var serverIp = mutableStateOf("192.168.1.10")
    var autoConnect = mutableStateOf(false)
    var autoReconnect = mutableStateOf(false)
    var gridColumns = mutableStateOf(3)

    var backgroundMode = mutableStateOf(false) // 防休眠/后台开关
    var currentStoragePath = mutableStateOf("") // 当前存储路径字符串
    var currentTab = mutableStateOf(0)
    private var isInitialized = false

    private var socket: Socket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var appContext: Context
    private lateinit var cacheDir: File

    fun init(context: Context) {
        if (isInitialized) return // [新增] 防止重复初始化

        appContext = context.applicationContext
        loadSettings()

        // 初始化路径
        if (currentStoragePath.value.isEmpty()) {
            // 默认路径: /Android/data/包名/files/sd_cache
            cacheDir = File(appContext.getExternalFilesDir(null), "sd_cache")
            currentStoragePath.value = cacheDir.absolutePath
        } else {
            cacheDir = File(currentStoragePath.value)
        }

        if (!cacheDir.exists()) cacheDir.mkdirs()

        refreshAllData()

        // 如果开启了后台模式，且服务未运行，则启动
        if (backgroundMode.value) {
            startService()
        }

        if (autoConnect.value) connect()

        isInitialized = true
    }

    fun setBackgroundMode(enable: Boolean) {
        backgroundMode.value = enable
        saveSettings()

        if (enable) {
            startService()
        } else {
            stopService()
        }
    }

    fun getFolderContents(path: String): List<File> {
        val targetDir = if (path.isEmpty()) cacheDir else File(path)
        if (!targetDir.exists()) return emptyList()

        val allItems = targetDir.listFiles() ?: return emptyList()

        // 分离文件夹和图片
        val dirs = allItems.filter { it.isDirectory }.sortedBy { it.name }
        val images = allItems.filter { it.isFile && isImageFile(it.name) }.sortedByDescending { it.lastModified() }

        // 返回合并列表：文件夹在前，图片在后
        return dirs + images
    }

    // 为了兼容之前的 Viewer 逻辑，我们需要一个方法只获取当前目录下的图片(用于大图浏览)
    fun getImagesOnlyInFolder(path: String): List<File> {
        val targetDir = if (path.isEmpty()) cacheDir else File(path)
        if (!targetDir.exists()) return emptyList()
        return targetDir.listFiles { f -> f.isFile && isImageFile(f.name) }
            ?.sortedByDescending { it.lastModified() }
            ?.toList() ?: emptyList()
    }

    private fun startService() {
        val intent = Intent(appContext, SyncService::class.java)
        // Android 8.0+ 必须使用 startForegroundService
        appContext.startForegroundService(intent)
    }

    private fun stopService() {
        val intent = Intent(appContext, SyncService::class.java)
        appContext.stopService(intent)
    }

    fun changeStoragePath(newPath: String): Boolean {
        val newDir = File(newPath)

        // 1. 检查新路径是否可写
        if (!newDir.exists()) {
            if (!newDir.mkdirs()) return false // 创建失败（可能是权限问题）
        }
        if (!newDir.canWrite()) return false // 无写入权限

        // 2. 迁移文件
        val oldDir = cacheDir
        scope.launch(Dispatchers.IO) {
            try {
                // 递归复制
                oldDir.copyRecursively(newDir, overwrite = true)
                // 删除旧文件
                oldDir.deleteRecursively()

                // 3. 更新状态
                cacheDir = newDir
                currentStoragePath.value = newDir.absolutePath
                saveSettings()

                // 刷新 UI
                withContext(Dispatchers.Main) {
                    refreshAllData()
                }
            } catch (e: Exception) {
                Log.e("Sync", "Move failed: ${e.message}")
            }
        }
        return true
    }

    fun resetStoragePath() {
        val defaultDir = File(appContext.getExternalFilesDir(null), "sd_cache")
        changeStoragePath(defaultDir.absolutePath)
    }

    fun getImagesInFolder(folderName: String): List<File> {
        val targetDir = File(cacheDir, folderName)
        if (!targetDir.exists()) return emptyList()

        return targetDir.listFiles { _, name ->
            isImageFile(name)
        }?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
    }

    fun refreshAllData() {
        // 1. 刷新文件夹列表
        val folders = cacheDir.listFiles { file -> file.isDirectory }
            ?.sortedBy { it.name } ?: emptyList()

        folderList.clear()
        folderList.addAll(folders)

        // 2. 刷新全部图片 (递归)
        val images = mutableListOf<File>()
        cacheDir.walk().filter { it.isFile && isImageFile(it.name) }
            .forEach { images.add(it) }

        images.sortByDescending { it.lastModified() }

        allFiles.clear()
        allFiles.addAll(images)
    }

    private fun isImageFile(name: String): Boolean {
        return name.endsWith(".jpg", true) ||
                name.endsWith(".png", true) ||
                name.endsWith(".webp", true)
    }

    // --- 核心网络逻辑 ---
    fun connect() {
        if (isConnected.value) return

        job = scope.launch {
            try {
                logText.value = "正在连接 ${serverIp.value}..."
                socket = Socket(serverIp.value, 12345)
                socket?.keepAlive = true

                // Java DataInputStream 默认就是 Big Endian，完美匹配 Qt 端
                val input = DataInputStream(socket!!.getInputStream())
                val output = DataOutputStream(socket!!.getOutputStream())

                isConnected.value = true
                logText.value = "已连接"

                // 连接成功后，发送本地清单 (Manifest) 用于差异同步
                sendManifest(output)

                while (isActive) {
                    // 1. 读取总长度 (4字节)
                    val totalLen = input.readInt()

                    // 2. 读取 JSON 长度 (4字节)
                    val jsonLen = input.readInt()

                    // 3. 读取 JSON
                    val jsonBytes = ByteArray(jsonLen)
                    input.readFully(jsonBytes)
                    val jsonStr = String(jsonBytes, StandardCharsets.UTF_8)

                    // 4. 读取二进制数据
                    val dataLen = totalLen - 4 - jsonLen
                    val dataBytes = ByteArray(dataLen)
                    if (dataLen > 0) {
                        input.readFully(dataBytes)
                    }

                    // 5. 处理指令
                    handleCommand(jsonStr, dataBytes)
                }
            } catch (e: Exception) {
                Log.e("Sync", "Connection error", e)
                isConnected.value = false
                logText.value = "断开: ${e.message}"
                socket = null

                if (autoReconnect.value) {
                    delay(5000)
                    connect()
                }
            }
        }
    }

    // 发送清单 (Client Manifest)
    private fun sendManifest(output: DataOutputStream) {
        val fileMap = JSONObject()
        val filesObj = JSONObject()

        val rootPathLen = cacheDir.absolutePath.length + 1 // +1 for slash

        cacheDir.walk().filter { it.isFile }.forEach { file ->
            val relPath = file.absolutePath.substring(rootPathLen).replace("\\", "/")
            filesObj.put(relPath, file.length())
        }

        fileMap.put("cmd", "CLIENT_MANIFEST")
        fileMap.put("files", filesObj)

        sendPacket(output, fileMap)
    }

    // 发送通用包
    private fun sendPacket(output: DataOutputStream, json: JSONObject) {
        val jsonBytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        val jsonLen = jsonBytes.size
        val totalLen = 4 + jsonLen // 没有二进制数据

        synchronized(output) {
            output.writeInt(totalLen)
            output.writeInt(jsonLen)
            output.write(jsonBytes)
            output.flush()
        }
    }

    private fun handleCommand(jsonStr: String, data: ByteArray) {
        try {
            val json = JSONObject(jsonStr)
            val cmd = json.optString("cmd")
            val relPath = json.optString("path")

            val targetFile = File(cacheDir, relPath)

            when (cmd) {
                "SYNC" -> {
                    targetFile.parentFile?.mkdirs()
                    FileOutputStream(targetFile).use { it.write(data) }
                }
                "DELETE" -> {
                    if (targetFile.exists()) targetFile.delete()
                }
                "DELETE_FOLDER" -> {
                    // C++ 发送的是相对路径，如 "Anime"
                    if (targetFile.exists()) targetFile.deleteRecursively()
                }
            }

            // [关键] 收到任何变动，刷新 UI 列表
            scope.launch(Dispatchers.Main) { refreshAllData() }

        } catch (e: Exception) {
            Log.e("Sync", "Parse error: ${e.message}")
        }
    }

    fun disconnect() {
        try { socket?.close() } catch (e: Exception) {}
        isConnected.value = false
        job?.cancel()
    }

    private fun refreshFileList() {
        val list = mutableListOf<File>()
        // 递归遍历所有图片
        cacheDir.walk().filter {
            it.isFile && (it.name.endsWith(".jpg") || it.name.endsWith(".png") || it.name.endsWith(".webp"))
        }.forEach { list.add(it) }

        // 按最后修改时间排序
        list.sortByDescending { it.lastModified() }

        allFiles.clear()
        allFiles.addAll(list)
    }

    // 简单的 SharedPreferences 封装
    private fun loadSettings() {
        val prefs = appContext.getSharedPreferences("SDSyncPrefs", Context.MODE_PRIVATE)
        serverIp.value = prefs.getString("ip", "192.168.1.10")!!
        autoConnect.value = prefs.getBoolean("autoConnect", false)
        autoReconnect.value = prefs.getBoolean("autoReconnect", false)
        gridColumns.value = prefs.getInt("gridColumns", 3)
        // [新增]
        backgroundMode.value = prefs.getBoolean("backgroundMode", false)
        currentStoragePath.value = prefs.getString("storagePath", "")!!
    }

    fun saveSettings() {
        val prefs = appContext.getSharedPreferences("SDSyncPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("ip", serverIp.value)
            .putBoolean("autoConnect", autoConnect.value)
            .putBoolean("autoReconnect", autoReconnect.value)
            .putInt("gridColumns", gridColumns.value)
            // [新增]
            .putBoolean("backgroundMode", backgroundMode.value)
            .putString("storagePath", currentStoragePath.value)
            .apply()
    }

    fun clearCache() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        refreshAllData()
    }
}