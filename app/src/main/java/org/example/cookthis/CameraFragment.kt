
package org.example.cookthis

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.hardware.Camera
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import androidx.camera.core.CameraX
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysisConfig
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.CaptureMode
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.core.ImageCaptureConfig
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.PreviewConfig
import android.view.DisplayCutout
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.navigation.Navigation
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.Exception
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Main fragment for this app. Implements :
 * - Viewfinder
 * - Image recognition
 */
class CameraFragment : Fragment() {

    private lateinit var container: ConstraintLayout
    private lateinit var viewFinder: TextureView
    private lateinit var outputDirectory: File
    private lateinit var broadcastManager: LocalBroadcastManager

    private var displayId = -1
    private var lensFacing = CameraX.LensFacing.BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var classifier: ImageClassifier? = null

    // Volume down button receiver
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val keyCode = intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)
            when (keyCode) {
                // When the volume down button is pressed, simulate a shutter button click
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val shutter = container
                            .findViewById<ImageButton>(R.id.camera_capture_button)
                    shutter.simulateClick()
                }
            }
        }
    }

    /** Internal reference of the [DisplayManager] */
    private lateinit var displayManager: DisplayManager

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                preview?.setTargetRotation(view.display.rotation)
                imageCapture?.setTargetRotation(view.display.rotation)
                imageAnalyzer?.setTargetRotation(view.display.rotation)
            }
        } ?: Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            classifier = ImageClassifier(activity!!)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to initialize an image classifier.")
        }

        super.onCreate(savedInstanceState)
        // Mark this as a retain fragment, so the lifecycle does not get restarted on config change
        retainInstance = true
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Unregister the broadcast receivers and listeners
        broadcastManager.unregisterReceiver(volumeDownReceiver)
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {

        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view as ConstraintLayout
        viewFinder = container.findViewById(R.id.view_finder)
        broadcastManager = LocalBroadcastManager.getInstance(view.context)

        // Set up the intent filter that will receive events from our main activity
        val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }
        broadcastManager.registerReceiver(volumeDownReceiver, filter)

        // Every time the orientation of device changes, recompute layout
        displayManager = viewFinder.context
                .getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)

        // Wait for the views to be properly laid out
        viewFinder.post {
            // Keep track of the display in which this view is attached
            displayId = viewFinder.display.displayId

            // Build UI controls and bind all camera use cases
            updateCameraUi()
            bindCameraUseCases()
        }
    }

    //Callback for image recognition
    private val imageCapturedListener = object : ImageCapture.OnImageCapturedListener(){
        override fun onError(
            error: ImageCapture.UseCaseError, message: String, exc: Throwable?) {
            Log.e(TAG, "Photo capture failed: $message")
            exc?.printStackTrace()
        }
        override fun onCaptureSuccess(
            image: ImageProxy,
            rotationDegrees: Int
        ) {
            if (classifier == null) {
                Toast.makeText(context,"Uninitialized Classifier", Toast.LENGTH_LONG).show()
                return
            }
            if (activity == null) {
                Toast.makeText(context,"Null activity ", Toast.LENGTH_LONG).show()
                return
            }
            var buffer = image.image!!.planes[0].buffer
            var bytes = ByteArray(buffer.capacity())
            buffer.get(bytes)
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            var scaledBitmap = Bitmap.createScaledBitmap(bitmap,224,224,false)
            val label = classifier!!.classifyFrame(scaledBitmap)
            Toast.makeText(context,"Found $label", Toast.LENGTH_LONG).show()
            image.close()
        }
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        val screenAspectRatio = Rational(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")

        // Set up the view finder use case to display camera preview
        val viewFinderConfig = PreviewConfig.Builder().apply {
            setLensFacing(lensFacing)
            // We request aspect ratio but no resolution to let CameraX optimize our use cases
            setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            setTargetRotation(viewFinder.display.rotation)
        }.build()

        // Use the auto-fit preview builder to automatically handle size and orientation changes
        preview = AutoFitPreviewBuilder.build(viewFinderConfig, viewFinder)

        // Set up the capture use case to allow users to take photos
        val imageCaptureConfig = ImageCaptureConfig.Builder().apply {
            setLensFacing(lensFacing)
            setCaptureMode(CaptureMode.MIN_LATENCY)
            // We request aspect ratio but no resolution to match preview config but letting
            // CameraX optimize for whatever specific resolution best fits requested capture mode
            setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            setTargetRotation(viewFinder.display.rotation)
        }.build()

        imageCapture = ImageCapture(imageCaptureConfig)

        // Apply declared configs to CameraX using the same lifecycle owner
        CameraX.bindToLifecycle(
                viewLifecycleOwner, preview, imageCapture)
    }

    /** Method used to re-draw the camera UI controls, called every time configuration changes */
    @SuppressLint("RestrictedApi")
    private fun updateCameraUi() {
        // Listener for button used to capture photo
        container.findViewById<ImageButton>(R.id.camera_capture_button).setOnClickListener {
            // Get a stable reference of the modifiable image capture use case
            imageCapture?.let { imageCapture ->
                // Setup image capture metadata
                val metadata = Metadata().apply {
                    // Mirror image when using the front camera
                    isReversedHorizontal = lensFacing == CameraX.LensFacing.FRONT
                }

                // Setup image capture listener which is triggered after photo has been taken
                imageCapture.takePicture(imageCapturedListener)

                // We can only change the foreground Drawable using API level 23+ API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    // Display flash animation to indicate that photo was captured
                    container.postDelayed({
                        container.foreground = ColorDrawable(Color.WHITE)
                        container.postDelayed(
                                { container.foreground = null }, ANIMATION_FAST_MILLIS)
                    }, ANIMATION_SLOW_MILLIS)
                }
            }
        }
    }


    companion object {
        private const val TAG = "CookThis"

        /** Milliseconds used for UI animations */
        const val ANIMATION_FAST_MILLIS = 50L
        const val ANIMATION_SLOW_MILLIS = 100L

        const val KEY_EVENT_ACTION = "key_event_action"
        const val KEY_EVENT_EXTRA = "key_event_extra"
        private const val IMMERSIVE_FLAG_TIMEOUT = 500L

        /**
         * Simulate a button click, including a small delay while it is being pressed to trigger the
         * appropriate animations.
         */
        fun ImageButton.simulateClick(delay: Long = ANIMATION_FAST_MILLIS) {
            performClick()
            isPressed = true
            invalidate()
            postDelayed({
                invalidate()
                isPressed = false
            }, delay)
        }
    }
}

/** Pad this view with the insets provided by the device cutout (i.e. notch) */
@RequiresApi(Build.VERSION_CODES.P)
fun View.padWithDisplayCutout() {

    /** Helper method that applies padding from cutout's safe insets */
    fun doPadding(cutout: DisplayCutout) = setPadding(
        cutout.safeInsetLeft,
        cutout.safeInsetTop,
        cutout.safeInsetRight,
        cutout.safeInsetBottom)

    // Apply padding using the display cutout designated "safe area"
    rootWindowInsets?.displayCutout?.let { doPadding(it) }

    // Set a listener for window insets since view.rootWindowInsets may not be ready yet
    setOnApplyWindowInsetsListener { view, insets ->
        insets.displayCutout?.let { doPadding(it) }
        insets
    }
}

/** Same as [AlertDialog.show] but setting immersive mode in the dialog's window */
fun AlertDialog.showImmersive() {
    // Set the dialog to not focusable
    window?.setFlags(
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

    // Make sure that the dialog's window is in full screen
    window?.decorView?.systemUiVisibility = FLAGS_FULLSCREEN

    // Show the dialog while still in immersive mode
    show()

    // Set the dialog to focusable again
    window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
}