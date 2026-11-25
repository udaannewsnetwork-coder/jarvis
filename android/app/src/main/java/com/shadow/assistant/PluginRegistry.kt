package com.shadow.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class PluginAction(
    val id: String,
    val name: String,
    val description: String,
    val path: String,
    val requiresConfirmation: Boolean
)

internal data class ExternalPlugin(
    val id: String,
    val name: String,
    val description: String,
    val baseUrl: String,
    val actions: List<PluginAction>
) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("description", description)
        .put("base_url", baseUrl).put("actions", JSONArray().apply {
            actions.forEach { put(JSONObject().put("id", it.id).put("name", it.name)
                .put("description", it.description).put("path", it.path)
                .put("requires_confirmation", it.requiresConfirmation)) }
        })

    companion object {
        fun parse(raw: String): ExternalPlugin {
            require(raw.length <= 64_000) { "Plugin manifest is too large (64 KB max)" }
            val json = JSONObject(raw)
            require(json.optInt("schema_version") == 1) { "Unsupported plugin manifest version" }
            val id = json.getString("id")
            val name = json.getString("name").trim()
            val description = json.optString("description").take(240)
            val base = json.getString("base_url").trim().trimEnd('/')
            require(id.matches(Regex("[a-z][a-z0-9_]{2,39}"))) { "Invalid plugin ID" }
            require(name.length in 2..40) { "Plugin name must be 2–40 characters" }
            val baseUri = URI(base)
            require(baseUri.scheme == "https" && !baseUri.host.isNullOrBlank() && baseUri.userInfo == null &&
                baseUri.query == null && baseUri.fragment == null) { "Plugin base URL must be a clean HTTPS URL" }
            val rawActions = json.optJSONArray("actions") ?: JSONArray()
            require(rawActions.length() in 1..20) { "Plugin must declare 1–20 actions" }
            val actions = (0 until rawActions.length()).map { index ->
                val item = rawActions.getJSONObject(index)
                val actionId = item.getString("id")
                val path = item.getString("path")
                require(actionId.matches(Regex("[a-z][a-z0-9_]{1,39}"))) { "Invalid action ID" }
                require(path.startsWith("/") && !path.startsWith("//") && !path.contains("..") &&
                    !path.contains("?") && !path.contains("#") && path.length <= 180) { "Unsafe action path" }
                PluginAction(actionId, item.getString("name").take(40),
                    item.optString("description").take(180), path,
                    item.optBoolean("requires_confirmation", true))
            }
            require(actions.map { it.id }.distinct().size == actions.size) { "Duplicate action IDs" }
            return ExternalPlugin(id, name, description, base, actions)
        }

        fun restore(raw: String): List<ExternalPlugin> = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                runCatching { parse(array.getJSONObject(i).toString()) }.getOrNull()
            }.take(20)
        }.getOrDefault(emptyList())
    }
}

@Composable
internal fun PluginHubScreen(
    plugins: List<ExternalPlugin>,
    status: String,
    busy: Boolean,
    onImport: (String, String) -> Unit,
    onRun: (String, String, String) -> Unit,
    onDisconnect: (String) -> Unit
) {
    var manifestUrl by remember { mutableStateOf("") }
    var bearerToken by remember { mutableStateOf("") }
    var actionPayload by remember { mutableStateOf("{}") }
    var selected by remember { mutableStateOf<Pair<String, PluginAction>?>(null) }
    var showImportHelp by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("PLUGIN HUB", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Compatible HTTPS APIs ko manifest URL se connect karo. SHADOW sirf declared POST actions call karega; downloaded code/APK execute nahi hoga.",
            color = Color(0xFFB9CBE1), fontSize = 11.sp)
        Text("BUILT-IN TOOLS", color = Color(0xFF8BD8FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        listOf(
            "Web Research" to "Search and summarize sources via configured backend",
            "Project Builder" to "Generate editable starter source files",
            "Android Quick Actions" to "Open supported apps and adjust media volume",
            "Reminders & Timers" to "Schedule local reminder notifications"
        ).forEach { item ->
            Surface(color = Color(0xFF0A1B31), shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF167DCA)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.first, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(item.second, color = Color(0xFFB9CBE1), fontSize = 10.sp)
                }
            }
        }
        Text("CONNECT A CUSTOM PLUGIN", color = Color(0xFF8BD8FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = manifestUrl, onValueChange = { manifestUrl = it.take(500) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Plugin manifest HTTPS URL") })
        OutlinedTextField(value = bearerToken, onValueChange = { bearerToken = it.take(2000) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("Bearer token (optional)") })
        Button(onClick = { onImport(manifestUrl.trim(), bearerToken.trim()) }, enabled = !busy && manifestUrl.startsWith("https://"),
            modifier = Modifier.fillMaxWidth()) { Text(if (busy) "CONNECTING…" else "FETCH & CONNECT") }
        Text("Manifest format: JSON v1 with plugin id, name, base_url and actions (POST only).",
            color = Color(0xFF91A4BD), fontSize = 9.sp)
        if (plugins.isNotEmpty()) {
            Text("CONNECTED PLUGINS • ${plugins.size}", color = Color(0xFF8BD8FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            plugins.forEach { plugin ->
                Surface(color = Color(0xFF0A1B31), shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF167DCA)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(plugin.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(plugin.description, color = Color(0xFFB9CBE1), fontSize = 10.sp)
                            }
                            TextButton(onClick = { onDisconnect(plugin.id) }) { Text("REMOVE", color = Color(0xFFFF9A9A), fontSize = 9.sp) }
                        }
                        plugin.actions.forEach { action ->
                            Surface(color = Color(0xFF142B47), shape = RoundedCornerShape(9.dp),
                                modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(horizontal = 9.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(action.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(action.description, color = Color(0xFF91A4BD), fontSize = 9.sp)
                                    }
                                    TextButton(onClick = { selected = plugin.id to action }) { Text("RUN", color = Color(0xFF8BD8FF)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(status, color = Color(0xFFB9CBE1), fontSize = 10.sp)
        Text("Security: HTTPS only • POST only • no redirects • action confirmation • 15s timeout • response size limit.",
            color = Color(0xFFFFD38A), fontSize = 9.sp)
    }
    if (selected != null) {
        val target = selected!!
        AlertDialog(onDismissRequest = { selected = null },
            title = { Text("Run ${target.second.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This sends the JSON below to the plugin provider. Only continue if you trust this service.",
                        color = Color(0xFFB9CBE1), fontSize = 12.sp)
                    OutlinedTextField(value = actionPayload, onValueChange = { actionPayload = it.take(8000) },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("Action input JSON") })
                }
            },
            confirmButton = { TextButton(onClick = {
                onRun(target.first, target.second.id, actionPayload)
                selected = null
            }, enabled = !busy && runCatching { JSONObject(actionPayload) }.isSuccess) { Text("CONFIRM & RUN") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("CANCEL") } },
            containerColor = Color(0xFF101B2B), titleContentColor = Color.White, textContentColor = Color(0xFFB8C9DD)
        )
    }
}


internal object PluginSecretStore {
    private const val PREFS = "shadow_manifest_plugin_secrets"
    private const val KEY_ALIAS = "shadow_manifest_plugin_aes"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun write(context: Context, id: String, secret: String) {
        if (secret.isBlank()) { clear(context, id); return }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("secret_" + id, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun read(context: Context, id: String): String? = runCatching {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("secret_" + id, null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > 12)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
    }.getOrNull()

    fun clear(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("secret_" + id).apply()
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
