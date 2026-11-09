package com.aram.ai.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

private val ChatInk = Color(0xFF090711)
private val ChatPanel = Color(0xFF151121)
private val ChatPanelRaised = Color(0xFF20192F)
private val ChatViolet = Color(0xFF9B7BFF)
private val ChatLilac = Color(0xFFD9CCFF)
private val ChatMuted = Color(0xFF9F96B4)
private val ChatGreen = Color(0xFF66DDB0)
private val ChatRed = Color(0xFFFF8295)
private val UserBubble = Color(0xFF39265D)
private val AssistantBubble = Color(0xFF191526)

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val chat: ChatViewModel = viewModel()
    val listState = rememberLazyListState()
    val messages = chat.messages
    val conversations = chat.conversations
    val activeId = chat.activeConversationId
    val draft = chat.draft
    val mode = chat.selectedMode
    val maxTokens = chat.maxTokens
    val isGenerating = chat.isGenerating
    val error = chat.error

    var engineLoaded by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showHeaderMenu by remember { mutableStateOf(false) }
    var showTokenMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            engineLoaded = com.aram.ai.engine.ModelRuntime.isLoaded() &&
                com.aram.ai.service.ModelService.active
            delay(700)
        }
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        modifier = modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF100C1B), ChatInk, ChatInk)
                )
            )
            .imePadding()
    ) {
        // Premium conversation header
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFB49AFF), Color(0xFF603BA7)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("ARAM AI", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, letterSpacing = 1.1.sp)
                Text(
                    chat.activeConversationTitle,
                    color = ChatMuted,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box {
                IconButton(onClick = { showHistory = true }, enabled = !isGenerating) {
                    Icon(Icons.Rounded.History, "سجل المحادثات", tint = ChatLilac)
                }
                DropdownMenu(expanded = showHistory, onDismissRequest = { showHistory = false }) {
                    DropdownMenuItem(
                        text = { Text("محادثة جديدة") },
                        leadingIcon = { Icon(Icons.Rounded.Add, null) },
                        onClick = { chat.newConversation(); showHistory = false },
                        enabled = !isGenerating
                    )
                    HorizontalDivider(color = ChatPanelRaised)
                    conversations.forEach { item ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(item.title.ifBlank { "محادثة جديدة" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (item.id == activeId) Text("المحادثة الحالية", color = ChatViolet, style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            leadingIcon = {
                                if (item.id == activeId) Icon(Icons.Rounded.Check, null, tint = ChatViolet)
                                else Icon(Icons.Rounded.Memory, null, tint = ChatMuted)
                            },
                            onClick = { chat.selectConversation(item.id); showHistory = false },
                            enabled = !isGenerating
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { showHeaderMenu = true }) {
                    Icon(Icons.Rounded.MoreVert, "خيارات المحادثة", tint = ChatMuted)
                }
                DropdownMenu(expanded = showHeaderMenu, onDismissRequest = { showHeaderMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("محادثة جديدة") },
                        leadingIcon = { Icon(Icons.Rounded.Add, null) },
                        onClick = { chat.newConversation(); showHeaderMenu = false },
                        enabled = !isGenerating
                    )
                    DropdownMenuItem(
                        text = { Text("مشاركة المحادثة") },
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        onClick = {
                            shareTranscript(context, messages)
                            showHeaderMenu = false
                        },
                        enabled = messages.isNotEmpty() && !isGenerating
                    )
                    DropdownMenuItem(
                        text = { Text("مسح الرسائل") },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                        onClick = { chat.clearConversation(); showHeaderMenu = false },
                        enabled = messages.isNotEmpty() && !isGenerating
                    )
                }
            }
        }

        // Engine state strip
        Surface(
            color = if (engineLoaded) Color(0xFF10271F) else ChatPanelRaised,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .border(1.dp, if (engineLoaded) Color(0xFF285541) else Color(0xFF393047), RoundedCornerShape(14.dp))
        ) {
            Row(
                Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (engineLoaded) ChatGreen else ChatMuted)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        if (engineLoaded) "المحرك جاهز" else "المحرك غير جاهز",
                        color = if (engineLoaded) ChatGreen else ChatMuted,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Spark-X2.6-1.7B  •  معالجة محلية على الجهاز",
                        color = ChatMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(if (engineLoaded) "متصل" else "متوقف", color = if (engineLoaded) ChatGreen else ChatMuted, style = MaterialTheme.typography.labelSmall)
            }
        }

        // Modes and output limit
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("عادي", "تحليل", "برمجة").forEach { item ->
                FilterChip(
                    selected = mode == item,
                    onClick = { chat.setMode(item) },
                    label = { Text(item, fontSize = 12.sp) },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = UserBubble,
                        selectedLabelColor = ChatLilac,
                        labelColor = ChatMuted
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = mode == item,
                        borderColor = ChatPanelRaised,
                        selectedBorderColor = ChatViolet
                    )
                )
            }
            Spacer(Modifier.weight(1f))
            Box {
                AssistChip(
                    onClick = { showTokenMenu = true },
                    label = { Text("$maxTokens رمز", fontSize = 11.sp) },
                    shape = RoundedCornerShape(12.dp),
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = ChatLilac,
                        containerColor = ChatPanelRaised
                    )
                )
                DropdownMenu(expanded = showTokenMenu, onDismissRequest = { showTokenMenu = false }) {
                    listOf(64, 128, 256, 500, 768, 1000, 1500, 2000).forEach { count ->
                        DropdownMenuItem(
                            text = { Text("$count رمز") },
                            onClick = { chat.updateMaxTokens(count); showTokenMenu = false }
                        )
                    }
                }
            }
        }

        // Message timeline
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 15.dp, end = 15.dp, top = 8.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    EmptyConversation(
                        onPrompt = { chat.updateDraft(it) }
                    )
                }
            }
            itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                val canRegenerate = !isGenerating &&
                    message.role == "assistant" &&
                    index == messages.lastIndex &&
                    message.content.isNotBlank()
                val canContinue = !isGenerating && message.role == "assistant" && index == messages.lastIndex && message.content.isNotBlank()
                MessageBubble(
                    message = message,
                    onRegenerate = if (canRegenerate) ({ chat.regenerateLast() }) else null,
                    onContinue = if (canContinue) ({ chat.continueLast() }) else null
                )
            }
            if (isGenerating) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = ChatViolet
                        )
                        Text("ARAM AI يكتب الآن…", color = ChatLilac, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        Text("جارٍ التوليد", color = ChatMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = error.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                color = Color(0xFF351B29),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text(error, color = ChatRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(11.dp))
            }
        }

        // Composer dock
        Surface(
            color = ChatPanel,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
                .border(width = 1.dp, color = Color(0xFF302640), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        ) {
            Column(
                Modifier.navigationBarsPadding().padding(start = 13.dp, end = 13.dp, top = 12.dp, bottom = 9.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Surface(
                        color = ChatInk,
                        shape = RoundedCornerShape(19.dp),
                        modifier = Modifier.weight(1f)
                            .border(1.dp, Color(0xFF403451), RoundedCornerShape(19.dp))
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { chat.updateDraft(it) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text("اكتب رسالتك هنا…", color = ChatMuted, fontSize = 14.sp)
                            },
                            minLines = 1,
                            maxLines = 5,
                            shape = RoundedCornerShape(19.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (!isGenerating) chat.sendMessage() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                disabledBorderColor = Color.Transparent,
                                focusedContainerColor = ChatInk,
                                unfocusedContainerColor = ChatInk,
                                cursorColor = ChatLilac,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                    }
                    if (isGenerating) {
                        FilledIconButton(
                            onClick = { chat.stopGeneration() },
                            modifier = Modifier.size(54.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color(0xFF512332),
                                contentColor = ChatRed
                            )
                        ) {
                            Icon(Icons.Rounded.StopCircle, "إيقاف التوليد", modifier = Modifier.size(25.dp))
                        }
                    } else {
                        FilledIconButton(
                            onClick = { chat.sendMessage() },
                            enabled = draft.isNotBlank() && engineLoaded,
                            modifier = Modifier.size(54.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = ChatViolet,
                                contentColor = Color.White,
                                disabledContainerColor = ChatPanelRaised,
                                disabledContentColor = ChatMuted
                            )
                        ) {
                            Icon(Icons.Rounded.Send, "إرسال الرسالة", modifier = Modifier.size(24.dp))
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (engineLoaded) "محادثتك تبقى على هذا الجهاز" else "شغّل المحرك من الصفحة الرئيسية للبدء",
                        color = ChatMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        if (chat.budgetIsCapped) "المطلوب: $maxTokens · المتاح حاليًا: ${chat.effectiveMaxTokens}"
                        else "حد الإخراج: $maxTokens رمز",
                        color = if (chat.budgetIsCapped) Color(0xFFFFD18A) else ChatMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyConversation(onPrompt: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Box(
            Modifier.size(78.dp).clip(RoundedCornerShape(27.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF60439B), Color(0xFF2B2042)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(36.dp))
        }
        Text("مرحبًا بك في ARAM AI", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(
            "مساحة تفكير وعمل تعمل محليًا على جهازك.\nما الذي ترغب في إنجازه اليوم؟",
            color = ChatMuted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 22.sp
        )
        Spacer(Modifier.height(3.dp))
        Text("ابدأ بأحد هذه الاقتراحات", color = ChatLilac, style = MaterialTheme.typography.labelLarge)
        listOf(
            "اشرح لي فكرة معقدة بطريقة بسيطة.",
            "اكتب كودًا وراجع أخطاءه المحتملة.",
            "حلّل فكرة واذكر نقاط قوتها وضعفها."
        ).forEach { prompt ->
            Surface(
                onClick = { onPrompt(prompt) },
                color = ChatPanel,
                shape = RoundedCornerShape(15.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF342A46)),
                modifier = Modifier.fillMaxWidth().animateContentSize()
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = ChatViolet, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(prompt, color = Color(0xFFECE5FF), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onRegenerate: (() -> Unit)?,
    onContinue: (() -> Unit)?
) {
    val context = LocalContext.current
    val isUser = message.role == "user"
    var showActions by remember(message.id) { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.Start else Arrangement.End
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.92f),
            horizontalAlignment = if (isUser) Alignment.Start else Alignment.End,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    Modifier.size(25.dp).clip(CircleShape)
                        .background(if (isUser) UserBubble else Color(0xFF30234B)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isUser) Icons.Rounded.Memory else Icons.Rounded.AutoAwesome,
                        null,
                        tint = if (isUser) ChatLilac else ChatViolet,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    if (isUser) "أنت" else "ARAM AI",
                    color = if (isUser) ChatLilac else ChatGreen,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                if (message.failed) {
                    Text("توقف غير مكتمل", color = ChatRed, style = MaterialTheme.typography.labelSmall)
                }
            }

            Surface(
                color = if (isUser) UserBubble else AssistantBubble,
                shape = RoundedCornerShape(
                    topStart = 19.dp,
                    topEnd = 19.dp,
                    bottomStart = if (isUser) 6.dp else 19.dp,
                    bottomEnd = if (isUser) 19.dp else 6.dp
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isUser) Color(0xFF5A3C88) else Color(0xFF30283E)
                ),
                modifier = Modifier.fillMaxWidth().animateContentSize()
            ) {
                Column(
                    Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    if (message.content.isBlank() && !isUser) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = ChatViolet)
                            Text("يتم إعداد الرد…", color = ChatMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        SelectionContainer {
                            Text(
                                message.content,
                                color = if (message.failed) Color(0xFFFFC2CC) else Color(0xFFF4F0FF),
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 24.sp
                            )
                        }
                    }

                    if (!isUser && message.durationMs != null) {
                        HorizontalDivider(color = Color(0xFF342B43))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${message.durationMs / 1000.0}s", color = ChatMuted, style = MaterialTheme.typography.labelSmall)
                            Text("أول مقطع: ${message.firstChunkMs ?: "—"} ms", color = ChatMuted, style = MaterialTheme.typography.labelSmall)
                            Text("${String.format("%.1f", message.chunksPerSecond ?: 0.0)} مقطع/ث", color = ChatMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("ARAM AI", message.content))
                    },
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                    enabled = message.content.isNotBlank()
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, tint = ChatMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("نسخ", color = ChatMuted, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(
                    onClick = { shareMessage(context, message) },
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                    enabled = message.content.isNotBlank()
                ) {
                    Icon(Icons.Rounded.Share, null, tint = ChatMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("مشاركة", color = ChatMuted, style = MaterialTheme.typography.labelSmall)
                }
                if (onRegenerate != null) {
                    TextButton(
                        onClick = onRegenerate,
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Rounded.Refresh, null, tint = ChatViolet, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("إعادة", color = ChatLilac, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (onContinue != null) {
                    TextButton(onClick = onContinue, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
                        Text("متابعة الرد", color = ChatLilac, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun shareMessage(context: Context, message: ChatMessage) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message.content)
        putExtra(Intent.EXTRA_SUBJECT, if (message.role == "user") "رسالة من محادثة ARAM AI" else "رد ARAM AI")
    }
    context.startActivity(Intent.createChooser(intent, "مشاركة الرسالة"))
}

private fun shareTranscript(context: Context, messages: List<ChatMessage>) {
    if (messages.isEmpty()) return
    val transcript = messages.joinToString("\n\n") { message ->
        (if (message.role == "user") "أنت" else "ARAM AI") + ":\n" + message.content
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "محادثة ARAM AI")
        putExtra(Intent.EXTRA_TEXT, transcript)
    }
    context.startActivity(Intent.createChooser(intent, "مشاركة المحادثة"))
}
