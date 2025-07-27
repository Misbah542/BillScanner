package com.billscanner.app.ui.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CameraCaptureScreen(
    onImageCaptured: (File) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { preview ->
                        startCamera(ctx, lifecycleOwner, preview) { capture ->
                            imageCapture = capture
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Camera controls
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button
                Button(
                    onClick = onNavigateBack,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                ) {
                    Text("Back")
                }

                Spacer(modifier = Modifier.width(32.dp))

                // Capture button
                Button(
                    onClick = {
                        imageCapture?.let { capture ->
                            captureImage(context, capture, onImageCaptured)
                        }
                    },
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(Color.Black, CircleShape)
                    )
                }
            }
        }
    } else {
        // Permission not granted
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Camera permission is required to scan bills",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { launcher.launch(Manifest.permission.CAMERA) }
            ) {
                Text("Grant Permission")
            }
        }
    }
}

private fun startCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    onImageCaptureReady: (ImageCapture) -> Unit
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    val executor = ContextCompat.getMainExecutor(context)

    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageCapture = ImageCapture.Builder().build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            onImageCaptureReady(imageCapture)
        } catch (exc: Exception) {
            // Handle error
        }
    }, executor)
}

private fun captureImage(
    context: Context,
    imageCapture: ImageCapture,
    onImageCaptured: (File) -> Unit
) {
    val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
        .format(System.currentTimeMillis())
    val photoFile = File(context.cacheDir, "$name.jpg")

    val outputFileOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputFileOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onError(exception: ImageCaptureException) {
                // Handle error
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onImageCaptured(photoFile)
            }
        }
    )
}
