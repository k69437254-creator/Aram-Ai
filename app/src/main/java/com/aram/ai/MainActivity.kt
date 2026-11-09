package com.aram.ai

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.StopCircle
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import com.aram.ai.data.ApiKeyRecord
import com.aram.ai.data.ApiKeyStore
import com.aram.ai.data.AppPrefs
import com.aram.ai.data.IssuedApiKey
import com.aram.ai.data.ModelFiles
import com.aram.ai.service.ModelService
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private val Ink = Color(0xFF0C0916)
private val Panel = Color(0xFF171226)
private val Panel2 = Color(0xFF211934)
private val Violet = Color(0xFF9B7BFF)
private val Lilac = Color(0xFFD5C7FF)
private val Muted = Color(0xFFAAA1BE)
private val Green = Color(0xFF57D6A2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 700)
        }
        setContent { AramAiTheme { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { AramAiRoot() } } }
    }
}

@Composable
private fun AramAiTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Violet, onPrimary = Color.White, secondary = Lilac,
        background = Ink, onBackground = Color(0xFFF5F1FF),
        surface = Panel, onSurface = Color(0xFFF5F1FF),
        surfaceVariant = Panel2, onSurfaceVariant = Muted,
        outline = Color(0xFF44365E)
    )
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

@Composable
private fun AramAiRoot() {
    var selectedTab by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar(containerColor = Panel, contentColor = Lilac) {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Rounded.Home, null) }, label = { Text("الرئيسية") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Rounded.Key, null) }, label = { Text("مفاتيح API") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Rounded.Settings, null) }, label = { Text("الإعدادات") })
            }
        }
    ) { padding ->
        AnimatedContent(targetState = selectedTab, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "tabs") { tab ->
            when (tab) {
                0 -> DashboardScreen(Modifier.padding(padding))
                1 -> ApiKeysScreen(Modifier.padding(padding))
                else -> SettingsScreen(Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun DashboardScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
    var running by remember { mutableStateOf(AppPrefs.isRunning(context)) }
    var modelExists by remember { mutableStateOf(ModelFiles.isModelReady(context)) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloading by remember { mutableStateOf(false) }
    var downloadMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            running = AppPrefs.isRunning(context)
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
                            errorMessage = "فشل التنزيل (رمز $reason). أعد المحاولة."
                            AppPrefs.setDownloadId(context, -1L)
                        } else if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            downloading = false
                            val promoted = ModelFiles.finalizeDownload(context)
                            modelExists = ModelFiles.isModelReady(context)
                            if (promoted) downloadMessage = "اكتمل تنزيل النموذج والتحقق من حجمه"
                            else errorMessage = if (modelExists) "تعذر استبدال النموذج؛ النسخة السابقة ما تزال محفوظة."
                                else "اكتمل التنزيل لكن حجم الملف غير مكتمل. أعد المحاولة."
                            AppPrefs.setDownloadId(context, -1L)
                        }
                    }
                }
            }
            delay(1000)
        }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("ARAM AI", color = Lilac, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("مركز الذكاء المحلي", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.size(50.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Violet, Color(0xFF4C2D83)))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Memory, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFF332054), Panel, Panel2))).padding(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(if (running) Green else Muted))
                            Text(if (running) "الخادم يعمل" else "الخادم متوقف", color = if (running) Green else Muted, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.weight(1f))
                            Text("ARM64 · CPU", color = Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        Text("Spark-X2.6-1.7B", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (modelExists) "النموذج موجود على الجهاز" else "النموذج غير مثبت بعد", color = Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = {
                                errorMessage = ""
                                if (modelExists) {
                                    startForegroundService(context, Intent(context, ModelService::class.java).setAction(ModelService.ACTION_START))
                                } else errorMessage = "نزّل النموذج أولًا قبل تشغيل الخادم."
                            }, enabled = !running && !downloading && modelExists, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Violet), shape = RoundedCornerShape(14.dp)) {
                                Icon(Icons.Rounded.PowerSettingsNew, null)
                                Spacer(Modifier.width(6.dp)); Text("تشغيل المحرك")
                            }
                            OutlinedButton(onClick = { context.startService(Intent(context, ModelService::class.java).setAction(ModelService.ACTION_STOP)) }, enabled = running, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                                Icon(Icons.Rounded.StopCircle, null); Spacer(Modifier.width(6.dp)); Text("إيقاف")
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionTitle("النموذج المحلي", "تنزيل رسمي بصيغة GGUF · Q4_K_M")
            Surface(shape = RoundedCornerShape(22.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CloudDownload, null, tint = Violet, modifier = Modifier.size(26.dp))
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
                    if (downloadMessage.isNotBlank()) Text(downloadMessage, color = Green)
                    if (errorMessage.isNotBlank()) Text(errorMessage, color = Color(0xFFFF8A9A))
                    Button(
                        onClick = {
                            try {
                                ModelFiles.enqueueDownload(context)
                                downloading = true
                                downloadProgress = 0
                                downloadMessage = ""
                                errorMessage = ""
                            } catch (error: Exception) { errorMessage = error.message ?: "تعذر بدء التنزيل" }
                        },
                        enabled = !downloading && !running,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Panel2, contentColor = Lilac)
                    ) { Text(if (modelExists) "إعادة تنزيل النموذج" else if (downloading) "جارٍ التنزيل" else "تنزيل النموذج") }
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
                    if (AppPrefs.allowLan(context)) Text("الوصول عبر الشبكة المحلية مفعّل", color = Green, style = MaterialTheme.typography.labelMedium)
                    else Text("الوصول محصور داخل الهاتف حاليًا", color = Muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard(Modifier.weight(1f), "المعمارية", "ARM64", Icons.Rounded.Memory)
                MetricCard(Modifier.weight(1f), "المنفذ", "8765", Icons.Rounded.NetworkCheck)
                MetricCard(Modifier.weight(1f), "التوليد", "Streaming", Icons.Rounded.Speed)
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
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("مفاتيح الوصول", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("أنشئ مفتاحًا مستقلًا لكل تطبيق أو عميل. لن يُحفظ المفتاح السري داخل التطبيق بعد عرضه مرة واحدة.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Button(onClick = { issued = store.issue("تطبيق جديد"); keys = store.list() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Violet)) {
                Icon(Icons.Rounded.Key, null); Spacer(Modifier.width(8.dp)); Text("إنشاء مفتاح API جديد")
            }
        }
        if (keys.isEmpty()) item { EmptyState("لا توجد مفاتيح بعد", "أنشئ مفتاحًا لربط تطبيقك بخادم Aram AI.") }
        items(keys, key = { it.id }) { record -> ApiKeyCard(record) { store.revoke(record.id); keys = store.list() } }
    }

    issued?.let { newKey ->
        AlertDialog(
            onDismissRequest = { issued = null },
            title = { Text("احفظ مفتاحك الآن") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("هذا المفتاح سيظهر مرة واحدة فقط. انسخه واحفظه في تطبيقك أو مدير أسرارك.", color = Muted)
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
                    Text(record.name, fontWeight = FontWeight.SemiBold)
                    Text("${record.prefix}••••••••", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (record.revoked) "ملغى" else "نشط", color = if (record.revoked) Muted else Green, style = MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("الطلبات: ${record.requests}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(record.createdAt)), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (!record.revoked) TextButton(onClick = onRevoke, modifier = Modifier.align(Alignment.Start)) { Text("إلغاء المفتاح", color = Color(0xFFFF8A9A)) }
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
                        Text("يسمح للأجهزة على شبكة Wi-Fi نفسها بالاتصال. لا يفتح الخادم على الإنترنت مباشرة. الاتصال هنا HTTP دون تشفير؛ استخدم شبكة موثوقة فقط، وأعد تشغيل الخادم بعد تغيير الخيار.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = allowLan, onCheckedChange = {
                        allowLan = it
                        AppPrefs.setAllowLan(context, it)
                    })
                }
            }
        }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("حول Aram AI", fontWeight = FontWeight.SemiBold)
                    Text("الإصدار 0.1.0 · Spark-X2.6-1.7B · llama.cpp Android binding", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text("أثناء تشغيل الخادم يبقى المعالج مستيقظًا حتى مع إطفاء الشاشة؛ قد يزيد ذلك استهلاك البطارية.", color = Lilac, style = MaterialTheme.typography.bodySmall)
                    Text("الوصول الخارجي عبر الإنترنت، الفوترة، حصص العملاء، وأدوات الوكلاء المتقدمة مراحل لاحقة وليست مفعلة في هذه النسخة.", color = Muted, style = MaterialTheme.typography.bodySmall)
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
private fun MetricCard(modifier: Modifier, title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(modifier, color = Panel, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, tint = Violet, modifier = Modifier.size(19.dp))
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text(title, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
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
