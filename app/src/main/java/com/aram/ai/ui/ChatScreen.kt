package com.aram.ai.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
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
fun ChatScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
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
    val isCancelling = chat.isCancelling
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
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length, isGenerating) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        modifier = modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF100C1B), ChatInk, ChatInk)))
            .imePadding()
    ) {
        // Independent chat-room top bar. The app's main navigation is not part of this screen.
        Row(
            modifier = Modifier.fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.ArrowForward, "رجوع", tint = ChatLilac, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(5.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Aram AI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.size(7.dp).clip(CircleShape)
                            .background(if (engineLoaded) ChatGreen else ChatMuted)
                    )
                    Text(
                        if (engineLoaded) "متصل" else "غير متصل",
                        color = if (engineLoaded) ChatGreen else ChatMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text("·", color = ChatMuted, style = MaterialTheme.typography.labelSmall)
                    Text(
                        chat.activeConversationTitle.ifBlank { "محادثة جديدة" },
                        color = ChatMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Box {
                IconButton(onClick = { showHistory = true }, enabled = !isGenerating) {
                    Icon(Icons.Rounded.History, "المحادثات", tint = ChatLilac)
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
                                    if (item.id == activeId) {
                                        Text("المحادثة الحالية", color = ChatViolet, style = MaterialTheme.typography.labelSmall)
                                    }
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
                        text = { Text("مشاركة المحادثة") },
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        onClick = { shareTranscript(context, messages); showHeaderMenu = false },
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

        HorizontalDivider(color = Color(0xFF292235), thickness = 1.dp)

        // Conversation controls stay inside the room and remain compact.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("عادي", "تحليل", "برمجة").forEach { item ->
                FilterChip(
                    selected = mode == item,
                    onClick = { chat.setMode(item) },
                    label = { Text(item, fontSize = 11.sp) },
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
                    label = { Text("$maxTokens رمز", fontSize = 10.sp) },
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
                            onClick = { chat.updateMaxTokens(count); showTokenMenu = false },
                            enabled = !isGenerating
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 15.dp, end = 15.dp, top = 10.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (messages.isEmpty()) {
                item { EmptyConversation(onPrompt = { chat.updateDraft(it) }) }
            }
            itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                val canRegenerate = !isGenerating && message.role == "assistant" &&
                    index == messages.lastIndex && message.content.isNotBlank()
                val canContinue = canRegenerate
                MessageBubble(
                    message = message,
                    onRegenerate = if (canRegenerate) ({ chat.regenerateLast() }) else null,
                    onContinue = if (canContinue) ({ chat.continueLast() }) else null
                )
            }
            if (isGenerating) {
                item(key = "generation-status") {
                    AnimatedVisibility(
                        visible = !isCancelling,
                        enter = fadeIn(tween(140)),
                        exit = fadeOut(tween(240))
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(17.dp),
                                strokeWidth = 2.dp,
                                color = ChatViolet
                            )
                            Text("جاري الكتابة…", color = ChatLilac, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = error.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
            Surface(
                color = Color(0xFF351B29),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Text(error, color = ChatRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(11.dp))
            }
        }

        // WhatsApp-inspired composer: expandable pill field + one circular send/stop control.
        Surface(color = ChatInk, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    color = ChatPanelRaised,
                    shape = RoundedCornerShape(27.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF403451))
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (draft.isEmpty()) {
                            Text("اكتب رسالتك…", color = ChatMuted, fontSize = 14.sp)
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = { chat.updateDraft(it) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 29.dp, max = 120.dp),
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 15.sp,
                                lineHeight = 22.sp
                            ),
                            cursorBrush = SolidColor(ChatLilac),
                            minLines = 1,
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (isGenerating) chat.stopGeneration()
                                    else if (draft.isNotBlank()) chat.sendMessage()
                                }
                            )
                        )
                    }
                }

                FilledIconButton(
                    onClick = {
                        if (isGenerating) chat.stopGeneration() else chat.sendMessage()
                    },
                    enabled = if (isGenerating) true else draft.isNotBlank() && engineLoaded,
                    modifier = Modifier.size(50.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isGenerating) Color(0xFFD94C55) else ChatViolet,
                        contentColor = Color.White,
                        disabledContainerColor = ChatPanelRaised,
                        disabledContentColor = ChatMuted
                    )
                ) {
                    AnimatedContent(targetState = isGenerating, label = "send-stop-control") { stopping ->
                        Icon(
                            if (stopping) Icons.Rounded.StopCircle else Icons.Rounded.ArrowUpward,
                            contentDescription = if (stopping) "إيقاف التوليد" else "إرسال الرسالة",
                            modifier = Modifier.size(23.dp)
                        )
                    }
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

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalAlignment = if (isUser) Alignment.Start else Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                Modifier.size(27.dp).clip(CircleShape)
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
                if (isUser) "أنت" else "Aram AI",
                color = if (isUser) ChatLilac else ChatGreen,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            if (message.failed) {
                Text("تعذر اكتمال الرد", color = ChatRed, style = MaterialTheme.typography.labelSmall)
            }
        }

        Surface(
            color = if (isUser) Color(0xFF211A32) else Color(0xFF14121D),
            shape = RoundedCornerShape(17.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isUser) Color(0xFF594477) else Color(0xFF30283E)
            ),
            modifier = Modifier.fillMaxWidth(if (isUser) 0.91f else 0.985f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (isUser) ChatViolet else ChatGreen)
                )
                Spacer(Modifier.width(10.dp))
                SelectionContainer {
                    Text(
                        message.content,
                        color = if (message.failed) Color(0xFFFFC2CC) else Color(0xFFF4F0FF),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 24.sp
                    )
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
                TextButton(onClick = onRegenerate, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
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
