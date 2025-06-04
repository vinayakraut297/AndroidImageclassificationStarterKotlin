package com.example.imageclassificationkotlin

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private val RESULT_LOAD_IMAGE = 123
    val IMAGE_CAPTURE_CODE = 654
    private val PERMISSION_CODE = 321
    var frame: ImageView? = null
    var innerImage: ImageView? = null
    var resultTv: TextView? = null
    private var image_uri: Uri? = null
    private var pendingCameraLaunch = false
    private lateinit var classifier: Classifier

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize the classifier
        try {
            classifier = Classifier(assets, "mobilenet_v1_1.0_224.tflite", "mobilenet_v1_1.0_224.txt", 224)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing classifier: ${e.message}")
            Toast.makeText(this, "Error initializing classifier: ${e.message}", Toast.LENGTH_LONG).show()
        }

        frame = findViewById(R.id.imageView)
        innerImage = findViewById(R.id.imageView2)
        resultTv = findViewById(R.id.textView)

        // Add click listener for gallery
        frame?.setOnClickListener {
            Log.d(TAG, "Frame clicked - opening gallery")
            val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(galleryIntent, RESULT_LOAD_IMAGE)
        }

        // Add long click listener for camera
        frame?.setOnLongClickListener {
            Log.d(TAG, "Frame long pressed - attempting to open camera")
            checkCameraPermissionAndOpen()
            true
        }
    }

    private fun checkCameraPermissionAndOpen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "Camera permission already granted - opening camera")
                    openCamera()
                }
                ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA) -> {
                    Log.d(TAG, "Should show camera permission rationale")
                    Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_LONG).show()
                    requestCameraPermission()
                }
                else -> {
                    Log.d(TAG, "Requesting camera permission")
                    requestCameraPermission()
                }
            }
        } else {
            Log.d(TAG, "Android version < M - opening camera directly")
            openCamera()
        }
    }

    private fun requestCameraPermission() {
        pendingCameraLaunch = true
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Camera permission granted")
                    if (pendingCameraLaunch) {
                        pendingCameraLaunch = false
                        openCamera()
                    }
                } else {
                    Log.d(TAG, "Camera permission denied")
                    Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_LONG).show()
                    pendingCameraLaunch = false
                }
            }
        }
    }

    private fun openCamera() {
        try {
            Log.d(TAG, "Opening camera")
            val values = ContentValues()
            values.put(MediaStore.Images.Media.TITLE, "New Picture")
            values.put(MediaStore.Images.Media.DESCRIPTION, "From the Camera")
            image_uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            
            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, image_uri)
            
            if (cameraIntent.resolveActivity(packageManager) != null) {
                startActivityForResult(cameraIntent, IMAGE_CAPTURE_CODE)
            } else {
                Log.e(TAG, "No camera app found")
                Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera: ${e.message}")
            Toast.makeText(this, "Error opening camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RESULT_LOAD_IMAGE && resultCode == Activity.RESULT_OK && data != null) {
            image_uri = data.data
            innerImage!!.setImageURI(image_uri)
            doInference()
        }
        if (requestCode == IMAGE_CAPTURE_CODE && resultCode == Activity.RESULT_OK) {
            innerImage!!.setImageURI(image_uri)
            doInference()
        }
    }

    //TODO pass image to the model and shows the results on screen
    fun doInference() {
        try {
            // Get bitmap from URI
            val bitmap = image_uri?.let { uriToBitmap(it) }
            if (bitmap == null) {
                Log.e(TAG, "Failed to get bitmap from URI")
                Toast.makeText(this, "Failed to process image", Toast.LENGTH_SHORT).show()
                return
            }

            // Rotate bitmap if needed
            val rotatedBitmap = rotateBitmap(bitmap) ?: bitmap

            // Perform classification
            val recognitions = classifier.recognizeImage(rotatedBitmap)

            // Display results
            val resultText = StringBuilder()
            for (recognition in recognitions) {
                resultText.append("${recognition.title}: ${String.format("%.2f", recognition.confidence * 100)}%\n")
            }
            resultTv?.text = resultText.toString()

        } catch (e: Exception) {
            Log.e(TAG, "Error during inference: ${e.message}")
            Toast.makeText(this, "Error during inference: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    //TODO takes URI of the image and returns bitmap
    private fun uriToBitmap(selectedFileUri: Uri): Bitmap? {
        try {
            val parcelFileDescriptor =
                contentResolver.openFileDescriptor(selectedFileUri, "r")
            val fileDescriptor = parcelFileDescriptor!!.fileDescriptor
            val image = BitmapFactory.decodeFileDescriptor(fileDescriptor)
            parcelFileDescriptor.close()
            return image
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return null
    }

    //TODO rotate image if image captured on samsung devices
    //Most phone cameras are landscape, meaning if you take the photo in portrait, the resulting photos will be rotated 90 degrees.
    fun rotateBitmap(input: Bitmap): Bitmap? {
        val orientationColumn =
            arrayOf(MediaStore.Images.Media.ORIENTATION)
        val cur =
            contentResolver.query(image_uri!!, orientationColumn, null, null, null)
        var orientation = -1
        if (cur != null && cur.moveToFirst()) {
            orientation = cur.getInt(cur.getColumnIndex(orientationColumn[0]))
        }
        Log.d("tryOrientation", orientation.toString() + "")
        val rotationMatrix = Matrix()
        rotationMatrix.setRotate(orientation.toFloat())
        return Bitmap.createBitmap(
            input,
            0,
            0,
            input.width,
            input.height,
            rotationMatrix,
            true
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // Close the classifier to free resources
        if (::classifier.isInitialized) {
            classifier.close()
        }
    }
}