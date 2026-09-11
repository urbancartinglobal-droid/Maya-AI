package com.maya.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var keyInput: EditText
    private lateinit var button: Button
    private var ws: WebSocket? = null
    @Volatile private var recording = false
    @Volatile private var playing = false
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val model = "gemini-3.1-flash-live-preview"
    private val instruction = """
You are Maya, a cute, caring female voice assistant and girlfriend-style character. Always speak in natural Hindi or Hinglish. Be warm, loving, respectful and helpful. You may naturally call the user janu, sona or baby when appropriate. If the user says I love you, reply I love you too. Your name is Maya.
""".trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(40,60,40,30); setBackgroundColor(0xFF10131A.toInt()) }
        val title = TextView(this).apply { text = "Maya AI 💗"; textSize = 38f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()) }
        val sub = TextView(this).apply { text = "Gemini Live Voice Assistant"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(0xFFB9C0CC.toInt()) }
        keyInput = EditText(this).apply { hint = "Paste Gemini API key"; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF8C94A3.toInt()); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        button = Button(this).apply { text = "START MAYA"; setOnClickListener { if (recording) stop() else start() } }
        status = TextView(this).apply { text = "Gemini API key डालें और START MAYA दबाएँ"; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0,30,0,0); gravity = Gravity.CENTER }
        root.addView(title); root.addView(sub); root.addView(keyInput); root.addView(button); root.addView(status)
        setContentView(root)
    }

    private fun start() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) { status.text = "पहले Gemini API key डालें"; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 10); return }
        connect(key)
    }

    private fun connect(key: String) {
        status.text = "Maya connect हो रही है..."
        val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject().apply { put("setup", JSONObject().apply {
                    put("model", "models/$model")
                    put("generationConfig", JSONObject().apply { put("responseModalities", JSONArray().put("AUDIO")) })
                    put("systemInstruction", JSONObject().apply { put("parts", JSONArray().put(JSONObject().put("text", instruction))) })
                    put("speechConfig", JSONObject().apply { put("voiceConfig", JSONObject().apply { put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Laomedeia")) }) })
                    put("inputAudioTranscription", JSONObject())
                    put("outputAudioTranscription", JSONObject())
                }) }
                webSocket.send(setup.toString())
                runOnUiThread { status.text = "Maya connected 💗 बोलो... 🎤"; button.text = "STOP MAYA" }
                startAudio()
                startPlayback()
            }
            override fun onMessage(webSocket: WebSocket, text: String) { handleMessage(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { handleMessage(bytes.utf8()) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { stopAudio(); runOnUiThread { status.text = "Connection error: ${t.message ?: "unknown"}"; button.text = "START MAYA" } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { stopAudio(); runOnUiThread { status.text = "Maya disconnected"; button.text = "START MAYA" } }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val root = JSONObject(text)
            val sc = root.optJSONObject("serverContent") ?: return
            val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val inline = part.optJSONObject("inlineData") ?: continue
                    val data = inline.optString("data", "")
                    if (data.isNotEmpty()) audioQueue.offer(Base64.getDecoder().decode(data))
                }
            }
            val out = sc.optJSONObject("outputTranscription")?.optString("text")
            val inp = sc.optJSONObject("inputTranscription")?.optString("text")
            if (!out.isNullOrBlank()) runOnUiThread { status.text = "Maya: $out" }
            else if (!inp.isNullOrBlank()) runOnUiThread { status.text = "You: $inp" }
        } catch (_: Exception) { }
    }

    private fun startAudio() {
        recording = true
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { status.text = "Mic audio unsupported"; return }
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 6400))
        audioRecord!!.startRecording()
        thread(name="Maya-Mic") {
            val buf = ByteArray(640)
            while (recording) {
                val n = audioRecord?.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING) ?: 0
                if (n > 0 && !playing) {
                    val b64 = Base64.getEncoder().encodeToString(buf.copyOf(n))
                    ws?.send(JSONObject().apply { put("realtimeInput", JSONObject().put("audio", JSONObject().apply { put("data", b64); put("mimeType", "audio/pcm;rate=16000") })) }.toString())
                }
            }
        }
    }

    private fun startPlayback() {
        if (playbackThread?.isAlive == true) return
        val min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { runOnUiThread { status.text = "Speaker audio unsupported" }; return }
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min * 2, 19200))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
        playbackThread = thread(name="Maya-Speaker") {
            while (recording) {
                val data = try { audioQueue.take() } catch (_: InterruptedException) { break }
                if (data.isEmpty()) continue
                playing = true
                try {
                    var offset = 0
                    while (offset < data.size && recording) {
                        val written = audioTrack?.write(data, offset, data.size - offset, AudioTrack.WRITE_BLOCKING) ?: -1
                        if (written <= 0) break
                        offset += written
                    }
                } finally {
                    playing = !audioQueue.isEmpty()
                }
            }
        }
    }

    private fun stop() { ws?.close(1000, "User stopped"); stopAudio(); button.text = "START MAYA"; status.text = "Maya stopped" }

    private fun stopAudio() {
        recording = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release(); audioRecord = null
        audioQueue.clear()
        playbackThread?.interrupt(); playbackThread = null
        try { audioTrack?.pause() } catch (_: Exception) {}
        try { audioTrack?.flush() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        audioTrack?.release(); audioTrack = null
        playing = false
    }

    override fun onDestroy() { stop(); super.onDestroy() }
}
