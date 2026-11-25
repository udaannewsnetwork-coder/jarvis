package com.shadow.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.util.Linkify
import android.text.method.LinkMovementMethod
import android.widget.TextView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private data class ChatMessage(val role: String, val content: String)
private data class ProjectFile(val path: String, val content: String)

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Your assistant is standing by")
    private var draft by mutableStateOf("")
    private var listening by mutableStateOf(false)
    private var backgroundWakeEnabled by mutableStateOf(false)
    private var pendingBackgroundStart = false
    private var sending by mutableStateOf(false)
    private var endpoint by mutableStateOf("")
    private var apiToken by mutableStateOf("")
    private var responseLanguage by mutableStateOf("Auto")
    private var voiceReplies by mutableStateOf(false)
    private var speaking by mutableStateOf(false)
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var chatMessages by mutableStateOf(listOf<ChatMessage>())
    private var savedMemories by mutableStateOf(listOf<String>())
    private var speechRecognizer: SpeechRecognizer? = null
    private var generatedProjectJson by mutableStateOf("")
    private var generatedProjectName by mutableStateOf("shadow-project")
    private var projectFiles by mutableStateOf(listOf<ProjectFile>())
    private var externalPlugins by mutableStateOf(listOf<ExternalPlugin>())
    private var pluginBusy by mutableStateOf(false)

    private val exportProjectLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) exportProjectZip(uri)
    }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (pendingBackgroundStart) {
                pendingBackgroundStart = false
                requestNotificationPermissionAndStartWake()
            } else beginRecognition()
        } else {
            pendingBackgroundStart = false
            status = "Microphone permission is needed for voice input"
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> startWakeService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getPreferences(MODE_PRIVATE)
        endpoint = preferences.getString("backend_url", "").orEmpty().ifBlank { "https://shadow-ai-api.udaannewsnetwork.workers.dev" }
        val legacyToken = preferences.getString("cloud_ai_token", "").orEmpty()
        apiToken = SecureTokenStore.read(this) ?: legacyToken.also { token ->
            if (token.isNotBlank()) runCatching { SecureTokenStore.write(this, token) }
        }
        if (legacyToken.isNotBlank()) preferences.edit().remove("cloud_ai_token").apply()
        responseLanguage = preferences.getString("response_language", "Auto").orEmpty()
        voiceReplies = preferences.getBoolean("voice_replies", false)
        backgroundWakeEnabled = getSharedPreferences(BackgroundWakeService.PREFS, MODE_PRIVATE).getBoolean(BackgroundWakeService.KEY_ENABLED, false)
        generatedProjectJson = preferences.getString("generated_project_json", "").orEmpty()
        generatedProjectName = preferences.getString("generated_project_name", "shadow-project").orEmpty()
        projectFiles = parseProjectFiles(generatedProjectJson)
        externalPlugins = ExternalPlugin.restore(preferences.getString("external_plugins_v1", "[]").orEmpty())
        textToSpeech = TextToSpeech(this) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) {
                textToSpeech?.setSpeechRate(0.92f)
                textToSpeech?.setPitch(0.88f)
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        runOnUiThread { speaking = true }
                    }
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread {
                            speaking = false
                            status = "Voice playback complete"
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        runOnUiThread {
                            speaking = false
                            status = "Voice playback failed"
                        }
                    }
                })
                if (voiceReplies) status = "Voice engine ready"
            } else {
                status = "Device text-to-speech engine is unavailable"
            }
        }
        chatMessages = loadSavedMessages(preferences.getString("chat_history", "[]").orEmpty())
        savedMemories = loadSavedMemories(preferences.getString("saved_memories", "[]").orEmpty())
        if (chatMessages.isNotEmpty()) status = "Previous conversation restored"
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        setContent {
            ShadowHome(
                status = status,
                draft = draft,
                endpoint = endpoint,
                apiToken = apiToken,
                responseLanguage = responseLanguage,
                voiceReplies = voiceReplies,
                backgroundWakeEnabled = backgroundWakeEnabled,
                speaking = speaking,
                messages = chatMessages,
                memories = savedMemories,
                listening = listening,
                sending = sending,
                onDraftChange = { draft = it },
                onEndpointChange = {
                    endpoint = it
                    getPreferences(MODE_PRIVATE).edit().putString("backend_url", it).apply()
                },
                onApiTokenChange = {
                    apiToken = it
                    runCatching { SecureTokenStore.write(this, it) }
                        .onFailure { status = "Secure token storage unavailable on this device" }
                },
                onLanguageChange = {
                    responseLanguage = it
                    getPreferences(MODE_PRIVATE).edit().putString("response_language", it).apply()
                },
                onBackgroundWakeChange = { setBackgroundWakeServiceEnabled(it) },
                onVoiceRepliesChange = {
                    voiceReplies = it
                    getPreferences(MODE_PRIVATE).edit().putBoolean("voice_replies", it).apply()
                    if (!it) stopSpeaking()
                    status = if (it) "Voice replies enabled" else "Voice replies disabled"
                },
                onSpeakLatest = { speakLatestReply() },
                onStopSpeaking = { stopSpeaking() },
                onClearChat = { clearConversation() },
                onAddMemory = { addMemory(it) },
                onDeleteMemory = { deleteMemory(it) },
                onVoiceTap = { requestVoiceInput() },
                onSend = { sendMessage() },
                onResearch = { researchWeb() },
                hasExportableProject = generatedProjectJson.isNotBlank(),
                projectFiles = projectFiles,
                onExportProject = { exportProjectLauncher.launch("${generatedProjectName}.zip") },
                onSaveProjectFile = { path, content -> saveProjectFile(path, content) },
                onCheckProject = { validateProjectSource() },
                externalPlugins = externalPlugins, pluginStatus = status, pluginBusy = pluginBusy,
                onImportPlugin = { url, token -> importExternalPlugin(url, token) },
                onRunPlugin = { id, actionId, payload -> runExternalPluginAction(id, actionId, payload) },
                onDisconnectPlugin = { id -> disconnectExternalPlugin(id) }
            )
        }
    }

    private fun saveExternalPlugins() {
        getPreferences(MODE_PRIVATE).edit().putString("external_plugins_v1",
            org.json.JSONArray().apply { externalPlugins.forEach { put(it.toJson()) } }.toString()).apply()
    }

    private fun importExternalPlugin(manifestUrl: String, token: String) {
        if (pluginBusy) return
        val parsedUrl = runCatching { java.net.URI(manifestUrl) }.getOrNull()
        if (parsedUrl == null || parsedUrl.scheme != "https" || parsedUrl.host.isNullOrBlank() || parsedUrl.userInfo != null) {
            status = "Manifest URL must be a valid HTTPS address"
            return
        }
        pluginBusy = true
        status = "Fetching plugin manifest…"
        lifecycleScope.launch {
            try {
                val plugin = withContext(Dispatchers.IO) {
                    val connection = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        instanceFollowRedirects = false
                        connectTimeout = 10000
                        readTimeout = 15000
                        setRequestProperty("Accept", "application/json")
                    }
                    try {
                        val code = connection.responseCode
                        require(code in 200..299) { "Manifest server returned HTTP $code (redirects blocked)" }
                        val raw = connection.inputStream.bufferedReader().use { reader ->
                            val out = StringBuilder()
                            val buffer = CharArray(4096)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                require(out.length + count <= 64000) { "Manifest exceeds 64 KB" }
                                out.append(buffer, 0, count)
                            }
                            out.toString()
                        }
                        ExternalPlugin.parse(raw)
                    } finally { connection.disconnect() }
                }
                externalPlugins = (externalPlugins.filterNot { it.id == plugin.id } + plugin).takeLast(20)
                saveExternalPlugins()
                PluginSecretStore.write(this@MainActivity, plugin.id, token.trim())
                status = "Connected " + plugin.name + " • " + plugin.actions.size + " actions"
            } catch (error: Exception) {
                status = "Plugin connect failed: " + (error.message ?: "invalid manifest").take(150)
            } finally { pluginBusy = false }
        }
    }

    private fun runExternalPluginAction(pluginId: String, actionId: String, payload: String) {
        if (pluginBusy) return
        val plugin = externalPlugins.firstOrNull { it.id == pluginId }
        val action = plugin?.actions?.firstOrNull { it.id == actionId }
        if (plugin == null || action == null) {
            status = "Plugin or action is no longer installed"
            return
        }
        val body = runCatching { org.json.JSONObject(payload) }.getOrNull()
        if (body == null || payload.length > 8000) {
            status = "Action input must be valid JSON under 8 KB"
            return
        }
        pluginBusy = true
        status = "Running plugin action…"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val target = URL(plugin.baseUrl + action.path)
                    require(target.protocol == "https" && target.host == URL(plugin.baseUrl).host) {
                        "Plugin action must stay on its declared HTTPS host"
                    }
                    val connection = (target.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        instanceFollowRedirects = false
                        connectTimeout = 10000
                        readTimeout = 15000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json, text/plain")
                        PluginSecretStore.read(this@MainActivity, pluginId)?.takeIf { it.isNotBlank() }?.let {
                            setRequestProperty("Authorization", "Bearer " + it)
                        }
                    }
                    try {
                        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                        val code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        val raw = stream?.bufferedReader()?.use { reader ->
                            val out = StringBuilder()
                            val buffer = CharArray(2048)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                require(out.length + count <= 32000) { "Plugin response exceeds 32 KB" }
                                out.append(buffer, 0, count)
                            }
                            out.toString()
                        }.orEmpty()
                        require(code in 200..299) { "Plugin returned HTTP " + code + ": " + raw.take(300) }
                        raw.ifBlank { "Plugin action completed successfully (empty response)." }
                    } finally { connection.disconnect() }
                }
                chatMessages = (chatMessages + ChatMessage("assistant",
                    "PLUGIN • " + plugin.name + " / " + action.name + "\n\n" + result)).takeLast(30)
                saveMessages(chatMessages)
                status = "Plugin action completed • " + plugin.name
            } catch (error: Exception) {
                status = "Plugin action failed: " + (error.message ?: "unknown error").take(180)
            } finally { pluginBusy = false }
        }
    }

    private fun disconnectExternalPlugin(pluginId: String) {
        externalPlugins = externalPlugins.filterNot { it.id == pluginId }
        PluginSecretStore.clear(this, pluginId)
        saveExternalPlugins()
        status = "Plugin disconnected and its saved token cleared"
    }

    private fun setBackgroundWakeServiceEnabled(enabled: Boolean) {
        if (!enabled) {
            backgroundWakeEnabled = false
            getSharedPreferences(BackgroundWakeService.PREFS, MODE_PRIVATE).edit()
                .putBoolean(BackgroundWakeService.KEY_ENABLED, false).apply()
            stopService(Intent(this, BackgroundWakeService::class.java).setAction(BackgroundWakeService.ACTION_STOP))
            status = "Background wake word stopped"
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingBackgroundStart = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        requestNotificationPermissionAndStartWake()
    }

    private fun requestNotificationPermissionAndStartWake() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startWakeService()
    }

    private fun startWakeService() {
        val intent = Intent(this, BackgroundWakeService::class.java).setAction(BackgroundWakeService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            backgroundWakeEnabled = true
            status = "SHADOW background wake word starting • check its notification"
        } catch (error: Exception) {
            backgroundWakeEnabled = false
            status = error.message?.take(140) ?: "Could not start background listening"
        }
    }

    private fun requestVoiceInput() {
        if (listening) {
            speechRecognizer?.cancel()
            listening = false
            status = "Voice listening stopped"
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status = "Speech recognition is not available on this device"
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            beginRecognition()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun beginRecognition() {
        runCatching {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        listening = true
                        status = "Listening… speak now"
                    }
                    override fun onBeginningOfSpeech() { status = "Hearing your voice…" }
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        listening = false
                        status = "Processing your voice…"
                    }
                    override fun onError(error: Int) {
                        listening = false
                        status = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Tap the orb to try again."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
                            else -> "Voice input stopped. Tap to try again."
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        listening = false
                        val phrase = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                        if (phrase.isNotBlank()) {
                            draft = phrase
                            status = "Voice captured • review and tap Send"
                        } else {
                            status = "No speech detected. Please try again."
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                recognizer.startListening(intent)
            }
        }.onFailure {
            listening = false
            status = "Could not start voice input. Please try again."
        }
    }

    private fun sendMessage() {
        val prompt = draft.trim()
        val codingMode = prompt.startsWith("/code ", ignoreCase = true)
        val buildMode = prompt.startsWith("/build ", ignoreCase = true)
        val messageText = if (codingMode || buildMode) prompt.substringAfter(' ').trim() else prompt
        // Local assistant commands share the same chat composer; no separate screen is required.
        if (handleLocalCommand(prompt)) return
        // Web research is a mode inside the same chat composer: type /search followed by a query.
        if (prompt.startsWith("/search ", ignoreCase = true)) {
            draft = prompt.substringAfter(' ').trim()
            researchWeb()
            return
        }
        if (buildMode) {
            draft = messageText
            buildProject()
            return
        }
        val baseUrl = endpoint.trim().trimEnd('/')
        if (messageText.isBlank() || sending) return
        if (!baseUrl.startsWith("https://")) {
            status = "Add your deployed HTTPS backend URL below before chatting"
            return
        }
        if (messageText.length > 6000) {
            status = "Message is too long. Keep it under 6,000 characters."
            return
        }

        // Keep the latest 30 messages to stay within the server's context limit.
        val updated = (chatMessages + ChatMessage("user", if (codingMode) "Coding task: " + messageText else messageText)).takeLast(30)
        chatMessages = updated
        saveMessages(updated)
        draft = ""
        sending = true
        status = "SHADOW is thinking…"

        lifecycleScope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    val connection = (URL(baseUrl + "/api/chat").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = 30000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("Authorization", "Bearer $apiToken")
                    }
                    try {
                        val payload = JSONObject().put("memories", JSONArray().apply { savedMemories.forEach { put(it) } }).put("messages", JSONArray().apply {
                            updated.forEach { message ->
                                put(JSONObject().put("role", message.role).put("content", message.content))
                            }
                        }).put("language", when (responseLanguage) { "English" -> "en"; "Hindi" -> "hi"; else -> "auto" }).put("mode", if (codingMode) "coding" else "chat")
                        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        val code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        val json = runCatching { JSONObject(response) }.getOrNull()
                        if (code !in 200..299) {
                            throw IllegalStateException(json?.optString("error")?.takeIf { it.isNotBlank() }
                                ?: "Server error ($code). Check backend setup.")
                        }
                        json?.optString("reply")?.takeIf { it.isNotBlank() }
                            ?: throw IllegalStateException("The server returned an empty reply.")
                    } finally {
                        connection.disconnect()
                    }
                }
                chatMessages = chatMessages + ChatMessage("assistant", reply)
                saveMessages(chatMessages)
                status = "Connected • SHADOW replied"
                if (voiceReplies) speakText(reply)
            } catch (error: Exception) {
                chatMessages = chatMessages.dropLast(1)
                saveMessages(chatMessages)
                draft = prompt
                status = error.message?.take(180) ?: "Could not connect. Check your internet and backend URL."
            } finally {
                sending = false
            }
        }
    }

    private fun handleLocalCommand(prompt: String): Boolean {
        val command = prompt.trim()
        val lower = command.lowercase(Locale.ROOT)
        val response = when {
            lower == "/help" -> "SHADOW CHAT COMMANDS\\n\\n/search <query> — search the web\\n/code <task> — coding and debugging mode\\n/build <app idea> — generate a starter project source preview\\n/remember <fact> — save a memory on this device\\n/memories — view saved memory notes\\n/forget <exact note> — delete one saved memory\\n/help — show these commands\n\nVOICE COMMANDS (background listening)\n“Shadow, what time is it” — current time\n“Shadow, battery status” — battery level\n“Shadow, set timer for 5 minutes” — timer notification\n“Shadow, remind me in 20 minutes to drink water” — reminder\n“Shadow, volume up/down” — media volume\n“Shadow, open YouTube” — tap OPEN in notification\n“Shadow, search weather tomorrow” — tap OPEN in notification"
            lower == "/memories" -> if (savedMemories.isEmpty()) {
                "No saved memories yet. Use /remember <fact> to save one."
            } else {
                "YOUR SAVED MEMORIES\\n\\n" + savedMemories.mapIndexed { index, memory -> "${index + 1}. $memory" }.joinToString("\\n")
            }
            lower.startsWith("/remember ") -> {
                val memory = command.substringAfter(' ').trim()
                when {
                    memory.isBlank() -> "Add a fact after /remember."
                    memory.length > 300 -> "Memory is too long. Keep it under 300 characters."
                    savedMemories.any { it.equals(memory, ignoreCase = true) } -> "That memory is already saved."
                    savedMemories.size >= 30 -> "Memory is full (30 notes). Delete one with /forget <exact note> first."
                    else -> {
                        addMemory(memory)
                        "Saved on this device: $memory"
                    }
                }
            }
            lower.startsWith("/forget ") -> {
                val memory = command.substringAfter(' ').trim()
                val match = savedMemories.firstOrNull { it.equals(memory, ignoreCase = true) }
                if (match == null) "I couldn't find that exact memory. Use /memories to see saved notes."
                else {
                    deleteMemory(match)
                    "Deleted this memory from this device: $match"
                }
            }
            lower == "/remember" -> "Use /remember <fact> to save a memory."
            lower == "/forget" -> "Use /forget <exact note> to delete a saved memory."
            else -> return false
        }
        val updated = (chatMessages + ChatMessage("user", command) + ChatMessage("assistant", response)).takeLast(30)
        chatMessages = updated
        saveMessages(updated)
        draft = ""
        status = "Local command complete"
        if (voiceReplies) speakText(response)
        return true
    }


    private fun buildProject() {
        val description = draft.trim()
        val baseUrl = endpoint.trim().trimEnd('/')
        if (description.isBlank() || sending) return
        if (!baseUrl.startsWith("https://")) {
            status = "Add your deployed HTTPS backend URL before building"
            return
        }
        if (description.length > 2000) {
            status = "Project brief must be under 2,000 characters"
            return
        }
        val updated = (chatMessages + ChatMessage("user", "Build project: " + description)).takeLast(30)
        chatMessages = updated
        saveMessages(updated)
        draft = ""
        sending = true
        status = "SHADOW Builder is designing your project…"
        lifecycleScope.launch {
            try {
                val replyResult = withContext(Dispatchers.IO) {
                    val connection = (URL(baseUrl + "/api/build").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = 90000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("Authorization", "Bearer $apiToken")
                    }
                    try {
                        val payload = JSONObject()
                            .put("description", description)
                            .put("language", if (responseLanguage == "English") "en" else "hi")
                        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        val code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        val parsedJson = runCatching { JSONObject(raw) }.getOrNull()
                        if (code !in 200..299) {
                            throw IllegalStateException(parsedJson?.optString("error")?.takeIf { it.isNotBlank() }
                                ?: "Builder server error ($code)")
                        }
                        val json = parsedJson ?: throw IllegalStateException("The builder returned invalid JSON.")
                        val projectName = json.optString("projectName").takeIf { it.isNotBlank() } ?: "shadow-project"
                        val files = json.optJSONArray("files")
                        if (files == null || files.length() !in 2..10) throw IllegalStateException("The builder returned an invalid project file list.")
                        Triple(projectName, json.optString("reply").takeIf { it.isNotBlank() }
                            ?: throw IllegalStateException("The builder returned an empty project preview."), json.toString())
                    } finally {
                        connection.disconnect()
                    }
                }
                generatedProjectName = replyResult.first.replace(Regex("[^a-zA-Z0-9_-]"), "-").take(60).ifBlank { "shadow-project" }
                generatedProjectJson = replyResult.third
                projectFiles = parseProjectFiles(generatedProjectJson)
                getPreferences(MODE_PRIVATE).edit()
                    .putString("generated_project_json", generatedProjectJson)
                    .putString("generated_project_name", generatedProjectName)
                    .apply()
                chatMessages = (chatMessages + ChatMessage("assistant", replyResult.second)).takeLast(30)
                saveMessages(chatMessages)
                status = "Project source ready • use EXPORT ZIP to save the files"
                if (voiceReplies) speakText("Your starter project source preview is ready.")
            } catch (error: Exception) {
                chatMessages = chatMessages.dropLast(1)
                saveMessages(chatMessages)
                draft = description
                status = error.message?.take(180) ?: "Could not generate the project. Check your connection and backend."
            } finally {
                sending = false
            }
        }
    }

    private fun parseProjectFiles(raw: String): List<ProjectFile> = runCatching {
        val files = JSONObject(raw).optJSONArray("files") ?: return@runCatching emptyList()
        (0 until files.length()).mapNotNull { index ->
            val item = files.optJSONObject(index) ?: return@mapNotNull null
            val path = item.optString("path")
            val content = item.optString("content")
            if (path.isNotBlank() && content.length <= 12000) ProjectFile(path, content) else null
        }.take(10)
    }.getOrDefault(emptyList())

    private fun validateProjectSource(): String {
        if (projectFiles.size !in 2..10) return "FAIL • Project must contain 2–10 files."
        val paths = projectFiles.map { it.path }
        if (paths.toSet().size != paths.size) return "FAIL • Duplicate file paths found."
        if (projectFiles.sumOf { it.content.length } > 40000) return "FAIL • Project is over the 40,000 character limit."
        val unsafe = projectFiles.firstOrNull { file ->
            file.path.isBlank() || file.path.length > 160 || file.path.startsWith("/") ||
                file.path.contains(92.toChar()) || file.path.contains("..") ||
                !file.path.matches(Regex("[a-zA-Z0-9._/-]+")) ||
                file.path.split("/").any { it.isBlank() || it == "." || it == ".." } ||
                file.content.length > 12000
        }
        if (unsafe != null) return "FAIL • Unsafe path or oversized file: " + unsafe.path
        val packageFile = projectFiles.firstOrNull { it.path == "package.json" }
        if (packageFile != null) {
            try {
                val packageJson = JSONObject(packageFile.content)
                if (packageJson.optString("name").isBlank()) return "WARNING • package.json is valid JSON but has no project name."
            } catch (_: Exception) {
                return "FAIL • package.json contains invalid JSON."
            }
        }
        val htmlFiles = projectFiles.count { it.path.endsWith(".html", true) || it.path.endsWith(".htm", true) }
        val notes = mutableListOf("PASS • File count, paths and source-size limits.")
        if (packageFile != null) notes.add("PASS • package.json is valid JSON.")
        if (htmlFiles > 0) notes.add("PASS • HTML preview is available for " + htmlFiles + " file(s).")
        notes.add("INFO • Static checks only; no code execution, dependency install, compiler or APK build was run.")
        return notes.joinToString("\n")
    }

    private fun saveProjectFile(path: String, content: String) {
        if (content.length > 12000) { status = "File is too large to save (12,000 character limit)"; return }
        val updatedFiles = projectFiles.map { file -> if (file.path == path) file.copy(content = content) else file }
        if (updatedFiles.none { it.path == path }) { status = "Selected project file could not be found"; return }
        if (updatedFiles.sumOf { it.content.length } > 40000) { status = "Project exceeds the 40,000 character export limit"; return }
        val json = JSONObject(generatedProjectJson)
        val array = JSONArray()
        updatedFiles.forEach { file -> array.put(JSONObject().put("path", file.path).put("content", file.content)) }
        json.put("files", array)
        generatedProjectJson = json.toString()
        projectFiles = updatedFiles
        getPreferences(MODE_PRIVATE).edit().putString("generated_project_json", generatedProjectJson).apply()
        status = "Saved " + path + " on this device • EXPORT ZIP to download the updated project"
    }

    private fun exportProjectZip(uri: android.net.Uri) {
        val raw = generatedProjectJson
        if (raw.isBlank()) { status = "Generate a project before exporting"; return }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val project = JSONObject(raw)
                    val files = project.optJSONArray("files") ?: throw IllegalStateException("No project files are available")
                    require(files.length() in 2..10) { "Project file list is invalid" }
                    val output = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Could not open the selected save location")
                    output.use { stream ->
                        ZipOutputStream(stream).use { zip ->
                            var totalChars = 0
                            for (index in 0 until files.length()) {
                                val file = files.optJSONObject(index) ?: throw IllegalStateException("Invalid project file")
                                val path = file.optString("path")
                                val content = file.optString("content")
                                require(path.isNotBlank() && path.length <= 160 && !path.startsWith("/") &&
                                    !path.contains("..") && !path.contains(92.toChar()) &&
                                    path.matches(Regex("[a-zA-Z0-9._/-]+")) &&
                                    path.split("/").none { it.isBlank() || it == "." || it == ".." }) { "Unsafe project file path" }
                                totalChars += content.length
                                require(content.length <= 12000 && totalChars <= 40000) { "Project source is too large to export" }
                                zip.putNextEntry(ZipEntry(path))
                                zip.write(content.toByteArray(Charsets.UTF_8))
                                zip.closeEntry()
                            }
                            zip.finish()
                        }
                    }
                }
                status = "Project ZIP saved successfully"
            } catch (error: Exception) {
                status = error.message?.take(160) ?: "Could not export project ZIP"
            }
        }
    }
    private fun researchWeb() {
        val query = draft.trim()
        val baseUrl = endpoint.trim().trimEnd('/')
        if (query.isBlank() || sending) return
        if (!baseUrl.startsWith("https://")) {
            status = "Add your deployed HTTPS backend URL before researching"
            return
        }
        if (query.length > 300) {
            status = "Research query must be under 300 characters"
            return
        }
        val updated = (chatMessages + ChatMessage("user", "Research: " + query)).takeLast(30)
        chatMessages = updated
        saveMessages(updated)
        draft = ""
        sending = true
        status = "Searching sources…"
        lifecycleScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val connection = (URL(baseUrl + "/api/research").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 12000
                        readTimeout = 20000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("Authorization", "Bearer $apiToken")
                    }
                    try {
                        val payload = JSONObject().put("query", query)
                        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        val code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        val json = runCatching { JSONObject(raw) }.getOrNull()
                        if (code !in 200..299) {
                            throw IllegalStateException(json?.optString("error")?.takeIf { it.isNotBlank() }
                                ?: "Research server error ($code)")
                        }
                        val results = json?.optJSONArray("results") ?: JSONArray()
                        buildString {
                            append("RESEARCH RESULTS • ")
                            append(json?.optString("provider") ?: "Source")
                            append("\n\n")
                            if (results.length() == 0) {
                                append("No matching source cards were returned for this query.")
                            } else {
                                for (index in 0 until results.length()) {
                                    val item = results.optJSONObject(index) ?: continue
                                    append(index + 1).append(". ")
                                    append(item.optString("title")).append("\n")
                                    val snippet = item.optString("snippet")
                                    if (snippet.isNotBlank()) append(snippet).append("\n")
                                    append(item.optString("url")).append("\n\n")
                                }
                            }
                            append(json?.optString("notice").orEmpty())
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
                chatMessages = chatMessages + ChatMessage("assistant", report)
                saveMessages(chatMessages)
                status = "Research complete • open source links to verify"
                if (voiceReplies) speakText("I found research sources. Please review the source links in the chat.")
            } catch (error: Exception) {
                chatMessages = chatMessages.dropLast(1)
                saveMessages(chatMessages)
                draft = query
                status = error.message?.take(180) ?: "Research failed. Check your connection and backend."
            } finally {
                sending = false
            }
        }
    }

    private fun addMemory(value: String) {
        val memory = value.trim().take(300)
        if (memory.isBlank()) return
        if (savedMemories.any { it.equals(memory, ignoreCase = true) }) {
            status = "This memory is already saved"
            return
        }
        savedMemories = (savedMemories + memory).takeLast(30)
        getPreferences(MODE_PRIVATE).edit().putString("saved_memories", JSONArray(savedMemories).toString()).apply()
        status = "Memory saved on this device"
    }

    private fun deleteMemory(value: String) {
        savedMemories = savedMemories.filterNot { it == value }
        getPreferences(MODE_PRIVATE).edit().putString("saved_memories", JSONArray(savedMemories).toString()).apply()
        status = "Memory deleted from this device"
    }

    private fun loadSavedMemories(raw: String): List<String> {
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { index ->
                json.optString(index).takeIf { it.isNotBlank() && it.length <= 300 }
            }.takeLast(30)
        }.getOrDefault(emptyList())
    }

    private fun clearConversation() {
        chatMessages = emptyList()
        saveMessages(emptyList())
        draft = ""
        status = "Conversation history cleared from this app"
    }

    private fun speakLatestReply() {
        val latest = chatMessages.lastOrNull { it.role == "assistant" }?.content
        if (latest.isNullOrBlank()) {
            status = "No SHADOW reply to read yet"
            return
        }
        speakText(latest)
    }

    private fun speakText(text: String) {
        if (!ttsReady || textToSpeech == null) {
            status = "Text-to-speech is not ready. Check your device speech engine."
            return
        }
        val locale = if (text.any { it.code in 0x0900..0x097F }) Locale("hi", "IN") else Locale.getDefault()
        val languageResult = textToSpeech?.setLanguage(locale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            status = "This voice language is not installed on your device"
            return
        }
        val result = textToSpeech?.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "shadow_reply")
        if (result == TextToSpeech.SUCCESS) {
            speaking = true
            status = "SHADOW is speaking…"
        } else {
            status = "Could not start voice playback"
        }
    }

    private fun stopSpeaking() {
        textToSpeech?.stop()
        speaking = false
        if (status == "SHADOW is speaking…") status = "Voice playback stopped"
    }

    private fun saveMessages(messages: List<ChatMessage>) {
        val json = JSONArray().apply {
            messages.takeLast(30).forEach { message ->
                put(JSONObject().put("role", message.role).put("content", message.content))
            }
        }
        getPreferences(MODE_PRIVATE).edit().putString("chat_history", json.toString()).apply()
    }

    private fun loadSavedMessages(raw: String): List<ChatMessage> {
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { index ->
                val item = json.optJSONObject(index) ?: return@mapNotNull null
                val role = item.optString("role")
                val content = item.optString("content")
                if ((role == "user" || role == "assistant") && content.isNotBlank() && content.length <= 6000) {
                    ChatMessage(role, content)
                } else null
            }.takeLast(30)
        }.getOrDefault(emptyList())
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }
}

