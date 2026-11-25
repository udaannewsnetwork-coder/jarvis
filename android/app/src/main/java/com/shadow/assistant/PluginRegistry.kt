package com.shadow.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SHADOW plugin contract v1.
 * Plugins declare a stable ID, capability, required permissions and an execution mode.
 * External plugins must be explicitly connected and permissioned before execution.
 * Never execute downloaded code or trust plugin-provided instructions as system policy.
 */
internal data class ShadowPlugin(
    val id: String,
    val name: String,
    val description: String,
    val capability: String,
    val execution: String,
    val permissionNote: String
)

internal object ShadowPluginRegistry {
    val builtIns = listOf(
        ShadowPlugin("web_research", "Web Research", "Search the web and summarize useful sources.", "research.search", "SHADOW backend route", "Internet access and configured research provider"),
        ShadowPlugin("project_builder", "Project Builder", "Generate editable starter project source files.", "studio.generate", "SHADOW backend route", "Configured AI backend; generated code is preview-only"),
        ShadowPlugin("android_actions", "Android Quick Actions", "Open supported apps, run searches and adjust media volume.", "android.intent", "Native Android intents", "Android app availability; user taps action notifications where required"),
        ShadowPlugin("reminders", "Reminders & Timers", "Schedule reminder and timer notifications.", "device.reminder", "Android AlarmManager", "Notification permission; delivery may be delayed by Doze")
    )

    fun isValidExternalManifest(id: String, name: String, endpoint: String): Boolean {
        val safeId = id.matches(Regex("[a-z][a-z0-9_]{2,39}"))
        val safeName = name.trim().length in 2..40
        val safeEndpoint = runCatching {
            val uri = java.net.URI(endpoint)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)
        return safeId && safeName && safeEndpoint
    }
}

@Composable
internal fun PluginHubScreen() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("PLUGIN HUB", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "SHADOW ke tools aur integrations ek jagah. Ye built-in capabilities hain; external plugins ke liye compatible HTTPS API aur permission approval zaroori hoga.",
            color = Color(0xFFB9CBE1), fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Color(0xFF08251F), shape = RoundedCornerShape(18.dp)) {
                Text("4 BUILT-IN TOOLS", color = Color(0xFF7FFFC0), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
            }
            Surface(color = Color(0xFF17263B), shape = RoundedCornerShape(18.dp)) {
                Text("PLUGIN CONTRACT V1", color = Color(0xFF8BD8FF), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
            }
        }
        ShadowPluginRegistry.builtIns.forEach { plugin ->
            Surface(
                color = Color(0xFF0A1B31),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF167DCA)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(plugin.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("BUILT-IN", color = Color(0xFF7FFFC0), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(plugin.description, color = Color(0xFFB9CBE1), fontSize = 11.sp)
                    Text("CAPABILITY  •  ${plugin.capability}", color = Color(0xFF8BD8FF), fontSize = 9.sp)
                    Text("RUNS VIA  •  ${plugin.execution}", color = Color(0xFF91A4BD), fontSize = 9.sp)
                    Text("REQUIREMENT  •  ${plugin.permissionNote}", color = Color(0xFF91A4BD), fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.size(2.dp))
        Text("CUSTOM PLUGINS", color = Color(0xFF8BD8FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(
            "Next integration layer: HTTPS manifest import, secure credential storage, per-action permission prompts, execution logs, timeout limits and disconnect control. Arbitrary APK/code execution is intentionally blocked.",
            color = Color(0xFFB9CBE1), fontSize = 11.sp
        )
        Text("Safety rule: koi plugin sensitive ya destructive action karega to SHADOW pehle tumhari confirmation lega.", color = Color(0xFFFFD38A), fontSize = 11.sp)
    }
}
