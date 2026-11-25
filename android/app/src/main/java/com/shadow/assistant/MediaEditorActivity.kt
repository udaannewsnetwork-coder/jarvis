package com.shadow.assistant

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import android.widget.VideoView
import android.widget.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class MediaEditorActivity : ComponentActivity() {
    private var source by mutableStateOf<Uri?>(null)
    private var startSeconds by mutableStateOf("0")
    private var endSeconds by mutableStateOf("")
    private var status by mutableStateOf("Import a local audio or video file to begin.")
    private var busy by mutableStateOf(false)
    private var previewDurationMs by mutableStateOf(0)

    private val pickSource = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            source = uri
            status = "Media selected. Preview it, set the trim range and export."
            endSeconds = ""
            previewDurationMs = 0
        }
    }
    private val createOutput = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val input = source
        if (uri != null && input != null) exportTrim(input, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF66BFFF), background = Color(0xFF030812), surface = Color(0xFF101B2B),
                onPrimary = Color.Black, onBackground = Color.White, onSurface = Color.White
            )) {
                Column(
                    Modifier.fillMaxSize().background(Color(0xFF030812)).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("SHADOW MEDIA LAB", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text("LOCAL VIDEO / AUDIO TRIMMER", color = Color(0xFF8BD8FF), style = MaterialTheme.typography.labelLarge)
                    Text("Import media from your device, choose a time range, then save an MP4 copy. Processing stays on this device.", color = Color(0xFFB9CBE1))
                    Button(onClick = { pickSource.launch(arrayOf("video/*", "audio/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("IMPORT AUDIO / VIDEO")
                    }
                    Text(if (source == null) "No media selected" else "Media selected • local preview available", color = Color(0xFF8BD8FF))
                    source?.let { selected ->
                        key(selected) {
                            AndroidView(
                                modifier = Modifier.fillMaxWidth().height(210.dp),
                                factory = { context ->
                                    VideoView(context).apply {
                                        val controls = MediaController(context)
                                        controls.setAnchorView(this)
                                        setMediaController(controls)
                                        setVideoURI(selected)
                                        setOnPreparedListener { player ->
                                            previewDurationMs = player.duration.coerceAtLeast(0)
                                            if (previewDurationMs > 0) endSeconds = String.format(Locale.US, "%.2f", previewDurationMs / 1000.0)
                                            status = "Preview ready • duration ${previewDurationMs / 1000}s"
                                        }
                                    }
                                }
                            )
                        }
                    }
                    OutlinedTextField(value = startSeconds, onValueChange = { startSeconds = it.filter { c -> c.isDigit() || c == '.' }.take(8) },
                        label = { Text("Start time (seconds)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = endSeconds, onValueChange = { endSeconds = it.filter { c -> c.isDigit() || c == '.' }.take(8) },
                        label = { Text("End time (seconds)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { createOutput.launch("shadow-trimmed.mp4") },
                        enabled = source != null && !busy && endSeconds.toDoubleOrNull()?.let { it > (startSeconds.toDoubleOrNull() ?: 0.0) } == true,
                        modifier = Modifier.fillMaxWidth()) { Text(if (busy) "EXPORTING…" else "EXPORT TRIMMED MP4") }
                    Surface(color = Color(0xFF101B2B), shape = MaterialTheme.shapes.medium) {
                        Text(status, Modifier.padding(14.dp), color = Color(0xFFDCEBFF))
                    }
                    Text("Current limits: stream-copy trimming (no re-encoding), MP4 output, and trim boundaries may snap to the nearest video keyframe. Some source codecs/containers may not be supported. This first editor does not yet provide crop, effects, multi-clip timeline, or audio mixing.", color = Color(0xFFFFD38A), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    private fun exportTrim(input: Uri, output: Uri) {
        val startUs = ((startSeconds.toDoubleOrNull() ?: 0.0) * 1_000_000).toLong()
        val endUs = ((endSeconds.toDoubleOrNull() ?: 0.0) * 1_000_000).toLong()
        if (startUs < 0 || endUs <= startUs) { status = "Enter a valid start and end time."; return }
        busy = true
        status = "Preparing local MP4 export…"
        lifecycleScope.launch {
            try {
                val details = withContext(Dispatchers.IO) {
                    MediaTrimProcessor.trim(this@MediaEditorActivity, input, output, startUs, endUs)
                }
                status = "Export complete. ${details.first} tracks • ${details.second} seconds written."
            } catch (error: Exception) {
                status = "Export failed: " + (error.message ?: "unsupported media").take(220)
            } finally { busy = false }
        }
    }
}

private object MediaTrimProcessor {
    fun trim(activity: MediaEditorActivity, input: Uri, output: Uri, startUs: Long, endUs: Long): Pair<Int, Double> {
        val inputPfd = activity.contentResolver.openFileDescriptor(input, "r")
            ?: error("Cannot open selected media")
        val outputPfd = activity.contentResolver.openFileDescriptor(output, "rw")
            ?: error("Cannot open output destination")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(inputPfd.fileDescriptor)
            val trackMap = mutableMapOf<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    trackMap[i] = -1
                }
            }
            require(trackMap.isNotEmpty()) { "No supported audio or video tracks were found" }
            val activeMuxer = MediaMuxer(outputPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            trackMap.keys.forEach { index -> trackMap[index] = activeMuxer.addTrack(extractor.getTrackFormat(index)) }
            activeMuxer.start()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var firstTimestamp = -1L
            var written = 0L
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val time = extractor.sampleTime
                if (time > endUs) break
                if (trackMap.containsKey(track)) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    if (firstTimestamp < 0) firstTimestamp = time
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = (time - firstTimestamp).coerceAtLeast(0)
                    info.flags = extractor.sampleFlags
                    activeMuxer.writeSampleData(trackMap.getValue(track), buffer, info)
                    written = maxOf(written, info.presentationTimeUs)
                }
                extractor.advance()
            }
            require(written > 0L) { "No media samples were found in the selected time range" }
            activeMuxer.stop()
            return trackMap.size to (written / 1_000_000.0)
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
            inputPfd.close()
            outputPfd.close()
        }
    }
}
