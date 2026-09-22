package com.badru827i.videoconvert

import android.content.Intent
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var inputUri: Uri? = null
    private val pickCode = 1001

    private lateinit var bitrateText: TextView
    private lateinit var fpsText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var aiSwitch: Switch
    private lateinit var aiStatus: TextView
    private lateinit var aiAnalyzeButton: Button

    private data class VideoInfo(
        val width: Int,
        val height: Int,
        val fps: Float,
        val bitrate: Long,
        val durationMs: Long
    )

    private data class AiRecommendation(
        val codec: String,
        val bitrateMbps: Int,
        val fps: Int,
        val resolution: String,
        val quality: String,
        val summary: String
    )

    private var aiRecommendation: AiRecommendation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        bitrateText = findViewById(R.id.bitrateText)
        fpsText = findViewById(R.id.fpsText)
        progress = findViewById(R.id.progressBar)
        status = findViewById(R.id.statusText)
        aiSwitch = findViewById(R.id.aiSwitch)
        aiStatus = findViewById(R.id.aiStatus)
        aiAnalyzeButton = findViewById(R.id.aiAnalyzeButton)

        setupSpinner(R.id.codecSpinner, listOf("H.265 / HEVC", "H.264 / AVC", "AV1"))
        setupSpinner(R.id.qualitySpinner, listOf("Low", "Medium", "High"))
        setupSpinner(R.id.resolutionSpinner, listOf("Keep source", "720p", "1080p", "2K / 1440p", "4K / 2160p"))
        setupSpinner(R.id.audioSpinner, listOf("AAC 128 kbps", "AAC 192 kbps", "AAC 256 kbps", "Copy audio"))

        findViewById<SeekBar>(R.id.bitrateSeek).setOnSeekBarChangeListener(listener {
            bitrateText.text = (it + 8).toString() + " Mbps"
        })
        findViewById<SeekBar>(R.id.bitrateSeek).progress = 8

        findViewById<SeekBar>(R.id.fpsSeek).setOnSeekBarChangeListener(listener {
            fpsText.text = (30 + it * 10).toString() + " FPS"
        })
        findViewById<SeekBar>(R.id.fpsSeek).progress = 0

        findViewById<Button>(R.id.selectButton).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, pickCode)
        }

        aiSwitch.setOnCheckedChangeListener { _, enabled ->
            aiStatus.text = if (enabled) {
                "AI Smart Optimize aktif — analisis video untuk setting automatik."
            } else {
                "Manual mode — anda kawal semua setting."
            }
        }

        aiAnalyzeButton.setOnClickListener { analyzeWithAi() }

        findViewById<Button>(R.id.convertButton).setOnClickListener {
            if (inputUri == null) {
                Toast.makeText(this, "Pilih video dahulu", Toast.LENGTH_SHORT).show()
            } else {
                convertVideo()
            }
        }
    }

    private fun setupSpinner(id: Int, items: List<String>) {
        findViewById<Spinner>(id).adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun listener(block: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = block(p)
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickCode && resultCode == RESULT_OK) {
            inputUri = data?.data
            aiRecommendation = null
            findViewById<TextView>(R.id.inputText).text =
                inputUri?.toString() ?: "No video selected"
            aiStatus.text = if (inputUri != null) {
                "Video dipilih. Tekan ANALYZE WITH AI."
            } else {
                "Belum ada video."
            }
        }
    }

    private fun analyzeWithAi() {
        val uri = inputUri ?: run {
            Toast.makeText(this, "Pilih video dahulu", Toast.LENGTH_SHORT).show()
            return
        }

        aiAnalyzeButton.isEnabled = false
        aiStatus.text = "AI sedang menganalisis resolusi, FPS, bitrate dan encoder device…"

        Thread {
            try {
                val info = readVideoInfo(uri)
                val recommendation = buildAiRecommendation(info)
                aiRecommendation = recommendation

                runOnUiThread {
                    applyRecommendationToUi(recommendation)
                    aiStatus.text = "AI READY ✓ " + recommendation.summary
                    aiAnalyzeButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    aiStatus.text = "AI ERROR: " + (e.message ?: "Analisis gagal")
                    aiAnalyzeButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun readVideoInfo(uri: Uri): VideoInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toFloatOrNull()?.let { frames ->
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        if (duration > 0) frames * 1000f / duration else 30f
                    } ?: 30f
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            VideoInfo(width, height, fps, bitrate, durationMs)
        } finally {
            retriever.release()
        }
    }

    private fun buildAiRecommendation(info: VideoInfo): AiRecommendation {
        val hasHevcEncoder = hasEncoder("video/hevc")
        val hasAvcEncoder = hasEncoder("video/avc")
        val hasAv1Encoder = hasEncoder("video/av01")

        val codec = when {
            hasHevcEncoder -> "H.265 / HEVC"
            hasAvcEncoder -> "H.264 / AVC"
            hasAv1Encoder -> "AV1"
            else -> "H.265 / HEVC"
        }

        val pixels = info.width.toLong() * info.height.toLong()
        val effectiveFps = info.fps.coerceAtLeast(24f).coerceAtMost(120f)
        val rawTarget = (pixels * effectiveFps * 0.00000007f).roundToInt()
        val bitrate = rawTarget.coerceIn(8, 30)

        val fps = when {
            info.fps >= 120f -> 120
            info.fps >= 90f -> 90
            info.fps >= 60f -> 60
            info.fps >= 50f -> 50
            info.fps >= 40f -> 40
            else -> 30
        }

        val resolution = when {
            info.height >= 2160 -> "4K / 2160p"
            info.height >= 1440 -> "2K / 1440p"
            info.height >= 1080 -> "1080p"
            info.height >= 720 -> "720p"
            else -> "Keep source"
        }

        val quality = when {
            info.bitrate <= 0L -> "Medium"
            info.bitrate / 1_000_000L >= bitrate -> "High"
            info.bitrate / 1_000_000L < bitrate / 2L -> "Medium"
            else -> "High"
        }

        val durationText = if (info.durationMs > 0) {
            String.format("%.1f min", info.durationMs / 60000.0)
        } else {
            "duration n/a"
        }

        val encoderText = when {
            hasHevcEncoder -> "HEVC hardware"
            hasAvcEncoder -> "AVC hardware"
            hasAv1Encoder -> "AV1 hardware"
            else -> "software fallback"
        }

        return AiRecommendation(
            codec = codec,
            bitrateMbps = bitrate,
            fps = fps,
            resolution = resolution,
            quality = quality,
            summary = info.width.toString() + "×" + info.height +
                " • " + info.fps.roundToInt() + " FPS • " + durationText +
                " • " + encoderText
        )
    }

    private fun hasEncoder(mime: String): Boolean {
        return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }

    private fun applyRecommendationToUi(rec: AiRecommendation) {
        selectSpinnerItem(R.id.codecSpinner, rec.codec)
        selectSpinnerItem(R.id.qualitySpinner, rec.quality)
        selectSpinnerItem(R.id.resolutionSpinner, rec.resolution)
        findViewById<SeekBar>(R.id.bitrateSeek).progress = rec.bitrateMbps - 8
        findViewById<SeekBar>(R.id.fpsSeek).progress = ((rec.fps - 30) / 10).coerceIn(0, 9)
        bitrateText.text = rec.bitrateMbps.toString() + " Mbps"
        fpsText.text = rec.fps.toString() + " FPS"
    }

    private fun selectSpinnerItem(id: Int, value: String) {
        val spinner = findViewById<Spinner>(id)
        for (i in 0 until spinner.count) {
            if (spinner.getItemAtPosition(i).toString() == value) {
                spinner.setSelection(i)
                return
            }
        }
    }

    private fun convertVideo() {
        val uri = inputUri ?: return
        val aiRec = if (aiSwitch.isChecked) {
            aiRecommendation ?: run {
                Toast.makeText(this, "Tekan ANALYZE WITH AI dahulu", Toast.LENGTH_SHORT).show()
                return
            }
        } else null

        val codec = aiRec?.codec ?: findViewById<Spinner>(R.id.codecSpinner).selectedItem.toString()
        val quality = aiRec?.quality ?: findViewById<Spinner>(R.id.qualitySpinner).selectedItem.toString()
        val resolution = aiRec?.resolution ?: findViewById<Spinner>(R.id.resolutionSpinner).selectedItem.toString()
        val audio = findViewById<Spinner>(R.id.audioSpinner).selectedItem.toString()
        val mbps = aiRec?.bitrateMbps ?: (findViewById<SeekBar>(R.id.bitrateSeek).progress + 8)
        val fps = aiRec?.fps ?: (30 + findViewById<SeekBar>(R.id.fpsSeek).progress * 10)

        val inputFile = File(cacheDir, "input_" + System.currentTimeMillis() + ".mp4")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                inputFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return fail("Unable to read input video")
        } catch (e: Exception) {
            return fail(e.message ?: "Unable to read input video")
        }

        val outputName = "VideoConvert_" + System.currentTimeMillis() + ".mp4"
        val outputFile = File(cacheDir, outputName)

        val hw = when (codec) {
            "H.264 / AVC" -> "h264_mediacodec"
            "AV1" -> "libaom-av1"
            else -> "hevc_mediacodec"
        }
        val sw = when (codec) {
            "H.264 / AVC" -> "h264"
            "AV1" -> "libaom-av1"
            else -> "hevc_kvazaar"
        }

        val scale = when (resolution) {
            "720p" -> "-vf scale=-2:720"
            "1080p" -> "-vf scale=-2:1080"
            "2K / 1440p" -> "-vf scale=-2:1440"
            "4K / 2160p" -> "-vf scale=-2:2160"
            else -> ""
        }
        val crf = when (quality) {
            "Low" -> "30"
            "High" -> "22"
            else -> "26"
        }
        val audioArgs = when (audio) {
            "Copy audio" -> "-c:a copy"
            "AAC 192 kbps" -> "-c:a aac -b:a 192k"
            "AAC 256 kbps" -> "-c:a aac -b:a 256k"
            else -> "-c:a aac -b:a 128k"
        }

        status.text = if (aiSwitch.isChecked) {
            "AI optimized ✓ • " + codec + " • " + mbps + " Mbps • " + fps + " FPS"
        } else {
            "Converting… " + codec + " • " + mbps + " Mbps • " + fps + " FPS"
        }
        progress.progress = 0
        findViewById<Button>(R.id.convertButton).isEnabled = false

        val base = "-y -i " + q(inputFile.absolutePath) + " " + scale +
            " -r " + fps + " -b:v " + mbps + "M -maxrate " + mbps + "M -bufsize " + (mbps * 2) +
            "M -crf " + crf + " " + audioArgs + " -movflags +faststart"

        runFfmpeg(
            base + " -c:v " + hw + " " + q(outputFile.absolutePath),
            sw,
            inputFile,
            outputFile,
            outputName
        )
    }

    private fun runFfmpeg(command: String, fallback: String, input: File, output: File, name: String) {
        FFmpegKit.executeAsync(command, { session ->
            if (ReturnCode.isSuccess(session.returnCode)) {
                saveOutput(output, name)
            } else {
                runOnUiThread { status.text = "Hardware encoder unavailable — software fallback…" }
                val retry = command.substringBeforeLast(" -c:v") +
                    " -c:v " + fallback + " " + q(output.absolutePath)

                FFmpegKit.executeAsync(retry, { second ->
                    if (ReturnCode.isSuccess(second.returnCode)) {
                        saveOutput(output, name)
                    } else {
                        fail(second.failStackTrace ?: "FFmpeg conversion failed")
                    }
                }, { log ->
                    runOnUiThread { status.text = log.message ?: status.text }
                }, { stat ->
                    runOnUiThread { progress.progress = stat.videoFrameNumber.toInt() % 100 }
                })
            }
        }, { log ->
            runOnUiThread { status.text = log.message ?: status.text }
        }, { stat ->
            runOnUiThread { progress.progress = stat.videoFrameNumber.toInt() % 100 }
        })
    }

    private fun saveOutput(file: File, name: String) {
        runOnUiThread {
            try {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoConvert")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert failed")
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                file.delete()
                status.text = "DONE ✓ Saved to Movies/VideoConvert"
                progress.progress = 100
                findViewById<Button>(R.id.convertButton).isEnabled = true
            } catch (e: Exception) {
                fail(e.message ?: "Save failed")
            }
        }
    }

    private fun fail(message: String) {
        runOnUiThread {
            status.text = "ERROR: " + message
            findViewById<Button>(R.id.convertButton).isEnabled = true
        }
    }

    private fun q(path: String) = "'" + path.replace("'", "'\\''") + "'"
}
