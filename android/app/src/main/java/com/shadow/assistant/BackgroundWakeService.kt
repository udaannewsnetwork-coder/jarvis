package com.shadow.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * User-started background voice trigger.
 * Android's installed speech provider performs recognition; this is not a
 * dedicated always-on DSP/local keyword model.
 */
class BackgroundWakeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var awaitingCommand = false
    private var running = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> startListening()
        }
        return START_STICKY
    }

    private fun startListening() {
        if (running) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            updateNotification("Microphone permission required")
            stopSelf()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech recognition provider unavailable")
            stopSelf()
            return
        }
        running = true
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, buildNotification("Listening for “Shadow”"))
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Listening for “Shadow”"))
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply()
        startRecognition()
    }

    private fun startRecognition() {
        if (!running) return
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        updateNotification(if (awaitingCommand) "Wake word heard • say your command" else "Listening for “Shadow”")
                    }
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onError(error: Int) {
                        if (running) handler.postDelayed({ startRecognition() }, RESTART_DELAY_MS)
                    }
                    override fun onResults(results: android.os.Bundle?) {
                        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                        if (phrase.isNotBlank()) processPhrase(phrase)
                        if (running) handler.postDelayed({ startRecognition() }, RESTART_DELAY_MS)
                    }
                    override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
                })
                val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
                speech.startListening(speechIntent)
            }
        } catch (_: Exception) {
            if (running) handler.postDelayed({ startRecognition() }, RESTART_DELAY_MS)
        }
    }

    private fun processPhrase(raw: String) {
        val phrase = raw.trim()
        val lower = phrase.lowercase(Locale.ROOT)
        if (awaitingCommand) {
            awaitingCommand = false
            executeCommand(phrase)
            return
        }
        val wakeIndex = lower.indexOf("shadow")
        if (wakeIndex < 0) return
        val command = phrase.substring(wakeIndex + "shadow".length).trim()
        if (command.isBlank()) {
            awaitingCommand = true
            updateNotification("Shadow activated • say your command")
        } else {
            executeCommand(command)
        }
    }

    private fun executeCommand(raw: String) {
        val command = raw.trim()
        val lower = command.lowercase(Locale.ROOT)
        when {
            lower in listOf("sleep", "stop listening", "stop", "band ho jao") -> {
                stopListening()
                stopSelf()
            }
            lower.contains("volume up") || lower.contains("volume badhao") -> {
                (getSystemService(AUDIO_SERVICE) as AudioManager)
                    .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                updateNotification("Done • volume increased")
            }
            lower.contains("volume down") || lower.contains("volume kam") -> {
                (getSystemService(AUDIO_SERVICE) as AudioManager)
                    .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                updateNotification("Done • volume decreased")
            }
            scheduleReminderIfRequested(command) -> Unit
            lower.startsWith("search ") || lower.startsWith("google ") -> {
                val query = command.substringAfter(' ').trim()
                if (query.isNotBlank()) {
                    val url = "https://www.google.com/search?q=" + Uri.encode(query)
                    showActionNotification("Search ready • tap OPEN to continue", Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } else updateNotification("Say what you want me to search")
            }
            lower.startsWith("open ") || lower.startsWith("kholo ") -> {
                val name = command.substringAfter(' ').trim().lowercase(Locale.ROOT)
                val packageName = when {
                    name.contains("youtube") -> "com.google.android.youtube"
                    name.contains("whatsapp") -> "com.whatsapp"
                    name.contains("chrome") -> "com.android.chrome"
                    name.contains("instagram") -> "com.instagram.android"
                    name.contains("settings") -> null
                    else -> null
                }
                val launch = when {
                    name.contains("settings") -> Intent(android.provider.Settings.ACTION_SETTINGS)
                    packageName != null -> packageManager.getLaunchIntentForPackage(packageName)
                    else -> null
                }
                if (launch != null) showActionNotification("Open $name • tap OPEN", launch)
                else updateNotification("I couldn't find that app. Try YouTube, WhatsApp, Chrome, Instagram or Settings.")
            }
            else -> updateNotification("Command not supported yet • try “open YouTube” or “volume up”")
        }
    }

    private fun scheduleReminderIfRequested(command: String): Boolean {
        val patterns = listOf(
            Regex("(?i)(?:remind me|reminder|yaad dilana|yaad dila dena|mujhe yaad dilana)\\s+(?:in\\s+)?(\\d+)\\s*(minutes?|mins?|min|hours?|hrs?|hr|ghante|ghanta)\\s*(?:to|about|ke liye|baad)?\\s*(.*)"),
            Regex("(?i)(\\d+)\\s*(minutes?|mins?|min|hours?|hrs?|hr|ghante|ghanta)\\s*(?:baad|later)\\s*(?:remind me|yaad dilana)?\\s*(.*)")
        )
        val match = patterns.firstNotNullOfOrNull { it.find(command) } ?: return false
        val amount = match.groupValues[1].toLongOrNull() ?: return false
        val unit = match.groupValues[2].lowercase(Locale.ROOT)
        if (amount !in 1..10080) {
            updateNotification("Reminder time must be between 1 minute and 7 days")
            return true
        }
        val factor = if (unit.startsWith("h") || unit.startsWith("gh")) 3_600_000L else 60_000L
        val note = match.groupValues[3].trim().ifBlank { "Your SHADOW reminder" }.take(180)
        val alarm = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        val reminderIntent = Intent(this, ReminderReceiver::class.java).putExtra(ReminderReceiver.EXTRA_TEXT, note)
        val requestCode = (note + System.currentTimeMillis()).hashCode()
        val pending = PendingIntent.getBroadcast(
            this, requestCode, reminderIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.setAndAllowWhileIdle(
            android.app.AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + amount * factor,
            pending
        )
        updateNotification("Reminder set in $amount $unit: $note")
        return true
    }

    private fun showActionNotification(message: String, target: Intent) {
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val openIntent = PendingIntent.getActivity(
            this, 42, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 43, Intent(this, BackgroundWakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("SHADOW")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_view, "OPEN", openIntent)
            .addAction(android.R.drawable.ic_media_pause, "STOP", stopIntent)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(message: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 43, Intent(this, BackgroundWakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("SHADOW background assistant")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "STOP LISTENING", stopIntent)
            .build()
    }

    private fun updateNotification(message: String) {
        if (!running) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "SHADOW background listening", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Visible notification while SHADOW listens for its wake word"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun stopListening() {
        running = false
        awaitingCommand = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.shadow.assistant.action.START_WAKE"
        const val ACTION_STOP = "com.shadow.assistant.action.STOP_WAKE"
        const val PREFS = "shadow_background_voice"
        const val KEY_ENABLED = "wake_enabled"
        private const val CHANNEL_ID = "shadow_background_voice"
        private const val NOTIFICATION_ID = 7301
        private const val RESTART_DELAY_MS = 900L
    }
}
