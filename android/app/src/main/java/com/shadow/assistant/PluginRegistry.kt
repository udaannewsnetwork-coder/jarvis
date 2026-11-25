package com.shadow.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class ConnectedShadowPlugin(
    val id: String,
    val name: String,
    val endpoint: String,
    val description: String
)

private object PluginVault {
    private const val PREFS = "shadow_plugin_vault"
    private const val KEY_ALIAS = "shadow_plugin_vault_aes"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun put(context: Context, id: String, secret: String) {
        if (secret.isBlank()) { remove(context, id); return }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("secret_$id", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun get(context: Context, id: String): String? = runCatching {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("secret_$id", null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > 12)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
    }.getOrNull()

    fun remove(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("secret_$id").apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}

private object PluginConnections {
    private const val PREFS = "shadow_plugin_connections"

    fun load(context: Context): List<ConnectedShadowPlugin> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("plugins", "[]") ?: "[]"
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            ConnectedShadowPlugin(item.optString("id"), item.optString("name"), item.optString("endpoint"), item.optString("description"))
                .takeIf { it.id.isNotBlank() && it.endpoint.isNotBlank() }
        }.take(20)
    }.getOrDefault(emptyList())

    fun save(context: Context, plugins: List<ConnectedShadowPlugin>) {
        val array = JSONArray()
        plugins.take(20).forEach { p ->
            array.put(JSONObject().put("id", p.id).put("name", p.name).put("endpoint", p.endpoint).put("description", p.description))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("plugins", array.toString()).apply()
    }
}

private fun validPlugin(id: String, name: String, endpoint: String): Boolean {
    if (!id.matches(Regex("[a-z][a-z0-9_]{2,39}")) || name.trim().length !in 2..40) return false
    return runCatching {
        val uri = URI(endpoint.trim())
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.host.length <= 253 && endpoint.length <= 1000
    }.getOrDefault(false)
}

private suspend fun invokePlugin(endpoint: String, token: String?, userInput: String): String = withContext(Dispatchers.IO) {
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 8000
        readTimeout = 10000
        instanceFollowRedirects = false
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Accept", "application/json, text/plain")
        if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
    }
    try {
        val body = JSONObject().put("input", userInput).put("source", "SHADOW Android").toString()
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText().take(8000) }.orEmpty()
        if (code !in 200..299) "Plugin returned HTTP $code: $response" else response.ifBlank { "Plugin completed successfully (HTTP $code)." }
    } finally {
        connection.disconnect()
    }
}

