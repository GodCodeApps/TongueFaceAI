package com.tongue.face.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tongue.face.ai.facelandmarker.FaceLandmarkerPreviewView
import com.tongue.face.ai.tonguelandmarker.TongueLandmarkerPreviewView

class MainActivity : AppCompatActivity() {
    private lateinit var facePreview: FaceLandmarkerPreviewView
    private lateinit var tonguePreview: TongueLandmarkerPreviewView
    private lateinit var faceButton: Button
    private lateinit var tongueButton: Button

    private var showingFace = true
    private var activityStarted = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (showingFace) {
                facePreview.start(this)
            } else {
                tonguePreview.start(this)
            }
        } else if (!granted) {
            Toast.makeText(this, "需要相机权限才能进行实时人脸预览", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        facePreview = findViewById(R.id.face_preview)
        tonguePreview = findViewById(R.id.tongue_preview)
        faceButton = findViewById(R.id.face_button)
        tongueButton = findViewById(R.id.tongue_button)

        faceButton.setOnClickListener { showFacePage() }
        tongueButton.setOnClickListener { showTonguePage() }
        showFacePage()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        if (showingFace) {
            startFacePreviewIfAllowed()
        }
    }

    override fun onStop() {
        activityStarted = false
        facePreview.stop()
        tonguePreview.stop()
        super.onStop()
    }

    private fun showFacePage() {
        showingFace = true
        facePreview.visibility = View.VISIBLE
        tonguePreview.visibility = View.GONE
        faceButton.isSelected = true
        tongueButton.isSelected = false

        if (activityStarted) {
            startFacePreviewIfAllowed()
        }
    }

    private fun showTonguePage() {
        showingFace = false
        facePreview.stop()
        facePreview.visibility = View.GONE
        tonguePreview.visibility = View.VISIBLE
        faceButton.isSelected = false
        tongueButton.isSelected = true

        if (activityStarted) {
            startTonguePreviewIfAllowed()
        }
    }

    private fun startFacePreviewIfAllowed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            facePreview.start(this)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startTonguePreviewIfAllowed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            tonguePreview.start(this)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}
