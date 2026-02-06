#include "syncclient.h"
#include <QDebug>
#include <QUrl>
#include <QDirIterator>
#include <QCoreApplication>

// [新增]
#ifdef Q_OS_ANDROID
#include <QJniEnvironment>
#endif

SyncClient::SyncClient(QObject *parent) : QObject(parent)
{
    m_socket = new QTcpSocket(this);

    // [关键修复] 设置 Socket 层的 KeepAlive，防止长时间无数据被中间路由断开
    m_socket->setSocketOption(QAbstractSocket::KeepAliveOption, 1);

    m_reconnectTimer = new QTimer(this);
    m_settings = new QSettings("MyStudio", "SD_Image_Sync_Mobile", this);

    // ... [保留原有构造函数逻辑: 路径加载等] ...
    QString defaultPath = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + "/sd_cache/";
    loadSettings();
    if (m_cachePath.isEmpty()) m_cachePath = defaultPath;
    QDir dir;
    if (!dir.exists(m_cachePath)) dir.mkpath(m_cachePath);

    connect(m_socket, &QTcpSocket::readyRead, this, &SyncClient::onReadyRead);
    connect(m_socket, &QTcpSocket::connected, this, &SyncClient::onConnected);
    connect(m_socket, &QTcpSocket::disconnected, this, &SyncClient::onDisconnected);
    connect(m_socket, &QTcpSocket::errorOccurred, this, &SyncClient::onError);
    connect(m_reconnectTimer, &QTimer::timeout, this, &SyncClient::onReconnectTimeout);

    // [新增] 如果启动时开启了后台模式，立即获取锁并显示通知
    if (m_backgroundMode) {
        acquireWakeLock();
        updateAndroidNotification("SD同步服务", "正在初始化...");
    }

    if (m_autoConnect && !m_savedIp.isEmpty()) {
        QTimer::singleShot(100, this, [this](){
            connectToHost(m_savedIp);
        });
    }
}

SyncClient::~SyncClient() {
    saveSettings();
    releaseWakeLock(); // [新增] 确保释放锁
    if (m_socket) {
        m_socket->abort();
        delete m_socket;
    }
}

void SyncClient::loadSettings() {
    m_savedIp = m_settings->value("serverIp", "").toString();
    m_gridColumns = m_settings->value("gridColumns", 3).toInt();
    m_autoConnect = m_settings->value("autoConnect", false).toBool();
    m_autoReconnect = m_settings->value("autoReconnect", false).toBool();
    m_cachePath = m_settings->value("storagePath", "").toString();
    m_backgroundMode = m_settings->value("backgroundMode", false).toBool();
}

void SyncClient::saveSettings() {
    m_settings->setValue("serverIp", m_savedIp);
    m_settings->setValue("gridColumns", m_gridColumns);
    m_settings->setValue("autoConnect", m_autoConnect);
    m_settings->setValue("autoReconnect", m_autoReconnect);
    m_settings->setValue("storagePath", m_cachePath);
    m_settings->setValue("backgroundMode", m_backgroundMode);
}

// [新增] 设置后台模式
void SyncClient::setBackgroundMode(bool enable) {
    if (m_backgroundMode != enable) {
        m_backgroundMode = enable;
        saveSettings();
        emit backgroundModeChanged();

        if (m_backgroundMode) {
            acquireWakeLock();
            updateAndroidNotification("SD同步服务", isConnected() ? "已连接" : "未连接");
        } else {
            releaseWakeLock();
            // 取消通知
#ifdef Q_OS_ANDROID
            QJniObject::callStaticMethod<void>(
                "androidx/core/app/NotificationManagerCompat", // 或 android/app/NotificationManager
                "cancel",
                "(I)V",
                1001 // Notification ID
                );
            // 注意：简单的 cancel 可能需要 Context，这里简化处理，通常建议保留通知或更新为"服务已关闭"
            updateAndroidNotification("SD同步服务", "后台服务已关闭");
#endif
        }
    }
}

