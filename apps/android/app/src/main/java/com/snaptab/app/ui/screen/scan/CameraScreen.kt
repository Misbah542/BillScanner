package com.snaptab.app.ui.screen.scan

import android.Manifest
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.snaptab.app.R
import com.snaptab.app.ui.components.ErrorBanner
import com.snaptab.app.ui.components.PrimaryButton
import com.snaptab.app.ui.components.SecondaryButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The camera. Everything that can fail here is surfaced, which is the opposite of what the
 * old screen did: it swallowed the bind exception in an empty catch and left
 * `OnImageSavedCallback.onError` empty, so a failed capture did nothing at all and the
 * user could tap the shutter forever with no feedback.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    onCaptured: (File) -> Unit,
    onClose: () -> Unit,
    onEnterByHand: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permission = rememberPermissionState(Manifest.permission.CAMERA)

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!permission.status.isGranted) permission.launchPermissionRequest()
    }

    if (!permission.status.isGranted) {
        CameraPermissionPrompt(
            showRationale = permission.status.shouldShowRationale,
            onGrant = { permission.launchPermissionRequest() },
            onEnterByHand = onEnterByHand,
            onClose = onClose
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1814))) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { previewView ->
                    startCamera(
                        context = ctx,
                        lifecycleOwner = lifecycleOwner,
                        previewView = previewView,
                        onReady = { capture ->
                            imageCapture = capture
                            cameraError = null
                        },
                        onError = { message -> cameraError = message }
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // The receipt frame, so people know to fill it edge to edge.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.84f)
                .fillMaxHeight(0.52f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.06f))
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White
                )
            }
            Text(
                text = stringResource(R.string.scan_a_bill),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.size(48.dp))
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            cameraError?.let { message ->
                ErrorBanner(message = message, onDismiss = { cameraError = null })
                Spacer(Modifier.height(14.dp))
            }

            Text(
                text = stringResource(R.string.scan_guidance),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedIconButton(
                    onClick = onEnterByHand,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.enter_by_hand),
                        tint = Color.White
                    )
                }

                // 84dp, well past the 44dp minimum: this is the one control that matters.
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    FilledIconButton(
                        onClick = {
                            val capture = imageCapture
                            if (capture == null) {
                                cameraError = context.getString(R.string.camera_failed, "")
                                return@FilledIconButton
                            }
                            capturing = true
                            captureTo(
                                context = context,
                                imageCapture = capture,
                                onSaved = { file ->
                                    capturing = false
                                    onCaptured(file)
                                },
                                onError = { message ->
                                    capturing = false
                                    cameraError = context.getString(R.string.capture_failed, message)
                                }
                            )
                        },
                        enabled = !capturing,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.size(64.dp)
                    ) {
                        if (capturing) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Icon(
                                Icons.Outlined.PhotoLibrary,
                                contentDescription = stringResource(R.string.capture),
                                tint = Color.White
                            )
                        }
                    }
                }

                Spacer(Modifier.size(56.dp))
            }
        }
    }
}

@Composable
private fun CameraPermissionPrompt(
    showRationale: Boolean,
    onGrant: () -> Unit,
    onEnterByHand: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.camera_permission_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.camera_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(text = stringResource(R.string.grant_permission), onClick = onGrant)
        Spacer(Modifier.height(10.dp))
        // Always offer the way round it: a refused camera must not be a dead end.
        SecondaryButton(text = stringResource(R.string.enter_by_hand), onClick = onEnterByHand)
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.cancel))
        }
        if (showRationale) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.camera_permission_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun startCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    onReady: (ImageCapture) -> Unit,
    onError: (String) -> Unit
) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener({
        try {
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                // A receipt needs detail more than it needs a fast shutter.
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture
            )
            onReady(capture)
        } catch (error: Exception) {
            // Reported rather than swallowed: the old code caught this and did nothing,
            // leaving a frozen black preview with no explanation.
            onError(error.message ?: "The camera is unavailable.")
        }
    }, ContextCompat.getMainExecutor(context))
}

private fun captureTo(
    context: Context,
    imageCapture: ImageCapture,
    onSaved: (File) -> Unit,
    onError: (String) -> Unit
) {
    val name = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(System.currentTimeMillis())
    // The app's own cache directory, so no storage permission is needed and the file is
    // cleaned up by the system if space runs short.
    val photo = File(context.cacheDir, "receipt-$name.jpg")

    imageCapture.takePicture(
        ImageCapture.OutputFileOptions.Builder(photo).build(),
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onSaved(photo)
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception.message ?: "unknown error")
            }
        }
    )
}
