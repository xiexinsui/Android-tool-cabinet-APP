package com.decibelmeter.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 预览页上的一条待看内容，由浏览页在用户点击的那一刻打包传过来。 */
data class PreviewItem(val path: String, val name: String, val size: Long, val modified: Long) {
    val kind: SmbKind get() = SmbKind.of(name)

    fun uri(link: SmbLink, mode: String) = SmbUris.forFile(link, path, size, modified, mode)
}

/** 按类型派发到具体页。[active] 是「用户正停在这一页」——只有它决定要不要真的起播放器。 */
@Composable
fun SmbPreviewPage(item: PreviewItem, link: SmbLink?, active: Boolean,
                   onExternalPlay: () -> Unit) {
    when (item.kind) {
        SmbKind.IMAGE -> ImagePage(item, link)
        SmbKind.VIDEO, SmbKind.AUDIO -> MediaPage(item, link, active, onExternalPlay)
        SmbKind.PDF -> PdfPage(item, link, active)
        SmbKind.TEXT -> TextPage(item, link)
        else -> NotePage(R.string.preview_unsupported, dark = false)
    }
}

// ------------------------------------------------------------------ 图片

/**
 * 全屏看图：双指缩放 + 拖动 + 双击切 1×/2.5×。
 *
 * 变换只作用在 `graphicsLayer` 上，不改变布局，缩放过程中已解码的位图不会被重新测量；
 * `ContentScale.Fit` 保证竖屏看横图时左右留边而不是裁掉内容。
 */
@Composable
private fun ImagePage(item: PreviewItem, link: SmbLink?) {
    val context = LocalContext.current
    if (link == null) {
        NotePage(R.string.preview_waiting, dark = true)
        return
    }
    var scale by remember(item.path) { mutableFloatStateOf(1f) }
    var shift by remember(item.path) { mutableStateOf(Offset.Zero) }
    Box(
            modifier = Modifier.fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(item.path) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, 8f)
                            scale = next
                            shift = if (next <= 1f) Offset.Zero
                            else Offset(shift.x + pan.x, shift.y + pan.y)
                        }
                    }
                    .pointerInput(item.path) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                shift = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        })
                    },
            contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
                model = item.uri(link, SmbUris.MODE_FULL),
                contentDescription = item.name,
                imageLoader = ToolboxImages.loader(context),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = shift.x
                    translationY = shift.y
                },
        )
    }
}

// ------------------------------------------------------------------ 视频 / 音频

/**
 * 边取边播。
 *
 * 三件事必须放一起才成立：起一个 [SmbHttpProxy]、建一个 [ExoPlayer]、把 `PlayerView` 经
 * AndroidView 挂进 Compose。代理与播放器的创建都碰到 socket，全部丢到 IO 上；
 * [active] 关掉时立刻整体释放——左右翻页时相邻页也会被组合出来，不做这个判断会出现
 * 「三页同时在播」。
 */
