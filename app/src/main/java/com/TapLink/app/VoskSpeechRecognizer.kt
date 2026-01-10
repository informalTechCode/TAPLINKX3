package com.TapLinkX3.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Vosk-based offline speech recognition for AR glasses.
 * Uses the device's microphone with AR glasses-specific audio configuration.
 */
class VoskSpeechRecognizer(private val context: Context) {
    
    companion object {
        private const val TAG = "VoskSpeechRecognizer"
        private const val SAMPLE_RATE = 16000
        private const val SPEAKER_MIC = 23  // AR glasses specific microphone ID
        private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    }
    
    interface VoskListener {
        fun onResult(text: String)
        fun onPartialResult(text: String)
        fun onError(message: String)
        fun onListening()
        fun onDone()
    }
    
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var audioManager: AudioManager? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var listener: VoskListener? = null
    private var isModelLoading = false
    private var isModelReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    
    fun setListener(listener: VoskListener) {
        this.listener = listener
    }
    
    /**
     * Initialize the Vosk model. This should be called once at startup.
     * The model will be unpacked from assets to internal storage.
     */
    fun initModel(onComplete: (Boolean) -> Unit) {
        if (isModelLoading) {
            Log.d(TAG, "Model already loading...")
            return
        }
        if (isModelReady && model != null) {
            Log.d(TAG, "Model already ready")
            onComplete(true)
            return
        }
        
        isModelLoading = true
        Log.d(TAG, "Starting model initialization...")
        
        // Run model loading in background
        executor.execute {
            try {
                // Check if model already exists in internal storage
                val modelDir = File(context.filesDir, "model")
                
                if (modelDir.exists() && modelDir.isDirectory && modelDir.listFiles()?.isNotEmpty() == true) {
                    Log.d(TAG, "Model already unpacked, loading from: ${modelDir.absolutePath}")
                    loadModelFromPath(modelDir.absolutePath, onComplete)
                } else {
                    Log.d(TAG, "Unpacking model from assets...")
                    // Use StorageService to unpack
                    StorageService.unpack(context, MODEL_NAME, "model",
                        { loadedModel ->
                            Log.d(TAG, "StorageService unpacked model successfully")
                            model = loadedModel
                            isModelReady = true
                            isModelLoading = false
                            mainHandler.post { onComplete(true) }
                        },
                        { exception ->
                            Log.e(TAG, "StorageService failed to unpack model", exception)
                            // Try manual loading as fallback
                            tryManualModelLoad(modelDir, onComplete)
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during model initialization", e)
                isModelLoading = false
                isModelReady = false
                mainHandler.post { onComplete(false) }
            }
        }
    }
    
    private fun loadModelFromPath(path: String, onComplete: (Boolean) -> Unit) {
        try {
            Log.d(TAG, "Loading model from path: $path")
            model = Model(path)
            isModelReady = true
            isModelLoading = false
            Log.d(TAG, "Model loaded successfully from path")
            mainHandler.post { onComplete(true) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model from path: $path", e)
            isModelLoading = false
            isModelReady = false
            mainHandler.post { onComplete(false) }
        }
    }
    
    private fun tryManualModelLoad(targetDir: File, onComplete: (Boolean) -> Unit) {
        Log.d(TAG, "Attempting manual model extraction...")
        try {
            // Try to copy model from assets manually
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            
            copyAssetFolder(context, MODEL_NAME, targetDir)
            
            if (targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true) {
                loadModelFromPath(targetDir.absolutePath, onComplete)
            } else {
                Log.e(TAG, "Manual extraction failed - directory empty")
                isModelLoading = false
                isModelReady = false
                mainHandler.post { onComplete(false) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Manual model extraction failed", e)
            isModelLoading = false
            isModelReady = false
            mainHandler.post { onComplete(false) }
        }
    }
    
    private fun copyAssetFolder(context: Context, assetPath: String, targetDir: File) {
        try {
            val assetManager = context.assets
            val files = assetManager.list(assetPath)
            
            Log.d(TAG, "Copying asset folder: $assetPath, found ${files?.size ?: 0} items")
            
            if (files.isNullOrEmpty()) {
                // It's a file, copy it
                assetManager.open(assetPath).use { input ->
                    File(targetDir, File(assetPath).name).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                // It's a directory, recurse
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                
                for (file in files) {
                    val subAssetPath = "$assetPath/$file"
                    val subTargetDir = if (assetManager.list(subAssetPath)?.isNotEmpty() == true) {
                        File(targetDir, file).also { it.mkdirs() }
                    } else {
                        targetDir
                    }
                    copyAssetFolder(context, subAssetPath, subTargetDir)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error copying asset: $assetPath", e)
            throw e
        }
    }
    
    /**
     * Start listening for speech. Will call listener callbacks with results.
     */
    fun startListening() {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }
        
        if (!isModelReady || model == null) {
            Log.e(TAG, "Model not ready, cannot start listening")
            listener?.onError("Speech model not loaded")
            return
        }
        
        try {
            // Configure AR glasses audio source
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager?.setParameters("audio_source_record=voice_recognition")
            
            // Create recognizer
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            
            // Calculate buffer size
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2
            
            // Create AudioRecord with VOICE_RECOGNITION source
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            
            // Try to use the AR glasses specific microphone
            try {
                val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                devices?.forEach { device ->
                    if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && device.id == SPEAKER_MIC) {
                        audioRecord?.preferredDevice = device
                        Log.d(TAG, "Set preferred device to SPEAKER_MIC (id=$SPEAKER_MIC)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not set preferred device: ${e.message}")
            }
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                listener?.onError("Failed to initialize microphone")
                return
            }
            
            audioRecord?.startRecording()
            isRecording = true
            listener?.onListening()
            
            // Start recognition thread
            recordingThread = Thread {
                recognizeLoop(bufferSize)
            }
            recordingThread?.start()
            
            Log.d(TAG, "Started listening")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting listening", e)
            listener?.onError("Error starting voice recognition: ${e.message}")
            stopListening()
        }
    }
    
    private fun recognizeLoop(bufferSize: Int) {
        val buffer = ShortArray(bufferSize / 2)
        
        try {
            while (isRecording && audioRecord != null) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                
                if (read > 0) {
                    val accepted = recognizer?.acceptWaveForm(buffer, read) ?: false
                    
                    if (accepted) {
                        // Final result for this phrase
                        val result = recognizer?.result
                        result?.let { parseAndNotifyResult(it, false) }
                    } else {
                        // Partial result
                        val partial = recognizer?.partialResult
                        partial?.let { parseAndNotifyResult(it, true) }
                    }
                }
            }
            
            // Get final result when stopping
            if (recognizer != null) {
                val finalResult = recognizer?.finalResult
                finalResult?.let { parseAndNotifyResult(it, false) }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in recognition loop", e)
            if (isRecording) {
                mainHandler.post { listener?.onError("Recognition error: ${e.message}") }
            }
        } finally {
            mainHandler.post { listener?.onDone() }
        }
    }
    
    private fun parseAndNotifyResult(jsonResult: String, isPartial: Boolean) {
        try {
            val json = JSONObject(jsonResult)
            val text = if (isPartial) {
                json.optString("partial", "")
            } else {
                json.optString("text", "")
            }
            
            if (text.isNotBlank()) {
                mainHandler.post {
                    if (isPartial) {
                        listener?.onPartialResult(text)
                    } else {
                        listener?.onResult(text)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing result: $jsonResult", e)
        }
    }
    
    /**
     * Stop listening and clean up audio resources.
     */
    fun stopListening() {
        Log.d(TAG, "Stopping listening...")
        isRecording = false
        
        try {
            recordingThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted waiting for recording thread")
        }
        recordingThread = null
        
        audioRecord?.let {
            if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                it.stop()
            }
            it.release()
        }
        audioRecord = null
        
        recognizer?.close()
        recognizer = null
        
        // Reset audio source
        audioManager?.setParameters("audio_source_record=off")
        audioManager = null
        
        Log.d(TAG, "Stopped listening")
    }
    
    /**
     * Release all resources. Call when done with speech recognition.
     */
    fun release() {
        stopListening()
        model?.close()
        model = null
        isModelReady = false
        executor.shutdown()
    }
    
    fun isListening(): Boolean = isRecording
    
    fun isReady(): Boolean = isModelReady && model != null
}
