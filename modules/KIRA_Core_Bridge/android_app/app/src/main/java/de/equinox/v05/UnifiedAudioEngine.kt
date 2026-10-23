package de.equinox.v05

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Step 41: the single hardware-audio owner for Equinox.
 *
 * LIVE, MEMORY preview, LAB preview and recording all use this engine. Other audio
 * helpers may construct read-only scenes/PCM descriptions, but no second class is
 * allowed to own an AudioTrack. The engine never writes into MemoryModel.
 */
class UnifiedAudioEngine {
    companion object {
        const val SAMPLE_RATE = 48_000
        private const val CHUNK_SAMPLES = 960 // 20 ms
    }

    private enum class PlaybackMode { CONTINUOUS, FINITE }

    @Volatile private var running = false
    @Volatile private var mode = PlaybackMode.CONTINUOUS
    @Volatile private var latestSound = SonificationSnapshot(emptyList(), emptyList())
    @Volatile private var latestWorking = WorkingSetSnapshot(emptyList(), emptyList())
    @Volatile private var latestCrossModal = KiraCrossModalProjection(output = KiraOutputLane.AUDIO)
    @Volatile private var suppressionHz = DoubleArray(0)
    @Volatile private var activeVoiceCount = 0
    @Volatile private var latestSummary = "0 Stimmen"
    @Volatile private var memoryBlend = 0.35
    @Volatile private var maxVoices = 9
    @Volatile private var tempoScale = 1.0
    @Volatile private var dreamWorld: DreamWorldState? = null
    @Volatile private var dreamAudioLinked = false
    @Volatile private var pcmTap: ((ShortArray) -> Unit)? = null

    private var worker: Thread? = null
    private var track: AudioTrack? = null

    fun isRunning(): Boolean = running
    fun isContinuous(): Boolean = running && mode == PlaybackMode.CONTINUOUS
    fun voiceCount(): Int = activeVoiceCount
    fun sceneSummary(): String = latestSummary

    fun setMemoryBlend(value: Float) { memoryBlend = value.coerceIn(0f, 1f).toDouble() }
    fun memoryBlend(): Double = memoryBlend
    fun setGenerationTuning(maxVoices: Int, tempoScale: Float) {
        this.maxVoices = maxVoices.coerceIn(2, 18)
        this.tempoScale = tempoScale.coerceIn(0.65f, 1.45f).toDouble()
    }
    fun generationMaxVoices(): Int = maxVoices
    fun generationTempoScale(): Double = tempoScale
    fun setDreamWorld(state: DreamWorldState?, linked: Boolean = state != null) {
        dreamWorld = state
        dreamAudioLinked = linked && state != null
    }
    fun dreamLinked(): Boolean = dreamAudioLinked
    fun setPcmTap(tap: ((ShortArray) -> Unit)?) { pcmTap = tap }
    fun suppressionFrequencies(): DoubleArray = suppressionHz.copyOf()

    fun updateState(
        sound: SonificationSnapshot,
        working: WorkingSetSnapshot,
        crossModal: KiraCrossModalProjection? = null,
    ) {
        latestSound = sound
        latestWorking = working
        if (crossModal != null) latestCrossModal = crossModal
    }

    fun setCrossModalProjection(projection: KiraCrossModalProjection) {
        latestCrossModal = projection
    }

    /** Continuous product audio: follows the same changing state as the GPU surface. */
    fun start(onState: (String) -> Unit) {
        if (running && mode == PlaybackMode.CONTINUOUS) return
        stop()
        mode = PlaybackMode.CONTINUOUS
        launch(onState, finiteSamples = null)
    }

    /** Finite read-only preview for MEMORY/LAB, through the exact same synth + AudioTrack. */
    fun playFinite(
        sound: SonificationSnapshot,
        working: WorkingSetSnapshot = WorkingSetSnapshot(emptyList(), emptyList()),
        durationSeconds: Double = 8.0,
        previewBlend: Double = 1.0,
        onState: (String) -> Unit
    ) {
        stop()
        latestSound = sound
        latestWorking = working
        mode = PlaybackMode.FINITE
        val samples = (SAMPLE_RATE * durationSeconds.coerceIn(0.5, 30.0)).toLong()
        launch(onState, finiteSamples = samples, fixedBlend = previewBlend.coerceIn(0.0, 1.0))
    }

    fun playFinite(state: EngineOutputState, onState: (String) -> Unit) =
        playFinite(state.sound, state.working, onState = onState)

