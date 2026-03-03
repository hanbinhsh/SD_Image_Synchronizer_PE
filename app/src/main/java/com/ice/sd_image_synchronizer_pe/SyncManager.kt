package com.ice.sd_image_synchronizer_pe

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Arrays

object SyncManager {
    // --- UI 状态 ---
    var isConnected = mutableStateOf(false)
    var logText = mutableStateOf("就绪")
    var isUserDisconnect = false

    // 数据源
    var allFiles = mutableStateListOf<File>()
    var folderList = mutableStateListOf<File>()

    // 设置项
    var serverIp = mutableStateOf("192.168.1.10")
    var serverPort = mutableStateOf("12345") // [新增]
    var aesKey = mutableStateOf("")          // [新增]

    var autoConnect = mutableStateOf(false)
    var autoReconnect = mutableStateOf(false)
    var gridColumns = mutableStateOf(3)
    var backgroundMode = mutableStateOf(false)
    var currentStoragePath = mutableStateOf("")

    private var isInitialized = false
    private var socket: Socket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var appContext: Context
    private lateinit var cacheDir: File

    // 设备唯一标识
    private lateinit var deviceId: String

    var serviceStateCallback: (() -> Unit)? = null

    fun init(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        loadSettings()

        // 获取或生成设备ID
        deviceId = getDeviceId()

        // 初始化路径
        if (currentStoragePath.value.isEmpty()) {
            cacheDir = File(appContext.getExternalFilesDir(null), "sd_cache")
            currentStoragePath.value = cacheDir.absolutePath
        } else {
            cacheDir = File(currentStoragePath.value)
        }

        if (!cacheDir.exists()) cacheDir.mkdirs()

        refreshAllData()

        if (backgroundMode.value) startService()
        if (autoConnect.value) connect()

        isInitialized = true
    }

    fun setBackgroundMode(enable: Boolean) {
        backgroundMode.value = enable
        saveSettings()
        if (enable) {
            startService()
            serviceStateCallback?.invoke()
        } else {
            stopService()
        }
    }

    fun getFolderContents(path: String): List<File> {
        val targetDir = if (path.isEmpty()) cacheDir else File(path)
        if (!targetDir.exists()) return emptyList()
        val allItems = targetDir.listFiles() ?: return emptyList()
        val dirs = allItems.filter { it.isDirectory }.sortedBy { it.name }
        val images = allItems.filter { it.isFile && isImageFile(it.name) }.sortedByDescending { it.lastModified() }
        return dirs + images
    }

    fun getImagesOnlyInFolder(path: String): List<File> {
        val targetDir = if (path.isEmpty()) cacheDir else File(path)
        if (!targetDir.exists()) return emptyList()
        return targetDir.listFiles { f -> f.isFile && isImageFile(f.name) }
            ?.sortedByDescending { it.lastModified() }
            ?.toList() ?: emptyList()
    }

    private fun startService() {
        val intent = Intent(appContext, SyncService::class.java)
        appContext.startForegroundService(intent)
    }

    private fun stopService() {
        val intent = Intent(appContext, SyncService::class.java)
        appContext.stopService(intent)
    }

    fun changeStoragePath(newPath: String): Boolean {
        val newDir = File(newPath)
        if (!newDir.exists()) { if (!newDir.mkdirs()) return false }
        if (!newDir.canWrite()) return false

        val oldDir = cacheDir
        scope.launch(Dispatchers.IO) {
            try {
                oldDir.copyRecursively(newDir, overwrite = true)
                oldDir.deleteRecursively()
                cacheDir = newDir
                currentStoragePath.value = newDir.absolutePath
                saveSettings()
                withContext(Dispatchers.Main) { refreshAllData() }
            } catch (e: Exception) { Log.e("Sync", "Move failed: ${e.message}") }
        }
        return true
    }

    fun resetStoragePath() {
        val defaultDir = File(appContext.getExternalFilesDir(null), "sd_cache")
        changeStoragePath(defaultDir.absolutePath)
    }

    fun refreshAllData() {
        val folders = cacheDir.listFiles { file -> file.isDirectory }?.sortedBy { it.name } ?: emptyList()
        folderList.clear()
        folderList.addAll(folders)
        val images = mutableListOf<File>()
        cacheDir.walk().filter { it.isFile && isImageFile(it.name) }.forEach { images.add(it) }
        images.sortByDescending { it.lastModified() }
        allFiles.clear()
        allFiles.addAll(images)
    }

    private fun isImageFile(name: String): Boolean {
        return name.endsWith(".jpg", true) || name.endsWith(".png", true) || name.endsWith(".webp", true)
    }


