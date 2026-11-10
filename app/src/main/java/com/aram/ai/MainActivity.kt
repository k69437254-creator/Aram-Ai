package com.aram.ai

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import com.aram.ai.data.ApiKeyRecord
import com.aram.ai.data.AppPrefs
import com.aram.ai.data.IssuedApiKey
import com.aram.ai.data.ModelFiles
import com.aram.ai.engine.ModelRuntime
import com.aram.ai.service.ModelService
import com.aram.ai.ui.ChatScreen
import com.aram.ai.ui.DeveloperScreen
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private val Ink = Color(0xFF0B0914)
private val Panel = Color(0xFF171226)
private val Panel2 = Color(0xFF211934)
private val Violet = Color(0xFF9B7BFF)
private val Lilac = Color(0xFFD5C7FF)
private val Muted = Color(0xFFAAA1BE)
private val Green = Color(0xFF57D6A2)
private val Danger = Color(0xFFFF8295)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 700)
        }
        setContent {
            AramAiTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { AramAiRoot() }
            }
        }
    }
}

@Composable
private fun AramAiTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Violet, onPrimary = Color.White, secondary = Lilac,
        background = Ink, onBackground = Color(0xFFF5F1FF),
        surface = Panel, onSurface = Color(0xFFF5F1FF),
        surfaceVariant = Panel2, onSurfaceVariant = Muted,
        outline = Color(0xFF44365E), error = Danger
    )
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

