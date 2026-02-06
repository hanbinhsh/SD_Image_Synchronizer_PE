cmake_minimum_required(VERSION 3.16)

project(SD_Image_Synchronizer_PE VERSION 0.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 REQUIRED COMPONENTS Quick Network)

qt_standard_project_setup(REQUIRES 6.8)

qt_add_executable(appSD_Image_Synchronizer_PE
    main.cpp
)

if(ANDROID)
    # 1. 告诉 Qt Android 部署工具，自定义的 android 文件夹在哪里
    #    这样编译时会自动使用里面的 AndroidManifest.xml、res、src (Java) 等文件
    set_target_properties(appSD_Image_Synchronizer_PE PROPERTIES
        QT_ANDROID_PACKAGE_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/android"
    )

    # 2. (可选) 将 AndroidManifest.xml 加入项目文件列表
    #    这一步不是编译必须的，但可以让你在 Qt Creator 的项目树中直接看到并编辑它
    target_sources(appSD_Image_Synchronizer_PE PRIVATE
        android/AndroidManifest.xml
    )
endif()

qt_add_qml_module(appSD_Image_Synchronizer_PE
    URI SD_Image_Synchronizer_PE
    QML_FILES
        Main.qml
        SOURCES syncclient.h syncclient.cpp
)

# Qt for iOS sets MACOSX_BUNDLE_GUI_IDENTIFIER automatically since Qt 6.1.
# If you are developing for iOS or macOS you should consider setting an
# explicit, fixed bundle identifier manually though.
set_target_properties(appSD_Image_Synchronizer_PE PROPERTIES
#    MACOSX_BUNDLE_GUI_IDENTIFIER com.example.appSD_Image_Synchronizer_PE
    MACOSX_BUNDLE_BUNDLE_VERSION ${PROJECT_VERSION}
    MACOSX_BUNDLE_SHORT_VERSION_STRING ${PROJECT_VERSION_MAJOR}.${PROJECT_VERSION_MINOR}
    MACOSX_BUNDLE TRUE
    WIN32_EXECUTABLE TRUE
)

target_link_libraries(appSD_Image_Synchronizer_PE
    PRIVATE
    Qt6::Gui
    Qt6::Qml
    Qt6::Quick
    Qt6::Network
)

include(GNUInstallDirs)
install(TARGETS appSD_Image_Synchronizer_PE
    BUNDLE DESTINATION .
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)
