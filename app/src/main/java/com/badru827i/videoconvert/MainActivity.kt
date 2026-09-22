package com.badru827i.videoconvert

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

class MainActivity : ComponentActivity() {
    private var inputUri: Uri? = null
    private val pickCode = 1001
    private lateinit var bitrateText: TextView
    private lateinit var fpsText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        bitrateText = findViewById(R.id.bitrateText)
        fpsText = findViewById(R.id.fpsText)
        progress = findViewById(R.id.progressBar)
        status = findViewById(R.id.statusText)

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

        findViewById<Button>(R.id.selectButton).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, pickCode)
        }

        findViewById<Button>(R.id.convertButton).setOnClickListener {
            if (inputUri == null) Toast.makeText(this, "Pilih video dahulu", Toast.LENGTH_SHORT).show()
            else convertVideo()
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
            findViewById<TextView>(R.id.inputText).text = inputUri?.toString() ?: "No video selected"
        }
    }

    private fun convertVideo() {
        val uri = inputUri ?: return
        val codec = findViewById<Spinner>(R.id.codecSpinner).selectedItem.toString()
        val quality = findViewById<Spinner>(R.id.qualitySpinner).selectedItem.toString()
        val resolution = findViewById<Spinner>(R.id.resolutionSpinner).selectedItem.toString()
        val audio = findViewById<Spinner>(R.id.audioSpinner).selectedItem.toString()
        val mbps = findViewById<SeekBar>(R.id.bitrateSeek).progress + 8
        val fps = 30 + findViewById<SeekBar>(R.id.fpsSeek).progress * 10

        val inputFile = File(cacheDir, "input_" + System.currentTimeMillis() + ".mp4")
        contentResolver.openInputStream(uri)?.use { input ->
            inputFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return fail("Unable to read input video")

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

        status.text = "Converting… " + codec + " • " + mbps + " Mbps • " + fps + " FPS"
        progress.progress = 0
        findViewById<Button>(R.id.convertButton).isEnabled = false

        val base = "-y -i " + q(inputFile.absolutePath) + " " + scale +
            " -r " + fps + " -b:v " + mbps + "M -maxrate " + mbps + "M -bufsize " + (mbps * 2) +
            "M -crf " + crf + " " + audioArgs + " -movflags +faststart"
        runFfmpeg(base + " -c:v " + hw + " " + q(outputFile.absolutePath), sw, inputFile, outputFile, outputName)
    }

    private fun runFfmpeg(command: String, fallback: String, input: File, output: File, name: String) {
        FFmpegKit.executeAsync(command, { session ->
            if (ReturnCode.isSuccess(session.returnCode)) saveOutput(output, name)
            else {
                runOnUiThread { status.text = "Hardware encoder unavailable — software fallback…" }
                val retry = command.substringBeforeLast(" -c:v") + " -c:v " + fallback + " " + q(output.absolutePath)
                FFmpegKit.executeAsync(retry, { second ->
                    if (ReturnCode.isSuccess(second.returnCode)) saveOutput(output, name)
                    else fail(second.failStackTrace ?: "FFmpeg conversion failed")
                }, { log -> runOnUiThread { status.text = log.message } }, { stat ->
                    runOnUiThread { progress.progress = stat.videoFrameNumber.toInt() % 100 }
                })
            }
        }, { log -> runOnUiThread { status.text = log.message } }, { stat ->
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
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                file.delete()
                status.text = "DONE ✓ Saved to Movies/VideoConvert"
                progress.progress = 100
                findViewById<Button>(R.id.convertButton).isEnabled = true
            } catch (e: Exception) { fail(e.message ?: "Save failed") }
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
