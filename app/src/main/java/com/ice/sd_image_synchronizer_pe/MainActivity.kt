package com.ice.sd_image_synchronizer_pe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.navArgument
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectDragGestures
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.ZoomSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch

fun encodeUrl(url: String): String {
    return try {
        URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
    } catch (e: Exception) {
        url
    }
}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全面屏：内容延伸到系统栏下方
        enableEdgeToEdge()

        // 设置窗口背景为纯黑，解决切换页面时的白屏闪烁
        window.setBackgroundDrawableResource(android.R.color.black)

        // 深色界面：状态栏/导航栏图标使用浅色，保证可见
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // 初始化数据
        SyncManager.init(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    PermissionAwareApp(
                        hasFileAccessPermission = ::hasFileAccessPermission,
                        fileAccessSettingsIntent = ::fileAccessSettingsIntent,
                        hasNotificationPermission = ::hasNotificationPermission
                    )
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        // 关键：当用户从系统设置页面授予权限并返回应用时，立刻刷新数据
        SyncManager.refreshAllData()
    }

    private fun hasFileAccessPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun fileAccessSettingsIntent(): Intent {
        val appSettingsIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        return if (appSettingsIntent.resolveActivity(packageManager) != null) {
            appSettingsIntent
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}

// 定义路由参数
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    // 子文件夹：使用 ?path=...
    const val SUBFOLDER = "subfolder?path={path}&name={name}"
    // 大图查看：关键修改！必须使用 ?path={path}
    const val VIEWER = "viewer/{mode}/{index}?path={path}"
}

@Composable
fun PermissionAwareApp(
    hasFileAccessPermission: () -> Boolean,
    fileAccessSettingsIntent: () -> Intent,
    hasNotificationPermission: () -> Boolean
) {
    var showFileAccessDialog by remember { mutableStateOf(!hasFileAccessPermission()) }
    var fileAccessSettingsOpened by remember { mutableStateOf(false) }
    var pendingEnableBackgroundMode by remember { mutableStateOf(false) }
    var showNotificationPermissionDialog by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingEnableBackgroundMode) {
            SyncManager.setBackgroundMode(true)
        }
        pendingEnableBackgroundMode = false
    }

    val fileAccessSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        showFileAccessDialog = !hasFileAccessPermission()
    }

    LaunchedEffect(fileAccessSettingsOpened) {
        if (fileAccessSettingsOpened && hasFileAccessPermission()) {
            showFileAccessDialog = false
        }
    }

    AppNavigation(
        hasNotificationPermission = hasNotificationPermission,
        requestNotificationPermission = {
            pendingEnableBackgroundMode = true
            showNotificationPermissionDialog = true
        }
    )

    if (showFileAccessDialog) {
        AlertDialog(
            onDismissRequest = { showFileAccessDialog = false },
            title = { Text("需要文件访问权限") },
            text = { Text("应用需要读取和管理同步图片缓存目录，否则无法正常显示和保存同步图片。") },
            confirmButton = {
                TextButton(onClick = {
                    fileAccessSettingsOpened = true
                    showFileAccessDialog = false
                    fileAccessSettingsLauncher.launch(fileAccessSettingsIntent())
                }) {
                    Text("去授权")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFileAccessDialog = false }) {
                    Text("稍后")
                }
            }
        )
    }

    if (showNotificationPermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                showNotificationPermissionDialog = false
                pendingEnableBackgroundMode = false
            },
            title = { Text("需要通知权限") },
            text = { Text("开启前台服务后，应用需要在通知栏显示同步状态，防止系统在后台断开连接。") },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationPermissionDialog = false
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) {
                    Text("允许通知")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotificationPermissionDialog = false
                    pendingEnableBackgroundMode = false
                }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun AppNavigation(
    hasNotificationPermission: () -> Boolean,
    requestNotificationPermission: () -> Unit
) {
    val navController = rememberNavController()

    NavHost(navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(navController) }

        // 子文件夹页
        composable(
            route = Routes.SUBFOLDER,
            // 定义参数默认值
            arguments = listOf(
                navArgument("path") { defaultValue = "" },
                navArgument("name") { defaultValue = "文件夹" }
            )
        ) { entry ->
            val path = entry.arguments?.getString("path") ?: ""
            val name = entry.arguments?.getString("name") ?: ""
            SubFolderScreen(navController, path, name)
        }

        // Viewer 页
        composable(
            // 必须与 Routes.VIEWER 一致
            route = "viewer/{mode}/{index}?path={path}",
            arguments = listOf(
                // 定义 path 是可选参数，默认为空
                navArgument("path") { defaultValue = "" }
            ),
                    // [修复] 进入动画：简单的淡入
            enterTransition = {
                fadeIn(animationSpec = tween(300))
            },
            // [关键修复] 退出动画：强制瞬间消失 (0毫秒)，避免渲染引擎处理放大后的图层动画
            exitTransition = {
                fadeOut(animationSpec = tween(0))
            },
            // [修复] 同样处理由于手势返回导致的弹出动画
            popEnterTransition = {
                fadeIn(animationSpec = tween(0))
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(0))
            }
        ) { entry ->
            val mode = entry.arguments?.getString("mode") ?: "all"
            val index = entry.arguments?.getString("index")?.toInt() ?: 0
            val path = entry.arguments?.getString("path") ?: ""
            ViewerScreen(navController, mode, index, path)
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                navController,
                hasNotificationPermission = hasNotificationPermission,
                requestNotificationPermission = requestNotificationPermission
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: androidx.navigation.NavController) {
    val isConnected by SyncManager.isConnected
    val isConnecting by SyncManager.isConnecting
    val logText by SyncManager.logText
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("文件夹", "全部图片")
    val pagerState = rememberPagerState(initialPage = selectedTab, pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(pagerState.currentPage) {
        selectedTab = pagerState.currentPage
    }

    // 连接状态变化时用 Snackbar 反馈（跳过首次的初始状态）
    var lastLog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(logText) {
        if (lastLog != null && logText != lastLog) {
            snackbarHostState.showSnackbar(logText)
        }
        lastLog = logText
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("SD Sync") },
                    actions = {
                        // 连接按钮：连接中显示进度，避免无反馈
                        IconButton(
                            onClick = { SyncManager.toggleConnection() },
                            enabled = !isConnecting
                        ) {
                            if (isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Icon(
                                    imageVector = if (isConnected) Icons.Default.Link else Icons.Default.LinkOff,
                                    contentDescription = "Connect",
                                    tint = if (isConnected) Color.Green else Color.Red
                                )
                            }
                        }
                        // 设置按钮
                        IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                            Icon(Icons.Default.Settings, "Settings")
                        }
                    }
                )
                // Tab 栏
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = {
                                selectedTab = index
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) { page ->
            when (page) {
                0 -> FolderListContent(navController)
                1 -> AllImagesContent(navController)
            }
        }
    }
}

