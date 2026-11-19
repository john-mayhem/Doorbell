package com.mayhem.doorbell

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Phone side of the call: mic -> 20 ms PCM frames -> [send]; frames from the door -> speaker.
 * 16 kHz mono s16le, same as the ESP32. The playback queue is capped so delay can't pile up.
 */
class AudioEngine(
    ctx: Context,
    private val useMic: Boolean,
    private val send: (ByteArray) -> Unit,
) {
    companion object {
        const val RATE = 16000
        const val FRAME_BYTES = 640           // 320 samples = 20 ms
        private const val MAX_QUEUED = 6      // 120 ms; older frames are dropped
    }

    private val am = ctx.getSystemService(AudioManager::class.java)
    private val queue = LinkedBlockingDeque<ByteArray>()
    @Volatile private var running = false
    @Volatile var muted = false
    /** Door voice volume, 0..100 (a share of the phone's call volume). */
    var volume = 100
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private val threads = mutableListOf<Thread>()

    @SuppressLint("MissingPermission")  // the service only passes useMic=true when RECORD_AUDIO is granted
    fun start(speaker: Boolean) {
        if (running) return
        running = true
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(speaker)

        val outMin = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(maxOf(outMin, FRAME_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        t.setVolume(volume / 100f)
        track = t
        t.play()
        threads += thread(name = "door-speaker") {
            while (running) {
                val f = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                t.write(f, 0, f.size)
            }
        }

        if (useMic) {
            val inMin = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(inMin, FRAME_BYTES * 4)
            )
            rec = r
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.enabled = true
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.enabled = true
            r.startRecording()
            threads += thread(name = "phone-mic") {
                val buf = ByteArray(FRAME_BYTES)
                while (running) {
                    var got = 0
                    while (got < FRAME_BYTES && running) {
                        val n = r.read(buf, got, FRAME_BYTES - got)
                        if (n <= 0) break
                        got += n
                    }
                    if (got == FRAME_BYTES && !muted) send(buf.copyOf())
                }
            }
        }
    }

    fun onDownlink(pcm: ByteArray) {
        if (!running) return
        while (queue.size >= MAX_QUEUED) queue.pollFirst()
        queue.offer(pcm)
    }

    fun setSpeaker(on: Boolean) {
        if (on) {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { am.setCommunicationDevice(it) }
        } else {
            am.clearCommunicationDevice()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        threads.forEach { it.join(500) }
        threads.clear()
        rec?.run { runCatching { stop() }; release() }
        track?.run { runCatching { stop() }; release() }
        rec = null
        track = null
        queue.clear()
        am.clearCommunicationDevice()
        am.mode = AudioManager.MODE_NORMAL
    }
}
