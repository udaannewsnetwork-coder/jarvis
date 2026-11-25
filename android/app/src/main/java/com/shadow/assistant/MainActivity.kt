package com.shadow.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
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
import java.util.Locale

private data class ChatMessage(val role: String, val content: String)

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Your assistant is standing by")
    private var draft by mutableStateOf("")
    private var listening by mutableStateOf(false)
    private var sending by mutableStateOf(false)
    private var endpoint by mutableStateOf("")
    private var responseLanguage by mutableStateOf("Auto")
    private var voiceReplies by mutableStateOf(false)
    private var speaking by mutableStateOf(false)
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var chatMessages by mutableStateOf(listOf<ChatMessage>())
    private var speechRecognizer: SpeechRecognizer? = null

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginRecognition()
        else status = "Microphone permission is needed for voice input"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getPreferences(MODE_PRIVATE)
        endpoint = preferences.getString("backend_url", "").orEmpty()
        responseLanguage = preferences.getString("response_language", "Auto").orEmpty()
        voiceReplies = preferences.getBoolean("voice_replies", false)
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
        if (chatMessages.isNotEmpty()) status = "Previous conversation restored"
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        setContent {
            ShadowHome(
                status = status,
                draft = draft,
                endpoint = endpoint,
                responseLanguage = responseLanguage,
                voiceReplies = voiceReplies,
                speaking = speaking,
                messages = chatMessages,
                listening = listening,
                sending = sending,
                onDraftChange = { draft = it },
                onEndpointChange = {
                    endpoint = it
                    getPreferences(MODE_PRIVATE).edit().putString("backend_url", it).apply()
                },
                onLanguageChange = {
                    responseLanguage = it
                    getPreferences(MODE_PRIVATE).edit().putString("response_language", it).apply()
                },
                onVoiceRepliesChange = {
                    voiceReplies = it
                    getPreferences(MODE_PRIVATE).edit().putBoolean("voice_replies", it).apply()
                    if (!it) stopSpeaking()
                    status = if (it) "Voice replies enabled" else "Voice replies disabled"
                },
                onSpeakLatest = { speakLatestReply() },
                onStopSpeaking = { stopSpeaking() },
                onVoiceTap = { requestVoiceInput() },
                onSend = { sendMessage() }
            )
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
        val baseUrl = endpoint.trim().trimEnd('/')
        if (prompt.isBlank() || sending) return
        if (!baseUrl.startsWith("https://")) {
            status = "Add your deployed HTTPS backend URL below before chatting"
            return
        }
        if (prompt.length > 6000) {
            status = "Message is too long. Keep it under 6,000 characters."
            return
        }

        // Keep the latest 30 messages to stay within the server's context limit.
        val updated = (chatMessages + ChatMessage("user", prompt)).takeLast(30)
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
                    }
                    try {
                        val payload = JSONObject().put("messages", JSONArray().apply {
                            updated.forEach { message ->
                                put(JSONObject().put("role", message.role).put("content", message.content))
                            }
                        }).put("language", when (responseLanguage) { "English" -> "en"; "Hindi" -> "hi"; else -> "auto" })
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
    status: String,
    draft: String,
    endpoint: String,
    responseLanguage: String,
    voiceReplies: Boolean,
    speaking: Boolean,
    messages: List<ChatMessage>,
    listening: Boolean,
    sending: Boolean,
    onDraftChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onVoiceRepliesChange: (Boolean) -> Unit,
    onSpeakLatest: () -> Unit,
    onStopSpeaking: () -> Unit,
    onVoiceTap: () -> Unit,
    onSend: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Night) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Black, Night, Color(0xFF071323))))
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("S", color = Ice, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("  S H A D O W", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (listening) "● LISTENING" else if (sending) "● THINKING" else "● READY",
                        color = if (listening || sending) Ice else Color(0xFF73D6A6), fontSize = 10.sp, letterSpacing = 1.sp)
                }

                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (messages.isEmpty()) {
                        Spacer(Modifier.weight(1f))
                        Text("YOUR PERSONAL AI", color = Color(0xFF7F9DBD), fontSize = 10.sp, letterSpacing = 3.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("I'm here.\nWhat shall we do?", color = Color(0xFFF1F6FF), fontSize = 26.sp,
                            lineHeight = 34.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                        ShadowOrb(listening = listening, onClick = onVoiceTap)
                        Text(if (listening) "LISTENING — TAP TO STOP" else "TAP THE ORB TO SPEAK",
                            color = Ice, fontSize = 10.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(status, color = Color(0xFF91A4BD), fontSize = 12.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.weight(1f))
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(messages) { message ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start
                                ) {
                                    Surface(
                                        color = if (message.role == "user") Color(0xFF12345A) else Color(0xFF101B2B),
                                        shape = RoundedCornerShape(18.dp),
                                        modifier = Modifier.fillMaxWidth(0.88f)
                                    ) {
                                        Column(Modifier.padding(14.dp)) {
                                            Text(if (message.role == "user") "YOU" else "SHADOW",
                                                color = Ice, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.height(5.dp))
                                            Text(message.content, color = Color(0xFFE2EEFF), fontSize = 14.sp, lineHeight = 21.sp)
                                        }
                                    }
                                }
                            }
                            if (sending) item {
                                Text("SHADOW is thinking…", color = Ice, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("RESPONSE LANGUAGE", color = Color(0xFF91A4BD), fontSize = 9.sp, letterSpacing = 1.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Auto", "Hindi", "English").forEach { option ->
                            Surface(
                                color = if (responseLanguage == option) Color(0xFF173B63) else Color(0xFF101B2B),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.clickable { onLanguageChange(option) }
                            ) {
                                Text(option, color = if (responseLanguage == option) Ice else Color(0xFF91A4BD),
                                    fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("VOICE REPLIES", color = Color(0xFF91A4BD), fontSize = 9.sp, letterSpacing = 1.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            color = if (voiceReplies) Color(0xFF173B63) else Color(0xFF101B2B),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.clickable { onVoiceRepliesChange(!voiceReplies) }
                        ) {
                            Text(if (voiceReplies) "AUTO ON" else "AUTO OFF",
                                color = if (voiceReplies) Ice else Color(0xFF91A4BD),
                                fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                        }
                        Surface(
                            color = Color(0xFF101B2B),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.clickable { if (speaking) onStopSpeaking() else onSpeakLatest() }
                        ) {
                            Text(if (speaking) "STOP" else "READ LAST",
                                color = Ice, fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = onEndpointChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Backend URL (HTTPS)") },
                    placeholder = { Text("https://your-shadow-server.example") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp)
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onVoiceTap,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF132A46)),
                        modifier = Modifier.height(54.dp)
                    ) { Text(if (listening) "Stop" else "🎙", color = Ice) }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message SHADOW…") },
                        maxLines = 3,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp)
                    )
                    Button(
                        onClick = onSend,
                        enabled = !sending && draft.isNotBlank(),
                        modifier = Modifier.height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                    ) { Text("Send", color = Color.White) }
                }
                Text("PRIVATE BY DESIGN • REVIEW VOICE TEXT BEFORE SENDING",
                    color = Color(0xFF52647A), fontSize = 8.sp, letterSpacing = 0.8.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
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