@OptIn(UnstableApi::class)
@Composable
private fun MediaPage(item: PreviewItem, link: SmbLink?, active: Boolean,
                      onExternalPlay: () -> Unit) {
    val context = LocalContext.current
    var player by remember(item.path) { mutableStateOf<ExoPlayer?>(null) }
    var proxy by remember(item.path) { mutableStateOf<SmbHttpProxy?>(null) }
    var failure by remember(item.path) { mutableStateOf<String?>(null) }
    /** ExoPlayer 解不了时退到系统播放器（设备厂商解码器），地址还是同一个回环流 */
    var usePlatform by remember(item.path) { mutableStateOf(false) }
    // 播放器报的是错误码名，留到真正渲染的那一刻再用 stringResource 拼句子：
    // 监听器回调不在组合作用域里，那里拿不到也留不住资源
    var mediaErrorCode by remember(item.path) { mutableStateOf<String?>(null) }
    var buffering by remember(item.path) { mutableStateOf(true) }

    LaunchedEffect(active, link, item.path) {
        val previous = player
        val previousProxy = proxy
        player = null
        proxy = null
        // ExoPlayer 绑的是「创建它的那个 Looper」，跨线程 release 一样会抛 wrong-thread，
        // 所以释放也必须在主线程（这个 effect 本身就跑在主线程）。
        previous?.release()
        previousProxy?.stop()
        if (!active || link == null) {
            return@LaunchedEffect
        }
        failure = null
        mediaErrorCode = null
        buffering = true
        try {
            // 只有「起代理」这一步要网络线程（bind + 之后要读 SMB）；播放器本身必须建在主线程
            val started = withContext(Dispatchers.IO) {
                SmbHttpProxy.start(link, item.path, item.name)
            }
            proxy = started
            // 外挂字幕要在 prepare 之前挂上去：Media3 只认 MediaItem 里声明的字幕，不会自己去
            // 同目录猜一个同名 .srt。字幕都是几十 KB，先整份取到本地再起播，代价可以忽略
            val subtitles = withContext(Dispatchers.IO) {
                externalSubtitles(context, link, item)
            }
            // 局域网流的调参：远端很快，缓冲不必给厚；真正影响手感的是「拖一下要等多久」，
            // 所以短缓冲 + back buffer + CLOSEST_SYNC 组合，比默认值明显跟手
            val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(10_000, 60_000, 1_000, 2_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            val built = ExoPlayer.Builder(context.applicationContext)
                    .setLoadControl(loadControl)
                    .setSeekBackIncrementMs(10_000)
                    .setSeekForwardIncrementMs(10_000)
                    .build()
            // 精确 seek 会为了对齐关键帧把中间数据都读一遍，走网络时表现为「拖一下卡几秒」
            built.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            built.setAudioAttributes(AudioAttributes.DEFAULT, true)
            built.setHandleAudioBecomingNoisy(true)
            built.setMediaItem(MediaItem.Builder()
                    .setUri(started.url)
                    .setSubtitleConfigurations(subtitles)
                    .build())
            val resumeKey = PlaybackPositions.key(link.host, item.path)
            built.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    buffering = state == Player.STATE_BUFFERING
                    if (state == Player.STATE_ENDED) {
                        // 看完了就把记录清掉，下次从头开始
                        PlaybackPositions.forget(context, resumeKey)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) {
                        PlaybackPositions.save(context, resumeKey,
                                built.currentPosition, built.duration)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // 播不了的真正原因通常是容器解不了（wmv、avi 这类）或者流被截断，
                    // 把 Media3 的错误码名带出来，用户能据此决定是转码还是换应用打开
                    mediaErrorCode = error.errorCodeName
                }
            })
            built.prepare()
            val saved = PlaybackPositions.get(context, resumeKey)
            if (saved > 0) {
                // prepare 之后 seek：播放器会直接从这里开始缓冲，不用先缓冲片头
                built.seekTo(saved)
            }
            // 用户上次选的倍速要跟着翻页走
            built.setPlaybackSpeed(PlaybackBridge.speed)
            built.playWhenReady = true
            player = built
            PlaybackBridge.player = built
        } catch (e: Exception) {
            failure = SmbErrors.describe(context, e)
        }
    }

    DisposableEffect(item.path) {
        onDispose {
            val alive = player
            val aliveProxy = proxy
            // 退出这一页也记一次位置：用户多半是「先退出去，回头再接着看」
            if (alive != null) {
                val target = link
                if (target != null) {
                    PlaybackPositions.save(context,
                            PlaybackPositions.key(target.host, item.path),
                            alive.currentPosition, alive.duration)
                }
            }
            player = null
            proxy = null
            if (PlaybackBridge.player === alive) {
                PlaybackBridge.player = null
            }
            // onDispose 就跑在主线程，直接释放：ExoPlayer 只认创建它的那个 Looper，
            // 丢到别的线程 release 会抛 wrong-thread（真机上踩过）
            runCatching { alive?.release() }
            aliveProxy?.stop()
        }
    }

    val current = player
    val proxyUrl = proxy?.url
    val mediaError = mediaErrorCode?.let { stringResource(R.string.preview_media_failed, it) }
    val message = mediaError ?: failure
    Box(modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center) {
        when {
            usePlatform && proxyUrl != null -> PlatformVideo(proxyUrl)
            current != null -> {
                PlayerSurface(current)
                if (buffering) {
                    CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center))
                }
            }
            message == null -> Text(stringResource(R.string.preview_loading),
                    color = Color.White.copy(alpha = .8f),
                    style = MaterialTheme.typography.bodyMedium)
        }
        if (message != null && !usePlatform) {
            Column(modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message, color = Color.White,
                        style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(10.dp))
                // 两条出路：换成设备自带的解码器再试一次，或者把流交给 VLC/MX 这类播放器
                Row {
                    if (proxyUrl != null) {
                        TextButton(onClick = { usePlatform = true }) {
                            Text(stringResource(R.string.smb_action_platform_player),
                                    color = Color.White)
                        }
                    }
                    TextButton(onClick = onExternalPlay) {
                        Text(stringResource(R.string.smb_action_open_other_player),
                                color = Color.White)
                    }
                }
                Text(stringResource(R.string.preview_try_external),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 字幕比这还大就不是字幕了，不取：宁可没有字幕也不要把一个坏文件整份拽下来。 */
private const val SUBTITLE_CAP = 8L * 1024 * 1024

private fun subtitleMime(name: String): String? =
        when (name.substringAfterLast('.').lowercase(java.util.Locale.US)) {
            "srt" -> "application/x-subrip"
            "ass", "ssa" -> "text/x-ssa"
            "vtt" -> "text/vtt"
            else -> null
        }

/**
 * 找同目录、同主名的外挂字幕（srt/ass/ssa/vtt），取到本地后交给 Media3。
 *
 * 为什么要自己找：内嵌字幕由解封装器直接给出，而同目录的 `.srt` 对播放器来说是「另一个文件」，
 * Media3 不会替用户去猜——必须显式挂成 [MediaItem.SubtitleConfiguration]。
 *
 * 走 [SmbSourceCache] 而不是回环代理：字幕只有几十 KB，落盘后解析和 seek 全在本地，也少占
 * 一个监听端口。必须在工作线程调用。
 */
@OptIn(UnstableApi::class)
private fun externalSubtitles(context: Context, link: SmbLink,
                              item: PreviewItem): List<MediaItem.SubtitleConfiguration> {
    val base = item.name.substringBeforeLast('.').lowercase(java.util.Locale.US)
    if (base.isEmpty()) {
        return emptyList()
    }
    val parent = SmbClient.parentOf(item.path)
    // includeHidden=true：Windows 上字幕文件偶尔带隐藏属性，不该因此整条丢掉
    val siblings = try {
        link.client.list(parent, true).items
    } catch (e: Exception) {
        return emptyList()
    }
    val result = ArrayList<MediaItem.SubtitleConfiguration>()
    for (child in siblings) {
        if (child.directory || child.size > SUBTITLE_CAP) {
            continue
        }
        val mime = subtitleMime(child.name) ?: continue
        if (child.name.substringBeforeLast('.').lowercase(java.util.Locale.US) != base) {
            continue
        }
        val path = if (parent.isEmpty()) child.name else "$parent/${child.name}"
        try {
            val key = SmbUris.forFile(link, path, child.size, child.modified, SmbUris.MODE_FULL)
            val file = SmbSourceCache.get(context, key) ?: run {
                val source = link.openRange(path)
                try {
                    SmbSourceCache.put(context, key, source, SUBTITLE_CAP)
                } finally {
                    runCatching { source.close() }
                }
            }
            // "电影.zh.srt" 里的 "zh" 拿来做标签；没有后缀就用一个通用名字
            val tag = child.name.substringBeforeLast('.').substring(base.length)
                    .trim('.', '-', '_', ' ', '[', ']')
            result.add(MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(file))
                    .setMimeType(mime)
                    .setLabel(tag.ifEmpty { context.getString(R.string.preview_subtitle_external) })
                    // 同名的第一条默认打开：既然片源旁边就摆着字幕，多半就是给这部片子配的
                    .setSelectionFlags(if (result.isEmpty()) C.SELECTION_FLAG_DEFAULT else 0)
                    .build())
        } catch (e: Exception) {
            // 单条字幕取不到不影响播放，其余候选继续
        }
    }
    return result
}

/**
 * 系统播放器（`VideoView` → 厂商 `MediaPlayer`）。
 *
 * 意义就在于它用的是**设备自带的解码器**：ExoPlayer 依赖 MediaCodec，格式支持面受厂商裁掉哪些
 * 编码器影响，一些 RMVB/WMV、带特殊音轨的 MKV 在 ExoPlayer 上直接报错，而系统播放器常常能放。
 * 地址还是同一个回环流，不需要重新取数据。
 */
@Composable
private fun PlatformVideo(url: String) {
    AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                android.widget.VideoView(context).apply {
                    val controller = android.widget.MediaController(context)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setVideoURI(android.net.Uri.parse(url))
                    setOnPreparedListener { start() }
                }
            },
    )
}