// [新增] 获取 Android WakeLock
void SyncClient::acquireWakeLock() {
#ifdef Q_OS_ANDROID
    if (m_wakeLock.isValid()) return; // 已经持有

    QJniObject activity = QJniObject::callStaticObjectMethod(
        "org/qtproject/qt/android/QtNative",
        "activity",
        "()Landroid/app/Activity;"
        );

    if (!activity.isValid()) activity = QJniObject::callStaticObjectMethod(
            "org/qtproject/qt/android/QtPublic", // Qt6 兼容性路径
            "activity",
            "()Landroid/app/Activity;"
            );

    if (activity.isValid()) {
        QJniObject powerService = activity.callObjectMethod(
            "getSystemService",
            "(Ljava/lang/String;)Ljava/lang/Object;",
            QJniObject::fromString("power").object<jstring>()
            );

        if (powerService.isValid()) {
            // PARTIAL_WAKE_LOCK = 1
            m_wakeLock = powerService.callObjectMethod(
                "newWakeLock",
                "(ILjava/lang/String;)Landroid/os/PowerManager$WakeLock;",
                1,
                QJniObject::fromString("SD_Sync_WakeLock").object<jstring>()
                );

            if (m_wakeLock.isValid()) {
                m_wakeLock.callMethod<void>("acquire");
                qDebug() << "WakeLock acquired!";
            }
        }
    }
#endif
}

// [新增] 释放 WakeLock
void SyncClient::releaseWakeLock() {
#ifdef Q_OS_ANDROID
    if (m_wakeLock.isValid()) {
        m_wakeLock.callMethod<void>("release");
        m_wakeLock = nullptr; // 重置
        qDebug() << "WakeLock released!";
    }
#endif
}

// [新增] 更新 Android 通知栏
void SyncClient::updateAndroidNotification(const QString &title, const QString &content) {
    if (!m_backgroundMode) return;

#ifdef Q_OS_ANDROID
    QJniObject channelId = QJniObject::fromString("sd_sync_channel");
    QJniObject channelName = QJniObject::fromString("SD Sync Connection");

    QJniObject context = QNativeInterface::QAndroidApplication::context();
    // Qt 6.2+ 使用 QNativeInterface::QAndroidApplication::context()
    // 如果是旧版本 Qt6，使用 QJniObject::callStaticObjectMethod("org/qtproject/qt/android/QtNative", "activity", ...)

    if (!context.isValid()) return;

    // 1. 获取 NotificationManager
    QJniObject notificationManager = context.callObjectMethod(
        "getSystemService",
        "(Ljava/lang/String;)Ljava/lang/Object;",
        QJniObject::fromString("notification").object<jstring>()
        );

    // 2. 创建 NotificationChannel (Android 8.0+)
    jint importanceHigh = 3; // IMPORTANCE_DEFAULT
    QJniObject channel = QJniObject(
        "android/app/NotificationChannel",
        "(Ljava/lang/String;Ljava/lang/CharSequence;I)V",
        channelId.object<jstring>(),
        channelName.object<jstring>(),
        importanceHigh
        );

    notificationManager.callMethod<void>(
        "createNotificationChannel",
        "(Landroid/app/NotificationChannel;)V",
        channel.object<jobject>()
        );

    // 3. 构建 Notification
    QJniObject builder(
        "android/app/Notification$Builder",
        "(Landroid/content/Context;Ljava/lang/String;)V",
        context.object<jobject>(),
        channelId.object<jstring>()
        );

    jint iconId = QJniObject::getStaticField<jint>("android/R$drawable", "stat_sys_download_done");

    if (iconId == 0) {
        iconId = 17301633; // android.R.drawable.ic_menu_upload 的常见 ID，但这不保险
        // 更保险的是不设 iconId 就会崩溃，所以必须保证有一个
        // 这里尝试获取 android.R.drawable.sym_def_app_icon
        iconId = QJniObject::getStaticField<jint>("android/R$drawable", "sym_def_app_icon");
    }

    builder.callObjectMethod("setSmallIcon", "(I)Landroid/app/Notification$Builder;", iconId);
    builder.callObjectMethod("setContentTitle", "(Ljava/lang/CharSequence;)Landroid/app/Notification$Builder;", QJniObject::fromString(title).object<jstring>());
    builder.callObjectMethod("setContentText", "(Ljava/lang/CharSequence;)Landroid/app/Notification$Builder;", QJniObject::fromString(content).object<jstring>());
    builder.callObjectMethod("setOngoing", "(Z)Landroid/app/Notification$Builder;", true); // 设置为常驻通知

    QJniObject notification = builder.callObjectMethod("build", "()Landroid/app/Notification;");

    // 4. 显示通知
    notificationManager.callMethod<void>(
        "notify",
        "(ILandroid/app/Notification;)V",
        1001, // 固定 ID，保证更新同一个通知
        notification.object<jobject>()
        );
#endif
}

