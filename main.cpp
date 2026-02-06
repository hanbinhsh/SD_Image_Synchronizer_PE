#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include "syncclient.h"

int main(int argc, char *argv[])
{
    QGuiApplication app(argc, argv);

    app.setOrganizationName("MyStudio");
    app.setOrganizationDomain("mystudio.com");
    app.setApplicationName("SD_Image_Synchronizer_PE");

    // 1. 初始化 C++ 后端
    SyncClient client;

    QQmlApplicationEngine engine;

    // 2. 注入对象 (必须在加载 QML 之前)
    engine.rootContext()->setContextProperty("syncClient", &client);

    // 3. 错误处理连接 (推荐保留，用于调试)
    QObject::connect(
        &engine,
        &QQmlApplicationEngine::objectCreationFailed,
        &app,
        []() { QCoreApplication::exit(-1); },
        Qt::QueuedConnection);

    // 4. 【关键修改】使用 loadFromModule 加载
    // 参数1: URI (对应 CMakeLists.txt 中的 URI)
    // 参数2: QML 文件名 (不带后缀)
    engine.loadFromModule("SD_Image_Synchronizer_PE", "Main");

    return app.exec();
}
