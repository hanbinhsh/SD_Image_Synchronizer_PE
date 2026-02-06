#ifndef SYNCCLIENT_H
#define SYNCCLIENT_H

#include <QObject>
#include <QTcpSocket>
#include <QStandardPaths>
#include <QDir>
#include <QFile>
#include <QDataStream>
#include <QUrl>
#include <QTimer>
#include <QSettings>

// [新增] 引入 Android 相关头文件（仅在 Android 平台编译）
#ifdef Q_OS_ANDROID
#include <QJniObject>
#endif

class SyncClient : public QObject
{
    Q_OBJECT
    // ... [保留原有的属性] ...
    Q_PROPERTY(QString cacheLocation READ cacheLocation NOTIFY cacheLocationChanged)
    Q_PROPERTY(bool isConnected READ isConnected NOTIFY connectionChanged)
    Q_PROPERTY(QString savedIp READ savedIp WRITE setSavedIp NOTIFY savedIpChanged)
    Q_PROPERTY(int gridColumns READ gridColumns WRITE setGridColumns NOTIFY gridColumnsChanged)
    Q_PROPERTY(bool autoConnect READ autoConnect WRITE setAutoConnect NOTIFY autoConnectChanged)
    Q_PROPERTY(bool autoReconnect READ autoReconnect WRITE setAutoReconnect NOTIFY autoReconnectChanged)
    Q_PROPERTY(QString storagePath READ storagePath WRITE setStoragePath NOTIFY storagePathChanged)

    // [新增] 后台模式开关属性
    Q_PROPERTY(bool backgroundMode READ backgroundMode WRITE setBackgroundMode NOTIFY backgroundModeChanged)

public:
    ~SyncClient();
    explicit SyncClient(QObject *parent = nullptr);

    // ... [保留原有 Q_INVOKABLE 方法] ...
    Q_INVOKABLE void connectToHost(const QString &hostIp, int port = 12345);
    Q_INVOKABLE void refreshCache();
    Q_INVOKABLE void clearCache();
    Q_INVOKABLE QStringList getAllImageFiles();
    Q_INVOKABLE void deleteFile(const QString &relativePath);
    Q_INVOKABLE void resetStoragePath();

    QString cacheLocation() const;
    bool isConnected() const;
    QString savedIp() const { return m_savedIp; }
    void setSavedIp(const QString &ip);
    int gridColumns() const { return m_gridColumns; }
    void setGridColumns(int cols);
    bool autoConnect() const { return m_autoConnect; }
    void setAutoConnect(bool b);
    bool autoReconnect() const { return m_autoReconnect; }
    void setAutoReconnect(bool b);
    QString storagePath() const { return m_cachePath; }
    void setStoragePath(const QString &path);

    // [新增] Getter & Setter
    bool backgroundMode() const { return m_backgroundMode; }
    void setBackgroundMode(bool enable);

    void sendManifest();

signals:
    // ... [保留原有信号] ...
    void connectionChanged();
    void newImageReceived(QString filePath);
    void imageDeleted(QString filePath);
    void folderDeleted(QString folderPath);
    void errorOccurred(QString message);
    void savedIpChanged();
    void gridColumnsChanged();
    void cacheRefreshed();
    void autoConnectChanged();
    void autoReconnectChanged();
    void storagePathChanged();
    void cacheLocationChanged();

    // [新增]
    void backgroundModeChanged();

private slots:
    void onReadyRead();
    void onConnected();
    void onDisconnected();
    void onError(QAbstractSocket::SocketError socketError);
    void onReconnectTimeout();

private:
    QTcpSocket *m_socket;
    QTimer *m_reconnectTimer;
    QSettings *m_settings;
    qint64 m_bytesExpected = 0;
    QString m_cachePath;
    QString m_savedIp;
    int m_gridColumns = 3;
    bool m_autoConnect = false;
    bool m_autoReconnect = false;

    // [新增]
    bool m_backgroundMode = false;
#ifdef Q_OS_ANDROID
    QJniObject m_wakeLock; // Android 唤醒锁对象
#endif

    void deleteFileOrFolder(const QString &path);
    bool moveDir(const QString &source, const QString &destination);
    void loadSettings();
    void saveSettings();

    // [新增] 私有辅助方法
    void updateAndroidNotification(const QString &title, const QString &content);
    void acquireWakeLock();
    void releaseWakeLock();
};

#endif