@Composable
private fun AramAiRoot() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var returnTab by remember { mutableIntStateOf(0) }
    var chatRoomOpen by remember { mutableStateOf(false) }

    val closeChatRoom = {
        chatRoomOpen = false
        selectedTab = returnTab
    }
    BackHandler(enabled = chatRoomOpen, onBack = closeChatRoom)

    Box(Modifier.fillMaxSize().background(Ink)) {
        AnimatedVisibility(
            visible = !chatRoomOpen,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.fillMaxSize()
        ) {
            Scaffold(
                containerColor = Ink,
                bottomBar = {
                    NavigationBar(containerColor = Panel, contentColor = Lilac) {
                        NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Rounded.Home, null) }, label = { Text("الرئيسية") })
                        NavigationBarItem(
                            selected = false,
                            onClick = { returnTab = selectedTab; chatRoomOpen = true },
                            icon = { Icon(Icons.Rounded.Memory, null) },
                            label = { Text("المحادثة") }
                        )
                        NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Rounded.Speed, null) }, label = { Text("المطوّر") })
                        NavigationBarItem(selected = selectedTab == 3, onClick = { selectedTab = 3 }, icon = { Icon(Icons.Rounded.Key, null) }, label = { Text("مفاتيح API") })
                        NavigationBarItem(selected = selectedTab == 4, onClick = { selectedTab = 4 }, icon = { Icon(Icons.Rounded.Settings, null) }, label = { Text("الإعدادات") })
                    }
                }
            ) { padding ->
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "aram-ai-pages"
                ) { tab ->
                    when (tab) {
                        0 -> DashboardScreen(Modifier.padding(padding))
                        2 -> DeveloperScreen(Modifier.padding(padding))
                        3 -> ApiKeysScreen(Modifier.padding(padding))
                        else -> SettingsScreen(Modifier.padding(padding))
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = chatRoomOpen,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(270)) + fadeIn(tween(220)),
            exit = slideOutHorizontally(targetOffsetX = { it / 4 }, animationSpec = tween(230)) + fadeOut(tween(180)),
            modifier = Modifier.fillMaxSize()
        ) {
            ChatScreen(onBack = closeChatRoom, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun DashboardScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
    var engine by remember { mutableStateOf(AppPrefs.engineStatus(context)) }
    var modelExists by remember { mutableStateOf(ModelFiles.isModelReady(context)) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloading by remember { mutableStateOf(false) }
    var downloadMessage by remember { mutableStateOf("") }
    var downloadError by remember { mutableStateOf("") }
    val genuinelyReady = engine.state == "READY" && ModelService.active && ModelRuntime.isLoaded()

    LaunchedEffect(Unit) {
        while (true) {
            AppPrefs.reconcileEngineState(context, ModelService.serviceAlive)
            engine = AppPrefs.engineStatus(context)
            modelExists = ModelFiles.isModelReady(context)
            val id = AppPrefs.downloadId(context)
            if (id != -1L) {
                val cursor = manager.query(DownloadManager.Query().setFilterById(id))
                cursor?.use {
                    if (it.moveToFirst()) {
                        val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val current = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        if (total > 0) downloadProgress = ((current * 100) / total).toInt().coerceIn(0, 100)
                        downloading = status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED
                        if (status == DownloadManager.STATUS_FAILED) {
                            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            downloading = false
                            downloadError = "فشل التنزيل (رمز $reason). أعد المحاولة."
                            AppPrefs.setDownloadId(context, -1L)
                        } else if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            downloading = false
                            val promoted = ModelFiles.finalizeDownload(context)
                            modelExists = ModelFiles.isModelReady(context)
                            if (promoted) downloadMessage = "اكتمل تنزيل النموذج والتحقق من حجمه."
                            else downloadError = if (modelExists) "تعذر استبدال النموذج؛ النسخة السابقة محفوظة."
                                else "اكتمل التنزيل لكن الملف غير مكتمل. أعد المحاولة."
                            AppPrefs.setDownloadId(context, -1L)
                        }
                    }
                }
            }
            delay(700)
        }
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("ARAM AI", color = Lilac, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("مركز الذكاء المحلي", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Violet, Color(0xFF4C2D83)))),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Memory, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF342153), Panel, Panel2))).padding(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(if (genuinelyReady) Green else if (engine.state == "ERROR") Danger else Muted))
                            Spacer(Modifier.width(9.dp))
                            Text(
                                when { genuinelyReady -> "المحرك جاهز"; engine.isBusy -> "يجري تشغيل المحرك"; engine.state == "ERROR" -> "تعذر التشغيل"; else -> "الخادم متوقف" },
                                color = when { genuinelyReady -> Green; engine.state == "ERROR" -> Danger; else -> Muted },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.weight(1f))
                            Text("ARM64 · CPU", color = Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        Text("Spark-X2.6-1.7B", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(if (modelExists) "النموذج موجود على الجهاز" else "النموذج يحتاج إلى تنزيل", color = Muted)
                        val statusMessage = if (engine.state == "READY" && !genuinelyReady)
                            "كانت الخدمة جاهزة سابقًا لكنها غير نشطة الآن؛ يمكنك تشغيلها مجددًا."
                        else engine.message
                        if (statusMessage.isNotBlank()) {
                            Text(statusMessage, color = if (engine.state == "ERROR") Danger else Lilac, style = MaterialTheme.typography.bodySmall)
                        }
                        if (engine.isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Violet)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    AppPrefs.setEngineState(context, "STARTING", "تم إرسال أمر التشغيل إلى خدمة Android…")
                                    try {
                                        startForegroundService(context, Intent(context, ModelService::class.java).setAction(ModelService.ACTION_START))
                                    } catch (error: Exception) {
                                        AppPrefs.setEngineState(context, "ERROR", "تعذر إرسال أمر التشغيل: ${error.message ?: error.javaClass.simpleName}")
                                    }
                                },
                                enabled = !genuinelyReady && !engine.isBusy && !downloading && modelExists,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Violet),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Rounded.Memory, null)
                                Spacer(Modifier.width(7.dp))
                                Text(if (engine.isBusy) "جارٍ التشغيل…" else "تشغيل المحرك")
                            }
                            OutlinedButton(
                                onClick = { context.startService(Intent(context, ModelService::class.java).setAction(ModelService.ACTION_STOP)) },
                                enabled = genuinelyReady || engine.isBusy,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp)
                            ) { Text("إيقاف المحرك") }
                        }
                    }
                }
            }
        }
        item {
            SectionTitle("النموذج المحلي", "ملف GGUF الرسمي · تكميم Q4_K_M")
            Surface(shape = RoundedCornerShape(22.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Memory, null, tint = Violet, modifier = Modifier.size(27.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Spark-X2.6-1.7B-Q4_K_M.gguf", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("الحجم التقريبي 1.77 GB", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        if (modelExists) Text("جاهز", color = Green, fontWeight = FontWeight.Bold)
                    }
                    if (downloading) {
                        LinearProgressIndicator(progress = { downloadProgress / 100f }, modifier = Modifier.fillMaxWidth(), color = Violet)
                        Text("جارٍ التنزيل · $downloadProgress%", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (downloadMessage.isNotBlank()) Text(downloadMessage, color = Green, style = MaterialTheme.typography.bodySmall)
                    if (downloadError.isNotBlank()) Text(downloadError, color = Danger, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            try {
                                ModelFiles.enqueueDownload(context)
                                downloading = true
                                downloadProgress = 0
                                downloadMessage = ""
                                downloadError = ""
                            } catch (error: Exception) { downloadError = error.message ?: "تعذر بدء التنزيل" }
                        },
                        enabled = !downloading && !engine.isBusy && !genuinelyReady,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = Lilac)
                    ) { Text(if (modelExists) "إعادة تنزيل النموذج" else "تنزيل النموذج") }
                }
            }
        }
        item {
            SectionTitle("واجهة API", "متوافقة مبدئيًا مع OpenAI Chat Completions")
            Surface(color = Panel, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("العنوان المحلي", color = Muted, style = MaterialTheme.typography.labelMedium)
                    Text("http://127.0.0.1:8765/v1/chat/completions", color = Lilac, style = MaterialTheme.typography.bodyMedium)
                    Text("يتطلب Bearer API Key · طلب توليد واحد في كل مرة", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text(if (AppPrefs.allowLan(context)) "الوصول عبر الشبكة المحلية مفعّل" else "الوصول محصور داخل الهاتف حاليًا", color = if (AppPrefs.allowLan(context)) Green else Muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ApiKeysScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as AramAiApplication).apiKeyStore }
    var keys by remember { mutableStateOf(store.list()) }
    var issued by remember { mutableStateOf<IssuedApiKey?>(null) }
    var ownerExists by remember { mutableStateOf(store.ownerKeyExists()) }
    var clientQuota by remember { mutableIntStateOf(100) }
    var showQuotaMenu by remember { mutableStateOf(false) }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("مفاتيح الوصول", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("أنشئ مفتاحًا مستقلًا لكل تطبيق أو عميل. لن يُحفظ المفتاح السري داخل التطبيق بعد عرضه مرة واحدة.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        if (!ownerExists) item {
            Button(onClick = { issued = store.issueOwnerKey(); ownerExists = true; keys = store.list() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Violet)) {
                Icon(Icons.Rounded.Key, null); Spacer(Modifier.width(8.dp)); Text("إنشاء مفتاح المالك")
            }
        }
        item {
            Text("مفاتيح العملاء", fontWeight = FontWeight.SemiBold)
            Text("كل مفتاح عميل له حصة يومية مستقلة. مفتاح المالك لا يخضع لهذه الحصة وله أولوية على طلبات العملاء المنتظرة.", color = Muted, style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { showQuotaMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("الحصة اليومية للمفتاح الجديد: $clientQuota طلب") }
                DropdownMenu(expanded = showQuotaMenu, onDismissRequest = { showQuotaMenu = false }) {
                    listOf(100, 500, 1000, 5000).forEach { limit ->
                        DropdownMenuItem(text = { Text("$limit طلب يوميًا") }, onClick = { clientQuota = limit; showQuotaMenu = false })
                    }
                }
            }
            Button(onClick = { issued = store.issueClient("تطبيق جديد", clientQuota); keys = store.list() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = Lilac)) {
                Icon(Icons.Rounded.Key, null); Spacer(Modifier.width(8.dp)); Text("إنشاء مفتاح عميل")
            }
        }
        if (keys.isEmpty()) item { EmptyState("لا توجد مفاتيح بعد", "أنشئ مفتاحًا لربط تطبيقك بخادم Aram AI.") }
        items(keys, key = { it.id }) { record -> ApiKeyCard(record) {
            store.revoke(record.id)
            keys = store.list()
            ownerExists = store.ownerKeyExists()
        } }
    }

    issued?.let { newKey ->
        AlertDialog(
            onDismissRequest = { issued = null },
            title = { Text("احفظ مفتاحك الآن") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("هذا المفتاح سيظهر مرة واحدة فقط. انسخه واحفظه في مدير أسرارك.", color = Muted)
                    Surface(color = Ink, shape = RoundedCornerShape(12.dp)) { Text(newKey.secret, modifier = Modifier.padding(12.dp), color = Lilac) }
                }
            },
            confirmButton = { TextButton(onClick = { clipboard.setPrimaryClip(ClipData.newPlainText("Aram AI API Key", newKey.secret)); issued = null }) { Text("نسخ وحفظ") } },
            dismissButton = { TextButton(onClick = { issued = null }) { Text("إغلاق") } }
        )
    }
}

