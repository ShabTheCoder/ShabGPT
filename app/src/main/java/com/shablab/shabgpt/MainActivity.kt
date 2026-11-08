package com.shablab.shabgpt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Ink = Color(0xFF07070A)
private val Panel = Color(0xFF17151D)
private val Panel2 = Color(0xFF211D29)
private val Lavender = Color(0xFFC8AEFF)
private val Mint = Color(0xFF9CE8C2)
private val MainText = Color(0xFFF8F6FC)
private val Muted = Color(0xFFBDB8C7)
private val Danger = Color(0xFFFF9BA8)
private const val API = "https://shabgptapi.shabarish4737.workers.dev"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 7, 10)
        window.navigationBarColor = android.graphics.Color.rgb(7, 7, 10)
        setContent { ShabGPTApp(this) }
    }
}

private data class ChatMessage(val id: String?, val role: String, val content: String, val time: String = "")
private data class Conversation(val id: String, val title: String, val updated: String = "")

@Composable
private fun ShabGPTApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("shabgpt", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", "").orEmpty()) }
    var username by remember { mutableStateOf(prefs.getString("username", "").orEmpty()) }
    var conversationId by remember { mutableStateOf(prefs.getString("conversation_id", "").orEmpty()) }
    var register by remember { mutableStateOf(false) }
    var usernameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var screen by remember { mutableStateOf("chat") }
    var historyQuery by remember { mutableStateOf("") }
    var conversations by remember { mutableStateOf(listOf<Conversation>()) }
    var darkMode by remember { mutableStateOf(true) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    fun persistSession() {
        prefs.edit().putString("token", token).putString("username", username)
            .putString("conversation_id", conversationId).apply()
    }
    fun newChat() {
        conversationId = ""
        messages.clear()
        prompt = ""
        error = ""
        info = ""
        persistSession()
        screen = "chat"
    }
    suspend fun loadHistory() {
        val result = api("/v1/conversations", "GET", token)
        val array = result.optJSONArray("conversations") ?: result.optJSONArray("data") ?: JSONArray()
        val list = mutableListOf<Conversation>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isNotBlank()) list.add(Conversation(
                id,
                item.optString("title").ifBlank { item.optString("name").ifBlank { "New conversation" } },
                item.optString("updated_at")
            ))
        }
        conversations = list
    }
    suspend fun openConversation(id: String) {
        val result = api("/v1/conversations/$id", "GET", token)
        val array = result.optJSONArray("messages") ?: JSONArray()
        messages.clear()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val role = item.optString("role")
            val contentValue = item.opt("content")
            val content = when (contentValue) {
                is String -> contentValue
                is JSONArray -> buildString {
                    for (j in 0 until contentValue.length()) {
                        val part = contentValue.optJSONObject(j)
                        if (part != null) append(part.optString("text"))
                    }
                }
                else -> contentValue?.toString().orEmpty()
            }
            if (role == "user" || role == "assistant") messages.add(ChatMessage(item.optString("id"), role, content, item.optString("created_at")))
        }
        conversationId = id
        persistSession()
        screen = "chat"
        error = ""
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Lavender, secondary = Mint, background = Ink, surface = Panel,
        onPrimary = Ink, onSecondary = Ink, onBackground = MainText, onSurface = MainText,
        outline = Color(0xFF494451)
    )) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            if (token.isBlank()) {
                AuthScreen(
                    register, { register = it }, usernameInput, { usernameInput = it },
                    passwordInput, { passwordInput = it }, busy, error,
                    onSubmit = {
                        scope.launch {
                            busy = true; error = ""
                            try {
                                val path = if (register) "/auth/register" else "/auth/login"
                                val result = api(path, "POST", null, JSONObject()
                                    .put("username", usernameInput.trim()).put("password", passwordInput))
                                token = result.getString("token")
                                username = result.getJSONObject("user").getString("username")
                                passwordInput = ""
                                persistSession()
                            } catch (e: Exception) { error = e.message ?: "Unable to authenticate." }
                            busy = false
                        }
                    }
                )
            } else if (screen == "history") {
                HistoryScreen(
                    conversations.filter { it.title.contains(historyQuery, true) },
                    historyQuery, { historyQuery = it }, busy, error,
                    onBack = { screen = "chat" },
                    onNew = { newChat() },
                    onRefresh = {
                        scope.launch {
                            busy = true; error = ""
                            try { loadHistory() } catch (e: Exception) { error = e.message ?: "History unavailable." }
                            busy = false
                        }
                    },
                    onOpen = { id ->
                        scope.launch {
                            busy = true
                            try { openConversation(id) } catch (e: Exception) { error = e.message ?: "Could not open conversation." }
                            busy = false
                        }
                    }
                )
            } else if (screen == "settings") {
                SettingsScreen(username, darkMode, { darkMode = it }, onBack = { screen = "chat" }, onLogout = {
                    scope.launch {
                        try { api("/auth/logout", "POST", token) } catch (_: Exception) {}
                        prefs.edit().clear().apply()
                        token = ""; username = ""; conversationId = ""; messages.clear(); screen = "chat"
                    }
                })
            } else {
                ChatScreen(
                    username = username, messages = messages, prompt = prompt,
                    onPrompt = { prompt = it }, busy = busy, error = error, info = info,
                    onHistory = {
                        scope.launch {
                            busy = true; error = ""
                            try { loadHistory(); screen = "history" }
                            catch (e: Exception) { error = e.message ?: "Could not load history." }
                            busy = false
                        }
                    },
                    onSettings = { screen = "settings" },
                    onNewChat = { newChat() },
                    onCopy = { text ->
                        clipboard.setText(AnnotatedString(text))
                        info = "Copied to clipboard"
                    },
                    onShare = { text ->
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, "Share response"))
                    },
                    onRetry = {
                        val last = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
                        if (last.isNotBlank()) prompt = last
                    },
                    onSend = {
                        val text = prompt.trim()
                        if (text.isNotEmpty() && !busy) {
                            prompt = ""; error = ""; info = ""
                            messages.add(ChatMessage(null, "user", text))
                            scope.launch {
                                busy = true
                                try {
                                    val arr = JSONArray()
                                    messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
                                    val body = JSONObject().put("messages", arr).put("max_tokens", 768)
                                    if (conversationId.isNotBlank()) body.put("conversation_id", conversationId)
                                    val result = api("/v1/chat/completions", "POST", token, body)
                                    val answer = result.getJSONArray("choices").getJSONObject(0)
                                        .getJSONObject("message").getString("content")
                                    conversationId = result.optString("conversation_id", conversationId)
                                    persistSession()
                                    messages.add(ChatMessage(result.optString("assistant_message_id").ifBlank { null }, "assistant", answer))
                                } catch (e: Exception) {
                                    error = e.message ?: "Message failed. Check your connection and try again."
                                }
                                busy = false
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AuthScreen(
    register: Boolean, onRegister: (Boolean) -> Unit,
    username: String, onUsername: (String) -> Unit,
    password: String, onPassword: (String) -> Unit,
    busy: Boolean, error: String, onSubmit: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Ink).imePadding().padding(horizontal = 26.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(48)
            Spacer(Modifier.width(12.dp))
            Text("ShabGPT", color = MainText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Text("Beyond the Existing.", color = Lavender, fontSize = 15.sp)
        Spacer(Modifier.height(34.dp))
        Text(if (register) "Create your account" else "Welcome back", color = MainText, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(username, onUsername, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, onPassword, Modifier.fillMaxWidth(), label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), singleLine = true)
        if (register) {
            Spacer(Modifier.height(8.dp))
            Text("Username: 3–24 letters, numbers or underscores. Password: 10–128 characters.", color = Muted, fontSize = 12.sp)
        }
        if (error.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(error, color = Danger, fontSize = 13.sp) }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onSubmit, Modifier.fillMaxWidth().height(54.dp), enabled = !busy && username.isNotBlank() && password.isNotBlank()) {
            Text(if (busy) "Please wait…" else if (register) "Create account" else "Sign in", fontSize = 16.sp)
        }
        TextButton(onClick = { onRegister(!register) }, Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (register) "Already have an account? Sign in" else "Create an account", color = Lavender)
        }
    }
}

@Composable
private fun ChatScreen(
    username: String, messages: List<ChatMessage>, prompt: String, onPrompt: (String) -> Unit,
    busy: Boolean, error: String, info: String,
    onHistory: () -> Unit, onSettings: () -> Unit, onNewChat: () -> Unit,
    onCopy: (String) -> Unit, onShare: (String) -> Unit, onRetry: () -> Unit, onSend: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Ink).imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onHistory) { Icon(Icons.Default.Menu, "Open conversation history", tint = MainText) }
            BrandMark(34)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("ShabGPT", color = MainText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(username, color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = onNewChat) { Icon(Icons.Default.Add, "New chat", tint = MainText) }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = Muted) }
        }
        if (messages.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
                Text("Beyond the Existing.", color = MainText, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("What would you like to explore today?", color = Muted, fontSize = 16.sp)
                Spacer(Modifier.height(24.dp))
                listOf("Explain a complex idea simply", "Help me plan a project", "Explore something unexpected", "Write or improve something").forEach { suggestion ->
                    Surface(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onPrompt(suggestion) },
                        color = Panel, shape = RoundedCornerShape(17.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF35313D))
                    ) {
                        Row(Modifier.padding(horizontal = 17.dp, vertical = 19.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(suggestion, color = MainText, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("↗", color = Lavender, fontSize = 20.sp)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(messages) { msg ->
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = if (msg.role == "user") Alignment.End else Alignment.Start
                    ) {
                        Text(if (msg.role == "user") "You" else "ShabGPT", color = if (msg.role == "user") Lavender else Mint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(5.dp))
                        Surface(
                            color = if (msg.role == "user") Panel2 else Panel,
                            shape = RoundedCornerShape(18.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF302B38)),
                            modifier = Modifier.fillMaxWidth(if (msg.role == "user") 0.94f else 1f)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(msg.content, color = MainText, fontSize = 15.sp, lineHeight = 23.sp)
                                }
                                if (msg.role == "assistant") {
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { onCopy(msg.content) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                            Icon(Icons.Default.ContentCopy, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("Copy", fontSize = 12.sp)
                                        }
                                        TextButton(onClick = { onShare(msg.content) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                            Icon(Icons.Default.Share, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("Share", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (busy) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Lavender, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("ShabGPT is thinking…", color = Muted, fontSize = 13.sp)
                    }
                }
            }
        }
        if (error.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error, color = Danger, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) { Icon(Icons.Default.Refresh, null); Text("Retry") }
            }
        }
        if (info.isNotBlank()) Text(info, color = Mint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = prompt, onValueChange = onPrompt, modifier = Modifier.weight(1f),
                placeholder = { Text("Message ShabGPT…", color = Muted) },
                maxLines = 5, shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = onSend, enabled = !busy && prompt.isNotBlank(),
                modifier = Modifier.size(52.dp)
            ) { Icon(Icons.Default.Send, "Send message") }
        }
        Text("ShabGPT can make mistakes. Check important information.", color = Color(0xFF77717F), fontSize = 10.sp, modifier = Modifier.fillMaxWidth().background(Panel).padding(bottom = 7.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun HistoryScreen(
    conversations: List<Conversation>, query: String, onQuery: (String) -> Unit,
    busy: Boolean, error: String, onBack: () -> Unit, onNew: () -> Unit,
    onRefresh: () -> Unit, onOpen: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = MainText) }
            Text("Your conversations", Modifier.weight(1f), color = MainText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh history", tint = Muted) }
            IconButton(onClick = onNew) { Icon(Icons.Default.Add, "New chat", tint = Lavender) }
        }
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(14.dp), placeholder = { Text("Search conversations") }, singleLine = true)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Lavender)
        if (error.isNotBlank()) Text(error, color = Danger, modifier = Modifier.padding(16.dp))
        if (conversations.isEmpty() && !busy) Text("No saved conversations yet.", color = Muted, modifier = Modifier.padding(22.dp))
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(conversations) { item ->
                Surface(
                    Modifier.fillMaxWidth().clickable { onOpen(item.id) },
                    color = Panel, shape = RoundedCornerShape(15.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF37313F))
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null, tint = Lavender)
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, color = MainText, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            if (item.updated.isNotBlank()) Text(item.updated, color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    username: String, dark: Boolean, onDark: (Boolean) -> Unit,
    onBack: () -> Unit, onLogout: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Ink).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = MainText) }
            Text("Settings", color = MainText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        Text("ACCOUNT", color = Lavender, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(17.dp)) {
                Text("Signed in as", color = Muted, fontSize = 12.sp)
                Text(username, color = MainText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("PREFERENCES", color = Lavender, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dark appearance", color = MainText)
                Text("ShabGPT's dark interface", color = Muted, fontSize = 12.sp)
            }
            Switch(checked = dark, onCheckedChange = onDark)
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onLogout, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) {
            Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
        }
        Text("ShabGPT · Native Android · 1.0", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
    }
}

@Composable
private fun BrandMark(size: Int) {
    Surface(Modifier.size(size.dp), color = Color.Black, shape = CircleShape) {
        Box(contentAlignment = Alignment.Center) {
            Text("✧", color = Lavender, fontSize = (size * 0.72).sp, fontWeight = FontWeight.Bold)
            Text("◆", color = Mint, fontSize = (size * 0.42).sp)
        }
    }
}

private suspend fun api(path: String, method: String, token: String?, body: JSONObject? = null): JSONObject =
    withContext(Dispatchers.IO) {
        val connection = (URL(API + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20000
            connection.readTimeout = 90000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ShabGPT-Android/1.0")
            if (!token.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
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
