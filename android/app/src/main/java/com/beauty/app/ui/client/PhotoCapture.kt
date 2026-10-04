package com.beauty.app.ui.client

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.beauty.app.R
import java.io.File
import java.io.FileOutputStream

/** Same bound as the web client's `compressImage(file, 1200, …)`. */
internal const val UPLOAD_PHOTO_MAX_WIDTH = 1200
private const val UPLOAD_PHOTO_QUALITY = 85
private const val CAPTURE_DIR = "camera_photos"
private const val STALE_CAPTURE_MILLIS = 24L * 60 * 60 * 1000

/** Starts the camera or the photo picker; whichever succeeds reports its image through `onPicked`. */
class PhotoSource internal constructor(
    val cameraAvailable: Boolean,
    private val startCamera: () -> Unit,
    private val startGallery: () -> Unit
) {
    fun takePhoto() = startCamera()
    fun pickFromGallery() = startGallery()
}

/**
 * Camera and gallery sources for procedure photos.
 *
 * The camera is the device's own camera app (`ACTION_IMAGE_CAPTURE`): it brings
 * focus, flash and HDR, and since the manifest does not declare `CAMERA` no
 * runtime permission is needed to start it. The shot goes to an app-private
 * cache file shared through FileProvider, so photographs of clients never land
 * in the public gallery or a cloud photo backup. The gallery is the system
 * photo picker, which needs no storage permission either.
 */
@Composable
fun rememberPhotoSource(onPicked: (Uri) -> Unit): PhotoSource {
    val context = LocalContext.current
    val currentOnPicked by rememberUpdatedState(onPicked)
    // Saved so the shot still arrives if the system reclaims the app while the camera is open.
    var pendingCapture by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCapture ?: return@rememberLauncherForActivityResult
        pendingCapture = null
        if (saved) currentOnPicked(uri) else deleteCapturedPhoto(context, uri)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(currentOnPicked)
    }
    val cameraAvailable = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    return remember(context, cameraAvailable, camera, gallery) {
        PhotoSource(
            cameraAvailable = cameraAvailable,
            startCamera = {
                val uri = newCaptureUri(context)
                pendingCapture = uri
                try {
                    camera.launch(uri)
                } catch (_: ActivityNotFoundException) {
                    pendingCapture = null
                    deleteCapturedPhoto(context, uri)
                    Toast.makeText(context, R.string.no_camera_app, Toast.LENGTH_LONG).show()
                }
            },
            startGallery = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        )
    }
}

private fun newCaptureUri(context: Context): Uri {
    val directory = File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }
    // Shots from abandoned forms would otherwise wait for the system to trim the cache.
    val cutoff = System.currentTimeMillis() - STALE_CAPTURE_MILLIS
    directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    val file = File(directory, "capture_${System.nanoTime()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** Removes a temporary camera shot once it has been compressed. Gallery images are never touched. */
fun deleteCapturedPhoto(context: Context, uri: Uri) {
    if (uri.authority != "${context.packageName}.fileprovider" || uri.pathSegments.firstOrNull() != CAPTURE_DIR) return
    runCatching { context.contentResolver.delete(uri, null, null) }
}

/** Largest power-of-two sample size that still leaves at least `targetWidth` pixels across. */
internal fun sampleSizeFor(width: Int, targetWidth: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= targetWidth) sample *= 2
    return sample
}

/**
 * The first BEFORE/AFTER slot still free on a visit, so the usual flow — a
 * baseline shot at the start, the result at the end — needs no extra tap.
 */
internal fun defaultPhotoTag(existingTags: Collection<String>): String = when {
    "BEFORE" !in existingTags -> "BEFORE"
    "AFTER" !in existingTags -> "AFTER"
    else -> "PROCEDURE"
}

private fun swapsAxes(orientation: Int) = orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
    orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
    orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
    orientation == ExifInterface.ORIENTATION_ROTATE_270

private fun orientationMatrix(orientation: Int) = Matrix().apply {
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(-90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
    }
}

/**
 * Decodes an image upright and at most `maxWidth` wide.
 *
 * Camera apps usually store the sensor's landscape pixels plus an EXIF
 * rotation tag, which `BitmapFactory` ignores — without applying it a portrait
 * shot would upload sideways. Decoding is subsampled first because a full
 * 12-megapixel frame alone costs about 48 MB.
 */
internal fun decodeUprightBitmap(context: Context, uri: Uri, maxWidth: Int): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val orientation = runCatching {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    val uprightWidth = if (swapsAxes(orientation)) bounds.outHeight else bounds.outWidth
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(uprightWidth, maxWidth) }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    val decodedWidth = if (swapsAxes(orientation)) decoded.height else decoded.width
    val scale = minOf(1f, maxWidth.toFloat() / decodedWidth)
    val matrix = orientationMatrix(orientation).apply { postScale(scale, scale) }
    if (matrix.isIdentity) return decoded
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        .also { if (it !== decoded) decoded.recycle() }
}

/** Writes the upload copy of a photo: upright, at most [UPLOAD_PHOTO_MAX_WIDTH] wide, JPEG. */
internal fun compressPhotoForUpload(context: Context, uri: Uri, destination: File) {
    val bitmap = decodeUprightBitmap(context, uri, UPLOAD_PHOTO_MAX_WIDTH)
        ?: throw IllegalStateException("COULD_NOT_READ_PHOTO")
    try {
        FileOutputStream(destination).use { bitmap.compress(Bitmap.CompressFormat.JPEG, UPLOAD_PHOTO_QUALITY, it) }
    } finally {
        bitmap.recycle()
    }
}
