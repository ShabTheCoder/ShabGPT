package com.shablab.shabgpt

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import io.noties.markwon.Markwon
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

private val Ink = Color(0xFF07070A)
private val Panel = Color(0xFF17151D)
private val Panel2 = Color(0xFF211D29)
private val MainText = Color(0xFFF8F6FC)
private val Muted = Color(0xFFBDB8C7)
private val Danger = Color(0xFFFF9BA8)
private const val API = "https://shabgptapi.shabarish4737.workers.dev"
private const val DOCS_URL = "https://shabgptapi.shabarish4737.workers.dev/docs"
private const val MAX_IMAGE_BYTES = 1_350_000

private data class AccentTheme(val name: String, val primary: Color, val secondary: Color)
private val AccentThemes = listOf(
    AccentTheme("Purple", Color(0xFFC8AEFF), Color(0xFF9CE8C2)),
    AccentTheme("Blue", Color(0xFF91BFFF), Color(0xFF9BE5E0)),
    AccentTheme("Green", Color(0xFF8DDBB0), Color(0xFFC0E8A4)),
    AccentTheme("Rose", Color(0xFFFFA7C7), Color(0xFFFFD2A1))
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 7, 10)
        window.navigationBarColor = android.graphics.Color.rgb(7, 7, 10)
        setContent { ShabGPTApp(this) }
    }
}

private data class ChatMessage(val id: String?, val role: String, val content: String, val images: List<String> = emptyList(), val time: String = "")
private data class Conversation(val id: String, val title: String, val updated: String = "")

