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
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Sherpa-onnx based offline speech recognition for AR glasses.
 * Uses the device's microphone with AR glasses-specific audio configuration.
 */
class SherpaSpeechRecognizer(private val context: Context) {
    
    companion object {
        private const val TAG = "SherpaSpeechRecognizer"
        private const val SAMPLE_RATE = 16000
        private const val SPEAKER_MIC = 23  // AR glasses specific microphone ID
        private const val ASSET_DIR = "sherpa-models"
        private const val AUDIO_GAIN = 8.0f  // Amplify audio for better sensitivity
    }
    
    interface SpeechListener {
        fun onResult(text: String)
        fun onPartialResult(text: String)
        fun onError(message: String)
        fun onListening()
        fun onDone()
    }
    
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var audioManager: AudioManager? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var listener: SpeechListener? = null
    private var isModelLoading = false
    private var isModelReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    
    fun setListener(listener: SpeechListener) {
        this.listener = listener
    }
    
    fun initModel(onComplete: (Boolean) -> Unit) {
        if (isModelLoading) return
        if (isModelReady && recognizer != null) {
            onComplete(true)
            return
        }
        
        isModelLoading = true
        Log.d(TAG, "Starting model initialization (from Assets)...")
        
        executor.execute {
            try {
                // English-only large model (INT8 quantized, ~70MB total)
                val tokens = "$ASSET_DIR/tokens.txt"
                val encoder = "$ASSET_DIR/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
                val decoder = "$ASSET_DIR/decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
                val joiner = "$ASSET_DIR/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
                
                val modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder,
                        decoder = decoder,
                        joiner = joiner
                    ),
                    tokens = tokens,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                    modelType = "zipformer2"
                )
                
                // Configure endpoint detection - less aggressive to avoid cutting off speech
                val endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0f),
                    rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.2f, minUtteranceLength = 0f),
                    rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0f, minUtteranceLength = 20f)
                )
                
                val config = OnlineRecognizerConfig(
                    modelConfig = modelConfig,
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    endpointConfig = endpointConfig
                )
                
                // Pass AssetManager to read directly from assets
                recognizer = OnlineRecognizer(context.assets, config)
                
                isModelReady = true
                isModelLoading = false
                Log.d(TAG, "Sherpa model initialized successfully from assets")
                mainHandler.post { onComplete(true) }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Sherpa model", e)
                isModelLoading = false
                isModelReady = false
                mainHandler.post { onComplete(false) }
            }
        }
    }
    
    private fun copyAssetFile(context: Context, assetPath: String, destFile: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }
    
    fun startListening() {
        if (isRecording) return
        
        if (!isModelReady || recognizer == null) {
            listener?.onError("Speech model not initialized")
            return
        }
        
        try {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager?.setParameters("audio_source_record=off") // Reset per SDK sample
            
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 4 
            
            // VOICE_RECOGNITION uses 3 mics to capture only wearer's voice
            // Combined with software gain for better sensitivity
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            // Disable noise suppression and echo cancellation for full frequency range
            audioRecord?.audioSessionId?.let { sessionId ->
                try {
                    if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                        val ns = android.media.audiofx.NoiseSuppressor.create(sessionId)
                        ns?.enabled = false
                        Log.d(TAG, "Disabled NoiseSuppressor")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not disable NoiseSuppressor: ${e.message}")
                }
                
                try {
                    if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                        val aec = android.media.audiofx.AcousticEchoCanceler.create(sessionId)
                        aec?.enabled = false
                        Log.d(TAG, "Disabled AcousticEchoCanceler")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not disable AcousticEchoCanceler: ${e.message}")
                }
                
                try {
                    if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                        val agc = android.media.audiofx.AutomaticGainControl.create(sessionId)
                        agc?.enabled = false
                        Log.d(TAG, "Disabled AutomaticGainControl")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not disable AutomaticGainControl: ${e.message}")
                }
                Unit
            }
            
            // Require specific mic for hardware support
            try {
                val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                devices?.forEach { device ->
                    if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && device.id == SPEAKER_MIC) {
                        audioRecord?.preferredDevice = device
                        Log.d(TAG, "Successfully set preferred device to SPEAKER_MIC (id=$SPEAKER_MIC)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not set preferred device: ${e.message}")
            }
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                listener?.onError("Failed to initialize microphone")
                return
            }
            
            audioRecord?.startRecording()
            
            // Create a new stream for this session
            stream = recognizer?.createStream()
            
            isRecording = true
            listener?.onListening()
            
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
        val floatBuffer = FloatArray(bufferSize / 2)
        
        Log.d(TAG, "Entering recognition loop. Buffer size: $bufferSize")
        
        try {
            while (isRecording && audioRecord != null) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                
                if (read > 0) {
                    // Convert short to float for Sherpa with gain amplification
                    for (i in 0 until read) {
                        val amplified = (buffer[i] * AUDIO_GAIN).coerceIn(-32768f, 32767f)
                        floatBuffer[i] = amplified / 32768.0f
                    }
                    
                    val currentStream = stream ?: break
                    
                    // Use copyOf with exact size instead of sliceArray for better performance
                    if (read == floatBuffer.size) {
                        currentStream.acceptWaveform(floatBuffer, SAMPLE_RATE)
                    } else {
                        currentStream.acceptWaveform(floatBuffer.copyOf(read), SAMPLE_RATE)
                    }
                    
                    while (recognizer?.isReady(currentStream) == true) {
                        recognizer?.decode(currentStream)
                    }
                    
                    val isEndpoint = recognizer?.isEndpoint(currentStream) ?: false
                    val text = recognizer?.getResult(currentStream)?.text ?: ""
                    
                    if (text.isNotBlank()) {
                        // Convert to sentence case (first letter uppercase, rest lowercase)
                        val lower = text.lowercase()
                        val sentenceCase = lower.substring(0, 1).uppercase() + lower.substring(1)
                        Log.d(TAG, "Decoded: '$sentenceCase' (Endpoint: $isEndpoint)")
                        if (isEndpoint) {
                             recognizer?.reset(currentStream)
                             mainHandler.post { listener?.onResult(sentenceCase) }
                        } else {
                             mainHandler.post { listener?.onPartialResult(sentenceCase) }
                        }
                    }
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord read error: $read")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in recognition loop", e)
            if (isRecording) {
                mainHandler.post { listener?.onError("Recognition error: ${e.message}") }
            }
        } finally {
            stream?.release()
            stream = null
            mainHandler.post { listener?.onDone() }
        }
    }
    
    fun stopListening() {
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
        stream?.release()
        stream = null
        
        audioManager?.setParameters("audio_source_record=off")
        audioManager = null
        
        Log.d(TAG, "Stopped listening")
    }
    
    fun release() {
        stopListening()
        recognizer?.release()
        recognizer = null
        isModelReady = false
        executor.shutdown()
    }
    
    fun isListening(): Boolean = isRecording
    
    fun isReady(): Boolean = isModelReady && recognizer != null
}