    // --- 核心网络逻辑 (已重写适配 AES-GCM 和 鉴权) ---
    fun connect() {
        if (isConnected.value) return
        isUserDisconnect = false

        job = scope.launch {
            try {
                val port = serverPort.value.toIntOrNull() ?: 12345
                logText.value = "正在连接 ${serverIp.value}:$port..."
                serviceStateCallback?.invoke()

                socket = Socket(serverIp.value, port)
                socket?.keepAlive = true

                val input = DataInputStream(socket!!.getInputStream())
                val output = DataOutputStream(socket!!.getOutputStream())

                // 1. 连接建立后，立即发送 AUTH
                sendAuth(output)

                isConnected.value = true
                logText.value = "已连接，正在认证..."
                serviceStateCallback?.invoke()

                while (isActive) {
                    // 读取加密包头：PayloadLength (4 bytes)
                    val payloadLen = input.readInt()

                    // 读取 IV (12 bytes)
                    val iv = ByteArray(12)
                    input.readFully(iv)

                    // 读取 Tag (16 bytes)
                    val tag = ByteArray(16)
                    input.readFully(tag)

                    // 读取密文 (TotalLen - 12 - 16)
                    val cipherLen = payloadLen - 12 - 16
                    if (cipherLen < 0) throw Exception("无效的数据包长度")
                    val cipherBytes = ByteArray(cipherLen)
                    if (cipherLen > 0) {
                        input.readFully(cipherBytes)
                    }

                    // 解密
                    val plainBytes = decryptAESGCM(cipherBytes, tag, iv)
                    if (plainBytes == null) {
                        Log.e("Sync", "Decryption failed")
                        throw Exception("解密失败，请检查密钥")
                    }

                    // 解析解密后的数据 (结构: TotalLen(4) + JsonLen(4) + JSON + Data)
                    // 使用 ByteBuffer 或手动读取
                    val dis = DataInputStream(plainBytes.inputStream())
                    val plainTotalLen = dis.readInt()
                    val jsonLen = dis.readInt()

                    val jsonBytes = ByteArray(jsonLen)
                    dis.readFully(jsonBytes)
                    val jsonStr = String(jsonBytes, StandardCharsets.UTF_8)

                    val dataLen = plainTotalLen - 4 - jsonLen
                    val dataBytes = ByteArray(dataLen)
                    if (dataLen > 0) {
                        dis.readFully(dataBytes)
                    }

                    handleCommand(jsonStr, dataBytes, output)
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                Log.e("Sync", "Connection error", e)
                isConnected.value = false
                logText.value = "断开: ${e.message}"
                socket = null
                serviceStateCallback?.invoke()

                if (autoReconnect.value && !isUserDisconnect) {
                    delay(5000)
                    if (!isUserDisconnect) connect()
                }
            }
        }
    }

    private fun handleCommand(jsonStr: String, data: ByteArray, output: DataOutputStream) {
        try {
            val json = JSONObject(jsonStr)
            val cmd = json.optString("cmd")

            when (cmd) {
                "FOLDER_LIST" -> {
                    // 认证成功，收到文件夹列表
                    logText.value = "认证成功"
                    // 发送本地文件清单进行同步
                    sendManifest(output)
                }
                "SYNC" -> {
                    val relPath = json.optString("path")
                    val targetFile = File(cacheDir, relPath)
                    targetFile.parentFile?.mkdirs()
                    FileOutputStream(targetFile).use { it.write(data) }
                    scope.launch(Dispatchers.Main) { refreshAllData() }
                }
                "DELETE" -> {
                    val relPath = json.optString("path")
                    val targetFile = File(cacheDir, relPath)
                    if (targetFile.exists()) targetFile.delete()
                    scope.launch(Dispatchers.Main) { refreshAllData() }
                }
                "DELETE_FOLDER" -> {
                    val relPath = json.optString("path")
                    val targetFile = File(cacheDir, relPath)
                    if (targetFile.exists()) targetFile.deleteRecursively()
                    scope.launch(Dispatchers.Main) { refreshAllData() }
                }
            }
        } catch (e: Exception) {
            Log.e("Sync", "Parse error: ${e.message}")
        }
    }

    // 发送 AUTH 包
    private fun sendAuth(output: DataOutputStream) {
        val json = JSONObject()
        json.put("cmd", "AUTH")
        json.put("deviceId", deviceId)
        json.put("deviceName", getDeviceName())
        sendPacket(output, json)
    }

    // 发送文件清单
    private fun sendManifest(output: DataOutputStream) {
        val fileMap = JSONObject()
        val filesObj = JSONObject()
        val rootPathLen = cacheDir.absolutePath.length + 1
        cacheDir.walk().filter { it.isFile }.forEach { file ->
            val relPath = file.absolutePath.substring(rootPathLen).replace("\\", "/")
            filesObj.put(relPath, file.length())
        }
        fileMap.put("cmd", "CLIENT_MANIFEST")
        fileMap.put("files", filesObj)
        sendPacket(output, fileMap)
    }

    // 加密并发送数据包
    private fun sendPacket(output: DataOutputStream, json: JSONObject, fileData: ByteArray = ByteArray(0)) {
        // 1. 构建明文数据包
        val jsonBytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        val jsonLen = jsonBytes.size
        val dataLen = fileData.size
        val totalLen = 4 + jsonLen + dataLen // JsonLen(4) + Json + Data (TotalLen field itself excluded in C++ logic inside struct, wait, let's check C++)

        // C++: plainOut << totalLen << jsonLen;
        // plainOut.writeRawData(json); plainOut.writeRawData(data);
        // So totalLen in C++ includes the 'jsonLen' field size(4) + jsonBytes + dataBytes.
        // It does NOT include the 'totalLen' field size itself.

        val plainStream = java.io.ByteArrayOutputStream()
        val dos = DataOutputStream(plainStream)
        dos.writeInt(totalLen) // 写入 TotalLen (4 bytes)
        dos.writeInt(jsonLen)  // 写入 JsonLen (4 bytes)
        dos.write(jsonBytes)
        if (dataLen > 0) dos.write(fileData)
        val plaintext = plainStream.toByteArray()

        // 2. 加密
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val (ciphertext, tag) = encryptAESGCM(plaintext, iv)

        // 3. 构建最终包: PayloadLength(4) + IV(12) + Tag(16) + Ciphertext
        val payloadLength = ciphertext.size + 12 + 16

        synchronized(output) {
            output.writeInt(payloadLength)
            output.write(iv)
            output.write(tag)
            output.write(ciphertext)
            output.flush()
        }
    }

    // --- AES-GCM 实现 ---

    // 填充密钥到 32 字节 (256位)
    private fun getPaddedKey(): ByteArray {
        val keyStr = aesKey.value
        val keyBytes = keyStr.toByteArray(StandardCharsets.UTF_8)
        return Arrays.copyOf(keyBytes, 32) // 不足补0，超过截断，符合 C++ leftJustified
    }

    private fun encryptAESGCM(plaintext: ByteArray, iv: ByteArray): Pair<ByteArray, ByteArray> {
        val keySpec = SecretKeySpec(getPaddedKey(), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv) // 128 bit tag length
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)

        val encrypted = cipher.doFinal(plaintext)

        // Java GCM output is CipherText + Tag appended at end.
        // C++ expects them separate.
        val tagLength = 16
        val cipherTextLength = encrypted.size - tagLength

        val actualCipher = Arrays.copyOfRange(encrypted, 0, cipherTextLength)
        val tag = Arrays.copyOfRange(encrypted, cipherTextLength, encrypted.size)

        return Pair(actualCipher, tag)
    }