@Composable
private fun ShabGPTApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("shabgpt", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", "").orEmpty()) }
    var username by remember { mutableStateOf(prefs.getString("username", "").orEmpty()) }
    var conversationId by remember { mutableStateOf(prefs.getString("conversation_id", "").orEmpty()) }
    var selectedTheme by remember { mutableStateOf(prefs.getString("accent_theme", "Purple").orEmpty()) }
    var selectedTimezone by remember { mutableStateOf(prefs.getString("timezone", java.util.TimeZone.getDefault().id).orEmpty()) }
    val accent = AccentThemes.firstOrNull { it.name == selectedTheme } ?: AccentThemes.first()
    var register by remember { mutableStateOf(false) }
    var usernameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var imageBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var screen by remember { mutableStateOf("chat") }
    val backStack = remember { mutableStateListOf<String>() }
    var historyQuery by remember { mutableStateOf("") }
    var conversations by remember { mutableStateOf(listOf<Conversation>()) }
    var pendingImages by remember { mutableStateOf(listOf<String>()) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val scope = rememberCoroutineScope()

    fun navigateTo(destination: String) {
        backStack.add(screen)
        screen = destination
    }
    fun navigateBack() {
        if (backStack.isNotEmpty()) screen = backStack.removeAt(backStack.lastIndex)
        else screen = "chat"
    }
    BackHandler(enabled = token.isNotBlank() && backStack.isNotEmpty()) { navigateBack() }

    fun persistSession() {
        prefs.edit().putString("token", token).putString("username", username)
            .putString("conversation_id", conversationId).putString("accent_theme", selectedTheme)
            .putString("timezone", selectedTimezone).apply()
    }
    fun newChat() {
        conversationId = ""; messages.clear(); prompt = ""; pendingImages = emptyList()
        error = ""; info = ""; backStack.clear(); persistSession(); screen = "chat"
    }
    suspend fun loadHistory() {
        val result = api("/v1/conversations", "GET", token)
        val array = result.optJSONArray("conversations") ?: result.optJSONArray("data") ?: JSONArray()
        conversations = (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isBlank()) null else Conversation(id, item.optString("title").ifBlank { "New conversation" }, item.optString("updated_at"))
        }
    }
    suspend fun openConversation(id: String) {
        val result = api("/v1/conversations/$id", "GET", token)
        val array = result.optJSONArray("messages") ?: JSONArray()
        messages.clear()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val role = item.optString("role")
            if (role != "user" && role != "assistant") continue
            val (text, images) = unpackStoredContent(item.opt("content"))
            messages.add(ChatMessage(item.optString("id").takeIf { it.isNotBlank() }, role, text, images, item.optString("created_at")))
        }
        conversationId = id; persistSession(); screen = "chat"; error = ""; info = ""
    }
    suspend fun sendMessage() {
        if (busy) return
        val text = prompt.trim()
        val images = pendingImages
        if (text.isBlank() && images.isEmpty()) return
        error = ""; info = ""
        messages.add(ChatMessage(null, "user", text, images))
        prompt = ""; pendingImages = emptyList(); busy = true
        try {
            val arr = JSONArray()
            val userZone = java.time.ZoneId.of(selectedTimezone)
            val localNow = java.time.ZonedDateTime.now(userZone)
            val timeContext = "The user's selected time zone is $selectedTimezone. The current local date and time for the user is ${localNow.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm:ss z"))}. Use this supplied local time when answering questions about the current time or date. Do not guess the user's time zone."
            arr.put(JSONObject().put("role", "system").put("content", timeContext))
            messages.forEach { msg ->
                val item = JSONObject().put("role", msg.role)
                if (msg.images.isEmpty()) item.put("content", msg.content)
                else {
                    val parts = JSONArray()
                    if (msg.content.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", msg.content))
                    msg.images.forEach { data ->
                        parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", data).put("detail", "auto")))
                    }
                    item.put("content", parts)
                }
                arr.put(item)
            }
            val body = JSONObject().put("messages", arr).put("max_tokens", 768).put("timezone", selectedTimezone)
            if (conversationId.isNotBlank()) body.put("conversation_id", conversationId)
            val result = api("/v1/chat/completions", "POST", token, body)
            val answer = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            conversationId = result.optString("conversation_id", conversationId)
            persistSession()
            messages.add(ChatMessage(result.optString("assistant_message_id").takeIf { it.isNotBlank() }, "assistant", answer))
        } catch (e: Exception) { error = e.message ?: "Message failed. Check your connection and try again." }
        busy = false
    }

    LaunchedEffect(token, conversationId) {
        if (token.isNotBlank() && conversationId.isNotBlank() && messages.isEmpty()) {
            try { openConversation(conversationId) } catch (e: Exception) { error = e.message ?: "Could not restore the last conversation." }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            imageBusy = true; error = ""
            try {
                val selected = mutableListOf<String>()
                var total = pendingImages.sumOf { it.length }
                for (uri in uris.take(3 - pendingImages.size)) {
                    val data = withContext(Dispatchers.IO) { compressImage(context, uri) }
                    total += data.length
                    if (total > MAX_IMAGE_BYTES) throw IllegalArgumentException("The selected images are too large together. Choose fewer or smaller images.")
                    selected.add(data)
                }
                pendingImages = pendingImages + selected
                info = "${selected.size} image(s) attached"
            } catch (e: Exception) { error = e.message ?: "Could not read the selected image." }
            imageBusy = false
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = accent.primary, secondary = accent.secondary, background = Ink, surface = Panel,
        onPrimary = Ink, onSecondary = Ink, onBackground = MainText, onSurface = MainText,
        outline = Color(0xFF494451)
    )) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            when {
                token.isBlank() -> AuthScreen(accent, register, { register = it }, usernameInput, { usernameInput = it },
                    passwordInput, { passwordInput = it }, busy, error, onSubmit = {
                        scope.launch {
                            busy = true; error = ""
                            try {
                                val path = if (register) "/auth/register" else "/auth/login"
                                val result = api(path, "POST", null, JSONObject().put("username", usernameInput.trim()).put("password", passwordInput))
                                token = result.getString("token"); username = result.getJSONObject("user").getString("username")
                                passwordInput = ""; persistSession()
                            } catch (e: Exception) { error = e.message ?: "Unable to authenticate." }
                            busy = false
                        }
                    })
                screen == "history" -> HistoryScreen(accent, conversations.filter { it.title.contains(historyQuery, true) },
                    historyQuery, { historyQuery = it }, busy, error, onBack = { navigateBack() }, onNew = { newChat() },
                    onRefresh = { scope.launch { busy = true; error = ""; try { loadHistory() } catch (e: Exception) { error = e.message ?: "History unavailable." }; busy = false } },
                    onOpen = { id -> scope.launch { busy = true; try { openConversation(id) } catch (e: Exception) { error = e.message ?: "Could not open conversation." }; busy = false } })
                screen == "settings" -> SettingsScreen(accent, username, selectedTheme, selectedTimezone,
                    onTheme = { selectedTheme = it; persistSession() }, onTimezone = { selectedTimezone = it; persistSession() }, onBack = { screen = "chat" },
                    onDocs = { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOCS_URL))) } catch (_: Exception) { error = "Could not open API documentation." } },
                    onLogout = { scope.launch { try { api("/auth/logout", "POST", token) } catch (_: Exception) {}; prefs.edit().clear().apply(); token = ""; username = ""; conversationId = ""; messages.clear(); pendingImages = emptyList(); backStack.clear(); screen = "chat" } })
                else -> ChatScreen(accent, username, messages, prompt, pendingImages, imageBusy,
                    onPrompt = { prompt = it }, onAttach = { if (!imageBusy && pendingImages.size < 3) imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onRemoveImage = { index -> pendingImages = pendingImages.toMutableList().also { it.removeAt(index) } },
                    busy = busy, error = error, info = info,
                    onHistory = { scope.launch { busy = true; error = ""; try { loadHistory(); navigateTo("history") } catch (e: Exception) { error = e.message ?: "Could not load history." }; busy = false } },
                    onSettings = { navigateTo("settings") }, onNewChat = { newChat() },
                    onCopy = { text -> val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager; cm.setPrimaryClip(ClipData.newPlainText("ShabGPT response", text)); info = "Copied to clipboard" },
                    onShare = { text -> val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }; context.startActivity(Intent.createChooser(send, "Share response")) },
                    onRetry = {
                        val last = messages.lastOrNull { it.role == "user" }
                        if (last != null) {
                            messages.remove(last); prompt = last.content; pendingImages = last.images
                            scope.launch { sendMessage() }
                        }
                    },
                    onSend = { scope.launch { sendMessage() } })
            }
        }
    }
}