    private fun launch(onState: (String) -> Unit, finiteSamples: Long?, fixedBlend: Double? = null) {
        running = true
        worker = thread(name = "EquinoxUnifiedAudio") {
            val synth = GenerativeOrchestraSynth(SAMPLE_RATE)
            try {
                val minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferBytes = max(minBuffer.coerceAtLeast(0), CHUNK_SAMPLES * 2 * 6)
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track = audioTrack
                audioTrack.play()
                onState(if (mode == PlaybackMode.CONTINUOUS) "Unified Audio: AKTIV" else "Unified Audio: PREVIEW")

                var rendered = 0L
                var lastStatusAt = 0L
                while (running && (finiteSamples == null || rendered < finiteSamples)) {
                    val count = if (finiteSamples == null) CHUNK_SAMPLES
                    else minOf(CHUNK_SAMPLES.toLong(), finiteSamples - rendered).toInt()
                    if (count <= 0) break
                    val baseScene = GenerativeOrchestraSceneBuilder.build(
                        latestSound,
                        latestWorking,
                        maxVoices = maxVoices,
                        memoryBlend = fixedBlend ?: memoryBlend,
                        crossModal = latestCrossModal,
                    )
                    val dream = dreamWorld.takeIf { dreamAudioLinked }
                    val scene = if (dream == null) {
                        baseScene.copy(
                            tempoBpm = (baseScene.tempoBpm * tempoScale).coerceIn(34.0, 180.0)
                        )
                    } else {
                        val pitchRatio = (0.90 + dream.environment.hue * 0.22 + dream.novelty * 0.06).coerceIn(0.82, 1.20)
                        val dreamVoices = baseScene.voices.mapIndexed { index, voice ->
                            val localRatio = pitchRatio * (1.0 + kotlin.math.sin(dream.tick * 0.011 + index * 0.73) * dream.tension * 0.025)
                            voice.copy(
                                baseHz = GenerativeOrchestraSceneBuilder.fold(voice.baseHz * localRatio),
                                memberHz = voice.memberHz.map { GenerativeOrchestraSceneBuilder.fold(it * localRatio) },
                                amplitude = (voice.amplitude * (0.78 + dream.energy * 0.52)).coerceIn(0.006, 0.19),
                                predictionConfidence = (voice.predictionConfidence * 0.65 + dream.stability * 0.35).coerceIn(0.0, 1.0),
                                crossmodalConfidence = (voice.crossmodalConfidence * 0.55 + dream.novelty * 0.45).coerceIn(0.0, 1.0),
                            )
                        }
                        baseScene.copy(
                            voices = dreamVoices,
                            tempoBpm = (baseScene.tempoBpm * tempoScale * (0.82 + dream.energy * 0.38 + dream.novelty * 0.12)).coerceIn(32.0, 186.0),
                            tension = (baseScene.tension * 0.55 + dream.tension * 0.45).coerceIn(0.0, 1.0),
                            activityDrive = (baseScene.activityDrive * 0.55 + dream.energy * 0.45).coerceIn(0.0, 1.0),
                            noveltyDrive = (baseScene.noveltyDrive * 0.45 + dream.novelty * 0.55).coerceIn(0.0, 1.0),
                            relationDrive = (baseScene.relationDrive * 0.55 + dream.stability * 0.45).coerceIn(0.0, 1.0),
                        )
                    }
                    activeVoiceCount = scene.voices.size
                    latestSummary = scene.compactSummary()
                    suppressionHz = synth.suppressionFrequencies(scene)
                    val pcm = synth.render(scene, count)
                    if (!running) break
                    try { pcmTap?.invoke(pcm) } catch (_: Exception) { }
                    audioTrack.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                    rendered += pcm.size

                    val now = System.currentTimeMillis()
                    if (now - lastStatusAt > 900L) {
                        lastStatusAt = now
                        onState("Unified Audio: ${scene.compactSummary()}")
                    }
                }
            } catch (e: Exception) {
                if (running) onState("Unified Audio: Fehler ${e.javaClass.simpleName}")
            } finally {
                running = false
                activeVoiceCount = 0
                suppressionHz = DoubleArray(0)
                latestSummary = "0 Stimmen"
                try { track?.pause() } catch (_: Exception) { }
                try { track?.flush() } catch (_: Exception) { }
                try { track?.stop() } catch (_: Exception) { }
                try { track?.release() } catch (_: Exception) { }
                track = null
                onState("Unified Audio: AUS")
            }
        }
    }

    fun stop() {
        running = false
        try { track?.pause() } catch (_: Exception) { }
        try { track?.flush() } catch (_: Exception) { }
        try { worker?.join(350L) } catch (_: InterruptedException) { }
        try { track?.release() } catch (_: Exception) { }
        track = null
        worker = null
        activeVoiceCount = 0
        suppressionHz = DoubleArray(0)
        latestSummary = "0 Stimmen"
    }
}