private val Night = Color(0xFF03060C)
private val Blue = Color(0xFF328BFF)
private val Ice = Color(0xFF8BD8FF)

@Composable
private fun ShadowHome(
 status:String,draft:String,endpoint:String,apiToken:String,responseLanguage:String,voiceReplies:Boolean,backgroundWakeEnabled:Boolean,speaking:Boolean,
 messages:List<ChatMessage>,memories:List<String>,listening:Boolean,sending:Boolean,
 onDraftChange:(String)->Unit,onEndpointChange:(String)->Unit,onApiTokenChange:(String)->Unit,onLanguageChange:(String)->Unit,
 onVoiceRepliesChange:(Boolean)->Unit,onBackgroundWakeChange:(Boolean)->Unit,onSpeakLatest:()->Unit,onStopSpeaking:()->Unit,onClearChat:()->Unit,
 onAddMemory:(String)->Unit,onDeleteMemory:(String)->Unit,onVoiceTap:()->Unit,onSend:()->Unit,onResearch:()->Unit,
 hasExportableProject:Boolean,projectFiles:List<ProjectFile>,onExportProject:()->Unit,onSaveProjectFile:(String,String)->Unit,onCheckProject:()->String,
 externalPlugins:List<ExternalPlugin>,pluginStatus:String,pluginBusy:Boolean,
 onImportPlugin:(String,String)->Unit,onRunPlugin:(String,String,String)->Unit,onDisconnectPlugin:(String)->Unit
) {
 val listState=rememberLazyListState()
 var selectedTab by androidx.compose.runtime.remember { mutableStateOf("Home") }
 var showClearConfirmation by androidx.compose.runtime.remember { mutableStateOf(false) }
 var showMemoryDialog by androidx.compose.runtime.remember { mutableStateOf(false) }
 var memoryDraft by androidx.compose.runtime.remember { mutableStateOf("") }
 var showWorkspaceDialog by androidx.compose.runtime.remember { mutableStateOf(false) }
 var selectedProjectPath by androidx.compose.runtime.remember { mutableStateOf("") }
 var projectFileDraft by androidx.compose.runtime.remember { mutableStateOf("") }
 var projectCheckReport by androidx.compose.runtime.remember { mutableStateOf("") }
 var showPreviewDialog by androidx.compose.runtime.remember { mutableStateOf(false) }
 var previewHtml by androidx.compose.runtime.remember { mutableStateOf("") }
 LaunchedEffect(messages.size,selectedTab) { if(messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
 MaterialTheme {
  Surface(Modifier.fillMaxSize(),color=Night) {
   Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF02050B),Color(0xFF061326),Color(0xFF020711)))).padding(horizontal=16.dp,vertical=12.dp)) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
     ShadowLogoMark(Modifier.size(42.dp))
     Column(Modifier.padding(start=9.dp)) {
      Text("SHADOW",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=2.2.sp)
      Text("PERSONAL INTELLIGENCE",color=Ice,fontSize=8.sp,letterSpacing=1.5.sp)
     }
     Spacer(Modifier.weight(1f))
     Surface(color=Color(0xFF08251F),shape=RoundedCornerShape(24.dp)) {
      Row(Modifier.padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically) {
       Box(Modifier.size(7.dp).background(if(sending) Color(0xFFFFC857) else Color(0xFF39E69A),RoundedCornerShape(50)))
       Text(if(sending) " THINKING" else " CLOUD AI",color=Color(0xFF7FFFC0),fontSize=9.sp,fontWeight=FontWeight.Bold)
      }
     }
    }
    when(selectedTab) {
     "Skills" -> PluginHubScreen(modifier=Modifier.weight(1f),plugins=externalPlugins,status=pluginStatus,busy=pluginBusy,onImport=onImportPlugin,onRun=onRunPlugin,onDisconnect=onDisconnectPlugin)
     "Home" -> Column(Modifier.weight(1f).fillMaxWidth().padding(top=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(9.dp)) {
      Spacer(Modifier.height(2.dp))
      HomeHeroBackdrop(listening= listening,onClick=onVoiceTap)
      Text("YOUR PERSONAL AI",color=Color(0xFF83A6CE),fontSize=9.sp,letterSpacing=2.5.sp)
      Text("I'm ready.\nWhat shall we do today?",color=Color(0xFFF3F7FF),fontSize=22.sp,lineHeight=28.sp,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center)
      Surface(color=Color(0xFF071E35),shape=RoundedCornerShape(24.dp)) { Text("✦  LLAMA 3.1  •  CLOUD AI",color=Ice,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(horizontal=18.dp,vertical=9.dp)) }
      Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
       ActionTile("AI CHAT","Ask anything","●",Modifier.weight(1f)) { selectedTab="Chat" }
       ActionTile("WEB RESEARCH","Find sources","◎",Modifier.weight(1f)) { selectedTab="Chat"; if(!draft.startsWith("/search ")) onDraftChange("/search ") }
      }
      Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
       ActionTile("BUILD PROJECT","Create apps & code","</>",Modifier.weight(1f)) { selectedTab="Studio"; if(!draft.startsWith("/build ")) onDraftChange("/build ") }
       ActionTile("MY MEMORY","Saved context","✧",Modifier.weight(1f)) { showMemoryDialog=true }
      }
      Surface(color=Color(0xFF071A30),shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth().clickable(onClick=onVoiceTap)) {
       Row(Modifier.padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("🎙",fontSize=30.sp)
        Column(Modifier.padding(start=14.dp)) {
         Text(if(listening) "LISTENING TO YOU" else "TALK TO SHADOW",color=Color.White,fontWeight=FontWeight.Bold,fontSize=14.sp)
         Text(if(listening) "Tap to stop listening" else "Tap to start voice",color=Ice,fontSize=11.sp)
        }
        Spacer(Modifier.weight(1f)); Text("›",color=Ice,fontSize=30.sp)
       }
      }
      Text(status,color=Color(0xFF91A4BD),fontSize=10.sp,textAlign=TextAlign.Center,maxLines=2)
      Spacer(Modifier.weight(1f))
     }
     "Chat" -> Column(Modifier.weight(1f).fillMaxWidth().padding(top=10.dp)) {
      Row(verticalAlignment=Alignment.CenterVertically) {
       Text("AI CHAT",color=Color.White,fontSize=16.sp,fontWeight=FontWeight.Bold); Spacer(Modifier.weight(1f))
       if(messages.isNotEmpty()) Text("CLEAR",color=Color(0xFFFF9A9A),fontSize=10.sp,modifier=Modifier.clickable { showClearConfirmation=true }.padding(8.dp))
      }
      if(messages.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
       Column(horizontalAlignment=Alignment.CenterHorizontally) { ShadowOrb(listening= listening,onClick=onVoiceTap); Text("Ask SHADOW anything",color=Color.White,fontSize=18.sp); Text("Cloud AI assistant • Hindi & English",color=Ice,fontSize=11.sp) }
      } else LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=listState,verticalArrangement=Arrangement.spacedBy(10.dp)) {
       items(messages) { message ->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=if(message.role=="user") Arrangement.End else Arrangement.Start) {
         Surface(color=if(message.role=="user") Color(0xFF12345A) else Color(0xFF101B2B),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth(0.92f)) {
          Column(Modifier.padding(12.dp)) { Text(if(message.role=="user") "YOU" else "SHADOW",color=Ice,fontSize=9.sp,fontWeight=FontWeight.Bold); Text(message.content,color=Color(0xFFE2EEFF),fontSize=13.sp) }
         }
        }
       }
       if(sending) item { Text("SHADOW is thinking…",color=Ice,fontSize=12.sp) }
      }
     }
     "Studio" -> Column(Modifier.weight(1f).fillMaxWidth().padding(top=18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
      Text("SHADOW STUDIO",color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold)
      Text("Describe an app or website. SHADOW will generate a starter project with editable source files.",color=Color(0xFFB9CBE1),fontSize=13.sp)
      ActionTile("PROJECT BUILDER","Generate source files","</>",Modifier.fillMaxWidth()) { if(!draft.startsWith("/build ")) onDraftChange("/build ") }
      if(hasExportableProject) {
       Text("${projectFiles.size} project files available",color=Ice,fontSize=12.sp)
       Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Button(onClick={ selectedProjectPath=projectFiles.firstOrNull()?.path.orEmpty(); projectFileDraft=projectFiles.firstOrNull()?.content.orEmpty(); showWorkspaceDialog=true }) { Text("WORKSPACE") }
        Button(onClick=onExportProject) { Text("EXPORT ZIP") }
       }
      } else Text("No generated project yet. Enter your idea below and tap SEND.",color=Color(0xFF91A4BD),fontSize=11.sp)
      Spacer(Modifier.weight(1f)); Text(status,color=Ice,fontSize=11.sp)
     }
     else -> Column(Modifier.weight(1f).fillMaxWidth().padding(top=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
      Text("SETTINGS",color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold)
      Text("CLOUD CONNECTION",color=Ice,fontSize=10.sp,fontWeight=FontWeight.Bold)
      OutlinedTextField(value=endpoint,onValueChange=onEndpointChange,modifier=Modifier.fillMaxWidth(),singleLine=true,label={ Text("Backend URL (HTTPS)") },textStyle=androidx.compose.ui.text.TextStyle(color=Color.White,fontSize=11.sp))
      OutlinedTextField(value=apiToken,onValueChange=onApiTokenChange,modifier=Modifier.fillMaxWidth(),singleLine=true,label={ Text("Private access token") },visualTransformation=PasswordVisualTransformation(),textStyle=androidx.compose.ui.text.TextStyle(color=Color.White,fontSize=11.sp))
      Text("RESPONSE LANGUAGE",color=Ice,fontSize=10.sp,fontWeight=FontWeight.Bold)
      Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("Auto","Hindi","English").forEach { option -> Surface(color=if(responseLanguage==option) Color(0xFF173B63) else Color(0xFF101B2B),shape=RoundedCornerShape(20.dp),modifier=Modifier.clickable { onLanguageChange(option) }) { Text(option,color=Color.White,modifier=Modifier.padding(horizontal=13.dp,vertical=9.dp),fontSize=11.sp) } } }
      Text("VOICE",color=Ice,fontSize=10.sp,fontWeight=FontWeight.Bold)
      Row(verticalAlignment=Alignment.CenterVertically) { Text(if(voiceReplies) "Automatic voice replies ON" else "Automatic voice replies OFF",color=Color.White,modifier=Modifier.weight(1f),fontSize=12.sp); Button(onClick={onVoiceRepliesChange(!voiceReplies)}) { Text(if(voiceReplies) "TURN OFF" else "TURN ON") } }
      Text("BACKGROUND WAKE WORD",color=Ice,fontSize=10.sp,fontWeight=FontWeight.Bold)
      Text("Say “Shadow” while the visible microphone notification is active. Speech is handled by the Android speech-recognition provider.",color=Color(0xFF9CB5D3),fontSize=10.sp)
      Row(verticalAlignment=Alignment.CenterVertically) { Text(if(backgroundWakeEnabled) "Background listening requested" else "Background listening OFF",color=Color.White,modifier=Modifier.weight(1f),fontSize=12.sp); Button(onClick={onBackgroundWakeChange(!backgroundWakeEnabled)}) { Text(if(backgroundWakeEnabled) "STOP" else "START") } }
      Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Button(onClick=onSpeakLatest) { Text("READ LAST") }; Button(onClick=onStopSpeaking) { Text("STOP VOICE") }; Button(onClick={showMemoryDialog=true}) { Text("MEMORY") } }
      Text(status,color=Color(0xFF91A4BD),fontSize=10.sp); Spacer(Modifier.weight(1f))
     }
    }
    if(selectedTab!="Settings") Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
     Button(onClick=onVoiceTap,colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF132A46)),modifier=Modifier.height(50.dp)) { Text("🎙",color=Ice) }
     OutlinedTextField(value=draft,onValueChange=onDraftChange,modifier=Modifier.weight(1f).padding(horizontal=7.dp),placeholder={ Text(if(selectedTab=="Studio") "Describe your app…" else "Message SHADOW…") },maxLines=2,textStyle=androidx.compose.ui.text.TextStyle(color=Color.White,fontSize=13.sp))
     Button(onClick=onSend,enabled=!sending&&draft.isNotBlank(),colors=ButtonDefaults.buttonColors(containerColor=Blue),modifier=Modifier.height(50.dp)) { Text("➤",color=Color.White) }
    }
    Row(Modifier.fillMaxWidth().padding(top=10.dp),horizontalArrangement=Arrangement.SpaceAround,verticalAlignment=Alignment.CenterVertically) {
     listOf("Home","Chat","Studio","Skills","Settings").forEach { tab ->
      Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.weight(1f).clickable { selectedTab=tab }.padding(vertical=5.dp)) {
       Text(when(tab) { "Home"->"⌂"; "Chat"->"◌"; "Studio"->"▣"; "Skills"->"⧉"; else->"⚙" },color=if(selectedTab==tab) Ice else Color(0xFF91A4BD),fontSize=22.sp)
       Text(tab,color=if(selectedTab==tab) Ice else Color(0xFF91A4BD),fontSize=10.sp,fontWeight=if(selectedTab==tab) FontWeight.Bold else FontWeight.Normal)
       if(selectedTab==tab) Box(Modifier.padding(top=3.dp).size(width=34.dp,height=2.dp).background(Blue,RoundedCornerShape(2.dp)))
      }
     }
    }
   }
  }
 }

    if (showWorkspaceDialog) {
        AlertDialog(
            onDismissRequest = { showWorkspaceDialog = false },
            title = { Text("Project Workspace") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(projectFiles.size.toString() + " source files • edits are saved locally", color = Color(0xFFB8C9DD), fontSize = 11.sp)
                    projectFiles.forEach { file ->
                        Surface(color = if (selectedProjectPath == file.path) Color(0xFF173B63) else Color(0xFF101B2B),
                            shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().clickable {
                                selectedProjectPath = file.path
                                projectFileDraft = file.content
                            }) {
                            Text(file.path, color = if (selectedProjectPath == file.path) Ice else Color(0xFFE2EEFF),
                                fontSize = 11.sp, modifier = Modifier.padding(9.dp))
                        }
                    }
                    if (selectedProjectPath.isNotBlank()) {
                        Text("EDITING: " + selectedProjectPath, color = Ice, fontSize = 10.sp)
                        if (selectedProjectPath.endsWith(".html", true) || selectedProjectPath.endsWith(".htm", true)) {
                            TextButton(onClick = { previewHtml = projectFileDraft; showPreviewDialog = true }) { Text("PREVIEW HTML") }
                        }
                        OutlinedTextField(value = projectFileDraft, onValueChange = { projectFileDraft = it },
                            modifier = Modifier.fillMaxWidth().height(260.dp), minLines = 8, maxLines = 14,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                    }
                    TextButton(onClick = { projectCheckReport = onCheckProject() }) { Text("CHECK PROJECT") }
                    if (projectCheckReport.isNotBlank()) {
                        Text(projectCheckReport, color = Color(0xFFB8C9DD), fontSize = 10.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (selectedProjectPath.isNotBlank()) onSaveProjectFile(selectedProjectPath, projectFileDraft)
                    showWorkspaceDialog = false
                }, enabled = selectedProjectPath.isNotBlank()) { Text("SAVE FILE") }
            },
            dismissButton = { TextButton(onClick = { showWorkspaceDialog = false }) { Text("CLOSE") } },
            containerColor = Color(0xFF101B2B), titleContentColor = Color.White, textContentColor = Color(0xFFB8C9DD)
        )
    }
    if (showPreviewDialog) {
        AlertDialog(
            onDismissRequest = { showPreviewDialog = false },
            title = { Text("Safe HTML Preview") },
            text = {
                Column {
                    Text("JavaScript and network requests are disabled in this preview.", color = Color(0xFFB8C9DD), fontSize = 10.sp)
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = false
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.domStorageEnabled = false
                                settings.blockNetworkLoads = true
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                                    }
                                }
                            }
                        },
                        update = { view ->
                            if (view.tag != previewHtml) {
                                view.tag = previewHtml
                                view.loadDataWithBaseURL("https://shadow-preview.invalid/", previewHtml, "text/html", "UTF-8", null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(420.dp)
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showPreviewDialog = false }) { Text("CLOSE") } },
            containerColor = Color(0xFF101B2B), titleContentColor = Color.White, textContentColor = Color(0xFFB8C9DD)
        )
    }
    if (showMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showMemoryDialog = false },
            title = { Text("SHADOW Memory") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Only facts you add here are saved on this device and shared with SHADOW during chat.", color = Color(0xFFB8C9DD), fontSize = 12.sp)
                    OutlinedTextField(
                        value = memoryDraft,
                        onValueChange = { memoryDraft = it.take(300) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Example: I prefer explanations in Hinglish") },
                        maxLines = 3
                    )
                    Text("SAVED MEMORIES (${memories.size}/30)", color = Ice, fontSize = 10.sp)
                    if (memories.isEmpty()) Text("No saved memories yet.", color = Color(0xFFB8C9DD), fontSize = 12.sp)
                    memories.forEach { memory ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(memory, color = Color(0xFFE2EEFF), fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onDeleteMemory(memory) }) { Text("DELETE", color = Color(0xFFFF9A9A), fontSize = 10.sp) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAddMemory(memoryDraft)
                    memoryDraft = ""
                }, enabled = memoryDraft.isNotBlank() && memories.size < 30) { Text("SAVE MEMORY") }
            },
            dismissButton = { TextButton(onClick = { showMemoryDialog = false }) { Text("CLOSE") } },
            containerColor = Color(0xFF101B2B),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFB8C9DD)
        )
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear this conversation?") },
            text = { Text("This removes the saved chat history from this app on this device. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirmation = false
                    onClearChat()
                }) { Text("Clear history") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") }
            },
            containerColor = Color(0xFF101B2B),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFB8C9DD)
        )
    }
}