    private fun decryptAESGCM(ciphertext: ByteArray, tag: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val keySpec = SecretKeySpec(getPaddedKey(), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)

            // Java needs CipherText + Tag combined input
            val input = ByteArray(ciphertext.size + tag.size)
            System.arraycopy(ciphertext, 0, input, 0, ciphertext.size)
            System.arraycopy(tag, 0, input, ciphertext.size, tag.size)

            cipher.doFinal(input)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // --- 辅助方法 ---
    private fun getDeviceId(): String {
        val prefs = appContext.getSharedPreferences("SDSyncPrefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 8) // 简短一点
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    private fun getDeviceName(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    fun disconnect() {
        isUserDisconnect = true
        try { socket?.close() } catch (e: Exception) {}
        isConnected.value = false
        job?.cancel()
        serviceStateCallback?.invoke()
    }

    fun clearCache() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        refreshAllData()
    }

    // 设置存取
    private fun loadSettings() {
        val prefs = appContext.getSharedPreferences("SDSyncPrefs", Context.MODE_PRIVATE)
        serverIp.value = prefs.getString("ip", "192.168.1.10")!!
        serverPort.value = prefs.getString("port", "12345")!! // [新增]
        aesKey.value = prefs.getString("aesKey", "")!!        // [新增]
        autoConnect.value = prefs.getBoolean("autoConnect", false)
        autoReconnect.value = prefs.getBoolean("autoReconnect", false)
        gridColumns.value = prefs.getInt("gridColumns", 3)
        backgroundMode.value = prefs.getBoolean("backgroundMode", false)
        currentStoragePath.value = prefs.getString("storagePath", "")!!
    }

    fun saveSettings() {
        val prefs = appContext.getSharedPreferences("SDSyncPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("ip", serverIp.value)
            .putString("port", serverPort.value) // [新增]
            .putString("aesKey", aesKey.value)   // [新增]
            .putBoolean("autoConnect", autoConnect.value)
            .putBoolean("autoReconnect", autoReconnect.value)
            .putInt("gridColumns", gridColumns.value)
            .putBoolean("backgroundMode", backgroundMode.value)
            .putString("storagePath", currentStoragePath.value)
            .apply()
    }
}