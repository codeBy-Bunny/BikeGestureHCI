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
private var tongueZoomStart = 0L
private var smileZoomStart = 0L
private var panStart = 0L
private var lastPanMode = ""
private var lastActionTime = 0L
private var lastVolumeStepTime = 0L

private val DWELL_TIME_MS = 350L
private val COOLDOWN_MS = 500L
private val VOLUME_STEP_INTERVAL_MS = 850L

fun processBlendshapes(blendshapes: Map<String, Float>) {
    val now = SystemClock.uptimeMillis()

    val jawOpen = blendshapes["jawOpen"] ?: 0f
    val mouthPucker = blendshapes["mouthPucker"] ?: 0f
    val tongueOut = blendshapes["tongueOut"] ?: 0f
    val mouthLeft = blendshapes["mouthLeft"] ?: 0f
    val mouthRight = blendshapes["mouthRight"] ?: 0f
    val smileLeft = blendshapes["mouthSmileLeft"] ?: 0f
    val smileRight = blendshapes["mouthSmileRight"] ?: 0f
    val smile = (smileLeft + smileRight) * 0.5f

    if (jawOpen < 0.55f) {
        jawOpenStart = 0L
    }
    if (mouthPucker < 0.50f) {
        puckerStart = 0L
    }

    // 1. TONGUE OUT -> Zoom In (~10%)
    if (tongueOut > 0.25f) {
        if (tongueZoomStart == 0L) tongueZoomStart = now
        if (now - tongueZoomStart > DWELL_TIME_MS && now - lastActionTime > COOLDOWN_MS) {
            val ok = GestureAccessibilityService.instance?.performZoom(zoomIn = true) ?: false
            playBeep(ToneGenerator.TONE_PROP_BEEP2)
            onStatusUpdate(
                if (ok) String.format(Locale.US, "ACTION: ZOOM IN ~10%% (Tongue %.2f)", tongueOut)
                else "A11Y OFF: ZOOM IN"
            )
            lastActionTime = now
        }
        smileZoomStart = 0L
        panStart = 0L
        lastPanMode = ""
        return
    } else {
        tongueZoomStart = 0L
    }

    // 2. SMILE -> Zoom Out (~10%)
    if (smile > 0.35f) {
        if (smileZoomStart == 0L) smileZoomStart = now
        if (now - smileZoomStart > DWELL_TIME_MS && now - lastActionTime > COOLDOWN_MS) {
            val ok = GestureAccessibilityService.instance?.performZoom(zoomIn = false) ?: false
            playBeep(ToneGenerator.TONE_PROP_BEEP2)
            onStatusUpdate(
                if (ok) String.format(Locale.US, "ACTION: ZOOM OUT ~10%% (Smile %.2f)", smile)
                else "A11Y OFF: ZOOM OUT"
            )
            lastActionTime = now
        }
        panStart = 0L
        lastPanMode = ""
        return
    } else {
        smileZoomStart = 0L
    }

    // 3. MOUTH SHIFT -> Pan Left / Right / Up / Down
    if (mouthPucker < 0.35f && smile < 0.30f) {
        val panMode = when {
            mouthLeft > 0.25f && mouthLeft >= mouthRight && jawOpen in 0.28f..0.60f -> "PAN_UP"
            mouthRight > 0.25f && mouthRight > mouthLeft && jawOpen in 0.28f..0.60f -> "PAN_DOWN"
            mouthLeft > 0.25f && mouthLeft >= mouthRight && jawOpen < 0.35f -> "PAN_LEFT"
            mouthRight > 0.25f && mouthRight > mouthLeft && jawOpen < 0.35f -> "PAN_RIGHT"
            else -> ""
        }

        if (panMode.isNotEmpty()) {
            if (panMode != lastPanMode) {
                lastPanMode = panMode
                panStart = now
            } else if (panStart == 0L) {
                panStart = now
            }

            if (now - panStart > DWELL_TIME_MS && now - lastActionTime > COOLDOWN_MS) {
                val service = GestureAccessibilityService.instance
                if (service == null) {
                    onStatusUpdate("ENABLE ACCESSIBILITY FOR MAP ()")
                } else {
                    when (panMode) {
                        "PAN_LEFT" -> {
                            service.performPanDirection(dxFraction = -0.22f, dyFraction = 0f)
                            playBeep(ToneGenerator.TONE_PROP_BEEP)
                            onStatusUpdate(String.format(Locale.US, "ACTION: PAN LEFT (%.2f)", mouthLeft))
                        }
                        "PAN_RIGHT" -> {
                            service.performPanDirection(dxFraction = 0.22f, dyFraction = 0f)
                            playBeep(ToneGenerator.TONE_PROP_BEEP)
                            onStatusUpdate(String.format(Locale.US, "ACTION: PAN RIGHT (%.2f)", mouthRight))
                        }
                        "PAN_UP" -> {
                            service.performPanDirection(dxFraction = 0f, dyFraction = -0.20f)
                            playBeep(ToneGenerator.TONE_PROP_BEEP)
                            onStatusUpdate(String.format(Locale.US, "ACTION: PAN UP (%.2f)", mouthLeft))
                        }
                        "PAN_DOWN" -> {
                            service.performPanDirection(dxFraction = 0f, dyFraction = 0.20f)
                            playBeep(ToneGenerator.TONE_PROP_BEEP)
                            onStatusUpdate(String.format(Locale.US, "ACTION: PAN DOWN (%.2f)", mouthRight))
                        }
                    }
                }
                lastActionTime = now
            }
        } else {
            lastPanMode = ""
            panStart = 0L
        }
    } else {
        lastPanMode = ""
        panStart = 0L
    }

    // 4. WIDE MOUTH OPEN -> Slower Volume Up
    if (jawOpen > 0.72f && smile < 0.30f) {
        if (jawOpenStart == 0L) jawOpenStart = now
        if (now - jawOpenStart > DWELL_TIME_MS && now - lastVolumeStepTime >= VOLUME_STEP_INTERVAL_MS) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI
            )
            playBeep(ToneGenerator.TONE_PROP_ACK)
            onStatusUpdate("ACTION: VOLUME UP")
            lastVolumeStepTime = now
            lastActionTime = now
        }
    }

    // 5. SUSTAINED LIP PINCH -> Slower Volume Down
    if (mouthPucker > 0.68f) {
        if (puckerStart == 0L) puckerStart = now
        if (now - puckerStart > DWELL_TIME_MS && now - lastVolumeStepTime >= VOLUME_STEP_INTERVAL_MS) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            playBeep(ToneGenerator.TONE_PROP_NACK)
            onStatusUpdate("ACTION: VOLUME DOWN")
            lastVolumeStepTime = now
            lastActionTime = now
        }
    }
}

private fun playBeep(toneType: Int) {
    toneGen.startTone(toneType, 120)
}

fun release() {
    toneGen.release()
}
}
