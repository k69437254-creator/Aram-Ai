package com.aram.ai.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

private val Ink = Color(0xFF0B0914)
private val Panel = Color(0xFF171226)
private val Panel2 = Color(0xFF211934)
private val Violet = Color(0xFF9B7BFF)
private val Lilac = Color(0xFFD5C7FF)
private val Muted = Color(0xFFAAA1BE)
private val Green = Color(0xFF57D6A2)
private val Danger = Color(0xFFFF8295)

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val chat: ChatViewModel = viewModel()
    val listState = rememberLazyListState()
    val messages = chat.messages
    val draft = chat.draft
    val selectedMode = chat.selectedMode
    val maxTokens = chat.maxTokens
    val isGenerating = chat.isGenerating
    val error = chat.error
    var engineLoaded by remember { mutableStateOf(false) }
    var showTokenMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            engineLoaded = com.aram.ai.engine.ModelRuntime.isLoaded() && com.aram.ai.service.ModelService.active
            delay(700)
        }
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }


    Column(modifier.fillMaxSize().background(Ink).imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(Violet, Color(0xFF54338A)))),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Memory, null, tint = Color.White) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("مساحة المحادثة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Spark-X2.6-1.7B · محلي على جهازك", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (messages.isNotEmpty()) {
                TextButton(
                    onClick = {
                        val transcript = messages.joinToString("\n\n") { message ->
                            (if (message.role == "user") "أنت" else "Aram AI") + ":\n" + message.content
                        }
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "محادثة Aram AI")
                            putExtra(Intent.EXTRA_TEXT, transcript)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "مشاركة المحادثة"))
                    },
                    enabled = !isGenerating
                ) { Icon(Icons.Rounded.Share, null, tint = Lilac) }
            }
            TextButton(onClick = { chat.clearConversation() }, enabled = !isGenerating) {
                Icon(Icons.Rounded.DeleteOutline, null, tint = Muted)
            }
        }

        Surface(
            color = if (engineLoaded) Color(0xFF102A25) else Panel2,
            shape = RoundedCornerShape(13.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
        ) {
            Row(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (engineLoaded) Green else Muted))
                Spacer(Modifier.width(8.dp))
                Text(if (engineLoaded) "المحرك متصل وجاهز لاستقبال الرسائل" else "المحرك غير جاهز · ابدأ تشغيله من الرئيسية", color = if (engineLoaded) Green else Muted, style = MaterialTheme.typography.labelMedium)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("مساعد", "برمجة", "تحليل").forEach { mode ->
                FilterChip(
                    selected = selectedMode == mode,
                    onClick = { chat.setMode(mode) },
                    label = { Text(mode) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF39265D), selectedLabelColor = Lilac)
                )
            }
            Box {
                AssistChip(onClick = { showTokenMenu = true }, label = { Text("حد الإخراج: $maxTokens") })
                DropdownMenu(expanded = showTokenMenu, onDismissRequest = { showTokenMenu = false }) {
                    listOf(64, 128, 256, 512).forEach { limit ->
                        DropdownMenuItem(text = { Text("$limit رمز") }, onClick = { chat.updateMaxTokens(limit); showTokenMenu = false })
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Surface(color = Panel, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            Box(Modifier.size(58.dp).clip(CircleShape).background(Color(0xFF30204D)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Memory, null, tint = Lilac, modifier = Modifier.size(29.dp))
                            }
                            Text("ابدأ حوارك مع Aram AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("أرسل سؤالًا، اطلب كتابة كود، أو اختبر قدرات النموذج. كل التوليد يتم محليًا على الهاتف.", color = Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                            Text("أمثلة للبدء", color = Lilac, style = MaterialTheme.typography.labelLarge)
                            listOf("اشرح لي كيف تعمل الشبكات العصبية.", "اكتب دالة Python تتحقق من الأعداد الأولية.", "حلل هذه الفكرة واذكر نقاط ضعفها.").forEach { example ->
                                OutlinedButton(onClick = { chat.updateDraft(example) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                                    Text(example, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            items(messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
            if (isGenerating) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 7.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = Violet)
                        Text("النموذج يكتب الآن…", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (error.isNotBlank()) {
            Text(error, color = Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp))
        }
        Surface(color = Panel, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { chat.updateDraft(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("اكتب رسالتك إلى النموذج…", color = Muted) },
                        minLines = 1,
                        maxLines = 5,
                        shape = RoundedCornerShape(18.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { chat.sendMessage() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Violet, unfocusedBorderColor = Color(0xFF44365E),
                            focusedContainerColor = Ink, unfocusedContainerColor = Ink,
                            cursorColor = Lilac
                        )
                    )
                    if (isGenerating) {
                        FilledTonalIconButton(onClick = { chat.stopGeneration() }, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Rounded.StopCircle, contentDescription = "إيقاف التوليد", tint = Danger)
                        }
                    } else {
                        FilledIconButton(
                            onClick = { chat.sendMessage() },
                            enabled = draft.isNotBlank() && engineLoaded,
                            modifier = Modifier.size(52.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Violet)
                        ) { Icon(Icons.Rounded.Send, contentDescription = "إرسال الطلب", tint = Color.White) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("محلي · لا يحتاج إلى اتصال سحابي بعد تنزيل النموذج", color = Muted, style = MaterialTheme.typography.labelSmall)
                    Text("$maxTokens رمز كحد أقصى", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val context = LocalContext.current
    val isUser = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.Start else Arrangement.End) {
        Surface(
            color = if (isUser) Color(0xFF352352) else Panel,
            shape = RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp,
                bottomStart = if (isUser) 6.dp else 20.dp,
                bottomEnd = if (isUser) 20.dp else 6.dp
            ),
            modifier = Modifier.fillMaxWidth(0.91f)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isUser) "أنت" else "ARAM AI", color = if (isUser) Lilac else Green, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (!isUser && message.content.isNotBlank()) {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Aram AI", message.content))
                        }, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.ContentCopy, null, tint = Muted, modifier = Modifier.size(16.dp)) }
                    }
                }
                if (message.content.isBlank() && !isUser) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.35f), color = Violet)
                } else {
                    SelectionContainer { Text(message.content, color = if (message.failed) Danger else Color(0xFFF4F0FF), style = MaterialTheme.typography.bodyMedium, lineHeight = 23.sp) }
                }
                if (!isUser && message.durationMs != null) {
                    Text(
                        "${message.durationMs / 1000.0}s · ${String.format("%.1f", message.chunksPerSecond ?: 0.0)} مقطع/ث",
                        color = Muted, style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
