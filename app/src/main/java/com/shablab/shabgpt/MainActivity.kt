package com.shablab.shabgpt

import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Ink = Color(0xFF08080B)
private val Panel = Color(0xFF17161D)
private val Lavender = Color(0xFFC6A8FF)
private val Mint = Color(0xFF98E8C1)
private val MainText = Color(0xFFF8F7FB)
private val Muted = Color(0xFFC5C1CC)
private const val API = "https://shabgptapi.shabarish4737.workers.dev"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ShabGPTApp(this) }
    }
}

private data class ChatMessage(val role: String, val content: String)

@Composable
private fun ShabGPTApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("shabgpt", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("token", null).orEmpty()) }
    var username by remember { mutableStateOf(prefs.getString("username", "").orEmpty()) }
    var register by remember { mutableStateOf(false) }
    var userField by remember { mutableStateOf("") }
    var passField by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = darkColorScheme(
        background = Ink, surface = Panel, primary = Lavender,
        secondary = Mint, onBackground = MainText, onSurface = MainText
    )) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            if (token.isBlank()) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("ShabGPT", color = MainText, fontSize = 30.sp)
                    Text("Beyond the Existing.", color = Lavender)
                    Spacer(Modifier.height(28.dp))
                    Text(if (register) "Create your account" else "Welcome back", color = MainText, fontSize = 22.sp)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(userField, { userField = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(passField, { passField = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true; error = ""
                                try {
                                    val path = if (register) "/auth/register" else "/auth/login"
                                    val result = api(path, "POST", null, JSONObject().put("username", userField).put("password", passField))
                                    token = result.getString("token")
                                    username = result.getJSONObject("user").getString("username")
                                    prefs.edit().putString("token", token).putString("username", username).apply()
                                } catch (e: Exception) { error = e.message ?: "Request failed" }
                                busy = false
                            }
                        },
                        Modifier.fillMaxWidth(), enabled = !busy && userField.isNotBlank() && passField.isNotBlank()
                    ) { Text(if (busy) "Please wait…" else if (register) "Create account" else "Sign in") }
                    TextButton(onClick = { register = !register; error = "" }) {
                        Text(if (register) "Already have an account? Sign in" else "New to ShabGPT? Create account")
                    }
                    if (error.isNotBlank()) Text(error, color = Color(0xFFFF9DA8))
                }
            } else {
                Column(Modifier.fillMaxSize().imePadding()) {
                    Row(
                        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("✦", color = Lavender, fontSize = 26.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("ShabGPT", color = MainText, fontSize = 20.sp)
                            Text(username, color = Muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                try { api("/auth/logout", "POST", token, JSONObject()) } catch (_: Exception) {}
                                prefs.edit().clear().apply(); token = ""; messages.clear()
                            }
                        }) { Icon(Icons.Default.Logout, "Sign out", tint = Muted) }
                    }
                    if (messages.isEmpty()) {
                        Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp), verticalArrangement = Arrangement.Center) {
                            Text("Beyond the Existing.", color = MainText, fontSize = 30.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("What would you like to explore today?", color = Muted, fontSize = 16.sp)
                            Spacer(Modifier.height(24.dp))
                            listOf("Explain a complex idea simply", "Help me plan a project", "Explore something unexpected").forEach {
                                OutlinedButton(onClick = { prompt = it }, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Text(it, color = MainText) }
                            }
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                            items(messages) { msg ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = if (msg.role == "user") Alignment.End else Alignment.Start) {
                                    Surface(color = if (msg.role == "user") Color(0xFF292333) else Panel, shape = RoundedCornerShape(18.dp)) {
                                        Text(msg.content, Modifier.padding(14.dp), color = MainText, fontSize = 16.sp)
                                    }
                                }
                            }
                            if (busy) item { Text("ShabGPT is thinking…", color = Lavender, modifier = Modifier.padding(12.dp)) }
                        }
                    }
                    if (error.isNotBlank()) Text(error, color = Color(0xFFFF9DA8), modifier = Modifier.padding(horizontal = 16.dp))
                    Row(Modifier.fillMaxWidth().background(Panel).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(prompt, { prompt = it }, Modifier.weight(1f), placeholder = { Text("Message ShabGPT…") }, maxLines = 5)
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = {
                                val text = prompt.trim()
                                if (text.isNotEmpty() && !busy) {
                                    prompt = ""; error = ""
                                    messages.add(ChatMessage("user", text))
                                    scope.launch {
                                        busy = true
                                        try {
                                            val arr = JSONArray()
                                            messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
                                            val result = api("/v1/chat/completions", "POST", token, JSONObject().put("messages", arr).put("max_tokens", 512))
                                            val answer = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                                            messages.add(ChatMessage("assistant", answer))
                                        } catch (e: Exception) { error = e.message ?: "Chat request failed" }
                                        busy = false
                                    }
                                }
                            }, enabled = !busy && prompt.isNotBlank()
                        ) { Icon(Icons.Default.Send, "Send") }
                    }
                }
            }
        }
    }
}

private suspend fun api(path: String, method: String, token: String?, body: JSONObject): JSONObject =
    withContext(Dispatchers.IO) {
        val connection = (URL(API + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            val json = JSONObject(text.ifBlank { "{}" })
            if (code !in 200..299) throw IllegalStateException(json.optString("error", "HTTP $code"))
            json
        } finally { connection.disconnect() }
    }