@Composable
private fun ActionTile(title:String,subtitle:String,symbol:String,modifier:Modifier=Modifier,onClick:()->Unit) {
 Surface(color=Color(0xFF0A1B31),shape=RoundedCornerShape(16.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Color(0xFF167DCA)),modifier=modifier.clickable(onClick=onClick)) {
  Row(Modifier.padding(horizontal=10.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
   Text(symbol,color=Ice,fontSize=22.sp,fontWeight=FontWeight.Bold)
   Column(Modifier.padding(start=9.dp).weight(1f)) { Text(title,color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold); Text(subtitle,color=Color(0xFF9CB5D3),fontSize=9.sp,maxLines=2) }
   Text("›",color=Blue,fontSize=22.sp)
  }
 }
}

@Composable
private fun ShadowLogoMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.055f
        val center = this.center
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF102C50), Color(0xFF06101F)),
                center = center,
                radius = size.minDimension * 0.72f
            ),
            radius = size.minDimension * 0.48f
        )
        drawCircle(color = Blue.copy(alpha = 0.85f), radius = size.minDimension * 0.45f,
            style = Stroke(width = stroke * 0.55f))
        val mark = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.68f, size.height * 0.29f)
            cubicTo(size.width * 0.58f, size.height * 0.17f, size.width * 0.34f, size.height * 0.21f, size.width * 0.32f, size.height * 0.39f)
            cubicTo(size.width * 0.29f, size.height * 0.54f, size.width * 0.72f, size.height * 0.46f, size.width * 0.68f, size.height * 0.64f)
            cubicTo(size.width * 0.65f, size.height * 0.82f, size.width * 0.39f, size.height * 0.83f, size.width * 0.30f, size.height * 0.69f)
        }
        drawPath(path = mark, brush = Brush.linearGradient(listOf(Ice, Blue)),
            style = Stroke(width = stroke * 1.35f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(color = Ice, radius = stroke * 0.7f,
            center = androidx.compose.ui.geometry.Offset(size.width * 0.75f, size.height * 0.22f))
    }
}