@Composable
private fun AuthScreen(accent: AccentTheme, register: Boolean, onRegister: (Boolean) -> Unit,
    username: String, onUsername: (String) -> Unit, password: String, onPassword: (String) -> Unit,
    busy: Boolean, error: String, onSubmit: () -> Unit) {
    // Android system Back returns from registration to sign-in instead of closing the app.
    BackHandler(enabled = register) { onRegister(false) }

    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Panel2,
        unfocusedContainerColor = Panel,
        disabledContainerColor = Panel,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        cursorColor = accent.primary,
        focusedTextColor = MainText,
        unfocusedTextColor = MainText,
        focusedPlaceholderColor = Muted,
        unfocusedPlaceholderColor = Muted
    )

    Column(
        Modifier.fillMaxSize().background(Ink).imePadding()
            .padding(horizontal = 26.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(48, accent)
            Spacer(Modifier.width(13.dp))
            Column {
                Text("ShabGPT", color = MainText, fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
                Text("BEYOND THE EXISTING", color = accent.primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
            }
        }
        Spacer(Modifier.height(46.dp))
        Text(
            if (register) "Create your account" else "Welcome back",
            color = MainText, fontSize = 30.sp, lineHeight = 36.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (register) "A space for your ideas starts here." else "Sign in to continue your conversations.",
            color = Muted, fontSize = 14.sp
        )
        Spacer(Modifier.height(30.dp))

        Text("USERNAME", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        TextField(
            value = username, onValueChange = onUsername,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Your username") },
            singleLine = true,
            shape = RoundedCornerShape(17.dp),
            colors = fieldColors,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )
        Spacer(Modifier.height(19.dp))
        Text("PASSWORD", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        TextField(
            value = password, onValueChange = onPassword,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Your password") },
            singleLine = true,
            shape = RoundedCornerShape(17.dp),
            colors = fieldColors,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
        )
        if (register) {
            Spacer(Modifier.height(13.dp))
            Text("3–24 characters for username · 10–128 for password", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        }
        if (error.isNotBlank()) {
            Spacer(Modifier.height(15.dp))
            Text(error, color = Danger, fontSize = 13.sp, lineHeight = 19.sp)
        }
        Spacer(Modifier.height(25.dp))
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            enabled = !busy && username.isNotBlank() && password.isNotBlank(),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent.primary, contentColor = Ink)
        ) {
            Text(
                if (busy) "Please wait…" else if (register) "Create account" else "Sign in",
                fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(16.dp))
        TextButton(
            onClick = { onRegister(!register) },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(
                if (register) "Already have an account?  Sign in" else "New to ShabGPT?  Create an account",
                color = accent.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ChatScreen(accent: AccentTheme, username: String, messages: List<ChatMessage>, prompt: String,
    pendingImages: List<String>, imageBusy: Boolean, onPrompt: (String) -> Unit, onAttach: () -> Unit,
    onRemoveImage: (Int) -> Unit, busy: Boolean, error: String, info: String,
    onHistory: () -> Unit, onSettings: () -> Unit, onNewChat: () -> Unit,
    onCopy: (String) -> Unit, onShare: (String) -> Unit, onRetry: () -> Unit, onSend: () -> Unit) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(messages.size, busy) {
        val count = messages.size + if (busy) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF101014)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onHistory, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Default.Menu, "Conversation history", tint = Muted, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f).padding(start = 5.dp)) {
                Text("ShabGPT", color = MainText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
                Text("Beyond the Existing", color = Muted, fontSize = 10.sp)
            }
            IconButton(onClick = onNewChat, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Default.Add, "New chat", tint = MainText, modifier = Modifier.size(23.dp))
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Default.Settings, "Settings", tint = Muted, modifier = Modifier.size(20.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))

        if (messages.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("What would you like to work on?", color = MainText, fontSize = 27.sp,
                    lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text("Ask a question, explore an idea, or continue building something.",
                    color = Muted, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 320.dp))
                Spacer(Modifier.height(28.dp))
                val suggestions = listOf(
                    "Explain a complex topic",
                    "Plan a project",
                    "Explore a new idea",
                    "Improve a piece of writing"
                )
                suggestions.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().widthIn(max = 560.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { suggestion ->
                            Surface(
                                Modifier.weight(1f).padding(vertical = 4.dp).clickable { onPrompt(suggestion) },
                                color = Color(0xFF15151B), shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.075f))
                            ) {
                                Text(suggestion, color = MainText, fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp, lineHeight = 17.sp,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp))
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                items(messages) { msg ->
                    val isUser = msg.role == "user"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                        Column(
                            Modifier.widthIn(max = 720.dp).fillMaxWidth(if (isUser) 0.88f else 0.96f),
                            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                        ) {
                            if (isUser) {
                                Surface(color = Color(0xFF24212B), shape = RoundedCornerShape(20.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.07f))) {
                                    Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                                        if (msg.images.isNotEmpty()) {
                                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                msg.images.forEach { data ->
                                                    val bitmap = remember(data) { decodeDataUrl(data) }
                                                    if (bitmap != null) Image(bitmap.asImageBitmap(), "Attached image",
                                                        Modifier.size(142.dp).background(Panel2, RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                                }
                                            }
                                            if (msg.content.isNotBlank()) Spacer(Modifier.height(9.dp))
                                        }
                                        if (msg.content.isNotBlank()) SelectionContainer {
                                            Text(msg.content, color = MainText, fontSize = 14.sp, lineHeight = 22.sp)
                                        }
                                    }
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 7.dp)) {
                                    Box(Modifier.size(7.dp).background(accent.primary, CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Text("ShabGPT", color = MainText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                                if (msg.images.isNotEmpty()) {
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        msg.images.forEach { data ->
                                            val bitmap = remember(data) { decodeDataUrl(data) }
                                            if (bitmap != null) Image(bitmap.asImageBitmap(), "Attached image",
                                                Modifier.size(142.dp).background(Panel2, RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                        }
                                    }
                                }
                                if (msg.content.isNotBlank()) MarkdownText(msg.content, accent)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 5.dp)) {
                                    TextButton(onClick = { onCopy(msg.content) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) {
                                        Icon(Icons.Default.ContentCopy, null, Modifier.size(13.dp), tint = Muted)
                                        Spacer(Modifier.width(5.dp)); Text("Copy", fontSize = 11.sp, color = Muted)
                                    }
                                    TextButton(onClick = { onShare(msg.content) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) {
                                        Icon(Icons.Default.Share, null, Modifier.size(13.dp), tint = Muted)
                                        Spacer(Modifier.width(5.dp)); Text("Share", fontSize = 11.sp, color = Muted)
                                    }
                                }
                            }
                        }
                    }
                }
                if (busy) {
                    item {
                        Row(Modifier.padding(start = 2.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = accent.primary, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("ShabGPT is thinking…", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (error.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error, color = Danger, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) { Icon(Icons.Default.Refresh, null); Text("Retry") }
            }
        }
        if (info.isNotBlank()) {
            Text(info, color = accent.secondary, fontSize = 10.sp, modifier = Modifier.padding(start = 17.dp, bottom = 4.dp))
        }
        if (pendingImages.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF0D0D11)).horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                pendingImages.forEachIndexed { index, data ->
                    Box {
                        val bitmap = remember(data) { decodeDataUrl(data) }
                        if (bitmap != null) Image(bitmap.asImageBitmap(), "Image attachment preview",
                            Modifier.size(64.dp).background(Panel2, RoundedCornerShape(11.dp)), contentScale = ContentScale.Crop)
                        IconButton(onClick = { onRemoveImage(index) },
                            modifier = Modifier.align(Alignment.TopEnd).size(23.dp).background(Ink.copy(alpha = 0.9f), CircleShape)) {
                            Icon(Icons.Default.Close, "Remove image", tint = MainText, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(Color(0xFF0D0D11)).padding(horizontal = 13.dp, vertical = 10.dp)) {
            Surface(
                color = Color(0xFF19191F), shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    IconButton(onClick = onAttach, enabled = !busy && !imageBusy && pendingImages.size < 3,
                        modifier = Modifier.size(48.dp)) {
                        if (imageBusy) CircularProgressIndicator(Modifier.size(18.dp), color = accent.primary, strokeWidth = 2.dp)
                        else Icon(Icons.Default.AttachFile, "Attach images", tint = Muted, modifier = Modifier.size(21.dp))
                    }
                    TextField(
                        value = prompt, onValueChange = onPrompt, modifier = Modifier.weight(1f),
                        placeholder = { Text("Message ShabGPT", color = Muted, fontSize = 14.sp) },
                        maxLines = 5, shape = RoundedCornerShape(20.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent, cursorColor = accent.primary,
                            focusedTextColor = MainText, unfocusedTextColor = MainText
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                    )
                    FilledIconButton(
                        onClick = onSend, enabled = !busy && (prompt.isNotBlank() || pendingImages.isNotEmpty()),
                        modifier = Modifier.padding(end = 7.dp, bottom = 7.dp).size(42.dp), shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = accent.primary, contentColor = Ink,
                            disabledContainerColor = Color(0xFF34313A), disabledContentColor = Color(0xFF77717F)
                        )
                    ) { Icon(Icons.Default.Send, "Send message", modifier = Modifier.size(18.dp)) }
                }
            }
            Spacer(Modifier.height(7.dp))
            Text("ShabGPT may make mistakes. Verify important information.",
                color = Color(0xFF77717F), fontSize = 9.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MarkdownText(markdown: String, accent: AccentTheme) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    AndroidView(modifier = Modifier.fillMaxWidth(), factory = { TextView(it).apply {
        textSize = 15f; setTextColor(android.graphics.Color.rgb(248, 246, 252)); setLinkTextColor(accent.primary.toArgb())
        setLineSpacing(0f, 1.22f); setTextIsSelectable(true); movementMethod = android.text.method.LinkMovementMethod.getInstance()
    } }, update = { view -> view.setTextColor(android.graphics.Color.rgb(248, 246, 252)); view.setLinkTextColor(accent.primary.toArgb()); markwon.setMarkdown(view, markdown) })
}

@Composable
private fun HistoryScreen(accent: AccentTheme, conversations: List<Conversation>, query: String, onQuery: (String) -> Unit,
    busy: Boolean, error: String, onBack: () -> Unit, onNew: () -> Unit, onRefresh: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = MainText) }
            Text("Your conversations", Modifier.weight(1f), color = MainText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh history", tint = Muted) }
            IconButton(onClick = onNew) { Icon(Icons.Default.Add, "New chat", tint = accent.primary) }
        }
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(14.dp), placeholder = { Text("Search conversations") }, singleLine = true)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent.primary)
        if (error.isNotBlank()) Text(error, color = Danger, modifier = Modifier.padding(16.dp))
        if (conversations.isEmpty() && !busy) Text("No saved conversations yet.", color = Muted, modifier = Modifier.padding(22.dp))
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(conversations) { item ->
                Surface(Modifier.fillMaxWidth().clickable { onOpen(item.id) }, color = Panel, shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF37313F))) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null, tint = accent.primary); Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) { Text(item.title, color = MainText, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium); if (item.updated.isNotBlank()) Text(item.updated, color = Muted, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(accent: AccentTheme, username: String, selectedTheme: String, selectedTimezone: String, onTheme: (String) -> Unit,
    onTimezone: (String) -> Unit, onBack: () -> Unit, onDocs: () -> Unit, onLogout: () -> Unit) {
    var timezoneDialog by remember { mutableStateOf(false) }
    var timezoneQuery by remember { mutableStateOf("") }
    val timezoneOptions = remember { java.util.TimeZone.getAvailableIDs().distinct().sorted() }
    val filteredTimezones = remember(timezoneOptions, timezoneQuery) {
        if (timezoneQuery.isBlank()) timezoneOptions
        else timezoneOptions.filter { it.contains(timezoneQuery.trim(), ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize().background(Ink).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = MainText) }; Text("Settings", color = MainText, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(24.dp)); Text("ACCOUNT", color = accent.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp))
        Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(17.dp)) { Text("Signed in as", color = Muted, fontSize = 12.sp); Text(username, color = MainText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) } }
        Spacer(Modifier.height(24.dp)); Text("APPEARANCE", color = accent.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("Choose your ShabGPT accent", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp, bottom = 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            AccentThemes.forEach { theme ->
                val chosen = selectedTheme == theme.name
                Column(Modifier.weight(1f).clickable { onTheme(theme.name) }.background(if (chosen) Panel2 else Panel, RoundedCornerShape(14.dp)).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(27.dp).background(theme.primary, CircleShape)); Spacer(Modifier.height(7.dp))
                    Text(theme.name, color = if (chosen) MainText else Muted, fontSize = 11.sp, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.height(24.dp)); Text("REGIONAL PREFERENCES", color = accent.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("Choose the timezone ShabGPT should use when answering questions about local time.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp, bottom = 10.dp))
        Surface(Modifier.fillMaxWidth().clickable { timezoneQuery = ""; timezoneDialog = true }, color = Panel, shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Time zone", color = MainText, fontWeight = FontWeight.Medium); Text(selectedTimezone, color = Muted, fontSize = 12.sp) }
                Text("Change", color = accent.primary, fontSize = 13.sp)
            }
        }
        if (timezoneDialog) AlertDialog(
            onDismissRequest = { timezoneDialog = false },
            title = { Text("Choose time zone") },
            text = {
                Column {
                    OutlinedTextField(
                        value = timezoneQuery,
                        onValueChange = { timezoneQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Search time zones") },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                        trailingIcon = {
                            if (timezoneQuery.isNotEmpty()) {
                                IconButton(onClick = { timezoneQuery = "" }) {
                                    Icon(Icons.Default.Close, "Clear search", tint = Muted)
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MainText,
                            unfocusedTextColor = MainText,
                            focusedBorderColor = accent.primary,
                            unfocusedBorderColor = Color(0xFF494451),
                            cursorColor = accent.primary
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    if (filteredTimezones.isEmpty()) {
                        Text("No matching time zones", color = Muted, modifier = Modifier.padding(12.dp))
                    } else {
                        LazyColumn(Modifier.heightIn(max = 340.dp)) {
                            items(filteredTimezones) { zone ->
                                Text(zone, color = if (zone == selectedTimezone) accent.primary else MainText,
                                    modifier = Modifier.fillMaxWidth().clickable { onTimezone(zone); timezoneDialog = false }.padding(vertical = 12.dp, horizontal = 4.dp),
                                    fontWeight = if (zone == selectedTimezone) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { timezoneDialog = false }) { Text("Done") } }
        )
        Spacer(Modifier.height(24.dp)); Text("DOCUMENTATION", color = accent.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Surface(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { onDocs() }, color = Panel, shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text("API Documentation", color = MainText, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium); Text("Open ↗", color = accent.primary, fontSize = 13.sp) }
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onLogout, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out") }
        Text("ShabGPT · Native Android · 1.1", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
    }
}

@Composable
private fun BrandMark(size: Int, accent: AccentTheme) {
    Surface(Modifier.size(size.dp), color = Color.Black, shape = CircleShape) {
        Box(contentAlignment = Alignment.Center) { Text("◇", color = accent.primary, fontSize = (size * 0.72).sp, fontWeight = FontWeight.Bold); Text("◆", color = accent.secondary, fontSize = (size * 0.38).sp) }
    }
}

private fun unpackStoredContent(value: Any?): Pair<String, List<String>> {
    if (value is JSONArray) return unpackParts(value)
    if (value is String && value.trimStart().startsWith("[")) return try { unpackParts(JSONArray(value)) } catch (_: Exception) { value to emptyList() }
    return value?.toString().orEmpty() to emptyList()
}
private fun unpackParts(parts: JSONArray): Pair<String, List<String>> {
    val text = StringBuilder(); val images = mutableListOf<String>()
    for (i in 0 until parts.length()) {
        val part = parts.optJSONObject(i) ?: continue
        when (part.optString("type")) {
            "text" -> text.append(part.optString("text"))
            "image_url" -> part.optJSONObject("image_url")?.optString("url")?.takeIf { it.startsWith("data:image/") }?.let { images.add(it) }
        }
    }
    return text.toString() to images
}
private fun decodeDataUrl(dataUrl: String): Bitmap? = try {
    val comma = dataUrl.indexOf(','); if (comma < 0) null else {
        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT); BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
} catch (_: Exception) { null }

private fun compressImage(context: Context, uri: Uri): String {
    val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    if (length > 5L * 1024L * 1024L) throw IllegalArgumentException("Each selected image must be 5 MB or smaller.")
    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        ?: throw IllegalArgumentException("This image format could not be opened.")
    val scale = minOf(1f, 1280f / maxOf(bitmap.width, bitmap.height))
    val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
    val output = ByteArrayOutputStream(); resized.compress(Bitmap.CompressFormat.JPEG, 76, output)
    if (resized !== bitmap) resized.recycle(); bitmap.recycle()
    return "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}

private suspend fun api(path: String, method: String, token: String?, body: JSONObject? = null): JSONObject =
    withContext(Dispatchers.IO) {
        val connection = URL(API + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method; connection.connectTimeout = 20000; connection.readTimeout = 90000
            connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("User-Agent", "ShabGPT-Android/1.1")
            if (!token.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            if (method == "POST") {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(responseText.ifBlank { "{}" }) } catch (_: Exception) { JSONObject().put("error", responseText.ifBlank { "Unexpected server response." }) }
            if (code !in 200..299) throw IllegalStateException(json.optString("error").ifBlank { "Request failed (HTTP $code)." })
            json
        } finally { connection.disconnect() }
    }
