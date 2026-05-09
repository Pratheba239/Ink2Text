package com.example.ink2text

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.ink2text.databinding.ActivityMainBinding
import com.example.ink2text.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var selectedImageUri: Uri? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            startProcessing(it)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setupListeners()
        showCameraState()

        val source = intent.getStringExtra("IMAGE_SOURCE")
        if (source == "ALBUM") {
            pickImage.launch("image/*")
        }
    }

    private fun setupListeners() {
        binding.btnCapture.setOnClickListener { takePhoto() }
        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.btnClose.setOnClickListener { finish() }
        
        binding.btnRecognize.setOnClickListener {
            selectedImageUri?.let { uri ->
                startProcessing(uri)
            } ?: run {
                Toast.makeText(this, "Please capture an image first", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCopyText.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Recognized Text", binding.tvResult.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnDownloadPdf.setOnClickListener {
            saveTextAsPdf(binding.tvResult.text.toString())
        }

        binding.btnBackToCamera.setOnClickListener {
            showCameraState()
        }
    }

    private fun showCameraState() {
        binding.layoutCamera.visibility = View.VISIBLE
        binding.layoutProcessing.visibility = View.GONE
        binding.layoutResults.visibility = View.GONE
        selectedImageUri = null
        binding.btnRecognize.isEnabled = false
    }

    private fun showProcessingState(uri: Uri) {
        binding.layoutCamera.visibility = View.GONE
        binding.layoutProcessing.visibility = View.VISIBLE
        binding.layoutResults.visibility = View.GONE
        binding.imageView.setImageURI(uri)
    }

    private fun showResultsState(uri: Uri, resultText: String) {
        binding.layoutCamera.visibility = View.GONE
        binding.layoutProcessing.visibility = View.GONE
        binding.layoutResults.visibility = View.VISIBLE
        
        binding.imageViewResult.setImageURI(uri)
        binding.tvResult.text = resultText
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Ink2Text")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri
                    if (savedUri != null) {
                        selectedImageUri = savedUri
                        runOnUiThread {
                            startProcessing(savedUri)
                        }
                    }
                }
            }
        )
    }

    private fun getFileFromUri(uri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val file = File(cacheDir, "temp_image.jpg")
            val outputStream = FileOutputStream(file)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun startProcessing(uri: Uri) {
        showProcessingState(uri)
        
        val file = getFileFromUri(uri)
        if (file == null) {
            Toast.makeText(this, "Error processing image file", Toast.LENGTH_SHORT).show()
            showCameraState()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mediaType = MediaType.parse("image/jpeg")
                val requestFile = RequestBody.create(mediaType, file)
                val body = MultipartBody.Part.createFormData("image", file.name, requestFile)
                
                val response = RetrofitClient.apiService.recognizeText(body)
                
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val text = response.body()?.text ?: "Empty response"
                        showResultsState(uri, text)
                    } else {
                        Toast.makeText(this@MainActivity, "Error: ${response.code()}", Toast.LENGTH_LONG).show()
                        showCameraState()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Exception: ${e.message}", Toast.LENGTH_LONG).show()
                    showCameraState()
                }
            }
        }
    }

    private fun saveTextAsPdf(text: String) {
        if (text.isEmpty() || text == getString(R.string.recognized_text_placeholder)) {
            Toast.makeText(this, "No text to save", Toast.LENGTH_SHORT).show()
            return
        }

        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 size
        val page = pdfDocument.startPage(pageInfo)

        val canvas = page.canvas
        val textPaint = TextPaint()
        textPaint.isAntiAlias = true
        textPaint.textSize = 14f * resources.displayMetrics.density
        textPaint.color = Color.BLACK

        val staticLayout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, 595 - 80) // 40 margin on each side
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()

        canvas.save()
        canvas.translate(40f, 40f) // Margins
        staticLayout.draw(canvas)
        canvas.restore()

        pdfDocument.finishPage(page)

        val fileName = "Ink2Text_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".pdf"
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

        try {
            pdfDocument.writeTo(FileOutputStream(file))
            Toast.makeText(this, "Saved PDF to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving PDF: ${e.message}")
            Toast.makeText(this, "Error saving PDF", Toast.LENGTH_SHORT).show()
        } finally {
            pdfDocument.close()
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "Ink2Text"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}