@Composable
private fun ApiKeyCard(record: ApiKeyRecord, onRevoke: () -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, null, tint = Violet)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(record.name + if (record.isOwner) " · المالك" else " · عميل", fontWeight = FontWeight.SemiBold)
                    Text("${record.prefix}••••••••", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (record.revoked) "ملغى" else if (record.isOwner) "أولوية المالك" else "نشط", color = if (record.revoked) Muted else Green, style = MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(if (record.isOwner) "الطلبات: ${record.requests} · دون حصة" else "اليوم: ${record.requestsToday}/${record.dailyQuota} · الإجمالي: ${record.requests}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(record.createdAt)), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (!record.revoked) TextButton(onClick = onRevoke, modifier = Modifier.align(Alignment.Start)) { Text(if (record.isOwner) "إلغاء/تدوير مفتاح المالك" else "إلغاء المفتاح", color = Danger) }
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var allowLan by remember { mutableStateOf(AppPrefs.allowLan(context)) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("الإعدادات", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("تحكم في طريقة إتاحة خادمك المحلي.", color = Muted)
        }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("الوصول من الشبكة المحلية", fontWeight = FontWeight.SemiBold)
                        Text("يسمح للأجهزة على شبكة Wi-Fi نفسها بالاتصال. يستخدم HTTP دون تشفير؛ لا تفعّله إلا على شبكة موثوقة. أوقف المحرك وشغّله مجددًا بعد تغيير الخيار.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = allowLan, onCheckedChange = { allowLan = it; AppPrefs.setAllowLan(context, it) })
                }
            }
        }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("حول Aram AI", fontWeight = FontWeight.SemiBold)
                    Text("الإصدار 0.5.0 · Spark-X2.6-1.7B · llama.cpp Android binding", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text("أثناء تشغيل الخادم يبقى المعالج مستيقظًا حتى مع إطفاء الشاشة؛ قد يزيد ذلك استهلاك البطارية.", color = Lilac, style = MaterialTheme.typography.bodySmall)
                    Text("حصص مفاتيح العملاء اليومية مفعّلة محليًا. الوصول العام عبر الإنترنت وTLS والفوترة وإدارة العملاء التجارية المركزية ليست مفعّلة.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyState(title: String, message: String) {
    Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Key, null, tint = Violet, modifier = Modifier.size(30.dp))
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(message, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
