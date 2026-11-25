package com.example.bikegesture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var faceLandmarker: FaceLandmarker? = null
    private var emulatorFallbackActive = false

    private lateinit var gestureController: OralGestureController
    private lateinit var previewView: PreviewView
    private lateinit var telemetryText: TextView
    private lateinit var statusText: TextView
    private lateinit var simControlsLayout: LinearLayout

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        telemetryText = findViewById(R.id.telemetryText)
        statusText = findViewById(R.id.statusText)
        simControlsLayout = findViewById(R.id.simControlsLayout)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        gestureController = OralGestureController(this) { status ->
            runOnUiThread { statusText.text = status }
        }

        setupMediaPipe()
        setupEmulatorSimButtons()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    private fun setupMediaPipe() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(true)
                .setResultListener { result, _ ->
                    if (result.faceBlendshapes().isPresent && result.faceBlendshapes().get().isNotEmpty()) {
                        val map = result.faceBlendshapes().get()[0].associate { it.categoryName() to it.score() }
                        dispatchBlendshapes(map)
                    }
                }
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(this, options)
            emulatorFallbackActive = false
        } catch (t: Throwable) {
            faceLandmarker = null
            emulatorFallbackActive = true
            runOnUiThread {
                statusText.text = "Emulator fallback active (MediaPipe JNI unavailable on x86_64)"
            }
        }
    }

    private fun dispatchBlendshapes(map: Map<String, Float>) {
        val jaw = map["jawOpen"] ?: 0f
        val pucker = map["mouthPucker"] ?: 0f
        val tongue = map["tongueOut"] ?: 0f

        runOnUiThread {
            val suffix = if (emulatorFallbackActive) " [SIM]" else ""
            telemetryText.text = String.format(
                Locale.US,
                "Jaw: %.2f | Pucker: %.2f | Tongue: %.2f%s",
                jaw, pucker, tongue, suffix
            )
        }
        gestureController.processBlendshapes(map)
    }

    private fun setupEmulatorSimButtons() {
        simControlsLayout.visibility = if (emulatorFallbackActive) View.VISIBLE else View.GONE

        findViewById<Button>(R.id.btnSimJaw).setOnClickListener {
            feedSimulatedPulse(mapOf("jawOpen" to 0.85f))
        }
        findViewById<Button>(R.id.btnSimPucker).setOnClickListener {
            feedSimulatedPulse(mapOf("mouthPucker" to 0.80f))
        }
        findViewById<Button>(R.id.btnSimTongue).setOnClickListener {
            feedSimulatedPulse(mapOf("tongueOut" to 0.80f))
        }
    }

    private fun feedSimulatedPulse(activeScores: Map<String, Float>) {
        cameraExecutor.execute {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 480L) {
                dispatchBlendshapes(activeScores)
                Thread.sleep(50L)
            }
            dispatchBlendshapes(emptyMap())
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val landmarker = faceLandmarker
                        if (landmarker != null && !emulatorFallbackActive) {
                            val rotatedBitmap = imageProxy.toRotatedBitmap()
                            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
                            landmarker.detectAsync(mpImage, imageProxy.imageInfo.timestamp / 1000)
                        }
                        imageProxy.close()
                    }
                }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                imageAnalyzer
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun ImageProxy.toRotatedBitmap(): Bitmap {
        val bitmap = this.toBitmap()
        val matrix = Matrix().apply {
            postRotate(imageInfo.rotationDegrees.toFloat())
            postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        gestureController.release()
        faceLandmarker?.close()
    }
}
