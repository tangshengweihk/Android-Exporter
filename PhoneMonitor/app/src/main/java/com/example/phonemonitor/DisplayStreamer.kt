package com.example.phonemonitor

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log

class DisplayStreamer {
    companion object {
        private const val TAG = "DisplayStreamer"
        private const val MIME_TYPE = "video/vp8"
        private const val WIDTH = 1080
        private const val HEIGHT = 1920
        private const val BITRATE = 6000000
        private const val FRAMERATE = 30
        private const val I_FRAME_INTERVAL = 1
    }

    private var mediaCodec: MediaCodec? = null
    private var isRunning = false

    fun start() {
        if (isRunning) return
        initEncoder()
        isRunning = true
        Log.d(TAG, "DisplayStreamer started")
    }

    fun stop() {
        if (!isRunning) return
        releaseEncoder()
        isRunning = false
        Log.d(TAG, "DisplayStreamer stopped")
    }

    private fun initEncoder() {
        try {
            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            val format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAMERATE)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing encoder: ${e.message}", e)
        }
    }

    private fun releaseEncoder() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder: ${e.message}", e)
        }
    }
} 