package com.example.bikegesture

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import java.util.Locale

class OralGestureController(
    context: Context,
    private val onStatusUpdate: (String) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)

    private var jawOpenStart = 0L
    private var puckerStart = 0L
    private var lastVolumeStepTime = 0L

    // Responsive tuning for comfortable riding evaluation
    private val JAW_TRIGGER_THRESHOLD = 0.42f
    private val JAW_RELEASE_THRESHOLD = 0.30f
    private val PUCKER_TRIGGER_THRESHOLD = 0.55f
    private val PUCKER_RELEASE_THRESHOLD = 0.40f

    private val DWELL_TIME_MS = 180L
    private val VOLUME_STEP_INTERVAL_MS = 550L

    fun processBlendshapes(blendshapes: Map<String, Float>) {
        val now = SystemClock.uptimeMillis()

        val jawOpen = blendshapes["jawOpen"] ?: 0f
        val mouthPucker = blendshapes["mouthPucker"] ?: 0f

        // Reset immediately on release (hysteresis prevents double-trigger jitter)
        if (jawOpen < JAW_RELEASE_THRESHOLD) {
            jawOpenStart = 0L
        }
        if (mouthPucker < PUCKER_RELEASE_THRESHOLD) {
            puckerStart = 0L
        }

        // 1. MOUTH OPEN -> Volume Up (high sensitivity)
        if (jawOpen >= JAW_TRIGGER_THRESHOLD && mouthPucker < 0.35f) {
            if (jawOpenStart == 0L) jawOpenStart = now
            if (now - jawOpenStart >= DWELL_TIME_MS && now - lastVolumeStepTime >= VOLUME_STEP_INTERVAL_MS) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
                playBeep(ToneGenerator.TONE_PROP_ACK)
                onStatusUpdate(String.format(Locale.US, "ACTION: VOLUME UP (Jaw %.2f)", jawOpen))
                lastVolumeStepTime = now
            }
            return
        }

        // 2. LIP PUCKER -> Volume Down
        if (mouthPucker >= PUCKER_TRIGGER_THRESHOLD && jawOpen < JAW_TRIGGER_THRESHOLD) {
            if (puckerStart == 0L) puckerStart = now
            if (now - puckerStart >= DWELL_TIME_MS && now - lastVolumeStepTime >= VOLUME_STEP_INTERVAL_MS) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                playBeep(ToneGenerator.TONE_PROP_NACK)
                onStatusUpdate(String.format(Locale.US, "ACTION: VOLUME DOWN (Pucker %.2f)", mouthPucker))
                lastVolumeStepTime = now
            }
        }
    }

    private fun playBeep(toneType: Int) {
        toneGen.startTone(toneType, 100)
    }

    fun release() {
        toneGen.release()
    }
}
