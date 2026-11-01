package com.novaplayer

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Rational
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

private val Ink = Color(0xFF10131A)
private val SurfaceDark = Color(0xFF1A1F29)
private val Accent = Color(0xFFB8C7FF)

data class VideoEntry(
    val uri: Uri,
    val name: String,
    val folder: String = "本地视频",
    val duration: Long = 0L
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContent {
            NovaApp(this)
        }
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()

            enterPictureInPictureMode(params)
        }
    }
}

fun scanDeviceVideos(context: Context): List<VideoEntry> {
    val videoList = mutableListOf<VideoEntry>()
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Video.Media.DURATION
    )

    val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

    context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val name = cursor.getString(nameCol) ?: "未知视频"
            val folder = cursor.getString(bucketCol) ?: "相册视频"
            val duration = cursor.getLong(durationCol)
            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

            videoList.add(VideoEntry(uri = uri, name = name, folder = folder, duration = duration))
        }
    }
    return videoList
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovaApp(activity: MainActivity) {

    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("nova_player", 0)
    }

    val videos = remember {
        mutableStateListOf<VideoEntry>()
    }

    val history = remember {
        mutableStateListOf<VideoEntry>()
    }

    var selectedTab by remember {
        mutableIntStateOf(0)
    }

    var current by remember {
        mutableStateOf<VideoEntry?>(null)
    }

    var showPlayer by remember {
        mutableStateOf(false)
    }

    var subtitleUri by remember {
        mutableStateOf<Uri?>(null)
    }

    var search by remember {
        mutableStateOf("")
    }

    var showNetworkDialog by remember {
        mutableStateOf(false)
    }
    var networkUrlInput by remember {
        mutableStateOf("")
    }

    fun loadHistory() {
        history.clear()
        val arr = runCatching {
            JSONArray(prefs.getString("history", "[]"))
        }.getOrDefault(JSONArray())

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = runCatching {
                Uri.parse(o.optString("uri"))
            }.getOrNull() ?: continue

            history.add(
                VideoEntry(
                    u,
                    o.optString("name", "视频"),
                    o.optString("folder", "历史记录")
                )
            )
        }
    }

    fun saveHistory(entry: VideoEntry) {
        val existing = history.indexOfFirst { it.uri == entry.uri }
        if (existing >= 0) {
            history.removeAt(existing)
        }
        history.add(0, entry)

        while (history.size > 100) {
            history.removeAt(history.lastIndex)
        }

        val arr = JSONArray()
        history.forEach {
            arr.put(
                JSONObject()
                    .put("uri", it.uri.toString())
                    .put("name", it.name)
                    .put("folder", it.folder)
            )
        }

        prefs.edit().putString("history", arr.toString()).apply()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val scanned = scanDeviceVideos(context)
            videos.clear()
            videos.addAll(scanned)
        }
    }

    LaunchedEffect(Unit) {
        loadHistory()
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            val scanned = scanDeviceVideos(context)
            videos.clear()
            videos.addAll(scanned)
        } else {
            permissionLauncher.launch(permission)
        }
    }

    val subtitlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            subtitleUri = uri
            current?.let {
                showPlayer = true
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF18213A),
            background = Ink,
            surface = SurfaceDark,
            onSurface = Color(0xFFE8EAF2),
            secondary = Color(0xFFB8C7FF)
        )
    ) {

        if (showPlayer && current != null) {

            val currentList = when (selectedTab) {
                1 -> videos.filter { it.folder == current?.folder }
                2 -> history
                else -> videos
            }.ifEmpty { listOf(current!!) }

            val initialIndex = currentList.indexOfFirst { it.uri == current!!.uri }.coerceAtLeast(0)

            PlayerScreen(
                activity = activity,
                playlist = currentList,
                initialIndex = initialIndex,
                subtitleUri = subtitleUri,
                onBack = {
                    showPlayer = false
                },
                onSubtitle = {
                    subtitlePicker.launch(
                        arrayOf(
                            "text/*",
                            "application/x-subrip",
                            "application/octet-stream"
                        )
                    )
                },
                onProgress = { index, position ->
                    val entry = currentList.getOrNull(index)
                    if (entry != null) {
                        prefs.edit()
                            .putLong("pos_${entry.uri}", position)
                            .apply()
                    }
                },
                onVideoChanged = { newEntry ->
                    current = newEntry
                    saveHistory(newEntry)
                }
            )

        } else {

            Scaffold(
                containerColor = Ink,

                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("Nova Player", fontWeight = FontWeight.Bold)
                                Text("本地检索与网络串流", fontSize = 11.sp, color = Color.LightGray)
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { showNetworkDialog = true }
                            ) {
                                Icon(Icons.Default.Link, "输入网络链接")
                            }
                            IconButton(
                                onClick = {
                                    val scanned = scanDeviceVideos(context)
                                    videos.clear()
                                    videos.addAll(scanned)
                                }
                            ) {
                                Icon(Icons.Default.Refresh, "刷新本地视频")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Ink
                        )
                    )
                },

                bottomBar = {
                    NavigationBar(
                        containerColor = SurfaceDark
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.VideoLibrary, null) },
                            label = { Text("视频") }
                        )

                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.Folder, null) },
                            label = { Text("文件夹") }
                        )

                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.Default.History, null) },
                            label = { Text("历史") }
                        )
                    }
                }

            ) { padding ->

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                ) {

                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 12.dp),
                        placeholder = {
                            Text("搜索本机视频名称")
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, null)
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp)
                    )

                    when (selectedTab) {

                        0 -> {
                            Text(
                                "共发现 ${videos.size} 个本地视频",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )

                            VideoList(
                                videos.filter {
                                    it.name.contains(search, true)
                                }
                            ) { entry ->
                                current = entry
                                saveHistory(entry)
                                showPlayer = true
                            }
                        }

                        1 -> {
                            val groups = videos.groupBy { it.folder }

                            if (groups.isEmpty()) {
                                EmptyState(
                                    "未检测到本地视频",
                                    "请检查存储权限或点击右上角刷新按钮重新扫描"
                                )
                            } else {
                                LazyColumn {
                                    groups.forEach { (folder, entries) ->
                                        item {
                                            Text(
                                                "$folder  ·  ${entries.size} 个视频",
                                                modifier = Modifier.padding(vertical = 12.dp),
                                                color = Accent,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        items(entries) { entry ->
                                            VideoRow(entry) {
                                                current = entry
                                                saveHistory(entry)
                                                showPlayer = true
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        else -> {
                            VideoList(
                                history.filter {
                                    it.name.contains(search, true)
                                }
                            ) { entry ->
                                current = entry
                                showPlayer = true
                            }
                        }
                    }
                }
            }

            if (showNetworkDialog) {
                AlertDialog(
                    onDismissRequest = { showNetworkDialog = false },
                    title = { Text("播放网络视频链接") },
                    text = {
                        OutlinedTextField(
                            value = networkUrlInput,
                            onValueChange = { networkUrlInput = it },
                            placeholder = { Text("请输入 http:// 或 https:// 视频直链") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (networkUrlInput.isNotBlank()) {
                                    runCatching {
                                        val uri = Uri.parse(networkUrlInput.trim())
                                        val name = uri.lastPathSegment ?: "网络视频"
                                        val entry = VideoEntry(uri = uri, name = name, folder = "网络链接")
                                        current = entry
                                        saveHistory(entry)
                                        showPlayer = true
                                        showNetworkDialog = false
                                        networkUrlInput = ""
                                    }
                                }
                            }
                        ) {
                            Text("播放")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNetworkDialog = false }) {
                            Text("取消")
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun EmptyState(title: String, detail: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.VideoLibrary,
            null,
            tint = Accent,
            modifier = Modifier.size(52.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(detail, color = Color.LightGray, fontSize = 13.sp)
    }
}

@Composable
fun VideoList(
    entries: List<VideoEntry>,
    onClick: (VideoEntry) -> Unit
) {
    if (entries.isEmpty()) {
        EmptyState(
            "没有找到相关视频",
            "换个关键词搜搜看，或通过右上角链接按钮播放网络视频"
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            items(entries) {
                VideoRow(it, onClick)
            }
        }
    }
}

@Composable
fun VideoRow(
    entry: VideoEntry,
    onClick: (VideoEntry) -> Unit
) {
    val context = LocalContext.current
    var thumbnail by remember(entry.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(entry.uri) {
        withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, entry.uri)
                val bitmap = retriever.getFrameAtTime(1000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                thumbnail = bitmap
            }
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick(entry) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceDark),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.PlayArrow,
                    null,
                    tint = Accent,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        Spacer(Modifier.width(13.dp))

        Column(Modifier.weight(1f)) {
            Text(entry.name, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(entry.folder, color = Color.LightGray, fontSize = 12.sp, maxLines = 1)
        }

        Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    activity: MainActivity,
    playlist: List<VideoEntry>,
    initialIndex: Int,
    subtitleUri: Uri?,
    onBack: () -> Unit,
    onSubtitle: () -> Unit,
    onProgress: (Int, Long) -> Unit,
    onVideoChanged: (VideoEntry) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nova_player", 0) }

    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    val currentEntry = playlist.getOrNull(currentIndex) ?: playlist.first()

    val player = remember(playlist) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItems = playlist.map { entry ->
                val builder = MediaItem.Builder().setUri(entry.uri)
                if (entry.uri == currentEntry.uri && subtitleUri != null) {
                    val mime = if (subtitleUri.toString().lowercase().endsWith(".vtt")) "text/vtt" else "application/x-subrip"
                    builder.setSubtitleConfigurations(
                        listOf(
                            MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                                .setMimeType(mime)
                                .setLanguage("und")
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build()
                        )
                    )
                }
                builder.build()
            }
            setMediaItems(mediaItems)

            val savedPos = prefs.getLong("pos_${currentEntry.uri}", 0L)
            seekTo(initialIndex, savedPos)
            prepare()
            playWhenReady = true

            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val newIndex = currentMediaItemIndex
                    if (newIndex >= 0 && newIndex < playlist.size) {
                        currentIndex = newIndex
                        onVideoChanged(playlist[newIndex])
                    }
                }
            })
        }
    }

    var speed by remember { mutableFloatStateOf(1f) }
    var message by remember { mutableStateOf("") }
    var brightness by remember { mutableFloatStateOf(0.65f) }
    var gestureSide by remember { mutableStateOf("") }
    var isFullScreen by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }

    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    DisposableEffect(player) {
        onDispose {
            onProgress(player.currentMediaItemIndex, player.currentPosition)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(1000)
            if (player.isPlaying) {
                onProgress(player.currentMediaItemIndex, player.currentPosition)
            }
        }
    }

    SideEffect {
        val lp = activity.window.attributes
        lp.screenBrightness = brightness.coerceIn(0.05f, 1f)
        activity.window.attributes = lp
    }

    fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        val windowInsetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (isFullScreen) {
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = {
                it.player = player
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(player) {
                    detectTapGestures(
                        onTap = {
                            showControls = !showControls
                        },
                        onDoubleTap = { offset ->
                            if (offset.x < size.width / 2) {
                                player.seekTo(max(0L, player.currentPosition - 10_000L))
                                message = "快退 10 秒"
                            } else {
                                player.seekTo(player.currentPosition + 10_000L)
                                message = "快进 10 秒"
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            gestureSide = if (offset.x < size.width / 2f) "brightness" else "volume"
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            if (gestureSide == "brightness") {
                                brightness = (brightness - dragAmount / size.height.toFloat()).coerceIn(0.05f, 1f)
                                message = "亮度 ${(brightness * 100).toInt()}%"
                            } else {
                                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val step = if (dragAmount < 0) 1 else -1
                                audioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    (currentVolume + step).coerceIn(0, maxVolume),
                                    0
                                )
                                val now = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                message = "音量 ${now * 100 / maxVolume.coerceAtLeast(1)}%"
                            }
                        },
                        onDragEnd = {
                            gestureSide = ""
                        }
                    )
                }
        )

        if (showControls) {
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Color(0x88000000))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                    }

                    Text(
                        currentEntry.name,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(onClick = { toggleFullScreen() }) {
                        Icon(
                            if (isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            "全屏切换",
                            tint = Color.White
                        )
                    }

                    IconButton(onClick = onSubtitle) {
                        Icon(Icons.Default.Subtitles, "字幕", tint = Color.White)
                    }

                    IconButton(
                        onClick = {
                            activity.enterPip()
                        }
                    ) {
                        Icon(Icons.Default.PictureInPictureAlt, "画中画", tint = Color.White)
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("倍速", color = Color.White, fontSize = 12.sp)

                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                        FilterChip(
                            selected = speed == value,
                            onClick = {
                                speed = value
                                player.setPlaybackSpeed(value)
                            },
                            label = { Text("${value}x", fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        if (message.isNotBlank()) {
            Text(
                message,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000), CircleShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            LaunchedEffect(message) {
                delay(800)
                message = ""
            }
        }
    }
}

