package sh.khaksar.safedrive

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface.ROTATION_180
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraSelector.LENS_FACING_FRONT
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import com.google.android.gms.location.*
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetectorOptions
import sh.khaksar.safedrive.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity(), FaceDetectorProcessor.OnFaceDetectListener {

    enum class UIStates(val intervalTolerancePolicy: Int) {
        SAFE(1),
        UNSAFE(0),
        NO_FACE(0)
    }

    companion object {
        private const val TAG = "sh.khaksar.safedrive.MainActivity"
        private const val PERMISSION_REQUESTS = 1
        private const val PENDING_INTENT_REQUESTS = 2
        private const val TRANSITIONS_RECEIVER_ACTION =
            "${BuildConfig.APPLICATION_ID}_transitions_receiver_action"


        private val REQUIRED_RUNTIME_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
    }

    private lateinit var binding: ActivityMainBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var faceProcessor: FaceDetectorProcessor? = null
    private val camSelector = CameraSelector.Builder().requireLensFacing(LENS_FACING_FRONT).build()

    private val viewModel: CameraXViewModel by viewModels()

    private lateinit var safeDriveMediaPlayer: MediaPlayer
    private lateinit var unsafeDriveMediaPlayer: MediaPlayer
    private lateinit var noFaceDetectedMediaPlayer: MediaPlayer


    //we will only change uiState when the image has been in the same state for 3 intervals
    private var stateChangeCounter = 0
    private var currentUIState: UIStates = UIStates.NO_FACE
    private var detectedState: UIStates = UIStates.NO_FACE


    private val transitionBroadcastReceiver: TransitionsReceiver = TransitionsReceiver()

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        safeDriveMediaPlayer = MediaPlayer.create(this, R.raw.upward)
        unsafeDriveMediaPlayer = MediaPlayer.create(this, R.raw.downward)
        noFaceDetectedMediaPlayer = MediaPlayer.create(this, R.raw.error)

        if (!isRuntimePermissionsGranted()) {
            getRuntimePermissions()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.processCameraProvider?.observe(this)
        { provider: ProcessCameraProvider? ->
            cameraProvider = provider
            bindAllCameraUseCases()
        }

        val transitionRequest = ActivityTransitionRequest(
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build()
            )
        )

        val transitionReceiverPendingIntent = PendingIntent.getBroadcast(
            this, PENDING_INTENT_REQUESTS, Intent(TRANSITIONS_RECEIVER_ACTION),
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        ActivityRecognition.getClient(this)
            .requestActivityTransitionUpdates(transitionRequest, transitionReceiverPendingIntent)
            .addOnSuccessListener { Log.d("ACTIVITY_TRACK", "SUCCESS") }
            .addOnFailureListener { Log.d("ACTIVITY_TRACK", "FAILURE") }

        binding.previewView.setOnClickListener {
            val intent = Intent()

            // Your broadcast receiver action

            intent.action = TRANSITIONS_RECEIVER_ACTION
            val events: ArrayList<ActivityTransitionEvent> = arrayListOf()

            // You can set desired events with their corresponding state

            val transitionEvent = ActivityTransitionEvent(
                DetectedActivity.IN_VEHICLE,
                ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                SystemClock.elapsedRealtimeNanos()
            )
            events.add(transitionEvent)
            val result = ActivityTransitionResult(events)
            SafeParcelableSerializer.serializeToIntentExtra(
                result,
                intent,
                "com.google.android.location.internal.EXTRA_ACTIVITY_TRANSITION_RESULT"
            )
            this.sendBroadcast(intent)
        }
    }


    public override fun onResume() {
        super.onResume()
        bindAllCameraUseCases()
        registerReceiver(transitionBroadcastReceiver, IntentFilter(TRANSITIONS_RECEIVER_ACTION))
    }

    override fun onPause() {
        super.onPause()
        faceProcessor?.stop()
        unregisterReceiver(transitionBroadcastReceiver)
    }

    public override fun onDestroy() {
        super.onDestroy()
        faceProcessor?.stop()
    }

    private fun bindAllCameraUseCases() {
        if (cameraProvider != null) {
            // As required by CameraX API, unbinds all use cases before trying to re-bind any of them.
            cameraProvider!!.unbindAll()
            bindPreviewUseCase()
            bindAnalysisUseCase()
        }
    }

    private fun bindPreviewUseCase() {
        if (cameraProvider == null) {
            return
        }
        if (previewUseCase != null) {
            cameraProvider!!.unbind(previewUseCase)
        }

        previewUseCase = Preview.Builder().setTargetRotation(ROTATION_180).build()
        previewUseCase!!.setSurfaceProvider(binding.previewView.surfaceProvider)
        cameraProvider!!.bindToLifecycle(this, camSelector, previewUseCase)
    }


    @SuppressLint("UnsafeOptInUsageError")
    private fun bindAnalysisUseCase() {
        if (cameraProvider == null) {
            return
        }
        if (analysisUseCase != null) {
            cameraProvider!!.unbind(analysisUseCase)
        }
        if (faceProcessor != null) {
            faceProcessor!!.stop()
        }

        val faceDetectorOptions = FaceDetectorOptions.Builder()
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.1f)
            .enableTracking()

        faceProcessor = FaceDetectorProcessor(faceDetectorOptions.build())
            .apply { setOnFaceDetectListener(this@MainActivity) }

        analysisUseCase = ImageAnalysis.Builder().build()

        //the cameraX method listener method to send stream of data (proxyImage) for whatever analysis we need
        analysisUseCase?.setAnalyzer(
            // imageProcessor.processImageProxy will use another thread to run the detection underneath,
            // thus we can just runs the analyzer itself on main thread.
            ContextCompat.getMainExecutor(this)
        ) { imageProxy: ImageProxy ->
            try {
                faceProcessor!!.processImageProxy(imageProxy)
            } catch (e: MlKitException) {
                Log.e(TAG, "Failed to process image. Error: " + e.localizedMessage)
                Toast.makeText(applicationContext, e.localizedMessage, Toast.LENGTH_SHORT).show()
            }
        }

        cameraProvider!!.bindToLifecycle(this, camSelector, analysisUseCase)
    }

    private fun isRuntimePermissionsGranted(): Boolean {
        for (permission in REQUIRED_RUNTIME_PERMISSIONS) {
            permission.let {
                if (!isPermissionGranted(this, it)) {
                    return false
                }
            }
        }
        return true
    }

    private fun getRuntimePermissions() {
        val permissionsToRequest = ArrayList<String>()
        for (permission in REQUIRED_RUNTIME_PERMISSIONS) {
            permission.let {
                if (!isPermissionGranted(this, it)) {
                    permissionsToRequest.add(permission)
                }
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUESTS
            )
        }
    }

    private fun isPermissionGranted(context: Context, permission: String): Boolean {

        val isAndroidQOrLater: Boolean =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q

        if (isAndroidQOrLater.not() && permission == Manifest.permission.ACTIVITY_RECOGNITION) {
            return true
        }

        if (ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Permission granted: $permission")
            return true
        }
        Log.i(TAG, "Permission NOT granted: $permission")
        return false
    }

    // update detectedState only if it's going to be different from UI State
    // then request to updateUI
    override fun onDetect(results: List<Face?>) {
        if (results.isNotEmpty()) {
            val face = results[0] //Only process the first face
            val reop = face?.rightEyeOpenProbability
            val leop = face?.leftEyeOpenProbability

            if (reop == null || leop == null) {
                detectedState = UIStates.NO_FACE
                requestUpdateUI(UIStates.NO_FACE)
            }

            if (reop != null && leop != null) {
                if (reop < 0.1f && leop < 0.1f) {
                    detectedState = UIStates.UNSAFE
                    requestUpdateUI(UIStates.UNSAFE)
                } else if (reop > 0.4f && leop > 0.4f) {
                    detectedState = UIStates.SAFE
                    requestUpdateUI(UIStates.SAFE)
                }
            }
        } else {
            detectedState = UIStates.NO_FACE
            requestUpdateUI(UIStates.NO_FACE)
        }
    }

    //We only update UI, if the state has been changed and been remained the same for the past 3 intervals
    private fun requestUpdateUI(state: UIStates) {
        if (detectedState == currentUIState) {
            stateChangeCounter = 0
            return
        }

        if (stateChangeCounter < currentUIState.intervalTolerancePolicy) {
            stateChangeCounter++
            return
        }

        stateChangeCounter = 0
        when (state) {
            UIStates.SAFE -> safeToDrive()
            UIStates.UNSAFE -> unSafeToDrive()
            UIStates.NO_FACE -> noFaceDetected()
        }

    }

    private fun stopSounds() {
        safeDriveMediaPlayer.stop()
        unsafeDriveMediaPlayer.stop()
        noFaceDetectedMediaPlayer.stop()
    }

    private fun noFaceDetected() {

        //because updating the UI is costly, we don't do it if the state is the same
        if (currentUIState == UIStates.NO_FACE) return
        currentUIState = UIStates.NO_FACE
        stopSounds()

        noFaceDetectedMediaPlayer.prepare()
        noFaceDetectedMediaPlayer.start()
        binding.message.setBackgroundColor(
            ContextCompat.getColor(
                applicationContext,
                R.color.transparent_holo_yellow_dark
            )
        )
        binding.message.text = getString(R.string.message_no_face)
    }

    private fun unSafeToDrive() {

        //because updating the UI is costly, we don't do it if the state is the same
        if (currentUIState == UIStates.UNSAFE) return
        currentUIState = UIStates.UNSAFE
        stopSounds()

        binding.message.setBackgroundColor(
            ContextCompat.getColor(
                applicationContext,
                R.color.transparent_holo_red_dark
            )
        )
        binding.message.text = getString(R.string.message_not_safe)
        unsafeDriveMediaPlayer.prepare()
        unsafeDriveMediaPlayer.start()
    }

    private fun safeToDrive() {

        //because updating the UI is costly, we don't do it if the state is the same
        if (currentUIState == UIStates.SAFE) return
        currentUIState = UIStates.SAFE
        stopSounds()

        binding.message.setBackgroundColor(
            ContextCompat.getColor(
                applicationContext,
                R.color.transparent_holo_green_dark
            )
        )
        binding.message.text = getString(R.string.message_safe)
        safeDriveMediaPlayer.prepare()
        safeDriveMediaPlayer.start()

    }


    inner class TransitionsReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            if (ActivityTransitionResult.hasResult(intent)) {
                val result = ActivityTransitionResult.extractResult(intent)
                if (result != null) {
                    for (event in result.transitionEvents) {
                        if (event.activityType == DetectedActivity.IN_VEHICLE && event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                            Log.d("Broadcast", "Vehicle Enter")
                    }
                }
            }
        }
    }
}