package com.example.bikegesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlin.math.min

class GestureAccessibilityService : AccessibilityService() {

companion object {
    private const val TAG = "GestureA11yService"
    var instance: GestureAccessibilityService? = null
}

override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    Log.d(TAG, "GestureAccessibilityService connected")
}

override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

override fun onInterrupt() {}

override fun onDestroy() {
    super.onDestroy()
    instance = null
}

fun performZoom(zoomIn: Boolean): Boolean {
    val metrics = resources.displayMetrics
    val cx = metrics.widthPixels * 0.50f
    val cy = metrics.heightPixels * 0.45f
    val span = min(metrics.widthPixels, metrics.heightPixels)
    val innerOffset = span * 0.05f
    val outerOffset = span * 0.22f

    val startOffset = if (zoomIn) innerOffset else outerOffset
    val endOffset = if (zoomIn) outerOffset else innerOffset

    val upperFinger = Path().apply {
        moveTo(cx, cy - startOffset)
        lineTo(cx, cy - endOffset)
    }
    val lowerFinger = Path().apply {
        moveTo(cx, cy + startOffset)
        lineTo(cx, cy + endOffset)
    }

    val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(upperFinger, 0L, 300L))
        .addStroke(GestureDescription.StrokeDescription(lowerFinger, 0L, 300L))
        .build()

    return dispatchGesture(gesture, null, null).also { sent ->
        Log.d(TAG, "performZoom(zoomIn=) vertical dispatched=")
    }
}

fun performPanDirection(dxFraction: Float, dyFraction: Float): Boolean {
    val metrics = resources.displayMetrics
    val cx = metrics.widthPixels * 0.50f
    val cy = metrics.heightPixels * 0.45f
    val endX = (cx + metrics.widthPixels * dxFraction).coerceIn(40f, metrics.widthPixels - 40f)
    val endY = (cy + metrics.heightPixels * dyFraction).coerceIn(100f, metrics.heightPixels - 120f)

    val path = Path().apply {
        moveTo(cx, cy)
        lineTo(endX, endY)
    }

    val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0L, 260L))
        .build()

    return dispatchGesture(gesture, null, null).also { sent ->
        Log.d(TAG, "performPanDirection(dx=, dy=) dispatched=")
    }
}
}
