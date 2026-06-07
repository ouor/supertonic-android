package com.ouor.supertonic.ui

import android.app.Application
import android.media.AudioDeviceCallback
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ouor.supertonic.audio.AudioExporter
import com.ouor.supertonic.audio.OutputDeviceManager
import com.ouor.supertonic.audio.OutputFormat
import com.ouor.supertonic.audio.PcmPlayer
import com.ouor.supertonic.data.ModelAssets
import com.ouor.supertonic.data.ModelDownloader
import com.ouor.supertonic.tts.Languages
import com.ouor.supertonic.tts.SupertonicTts
import com.ouor.supertonic.tts.VoiceStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    enum class Stage { CHECKING, NEEDS_DOWNLOAD, DOWNLOADING, LOADING, READY, ERROR }

    data class UiState(
        val stage: Stage = Stage.CHECKING,
        val text: String = DEFAULT_TEXT,
        val lang: String = "na",
        val voiceName: String = "M1",
        val totalStep: Int = 8,
        val speed: Float = 1.05f,
        val format: OutputFormat = OutputFormat.WAV,
        val devices: List<OutputDeviceManager.OutputDevice> = emptyList(),
        val selectedDeviceId: Int? = null,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = ModelAssets.KNOWN_TOTAL_BYTES,
        val downloadingFile: String = "",
        val isBusy: Boolean = false,      // synthesizing/exporting
        val isPlaying: Boolean = false,
        val message: String? = null,
        val errorMessage: String? = null,
    ) {
        val isReady: Boolean get() = stage == Stage.READY
        val languages: List<String> get() = Languages.AVAILABLE
        val voices: List<String> get() = ModelAssets.VOICE_NAMES
        val formats: List<OutputFormat> get() = OutputFormat.entries
    }

    private val appContext = app.applicationContext
    private val deviceManager = OutputDeviceManager(appContext)
    private val player = PcmPlayer()
    private val exporter = AudioExporter(appContext)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var engine: SupertonicTts? = null
    private val styleCache = mutableMapOf<String, VoiceStyle>()
    private var deviceCallback: AudioDeviceCallback? = null
    private var playJob: Job? = null

    // Cache the last synthesis so Play→Save reuses the audio when inputs are unchanged.
    private var lastPcm: FloatArray? = null
    private var lastKey: String? = null

    init {
        deviceCallback = deviceManager.registerCallback { refreshDevices() }
        refreshDevices()
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            if (ModelAssets.isReady(appContext)) {
                loadEngine()
            } else {
                _state.update { it.copy(stage = Stage.NEEDS_DOWNLOAD) }
            }
        }
    }

    fun startDownload() {
        if (_state.value.stage == Stage.DOWNLOADING) return
        _state.update { it.copy(stage = Stage.DOWNLOADING, errorMessage = null) }
        viewModelScope.launch {
            ModelDownloader(appContext).download().collect { p ->
                when (p) {
                    is ModelDownloader.Progress.Running -> _state.update {
                        it.copy(
                            downloadedBytes = p.downloadedBytes,
                            totalBytes = p.totalBytes,
                            downloadingFile = p.currentPath,
                        )
                    }
                    is ModelDownloader.Progress.Done -> loadEngine()
                    is ModelDownloader.Progress.Failed -> _state.update {
                        it.copy(stage = Stage.ERROR, errorMessage = p.message)
                    }
                }
            }
        }
    }

    private suspend fun loadEngine() {
        _state.update { it.copy(stage = Stage.LOADING, errorMessage = null) }
        try {
            engine = withContext(Dispatchers.IO) {
                SupertonicTts.load(ModelAssets.onnxDir(appContext))
            }
            _state.update { it.copy(stage = Stage.READY) }
        } catch (e: Exception) {
            _state.update { it.copy(stage = Stage.ERROR, errorMessage = e.message) }
        }
    }

    // --- user input ------------------------------------------------------- //

    fun onTextChange(v: String) = _state.update { it.copy(text = v) }
    fun onLangChange(v: String) = _state.update { it.copy(lang = v) }
    fun onVoiceChange(v: String) = _state.update { it.copy(voiceName = v) }
    fun onStepChange(v: Int) = _state.update { it.copy(totalStep = v) }
    fun onSpeedChange(v: Float) = _state.update { it.copy(speed = v) }
    fun onFormatChange(v: OutputFormat) = _state.update { it.copy(format = v) }
    fun onDeviceChange(id: Int?) = _state.update { it.copy(selectedDeviceId = id) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun refreshDevices() {
        val devices = deviceManager.listOutputs()
        _state.update { s ->
            val stillThere = devices.any { it.id == s.selectedDeviceId }
            s.copy(devices = devices, selectedDeviceId = if (stillThere) s.selectedDeviceId else null)
        }
    }

    // --- actions ---------------------------------------------------------- //

    fun playPreview() {
        val eng = engine ?: return
        if (_state.value.isBusy) return
        playJob?.cancel()
        playJob = viewModelScope.launch {
            try {
                val pcm = synthesize(eng)
                _state.update { it.copy(isBusy = false, isPlaying = true) }
                val device = _state.value.selectedDeviceId?.let { deviceManager.findById(it) }
                player.play(pcm, device)
            } catch (e: Exception) {
                _state.update { it.copy(message = "Playback failed: ${e.message}") }
            } finally {
                _state.update { it.copy(isBusy = false, isPlaying = false) }
            }
        }
    }

    fun stopPlayback() {
        playJob?.cancel()
        player.stop()
        _state.update { it.copy(isPlaying = false) }
    }

    fun save(destination: Uri) {
        val eng = engine ?: return
        if (_state.value.isBusy) return
        val format = _state.value.format
        viewModelScope.launch {
            try {
                val pcm = synthesize(eng)
                _state.update { it.copy(isBusy = false) }
                when (val r = exporter.export(pcm, eng.sampleRate, format, destination)) {
                    is AudioExporter.Result.Success ->
                        _state.update { it.copy(message = "Saved ${format.label}") }
                    is AudioExporter.Result.Failure ->
                        _state.update { it.copy(message = "Save failed: ${r.message}") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(message = "Save failed: ${e.message}") }
            } finally {
                _state.update { it.copy(isBusy = false) }
            }
        }
    }

    /** Synthesize current inputs (with caching), returning mono float PCM. */
    private suspend fun synthesize(eng: SupertonicTts): FloatArray {
        val s = _state.value
        val key = "${s.text}|${s.lang}|${s.voiceName}|${s.totalStep}|${s.speed}"
        lastPcm?.let { if (key == lastKey) return it }

        _state.update { it.copy(isBusy = true) }
        return withContext(Dispatchers.IO) {
            val style = styleCache.getOrPut(s.voiceName) {
                VoiceStyle.load(ModelAssets.voiceStyleFile(appContext, s.voiceName))
            }
            val result = eng.synthesize(s.text, s.lang, style, s.totalStep, s.speed)
            lastPcm = result.wav
            lastKey = key
            result.wav
        }
    }

    /** Suggested filename (without extension) derived from the input text. */
    fun suggestedBaseName(): String =
        _state.value.text.take(20).replace(Regex("[^\\w]"), "_").ifBlank { "supertonic" }

    override fun onCleared() {
        super.onCleared()
        playJob?.cancel()
        player.stop()
        deviceCallback?.let { deviceManager.unregisterCallback(it) }
        engine?.close()
    }

    private companion object {
        const val DEFAULT_TEXT =
            "Supertonic is a lightning fast, on-device text to speech system."
    }
}