void SyncClient::connectToHost(const QString &hostIp, int port)
{
    // 如果正在连接或已连接，先断开
    if (m_socket->state() != QAbstractSocket::UnconnectedState) {
        m_socket->abort();
    }

    // 手动连接时，如果定时器在跑，先暂停，避免冲突
    m_reconnectTimer->stop();

    m_socket->connectToHost(hostIp, port);
}

void SyncClient::setAutoConnect(bool b) {
    if (m_autoConnect != b) {
        m_autoConnect = b;
        saveSettings();
        emit autoConnectChanged();
    }
}

void SyncClient::setAutoReconnect(bool b) {
    if (m_autoReconnect != b) {
        m_autoReconnect = b;
        saveSettings();
        emit autoReconnectChanged();

        // 如果当前未连接且开启了重连，立即尝试
        if (m_autoReconnect && m_socket->state() == QAbstractSocket::UnconnectedState && !m_savedIp.isEmpty()) {
            m_reconnectTimer->start(5000);
        } else if (!m_autoReconnect) {
            m_reconnectTimer->stop();
        }
    }
}

void SyncClient::setSavedIp(const QString &ip) {
    if (m_savedIp != ip) {
        m_savedIp = ip;
        saveSettings(); // 立即保存
        emit savedIpChanged();
    }
}

void SyncClient::setGridColumns(int cols) {
    if (m_gridColumns != cols) {
        m_gridColumns = cols;
        saveSettings(); // 立即保存
        emit gridColumnsChanged();
    }
}

void SyncClient::setStoragePath(const QString &path) {
    if (m_cachePath == path || path.isEmpty()) return;

    QString oldPath = m_cachePath;
    QString newPath = path;

    // 去除 file:/// 前缀（如果是从QML传来的URL）
    if (newPath.startsWith("file:///")) {
#ifdef Q_OS_WIN
        newPath = newPath.mid(8);
#else
        newPath = newPath.mid(7);
#endif
    }

    // 检查新路径是否可写
    QDir testDir(newPath);
    if (!testDir.exists()) {
        if (!testDir.mkpath(".")) {
            emit errorOccurred("无法创建新目录，权限不足或路径无效");
            return;
        }
    }

    qDebug() << "Moving cache from" << oldPath << "to" << newPath;

    // 执行移动
    if (moveDir(oldPath, newPath)) {
        // 删除旧目录
        QDir oldDir(oldPath);
        oldDir.removeRecursively();

        m_cachePath = newPath;
        saveSettings();

        emit storagePathChanged();
        emit cacheLocationChanged(); // 通知 UI 刷新 Model
        emit cacheRefreshed();       // 强制刷新列表
    } else {
        emit errorOccurred("移动文件失败，请检查目标是否有写权限");
    }
}

