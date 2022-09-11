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
import android.view.View
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
import sh.khaksar.safedrive.MainAcitivtyViewModel.FaceDetectionStates
import sh.khaksar.safedrive.MainAcitivtyViewModel.InCarStates
import sh.khaksar.safedrive.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity(), FaceDetectorProcessor.OnFaceDetectListener {

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

    private val viewModel: MainAcitivtyViewModel by viewModels()

    private lateinit var safeDriveMediaPlayer: MediaPlayer
    private lateinit var unsafeDriveMediaPlayer: MediaPlayer
    private lateinit var noFaceDetectedMediaPlayer: MediaPlayer


    private val transitionBroadcastReceiver: TransitionsReceiver = TransitionsReceiver()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isRuntimePermissionsGranted()) {
            getRuntimePermissions()
        }

        safeDriveMediaPlayer = MediaPlayer.create(this, R.raw.upward)
        unsafeDriveMediaPlayer = MediaPlayer.create(this, R.raw.downward)
        noFaceDetectedMediaPlayer = MediaPlayer.create(this, R.raw.error)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.apply {
            processCameraProvider?.observe(this@MainActivity) { provider ->
                cameraProvider = provider
                bindPreviewUseCase()
            }
            uiFaceDetectionState.observe(this@MainActivity) { state -> updateFaceUI(state) }
            inCarDetectionState.observe(this@MainActivity) { state -> updateCarUI(state) }
        }

        registerActivityRecognition()

        binding.carMessage.setOnClickListener {
            val intent = Intent()
            intent.action = TRANSITIONS_RECEIVER_ACTION
            val result = ActivityRecognitionResult(DetectedActivity(
                if (viewModel.inCarDetectionState.value != InCarStates.IN_CAR)
                    DetectedActivity.IN_VEHICLE
                else
                    DetectedActivity.STILL,
                100
            ),5000,SystemClock.elapsedRealtimeNanos())
            SafeParcelableSerializer.serializeToIntentExtra(
                result, intent,
                "com.google.android.location.internal.EXTRA_ACTIVITY_RESULT"
            )
            this.sendBroadcast(intent)
        }
    }


    override fun onResume() {
        super.onResume()
        bindPreviewUseCase()
        registerReceiver(transitionBroadcastReceiver, IntentFilter(TRANSITIONS_RECEIVER_ACTION))
    }

    override fun onPause() {
        super.onPause()
        faceProcessor?.stop()
        unregisterReceiver(transitionBroadcastReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        faceProcessor?.stop()
    }

    @SuppressLint("MissingPermission")
    private fun registerActivityRecognition() {
        val transitionRequest = ActivityTransitionRequest(
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.UNKNOWN)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.STILL)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
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
            .requestActivityUpdates(500, transitionReceiverPendingIntent)
            .addOnSuccessListener { Log.d(TAG, "ACTIVITY_TRACK_SUCCESS") }
            .addOnFailureListener { Log.d(TAG, "ACTIVITY_TRACK_FAILURE") }
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
        unbindAnalysisUseCase() //unbind previous use-case

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

    private fun unbindAnalysisUseCase() {
        if (cameraProvider == null) {
            return
        }
        if (analysisUseCase != null) {
            cameraProvider!!.unbind(analysisUseCase)
        }
        if (faceProcessor != null) {
            faceProcessor!!.stop()
        }
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
                viewModel.updateFaceDetectionState(FaceDetectionStates.NO_FACE)
            }

            if (reop != null && leop != null) {
                if (reop < 0.1f && leop < 0.1f) {
                    viewModel.updateFaceDetectionState(FaceDetectionStates.UNSAFE)
                } else if (reop > 0.4f && leop > 0.4f) {
                    viewModel.updateFaceDetectionState(FaceDetectionStates.SAFE)
                }
            }
        } else {
            viewModel.updateFaceDetectionState(FaceDetectionStates.NO_FACE)
        }
    }


    private fun updateFaceUI(state: FaceDetectionStates) {
        safeDriveMediaPlayer.stop()
        unsafeDriveMediaPlayer.stop()
        noFaceDetectedMediaPlayer.stop()

        when (state) {
            FaceDetectionStates.SAFE -> {
                safeDriveMediaPlayer.apply { prepare();start() }
                binding.faceMessage.apply {
                    text = getString(R.string.detection_safe)
                    setBackgroundColor(
                        ContextCompat.getColor(
                            applicationContext,
                            R.color.transparent_holo_green_dark
                        )
                    )
                }
            }
            FaceDetectionStates.UNSAFE -> {
                unsafeDriveMediaPlayer.apply { prepare();start() }
                binding.faceMessage.apply {
                    text = getString(R.string.detection_unsafe)
                    setBackgroundColor(
                        ContextCompat.getColor(
                            applicationContext,
                            R.color.transparent_holo_red_dark
                        )
                    )
                }
            }
            FaceDetectionStates.NO_FACE -> {
                noFaceDetectedMediaPlayer.apply { prepare();start() }
                binding.faceMessage.apply {
                    text = getString(R.string.detection_no_face)
                    setBackgroundColor(
                        ContextCompat.getColor(
                            applicationContext,
                            R.color.transparent_holo_yellow_dark
                        )
                    )
                }
            }
        }
    }

    private fun updateCarUI(state: InCarStates) {
        when (state) {
            InCarStates.IN_CAR -> {
                bindAnalysisUseCase()
                binding.faceMessage.visibility = View.VISIBLE
                binding.carMessage.apply {
                    text = getString(R.string.in_vehicle)
                    setBackgroundColor(
                        ContextCompat.getColor(
                            applicationContext,
                            R.color.transparent_holo_green_dark
                        )
                    )
                }
            }
            InCarStates.OUT_CAR -> {
                binding.faceMessage.visibility = View.INVISIBLE
                unbindAnalysisUseCase()
                binding.carMessage.apply {
                    text = getString(R.string.out_vehicle)
                    setBackgroundColor(
                        ContextCompat.getColor(
                            applicationContext,
                            R.color.transparent_holo_red_dark
                        )
                    )
                }
            }
        }
    }

    inner class TransitionsReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val res = ActivityRecognitionResult.extractResult(intent)
            Log.d(TAG, res?.mostProbableActivity.toString())
            if (res?.mostProbableActivity?.type == DetectedActivity.IN_VEHICLE) {
                viewModel.inCarDetectionState.value = InCarStates.IN_CAR
                Log.d(TAG, "IN_VEHICLE_ENTER")
            } else {
                viewModel.inCarDetectionState.value = InCarStates.OUT_CAR
                viewModel.uiFaceDetectionState.value = FaceDetectionStates.NO_FACE
                Log.d(TAG, "IN_VEHICLE_EXIT")
            }
        }
    }
}