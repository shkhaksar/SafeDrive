package sh.khaksar.safedrive

import android.app.Application
import android.util.Log
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException

/** View model for interacting with CameraX. */
class MainAcitivtyViewModel(application: Application) : AndroidViewModel(application) {

    enum class FaceDetectionStates(val intervalTolerancePolicy: Int) {
        SAFE(1),
        UNSAFE(0),
        NO_FACE(0)
    }

    enum class InCarStates { IN_CAR, OUT_CAR }

    private var cameraProviderLiveData: MutableLiveData<ProcessCameraProvider>? = null

    val faceDetectionState = MutableLiveData(FaceDetectionStates.NO_FACE)
    val uiFaceDetectionState = MutableLiveData(FaceDetectionStates.NO_FACE)

    //we will only change uiState when the image has been in the same state for 3 intervals
    private var stateChangeCounter = 0

    val inCarDetectionState = MutableLiveData(InCarStates.OUT_CAR)

    // Handle any errors (including cancellation) here.
    val processCameraProvider: MutableLiveData<ProcessCameraProvider>?
        get() {
            if (cameraProviderLiveData == null) {
                cameraProviderLiveData = MutableLiveData<ProcessCameraProvider>()
                val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> =
                    ProcessCameraProvider.getInstance(getApplication())
                cameraProviderFuture.addListener(
                    {
                        try {
                            cameraProviderLiveData!!.setValue(cameraProviderFuture.get())
                        } catch (e: ExecutionException) {
                            // Handle any errors (including cancellation) here.
                            Log.e(TAG, "Unhandled exception", e)
                        } catch (e: InterruptedException) {
                            Log.e(TAG, "Unhandled exception", e)
                        }
                    },
                    ContextCompat.getMainExecutor(getApplication())
                )
            }
            return cameraProviderLiveData
        }

    //We only update UI, if the state has been changed and been remained the same for the past 3 intervals
    fun updateFaceDetectionState(state:FaceDetectionStates){
        faceDetectionState.value = state

        if (faceDetectionState.value == uiFaceDetectionState.value) {
            stateChangeCounter = 0
            return
        }

        if (stateChangeCounter < uiFaceDetectionState.value?.intervalTolerancePolicy!!) {
            stateChangeCounter++
            return
        }

        //sync detected state with UI State
        stateChangeCounter = 0
        uiFaceDetectionState.value= faceDetectionState.value
    }


    companion object {
        private const val TAG = "CameraXViewModel"
    }
}