// 递归复制并删除
bool SyncClient::moveDir(const QString &source, const QString &destination) {
    QDir sourceDir(source);
    if (!sourceDir.exists()) return true; // 源不存在视为成功（没什么可移的）

    QDir destDir(destination);
    if (!destDir.exists()) destDir.mkpath(".");

    bool success = true;

    // 1. 遍历文件
    foreach (QString fileName, sourceDir.entryList(QDir::Files)) {
        QString srcFilePath = sourceDir.absoluteFilePath(fileName);
        QString destFilePath = destDir.absoluteFilePath(fileName);

        // 如果目标文件存在，先删除
        if (QFile::exists(destFilePath)) QFile::remove(destFilePath);

        if (!QFile::copy(srcFilePath, destFilePath)) {
            success = false;
            qDebug() << "Failed to copy file:" << fileName;
        }
    }

    // 2. 递归遍历子目录
    foreach (QString subDir, sourceDir.entryList(QDir::Dirs | QDir::NoDotAndDotDot)) {
        QString srcSubPath = sourceDir.absoluteFilePath(subDir);
        QString destSubPath = destDir.absoluteFilePath(subDir);
        if (!moveDir(srcSubPath, destSubPath)) {
            success = false;
        }
    }

    return success;
}

void SyncClient::resetStoragePath() {
    QString defaultPath = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + "/sd_cache/";
    setStoragePath(defaultPath);
}

void SyncClient::refreshCache() {
    emit cacheRefreshed();
}

void SyncClient::clearCache() {
    // 删除整个缓存目录并重新创建
    QDir cacheDir(m_cachePath);
    if (cacheDir.exists()) {
        cacheDir.removeRecursively();
        cacheDir.mkpath(".");
        qDebug() << "Cache cleared successfully";
        emit cacheRefreshed();
    }
}

QString SyncClient::cacheLocation() const
{
    return QUrl::fromLocalFile(m_cachePath).toString();
}

bool SyncClient::isConnected() const
{
    return m_socket->state() == QAbstractSocket::ConnectedState;
}

void SyncClient::deleteFileOrFolder(const QString &path) {
    QFileInfo info(path);
    if (info.isFile()) {
        QFile::remove(path);
        emit imageDeleted(path);
    } else if (info.isDir()) {
        QDir dir(path);
        dir.removeRecursively();
        emit folderDeleted(path);
    }
}

