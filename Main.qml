import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Qt.labs.folderlistmodel

Window {
    id: window
    visible: true
    width: 360; height: 720
    color: "#121212"

    property int statusBarHeight: 35

    property int currentTab: 0

    // 全局返回键处理
    Item {
        focus: true
        Keys.onBackPressed: {
            event.accepted = true
            if (navStack.depth > 1) {
                navStack.pop()
            }
        }
    }

    Connections {
        target: syncClient
        function onErrorOccurred(msg) {
            console.error(msg)
            // 这里可以用一个 Toast 或者 Dialog 显示 msg
        }
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 0

        // 顶部导航栏
        Rectangle {
            id: topBar
            Layout.fillWidth: true
            Layout.preferredHeight: 50 + statusBarHeight
            color: "#1f1f1f"
            z: 10

            // 当在查看器中且UI隐藏时，变为透明
            opacity: (navStack.currentItem && navStack.currentItem.isViewer === true && !navStack.currentItem.uiVisible) ? 0.0 : 1.0
            visible: opacity > 0

            Behavior on opacity {
                NumberAnimation { duration: 200 }
            }

            RowLayout {
                anchors.fill: parent
                anchors.topMargin: statusBarHeight
                anchors.leftMargin: 10; anchors.rightMargin: 10

                ToolButton {
                    text: navStack.depth > 1 ? "<" : "⚙"
                    onClicked: {
                        if (navStack.depth > 1) navStack.pop()
                        else navStack.push(settingsComponent)
                    }
                    contentItem: Text { text: parent.text; color: "white"; font.pixelSize: 22 }
                }

                Label {
                    text: navStack.currentItem ? navStack.currentItem.title : "图库"
                    color: "white"; font.bold: true; Layout.fillWidth: true
                }

                Button {
                    text: syncClient.isConnected ? "已连接" : "连接"
                    onClicked: syncClient.connectToHost(syncClient.savedIp)
                    background: Rectangle { color: syncClient.isConnected ? "#2e7d32" : "#333"; radius: 6 }
                    contentItem: Text { text: parent.text; color: "white" }
                }
            }
        }

        // Tab栏
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 45
            color: "#1a1a1a"
            visible: navStack.depth === 1

            RowLayout {
                anchors.fill: parent
                spacing: 0

                Repeater {
                    model: ["文件夹", "全部图片"]
                    TabButton {
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        text: modelData
                        checked: currentTab === index
                        onClicked: {
                            currentTab = index
                            if (index === 0) navStack.replace(folderViewComponent)
                            else navStack.replace(allImagesComponent)
                        }
                        background: Rectangle {
                            color: parent.checked ? "#2a2a2a" : "#1a1a1a"
                            Rectangle {
                                width: parent.width; height: 3
                                anchors.bottom: parent.bottom
                                color: "#1976d2"
                                visible: parent.parent.checked
                            }
                        }
                        contentItem: Text {
                            text: parent.text
                            color: parent.checked ? "white" : "#888"
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                            font.pixelSize: 15
                        }
                    }
                }
            }
        }

        StackView {
            id: navStack
            Layout.fillWidth: true
            Layout.fillHeight: true
            initialItem: currentTab === 0 ? folderViewComponent : allImagesComponent
        }
    }

    // 设置页
    Component {
        id: settingsComponent
        Item {
            property string title: "设置"
            ScrollView {
                            anchors.fill: parent
                contentWidth: parent.width

                Column {
                    width: parent.width
                    anchors.margins: 20
                    // 注意：ScrollView 里的锚点需要稍微调整，或者直接用 padding
                    padding: 20
                    spacing: 20

                    // --- 网络设置 ---
                    Label { text: "网络设置"; color: "#888"; font.bold: true }

                    Label { text: "服务器 IP 地址:"; color: "white" }
                    TextField {
                        width: parent.width - 40
                        text: syncClient.savedIp
                        onEditingFinished: syncClient.savedIp = text
                        color: "white"
                        background: Rectangle { color: "#333"; radius: 5 }
                    }

                    // [新增] 自动连接开关
                    RowLayout {
                        width: parent.width - 40
                        Label {
                            text: "启动时自动连接"
                            color: "white"
                            Layout.fillWidth: true
                        }
                        Switch {
                            checked: syncClient.autoConnect
                            onToggled: syncClient.autoConnect = checked
                        }
                    }

                    // [新增] 断线重连开关
                    RowLayout {
                        width: parent.width - 40
                        Label {
                            text: "断线自动重连"
                            color: "white"
                            Layout.fillWidth: true
                        }
                        Switch {
                            checked: syncClient.autoReconnect
                            onToggled: syncClient.autoReconnect = checked
                        }
                    }

                    Rectangle { height: 1; width: parent.width - 40; color: "#333" }

                    // [新增] 后台模式 & 通知栏设置
                    Label { text: "后台运行"; color: "#888"; font.bold: true }

                    RowLayout {
                        width: parent.width - 40
                        Label {
                            text: "前台服务通知 & 防休眠"
                            color: "white"
                            Layout.fillWidth: true
                        }
                        Switch {
                            checked: syncClient.backgroundMode
                            onToggled: syncClient.backgroundMode = checked
                        }
                    }

                    Label {
                        text: "开启后会在通知栏显示连接状态，并防止系统在后台断开网络连接 (需授予通知权限)。"
                        color: "#aaa"
                        font.pixelSize: 12
                        wrapMode: Text.WordWrap
                        width: parent.width - 40
                    }

                    Rectangle { height: 1; width: parent.width - 40; color: "#333" }

                    // --- 显示设置 ---
                    Label { text: "显示设置"; color: "#888"; font.bold: true }

                    Label { text: "宫格列数: " + syncClient.gridColumns; color: "white" }
                    Slider {
                        width: parent.width - 40
                        from: 1; to: 15; stepSize: 1
                        value: syncClient.gridColumns
                        onMoved: syncClient.gridColumns = value // 拖动结束再设置
                    }

                    Rectangle { height: 1; width: parent.width - 40; color: "#333" }

                    // --- 存储设置 [新增] ---
                    Label { text: "存储设置"; color: "#888"; font.bold: true }

                    Label {
                        text: "当前存储路径:"
                        color: "white"
                    }
                    Label {
                        text: syncClient.storagePath
                        color: "#aaa"
                        font.pixelSize: 12
                        wrapMode: Text.WrapAnywhere
                        width: parent.width - 40
                    }

                    Button {
                        text: "更改存储位置 (粘贴路径)"
                        width: parent.width - 40
                        onClicked: pathInputDialog.open()
                        background: Rectangle { color: "#333"; radius: 5 }
                        contentItem: Text { text: parent.text; color: "white"; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                    }

                    Button {
                        text: "恢复默认存储位置"
                        width: parent.width - 40
                        onClicked: syncClient.resetStoragePath()
                        background: Rectangle { color: "#333"; radius: 5 }
                        contentItem: Text { text: parent.text; color: "white"; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                    }

                    Label {
                        text: "⚠️ 修改路径将自动移动现有图片，请确保目标路径有写入权限。"
                        color: "#ff9800"
                        font.pixelSize: 12
                        wrapMode: Text.WordWrap
                        width: parent.width - 40
                    }

                    Rectangle { height: 1; width: parent.width - 40; color: "#333" }

                    // --- 缓存管理 ---
                    Button {
                        width: parent.width - 40
                        text: "清除所有缓存图片"
                        onClicked: clearCacheDialog.open()
                        background: Rectangle { color: "#d32f2f"; radius: 5 }
                        contentItem: Text {
                            text: parent.text
                            color: "white"
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                        }
                    }
                }
            } // End ScrollView
            Dialog {
                id: pathInputDialog
                anchors.centerIn: parent
                width: parent.width * 0.9
                title: "设置新路径"
                modal: true

                contentItem: Column {
                    spacing: 15
                    padding: 10
                    Label { text: "请输入绝对路径:"; color: "white" }
                    TextField {
                        id: pathField
                        width: parent.width
                        text: syncClient.storagePath
                        color: "white"
                        background: Rectangle { color: "#444"; radius: 4 }
                        selectByMouse: true
                    }
                    RowLayout {
                        spacing: 10
                        Button {
                            text: "取消"
                            onClicked: pathInputDialog.close()
                        }
                        Button {
                            text: "确定移动"
                            Layout.fillWidth: true
                            onClicked: {
                                syncClient.storagePath = pathField.text
                                pathInputDialog.close()
                            }
                            background: Rectangle { color: "#1976d2"; radius: 4 }
                            contentItem: Text { text: parent.text; color: "white"; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                        }
                    }
                }
                background: Rectangle { color: "#2a2a2a"; radius: 8 }
            }
            Dialog {
                id: clearCacheDialog
                anchors.centerIn: parent
                width: parent.width * 0.8
                title: "确认清除缓存"
                modal: true

                contentItem: Column {
                    spacing: 20
                    padding: 20

                    Label {
                        text: "确定要删除所有缓存的图片吗？\n此操作不可恢复！"
                        color: "white"
                        wrapMode: Text.WordWrap
                        width: parent.width - 40
                    }

                    Row {
                        spacing: 10
                        anchors.horizontalCenter: parent.horizontalCenter

                        Button {
                            text: "取消"
                            onClicked: clearCacheDialog.close()
                            background: Rectangle { color: "#555"; radius: 5 }
                            contentItem: Text { text: parent.text; color: "white" }
                        }

                        Button {
                            text: "确认清除"
                            onClicked: {
                                syncClient.clearCache()
                                clearCacheDialog.close()
                            }
                            background: Rectangle { color: "#d32f2f"; radius: 5 }
                            contentItem: Text { text: parent.text; color: "white" }
                        }
                    }
                }

                background: Rectangle {
                    color: "#2a2a2a"
                    radius: 8
                }
            }
        }
    }

    // 文件夹视图
    Component {
        id: folderViewComponent
        Item {
            property string title: "文件夹"
            property string currentPath: syncClient.cacheLocation

            GridView {
                id: grid
                anchors.fill: parent
                cellWidth: width / syncClient.gridColumns
                cellHeight: cellWidth

                model: FolderListModel {
                    id: fModel
                    folder: currentPath
                    showDirs: true
                    showFiles: false  // 只显示文件夹，不显示文件
                    showDotAndDotDot: false
                }

                delegate: Item {
                    width: grid.cellWidth; height: grid.cellHeight
                    Rectangle {
                        anchors.fill: parent; anchors.margins: 2; color: "#252525"; radius: 4

                        // 文件夹图标
                        Column {
                            anchors.centerIn: parent
                            spacing: 5
                            Text {
                                text: "📁"
                                font.pixelSize: 40
                                anchors.horizontalCenter: parent.horizontalCenter
                            }
                            Text {
                                text: fileName
                                color: "white"
                                font.pixelSize: 12
                                elide: Text.ElideRight
                                width: grid.cellWidth - 10
                                horizontalAlignment: Text.AlignHCenter
                            }
                        }

                        MouseArea {
                            anchors.fill: parent
                            onClicked: {
                                navStack.push(subFolderComponent, {
                                    currentPath: model.fileUrl,
                                    folderName: fileName
                                })
                            }
                        }
                    }
                }

                // 空状态提示
                Label {
                    anchors.centerIn: parent
                    text: "没有文件夹\n请在电脑端添加同步文件夹"
                    color: "#666"
                    font.pixelSize: 16
                    horizontalAlignment: Text.AlignHCenter
                    visible: grid.count === 0
                }
            }

            Connections {
                target: syncClient
                function onNewImageReceived() { fModel.folder = currentPath }
                function onCacheRefreshed() { fModel.folder = currentPath }
            }
        }
    }

    // 子文件夹
    Component {
        id: subFolderComponent
        Item {
            property string title: folderName
            property string folderName
            property string currentPath

            GridView {
                id: subGrid
                anchors.fill: parent
                cellWidth: width / syncClient.gridColumns
                cellHeight: cellWidth

                model: FolderListModel {
                    id: subModel
                    folder: currentPath
                    showDirs: true; showDotAndDotDot: false
                    nameFilters: ["*.png", "*.jpg", "*.jpeg", "*.webp"]
                }

                delegate: Item {
                    width: subGrid.cellWidth; height: subGrid.cellHeight
                    Rectangle {
                        anchors.fill: parent; anchors.margins: 2; color: "#252525"; radius: 4

                        // 文件夹图标
                        Column {
                            visible: fileIsDir
                            anchors.centerIn: parent
                            spacing: 5
                            Text {
                                text: "📁"
                                font.pixelSize: 40
                                anchors.horizontalCenter: parent.horizontalCenter
                            }
                            Text {
                                text: fileName
                                color: "white"
                                font.pixelSize: 12
                                elide: Text.ElideRight
                                width: subGrid.cellWidth - 10
                                horizontalAlignment: Text.AlignHCenter
                            }
                        }

                        // 图片预览
                        Image {
                            visible: !fileIsDir
                            anchors.fill: parent; anchors.margins: 2
                            fillMode: Image.PreserveAspectCrop; asynchronous: true
                            source: fileIsDir ? "" : model.fileUrl
                            sourceSize.width: 300
                        }

                        MouseArea {
                            anchors.fill: parent
                            onClicked: {
                                if (fileIsDir) {
                                    navStack.push(subFolderComponent, {
                                        currentPath: model.fileUrl,
                                        folderName: fileName
                                    })
                                } else {
                                    navStack.push(viewerComponent, {
                                        isListMode: false, // 文件夹模式
                                        folderPath: currentPath,
                                        initialIndex: index
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 全部图片视图
    Component {
        id: allImagesComponent
        Item {
            // [修复] 1. 给页面实例起一个 ID，供内部引用
            id: allImagesPage

            property string title: "全部图片"
            property var allFiles: [] // 存储所有文件路径

            // 页面加载时获取文件列表
            Component.onCompleted: refreshList()

            function refreshList() {
                // 调用 C++ 接口获取扁平化列表
                allFiles = syncClient.getAllImageFiles()
                // 强制 GridView 刷新
                allGridView.model = allFiles
            }

            // 监听缓存刷新信号，自动更新列表
            Connections {
                target: syncClient
                function onCacheRefreshed() {
                    refreshList()
                }
            }

            GridView {
                id: allGridView
                anchors.fill: parent
                clip: true

                cellWidth: width / syncClient.gridColumns
                cellHeight: cellWidth

                // 如果列表为空的提示
                Label {
                    anchors.centerIn: parent
                    text: "暂无图片"
                    visible: allGridView.count === 0
                    color: "#666"
                    font.pixelSize: 16
                }

                delegate: Item {
                    width: allGridView.cellWidth
                    height: allGridView.cellHeight

                    Rectangle {
                        anchors.fill: parent
                        anchors.margins: 2
                        color: "#252525"
                        radius: 4

                        Image {
                            anchors.fill: parent
                            source: modelData
                            fillMode: Image.PreserveAspectCrop
                            asynchronous: true
                            sourceSize.width: 200
                        }

                        MouseArea {
                            anchors.fill: parent
                            onClicked: {
                                // [修复] 2. 使用 allImagesPage.allFiles 引用实例的数据
                                // 之前错误的写法是 allImagesComponent.allFiles (引用了组件而非实例)
                                navStack.push(viewerComponent, {
                                    isListMode: true,
                                    imageList: allImagesPage.allFiles, // <--- 关键修改
                                    initialIndex: index
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    // 全屏查看器
    Component {
        id: viewerComponent
        Item {
            // [修复1] 给根节点起名，供内部引用
            id: viewerRoot

            property bool isViewer: true
            property bool isListMode: false
            property var imageList: []
            property string folderPath: ""
            property int initialIndex: 0

            // [修复2] 增加标志位，防止反复跳转
            property bool hasJumped: false
            property bool uiVisible: true

            // 动态标题
            property string title: (photoList.currentIndex + 1) + " / " + photoList.count

            // 数据模型
            FolderListModel {
                id: folderModel
                folder: viewerRoot.folderPath
                showDirs: false
                nameFilters: ["*.png", "*.jpg", "*.jpeg", "*.webp"]
                sortField: FolderListModel.Name

                // [修复2] 监听数据加载完成，且只跳一次
                onCountChanged: {
                    if (count > 0 && !viewerRoot.hasJumped && !viewerRoot.isListMode) {
                        if (viewerRoot.initialIndex < count) {
                            console.log("Model loaded, jumping to index: " + viewerRoot.initialIndex)
                            photoList.positionViewAtIndex(viewerRoot.initialIndex, ListView.Center)
                            viewerRoot.hasJumped = true
                        }
                    }
                }
            }

            ListView {
                id: photoList
                anchors.fill: parent
                orientation: ListView.Horizontal
                snapMode: ListView.SnapOneItem
                highlightRangeMode: ListView.StrictlyEnforceRange
                highlightMoveDuration: 250
                cacheBuffer: width

                model: viewerRoot.isListMode ? viewerRoot.imageList : folderModel

                property var folderModel: viewerRoot.isListMode ? null : folderModelObject
                property var folderModelObject: null

                Component.onCompleted: {
                    if (!viewerRoot.isListMode) folderModelObject = folderModelComponent.createObject(photoList)
                    if (viewerRoot.initialIndex >= 0) positionViewAtIndex(viewerRoot.initialIndex, ListView.Center)
                }

                delegate: Item {
                    width: photoList.width
                    height: photoList.height

                    property string imgSrc: viewerRoot.isListMode ? modelData : model.fileUrl
                    property bool isCurrent: ListView.isCurrentItem

                    // 切换图片时，强制复位
                    onIsCurrentChanged: if (!isCurrent) resetZoom()

                    function resetZoom() {
                        imgContainer.width = flick.width
                        imgContainer.height = flick.height
                        flick.contentX = 0
                        flick.contentY = 0
                        // 强制刷新 Flickable 内部状态
                        flick.returnToBounds()
                    }

                    Flickable {
                        id: flick
                        anchors.fill: parent
                        clip: true

                        // [修复 1] 动态计算边距，实现缩小后居中
                        // 当内容小于屏幕时，边距为 (屏幕-内容)/2，否则为 0
                        leftMargin: (width - contentWidth) > 0 ? (width - contentWidth) / 2 : 0
                        topMargin: (height - contentHeight) > 0 ? (height - contentHeight) / 2 : 0

                        // [修复 2] 显式绑定内容尺寸，确保 Flickable 内部逻辑同步
                        contentWidth: imgContainer.width
                        contentHeight: imgContainer.height

                        // 缩放大于1时接管交互，等于1时透传给ListView
                        interactive: imgContainer.width > flick.width

                        // 允许越界回弹
                        boundsBehavior: Flickable.DragAndOvershootBounds

                        onDraggingChanged: {
                            if (!dragging && interactive) {
                                var threshold = 100
                                if (atXBeginning && contentX < -threshold) {
                                    photoList.decrementCurrentIndex()
                                    resetZoom()
                                }
                                else if (atXEnd && contentX > (contentWidth - width) + threshold) {
                                    photoList.incrementCurrentIndex()
                                    resetZoom()
                                }
                            }
                        }

                        Item {
                            id: imgContainer
                            width: flick.width
                            height: flick.height
                            // [修复 3] 移除 transformOrigin: Item.TopLeft，避免逻辑干扰
                            // 默认中心点即可，因为我们是通过 width/height 调整大小的

                            Image {
                                id: img
                                anchors.fill: parent
                                source: imgSrc
                                fillMode: Image.PreserveAspectFit
                                asynchronous: true
                                mipmap: true
                            }

                            // [修复 4] PinchArea 逻辑优化
                            PinchArea {
                                anchors.fill: parent

                                property real initialWidth
                                property real initialHeight

                                onPinchStarted: {
                                    initialWidth = flick.contentWidth
                                    initialHeight = flick.contentHeight
                                }

                                onPinchUpdated: (pinch) => {
                                    var center = pinch.center

                                    // 计算新尺寸
                                    var newWidth = initialWidth * pinch.scale
                                    var newHeight = initialHeight * pinch.scale

                                    // 限制缩放范围 (1.0 - 5.0)
                                    if (newWidth < flick.width) newWidth = flick.width
                                    if (newWidth > flick.width * 5.0) newWidth = flick.width * 5.0

                                    // 应用尺寸
                                    imgContainer.width = newWidth
                                    imgContainer.height = newHeight

                                    // resizeContent 会自动调整 contentX/Y 以保持 pinch.center 对应的点不动
                                    // 配合上面的 contentWidth/Height 绑定，这能解决"往右下跑"的问题
                                    flick.resizeContent(newWidth, newHeight, center)

                                    // 关键：缩放过程中禁止回弹，否则画面会抖动
                                    flick.returnToBounds()
                                }

                                onPinchFinished: {
                                    // 缩放结束后，如果小于屏幕，复位
                                    if (imgContainer.width < flick.width) {
                                        resetAnim.start()
                                    } else {
                                        flick.returnToBounds()
                                    }
                                }

                                // ... MouseArea (点击事件保持不变) ...
                                MouseArea {
                                    anchors.fill: parent
                                    onClicked: viewerRoot.uiVisible = !viewerRoot.uiVisible
                                    onDoubleClicked: {
                                        if (imgContainer.width > flick.width) {
                                            resetAnim.start()
                                        } else {
                                            var targetScale = 2.5
                                            var newW = flick.width * targetScale
                                            var newH = flick.height * targetScale

                                            imgContainer.width = newW
                                            imgContainer.height = newH

                                            // 双击时以鼠标位置为中心放大
                                            flick.resizeContent(newW, newH, Qt.point(mouseX, mouseY))
                                            flick.returnToBounds()
                                        }
                                    }
                                }
                            }
                        }

                        // ... 复位动画保持不变 ...
                        ParallelAnimation {
                            id: resetAnim
                            NumberAnimation { target: imgContainer; property: "width"; to: flick.width; duration: 200 }
                            NumberAnimation { target: imgContainer; property: "height"; to: flick.height; duration: 200 }
                            NumberAnimation { target: flick; property: "contentX"; to: 0; duration: 200 }
                            NumberAnimation { target: flick; property: "contentY"; to: 0; duration: 200 }
                        }
                    }
                }
            }

            // 顶部栏删除按钮（如果你有的话，或者通过长按删除）
            // 这里假设你在 TopBar 加了删除按钮，或者通过某种方式触发
            function deleteCurrentPhoto() {
                var currentItem = photoList.currentItem
                if (currentItem) {
                    var path = currentItem.imgSrc
                    console.log("Requesting delete for: " + path) // [修复5] 调试日志
                    syncClient.deleteFile(path)
                }
            }
        }
    }
}