@Composable
private fun HomeHeroBackdrop(listening: Boolean, onClick: () -> Unit) {
 Box(Modifier.fillMaxWidth().height(252.dp), contentAlignment=Alignment.Center) {
  Canvas(Modifier.fillMaxSize()) {
   val w=size.width; val h=size.height
   drawRect(brush=Brush.verticalGradient(listOf(Color(0xFF061A3A),Color(0xFF020711)),startY=0f,endY=h))
   drawCircle(Color(0xFF8BBFFF).copy(alpha=0.20f),radius=42.dp.toPx(),center=androidx.compose.ui.geometry.Offset(w*0.86f,h*0.22f))
   drawCircle(Color(0xFFB9D9FF).copy(alpha=0.16f),radius=34.dp.toPx(),center=androidx.compose.ui.geometry.Offset(w*0.86f,h*0.22f))
   for(i in 0..34) {
    val x=w*((i*37%101)/100f); val y=h*((i*19%67)/100f)
    drawCircle(Ice.copy(alpha=0.18f+(i%4)*0.09f),radius=(i%3+1).dp.toPx()*0.45f,center=androidx.compose.ui.geometry.Offset(x,y))
   }
   val widths=floatArrayOf(.07f,.09f,.06f,.11f,.08f,.07f,.12f,.08f,.06f,.10f,.07f,.09f)
   val heights=floatArrayOf(.27f,.42f,.34f,.52f,.31f,.44f,.58f,.35f,.48f,.30f,.43f,.25f)
   var x=0f
   for(i in widths.indices) {
    val bw=w*widths[i]; val bh=h*heights[i]
    drawRect(Color(0xFF07152B),androidx.compose.ui.geometry.Offset(x,h-bh),androidx.compose.ui.geometry.Size(bw+2f,bh))
    for(row in 0..3) for(col in 0..1) {
     val wx=x+bw*(0.24f+col*0.42f); val wy=h-bh+bh*(0.18f+row*0.17f)
     drawRect(Color(0xFF1687D9).copy(alpha=0.28f),androidx.compose.ui.geometry.Offset(wx,wy),androidx.compose.ui.geometry.Size(2.2.dp.toPx(),3.dp.toPx()))
    }
    x+=bw
   }
   drawRect(brush=Brush.verticalGradient(listOf(Color.Transparent,Color(0xFF020711))),topLeft=androidx.compose.ui.geometry.Offset(0f,h*0.70f),size=androidx.compose.ui.geometry.Size(w,h*0.30f))
  }
  ShadowOrb(listening=listening,onClick=onClick)
 }
}

@Composable
private fun ShadowOrb(listening: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "shadow-orb")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = if (listening) 1.12f else 1.06f,
        animationSpec = infiniteRepeatable(tween(if (listening) 700 else 1800), RepeatMode.Reverse),
        label = "orb-pulse"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(240.dp).clickable(onClick = onClick)
    ) {
        Canvas(Modifier.size(230.dp)) {
            val center = this.center
            drawCircle(Blue.copy(alpha = if (listening) 0.14f else 0.07f), radius = size.minDimension * 0.46f)
            drawCircle(Blue.copy(alpha = 0.22f), radius = size.minDimension * 0.39f, style = Stroke(width = 1.2.dp.toPx()))
            drawOval(
                color = Ice.copy(alpha = 0.65f),
                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.13f, size.height * 0.36f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.74f, size.height * 0.28f),
                style = Stroke(width = 2.dp.toPx())
            )
            drawOval(
                color = Blue.copy(alpha = 0.9f),
                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.28f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.56f, size.height * 0.42f),
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF153C78), Color(0xFF06152E), Color.Transparent),
                    center,
                    size.minDimension * 0.28f
                ),
                radius = size.minDimension * 0.28f * pulse
            )
        }
    }
}