@Composable
internal fun PluginHubScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plugins by remember { mutableStateOf(PluginConnections.load(context)) }
    var showAdd by remember { mutableStateOf(false) }
    var addName by remember { mutableStateOf("") }
    var addId by remember { mutableStateOf("") }
    var addEndpoint by remember { mutableStateOf("") }
    var addDescription by remember { mutableStateOf("") }
    var addToken by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ConnectedShadowPlugin?>(null) }
    var actionInput by remember { mutableStateOf("") }
    var pendingRun by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf(listOf<String>()) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PLUGIN HUB", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Built-in tools aur tumhare connected HTTPS webhook plugins. External plugins ko har run se pehle tumhari approval chahiye.", color = Color(0xFFB9CBE1), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Color(0xFF08251F), shape = RoundedCornerShape(18.dp)) {
                Text("4 BUILT-IN TOOLS", color = Color(0xFF7FFFC0), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
            }
            Surface(color = Color(0xFF17263B), shape = RoundedCornerShape(18.dp)) {
                Text("${plugins.size}/20 CONNECTED", color = Color(0xFF8BD8FF), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
            }
        }
        ShadowPluginRegistry.builtIns.forEach { plugin ->
            Surface(color = Color(0xFF0A1B31), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0xFF167DCA)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(plugin.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(plugin.description, color = Color(0xFFB9CBE1), fontSize = 11.sp)
                    Text("${plugin.capability} • ${plugin.execution}", color = Color(0xFF8BD8FF), fontSize = 9.sp)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CONNECTED PLUGINS", color = Color(0xFF8BD8FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = { showAdd = true }) { Text("＋ CONNECT") }
        }
        if (plugins.isEmpty()) Text("Abhi koi external plugin connected nahi hai. Apne HTTPS webhook ka endpoint add karo.", color = Color(0xFFB9CBE1), fontSize = 11.sp)
        plugins.forEach { plugin ->
            Surface(color = Color(0xFF0A1B31), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color(0xFF34516F)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(plugin.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(plugin.description.ifBlank { plugin.endpoint }, color = Color(0xFFB9CBE1), fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { selected = plugin; actionInput = ""; result = ""; error = ""; pendingRun = false }) { Text("RUN") }
                        TextButton(onClick = {
                            plugins = plugins.filterNot { it.id == plugin.id }
                            PluginConnections.save(context, plugins)
                            PluginVault.remove(context, plugin.id)
                            logs = (listOf("Disconnected ${plugin.name}") + logs).take(10)
                        }) { Text("DISCONNECT", color = Color(0xFFFF9A9A)) }
                    }
                }
            }
        }
        if (logs.isNotEmpty()) {
            Text("RECENT ACTIVITY", color = Color(0xFF8BD8FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            logs.take(5).forEach { Text(it, color = Color(0xFF91A4BD), fontSize = 10.sp) }
        }
        Text("Security: HTTPS only • bearer token encrypted with Android Keystore • 8s connect / 10s read timeout • redirects blocked • each run requires confirmation. Plugin endpoint ko sirf tab connect karo jab tum us provider par trust karte ho.", color = Color(0xFFFFD38A), fontSize = 10.sp)
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Connect HTTPS Plugin") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Ye v1 connector generic webhook API ke liye hai. Endpoint POST JSON { input, source } receive kare.", color = Color(0xFFB9CBE1), fontSize = 11.sp)
                    OutlinedTextField(value = addName, onValueChange = { addName = it.take(40) }, label = { Text("Plugin name") }, singleLine = true)
                    OutlinedTextField(value = addId, onValueChange = { addId = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(40) }, label = { Text("Plugin ID (e.g. notion_tools)") }, singleLine = true)
                    OutlinedTextField(value = addEndpoint, onValueChange = { addEndpoint = it.take(1000) }, label = { Text("HTTPS POST endpoint") }, singleLine = true)
                    OutlinedTextField(value = addDescription, onValueChange = { addDescription = it.take(120) }, label = { Text("What does it do?") }, singleLine = true)
                    OutlinedTextField(value = addToken, onValueChange = { addToken = it.take(2000) }, label = { Text("Bearer token (optional)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    if (error.isNotBlank()) Text(error, color = Color(0xFFFF9A9A), fontSize = 10.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!validPlugin(addId, addName, addEndpoint)) { error = "Valid ID, name aur HTTPS endpoint required."; return@TextButton }
                    if (plugins.any { it.id == addId }) { error = "Ye plugin ID already connected hai."; return@TextButton }
                    if (plugins.size >= 20) { error = "Maximum 20 custom plugins allowed."; return@TextButton }
                    val plugin = ConnectedShadowPlugin(addId, addName.trim(), addEndpoint.trim(), addDescription.trim())
                    runCatching { PluginVault.put(context, plugin.id, addToken.trim()) }
                        .onFailure { error = "Secure credential save nahi hua."; return@TextButton }
                    plugins = plugins + plugin
                    PluginConnections.save(context, plugins)
                    logs = (listOf("Connected ${plugin.name}") + logs).take(10)
                    addName = ""; addId = ""; addEndpoint = ""; addDescription = ""; addToken = ""; error = ""; showAdd = false
                }) { Text("CONNECT") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false; error = "" }) { Text("CANCEL") } },
            containerColor = Color(0xFF101B2B), titleContentColor = Color.White, textContentColor = Color(0xFFB8C9DD)
        )
    }

    selected?.let { plugin ->
        AlertDialog(
            onDismissRequest = { if (!running) selected = null },
            title = { Text("Run ${plugin.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SHADOW tumhare input ko is external endpoint par bhejega. Is endpoint ke owner ko data mil sakta hai.", color = Color(0xFFFFD38A), fontSize = 11.sp)
                    OutlinedTextField(value = actionInput, onValueChange = { actionInput = it.take(2000) }, label = { Text("Instruction / input") }, minLines = 2, maxLines = 4)
                    if (result.isNotBlank()) Text(result, color = Color(0xFFB9CBE1), fontSize = 10.sp)
                    if (error.isNotBlank()) Text(error, color = Color(0xFFFF9A9A), fontSize = 10.sp)
                    if (running) Text("Plugin is running…", color = Color(0xFF8BD8FF), fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (actionInput.isBlank() || running) { error = "Instruction enter karo."; return@TextButton }
                    pendingRun = true
                }, enabled = !running) { Text("REVIEW & RUN") }
            },
            dismissButton = { TextButton(onClick = { if (!running) selected = null }) { Text("CLOSE") } },
            containerColor = Color(0xFF101B2B), titleContentColor = Color.White, textContentColor = Color(0xFFB8C9DD)
        )
    }

    if (pendingRun && selected != null) {
        AlertDialog(
            onDismissRequest = { pendingRun = false },
            title = { Text("Confirm plugin action") },
            text = { Text("Send this input to ${selected!!.name}?\n\n${actionInput.take(240)}\n\nOnly continue if you trust this plugin endpoint.") },
            confirmButton = {
                TextButton(onClick = {
                    val plugin = selected ?: return@TextButton
                    pendingRun = false
                    running = true
                    error = ""; result = "Request sent…"
                    scope.launch {
                        val response = runCatching { invokePlugin(plugin.endpoint, PluginVault.get(context, plugin.id), actionInput) }
                        running = false
                        response.onSuccess {
                            result = it
                            logs = (listOf("Ran ${plugin.name} • ${if (it.startsWith("Plugin returned")) "error" else "complete"}") + logs).take(10)
                        }.onFailure {
                            error = it.message?.take(300) ?: "Plugin request failed"
                            result = ""
                            logs = (listOf("Failed ${plugin.name}") + logs).take(10)
                        }
                    }
                }) { Text("SEND & RUN") }
            },
            dismissButton = { TextButton(onClick = { pendingRun = false }) { Text("CANCEL") } },
            containerColor = Color(0xFF101B2B), titleContentColor = Color.White, textContentColor = Color(0xFFB8C9DD)
        )
    }
}