// Tab 0: 文件夹列表视图
@Composable
fun FolderListContent(navController: androidx.navigation.NavController) {
    // 获取根目录内容
    val rootContents = remember(SyncManager.folderList.size, SyncManager.allFiles.size) {
        // 传入空字符串表示根目录
        SyncManager.getFolderContents("")
    }
    val columns by SyncManager.gridColumns

    if (rootContents.isEmpty()) {
        NoImagesState(navController)
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(2.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(rootContents.size) { index ->
                val file = rootContents[index]
                if (file.isDirectory) {
                    FolderItem(file) {
                        // 进入子文件夹，传递绝对路径
                        navController.navigate("subfolder?path=${file.absolutePath}&name=${file.name}")
                    }
                } else {
                    // 根目录下的图片
                    ThumbnailImage(
                        file = file,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .padding(1.dp)
                            .clickable {
                                // 模式为 folder，path 为空(根目录)
                                navController.navigate("viewer/folder/$index?path=")
                            }
                    )
                }
            }
        }
    }
}

@Composable
fun FolderItem(folder: File, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(1.dp)
            .clip(RectangleShape)
            .background(Color(0xFF252525))
            .clickable(onClick = onClick)
            .aspectRatio(1f), // 正方形
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = Color(0xFFFFB300) // 文件夹黄色
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = folder.name,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

// Tab 1: 全部图片视图
@Composable
fun AllImagesContent(navController: androidx.navigation.NavController) {
    val images = SyncManager.allFiles
    val columns by SyncManager.gridColumns

    if (images.isEmpty()) {
        NoImagesState(navController)
    } else {
        ImageGrid(images, columns) { index ->
            navController.navigate("viewer/all/$index?path=")
        }
    }
}

// 通用图片网格
@Composable
fun ImageGrid(images: List<File>, columns: Int, onClick: (Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(2.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(images.size) { index ->
            val file = images[index]
            ThumbnailImage(
                file = file,
                modifier = Modifier
                    .aspectRatio(1f)
                    .padding(1.dp)
                    .clickable { onClick(index) }
            )
        }
    }
}

// 子文件夹页面
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubFolderScreen(navController: androidx.navigation.NavController, path: String, folderName: String) {
    val contents = remember(path) { SyncManager.getFolderContents(path) }
    val columns by SyncManager.gridColumns

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(folderName) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (contents.isEmpty()) {
                EmptyState("文件夹为空")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(contents.size) { index ->
                        val file = contents[index]

                        if (file.isDirectory) {
                            // 递归进入下一级
                            FolderItem(file) {
                                navController.navigate("subfolder?path=${file.absolutePath}&name=${file.name}")
                            }
                        } else {
                            // 显示图片
                            ThumbnailImage(
                                file = file,
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .padding(1.dp)
                                    .clickable {
                                        val imagesOnly = contents.filter { !it.isDirectory }
                                        val imgIndex = imagesOnly.indexOf(file)

                                        // [修复] 对路径进行编码，并使用 ?path= 格式
                                        val encodedPath = encodeUrl(path)
                                        navController.navigate("viewer/folder/$imgIndex?path=$encodedPath")
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

// 大图查看器 (完全还原功能)
// [修复] 大图查看器
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    navController: androidx.navigation.NavController,
    mode: String,
    initialIndex: Int,
    path: String
) {
    // 数据源获取
    val imageList = remember(mode, path) {
        if (mode == "folder") {
            SyncManager.getImagesOnlyInFolder(path)
        } else {
            SyncManager.allFiles
        }
    }

    if (imageList.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("没有图片", color = Color.White)
        }
        return
    }

    val safeIndex = initialIndex.coerceIn(0, (imageList.size - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(initialPage = safeIndex, pageCount = { imageList.size })
    var showUi by remember { mutableStateOf(true) }

    // 依然保留状态，但不再用于锁定 Pager，可用于其他 UI 逻辑（如显示缩放比例提示等）
    var isZoomed by remember { mutableStateOf(false) }

    // 跟随 showUi 同步显示/隐藏系统状态栏和导航栏（沉浸式大图模式）
    val view = LocalView.current
    val window = remember(view) { (view.context as? Activity)?.window }
    LaunchedEffect(showUi, window) {
        window?.let {
            val controller = WindowCompat.getInsetsController(it, view)
            // 隐藏后用户可从屏幕边缘滑动临时唤出系统栏
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (showUi) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    // 离开大图页面时恢复系统栏，避免影响其他界面
    DisposableEffect(window) {
        onDispose {
            window?.let {
                WindowCompat.getInsetsController(it, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Scaffold(
        topBar = {
            // 简单的显示/隐藏逻辑
            if (showUi) {
                TopAppBar(
                    title = { Text("${pagerState.currentPage + 1} / ${imageList.size}") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.4f),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    )
                )
            }
        },
        containerColor = Color.Black
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                pageSpacing = 16.dp
                // 不需要 userScrollEnabled，Telephoto 库会自动处理嵌套滑动
            ) { page ->
                // 使用 Telephoto 提供的组件
                ZoomableAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageList[page])
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    // 单击监听：切换 UI 显示
                    onClick = { showUi = !showUi },
                    // 确保未选中的页面重置状态
                    state = rememberZoomableImageState(
                        rememberZoomableState(
                            zoomSpec = ZoomSpec(
                                // 这里设置最大放大倍数，例如 10f 代表 10 倍
                                maxZoomFactor = 10f
                            )
                        )
                    )
                )
            }
        }
    }
}

@Composable
fun EmptyState(msg: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(msg, color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
    }
}

// 统一的缩略图组件：加载中显示占位色，失败显示错误占位色，避免黑块闪烁
@Composable
fun ThumbnailImage(file: File, modifier: Modifier = Modifier) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(file)
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        placeholder = ColorPainter(Color(0xFF1E1E1E)),
        error = ColorPainter(Color(0xFF2A1E1E)),
        modifier = modifier
    )
}

// 空状态引导：未连接时提示并提供前往设置的入口
@Composable
fun NoImagesState(navController: androidx.navigation.NavController) {
    val isConnected by SyncManager.isConnected
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (isConnected) Icons.Default.HourglassEmpty else Icons.Default.CloudOff,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (isConnected) "已连接，等待图片同步…" else "还没有图片",
            color = Color.Gray,
            style = MaterialTheme.typography.bodyLarge
        )
        if (!isConnected) {
            Spacer(Modifier.height(8.dp))
            Text(
                "连接电脑端后，图片会自动同步到这里",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("前往设置连接")
            }
        }
    }
}

@Composable
fun ZoomableImage(
    file: File,
    isVisible: Boolean,
    onTap: () -> Unit,
    onZoomChange: (Boolean) -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    // 页面不可见时重置状态
    LaunchedEffect(isVisible) {
        if (!isVisible) {
            scale = 1f
            offset = androidx.compose.ui.geometry.Offset.Zero
            onZoomChange(false)
        }
    }

    val state = rememberTransformableState { zoomChange, _, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        onZoomChange(scale > 1f)

        if (scale == 1f) {
            offset = androidx.compose.ui.geometry.Offset.Zero
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 1. 点击手势 (需放在最外层或 transformable 之前)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = androidx.compose.ui.geometry.Offset.Zero
                            onZoomChange(false)
                        } else {
                            scale = 2.5f
                            onZoomChange(true)
                        }
                    }
                )
            }
            // 2. 双指缩放 (始终生效)
            .transformable(state = state)
            // 3. 单指拖拽 (仅在放大时生效)
            // [关键] Key 设为 (scale > 1f)。当 scale=1 时，此 block 不会运行，
            // 因此不会通过 detectDragGestures 消费事件，Pager 就能捕获到滑动事件。
            .pointerInput(scale > 1f) {
                if (scale > 1f) {
                    detectDragGestures { change, dragAmount ->
                        // 消费事件，防止事件传递给父组件 (Pager)
                        change.consume()
                        offset += dragAmount
                    }
                }
            }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(file)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: androidx.navigation.NavController,
    hasNotificationPermission: () -> Boolean,
    requestNotificationPermission: () -> Unit
) {
    var ip by remember { SyncManager.serverIp }
    var port by remember { SyncManager.serverPort }
    var aesKey by remember { SyncManager.aesKey }
    var autoConnect by remember { SyncManager.autoConnect }
    var autoReconnect by remember { SyncManager.autoReconnect }
    var backgroundMode by remember { SyncManager.backgroundMode }
    var columns by remember { SyncManager.gridColumns }
    var currentPath by remember { SyncManager.currentStoragePath }

    // 弹窗状态管理
    var showPathDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var ipDropdownExpanded by remember { mutableStateOf(false) }
    val discoveredServers = SyncManager.discoveredServers
    val isDiscoveringServers by SyncManager.isDiscoveringServers

    // 临时路径输入变量
    var tempPath by remember { mutableStateOf("") }
    // AES 密钥显隐切换
    var aesKeyVisible by remember { mutableStateOf(false) }

    // 记住滚动状态 (保留之前的修复)
    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = {
                        // 虽然实时保存了，这里保留也没坏处
                        SyncManager.saveSettings()
                        navController.popBackStack()
                    }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {

            // --- 网络设置 ---
            Text("网络", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            ExposedDropdownMenuBox(
                expanded = ipDropdownExpanded,
                onExpandedChange = { expanded ->
                    ipDropdownExpanded = expanded
                    if (expanded) {
                        SyncManager.discoverSyncServers()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = {
                        ip = it
                        SyncManager.serverIp.value = it
                        SyncManager.saveSettings() // [修改] 立即保存
                    },
                    label = { Text("电脑 IP 地址") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ipDropdownExpanded) },
                    singleLine = true,
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = ipDropdownExpanded,
                    onDismissRequest = { ipDropdownExpanded = false }
                ) {
                    if (isDiscoveringServers) {
                        DropdownMenuItem(
                            text = { Text("正在搜索...") },
                            onClick = {},
                            enabled = false
                        )
                    } else if (discoveredServers.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("未发现服务") },
                            onClick = {},
                            enabled = false
                        )
                    } else {
                        discoveredServers.forEach { server ->
                            DropdownMenuItem(
                                text = { Text("${server.name} (${server.ip}:${server.port})") },
                                onClick = {
                                    ip = server.ip
                                    port = server.port.toString()
                                    SyncManager.serverIp.value = server.ip
                                    SyncManager.serverPort.value = server.port.toString()
                                    SyncManager.saveSettings()
                                    ipDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = port,
                onValueChange = {
                    if (it.all { char -> char.isDigit() }) {
                        port = it
                        SyncManager.serverPort.value = it
                        SyncManager.saveSettings() // [修改] 立即保存
                    }
                },
                label = { Text("端口 (默认 12345)") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            OutlinedTextField(
                value = aesKey,
                onValueChange = {
                    aesKey = it
                    SyncManager.aesKey.value = it
                    SyncManager.saveSettings() // [修改] 立即保存
                },
                label = { Text("AES 密钥 (留空则不加密)") },
                singleLine = true,
                visualTransformation = if (aesKeyVisible)
                    androidx.compose.ui.text.input.VisualTransformation.None
                else
                    androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { aesKeyVisible = !aesKeyVisible }) {
                        Icon(
                            imageVector = if (aesKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (aesKeyVisible) "隐藏密钥" else "显示密钥"
                        )
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            SwitchItem("启动自动连接", autoConnect) {
                autoConnect = it
                SyncManager.autoConnect.value = it
                SyncManager.saveSettings() // [修改] 立即保存
            }
            SwitchItem("断线自动重连", autoReconnect) {
                autoReconnect = it
                SyncManager.autoReconnect.value = it
                SyncManager.saveSettings() // [修改] 立即保存
            }

            Spacer(Modifier.height(16.dp))

            // --- 后台设置 ---
            Text("后台运行", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            SwitchItem("前台服务通知 & 防休眠", backgroundMode) {
                if (it && !hasNotificationPermission()) {
                    requestNotificationPermission()
                } else {
                    SyncManager.setBackgroundMode(it)
                    // setBackgroundMode 内部已经调用了 saveSettings，所以这里不需要再调
                }
            }
            Text(
                "开启后将在通知栏显示常驻通知，并防止系统在后台断开网络连接。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            Spacer(Modifier.height(16.dp))

            // --- 存储设置 ---
            Text("存储", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("当前路径:", style = MaterialTheme.typography.bodySmall)
            Text(currentPath, style = MaterialTheme.typography.bodySmall, color = Color.Gray)

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    tempPath = currentPath
                    showPathDialog = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("更改存储位置")
            }

            Button(
                onClick = { showResetDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("恢复默认路径")
            }

            Spacer(Modifier.height(16.dp))

            // --- 显示设置 ---
            Text("显示", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("网格列数: $columns")
            Slider(
                value = columns.toFloat(),
                onValueChange = {
                    columns = it.toInt()
                    SyncManager.gridColumns.value = it.toInt()
                },
                // [修改] 增加这个回调，在手指松开时保存，避免拖动时疯狂写入硬盘
                onValueChangeFinished = {
                    SyncManager.saveSettings()
                },
                valueRange = 1f..10f,
                steps = 9
            )

            Spacer(Modifier.height(32.dp))

            // --- 危险区域 ---
            Button(
                onClick = { showClearCacheDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清除所有缓存")
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // ... (后续的弹窗代码完全保持不变) ...
    if (showPathDialog) {
        AlertDialog(
            onDismissRequest = { showPathDialog = false },
            title = { Text("设置新路径") },
            text = {
                Column {
                    Text("请输入绝对路径 (请确保有读写权限):")
                    OutlinedTextField(
                        value = tempPath,
                        onValueChange = { tempPath = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val success = SyncManager.changeStoragePath(tempPath)
                    if (success) {
                        showPathDialog = false
                    }
                }) { Text("确定移动") }
            },
            dismissButton = {
                TextButton(onClick = { showPathDialog = false }) { Text("取消") }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("恢复默认路径") },
            text = { Text("确定要将存储路径恢复为默认吗？\n现有文件将被移动到默认目录。") },
            confirmButton = {
                TextButton(onClick = {
                    SyncManager.resetStoragePath()
                    showResetDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
            }
        )
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("确认清除缓存") },
            text = { Text("确定要删除所有缓存的图片吗？\n此操作不可恢复！", color = MaterialTheme.colorScheme.error) },
            confirmButton = {
                TextButton(
                    onClick = {
                        SyncManager.clearCache()
                        showClearCacheDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("确认清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
fun SwitchItem(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