void SyncClient::onReadyRead() {
    QDataStream in(m_socket);
    in.setVersion(QDataStream::Qt_6_0);

    while (true) {
        if (m_bytesExpected == 0) {
            if (m_socket->bytesAvailable() < (qint64)sizeof(qint64)) return;
            in >> m_bytesExpected;
        }
        if (m_socket->bytesAvailable() < m_bytesExpected) return;

        QString relativePath;
        QByteArray fileData;
        in >> relativePath >> fileData;

        if (relativePath.startsWith("SYNC_FOLDER_LIST:")) {
            QString listStr = relativePath.mid(17); // 去掉头
            QStringList activeFolders = listStr.split("|", Qt::SkipEmptyParts);

            qDebug() << "[Sync] Server active folders:" << activeFolders;

            // 遍历本地缓存目录
            QDir cacheDir(m_cachePath);
            QStringList localFolders = cacheDir.entryList(QDir::Dirs | QDir::NoDotAndDotDot);

            for (const QString &localFolder : localFolders) {
                // 如果本地文件夹 不在 服务器的有效列表中，说明在断开期间被删了
                if (!activeFolders.contains(localFolder)) {
                    QString fullPath = m_cachePath + "/" + localFolder;
                    qDebug() << "[Sync] Deleting stale folder:" << fullPath;

                    QDir dir(fullPath);
                    dir.removeRecursively(); // 物理删除

                    // 通知 QML 刷新
                    emit folderDeleted("file:///" + fullPath);
                }
            }
            emit cacheRefreshed();
        }

        // 检查是否是删除指令
        if (relativePath.startsWith("DELETE_FILE:")) {
            // 提取相对路径 "Folder/Image.jpg"
            QString fileToDelete = relativePath.mid(12);

            // 拼接完整的本地缓存路径
            QString fullPath = m_cachePath + "/" + fileToDelete;

            QFile file(fullPath);
            if (file.exists()) {
                if (file.remove()) {
                    qDebug() << "[Sync] Deleted file from PC command:" << fullPath;
                    // 通知 QML 文件没了
                    // 注意：必须加 file:/// 前缀，因为 FolderListModel 用的是 URL
                    emit imageDeleted("file:///" + fullPath);

                    // [关键] 触发缓存刷新信号，强迫 UI 重绘
                    emit cacheRefreshed();
                } else {
                    qDebug() << "[Sync] Failed to delete:" << fullPath;
                }
            } else {
                qDebug() << "[Sync] File to delete not found:" << fullPath;
            }
        }
        else if (relativePath.startsWith("DELETE_FOLDER:")) {
            QString folderToDelete = relativePath.mid(14); // 去掉 "DELETE_FOLDER:"

            // 构造完整路径 .../sd_cache/Anime
            QString fullPath = m_cachePath + "/" + folderToDelete;

            QDir dir(fullPath);
            if (dir.exists()) {
                // [关键] 递归删除整个文件夹
                if (dir.removeRecursively()) {
                    qDebug() << "[Sync] Folder deleted:" << fullPath;

                    // 通知 QML 文件夹被删了，参数可以是文件夹的 URL
                    emit folderDeleted("file:///" + fullPath);
                    emit cacheRefreshed();
                } else {
                    qDebug() << "[Sync] Failed to delete folder:" << fullPath;
                }
            }
        }
        else {
            // 正常的文件传输
            QString fullPath = m_cachePath + "/" + relativePath;

            QFileInfo fileInfo(fullPath);
            QDir dir = fileInfo.absoluteDir();
            if (!dir.exists()) {
                dir.mkpath(".");
            }

            QFile file(fullPath);
            if (file.open(QIODevice::WriteOnly)) {
                file.write(fileData);
                file.close();
                emit newImageReceived(fullPath);
            }
        }

        m_bytesExpected = 0;
    }
}

void SyncClient::onConnected()
{
    m_reconnectTimer->stop();
    emit connectionChanged();
    qDebug() << "Connected to server!";
    updateAndroidNotification("SD同步服务", "状态：已连接 (" + m_savedIp + ")");

    // [新增] 连接成功后，立即发送清单，触发同步
    // 稍微延迟一下，避免和握手包撞车（可选）
    QTimer::singleShot(200, this, [this](){
        sendManifest();
    });
}

void SyncClient::onDisconnected()
{
    emit connectionChanged();
    qDebug() << "Disconnected from server!";
    // [新增] 更新状态
    updateAndroidNotification("SD同步服务", "状态：已断开 (等待重连...)");

    if (m_autoReconnect) {
        m_reconnectTimer->start(5000);
    }
}

void SyncClient::onReconnectTimeout() {
    if (m_socket->state() == QAbstractSocket::UnconnectedState && !m_savedIp.isEmpty()) {
        qDebug() << "Auto reconnecting...";
        m_socket->connectToHost(m_savedIp, 12345);
    } else {
        m_reconnectTimer->stop();
    }
}

void SyncClient::onError(QAbstractSocket::SocketError socketError)
{
    Q_UNUSED(socketError);
    // 错误不应该直接停止重连（比如网络暂时不可达），但可以记录
    emit errorOccurred(m_socket->errorString());

    // 如果是 ConnectionRefused，定时器会继续跑，不需要额外操作
}