/** `PlayerView` 是个 View，只能经 AndroidView 嵌进来；快门设成透明，免得闪一下白。 */
@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(player: ExoPlayer) {
    AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    useController = true
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    // 看片时别让屏幕睡过去；这个 PlayerView 只在这一页存在，离开即失效
                    keepScreenOn = true
                }
            },
            update = { view -> view.player = player },
            onRelease = { view -> view.player = null },
    )
}

// ------------------------------------------------------------------ PDF

/**
 * PDF 分页。
 *
 * 先整份落到缓存（[SmbPdf] 的类注释解释了为什么非落盘不可），再按页懒渲染；页面用
 * `aspectRatio` 先撑住高度，否则每渲染完一页才知道多高，列表会一跳一跳。
 */
@Composable
private fun PdfPage(item: PreviewItem, link: SmbLink?, active: Boolean) {
    val context = LocalContext.current
    var document by remember(item.path) { mutableStateOf<SmbPdf?>(null) }
    var failure by remember(item.path) { mutableStateOf<String?>(null) }
    var done by remember(item.path) { mutableIntStateOf(0) }
    var total by remember(item.path) { mutableStateOf(-1L) }
    var loading by remember(item.path) { mutableStateOf(false) }

    // PDF 必须整份取完才能翻第一页，所以只在看这一页时才取：翻页预载相邻页会让一次滑动
    // 悄悄拉下几十 MB，用户完全看不见这件事
    LaunchedEffect(active, link, item.path) {
        val target = link ?: return@LaunchedEffect
        if (!active) {
            return@LaunchedEffect
        }
        if (document != null) {
            return@LaunchedEffect
        }
        failure = null
        loading = true
        done = 0
        total = -1L
        try {
            val opened = withContext(Dispatchers.IO) {
                SmbPdf.fetch(context, target, item.path, item.uri(target, SmbUris.MODE_FULL),
                        SmbSourceCache.FULL_CAP) { read, sum ->
                            done = read.toInt()
                            total = sum
                        }
            }
            document = opened
        } catch (e: Exception) {
            failure = SmbErrors.describe(context, e)
        } finally {
            loading = false
        }
    }

    DisposableEffect(item.path) {
        onDispose {
            val held = document
            document = null
            if (held != null) {
                Thread { runCatching { held.close() } }.apply { isDaemon = true; start() }
            }
        }
    }

    val doc = document
    if (doc != null) {
        LazyColumn(
                modifier = Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentPadding = PaddingValues(8.dp),
        ) {
            itemsIndexed(List(doc.pageCount) { it }) { page, index ->
                PdfPageRow(doc, index)
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        if (failure != null) {
            Text(failure ?: "", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.preview_try_external),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            if (total > 0) {
                LinearProgressIndicator(
                        progress = { (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                Spacer(modifier = Modifier.height(6.dp))
                Text(stringResource(R.string.smb_progress_format,
                        FileUtil.humanSize(done.toLong()), FileUtil.humanSize(total)),
                        style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(if (loading) R.string.preview_pdf_loading
                else R.string.preview_loading),
                        style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PdfPageRow(doc: SmbPdf, index: Int) {
    var pixelWidth by remember(doc, index) { mutableIntStateOf(0) }
    val ratio = doc.ratioAt(index)
    val bitmap by produceState<Bitmap?>(null, doc, index, pixelWidth) {
        value = if (pixelWidth <= 0) null else withContext(Dispatchers.Default) {
            doc.render(index, pixelWidth)
        }
    }
    Box(
            modifier = Modifier.fillMaxWidth()
                    .aspectRatio(ratio)
                    .onSizeChanged { pixelWidth = it.width }
                    .background(Color.White),
            contentAlignment = Alignment.Center,
    ) {
        val frame = bitmap
        if (frame != null) {
            Image(bitmap = frame.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth)
        } else {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
    }
}

// ------------------------------------------------------------------ 文本

/** 一段文本的读取结果。 */
private class TextChunk(val text: String, val charset: String, val readBytes: Long,
                        val total: Long)

/**
 * 文本/日志/代码预览。
 *
 * 只取开头 [TEXT_LIMIT] 字节：共享上一份几 GB 的日志很常见，全读进来既没必要也会把内存吃掉。
 * 截断会明确写在底部，用户能判断是该下载回来看完。
 */
@Composable
private fun TextPage(item: PreviewItem, link: SmbLink?) {
    val loaded by produceState<TextChunk?>(null, link, item.path) {
        val target = link ?: return@produceState
        value = try {
            withContext(Dispatchers.IO) {
                val range = target.openRange(item.path)
                try {
                    val total = if (range.size >= 0) range.size else -1L
                    val want = if (total < 0) TEXT_LIMIT
                    else minOf(total, TEXT_LIMIT)
                    val bytes = range.readBlock(0, want.toInt())
                    val decoded = TextDecode.decode(bytes)
                    TextChunk(decoded.first, decoded.second, bytes.size.toLong(),
                            if (total < 0) bytes.size.toLong() else total)
                } finally {
                    runCatching { range.close() }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    val chunk = loaded
    if (chunk == null) {
        NotePage(R.string.preview_loading, dark = false)
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
                text = stringResource(R.string.preview_text_charset, chunk.charset),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
        )
        SelectionContainer {
            Text(
                    text = chunk.text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
            )
        }
        if (chunk.readBytes < chunk.total) {
            Text(
                    text = stringResource(R.string.preview_text_truncated,
                            FileUtil.humanSize(chunk.readBytes), FileUtil.humanSize(chunk.total)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
            )
        }
    }
}

private const val TEXT_LIMIT = 512L * 1024

/** 一句话说明页（没有内容可渲染时的兜底）。 */
@Composable
private fun NotePage(labelRes: Int, dark: Boolean) {
    Box(
            modifier = Modifier.fillMaxSize()
                    .background(if (dark) Color.Black else MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(labelRes),
                color = if (dark) Color.White.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp))
    }
}
