package com.lumina.gallery

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : ComponentActivity() {
    var onMediaPicked: ((List<Uri>) -> Unit)? = null
    var onFolderPicked: ((Uri?) -> Unit)? = null
    var onMediaAccessChanged: (() -> Unit)? = null

    private var cameraOutput: Uri? = null
    private var afterWriteGrant: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onMediaAccessChanged?.invoke()
    }
    private val systemRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) afterWriteGrant?.invoke()
        afterWriteGrant = null
        onMediaAccessChanged?.invoke()
    }
    private val documentsLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onMediaPicked?.invoke(uris)
    }
    private val treeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        onFolderPicked?.invoke(uri)
    }
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = cameraOutput
        if (result.resultCode == RESULT_OK && uri != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        } else if (uri != null) {
            contentResolver.delete(uri, null, null)
        }
        cameraOutput = null
        onMediaAccessChanged?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(5, 5, 6)
        window.navigationBarColor = android.graphics.Color.rgb(5, 5, 6)
        setContent { LuminaGalleryApp(this) }
        if (!hasMediaAccess()) requestMediaAccess()
    }

    fun hasMediaAccess(): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        val images = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        val videos = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        val selected = Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        images || videos || selected
    } else {
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun requestMediaAccess() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions)
    }

    fun requestTrash(uris: List<Uri>, trash: Boolean) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val request = if (trash) MediaStore.createTrashRequest(contentResolver, uris, true)
            else MediaStore.createDeleteRequest(contentResolver, uris)
            systemRequestLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            val intent = Intent(if (trash) Intent.ACTION_DELETE else Intent.ACTION_DELETE)
            intent.data = uris.first()
            startActivity(intent)
        }
    }

    fun requestWriteAccess(uris: List<Uri>, onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            afterWriteGrant = onGranted
            val request = MediaStore.createWriteRequest(contentResolver, uris)
            systemRequestLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else onGranted()
    }

    fun requestRestore(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val request = MediaStore.createTrashRequest(contentResolver, uris, false)
            systemRequestLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }
    }

    fun authenticateBiometric(onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onResult(false)
            return
        }
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
            .setTitle("Unlock Lumina Vault")
            .setSubtitle("Authenticate to access encrypted files")
            .setNegativeButton("Use PIN", mainExecutor) { _, _ -> onResult(false) }
            .build()
        prompt.authenticate(CancellationSignal(), mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                onResult(true)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(false)
            }
        })
    }

    fun shareAssets(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uris.first()) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uris.first())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share with"))
    }

    fun pickDocuments() = documentsLauncher.launch(arrayOf("image/*", "video/*", "application/pdf"))
    fun pickFolder() = treeLauncher.launch(null)

    fun scanQr(onValue: (String) -> Unit) {
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(onValue) }
            .addOnFailureListener { onValue("Scanner error: ${it.localizedMessage ?: "Google code scanner is unavailable"}") }
    }

    fun launchCamera() {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Lumina_${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Lumina Gallery")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        cameraOutput = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        val uri = cameraOutput ?: return
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        cameraLauncher.launch(intent)
    }
}