QStringList SyncClient::getAllImageFiles() {
    QStringList files;
    QDirIterator it(m_cachePath,
                    QStringList() << "*.png" << "*.jpg" << "*.jpeg" << "*.webp",
                    QDir::Files,
                    QDirIterator::Subdirectories);
    while (it.hasNext()) {
        // 返回绝对路径，供QML直接使用 "file:///"
        files << "file:///" + it.next();
    }
    // 简单的按名称排序，保证浏览顺序一致
    files.sort();
    return files;
}

void SyncClient::deleteFile(const QString &relativePath) {
    // [修复5] 清理路径前缀
    QString cleanPath = relativePath;
    if (cleanPath.startsWith("file:///")) {
#ifdef Q_OS_WIN
        cleanPath = cleanPath.mid(8); // Windows 去掉 file:///
#else
        cleanPath = cleanPath.mid(7); // Linux/Android 去掉 file://
#endif
    }

    // 确保路径分隔符统一
    cleanPath = cleanPath.replace("\\", "/");

    // 此时 cleanPath 可能是绝对路径 "/data/user/0/.../cache/Root/xxx.jpg"
    // 也可能是相对路径，我们需要判断

    QFileInfo fileInfo(cleanPath);
    if (!fileInfo.isAbsolute()) {
        cleanPath = m_cachePath + "/" + cleanPath;
    }

    qDebug() << "[SyncClient] Trying to delete file:" << cleanPath;

    QFile file(cleanPath);
    if (file.exists()) {
        if (file.remove()) {
            qDebug() << "[SyncClient] Delete SUCCESS";
            // 必须传回带 file:/// 的路径给 QML，否则 QML 里的 Model 无法匹配删除
            emit imageDeleted("file:///" + cleanPath);
            emit cacheRefreshed();
        } else {
            qDebug() << "[SyncClient] Delete FAILED: " << file.errorString();
        }
    } else {
        qDebug() << "[SyncClient] File NOT FOUND";

        // 尝试另一种拼接方式（万一传入的是纯相对路径）
        QString retryPath = m_cachePath + "/" + relativePath;
        QFile retryFile(retryPath);
        if (retryFile.exists() && retryFile.remove()) {
            qDebug() << "[SyncClient] Retry Delete SUCCESS";
            emit imageDeleted("file:///" + retryPath);
            emit cacheRefreshed();
        }
    }
}

void SyncClient::sendManifest() {
    QMap<QString, qint64> manifest;

    // 遍历本地缓存目录
    QDirIterator it(m_cachePath,
                    QStringList() << "*.png" << "*.jpg" << "*.jpeg" << "*.webp",
                    QDir::Files, QDirIterator::Subdirectories);

    // 计算切除路径的前缀长度
    // 本地路径可能是 ".../sd_cache/Anime/01.jpg"
    // 我们需要提取 "Anime/01.jpg"
    int prefixLen = m_cachePath.length();
    if (!m_cachePath.endsWith('/')) prefixLen++;

    while (it.hasNext()) {
        it.next();
        QString fullPath = it.filePath();
        QString relativePath = fullPath.mid(prefixLen);

        // 统一路径分隔符，确保和服务端一致
        relativePath = relativePath.replace("\\", "/");

        manifest[relativePath] = it.fileInfo().size();
    }

    qDebug() << "[Sync] Sending manifest with" << manifest.size() << "files.";

    // 序列化 Map
    QByteArray payload;
    QDataStream stream(&payload, QIODevice::WriteOnly);
    stream.setVersion(QDataStream::Qt_6_0);
    stream << manifest;

    // 发送数据包
    QByteArray block;
    QDataStream out(&block, QIODevice::WriteOnly);
    out.setVersion(QDataStream::Qt_6_0);

    // 协议格式：Size | Header | Payload
    out << (qint64)0 << QString("CLIENT_MANIFEST") << payload;
    out.device()->seek(0);
    out << (qint64)(block.size() - sizeof(qint64));

    m_socket->write(block);
    m_socket->flush();
}
