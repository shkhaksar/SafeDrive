/*
 * Copyright 2020 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sh.khaksar.safedrive

import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.TaskExecutors
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import sh.khaksar.safedrive.views.FaceGraphic
import sh.khaksar.safedrive.views.GraphicOverlay
import java.util.*

/**
 * Abstract base class for ML Kit frame processors. Subclasses need to implement {@link
 * #onSuccess(T, FrameMetadata, GraphicOverlay)} to define what they want to with the detection
 * results and {@link #detectInImage(VisionImage)} to specify the detector object.
 *
 * @param <T> The type of the detected feature.
 */
class FaceDetectorProcessor(detectorOptions: FaceDetectorOptions?) {

    interface OnFaceDetectListener {
        fun onDetect(results: List<Face?>)
    }

    private val detector: FaceDetector
    private var onFaceDetectListener: OnFaceDetectListener? = null

    private val fpsTimer = Timer()
    private val executor = ScopedExecutor(TaskExecutors.MAIN_THREAD)

    // Whether this processor is already shut down
    private var isShutdown = false

    // Frame count that have been processed so far in an one second interval to calculate FPS.
    private var frameProcessedInInterval = 0

    companion object {
        const val MANUAL_TESTING_LOG = "LogTagForTest"
        private const val TAG = "FaceDetectorProcessor"

        private fun logExtrasForTesting(face: Face?) {
            if (face != null) {
                Log.v(MANUAL_TESTING_LOG, "face bounding box:" + face.boundingBox.flattenToString())
                Log.v(MANUAL_TESTING_LOG, "face Euler Angle X: " + face.headEulerAngleX)
                Log.v(MANUAL_TESTING_LOG, "face Euler Angle Y: " + face.headEulerAngleY)
                Log.v(MANUAL_TESTING_LOG, "face Euler Angle Z: " + face.headEulerAngleZ)
                // All landmarks
                val landMarkTypes = intArrayOf(
                    FaceLandmark.MOUTH_BOTTOM, FaceLandmark.MOUTH_RIGHT,
                    FaceLandmark.MOUTH_LEFT, FaceLandmark.RIGHT_EYE,
                    FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EAR,
                    FaceLandmark.LEFT_EAR, FaceLandmark.RIGHT_CHEEK,
                    FaceLandmark.LEFT_CHEEK, FaceLandmark.NOSE_BASE
                )
                val landMarkTypesStrings = arrayOf(
                    "MOUTH_BOTTOM", "MOUTH_RIGHT", "MOUTH_LEFT", "RIGHT_EYE", "LEFT_EYE",
                    "RIGHT_EAR", "LEFT_EAR", "RIGHT_CHEEK", "LEFT_CHEEK", "NOSE_BASE"
                )
                for (i in landMarkTypes.indices) {
                    val landmark = face.getLandmark(landMarkTypes[i])
                    if (landmark == null) {
                        Log.v(
                            MANUAL_TESTING_LOG,
                            "No landmark of type: " + landMarkTypesStrings[i] + " has been detected"
                        )
                    } else {
                        val landmarkPosition = landmark.position
                        val landmarkPositionStr =
                            String.format(
                                Locale.US, "x: %f , y: %f", landmarkPosition.x, landmarkPosition.y
                            )
                        Log.v(
                            MANUAL_TESTING_LOG,
                            "Position for face landmark: " +
                                    landMarkTypesStrings[i] +
                                    " is :" +
                                    landmarkPositionStr
                        )
                    }
                }
                Log.v(
                    MANUAL_TESTING_LOG,
                    "face left eye open probability: " + face.leftEyeOpenProbability
                )
                Log.v(
                    MANUAL_TESTING_LOG,
                    "face right eye open probability: " + face.rightEyeOpenProbability
                )
                Log.v(
                    MANUAL_TESTING_LOG,
                    "face smiling probability: " + face.smilingProbability
                )
                Log.v(
                    MANUAL_TESTING_LOG,
                    "face tracking id: " + face.trackingId
                )
            }
        }
    }

    init {

        // a timer, for processing images every five seconds
        fpsTimer.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() {
                    frameProcessedInInterval = 0
                }
            }, 0, 1000
        )

        val options = detectorOptions
            ?: FaceDetectorOptions.Builder()
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .enableTracking()
                .build()

        detector = FaceDetection.getClient(options)

        Log.v(MANUAL_TESTING_LOG, "Face detector options: $options")
    }

    // -----------------Code for processing live preview frame from CameraX API-----------------------
    @ExperimentalGetImage
    fun processImageProxy(graphicOverlay: GraphicOverlay, image: ImageProxy) {
        if (isShutdown) return

        //use detector's async-task to detect off-thread and call detector.close() on finish
        detector.process(InputImage.fromMediaImage(image.image!!, image.imageInfo.rotationDegrees))
            // When the image is from CameraX analysis use case, must call image.close() on received
            // images when finished using them. Otherwise, new images may not be received or the camera
            // may stall.
            .addOnCompleteListener { image.close() }
            .addOnSuccessListener(executor) { results ->
                graphicOverlay.clear()
                if (results.isNotEmpty()){
                    graphicOverlay.add(FaceGraphic(graphicOverlay, results[0]))
                    graphicOverlay.postInvalidate()
                }

                if (frameProcessedInInterval > 0) return@addOnSuccessListener
                frameProcessedInInterval++

                if (results == null) return@addOnSuccessListener

                onFaceDetectListener?.onDetect(results)
                for (face in results) {
                    logExtrasForTesting(face)
                }
            }
            .addOnFailureListener(executor) { e ->
                Log.e(TAG, "Face detection failed $e")
                Log.d(TAG, "Failed to process. Error: " + e.localizedMessage)
                e.printStackTrace()
            }

    }

    fun stop() {
        executor.shutdown()
        isShutdown = true
        fpsTimer.cancel()
        detector.close()
    }

    fun setOnFaceDetectListener(l: OnFaceDetectListener) {
        onFaceDetectListener = l
    }
}
