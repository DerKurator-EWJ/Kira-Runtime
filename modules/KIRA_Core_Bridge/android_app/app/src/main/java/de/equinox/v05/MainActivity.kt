package de.equinox.v05

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.text.TextUtils
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.media.AudioFormat
import android.net.Uri
import android.content.Intent
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.media.audiofx.AcousticEchoCanceler
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.PowerManager
import android.view.Gravity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    companion object {
        private const val REQ_MIC = 100
        private const val REQ_CAMERA = 101
        private const val REQ_MULTI = 102
        private const val SAMPLE_RATE = 48_000
        private const val FFT_SIZE = 2_048
        private const val MEMORY_STEP_MS = 650L
        private const val DREAM_STEP_MS = 80L
        private const val HYBRID_STEP_MS = 420L
    }

    private enum class AppPage { LIVE, HYBRID, MEMORY, MIND, LAB }
    private enum class InputChannel { TEXT, AUDIO, CAMERA }
    private enum class OutputChannel { TEXT, VIDEO, MUSIC }
    private enum class MasterOutputTarget { TEXT, VIDEO, AUDIO }
    private enum class OutputCapability { ORCHESTRA, FILM, CODING, MATH, WRITING, FREE_CANVAS }
    // MUSIC is retained as the legacy enum name for the dedicated ID-flow viewport.
    private enum class PrimaryOutputSurface { TEXT, VIDEO, AUDIO, FILM }
    private enum class SettingsScreen { ROOT, TEXT, VIDEO, MUSIC, FILM_OUTPUT, DREAM, MODULES, MODULE_MATH, MODULE_CODING, MODULE_FILM, MODULE_ORCHESTRA, SYSTEM, ARCHIVE, MIND, KURATOR, KURATOR_MODE, LAB }

    @Volatile private var running = false
    @Volatile private var cameraRunning = false
    @Volatile private var memoryPlaybackRunning = false
    @Volatile private var dreamPlaybackRunning = false
    @Volatile private var hybridPlaybackRunning = false

    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private var visualFrameCount = 0L
    private var visualObservationCount = 0L
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    private lateinit var livePage: LinearLayout
    private lateinit var hybridPage: LinearLayout
    private lateinit var memoryPage: LinearLayout
    private lateinit var mindPage: LinearLayout
    private lateinit var labPage: LinearLayout
    private var currentPage = AppPage.LIVE
    private var settingsOpen = false
    private var lastLabRenderMs = 0L
    private lateinit var surfaceVideo: GpuMemoryVideoView
    private lateinit var settingsOverlay: ScrollView
    private lateinit var settingsScrim: View
    private lateinit var settingsSheet: FrameLayout
    private lateinit var captureRecorder: UnifiedCaptureRecorder
    private lateinit var captureButton: TextView
    private lateinit var productAudioButton: TextView
    private lateinit var productModeButton: TextView
    private lateinit var cognitiveModeStatus: TextView
    private lateinit var captureStatus: TextView
    private val capturePermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            CaptureForegroundService.start(this)
            // Give the foreground service a brief moment to enter foreground before MediaProjection.
            uiHandler.postDelayed({ startUnifiedCapture(result.resultCode, data) }, 140L)
        } else {
            if (::captureStatus.isInitialized) captureStatus.text = "Aufnahme: abgebrochen"
            if (::captureButton.isInitialized) captureButton.text = "REC"
            CaptureForegroundService.stop(this)
        }
    }
    private val profileExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null || !::profiles.isInitialized) return@registerForActivityResult
        val selected = selectedProfileInfo() ?: return@registerForActivityResult
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.let { profiles.exportProfile(selected.id, it) } ?: false
        }.getOrDefault(false)
        if (::profileStatus.isInitialized) profileStatus.text = if (ok) "Profil exportiert: ${selected.name}" else "Profil-Export fehlgeschlagen"
    }

    private val profileImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || !::profiles.isInitialized) return@registerForActivityResult
        val suggested = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported Memory"
        val imported = runCatching {
            contentResolver.openInputStream(uri)?.let { profiles.importProfile(it, suggested) }
        }.getOrNull()
        if (imported != null) {
            refreshProfileControls(selectId = imported.id)
            if (::profileStatus.isInitialized) profileStatus.text = "Importiert: ${imported.name} · LOAD zum Aktivieren"
        } else if (::profileStatus.isInitialized) {
            profileStatus.text = "Profil-Import fehlgeschlagen"
        }
    }

    private val externalKnowledgeImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        if (::externalKnowledgeStatus.isInitialized) externalKnowledgeStatus.text = "External Knowledge: Dry-Run…"
        thread(name = "Equinox-ExternalKnowledge-DryRun") {
            val report = runCatching {
                contentResolver.openInputStream(uri)?.let { ExternalKnowledgeConverter().parseAndCompile(it) }
            }.getOrNull() ?: ExternalImportReport(
                valid = false, sourceId = "", sourceVersion = "", packId = "", packVersion = 0,
                nodesRead = 0, macrosCompiled = 0, explicitRelationsRead = 0, embeddingRelationsDerived = 0, finalRelations = 0,
                issues = listOf(ImportIssue(ImportIssue.Severity.ERROR, "Datei konnte nicht gelesen werden"))
            )
            runOnUiThread { showExternalKnowledgeDryRun(report) }
        }
    }

    private val forgeIntermediateImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        if (::externalKnowledgeStatus.isInitialized) externalKnowledgeStatus.text = "Forge Intermediate v1: Dry-Run…"
        thread(name = "Kira-ForgeIntermediate-DryRun") {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.let { ForgeIntermediateJson.parseAndValidate(it) }
            }.getOrNull() ?: ForgeIntermediateJson.ParseResult(
                document = null,
                plan = null,
                parserIssues = listOf(ForgeValidationIssue(ForgeValidationIssue.Severity.ERROR, "READ", "Datei konnte nicht gelesen werden")),
            )
            runOnUiThread { showForgeIntermediateDryRun(result) }
        }
    }

    private val basePackageImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        if (::externalKnowledgeStatus.isInitialized) externalKnowledgeStatus.text = "BASE Package v1: prüfe…"
        thread(name = "Kira-BasePackage-Validate") {
            val report = runCatching {
                contentResolver.openInputStream(uri)?.let { KiraBasePackageCodec.readAndValidate(it) }
            }.getOrNull() ?: KiraBasePackageReport(
                valid = false,
                payload = null,
                issues = listOf(KiraBasePackageIssue(KiraBasePackageIssue.Severity.ERROR, "READ", "Datei konnte nicht gelesen werden")),
            )
            runOnUiThread { showBasePackageDryRun(report) }
        }
    }

    private val forgeKernelImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || !::mindRepository.isInitialized) return@registerForActivityResult
        if (::mindStatus.isInitialized) mindStatus.text = "MIND: Forge-Kernel wird geprüft…"
        thread(name = "Equinox-ForgeKernel-Import") {
            val result = runCatching {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Datei konnte nicht gelesen werden")
                val pack = ForgeKernelParserAndroid.parse(text)
                text to pack
            }
            runOnUiThread {
                result.onSuccess { (text, pack) -> showForgeKernelDryRun(text, pack) }
                    .onFailure { mindStatus.text = "MIND: Forge-Kernel abgelehnt · ${it.message ?: it::class.java.simpleName}" }
            }
        }
    }

    private val backgroundMediaPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val mime = contentResolver.getType(uri) ?: if (uri.toString().contains("video", ignoreCase = true)) "video/*" else "image/*"
        mediaBackgroundUri = uri.toString()
        mediaBackgroundMime = mime
        mediaBackgroundEnabled = true
        persistModalityRouting()
        if (::mediaBackdropView.isInitialized) {
            mediaBackdropView.setMedia(uri, mime)
            mediaBackdropView.setMediaEnabled(true)
        }
        applyLayerComposition()
        if (settingsOpen) {
            settingsScreen = SettingsScreen.VIDEO
            renderSettingsScreen()
        }
    }

    private val livePipeline = LowLatencyLivePipeline()
    private val performanceGovernor = PerformanceGovernor()
    private var visualAnalyzer: VisualGridAnalyzer? = null
    private var lastGovernorUpdateMs = 0L
    private val liveRenderPosted = AtomicBoolean(false)
    private lateinit var liveDelaySpinner: Spinner
    private lateinit var liveLatencyStatus: TextView

    private lateinit var status: TextView
    private lateinit var frequency: TextView
    private lateinit var frequencyId: TextView
    private lateinit var strength: TextView
    private lateinit var frames: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var memoryStats: TextView
    private lateinit var labMemoryStats: TextView
    private lateinit var intervalSpinner: Spinner
    private lateinit var outputCanvas: EquinoxOutputView
    private lateinit var hybridCanvas: EquinoxOutputView
    private lateinit var liveVideoCanvas: MemoryVideoView
    private lateinit var hybridVideoCanvas: MemoryVideoView
    private lateinit var memoryCanvas: EquinoxOutputView
    private lateinit var graphView: RelationGraphView
    private lateinit var memoryModeStatus: TextView
    private lateinit var dreamModeStatus: TextView
    private lateinit var hybridModeStatus: TextView
    private lateinit var hybridSensorStatus: TextView
    private lateinit var memoryOrchestraStatus: TextView
    private lateinit var memoryAutoOrchestra: CheckBox
    private lateinit var hybridOrchestraStatus: TextView

    private lateinit var memory: MemoryModel
    private lateinit var outputBridge: EngineOutputBridge
    // Step 41: exactly one engine owns AudioTrack for every Equinox output mode.
    private val unifiedAudio = UnifiedAudioEngine()
    private val selfOutputSuppressor = AudioSelfOutputSuppressor(SAMPLE_RATE)
    private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var feedbackProtectionEnabled = true
    @Volatile private var lastSelfOutputRemovedRatio = 0.0
    @Volatile private var lastSelfOutputComponents = 0
    private var feedbackUiSyncing = false
    private lateinit var liveContinuousButton: Button
    private lateinit var hybridContinuousButton: Button
    private lateinit var liveFeedbackProtectionToggle: CheckBox
    private lateinit var hybridFeedbackProtectionToggle: CheckBox

    private lateinit var sonificationStatus: TextView
    private lateinit var labOrchestraStatus: TextView
    private lateinit var liveOrchestraStatus: TextView
    private lateinit var consolidationStatus: TextView
    private lateinit var cameraStatus: TextView
    private lateinit var cameraFrames: TextView
    private lateinit var cameraButton: Button
    private lateinit var cameraStopButton: Button
    private lateinit var displayPrefsStore: DisplayPrefsStore
    private lateinit var saveSlots: SaveSlotManager
    private lateinit var profiles: MemoryProfileManager
    private lateinit var profileSpinner: Spinner
    private lateinit var profileStatus: TextView
    private lateinit var externalKnowledgeStatus: TextView
    private lateinit var mindStatus: TextView
    private lateinit var mindSource: EditText
    private lateinit var mindRepository: EquinoxKernelRepository
    private val mindLanguage = EquinoxLanguageEngine()
    private lateinit var chatEngine: EquinoxChatEngine
    private lateinit var chatOverlay: FrameLayout
    private lateinit var stageRootFrame: FrameLayout
    private lateinit var chatContent: LinearLayout
    private lateinit var mediaBackdropView: MediaBackdropView
    private lateinit var proceduralFilmView: ProceduralFilmView
    private lateinit var dreamWorldView: DreamWorldView
    private lateinit var dreamRuntime: DreamRuntime
    private lateinit var matrixRainView: MatrixActivityView
    private lateinit var nowGateView: NowGateView
    private lateinit var chatScroll: ScrollView
    private lateinit var chatMessages: LinearLayout
    private lateinit var chatInput: EditText
    private lateinit var chatComposer: LinearLayout
    private lateinit var chatRule: View
    private lateinit var mainViewDock: LinearLayout
    private lateinit var textViewModeButton: TextView
    private lateinit var videoViewModeButton: TextView
    private lateinit var musicViewModeButton: TextView
    private lateinit var chatStatus: TextView
    private lateinit var chatStyleButton: TextView
    private lateinit var chatControlButton: TextView
    private lateinit var chatSendButton: TextView
    @Volatile private var chatSending = false
    private var lastCrossModalTextPulseAt = 0L
    private var lastCrossModalTextSeed = 0L
    private var chatVisible = false
    private var chatDisplayMode = ChatDisplayMode.MATRIX
    private var terminalNotice: String? = null
    private var idFlowSpeed = 0.32f
    private var idFlowPulse = true
    private var nowGateSpeed = 0.42f
    private var dreamControlMode = DreamControlMode.REACTIVE
    private var dreamInfluenceStrength = 0.58f
    private var dreamWorldSpeed = 1.0f
    private var dreamAudioLinked = true
    private var dreamVisualEnabled = true
    private var lastDreamText = ""
    private var dreamMemoryStepCounter = 0

    // v0.8.3: the top dock controls INPUT only. Outputs are composited as independent layers.
    // View selection is a third, independent layer: Kira, ID-flow and Now Gate can be
    // inspected without changing which inputs are live or which outputs are enabled.
    private lateinit var modalityPrefs: android.content.SharedPreferences
    private lateinit var modalityDock: LinearLayout
    private lateinit var inputMenuButton: TextView
    private lateinit var masterMenuButton: TextView
    private lateinit var featureMenuButton: TextView
    private lateinit var settingsMenuButton: TextView
    private lateinit var macroDepthSlabView: MacroDepthSlabView
    private lateinit var dialecticTierSlabView: DialecticTierSlabView
    private var macroDepthVisible = false
    private var dialecticTierVisible = true
    private var relationTraceVisible = false
    private var coreOutputMode = CoreOutputMode.WRITE
    private val coreStimulusMasks = java.util.concurrent.ConcurrentHashMap<CoreOutputMode, CoreStimulusMask>()
    private var inputPopupWindow: PopupWindow? = null
    private var masterPopupWindow: PopupWindow? = null
    private var featurePopupWindow: PopupWindow? = null
    private var settingsQuickPopupWindow: PopupWindow? = null
    private var mediaPopupWindow: PopupWindow? = null
    private var openSettingsAction: ((SettingsScreen) -> Unit)? = null
    private var closeSettingsAction: (() -> Unit)? = null
    private val masterRoutes = BooleanArray(9) { true }
    private val operationalRegistry = KiraOperationalRegistry()
    private var mathPrecision = 6
    private var mathFunctionsEnabled = true
    private var codingLanguage = CodingLanguage.KOTLIN
    private var codingStrict = true
    private var codingMemoryContext = true
    private var filmIntensity = 0.55f
    private var filmStateInfluence = 0.70f
    private var orchestraMemoryBlend = 0.35f
    private var orchestraMaxVoices = 9
    private var orchestraTempoScale = 1.0f
    private var textInputEnabled = true
    private var audioInputEnabled = false
    private var cameraInputEnabled = false
    private var textOutputEnabled = true
    private var videoOutputEnabled = false
    private var musicOutputEnabled = false
    private var idFlowOutputEnabled = true
    private var mediaBackgroundEnabled = false
    private var mediaBackgroundUri: String? = null
    private var mediaBackgroundMime: String? = null
    private var stageBackgroundColor: Int = Color.BLACK
    // v0.8.3: ID-water is a distinct renderer. It no longer piggybacks on visible
    // MatrixActivityView lanes. The Now Gate is likewise a background deformation,
    // not a drawn tree overlay.
    private var idWaterEnabled = false
    private var idWaterStrength = 0.72f
    private var nowGateEffectEnabled = false
    // v0.8.4: work capabilities are independent switches inside output submenus.
    // They are routing intents, not mutually exclusive app modes.
    private val activeOutputCapabilities = linkedSetOf<OutputCapability>()
    private var routingOwnsAudio = false
    private var routingOwnsCamera = false

    private lateinit var settingsRootLayout: LinearLayout
    private lateinit var settingsContent: LinearLayout
    private lateinit var settingsBreadcrumb: TextView
    private lateinit var settingsBackButton: TextView
    private lateinit var developerLabHost: LinearLayout
    private var settingsScreen = SettingsScreen.ROOT
    private var profileSpinnerSyncing = false
    private lateinit var diagnostics: DiagnosticsManager
    private lateinit var diagnosticsStatus: TextView
    private lateinit var hardTest: LongTermHardTestManager
    private lateinit var hardTestStatus: TextView
    private lateinit var audioEncoderModeStore: AudioEncoderModeStore
    private var audioEncoderMode: AudioEncoderMode = AudioEncoderMode.FFT_FREQUENCY
    private lateinit var audioEncoderStatus: TextView
    private lateinit var labAudioEncoderStatus: TextView
    private lateinit var encoderBenchmark: EncoderBenchmarkManager
    private lateinit var encoderBenchmarkStatus: TextView
    private lateinit var encoderBenchmarkToggle: CheckBox
    private lateinit var adaptiveQuantizer: AdaptiveAudioQuantizer
    private lateinit var adaptiveVisualQuantizer: AdaptiveVisualQuantizer
    private lateinit var adaptiveVisualQuantizerStatus: TextView
    private lateinit var adaptiveQuantizerStatus: TextView
    @Volatile private var parallelEncoderBenchmarkEnabled = false

    private var lastConsolidationSummary = "Noch keine Archiv-Konsolidierung in dieser Sitzung"
    @Volatile private var lastConsolidationMs = 0L

    private val uiHandler = Handler(Looper.getMainLooper())
    private val memoryLoop = object : Runnable {
        override fun run() {
            if (!memoryPlaybackRunning) return

            val ok = memory.memoryPlaybackStep()
            if (!ok) {
                memoryPlaybackRunning = false
                memoryModeStatus.text = "ARCHIVE: noch keine gelernten Makros"
                return
            }

            refreshMemoryPage()
            updateMemoryStats()

            if (::memoryAutoOrchestra.isInitialized && memoryAutoOrchestra.isChecked && !unifiedAudio.isRunning()) {
                val state = outputBridge.snapshot()
                unifiedAudio.playFinite(state) { text ->
                    runOnUiThread { if (::memoryOrchestraStatus.isInitialized) memoryOrchestraStatus.text = text }
                }
            }

            uiHandler.postDelayed(this, MEMORY_STEP_MS)
        }
    }

    private val dreamLoop = object : Runnable {
        override fun run() {
            if (!dreamPlaybackRunning) return

            // The DREAM world never depends on learned macros. Memory traversal is an
            // optional teacher/driver layered into the same persistent world.
            dreamMemoryStepCounter++
            if (memory.hasLearnedMacros() && dreamMemoryStepCounter % 6 == 0) {
                when {
                    memory.cognitiveMode() == CognitiveMode.DREAM -> runCatching { memory.autonomousDreamStep() }
                    dreamControlMode == DreamControlMode.FUSION && memory.cognitiveMode() == CognitiveMode.PLAY -> runCatching { memory.hybridPlaybackStep() }
                }
            }

            val working = runCatching { workingSnapshotForTarget(MasterOutputTarget.VIDEO) }
                .getOrElse { memory.unifiedWorkingSnapshot() }
            val ids = runCatching { expressionIdsForTarget(MasterOutputTarget.VIDEO) }.getOrDefault(emptyList())
            val dreamText = lastDreamText.takeIf {
                dreamControlMode != DreamControlMode.AUTONOMOUS &&
                    textInputEnabled && masterMask(MasterOutputTarget.VIDEO).text
            }.orEmpty()
            val context = moduleContext(dreamText, working, ids, includeMemoryHints = false)
            val influence = buildDreamInfluence(working, context)
            val world = dreamRuntime.step(
                context = context,
                external = influence,
                mode = dreamControlMode,
                influenceStrength = dreamInfluenceStrength.toDouble(),
                speed = dreamWorldSpeed.toDouble(),
            )
            if (world.tick % 120L == 0L) persistDreamWorldState()

            if (::dreamWorldView.isInitialized) {
                val visualAllowed = dreamVisualEnabled && (videoOutputEnabled || OutputCapability.FILM in activeOutputCapabilities)
                dreamWorldView.setWorldState(world.takeIf { visualAllowed }, animate = visualAllowed)
            }
            unifiedAudio.setDreamWorld(world, linked = dreamAudioLinked && dreamPlaybackRunning)

            // Keep all other expression layers in sync with the same evolving state.
            if (::matrixRainView.isInitialized || ::mediaBackdropView.isInitialized) syncExpressionRenderers()
            if (settingsOpen && ::dreamModeStatus.isInitialized) {
                dreamModeStatus.text = "DREAM: ${world.compactSummary()}"
            }
            if (unifiedAudio.isRunning()) pushContinuousOrchestraState()

            uiHandler.postDelayed(this, DREAM_STEP_MS)
        }
    }

    private val hybridLoop = object : Runnable {
        override fun run() {
            if (!hybridPlaybackRunning) return

            val ok = memory.hybridPlaybackStep()
            refreshHybridPage(ok)
            updateMemoryStats()
            if (unifiedAudio.isRunning()) pushContinuousOrchestraState()
            uiHandler.postDelayed(this, HYBRID_STEP_MS)
        }
    }

    private val hardTestLoop = object : Runnable {
        override fun run() {
            if (!::hardTest.isInitialized || !hardTest.isRunning()) return
            captureHardTestSample(saveMemory = true)
            uiHandler.postDelayed(this, LongTermHardTestManager.DEFAULT_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Kira owns the whole visual stage; keep the Android system bars transparent
        // instead of painting an opaque strip below the composer.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        memory = MemoryModel(this)
        mindRepository = EquinoxKernelRepository(this, memory)
        chatEngine = EquinoxChatEngine(this, mindRepository)
        chatDisplayMode = chatEngine.displayMode()
        modalityPrefs = getSharedPreferences("equinox_modality_router", MODE_PRIVATE)
        coreOutputMode = runCatching {
            CoreOutputMode.valueOf(modalityPrefs.getString("core_output_mode", CoreOutputMode.WRITE.name) ?: CoreOutputMode.WRITE.name)
        }.getOrDefault(CoreOutputMode.WRITE)
        CoreOutputMode.entries.forEach { mode ->
            coreStimulusMasks[mode] = CoreStimulusMask(
                text = modalityPrefs.getBoolean("core_${mode.name.lowercase()}_text", true),
                audio = modalityPrefs.getBoolean("core_${mode.name.lowercase()}_audio", true),
                camera = modalityPrefs.getBoolean("core_${mode.name.lowercase()}_camera", true),
            )
        }
        for (input in 0..2) {
            for (output in 0..2) {
                masterRoutes[input * 3 + output] = modalityPrefs.getBoolean("master_route_${input}_${output}", true)
            }
        }
        syncCoreMasksFromMasterRoutes()
        macroDepthVisible = modalityPrefs.getBoolean("macro_depth_visible", false)
        dialecticTierVisible = modalityPrefs.getBoolean("dialectic_tier_visible", true)
        relationTraceVisible = modalityPrefs.getBoolean("relation_trace_visible", false)
        textInputEnabled = modalityPrefs.getBoolean("text_input", true)
        audioInputEnabled = modalityPrefs.getBoolean("audio_input", false)
        cameraInputEnabled = modalityPrefs.getBoolean("camera_input", false)
        textOutputEnabled = modalityPrefs.getBoolean("text_output", true)
        videoOutputEnabled = modalityPrefs.getBoolean("video_output", false)
        nowGateEffectEnabled = modalityPrefs.getBoolean("now_gate_effect", videoOutputEnabled)
        musicOutputEnabled = modalityPrefs.getBoolean("music_output", false)
        idFlowOutputEnabled = modalityPrefs.getBoolean("id_flow_output", true)
        idFlowSpeed = modalityPrefs.getFloat("id_flow_speed", 0.32f).coerceIn(0.05f, 2.5f)
        idFlowPulse = modalityPrefs.getBoolean("id_flow_pulse", true)
        nowGateSpeed = modalityPrefs.getFloat("now_gate_speed", 0.42f).coerceIn(0.05f, 2.5f)
        dreamControlMode = runCatching {
            DreamControlMode.valueOf(modalityPrefs.getString("dream_control_mode", DreamControlMode.REACTIVE.name) ?: DreamControlMode.REACTIVE.name)
        }.getOrDefault(DreamControlMode.REACTIVE)
        dreamInfluenceStrength = modalityPrefs.getFloat("dream_influence_strength", 0.58f).coerceIn(0f, 1f)
        dreamWorldSpeed = modalityPrefs.getFloat("dream_world_speed", 1.0f).coerceIn(0.25f, 3.0f)
        dreamAudioLinked = modalityPrefs.getBoolean("dream_audio_linked", true)
        dreamVisualEnabled = modalityPrefs.getBoolean("dream_visual_enabled", true)
        val restoredDream = DreamWorldCodec.decode(modalityPrefs.getString("dream_world_state", null))
        dreamRuntime = DreamRuntime(restored = restoredDream)
        refreshDreamKnowledgeBridge()
        mediaBackgroundEnabled = modalityPrefs.getBoolean("media_background_enabled", false)
        mediaBackgroundUri = modalityPrefs.getString("media_background_uri", null)
        mediaBackgroundMime = modalityPrefs.getString("media_background_mime", null)
        stageBackgroundColor = modalityPrefs.getInt("stage_background_color", Color.BLACK)
        idWaterEnabled = modalityPrefs.getBoolean("id_water_enabled", false)
        idWaterStrength = modalityPrefs.getFloat(
            "id_water_strength",
            modalityPrefs.getFloat("media_refraction_strength", 0.72f)
        ).coerceIn(0f, 1.6f)
        mathPrecision = modalityPrefs.getInt("module_math_precision", 6).coerceIn(0, 12)
        mathFunctionsEnabled = modalityPrefs.getBoolean("module_math_functions", true)
        codingLanguage = runCatching {
            CodingLanguage.valueOf(modalityPrefs.getString("module_coding_language", CodingLanguage.KOTLIN.name) ?: CodingLanguage.KOTLIN.name)
        }.getOrDefault(CodingLanguage.KOTLIN)
        codingStrict = modalityPrefs.getBoolean("module_coding_strict", true)
        codingMemoryContext = modalityPrefs.getBoolean("module_coding_memory", true)
        filmIntensity = modalityPrefs.getFloat("module_film_intensity", 0.55f).coerceIn(0f, 1f)
        filmStateInfluence = modalityPrefs.getFloat("module_film_state", 0.70f).coerceIn(0f, 1f)
        orchestraMemoryBlend = modalityPrefs.getFloat("module_orchestra_memory", 0.35f).coerceIn(0f, 1f)
        orchestraMaxVoices = modalityPrefs.getInt("module_orchestra_voices", 9).coerceIn(2, 18)
        orchestraTempoScale = modalityPrefs.getFloat("module_orchestra_tempo", 1.0f).coerceIn(0.65f, 1.45f)
        activeOutputCapabilities.clear()
        OutputCapability.entries.forEach { capability ->
            if (modalityPrefs.getBoolean("cap_${capability.name.lowercase()}", false)) {
                activeOutputCapabilities += capability
            }
        }
        memory.setCognitiveMode(
            if (modalityPrefs.getBoolean("product_learning_enabled", true)) CognitiveMode.LEARN else CognitiveMode.PLAY
        )
        outputBridge = EngineOutputBridge(memory)
        cameraExecutor = Executors.newSingleThreadExecutor()
        displayPrefsStore = DisplayPrefsStore(this)
        saveSlots = SaveSlotManager(this)
        profiles = MemoryProfileManager(this)
        diagnostics = DiagnosticsManager(this)
        hardTest = LongTermHardTestManager(this)
        audioEncoderModeStore = AudioEncoderModeStore(this)
        audioEncoderMode = audioEncoderModeStore.load()
        encoderBenchmark = EncoderBenchmarkManager(this)
        adaptiveQuantizer = AdaptiveAudioQuantizer(this)
        adaptiveVisualQuantizer = AdaptiveVisualQuantizer(this)
        captureRecorder = UnifiedCaptureRecorder(this)
        val latencyPrefs = getSharedPreferences("equinox_live_latency", MODE_PRIVATE)
        livePipeline.setPreset(
            LowLatencyLivePipeline.DelayPreset.fromName(latencyPrefs.getString("preset", null))
        )

        // Step 46.1: HYPNO is the only product renderer. Live sensors may still activate
        // the Unified State, but raw camera pigment is no longer exposed as a Reality mode.
        stageRootFrame = FrameLayout(this).apply {
            setBackgroundColor(stageBackgroundColor)
        }
        val rootFrame = stageRootFrame

        surfaceVideo = GpuMemoryVideoView(this).apply {
            setModeLabel("NOW GATE")
            setPresentationMode(UnifiedPresentationMode.HYPNO)
            // Pure generative HYPNO surface: sensor input influences state, not visible camera pixels.
            setMemoryBlend(1.0f)
            visibility = View.VISIBLE
        }
        // Audio still reads the same Unified State. Its internal context weighting is not a product UI mode.
        unifiedAudio.setMemoryBlend(0.35f)
        rootFrame.addView(surfaceVideo, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        surfaceVideo.visibility = View.GONE

        proceduralFilmView = ProceduralFilmView(this).apply { visibility = View.GONE }
        rootFrame.addView(proceduralFilmView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        mediaBackdropView = MediaBackdropView(this).apply {
            setIdWaterEnabled(idWaterEnabled)
            setWaterStrength(idWaterStrength)
            setNowGateEnabled(videoOutputEnabled && nowGateEffectEnabled)
            setNowGateSpeed(nowGateSpeed)
            visibility = View.GONE
        }
        rootFrame.addView(mediaBackdropView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        mediaBackgroundUri?.let { saved ->
            runCatching { Uri.parse(saved) }.getOrNull()?.let { uri ->
                mediaBackdropView.setMedia(uri, mediaBackgroundMime)
                mediaBackdropView.setMediaEnabled(mediaBackgroundEnabled)
            }
        }

        dreamWorldView = DreamWorldView(this).apply {
            visibility = View.GONE
            setWorldState(dreamRuntime.snapshot(), animate = false)
            visibility = View.GONE
        }
        rootFrame.addView(dreamWorldView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        matrixRainView = MatrixActivityView(this).apply {
            visibility = View.GONE
            setSpeedMultiplier(idFlowSpeed)
            setPulseEnabled(idFlowPulse)
        }
        rootFrame.addView(matrixRainView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        buildChatOverlay(rootFrame)

        // Compact product dock. HYPNO itself has no mode switch and no Reality <-> Memory slider.
        // LIVE/DREAM starts or stops the persistent DreamWorld runtime; primary outputs still decide what is visible/audible.
        val controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = roundedBackground(Color.argb(218, 12, 14, 20), 24f, Color.argb(42, 255, 255, 255), 1)
            elevation = dp(12).toFloat()
        }
        productModeButton = TextView(this).apply {
            text = "LIVE"
            textSize = 11f
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(145, 230, 210))
            background = roundedBackground(Color.argb(64, 255, 255, 255), 18f)
            isClickable = true
            isFocusable = true
            contentDescription = "Aktivierungsquelle LIVE oder DREAM"
            attachPressMotion(this)
        }
        productAudioButton = TextView(this).apply {
            text = "♪"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.argb(64, 255, 255, 255), 18f)
            isClickable = true
            isFocusable = true
            contentDescription = "Unified Audio an oder aus"
            attachPressMotion(this)
        }
        captureButton = TextView(this).apply {
            text = "REC"
            textSize = 11f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 112, 112))
            background = roundedBackground(Color.argb(64, 255, 255, 255), 18f)
            isClickable = true
            isFocusable = true
            attachPressMotion(this)
        }
        chatControlButton = TextView(this).apply {
            text = "CHAT"
            textSize = 10f
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(125, 235, 175))
            background = roundedBackground(Color.argb(64, 255, 255, 255), 18f)
            isClickable = true
            isFocusable = true
            contentDescription = "Equinox Text Chat öffnen oder schließen"
            attachPressMotion(this)
        }
        val settingsButton = TextView(this).apply {
            text = "⚙"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.argb(64, 255, 255, 255), 18f)
            isClickable = true
            isFocusable = true
            attachPressMotion(this)
        }
        // Iceberg surface: the primary screen exposes only the terminal and one quiet settings entry.
        // Product/diagnostic controls still exist and are reachable in the hidden machine room.
        controlBar.setBackgroundColor(Color.TRANSPARENT)
        controlBar.setPadding(0, 0, 0, 0)
        controlBar.addView(settingsButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        val controlBarParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(10)
            marginEnd = dp(10)
        }
        controlBar.visibility = View.GONE
        rootFrame.addView(controlBar, controlBarParams)
        buildModalityDock(rootFrame)

        dialecticTierSlabView = DialecticTierSlabView(this).apply {
            visibility = if (dialecticTierVisible) View.VISIBLE else View.GONE
            elevation = dp(10).toFloat()
        }
        rootFrame.addView(dialecticTierSlabView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(88), Gravity.TOP
        ).apply {
            topMargin = dp(68)
            marginStart = dp(12)
            marginEnd = dp(12)
        })

        macroDepthSlabView = MacroDepthSlabView(this).apply {
            visibility = if (macroDepthVisible) View.VISIBLE else View.GONE
            elevation = dp(9).toFloat()
        }
        rootFrame.addView(macroDepthSlabView, FrameLayout.LayoutParams(
            dp(286), dp(150), Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(164)
            marginEnd = dp(12)
        })

        rootFrame.setOnApplyWindowInsetsListener { _, insets ->
            val lp = controlBar.layoutParams as FrameLayout.LayoutParams
            val top = insets.systemWindowInsetTop + dp(10)
            if (lp.topMargin != top) {
                lp.topMargin = top
                controlBar.layoutParams = lp
            }
            if (::modalityDock.isInitialized) {
                val dockLp = modalityDock.layoutParams as FrameLayout.LayoutParams
                if (dockLp.topMargin != top) {
                    dockLp.topMargin = top
                    modalityDock.layoutParams = dockLp
                }
            }
            if (::dialecticTierSlabView.isInitialized) {
                val tierLp = dialecticTierSlabView.layoutParams as FrameLayout.LayoutParams
                val tierTop = top + dp(58)
                if (tierLp.topMargin != tierTop) {
                    tierLp.topMargin = tierTop
                    dialecticTierSlabView.layoutParams = tierLp
                }
            }
            if (::macroDepthSlabView.isInitialized) {
                val depthLp = macroDepthSlabView.layoutParams as FrameLayout.LayoutParams
                val depthTop = top + dp(154)
                if (depthLp.topMargin != depthTop) {
                    depthLp.topMargin = depthTop
                    macroDepthSlabView.layoutParams = depthLp
                }
            }
            if (::chatOverlay.isInitialized) {
                val chatLp = chatOverlay.layoutParams as FrameLayout.LayoutParams
                val chatBottom = insets.systemWindowInsetBottom
                if (chatLp.bottomMargin != chatBottom) {
                    chatLp.bottomMargin = chatBottom
                    chatOverlay.layoutParams = chatLp
                }
            }
            if (::settingsSheet.isInitialized) {
                val sheetLp = settingsSheet.layoutParams as FrameLayout.LayoutParams
                val sheetBottom = insets.systemWindowInsetBottom
                if (sheetLp.bottomMargin != sheetBottom) {
                    sheetLp.bottomMargin = sheetBottom
                    settingsSheet.layoutParams = sheetLp
                }
            }
            insets
        }

        // Step 35: settings are a true animated bottom sheet. The unified canvas remains
        // visible beneath a dim scrim, so opening controls never feels like navigating away.
        settingsScrim = View(this).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            alpha = 0f
            visibility = View.GONE
            isClickable = true
        }
        rootFrame.addView(settingsScrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        settingsSheet = FrameLayout(this).apply {
            background = roundedBackground(Color.rgb(17, 19, 25), 30f, Color.argb(38, 255, 255, 255), 1)
            elevation = dp(24).toFloat()
            visibility = View.INVISIBLE
        }
        val sheetParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.82f).roundToInt(),
            Gravity.BOTTOM
        ).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        }
        rootFrame.addView(settingsSheet, sheetParams)

        settingsOverlay = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        settingsRootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(14), dp(14), dp(40))
            setBackgroundColor(Color.TRANSPARENT)
        }
        settingsOverlay.addView(settingsRootLayout, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        settingsSheet.addView(settingsOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val grabber = View(this).apply {
            background = roundedBackground(Color.argb(90, 235, 238, 245), 4f)
        }
        settingsSheet.addView(grabber, FrameLayout.LayoutParams(dp(42), dp(4), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(8)
        })

        val settingsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        settingsBackButton = TextView(this).apply {
            text = "‹"
            textSize = 30f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(0, 255, 0))
            setPadding(dp(4), 0, dp(12), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { navigateSettingsBack() }
        }
        settingsBreadcrumb = TextView(this).apply {
            text = "KIRA"
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(0, 255, 0))
            gravity = Gravity.CENTER_VERTICAL
        }
        val settingsCloseButton = TextView(this).apply {
            text = "×"
            textSize = 26f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(170, 185, 175))
            setPadding(dp(12), 0, dp(4), 0)
            isClickable = true
            isFocusable = true
        }
        settingsHeader.addView(settingsBackButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        settingsHeader.addView(settingsBreadcrumb, LinearLayout.LayoutParams(0, dp(42), 1f))
        settingsHeader.addView(settingsCloseButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        settingsRootLayout.addView(settingsHeader, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        settingsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
        }
        settingsRootLayout.addView(settingsContent, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        buildDeveloperLabHost()
        renderSettingsScreen()

        if (::liveVideoCanvas.isInitialized) liveVideoCanvas.visibility = View.GONE
        if (::outputCanvas.isInitialized) outputCanvas.visibility = View.GONE
        if (::hybridVideoCanvas.isInitialized) hybridVideoCanvas.visibility = View.GONE
        if (::hybridCanvas.isInitialized) hybridCanvas.visibility = View.GONE
        if (::memoryCanvas.isInitialized) memoryCanvas.visibility = View.GONE

        fun openSettingsOverlay(startScreen: SettingsScreen = SettingsScreen.ROOT) {
            hideKeyboardNow()
            if (settingsOpen) {
                settingsScreen = startScreen
                renderSettingsScreen()
                return
            }
            dismissTopMenus()
            settingsOpen = true
            refreshModalityDock()
            settingsScreen = startScreen
            renderSettingsScreen()
            settingsScrim.visibility = View.VISIBLE
            settingsSheet.visibility = View.VISIBLE
            settingsScrim.animate().alpha(1f).setDuration(180L).start()
            settingsSheet.post {
                settingsSheet.translationY = settingsSheet.height.toFloat()
                settingsSheet.alpha = 0.96f
                settingsSheet.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(300L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }

        fun closeSettingsOverlay() {
            if (!settingsOpen) return
            // STRUCTURE is a LAB-only diagnostic preview. The product surface is always HYPNO.
            surfaceVideo.setPresentationMode(UnifiedPresentationMode.HYPNO)
            surfaceVideo.setModeLabel("NOW GATE")
            settingsOpen = false
            refreshModalityDock()
            settingsScrim.animate().alpha(0f).setDuration(170L).withEndAction {
                settingsScrim.visibility = View.GONE
            }.start()
            settingsSheet.animate()
                .translationY(settingsSheet.height.toFloat())
                .alpha(0.96f)
                .setDuration(240L)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    settingsSheet.visibility = View.INVISIBLE
                    settingsSheet.translationY = 0f
                    settingsSheet.alpha = 1f
                }.start()
            refreshGraph()
        }

        productModeButton.setOnClickListener {
            if (dreamPlaybackRunning) stopDreamPlayback(clear = false) else startDreamPlayback()
        }
        productAudioButton.setOnClickListener { toggleContinuousOrchestra() }
        captureButton.setOnClickListener {
            if (captureRecorder.isRecording()) {
                stopUnifiedCapture()
            } else {
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                capturePermissionLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }
        chatControlButton.setOnClickListener { setChatVisible(!chatVisible) }
        openSettingsAction = { screen -> openSettingsOverlay(screen) }
        closeSettingsAction = { closeSettingsOverlay() }
        settingsButton.setOnClickListener {
            if (settingsOpen) closeSettingsOverlay() else openSettingsOverlay()
        }
        settingsMenuButton.setOnClickListener {
            settingsMenuButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            hideKeyboardNow()
            if (settingsOpen) closeSettingsOverlay() else showSettingsQuickMenu(settingsMenuButton)
        }
        settingsScrim.setOnClickListener { closeSettingsOverlay() }
        settingsCloseButton.setOnClickListener { closeSettingsOverlay() }

        setContentView(rootFrame)
        syncCognitiveModeUi()
        closeSettingsOverlay()
        applyOutputRouting(syncSensors = false)
        applyInputRouting()
    }

    private fun buildModalityDock(rootFrame: FrameLayout) {
        modalityDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = roundedBackground(Color.argb(106, 0, 0, 0), 18f, Color.argb(34, 235, 240, 236), 1)
            elevation = dp(10).toFloat()
        }

        fun dockButton(label: String, description: String, action: (TextView) -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(9), dp(4), dp(9))
            background = null
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action(this)
            }
        }

        inputMenuButton = dockButton("IN⌄", "Eingaben öffnen") { showInputQuickMenu(it) }
        masterMenuButton = dockButton("M", "Master Patch öffnen") { showMasterMenu(it) }
        featureMenuButton = dockButton("✦", "Features öffnen") { showFeatureMenu(it) }
        settingsMenuButton = dockButton("⚙", "Einstellungen öffnen") {
            hideKeyboardNow()
            if (settingsOpen) closeSettingsAction?.invoke() else showSettingsQuickMenu(it)
        }

        modalityDock.addView(inputMenuButton, weightedButtonParams())
        modalityDock.addView(masterMenuButton, weightedButtonParams())
        modalityDock.addView(featureMenuButton, weightedButtonParams())
        modalityDock.addView(settingsMenuButton, weightedButtonParams())

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dp(10)
            marginStart = dp(10)
            marginEnd = dp(10)
        }
        rootFrame.addView(modalityDock, params)
        refreshModalityDock()
    }

    private fun toggleInputChannel(channel: InputChannel) {
        when (channel) {
            InputChannel.TEXT -> textInputEnabled = !textInputEnabled
            InputChannel.AUDIO -> audioInputEnabled = !audioInputEnabled
            InputChannel.CAMERA -> cameraInputEnabled = !cameraInputEnabled
        }
        persistModalityRouting()
        applyInputRouting()
        syncExpressionRenderers()
        refreshModalityDock()
        if (settingsOpen) renderSettingsScreen()
    }

    private fun dismissTopMenus(except: PopupWindow? = null) {
        listOf(inputPopupWindow, masterPopupWindow, featurePopupWindow, settingsQuickPopupWindow, mediaPopupWindow)
            .filterNotNull()
            .filter { it !== except }
            .forEach { runCatching { it.dismiss() } }
    }

    private fun styleGlassIcon(button: TextView, enabled: Boolean) {
        button.setTextColor(if (enabled) Color.rgb(232, 238, 234) else Color.rgb(92, 101, 95))
        button.background = roundedBackground(
            if (enabled) Color.argb(216, 7, 10, 11) else Color.argb(174, 4, 6, 7),
            13f,
            if (enabled) Color.argb(84, 230, 236, 232) else Color.argb(38, 230, 236, 232),
            1,
        )
    }

    private fun glassIconButton(symbol: String, enabled: Boolean, description: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            textSize = 21f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            styleGlassIcon(this, enabled)
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action()
            }
        }

    private fun compactGlassPopup(panel: LinearLayout, width: Int): PopupWindow = PopupWindow(
        panel,
        width,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        true,
    ).apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isOutsideTouchable = true
        elevation = dp(18).toFloat()
    }

    private fun showInputQuickMenu(anchor: View) {
        dismissTopMenus()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = roundedBackground(Color.argb(240, 2, 4, 5), 18f, Color.argb(64, 230, 236, 232), 1)
        }
        val buttons = mutableListOf<TextView>()
        fun refresh() {
            if (buttons.size == 3) {
                styleGlassIcon(buttons[0], textInputEnabled)
                styleGlassIcon(buttons[1], audioInputEnabled)
                styleGlassIcon(buttons[2], cameraInputEnabled)
            }
            refreshModalityDock()
        }
        fun add(symbol: String, enabled: Boolean, description: String, channel: InputChannel) {
            val b = glassIconButton(symbol, enabled, description) {
                toggleInputChannel(channel)
                refresh()
            }
            buttons += b
            panel.addView(b, LinearLayout.LayoutParams(dp(50), dp(50)).apply { topMargin = dp(3); bottomMargin = dp(3) })
        }
        add("✎", textInputEnabled, "Text Input", InputChannel.TEXT)
        add("🎙", audioInputEnabled, "Audio Input", InputChannel.AUDIO)
        add("◉", cameraInputEnabled, "Kamera Input", InputChannel.CAMERA)
        val popup = compactGlassPopup(panel, dp(58))
        inputPopupWindow = popup
        popup.setOnDismissListener { if (inputPopupWindow === popup) inputPopupWindow = null }
        popup.showAsDropDown(anchor, (anchor.width - dp(58)) / 2, dp(5))
    }

    private fun showMasterMenu(anchor: View) {
        dismissTopMenus()
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = roundedBackground(Color.argb(230, 3, 5, 6), 18f, Color.argb(60, 230, 236, 232), 1)
        }
        val grid = MasterPatchGridView(this).apply {
            setRoutes(masterRoutes)
            setOnRouteChanged { input, output, enabled ->
                masterRoutes[input * 3 + output] = enabled
                syncCoreMasksFromMasterRoutes()
                persistModalityRouting()
                syncExpressionRenderers()
                if (musicOutputEnabled && unifiedAudio.isRunning()) pushContinuousOrchestraState()
            }
        }
        host.addView(grid, LinearLayout.LayoutParams(dp(250), dp(250)))
        val popup = PopupWindow(host, dp(264), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(20).toFloat()
        }
        masterPopupWindow = popup
        popup.setOnDismissListener { if (masterPopupWindow === popup) masterPopupWindow = null }
        popup.showAsDropDown(anchor, -dp(104), dp(6))
    }

    private fun styleFeatureGlassRow(row: TextView, symbol: String, label: String, enabled: Boolean) {
        row.text = "$symbol   $label   ${if (enabled) "●" else "○"}"
        row.setTextColor(if (enabled) Color.rgb(230, 236, 232) else Color.rgb(116, 128, 120))
        row.background = roundedBackground(Color.argb(208, 5, 7, 8), 12f, Color.argb(45, 230, 236, 232), 1)
    }

    private fun featureGlassRow(symbol: String, label: String, enabled: Boolean, action: () -> Unit): TextView =
        TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(10), dp(11), dp(10))
            styleFeatureGlassRow(this, symbol, label, enabled)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action()
            }
        }

    private fun showFeatureMenu(anchor: View) {
        dismissTopMenus()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = roundedBackground(Color.argb(240, 2, 4, 5), 18f, Color.argb(64, 230, 236, 232), 1)
        }
        data class FeatureQuick(
            val symbol: String,
            val label: String,
            val state: () -> Boolean,
            val action: () -> Unit,
        )
        val items = listOf(
            FeatureQuick("⋮", "ID MATRIX", { idFlowOutputEnabled }) {
                idFlowOutputEnabled = !idFlowOutputEnabled; persistModalityRouting(); applyLayerComposition()
            },
            FeatureQuick("≋", "ID WATER", { idWaterEnabled }) {
                idWaterEnabled = !idWaterEnabled; persistModalityRouting(); applyLayerComposition()
            },
            FeatureQuick("🚪", "NOW GATE", { nowGateEffectEnabled }) {
                nowGateEffectEnabled = !nowGateEffectEnabled; persistModalityRouting(); applyLayerComposition()
            },
            FeatureQuick("↔", "RELATION", { relationTraceVisible }) {
                relationTraceVisible = !relationTraceVisible; persistModalityRouting(); renderChatHistory()
            },
            FeatureQuick("Θ", "TIERS", { dialecticTierVisible }) {
                dialecticTierVisible = !dialecticTierVisible; persistModalityRouting(); syncExpressionRenderers()
            },
            FeatureQuick("⌁", "DEPTH", { macroDepthVisible }) {
                macroDepthVisible = !macroDepthVisible; persistModalityRouting(); syncExpressionRenderers()
            },
        )
        val rows = mutableListOf<TextView>()
        fun refreshRows() {
            rows.forEachIndexed { index, row ->
                val item = items[index]
                styleFeatureGlassRow(row, item.symbol, item.label, item.state())
            }
            refreshModalityDock()
        }
        items.forEach { item ->
            val row = featureGlassRow(item.symbol, item.label, item.state()) {
                item.action()
                refreshRows()
            }
            rows += row
            panel.addView(row, LinearLayout.LayoutParams(dp(212), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3); bottomMargin = dp(3)
            })
        }
        val mediaRow = featureGlassRow("▣", "MEDIA", mediaBackgroundEnabled || mediaBackgroundUri != null || stageBackgroundColor != Color.BLACK) {
            featurePopupWindow?.dismiss()
            showMediaQuickMenu(featureMenuButton)
        }
        panel.addView(mediaRow, LinearLayout.LayoutParams(dp(212), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3); bottomMargin = dp(3)
        })
        val popup = PopupWindow(panel, dp(226), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(18).toFloat()
        }
        featurePopupWindow = popup
        popup.setOnDismissListener { if (featurePopupWindow === popup) featurePopupWindow = null }
        popup.showAsDropDown(anchor, -dp(128), dp(6))
    }

    private fun clearBackgroundMedia() {
        mediaBackgroundEnabled = false
        mediaBackgroundUri = null
        mediaBackgroundMime = null
        if (::mediaBackdropView.isInitialized) {
            mediaBackdropView.clearMedia()
            mediaBackdropView.setMediaEnabled(false)
        }
        persistModalityRouting()
        applyLayerComposition()
        refreshModalityDock()
        if (settingsOpen) renderSettingsScreen()
    }

    private fun setStageBackgroundColor(color: Int) {
        stageBackgroundColor = color
        if (::stageRootFrame.isInitialized) stageRootFrame.setBackgroundColor(color)
        persistModalityRouting()
    }

    private fun showMediaQuickMenu(anchor: View) {
        dismissTopMenus()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = roundedBackground(Color.argb(244, 2, 4, 5), 18f, Color.argb(72, 230, 236, 232), 1)
        }
        fun addRow(symbol: String, label: String, enabled: Boolean = false, action: () -> Unit) {
            val row = featureGlassRow(symbol, label, enabled) { action() }
            panel.addView(row, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3); bottomMargin = dp(3)
            })
        }
        addRow("▣", "BILD / VIDEO", mediaBackgroundUri != null) {
            mediaPopupWindow?.dismiss()
            launchBackgroundMediaPicker()
        }
        addRow("∅", "KEIN MEDIUM", mediaBackgroundUri == null) {
            clearBackgroundMedia()
            mediaPopupWindow?.dismiss()
        }

        val palette = listOf(
            "SCHWARZ" to Color.rgb(0, 0, 0),
            "GRAPHIT" to Color.rgb(17, 20, 24),
            "WEISS" to Color.rgb(244, 244, 240),
            "GRÜN" to Color.rgb(6, 18, 10),
            "BLAU" to Color.rgb(6, 14, 24),
            "WARM" to Color.rgb(24, 18, 12),
        )
        palette.forEach { (name, color) ->
            addRow("●", name, stageBackgroundColor == color) {
                setStageBackgroundColor(color)
            }
        }
        val popup = PopupWindow(panel, dp(234), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(20).toFloat()
        }
        mediaPopupWindow = popup
        popup.setOnDismissListener {
            if (mediaPopupWindow === popup) mediaPopupWindow = null
            refreshModalityDock()
        }
        popup.showAsDropDown(anchor, -dp(136), dp(6))
    }

    private fun setCoreOutputMode(mode: CoreOutputMode) {
        if (coreOutputMode == mode) return
        coreOutputMode = mode
        persistModalityRouting()
        applyOperationalOutputTuning()
        syncExpressionRenderers()
        if (musicOutputEnabled && unifiedAudio.isRunning()) pushContinuousOrchestraState()
        if (::chatStatus.isInitialized) refreshChatStatus()
        refreshModalityDock()
        if (settingsOpen) renderSettingsScreen()
    }

    private fun showSettingsQuickMenu(anchor: View) {
        hideKeyboardNow()
        dismissTopMenus()
        data class QuickSetting(
            val symbol: String,
            val label: String,
            val screen: SettingsScreen,
            val mode: CoreOutputMode? = null,
        )
        val items = listOf(
            QuickSetting("✎", "WRITE", SettingsScreen.TEXT, CoreOutputMode.WRITE),
            QuickSetting("🎞", "VIDEO", SettingsScreen.VIDEO, CoreOutputMode.VIDEO),
            QuickSetting("♫", "MUSIC", SettingsScreen.MUSIC, CoreOutputMode.MUSIC),
            QuickSetting("⌘", "CODE", SettingsScreen.MODULE_CODING, CoreOutputMode.CODING),
            QuickSetting("∑", "MATH", SettingsScreen.MODULE_MATH, CoreOutputMode.MATH),
            QuickSetting("◫", "OUTPUT", SettingsScreen.ROOT),
            QuickSetting("◇", "MIND", SettingsScreen.MIND),
            QuickSetting("⚙", "SYSTEM", SettingsScreen.SYSTEM),
        )
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = roundedBackground(Color.argb(244, 2, 4, 5), 18f, Color.argb(72, 230, 236, 232), 1)
        }
        val rows = mutableListOf<TextView>()
        var expandedIndex = -1

        fun styleRow(index: Int) {
            if (index !in rows.indices) return
            val item = items[index]
            val active = item.mode != null && item.mode == coreOutputMode
            val row = rows[index]
            row.setTextColor(if (active) Color.rgb(238, 245, 240) else Color.rgb(185, 196, 189))
            row.background = roundedBackground(
                if (active) Color.argb(235, 12, 18, 15) else Color.argb(226, 5, 7, 8),
                12f,
                if (active) Color.argb(118, 235, 242, 238) else Color.argb(48, 230, 236, 232),
                1,
            )
        }

        fun collapse(index: Int) {
            if (index !in rows.indices) return
            val row = rows[index]
            row.animate().cancel()
            val animator = android.animation.ValueAnimator.ofInt(row.layoutParams.width, dp(48))
            animator.duration = 130L
            animator.addUpdateListener { a -> row.layoutParams = row.layoutParams.apply { width = a.animatedValue as Int } }
            animator.start()
            row.text = items[index].symbol
            styleRow(index)
        }

        items.forEachIndexed { index, item ->
            val row = TextView(this).apply {
                text = item.symbol
                textSize = 14f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setPadding(dp(12), dp(9), dp(12), dp(9))
                isClickable = true
                isFocusable = true
                contentDescription = item.label
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (expandedIndex == index) {
                        item.mode?.let { mode -> setCoreOutputMode(mode) }
                        settingsQuickPopupWindow?.dismiss()
                        openSettingsAction?.invoke(item.screen)
                    } else {
                        if (expandedIndex >= 0) collapse(expandedIndex)
                        expandedIndex = index
                        val marker = if (item.mode != null && item.mode == coreOutputMode) "●" else ""
                        text = buildString {
                            append(item.label)
                            if (marker.isNotBlank()) append("  ").append(marker)
                            append("   ").append(item.symbol)
                        }
                        val target = (paint.measureText(text.toString()) + dp(34)).roundToInt().coerceIn(dp(96), dp(220))
                        val animator = android.animation.ValueAnimator.ofInt(layoutParams.width, target)
                        animator.duration = 170L
                        animator.interpolator = android.view.animation.DecelerateInterpolator()
                        animator.addUpdateListener { a -> layoutParams = layoutParams.apply { width = a.animatedValue as Int } }
                        animator.start()
                        styleRow(index)
                    }
                }
            }
            rows += row
            panel.addView(row, LinearLayout.LayoutParams(dp(48), dp(46)).apply {
                gravity = Gravity.END
                topMargin = dp(3)
                bottomMargin = dp(3)
            })
            styleRow(index)
        }
        val popup = PopupWindow(panel, dp(238), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(20).toFloat()
        }
        settingsQuickPopupWindow = popup
        popup.setOnDismissListener {
            if (settingsQuickPopupWindow === popup) settingsQuickPopupWindow = null
            refreshModalityDock()
        }
        popup.showAsDropDown(anchor, anchor.width - dp(238), dp(6))
        refreshModalityDock()
    }

    private fun hideKeyboardNow() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val target = currentFocus ?: if (::chatInput.isInitialized) chatInput else null
        target?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
        target?.clearFocus()
    }

    private fun masterMask(target: MasterOutputTarget): CoreStimulusMask {
        val output = target.ordinal
        return CoreStimulusMask(
            text = masterRoutes[0 * 3 + output],
            audio = masterRoutes[1 * 3 + output],
            camera = masterRoutes[2 * 3 + output],
        )
    }

    private fun masterRouteMatrix(): KiraRouteMatrix = KiraRouteMatrix(masterRoutes.copyOf())

    private fun liveInputAvailability(): KiraInputAvailability = KiraInputAvailability(
        text = textInputEnabled,
        audio = audioInputEnabled,
        camera = cameraInputEnabled,
    )

    private fun outputLane(target: MasterOutputTarget): KiraOutputLane = when (target) {
        MasterOutputTarget.TEXT -> KiraOutputLane.TEXT
        MasterOutputTarget.VIDEO -> KiraOutputLane.VIDEO
        MasterOutputTarget.AUDIO -> KiraOutputLane.AUDIO
    }

    /**
     * v0.9.6: one explicit 3x3 runtime projection. MASTER is no longer just UI state:
     * every target receives separate text/audio/camera drives from the same live memory frontier.
     */
    private fun crossModalProjectionForTarget(
        target: MasterOutputTarget,
        source: WorkingSetSnapshot? = null,
        liveText: String = lastDreamText,
    ): KiraCrossModalProjection {
        val snapshot = source ?: runCatching { memory.unifiedWorkingSnapshot() }
            .getOrElse { WorkingSetSnapshot(emptyList(), emptyList()) }
        val samples = snapshot.nodes.map { node ->
            KiraCrossModalSample(
                id = node.id.toLong() and 0xffffffffL,
                family = KiraModalityClassifier.familyOf(node.modality),
                activation = node.activation,
                expectation = node.expectation,
                relevance = node.structuralRelevance,
                exchangeBias = node.exchangeBias,
                depth = node.abstractionLevel,
            )
        }
        return KiraCrossModalRuntime.project(
            output = outputLane(target),
            routes = masterRouteMatrix(),
            availability = liveInputAvailability(),
            samples = samples,
            liveText = liveText,
        )
    }

    private fun syncCoreMasksFromMasterRoutes() {
        val text = masterMask(MasterOutputTarget.TEXT)
        coreStimulusMasks[CoreOutputMode.WRITE] = text
        coreStimulusMasks[CoreOutputMode.CODING] = text
        coreStimulusMasks[CoreOutputMode.MATH] = text
        coreStimulusMasks[CoreOutputMode.VIDEO] = masterMask(MasterOutputTarget.VIDEO)
        coreStimulusMasks[CoreOutputMode.MUSIC] = masterMask(MasterOutputTarget.AUDIO)
    }

    private fun outputMaskForCoreMode(mode: CoreOutputMode = coreOutputMode): CoreStimulusMask = when (mode) {
        CoreOutputMode.WRITE, CoreOutputMode.CODING, CoreOutputMode.MATH -> masterMask(MasterOutputTarget.TEXT)
        CoreOutputMode.VIDEO -> masterMask(MasterOutputTarget.VIDEO)
        CoreOutputMode.MUSIC -> masterMask(MasterOutputTarget.AUDIO)
    }

    private fun combinedActiveOutputMask(): CoreStimulusMask {
        val masks = mutableListOf<CoreStimulusMask>()
        if (textOutputEnabled) masks += masterMask(MasterOutputTarget.TEXT)
        if (OutputCapability.FILM in activeOutputCapabilities || videoOutputEnabled || coreOutputMode == CoreOutputMode.VIDEO) masks += masterMask(MasterOutputTarget.VIDEO)
        if (musicOutputEnabled || OutputCapability.ORCHESTRA in activeOutputCapabilities) masks += masterMask(MasterOutputTarget.AUDIO)
        if (masks.isEmpty()) masks += outputMaskForCoreMode()
        return CoreStimulusMask(
            text = masks.any { it.text },
            audio = masks.any { it.audio },
            camera = masks.any { it.camera },
        )
    }

    private fun routeWorkingSnapshot(base: WorkingSetSnapshot, target: MasterOutputTarget): WorkingSetSnapshot {
        val mask = masterMask(target)
        val sensor = filterSensorSnapshot(base, mask)
        val mind = if (mask.text) mindRepository.workingSnapshot() else WorkingSetSnapshot(emptyList(), emptyList())
        return mergeWorkingSnapshots(sensor, mind)
    }

    /** Raw MASTER-routed evidence before the v0.9.4 decision projection. */
    private fun rawWorkingSnapshotForTarget(target: MasterOutputTarget): WorkingSetSnapshot =
        routeWorkingSnapshot(memory.unifiedWorkingSnapshot(), target)

    /**
     * Every product output receives the same Thesis/Antithesis stability gate.
     * The gate is read-only: it only projects the active working state and never writes memory.
     */
    private fun workingSnapshotForTarget(target: MasterOutputTarget): WorkingSetSnapshot {
        val raw = rawWorkingSnapshotForTarget(target)
        return decisionProjectedWorking(raw, decisionState(raw))
    }

    private fun decisionStateForTarget(target: MasterOutputTarget): KiraDecisionState =
        decisionState(rawWorkingSnapshotForTarget(target))

    private fun expressionIdsForTarget(target: MasterOutputTarget): List<Long> {
        val mask = masterMask(target)
        val ids = linkedSetOf<Long>()
        if (mask.text && ::mindRepository.isInitialized) ids += mindRepository.activeIdSnapshot(56)
        val working = runCatching { workingSnapshotForTarget(target) }.getOrNull()
        working?.nodes.orEmpty().take(28).forEach { node ->
            ids += EquinoxId64.stableId("working_node", "${node.modality}:${node.id}:${node.abstractionLevel}")
        }
        working?.exchanges.orEmpty().take(24).forEach { edge ->
            ids += EquinoxId64.stableId("working_relation", "${edge.fromId}:${edge.toId}")
        }
        return ids.filter { it != 0L }.distinct().take(64)
    }

    private fun launchBackgroundMediaPicker() {
        backgroundMediaPicker.launch(arrayOf("image/*", "video/*"))
    }

    private fun toggleOutputCapability(capability: OutputCapability) {
        if (!activeOutputCapabilities.add(capability)) activeOutputCapabilities.remove(capability)
        // Orchestra is a named sound capability; selecting it ensures the sound bus is live.
        if (capability == OutputCapability.ORCHESTRA && capability in activeOutputCapabilities) {
            musicOutputEnabled = true
        }
        persistModalityRouting()
        applyOperationalOutputTuning()
        applyOutputRouting(syncSensors = false)
    }

    private fun activeOperationalModules(): Set<OperationalModuleId> = buildSet {
        when (coreOutputMode) {
            CoreOutputMode.MATH -> add(OperationalModuleId.MATH)
            CoreOutputMode.CODING -> add(OperationalModuleId.CODING)
            CoreOutputMode.VIDEO -> add(OperationalModuleId.FILM)
            CoreOutputMode.MUSIC -> add(OperationalModuleId.ORCHESTRA)
            CoreOutputMode.WRITE -> Unit
        }
        if (OutputCapability.MATH in activeOutputCapabilities) add(OperationalModuleId.MATH)
        if (OutputCapability.CODING in activeOutputCapabilities) add(OperationalModuleId.CODING)
        if (videoOutputEnabled || OutputCapability.FILM in activeOutputCapabilities) add(OperationalModuleId.FILM)
        if (musicOutputEnabled || OutputCapability.ORCHESTRA in activeOutputCapabilities) add(OperationalModuleId.ORCHESTRA)
    }

    private fun currentModuleTuning(): KiraModuleTuning = KiraModuleTuning(
        math = MathModuleTuning(
            precision = mathPrecision,
            functionsEnabled = mathFunctionsEnabled,
        ),
        coding = CodingModuleTuning(
            language = codingLanguage,
            strict = codingStrict,
            useMemoryContext = codingMemoryContext,
        ),
        film = FilmModuleTuning(
            intensity = filmIntensity.toDouble(),
            stateInfluence = filmStateInfluence.toDouble(),
        ),
        orchestra = OrchestraModuleTuning(
            memoryBlend = orchestraMemoryBlend.toDouble(),
            maxVoices = orchestraMaxVoices,
            tempoScale = orchestraTempoScale.toDouble(),
        ),
    )

    private fun decisionState(working: WorkingSetSnapshot?): KiraDecisionState =
        KiraDecisionCore.evaluate(working?.nodes.orEmpty().map { node ->
            KiraDecisionEvidence(
                id = node.id,
                depth = node.abstractionLevel.coerceAtLeast(1),
                modality = node.modality,
                activation = node.activation,
                expectation = node.expectation,
                exchangeBias = node.exchangeBias,
                relevance = node.structuralRelevance,
                maturity = node.maturityScore,
                recurrence = node.recurrence,
                reuse = node.reuse,
                relationSupport = node.relationSupport,
                contextEntropy = node.contextEntropy,
                persistence = node.persistence,
                generality = node.generality,
                retrieved = node.retrieved,
            )
        })

    private fun dialecticState(working: WorkingSetSnapshot?): DialecticDecisionState =
        decisionState(working).toDialectic()

    /**
     * Projects the currently selected branch back into the same WorkingSet contract used by
     * TEXT, DREAM/VIDEO and ORCHESTRA. Stable losers are attenuated, never erased; an OPEN
     * state deliberately keeps both paths alive so Kira can ask/observe instead of fabricating
     * certainty.
     */
    private fun decisionProjectedWorking(
        working: WorkingSetSnapshot,
        decision: KiraDecisionState = decisionState(working),
    ): WorkingSetSnapshot {
        if (working.nodes.isEmpty() || decision.status == KiraDecisionStatus.EMPTY) return working
        val voteById = decision.votes.associateBy { it.id }
        fun gain(id: Int): Double {
            val vote = voteById[id] ?: return 0.72
            if (decision.status != KiraDecisionStatus.STABLE || decision.winner == null) {
                return (0.72 + 0.28 * vote.strength).coerceIn(0.72, 1.0)
            }
            return if (vote.branch == decision.winner) {
                (0.88 + 0.12 * vote.strength).coerceIn(0.88, 1.0)
            } else {
                (0.18 + 0.42 * decision.openness + 0.12 * vote.strength).coerceIn(0.18, 0.66)
            }
        }
        val nodes = working.nodes.map { node ->
            val g = gain(node.id)
            node.copy(
                activation = (node.activation * g).coerceIn(0.0, 1.0),
                expectation = (node.expectation * (0.58 + 0.42 * g)).coerceIn(0.0, 1.0),
                structuralRelevance = (node.structuralRelevance * (0.70 + 0.30 * g)).coerceIn(0.0, 1.0),
            )
        }
        val exchanges = working.exchanges.map { edge ->
            val g = kotlin.math.sqrt(gain(edge.fromId) * gain(edge.toId))
            edge.copy(value = edge.value * g)
        }
        val predictions = working.predictions.map { prediction ->
            prediction.copy(confidence = (prediction.confidence * gain(prediction.targetId)).coerceIn(0.0, 1.0))
        }
        val cross = working.crossModalLinks.map { link ->
            val g = minOf(gain(link.jointId), gain(link.audioId), gain(link.visualId))
            link.copy(confidence = (link.confidence * g).coerceIn(0.0, 1.0))
        }
        return WorkingSetSnapshot(nodes, exchanges, predictions, cross)
    }

    private fun stateVector(working: WorkingSetSnapshot?): KiraStateVector {
        val state = UnifiedPresentationState.from(working)
        return KiraStateVector(
            activity = state.activity,
            expectation = state.expectation,
            relevance = state.relevance,
            relationEnergy = state.relationEnergy,
            signedExchange = state.signedExchange,
            prediction = state.prediction,
            novelty = state.novelty,
            crossmodal = state.crossmodal,
            tierDepth = state.tierDepth,
            retrievalShare = state.retrievalShare,
            directActivation = state.directActivation,
            density = state.density,
            memoryEnergy = state.memoryEnergy,
        )
    }

    private fun moduleContext(
        text: String,
        working: WorkingSetSnapshot? = runCatching { unifiedExpressionWorkingSnapshot() }.getOrNull(),
        ids: List<Long> = unifiedExpressionIds(),
        includeMemoryHints: Boolean = true,
    ): KiraModuleContext = KiraModuleContext(
        text = text,
        activeIds = ids,
        memoryHints = if (includeMemoryHints && text.isNotBlank()) chatEngine.recallHints(text, 5) else emptyList(),
        state = stateVector(working),
    )

    private fun buildDreamInfluence(
        working: WorkingSetSnapshot?,
        context: KiraModuleContext,
    ): DreamInfluence {
        val projection = crossModalProjectionForTarget(
            MasterOutputTarget.VIDEO,
            source = working,
            liveText = context.text,
        )
        val base = DreamInfluence.fromContext(context)
        val decision = runCatching { decisionStateForTarget(MasterOutputTarget.VIDEO) }.getOrDefault(KiraDecisionState())
        val stableDrive = if (decision.status == KiraDecisionStatus.STABLE) decision.confidence else 0.0
        val correctiveDrive = if (decision.winner == KiraDecisionBranch.ANTITHESIS) stableDrive else 0.0
        return base.copy(
            text = projection.textDrive,
            audio = projection.audioDrive,
            camera = projection.cameraDrive,
            energy = maxOf(base.energy, projection.energy * 0.92 + stableDrive * 0.05).coerceIn(0.0, 1.0),
            motion = maxOf(base.motion, projection.motion + correctiveDrive * 0.08).coerceIn(0.0, 1.0),
            novelty = maxOf(base.novelty, projection.novelty + decision.openness * 0.06).coerceIn(0.0, 1.0),
            tension = maxOf(base.tension, projection.tension + decision.openness * 0.10 + correctiveDrive * 0.05).coerceIn(0.0, 1.0),
            coherence = (base.coherence * 0.48 + projection.coherence * 0.42 + stableDrive * 0.10 - decision.openness * 0.06).coerceIn(0.0, 1.0),
            hueBias = if (projection.seed == 0L) base.hueBias else ((projection.seed ushr 11) and 0xffffL).toDouble() / 65535.0,
            activeIds = (projection.activeIds + context.activeIds).distinct().take(64),
        )
    }

    private fun applyOperationalOutputTuning(working: WorkingSetSnapshot? = null, ids: List<Long>? = null) {
        val active = activeOperationalModules()
        val tuning = currentModuleTuning()
        val resolvedWorking = working ?: runCatching { unifiedExpressionWorkingSnapshot() }.getOrNull()
        val resolvedIds = ids ?: unifiedExpressionIds()
        val filmWorking = runCatching { workingSnapshotForTarget(MasterOutputTarget.VIDEO) }.getOrNull() ?: resolvedWorking
        val filmIds = expressionIdsForTarget(MasterOutputTarget.VIDEO).ifEmpty { resolvedIds }
        val filmText = lastDreamText.takeIf { textInputEnabled && masterMask(MasterOutputTarget.VIDEO).text }.orEmpty()
        val filmContext = moduleContext(filmText, filmWorking, filmIds, includeMemoryHints = false)
        val filmProjection = crossModalProjectionForTarget(
            MasterOutputTarget.VIDEO,
            source = filmWorking,
            liveText = filmText,
        )

        val film = operationalRegistry.filmDirective(active, filmContext, tuning, filmProjection)
        if (::mediaBackdropView.isInitialized) {
            mediaBackdropView.setFilmGeneration(film, animate = film != null)
        }
        if (::proceduralFilmView.isInitialized) {
            val mediaOwnsFilm = ::mediaBackdropView.isInitialized && mediaBackgroundEnabled && mediaBackdropView.hasMedia()
            proceduralFilmView.setState(film?.takeUnless { mediaOwnsFilm }, filmIds, animate = film != null && !mediaOwnsFilm)
        }

        val orchestra = operationalRegistry.orchestraDirective(active, tuning)
        val audioProjection = crossModalProjectionForTarget(
            MasterOutputTarget.AUDIO,
            liveText = lastDreamText.takeIf { textInputEnabled && masterMask(MasterOutputTarget.AUDIO).text }.orEmpty(),
        )
        unifiedAudio.setCrossModalProjection(audioProjection)
        if (orchestra != null) {
            unifiedAudio.setMemoryBlend(orchestra.memoryBlend)
            unifiedAudio.setGenerationTuning(orchestra.maxVoices, orchestra.tempoScale)
        } else {
            unifiedAudio.setMemoryBlend(0.35f)
            unifiedAudio.setGenerationTuning(9, 1.0f)
        }
    }

    /**
     * Product output ownership lives in Settings. IN only admits stimuli; MASTER only
     * routes admitted stimuli. These helpers decide which renderers are actually allowed
     * to become visible/audible.
     */
    private fun setSoloOutput(surface: PrimaryOutputSurface) {
        textOutputEnabled = surface == PrimaryOutputSurface.TEXT
        videoOutputEnabled = surface == PrimaryOutputSurface.VIDEO
        musicOutputEnabled = surface == PrimaryOutputSurface.AUDIO
        if (surface == PrimaryOutputSurface.FILM) {
            activeOutputCapabilities.add(OutputCapability.FILM)
        } else {
            activeOutputCapabilities.remove(OutputCapability.FILM)
        }
        persistModalityRouting()
        applyOperationalOutputTuning()
        applyOutputRouting(syncSensors = false)
        if (settingsOpen) renderSettingsScreen()
    }

    private fun toggleOutputChannel(channel: OutputChannel) {
        when (channel) {
            OutputChannel.TEXT -> textOutputEnabled = !textOutputEnabled
            OutputChannel.VIDEO -> videoOutputEnabled = !videoOutputEnabled
            OutputChannel.MUSIC -> musicOutputEnabled = !musicOutputEnabled
        }
        persistModalityRouting()
        applyOutputRouting(syncSensors = true)
        if (channel == OutputChannel.TEXT && ::chatMessages.isInitialized) renderChatHistory()
        if (settingsOpen) renderSettingsScreen()
    }

    private fun persistModalityRouting() {
        if (!::modalityPrefs.isInitialized) return
        val editor = modalityPrefs.edit()
            .putString("core_output_mode", coreOutputMode.name)
            .putBoolean("macro_depth_visible", macroDepthVisible)
            .putBoolean("dialectic_tier_visible", dialecticTierVisible)
            .putBoolean("relation_trace_visible", relationTraceVisible)
            .putBoolean("text_input", textInputEnabled)
            .putBoolean("audio_input", audioInputEnabled)
            .putBoolean("camera_input", cameraInputEnabled)
            .putBoolean("text_output", textOutputEnabled)
            .putBoolean("video_output", videoOutputEnabled)
            .putBoolean("now_gate_effect", nowGateEffectEnabled)
            .putBoolean("music_output", musicOutputEnabled)
            .putBoolean("id_flow_output", idFlowOutputEnabled)
            .putBoolean("media_background_enabled", mediaBackgroundEnabled)
            .putString("media_background_uri", mediaBackgroundUri)
            .putString("media_background_mime", mediaBackgroundMime)
            .putInt("stage_background_color", stageBackgroundColor)
            .putBoolean("id_water_enabled", idWaterEnabled)
            .putFloat("id_water_strength", idWaterStrength)
            .putFloat("id_flow_speed", idFlowSpeed)
            .putBoolean("id_flow_pulse", idFlowPulse)
            .putFloat("now_gate_speed", nowGateSpeed)
            .putString("dream_control_mode", dreamControlMode.name)
            .putFloat("dream_influence_strength", dreamInfluenceStrength)
            .putFloat("dream_world_speed", dreamWorldSpeed)
            .putBoolean("dream_audio_linked", dreamAudioLinked)
            .putBoolean("dream_visual_enabled", dreamVisualEnabled)
            .putInt("module_math_precision", mathPrecision)
            .putBoolean("module_math_functions", mathFunctionsEnabled)
            .putString("module_coding_language", codingLanguage.name)
            .putBoolean("module_coding_strict", codingStrict)
            .putBoolean("module_coding_memory", codingMemoryContext)
            .putFloat("module_film_intensity", filmIntensity)
            .putFloat("module_film_state", filmStateInfluence)
            .putFloat("module_orchestra_memory", orchestraMemoryBlend)
            .putInt("module_orchestra_voices", orchestraMaxVoices)
            .putFloat("module_orchestra_tempo", orchestraTempoScale)
        syncCoreMasksFromMasterRoutes()
        CoreOutputMode.entries.forEach { mode ->
            val mask = coreStimulusMasks[mode] ?: CoreStimulusMask()
            editor.putBoolean("core_${mode.name.lowercase()}_text", mask.text)
            editor.putBoolean("core_${mode.name.lowercase()}_audio", mask.audio)
            editor.putBoolean("core_${mode.name.lowercase()}_camera", mask.camera)
        }
        for (input in 0..2) {
            for (output in 0..2) {
                editor.putBoolean("master_route_${input}_${output}", masterRoutes[input * 3 + output])
            }
        }
        OutputCapability.entries.forEach { capability ->
            editor.putBoolean("cap_${capability.name.lowercase()}", capability in activeOutputCapabilities)
        }
        editor.apply()
    }

    private fun refreshDreamKnowledgeBridge() {
        if (!::dreamRuntime.isInitialized || !::mindRepository.isInitialized) return
        val atoms = ArrayList<DreamKnowledgeAtom>()
        val headers = runCatching { BaseKnowledgeRepository(this).store.readMacroHeaders() }.getOrDefault(emptyList())
        val maxWeight = headers.maxOfOrNull { it.weight.coerceAtLeast(1L) }?.toDouble()?.coerceAtLeast(1.0) ?: 1.0
        headers.asSequence().sortedByDescending { it.weight }.take(384).forEach { h ->
            val hash = DreamRuntime.hash64(h.id.toLong() xor DreamRuntime.hash64(h.signature.hashCode().toLong()))
            val hue = ((hash ushr 11) and 0xffffL).toDouble() / 65535.0
            val weightNorm = (kotlin.math.ln(h.weight.coerceAtLeast(1L).toDouble() + 1.0) / kotlin.math.ln(maxWeight + 1.0)).coerceIn(0.0, 1.0)
            val freqNorm = (kotlin.math.ln(kotlin.math.abs(h.hz).coerceAtLeast(1.0)) / kotlin.math.ln(20000.0)).coerceIn(0.0, 1.0)
            atoms += DreamKnowledgeAtom(
                id = h.id.toLong(),
                shapeBias = (h.level / 12.0).coerceIn(0.0, 1.0),
                motionBias = freqNorm,
                colorBias = hue,
                relationBias = weightNorm,
                stabilityBias = (0.35 + weightNorm * 0.50 + (h.level / 20.0)).coerceIn(0.0, 1.0),
            )
        }
        mindRepository.forgePack?.facts.orEmpty().asSequence().sortedByDescending { it.quality }.take(256).forEach { f ->
            val id = DreamRuntime.hash64(f.subject xor f.predicate xor f.objectId)
            val hue = ((DreamRuntime.hash64(id) ushr 9) and 0xffffL).toDouble() / 65535.0
            atoms += DreamKnowledgeAtom(
                id = id,
                shapeBias = f.score.coerceIn(0.0, 1.0),
                motionBias = (0.25 + f.confidence * 0.55).coerceIn(0.0, 1.0),
                colorBias = hue,
                relationBias = f.quality,
                stabilityBias = f.confidence.coerceIn(0.0, 1.0),
            )
        }
        dreamRuntime.setKnowledge(atoms)
    }

    private fun persistDreamWorldState() {
        if (!::modalityPrefs.isInitialized || !::dreamRuntime.isInitialized) return
        runCatching {
            modalityPrefs.edit().putString("dream_world_state", DreamWorldCodec.encode(dreamRuntime.snapshot())).apply()
        }
    }

    private fun refreshModalityDock() {
        if (!::modalityDock.isInitialized) return
        fun render(button: TextView, enabled: Boolean) {
            button.setTextColor(if (enabled) Color.rgb(226, 234, 228) else Color.rgb(100, 112, 104))
            button.alpha = if (enabled) 1f else 0.62f
            button.background = if (enabled) {
                roundedBackground(Color.argb(196, 5, 8, 9), 14f, Color.argb(64, 230, 236, 232), 1)
            } else null
        }
        val anyInput = textInputEnabled || audioInputEnabled || cameraInputEnabled
        val anyFeature = idFlowOutputEnabled || idWaterEnabled || nowGateEffectEnabled || relationTraceVisible || dialecticTierVisible || macroDepthVisible || mediaBackgroundEnabled || stageBackgroundColor != Color.BLACK
        val anyRoute = masterRoutes.any { it }
        render(inputMenuButton, anyInput)
        render(masterMenuButton, anyRoute)
        render(featureMenuButton, anyFeature)
        render(settingsMenuButton, settingsOpen || settingsQuickPopupWindow?.isShowing == true)
    }

    private fun applyInputRouting() {
        if (::chatInput.isInitialized) {
            chatInput.isEnabled = textInputEnabled
            chatInput.alpha = if (textInputEnabled) 1f else 0.34f
            chatSendButton.isEnabled = textInputEnabled && !chatSending
            chatSendButton.alpha = if (textInputEnabled) 1f else 0.30f
        }
        syncContextSensors()
        if (textInputEnabled) refocusComposer(showKeyboard = false)
    }

    private fun applyOutputRouting(syncSensors: Boolean = true) {
        if (::chatOverlay.isInitialized) {
            chatOverlay.visibility = View.VISIBLE
            chatVisible = textOutputEnabled
        }
        // Product Now Gate is a background deformation in MediaBackdropView.
        // The legacy GpuMemoryVideoView remains available only to the deep laboratory.
        if (::surfaceVideo.isInitialized && surfaceVideo.visibility == View.VISIBLE && videoOutputEnabled) {
            surfaceVideo.setWorkingState(routedVideoWorkingSnapshot())
        }
        run {
            if (musicOutputEnabled && !unifiedAudio.isRunning()) {
                unifiedAudio.start { text ->
                    runOnUiThread {
                        updateContinuousOrchestraUi(text)
                        updateEchoCancelerState()
                    }
                }
                pushContinuousOrchestraState()
                updateEchoCancelerState()
            } else if (!musicOutputEnabled && unifiedAudio.isRunning()) {
                stopContinuousOrchestra()
            }
        }
        if (::chatOverlay.isInitialized) applyChatDisplayMode()
        refreshModalityDock()
        refreshMainViewDock()
        applyLayerComposition()
        syncExpressionRenderers()
        if (syncSensors) applyInputRouting()
    }

    /**
     * Additional input means exactly the other two modalities for that output:
     * TEXT <- audio+video, VIDEO <- text+audio, MUSIC <- text+video.
     * Only physical sensor inputs need lifecycle work here; text already arrives
     * through the terminal and is routed on submit.
     */
    private fun syncContextSensors() {
        // Inputs are first-class switches. Outputs consume the shared state but never
        // secretly start sensors on their own.
        val needsAudio = audioInputEnabled
        val needsCamera = cameraInputEnabled

        if (!needsAudio && routingOwnsAudio) {
            if (running) stopAudio()
            routingOwnsAudio = false
        }
        if (!needsCamera && routingOwnsCamera) {
            if (cameraRunning) stopCameraLearning()
            routingOwnsCamera = false
        }

        val wantAudioStart = needsAudio && !running
        val wantCameraStart = needsCamera && !cameraRunning
        if (!wantAudioStart && !wantCameraStart) return

        if (wantAudioStart) routingOwnsAudio = true
        if (wantCameraStart) routingOwnsCamera = true

        val missingAudio = wantAudioStart && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        val missingCamera = wantCameraStart && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        if (missingAudio && missingCamera) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), REQ_MULTI)
            return
        }
        if (missingAudio) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (missingCamera) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        if (wantAudioStart) startAudio()
        if (wantCameraStart) startCameraLearning()
    }

    private fun currentTextContextSignals(): List<ChatContextSignal> {
        val mask = masterMask(MasterOutputTarget.TEXT)
        if ((!audioInputEnabled || !mask.audio) && (!cameraInputEnabled || !mask.camera)) return emptyList()
        return memory.unifiedWorkingSnapshot().nodes.asSequence()
            .filter { it.activation > 0.08 }
            .filter {
                when (KiraModalityClassifier.familyOf(it.modality)) {
                    KiraModalityFamily.AUDIO -> audioInputEnabled && mask.audio
                    KiraModalityFamily.VISION -> cameraInputEnabled && mask.camera
                    KiraModalityFamily.CROSSMODAL ->
                        audioInputEnabled && cameraInputEnabled && mask.audio && mask.camera
                    else -> false
                }
            }
            .sortedByDescending { it.activation }
            .take(32)
            .map { node ->
                ChatContextSignal(
                    sourceModality = node.modality,
                    sourceId = node.id.toLong() and 0xffffffffL,
                    activation = node.activation,
                )
            }
            .toList()
    }

    private fun maybeEmitCrossModalTextPulse(reason: String) {
        if (!textOutputEnabled || !::chatEngine.isInitialized) return
        val mask = masterMask(MasterOutputTarget.TEXT)
        if ((!mask.audio || !audioInputEnabled) && (!mask.camera || !cameraInputEnabled)) return
        val projection = crossModalProjectionForTarget(MasterOutputTarget.TEXT, liveText = "")
        val strongest = maxOf(projection.audioDrive, projection.cameraDrive)
        if (strongest < 0.72) return
        val now = System.currentTimeMillis()
        if (now - lastCrossModalTextPulseAt < 8_000L) return
        val sensorSeed = projection.activeIds.take(10).fold(projection.seed) { acc, id -> acc xor id }
        if (sensorSeed == lastCrossModalTextSeed && now - lastCrossModalTextPulseAt < 24_000L) return

        val label = when {
            projection.audioDrive >= 0.72 && projection.cameraDrive >= 0.72 -> "Audio- und Kamera-Reiz"
            projection.cameraDrive >= projection.audioDrive -> "Kamera-Reiz"
            else -> "Audio-Reiz"
        }
        chatEngine.emitAutonomously(
            "Ich registriere gerade einen neuen starken $label.",
            reason = "crossmodal_$reason",
            kind = ChatReplyKind.NATIVE,
        ) ?: return
        lastCrossModalTextPulseAt = now
        lastCrossModalTextSeed = sensorSeed
        if (::chatMessages.isInitialized) renderChatHistory()
    }

    private fun mergeWorkingSnapshots(primary: WorkingSetSnapshot, secondary: WorkingSetSnapshot): WorkingSetSnapshot {
        if (secondary.nodes.isEmpty() && secondary.exchanges.isEmpty()) return primary
        if (primary.nodes.isEmpty() && primary.exchanges.isEmpty()) return secondary
        val nodes = linkedMapOf<Int, ActiveMacroNode>()
        (primary.nodes + secondary.nodes).forEach { node ->
            val old = nodes[node.id]
            if (old == null || node.activation > old.activation) nodes[node.id] = node
        }
        val exchanges = (primary.exchanges + secondary.exchanges)
            .distinctBy { Triple(it.fromId, it.toId, (it.value * 1000.0).roundToInt()) }
            .take(160)
        val predictions = (primary.predictions + secondary.predictions)
            .distinctBy { it.sourceId to it.targetId }
            .take(96)
        val cross = (primary.crossModalLinks + secondary.crossModalLinks)
            .distinctBy { Triple(it.jointId, it.audioId, it.visualId) }
            .take(96)
        return WorkingSetSnapshot(nodes.values.take(64), exchanges, predictions, cross)
    }

    private fun routedVideoWorkingSnapshot(): WorkingSetSnapshot = workingSnapshotForTarget(MasterOutputTarget.VIDEO)

    private fun routedMusicWorkingSnapshot(): WorkingSetSnapshot = workingSnapshotForTarget(MasterOutputTarget.AUDIO)

    private fun buildDeveloperLabHost() {
        developerLabHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(10))
        }

        captureStatus = label("Aufnahme: bereit · MP4 / H.264 + Unified Audio AAC", 12f, 4).apply {
            setTextColor(Color.rgb(214, 177, 116))
        }
        developerLabHost.addView(captureStatus)
        developerLabHost.addView(captureButton, buttonParams(5))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val intake = productButton("INTAKE")
        val hybrid = productButton("HYBRID")
        val archive = productButton("ARCHIV")
        val mind = productButton("MIND")
        val control = productButton("CONTROL")
        nav.addView(intake, weightedButtonParams())
        nav.addView(hybrid, weightedButtonParams())
        nav.addView(archive, weightedButtonParams())
        nav.addView(mind, weightedButtonParams())
        nav.addView(control, weightedButtonParams())
        developerLabHost.addView(nav, buttonParams(8))

        livePage = pageContainer()
        hybridPage = pageContainer()
        memoryPage = pageContainer()
        mindPage = pageContainer()
        labPage = pageContainer()
        developerLabHost.addView(livePage)
        developerLabHost.addView(hybridPage)
        developerLabHost.addView(memoryPage)
        developerLabHost.addView(mindPage)
        developerLabHost.addView(labPage)
        buildLivePage()
        buildHybridPage()
        buildMemoryPage()
        buildMindPage()
        buildLabPage()
        polishProductTree(developerLabHost)

        intake.setOnClickListener { showDeveloperPage(AppPage.LIVE) }
        hybrid.setOnClickListener { showDeveloperPage(AppPage.HYBRID) }
        archive.setOnClickListener { showDeveloperPage(AppPage.MEMORY) }
        mind.setOnClickListener { showDeveloperPage(AppPage.MIND) }
        control.setOnClickListener { showDeveloperPage(AppPage.LAB) }
        showDeveloperPage(AppPage.LIVE)
    }

    private fun showDeveloperPage(page: AppPage) {
        currentPage = page
        livePage.visibility = if (page == AppPage.LIVE) View.VISIBLE else View.GONE
        hybridPage.visibility = if (page == AppPage.HYBRID) View.VISIBLE else View.GONE
        memoryPage.visibility = if (page == AppPage.MEMORY) View.VISIBLE else View.GONE
        mindPage.visibility = if (page == AppPage.MIND) View.VISIBLE else View.GONE
        labPage.visibility = if (page == AppPage.LAB) View.VISIBLE else View.GONE
        if (page == AppPage.LIVE || page == AppPage.HYBRID) refreshSensorUiState()
        updateMemoryStats()
        refreshGraph()
    }

    private fun navigateSettings(screen: SettingsScreen) {
        settingsScreen = screen
        renderSettingsScreen()
        settingsOverlay.post { settingsOverlay.scrollTo(0, 0) }
    }

    private fun navigateSettingsBack() {
        settingsScreen = when (settingsScreen) {
            SettingsScreen.ROOT -> SettingsScreen.ROOT
            SettingsScreen.TEXT, SettingsScreen.VIDEO, SettingsScreen.MUSIC, SettingsScreen.FILM_OUTPUT, SettingsScreen.MODULES, SettingsScreen.SYSTEM -> SettingsScreen.ROOT
            SettingsScreen.DREAM -> SettingsScreen.VIDEO
            SettingsScreen.MODULE_MATH, SettingsScreen.MODULE_CODING, SettingsScreen.MODULE_FILM, SettingsScreen.MODULE_ORCHESTRA -> SettingsScreen.MODULES
            SettingsScreen.ARCHIVE, SettingsScreen.MIND, SettingsScreen.KURATOR -> SettingsScreen.SYSTEM
            SettingsScreen.KURATOR_MODE -> SettingsScreen.KURATOR
            SettingsScreen.LAB -> SettingsScreen.KURATOR_MODE
        }
        renderSettingsScreen()
        settingsOverlay.post { settingsOverlay.scrollTo(0, 0) }
    }

    private fun settingsPath(screen: SettingsScreen): String = when (screen) {
        SettingsScreen.ROOT -> "KIRA / EINSTELLUNGEN"
        SettingsScreen.TEXT -> "KIRA / AUSGABE / TEXT"
        SettingsScreen.VIDEO -> "KIRA / AUSGABE / VIDEO"
        SettingsScreen.MUSIC -> "KIRA / AUSGABE / AUDIO"
        SettingsScreen.FILM_OUTPUT -> "KIRA / AUSGABE / FILM"
        SettingsScreen.DREAM -> "KIRA / DREAM"
        SettingsScreen.MODULES -> "KIRA / MODULE"
        SettingsScreen.MODULE_MATH -> "KIRA / MODULE / MATHE"
        SettingsScreen.MODULE_CODING -> "KIRA / MODULE / CODING"
        SettingsScreen.MODULE_FILM -> "KIRA / MODULE / FILM"
        SettingsScreen.MODULE_ORCHESTRA -> "KIRA / MODULE / ORCHESTRA"
        SettingsScreen.SYSTEM -> "KIRA / SYSTEM"
        SettingsScreen.ARCHIVE -> "KIRA / SYSTEM / ARCHIV"
        SettingsScreen.MIND -> "KIRA / SYSTEM / MIND"
        SettingsScreen.KURATOR -> "KIRA / SYSTEM / KURATOR"
        SettingsScreen.KURATOR_MODE -> "KIRA / SYSTEM / KURATOR / KURATOR-MODUS"
        SettingsScreen.LAB -> "KIRA / SYSTEM / KURATOR / KURATOR-MODUS / LABOR"
    }

    private fun settingsNavItem(title: String, subtitle: String, onClick: () -> Unit): View {
        return TextView(this).apply {
            text = if (subtitle.isBlank()) title else "$title\n$subtitle"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(215, 228, 218))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(Color.argb(72, 255, 255, 255), 14f, Color.argb(28, 0, 255, 0), 1)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun addSettingsNav(title: String, subtitle: String, screen: SettingsScreen) {
        settingsContent.addView(settingsNavItem(title, subtitle) { navigateSettings(screen) }, buttonParams(5))
    }

    private fun masterRouteSummary(target: MasterOutputTarget): String {
        val mask = masterMask(target)
        val parts = buildList {
            if (mask.text) add("TEXT")
            if (mask.audio) add("AUDIO")
            if (mask.camera) add("KAMERA")
        }
        return if (parts.isEmpty()) "MASTER: KEINE VERBINDUNG" else "MASTER: ${parts.joinToString(" + ")}"
    }

    private fun settingsSectionLabel(text: String): TextView = label(text, 11f, 10).apply {
        typeface = Typeface.MONOSPACE
        setTextColor(Color.rgb(78, 145, 88))
        gravity = Gravity.START
    }

    private fun renderSettingsScreen() {
        if (!::settingsContent.isInitialized) return
        settingsContent.removeAllViews()
        settingsBreadcrumb.text = settingsPath(settingsScreen)
        settingsBackButton.visibility = if (settingsScreen == SettingsScreen.ROOT) View.INVISIBLE else View.VISIBLE

        when (settingsScreen) {
            SettingsScreen.ROOT -> {
                settingsContent.addView(settingsSectionLabel("OUTPUT"))
                addSettingsNav("TEXT · ${if (textOutputEnabled) "AN" else "AUS"}", masterRouteSummary(MasterOutputTarget.TEXT).removePrefix("MASTER: "), SettingsScreen.TEXT)
                addSettingsNav("VIDEO · ${if (videoOutputEnabled) "AN" else "AUS"}", masterRouteSummary(MasterOutputTarget.VIDEO).removePrefix("MASTER: "), SettingsScreen.VIDEO)
                addSettingsNav("AUDIO · ${if (musicOutputEnabled) "AN" else "AUS"}", masterRouteSummary(MasterOutputTarget.AUDIO).removePrefix("MASTER: "), SettingsScreen.MUSIC)
                addSettingsNav("FILM · ${if (OutputCapability.FILM in activeOutputCapabilities) "AN" else "AUS"}", "GENERATOR", SettingsScreen.FILM_OUTPUT)
                addSettingsNav("DREAM · ${if (dreamPlaybackRunning) "RUN" else "STOP"}", dreamControlMode.label, SettingsScreen.DREAM)
                settingsContent.addView(settingsSectionLabel("MODULE"))
                addSettingsNav("MODULE", "MATHE · CODE · FILM · ORCHESTRA", SettingsScreen.MODULES)
                settingsContent.addView(settingsSectionLabel("KERN"))
                addSettingsNav("MIND", "", SettingsScreen.MIND)
                addSettingsNav("SYSTEM", "", SettingsScreen.SYSTEM)
                settingsContent.addView(label("Kira v0.9.8", 10.5f, 18).apply {
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(68, 84, 70))
                })
            }
            SettingsScreen.TEXT -> {
                settingsContent.addView(settingsSectionLabel("KIRA · AUSGABE"))
                settingsContent.addView(label(masterRouteSummary(MasterOutputTarget.TEXT), 11f, 4).apply { setTextColor(Color.rgb(125, 145, 132)) })
                settingsContent.addView(productButton(if (textOutputEnabled) "KIRA · AN" else "KIRA · AUS").apply {
                    setOnClickListener { toggleOutputChannel(OutputChannel.TEXT) }
                }, buttonParams(5))
                settingsContent.addView(productButton("NUR TEXT").apply {
                    setOnClickListener { setSoloOutput(PrimaryOutputSurface.TEXT) }
                }, buttonParams(5))
                settingsContent.addView(settingsSectionLabel("AUSSEHEN"))
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                listOf(ChatDisplayMode.MATRIX, ChatDisplayMode.CLEAN, ChatDisplayMode.RAW).forEach { mode ->
                    row.addView(productButton(mode.name).apply {
                        alpha = if (chatDisplayMode == mode) 1f else 0.55f
                        setOnClickListener { setChatDisplayMode(mode); renderSettingsScreen() }
                    }, weightedButtonParams())
                }
                settingsContent.addView(row, buttonParams(5))
                settingsContent.addView(settingsSectionLabel("ID-FLUSS · AUSGABE"))
                settingsContent.addView(productButton(if (idFlowOutputEnabled) "ID-FLUSS · AN" else "ID-FLUSS · AUS").apply {
                    setOnClickListener { idFlowOutputEnabled = !idFlowOutputEnabled; persistModalityRouting(); applyOutputRouting(syncSensors = false); renderSettingsScreen() }
                }, buttonParams(5))
                settingsContent.addView(settingsSectionLabel("ID-FLUSS · DARSTELLUNG"))
                val flowRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                flowRow.addView(productButton("LANGSAM").apply {
                    setOnClickListener { idFlowSpeed = 0.16f; matrixRainView.setSpeedMultiplier(idFlowSpeed); persistModalityRouting(); renderSettingsScreen() }
                }, weightedButtonParams())
                flowRow.addView(productButton("MITTEL").apply {
                    setOnClickListener { idFlowSpeed = 0.32f; matrixRainView.setSpeedMultiplier(idFlowSpeed); persistModalityRouting(); renderSettingsScreen() }
                }, weightedButtonParams())
                flowRow.addView(productButton("SCHNELL").apply {
                    setOnClickListener { idFlowSpeed = 0.68f; matrixRainView.setSpeedMultiplier(idFlowSpeed); persistModalityRouting(); renderSettingsScreen() }
                }, weightedButtonParams())
                settingsContent.addView(flowRow, buttonParams(5))
                settingsContent.addView(productButton(if (idFlowPulse) "GRÜNES PULSIEREN · AN" else "GRÜNES PULSIEREN · AUS").apply {
                    setOnClickListener { idFlowPulse = !idFlowPulse; matrixRainView.setPulseEnabled(idFlowPulse); persistModalityRouting(); renderSettingsScreen() }
                }, buttonParams(5))
                settingsContent.addView(productButton("CHAT-VERLAUF LEEREN").apply {
                    setOnClickListener { chatEngine.clear(); renderChatHistory() }
                }, buttonParams(5))
            }
            SettingsScreen.VIDEO -> {
                settingsContent.addView(settingsSectionLabel("VIDEO · AUSGABE"))
                settingsContent.addView(label(masterRouteSummary(MasterOutputTarget.VIDEO), 11f, 4).apply { setTextColor(Color.rgb(125, 145, 132)) })
                settingsContent.addView(productButton(if (videoOutputEnabled) "VIDEO · AN" else "VIDEO · AUS").apply {
                    setOnClickListener { toggleOutputChannel(OutputChannel.VIDEO) }
                }, buttonParams(5))
                settingsContent.addView(productButton("NUR VIDEO").apply {
                    setOnClickListener { setSoloOutput(PrimaryOutputSurface.VIDEO) }
                }, buttonParams(5))
                settingsContent.addView(productButton(if (nowGateEffectEnabled) "TOR ZUM JETZT · EFFEKT AN" else "TOR ZUM JETZT · EFFEKT AUS").apply {
                    setOnClickListener {
                        nowGateEffectEnabled = !nowGateEffectEnabled
                        persistModalityRouting()
                        applyLayerComposition()
                        renderSettingsScreen()
                    }
                }, buttonParams(5))
                addSettingsNav(
                    "DREAM · ${if (dreamPlaybackRunning) "RUN" else "STOP"}",
                    "${dreamControlMode.label} · world tick ${dreamRuntime.snapshot().tick}",
                    SettingsScreen.DREAM
                )
                settingsContent.addView(settingsSectionLabel("HINTERGRUND"))
                settingsContent.addView(productButton(if (mediaBackgroundEnabled && mediaBackgroundUri != null) "MEDIUM · AN" else "MEDIUM · AUS").apply {
                    setOnClickListener {
                        if (mediaBackgroundUri == null) launchBackgroundMediaPicker() else {
                            mediaBackgroundEnabled = !mediaBackgroundEnabled
                            persistModalityRouting()
                            applyLayerComposition()
                            renderSettingsScreen()
                        }
                    }
                }, buttonParams(5))
                val mediumRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                mediumRow.addView(productButton("WÄHLEN").apply { setOnClickListener { launchBackgroundMediaPicker() } }, weightedButtonParams())
                mediumRow.addView(productButton("KEIN MEDIUM").apply { setOnClickListener { clearBackgroundMedia(); renderSettingsScreen() } }, weightedButtonParams())
                settingsContent.addView(mediumRow, buttonParams(5))
                val bgRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                listOf(
                    "SCHWARZ" to Color.rgb(0, 0, 0),
                    "GRAPHIT" to Color.rgb(17, 20, 24),
                    "WEISS" to Color.rgb(244, 244, 240),
                ).forEach { (name, color) ->
                    bgRow.addView(productButton(name).apply {
                        alpha = if (stageBackgroundColor == color) 1f else 0.55f
                        setOnClickListener { setStageBackgroundColor(color); renderSettingsScreen() }
                    }, weightedButtonParams())
                }
                settingsContent.addView(bgRow, buttonParams(5))
                settingsContent.addView(settingsSectionLabel("DARSTELLUNG"))
                settingsContent.addView(label("Nervensystem ohne Zentrum · reine Hintergrundverformung", 14f, 8).apply {
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.rgb(190, 215, 200))
                })
                val gateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                listOf(0.18f to "RUHIG", 0.42f to "FLUSS", 0.85f to "INTENSIV").forEach { (speed, label) ->
                    gateRow.addView(productButton(label).apply { setOnClickListener { nowGateSpeed = speed; if (::mediaBackdropView.isInitialized) mediaBackdropView.setNowGateSpeed(speed); persistModalityRouting(); syncExpressionRenderers(); renderSettingsScreen() } }, weightedButtonParams())
                }
                settingsContent.addView(gateRow, buttonParams(5))
            }
            SettingsScreen.DREAM -> {
                val world = dreamRuntime.snapshot()
                settingsContent.addView(settingsSectionLabel("DREAM · WELTMASCHINE"))
                settingsContent.addView(label(world.compactSummary(), 11f, 5).apply {
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.rgb(164, 192, 176))
                })
                settingsContent.addView(label("OFEN / BASE: ${dreamRuntime.knowledgeSize()} native Dream-Traits", 11f, 3).apply {
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.rgb(134, 160, 145))
                })
                settingsContent.addView(productButton(if (dreamPlaybackRunning) "DREAM · STOP" else "DREAM · START").apply {
                    setOnClickListener {
                        if (dreamPlaybackRunning) stopDreamPlayback(clear = false) else startDreamPlayback()
                        renderSettingsScreen()
                    }
                }, buttonParams(5))

                settingsContent.addView(settingsSectionLabel("STEUERUNG"))
                val modeRowA = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                listOf(DreamControlMode.AUTONOMOUS to "AUTONOM", DreamControlMode.REACTIVE to "REAKTIV").forEach { (mode, title) ->
                    modeRowA.addView(productButton(title).apply {
                        alpha = if (dreamControlMode == mode) 1f else .55f
                        setOnClickListener {
                            dreamControlMode = mode
                            if (dreamPlaybackRunning) {
                                if (mode == DreamControlMode.AUTONOMOUS) memory.setCognitiveMode(CognitiveMode.DREAM)
                                else if (memory.cognitiveMode() == CognitiveMode.DREAM) restorePreferredLiveCognitiveMode()
                                syncCognitiveModeUi()
                            }
                            persistModalityRouting()
                            renderSettingsScreen()
                        }
                    }, weightedButtonParams())
                }
                settingsContent.addView(modeRowA, buttonParams(5))
                val modeRowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                listOf(DreamControlMode.INSTRUMENT to "INSTRUMENT", DreamControlMode.FUSION to "FUSION").forEach { (mode, title) ->
                    modeRowB.addView(productButton(title).apply {
                        alpha = if (dreamControlMode == mode) 1f else .55f
                        setOnClickListener {
                            dreamControlMode = mode
                            if (dreamPlaybackRunning && memory.cognitiveMode() == CognitiveMode.DREAM) restorePreferredLiveCognitiveMode()
                            persistModalityRouting()
                            syncCognitiveModeUi()
                            renderSettingsScreen()
                        }
                    }, weightedButtonParams())
                }
                settingsContent.addView(modeRowB, buttonParams(5))

                settingsContent.addView(settingsSectionLabel("INPUT-EINFLUSS"))
                val influenceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                listOf(0.25f to "LEICHT", 0.58f to "MITTEL", 0.90f to "STARK").forEach { (value, title) ->
                    influenceRow.addView(productButton(title).apply {
                        alpha = if (kotlin.math.abs(dreamInfluenceStrength - value) < .04f) 1f else .55f
                        setOnClickListener { dreamInfluenceStrength = value; persistModalityRouting(); renderSettingsScreen() }
                    }, weightedButtonParams())
                }
                settingsContent.addView(influenceRow, buttonParams(5))

                settingsContent.addView(settingsSectionLabel("WELTGESCHWINDIGKEIT"))
                val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                listOf(0.55f to "RUHIG", 1.0f to "NORMAL", 1.65f to "SCHNELL").forEach { (value, title) ->
                    speedRow.addView(productButton(title).apply {
                        alpha = if (kotlin.math.abs(dreamWorldSpeed - value) < .06f) 1f else .55f
                        setOnClickListener { dreamWorldSpeed = value; persistModalityRouting(); renderSettingsScreen() }
                    }, weightedButtonParams())
                }
                settingsContent.addView(speedRow, buttonParams(5))

                settingsContent.addView(settingsSectionLabel("AUSDRUCK"))
                settingsContent.addView(productButton(if (dreamVisualEnabled) "DREAM VISUAL · AN" else "DREAM VISUAL · AUS").apply {
                    setOnClickListener {
                        dreamVisualEnabled = !dreamVisualEnabled
                        persistModalityRouting()
                        applyLayerComposition()
                        renderSettingsScreen()
                    }
                }, buttonParams(5))
                settingsContent.addView(productButton(if (dreamAudioLinked) "DREAM → ORCHESTRA · AN" else "DREAM → ORCHESTRA · AUS").apply {
                    setOnClickListener {
                        dreamAudioLinked = !dreamAudioLinked
                        unifiedAudio.setDreamWorld(dreamRuntime.snapshot().takeIf { dreamPlaybackRunning }, dreamAudioLinked && dreamPlaybackRunning)
                        persistModalityRouting()
                        renderSettingsScreen()
                    }
                }, buttonParams(5))
                settingsContent.addView(productButton("WELT NEU STARTEN").apply {
                    setOnClickListener {
                        dreamRuntime.reset()
                        if (dreamPlaybackRunning && ::dreamWorldView.isInitialized) dreamWorldView.setWorldState(dreamRuntime.snapshot(), animate = dreamVisualEnabled)
                        persistDreamWorldState()
                        renderSettingsScreen()
                    }
                }, buttonParams(5))
            }
            SettingsScreen.MUSIC -> {
                settingsContent.addView(settingsSectionLabel("KLANG · AUSGABE"))
                settingsContent.addView(label(masterRouteSummary(MasterOutputTarget.AUDIO), 11f, 4).apply { setTextColor(Color.rgb(125, 145, 132)) })
                settingsContent.addView(productButton(if (musicOutputEnabled) "KLANG · AN" else "KLANG · AUS").apply {
                    setOnClickListener { toggleOutputChannel(OutputChannel.MUSIC) }
                }, buttonParams(5))
                settingsContent.addView(productButton("NUR AUDIO").apply {
                    setOnClickListener { setSoloOutput(PrimaryOutputSurface.AUDIO) }
                }, buttonParams(5))
                val guard = CheckBox(this).apply {
                    text = "Feedback-Schutz / Self-output suppression"
                    setTextColor(Color.WHITE)
                    isChecked = feedbackProtectionEnabled
                    setOnCheckedChangeListener { _, checked -> setFeedbackProtection(checked) }
                }
                settingsContent.addView(guard)
            }
            SettingsScreen.FILM_OUTPUT -> {
                settingsContent.addView(settingsSectionLabel("FILM · HAUPTAUSGABE"))
                settingsContent.addView(label(masterRouteSummary(MasterOutputTarget.VIDEO), 11f, 4).apply { setTextColor(Color.rgb(125, 145, 132)) })
                settingsContent.addView(productButton(if (OutputCapability.FILM in activeOutputCapabilities) "FILM · AN" else "FILM · AUS").apply {
                    setOnClickListener { toggleOutputCapability(OutputCapability.FILM); renderSettingsScreen() }
                }, buttonParams(5))
                settingsContent.addView(productButton("NUR FILM").apply {
                    setOnClickListener { setSoloOutput(PrimaryOutputSurface.FILM) }
                }, buttonParams(5))
                addSettingsNav("FILM FEINABSTIMMUNG", "Intensität und State-Einfluss", SettingsScreen.MODULE_FILM)
            }
            SettingsScreen.MODULES -> {
                settingsContent.addView(settingsSectionLabel("OPERATIVE MODULE"))
                addSettingsNav(
                    "MATHE",
                    "${if (OutputCapability.MATH in activeOutputCapabilities) "AN" else "AUS"} · deterministischer Rechner",
                    SettingsScreen.MODULE_MATH
                )
                addSettingsNav(
                    "CODING",
                    "${if (OutputCapability.CODING in activeOutputCapabilities) "AN" else "AUS"} · strukturierter Codegenerator",
                    SettingsScreen.MODULE_CODING
                )
                addSettingsNav(
                    "FILM",
                    "${if (OutputCapability.FILM in activeOutputCapabilities) "AN" else "AUS"} · prozeduraler State-Director",
                    SettingsScreen.MODULE_FILM
                )
                addSettingsNav(
                    "ORCHESTRA",
                    "${if (OutputCapability.ORCHESTRA in activeOutputCapabilities) "AN" else "AUS"} · generative Klangsynthese",
                    SettingsScreen.MODULE_ORCHESTRA
                )
            }
            SettingsScreen.MODULE_MATH -> {
                settingsContent.addView(settingsSectionLabel("MATHE · OPERATIV"))
                settingsContent.addView(productButton(if (OutputCapability.MATH in activeOutputCapabilities) "MATHE · AN" else "MATHE · AUS").apply {
                    setOnClickListener { toggleOutputCapability(OutputCapability.MATH); renderSettingsScreen() }
                }, buttonParams(5))
                settingsContent.addView(label(
                    "Der Rechner arbeitet lokal und deterministisch. Bei erkannten Ausdrücken darf er Kiras normale Spiegelantwort ersetzen; ohne mathematische Aufgabe greift er nicht ein.",
                    12f,
                    8
                ).apply { setTextColor(Color.LTGRAY) })
                settingsContent.addView(settingsSectionLabel("GENAUIGKEIT"))
                val precisionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                listOf(3 to "3", 6 to "6", 10 to "10").forEach { (precision, title) ->
                    precisionRow.addView(productButton(title).apply {
                        alpha = if (mathPrecision == precision) 1f else .55f
                        setOnClickListener {
                            mathPrecision = precision
                            persistModalityRouting()
                            renderSettingsScreen()
                        }
                    }, weightedButtonParams())
                }
                settingsContent.addView(precisionRow, buttonParams(5))
                settingsContent.addView(productButton(if (mathFunctionsEnabled) "FUNKTIONEN · AN" else "FUNKTIONEN · AUS").apply {
                    setOnClickListener {
                        mathFunctionsEnabled = !mathFunctionsEnabled
                        persistModalityRouting()
                        renderSettingsScreen()
                    }
                }, buttonParams(5))
                settingsContent.addView(label("Unterstützt + − × ÷ % ^, Klammern sowie optional sqrt, sin, cos, tan, abs, ln, log, floor, ceil und round.", 12f, 8).apply { setTextColor(Color.LTGRAY) })
            }
            SettingsScreen.MODULE_CODING -> {
                settingsContent.addView(settingsSectionLabel("CODING · OPERATIV"))
                settingsContent.addView(productButton(if (OutputCapability.CODING in activeOutputCapabilities) "CODING · AN" else "CODING · AUS").apply {
                    setOnClickListener { toggleOutputCapability(OutputCapability.CODING); renderSettingsScreen() }
                }, buttonParams(5))
                settingsContent.addView(label(
                    "Der lokale Backend-Stand kann bereits sichere Funktionen/Klassen und Scaffolds erzeugen. Freie komplexe Codegenerierung bleibt dieselbe Schnittstelle, braucht aber später einen stärkeren gelernten oder externen Generator.",
                    12f,
                    8
                ).apply { setTextColor(Color.LTGRAY) })
                settingsContent.addView(settingsSectionLabel("SPRACHE"))
                val languageRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                CodingLanguage.entries.forEach { language ->
                    languageRow.addView(productButton(language.label.uppercase()).apply {
                        alpha = if (codingLanguage == language) 1f else .55f
                        setOnClickListener {
                            codingLanguage = language
                            persistModalityRouting()
                            renderSettingsScreen()
                        }
                    }, weightedButtonParams())
                }
                settingsContent.addView(languageRow, buttonParams(5))
                settingsContent.addView(productButton(if (codingStrict) "STRICT · AN" else "SCAFFOLD · AN").apply {
                    setOnClickListener {
                        codingStrict = !codingStrict
                        persistModalityRouting()
                        renderSettingsScreen()
                    }
                }, buttonParams(5))
                settingsContent.addView(productButton(if (codingMemoryContext) "GELERNTER KONTEXT · AN" else "GELERNTER KONTEXT · AUS").apply {
                    setOnClickListener {
                        codingMemoryContext = !codingMemoryContext
                        persistModalityRouting()
                        renderSettingsScreen()
                    }
                }, buttonParams(5))
                settingsContent.addView(label("Gelernter Kontext wird nur als Hinweis in den Generator gegeben; eine alte Relation wird nicht automatisch als richtige Programmlogik behandelt.", 12f, 8).apply { setTextColor(Color.LTGRAY) })
            }
            SettingsScreen.MODULE_FILM -> {
                settingsContent.addView(settingsSectionLabel("FILM · OPERATIV"))
                settingsContent.addView(productButton(if (OutputCapability.FILM in activeOutputCapabilities) "FILM · AN" else "FILM · AUS").apply {
                    setOnClickListener { toggleOutputCapability(OutputCapability.FILM); syncExpressionRenderers(); renderSettingsScreen() }
                }, buttonParams(5))
                settingsContent.addView(label(
                    "Aktueller Backend: prozeduraler State-Director. Er erzeugt aus Aktivität, Neuheit, Relationen und IDs eine eigene Bewegung/Verformung des gewählten Mediums. Ein späterer echter Video-Generator kann an dieselbe Directive-Schnittstelle gehängt werden.",
                    12f,
                    8
                ).apply { setTextColor(Color.LTGRAY) })
                settingsContent.addView(settingsSectionLabel("INTENSITÄT"))
                val intensityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                listOf(0.28f to "RUHIG", 0.55f to "MITTEL", 0.88f to "STARK").forEach { (value, title) ->
                    intensityRow.addView(productButton(title).apply {
                        alpha = if (kotlin.math.abs(filmIntensity - value) < .05f) 1f else .55f
                        setOnClickListener {
                            filmIntensity = value
                            persistModalityRouting()
                            syncExpressionRenderers()
                            renderSettingsScreen()
                        }
                    }, weightedButtonParams())
                }
                settingsContent.addView(intensityRow, buttonParams(5))
                settingsContent.addView(settingsSectionLabel("STATE-EINFLUSS"))
                val stateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                listOf(0.25f to "LEICHT", 0.70f to "MIX", 1.0f to "VOLL").forEach { (value, title) ->
                    stateRow.addView(productButton(title).apply {
                        alpha = if (kotlin.math.abs(filmStateInfluence - value) < .05f) 1f else .55f
                        setOnClickListener {
                            filmStateInfluence = value
                            persistModalityRouting()
                            syncExpressionRenderers()
                            renderSettingsScreen()
                        }
                    }, weightedButtonParams())
                }
                settingsContent.addView(stateRow, buttonParams(5))
            }
            SettingsScreen.MODULE_ORCHESTRA -> {
                settingsContent.addView(settingsSectionLabel("ORCHESTRA · OPERATIV"))
                settingsContent.addView(productButton(if (OutputCapability.ORCHESTRA in activeOutputCapabilities) "ORCHESTRA · AN" else "ORCHESTRA · AUS").apply {
                    setOnClickListener { toggleOutputCapability(OutputCapability.ORCHESTRA); renderSettingsScreen() }
                }, buttonParams(5))
                settingsContent.addView(label("Orchestra synthetisiert weiterhin vollständig lokal aus dem gemeinsamen State. Keine Samples werden abgespielt; der State steuert Stimmen, Harmonie, Spannung und Bewegung.", 12f, 8).apply { setTextColor(Color.LTGRAY) })
                settingsContent.addView(settingsSectionLabel("ERINNERUNG / JETZT"))
                val memoryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                listOf(0.10f to "JETZT", 0.35f to "MIX", 0.72f to "MEMORY").forEach { (value, title) ->
                    memoryRow.addView(productButton(title).apply {
                        alpha = if (kotlin.math.abs(orchestraMemoryBlend - value) < .05f) 1f else .55f
                        setOnClickListener {
                            orchestraMemoryBlend = value
                            persistModalityRouting()
                            applyOperationalOutputTuning()
                            if (unifiedAudio.isRunning()) pushContinuousOrchestraState()
                            renderSettingsScreen()
                        }
                    }, weightedButtonParams())
                }
                settingsContent.addView(memoryRow, buttonParams(5))
                settingsContent.addView(settingsSectionLabel("STIMMEN"))
                val voicesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                listOf(4 to "4", 9 to "9", 14 to "14").forEach { (value, title) ->
                    voicesRow.addView(productButton(title).apply {
                        alpha = if (orchestraMaxVoices == value) 1f else .55f
                        setOnClickListener {
                            orchestraMaxVoices = value
                            persistModalityRouting()
                            applyOperationalOutputTuning()
                            renderSettingsScreen()
                        }
                    }, weightedButtonParams())
                }
                settingsContent.addView(voicesRow, buttonParams(5))
                settingsContent.addView(settingsSectionLabel("TEMPO"))
                val tempoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                listOf(0.82f to "RUHIG", 1.0f to "NATÜRLICH", 1.20f to "TREIBEND").forEach { (value, title) ->
                    tempoRow.addView(productButton(title).apply {
                        alpha = if (kotlin.math.abs(orchestraTempoScale - value) < .04f) 1f else .55f
                        setOnClickListener {
                            orchestraTempoScale = value
                            persistModalityRouting()
                            applyOperationalOutputTuning()
                            renderSettingsScreen()
                        }
                    }, weightedButtonParams())
                }
                settingsContent.addView(tempoRow, buttonParams(5))
            }
            SettingsScreen.SYSTEM -> {
                settingsContent.addView(settingsSectionLabel("SYSTEM"))
                settingsContent.addView(label("${mindRepository.status()}\n${memoryText()}", 11.5f, 8).apply {
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.rgb(170, 195, 180))
                })
                addSettingsNav("ARCHIV", "Speicherzustand und Profile", SettingsScreen.ARCHIVE)
                addSettingsNav("MIND", "Language/FABRIC und Forge BASE", SettingsScreen.MIND)
                addSettingsNav("KURATOR", "Betriebslogik und geschützter Entwicklerpfad", SettingsScreen.KURATOR)
            }
            SettingsScreen.ARCHIVE -> {
                settingsContent.addView(settingsSectionLabel("SYSTEM / ARCHIV"))
                settingsContent.addView(label(memoryText(), 12f, 8).apply { typeface = Typeface.MONOSPACE; setTextColor(Color.rgb(180, 215, 190)) })
                settingsContent.addView(label("Die vollständigen Speicher-, Profil- und Slot-Messwerte liegen absichtlich nicht auf der normalen Oberfläche. Sie sind im Labor unter ARCHIV erreichbar.", 12f, 8).apply { setTextColor(Color.LTGRAY) })
            }
            SettingsScreen.MIND -> {
                settingsContent.addView(settingsSectionLabel("SYSTEM / MIND"))
                settingsContent.addView(label(mindRepository.status(), 12f, 8).apply { typeface = Typeface.MONOSPACE; setTextColor(Color.rgb(180, 220, 200)) })
                settingsContent.addView(label("Kira ist die Textoberfläche über demselben lokalen MIND. Forge BASE bleibt optional; lokale Chat- und Kontextrelationen bleiben davon getrennt erhalten.", 12f, 8).apply { setTextColor(Color.LTGRAY) })
            }
            SettingsScreen.KURATOR -> {
                settingsContent.addView(settingsSectionLabel("SYSTEM / KURATOR"))
                settingsContent.addView(label("Oben werden nur Eingaben geschaltet. Sichtbare und hörbare Ausgaben liegen im Ausgangsmenü; tiefe Eingriffe bleiben im Kurator-Pfad.", 12f, 8).apply { setTextColor(Color.LTGRAY) })
                addSettingsNav("KURATOR-MODUS", "LEARN / PLAY / DREAM und Laborzugang", SettingsScreen.KURATOR_MODE)
            }
            SettingsScreen.KURATOR_MODE -> {
                settingsContent.addView(settingsSectionLabel("KURATOR-MODUS"))
                val mode = memory.cognitiveMode()
                settingsContent.addView(label("Aktiv: ${mode.label}", 14f, 8).apply { typeface = Typeface.MONOSPACE; setTextColor(Color.rgb(0, 255, 0)) })
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                row.addView(productButton("LEARN").apply { alpha = if (mode == CognitiveMode.LEARN) 1f else .55f; setOnClickListener { enterLearnMode(); renderSettingsScreen() } }, weightedButtonParams())
                row.addView(productButton("PLAY").apply { alpha = if (mode == CognitiveMode.PLAY) 1f else .55f; setOnClickListener { enterPlayMode(); renderSettingsScreen() } }, weightedButtonParams())
                row.addView(productButton("DREAM").apply { alpha = if (dreamPlaybackRunning) 1f else .55f; setOnClickListener { if (dreamPlaybackRunning) stopDreamPlayback(false) else startDreamPlayback(); renderSettingsScreen() } }, weightedButtonParams())
                settingsContent.addView(row, buttonParams(5))
                settingsContent.addView(label("LEARN schreibt neue Sensorstruktur. PLAY aktiviert Bekanntes. DREAM ist eine persistente Weltmaschine; AUTONOM ignoriert externe Reize, REAKTIV/INSTRUMENT/FUSION können MASTER-geroutete Inputs aufnehmen.", 12f, 8).apply { setTextColor(Color.LTGRAY) })
                settingsContent.addView(settingsSectionLabel("ENTWICKLER"))
                addSettingsNav("LABOR", "Messwerte, Intake, Hybrid, Archiv, MIND und Control", SettingsScreen.LAB)
            }
            SettingsScreen.LAB -> {
                settingsContent.addView(settingsSectionLabel("LABOR · ENTWICKLER"))
                (developerLabHost.parent as? ViewGroup)?.removeView(developerLabHost)
                settingsContent.addView(developerLabHost, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                showDeveloperPage(currentPage)
            }
        }
    }

    private fun buildLivePage() {
        livePage.addView(label("INTAKE / ANALYSIS", 20f, 18).apply {
            setTextColor(Color.rgb(105, 225, 235))
        })
        livePage.addView(label(
            "Sensor-Intake liegt hinter der Oberfläche. LEARN schreibt neue Erfahrung, PLAY aktiviert Bekanntes. DREAM ist davon entkoppelt: AUTONOM ignoriert externe Reize; REAKTIV / INSTRUMENT / FUSION können MASTER-geroutete Inputs als Weltmodulation verwenden.",
            12f,
            6
        ).apply { setTextColor(Color.LTGRAY) })

        cognitiveModeStatus = label("Modus: PLAY · Sensoren read-only", 14f, 8).apply {
            setTextColor(Color.rgb(145, 230, 210))
        }
        livePage.addView(cognitiveModeStatus)
        val cognitiveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        cognitiveRow.addView(Button(this).apply {
            text = "LEARN · SCHREIBEN"
            setOnClickListener { enterLearnMode() }
        }, weightedButtonParams())
        cognitiveRow.addView(Button(this).apply {
            text = "PLAY · READ-ONLY"
            setOnClickListener { enterPlayMode() }
        }, weightedButtonParams())
        livePage.addView(cognitiveRow, buttonParams(8))

        audioEncoderStatus = stableDiagnosticLabel("Signal-Parser: ${audioEncoderMode.label}", 14f, 12, 1).apply {
            setTextColor(Color.rgb(145, 210, 255))
        }
        livePage.addView(audioEncoderStatus)

        status = stableDiagnosticLabel("Audio-Feed: AUS", 17f, 10, 1)
        frequency = stableDiagnosticLabel(
            when (audioEncoderMode) {
                AudioEncoderMode.FFT_FREQUENCY -> "Dominante Frequenz: — Hz"
                AudioEncoderMode.DIRECT_PATTERN -> "Direktmuster: —"
                AudioEncoderMode.ADAPTIVE_PATTERN -> "Adaptive-ID: —"
            },
            14f,
            6,
            1
        )
        frequencyId = stableDiagnosticLabel(when (audioEncoderMode) {
            AudioEncoderMode.FFT_FREQUENCY -> "Frequenz-ID: —"
            AudioEncoderMode.DIRECT_PATTERN -> "Pattern-ID: —"
            AudioEncoderMode.ADAPTIVE_PATTERN -> "Adaptive Pattern-ID: —"
        }, 14f, 4, 1)
        strength = stableDiagnosticLabel("Signalstärke: —", 14f, 4, 1)
        frames = stableDiagnosticLabel("Frames verarbeitet: 0", 14f, 4, 1)
        livePage.addView(status)
        livePage.addView(frequency)
        livePage.addView(frequencyId)
        livePage.addView(strength)
        livePage.addView(frames)

        startButton = Button(this).apply {
            text = "AUDIO STARTEN"
            setOnClickListener { ensurePermissionAndStart() }
        }
        stopButton = Button(this).apply {
            text = "AUDIO STOPP"
            isEnabled = false
            setOnClickListener { stopAudio() }
        }
        livePage.addView(startButton, buttonParams(12))
        livePage.addView(stopButton, buttonParams(6))

        cameraStatus = stableDiagnosticLabel("Optical Feed: AUS", 15f, 16, 1).apply { setTextColor(Color.rgb(235, 175, 95)) }
        cameraFrames = stableDiagnosticLabel("Visuelle Frames: 0 · visuelle Beobachtungen: 0", 12f, 5, 2).apply {
            setTextColor(Color.rgb(205, 205, 205))
        }
        adaptiveVisualQuantizerStatus = stableDiagnosticLabel(adaptiveVisualQuantizerText(), 11f, 4, 2).apply {
            setTextColor(Color.rgb(195, 175, 235))
        }
        livePage.addView(cameraStatus)
        livePage.addView(cameraFrames)
        livePage.addView(adaptiveVisualQuantizerStatus)

        cameraButton = Button(this).apply {
            text = "KAMERA STARTEN"
            setOnClickListener { ensureCameraPermissionAndStart() }
        }
        cameraStopButton = Button(this).apply {
            text = "KAMERA STOPP"
            isEnabled = false
            setOnClickListener { stopCameraLearning() }
        }
        livePage.addView(cameraButton, buttonParams(8))
        livePage.addView(cameraStopButton, buttonParams(6))

        livePage.addView(label("TEMPORAL SYNC / FRAME CONTROL", 14f, 12).apply {
            setTextColor(Color.rgb(120, 220, 205))
        })
        liveDelaySpinner = Spinner(this).apply {
            val presets = LowLatencyLivePipeline.DelayPreset.entries
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                presets.map { it.label }
            )
            setSelection(presets.indexOf(livePipeline.getPreset()).coerceAtLeast(0), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = presets.getOrElse(position) { LowLatencyLivePipeline.DelayPreset.AUTO }
                    livePipeline.setPreset(selected)
                    getSharedPreferences("equinox_live_latency", MODE_PRIVATE)
                        .edit().putString("preset", selected.name).apply()
                    updateLiveLatencyStatus()
                    scheduleLowLatencyRender()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        livePage.addView(liveDelaySpinner, buttonParams(4))
        liveLatencyStatus = stableDiagnosticLabel("Temporal Sync: —", 11f, 6, 2).apply {
            setTextColor(Color.rgb(185, 205, 215))
        }
        livePage.addView(liveLatencyStatus)

        memoryStats = stableDiagnosticLabel(memoryText(), 11f, 16, 22).apply { setTextColor(Color.rgb(180, 220, 215)) }
        livePage.addView(memoryStats)

        livePage.addView(label(
            "MEMORY VIDEO — Kamera liefert nur Pigment; der gelernte Working-State verformt das Bild read-only.",
            12f,
            12
        ).apply { setTextColor(Color.rgb(170, 205, 245)) })
        liveVideoCanvas = MemoryVideoView(this).apply { setModeLabel("LIVE") }
        livePage.addView(
            liveVideoCanvas,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(520)).apply { topMargin = dp(8) }
        )

        outputCanvas = EquinoxOutputView(this).apply { setModeLabel("LIVE STATE") }
        livePage.addView(
            outputCanvas,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)).apply { topMargin = dp(12) }
        )

        liveOrchestraStatus = label("Unified Audio: AUS", 13f, 10).apply {
            setTextColor(Color.rgb(125, 220, 255))
        }
        livePage.addView(liveOrchestraStatus)
        liveFeedbackProtectionToggle = CheckBox(this).apply {
            text = "Feedback-Schutz: Android AEC + Eigenklang-Filter"
            isChecked = feedbackProtectionEnabled
            setTextColor(Color.LTGRAY)
            setOnCheckedChangeListener { _, checked -> setFeedbackProtection(checked) }
        }
        livePage.addView(liveFeedbackProtectionToggle)
        liveContinuousButton = Button(this).apply {
            text = "AUDIO START"
            setOnClickListener { toggleContinuousOrchestra() }
        }
        livePage.addView(liveContinuousButton, buttonParams(6))
        livePage.addView(label(
            "Mikrofon darf dabei weiterlaufen. Der Feedback-Schutz entfernt bekannte Syntheseanteile konservativ vor der Lernpipeline; mit Kopfhörern bleibt der Input am saubersten.",
            11f,
            4
        ).apply { setTextColor(Color.GRAY) })
    }

    private fun buildHybridPage() {
        hybridPage.addView(label("HYBRID — GEGENWART + GEDÄCHTNIS", 20f, 18).apply {
            setTextColor(Color.rgb(120, 235, 190))
        })
        hybridPage.addView(label(
            "Live-Input bleibt echtes A-Signal. Dazwischen darf das bereits Gelernte read-only über Prediction und lokale Relationen weiterlaufen. Interne Aktivierung wird nicht als neue Erfahrung zurückgeschrieben.",
            12f,
            6
        ).apply { setTextColor(Color.LTGRAY) })

        hybridSensorStatus = label("Sensoren: Audio AUS · Kamera AUS", 14f, 12).apply {
            setTextColor(Color.rgb(155, 230, 205))
        }
        hybridPage.addView(hybridSensorStatus)

        val sensorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        sensorRow.addView(Button(this).apply {
            text = "AUDIO AN/AUS"
            setOnClickListener { if (running) stopAudio() else ensurePermissionAndStart() }
        }, weightedButtonParams())
        sensorRow.addView(Button(this).apply {
            text = "KAMERA AN/AUS"
            setOnClickListener { if (cameraRunning) stopCameraLearning() else ensureCameraPermissionAndStart() }
        }, weightedButtonParams())
        hybridPage.addView(sensorRow, buttonParams(8))

        hybridModeStatus = label("HYBRID: bereit · wartet auf Live-Makro", 15f, 12).apply {
            setTextColor(Color.rgb(185, 255, 220))
        }
        hybridPage.addView(hybridModeStatus)

        hybridPage.addView(label(
            "MEMORY VIDEO — derselbe Live-Frame wird zusätzlich durch Recall, Prediction und Überaustausch des Hybrid-State verformt.",
            12f,
            10
        ).apply { setTextColor(Color.rgb(170, 225, 205)) })
        hybridVideoCanvas = MemoryVideoView(this).apply { setModeLabel("HYBRID") }
        hybridPage.addView(
            hybridVideoCanvas,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(520)).apply { topMargin = dp(8) }
        )

        hybridCanvas = EquinoxOutputView(this).apply { setModeLabel("HYBRID STATE") }
        hybridPage.addView(
            hybridCanvas,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)).apply { topMargin = dp(12) }
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(Button(this).apply {
            text = "RUN"
            setOnClickListener { startHybridPlayback() }
        }, weightedButtonParams())
        row.addView(Button(this).apply {
            text = "1 STEP"
            setOnClickListener {
                val ok = memory.hybridPlaybackStep()
                refreshHybridPage(ok)
                updateMemoryStats()
            }
        }, weightedButtonParams())
        row.addView(Button(this).apply {
            text = "STOP"
            setOnClickListener { stopHybridPlayback(clear = false) }
        }, weightedButtonParams())
        hybridPage.addView(row, buttonParams(10))

        hybridOrchestraStatus = label("Unified Audio: AUS", 13f, 10).apply {
            setTextColor(Color.rgb(125, 220, 255))
        }
        hybridPage.addView(hybridOrchestraStatus)
        hybridFeedbackProtectionToggle = CheckBox(this).apply {
            text = "Feedback-Schutz: Android AEC + Eigenklang-Filter"
            isChecked = feedbackProtectionEnabled
            setTextColor(Color.LTGRAY)
            setOnCheckedChangeListener { _, checked -> setFeedbackProtection(checked) }
        }
        hybridPage.addView(hybridFeedbackProtectionToggle)
        hybridContinuousButton = Button(this).apply {
            text = "AUDIO START"
            setOnClickListener { toggleContinuousOrchestra() }
        }
        hybridPage.addView(hybridContinuousButton, buttonParams(6))
    }

    private fun buildMemoryPage() {
        memoryPage.addView(label("ARCHIVE — INTERNAL ACCESS", 20f, 18).apply {
            setTextColor(Color.rgb(226, 130, 255))
        })
        memoryPage.addView(label(
            "Kein Mikrofon, keine Kamera, kein neues Lernen. ARCHIVE traversiert gespeicherte Pfade; BRANCH WALK bewegt sich autonom zwischen Prediction, Relationen, Substitutionsklassen und Crossmodal-Brücken.",
            12f,
            6
        ).apply { setTextColor(Color.LTGRAY) })

        memoryModeStatus = label("ARCHIVE: bereit", 15f, 14).apply {
            setTextColor(Color.rgb(225, 205, 255))
        }
        memoryPage.addView(memoryModeStatus)

        dreamModeStatus = label("BRANCH WALK: bereit", 14f, 6).apply {
            setTextColor(Color.rgb(255, 176, 112))
        }
        memoryPage.addView(dreamModeStatus)

        memoryCanvas = EquinoxOutputView(this).apply { setModeLabel("MEMORY / DREAM") }
        memoryPage.addView(
            memoryCanvas,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(540)).apply { topMargin = dp(12) }
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val start = Button(this).apply {
            text = "ARCHIVE RUN"
            setOnClickListener { startMemoryPlayback() }
        }
        val step = Button(this).apply {
            text = "1 STEP"
            setOnClickListener {
                if (running) stopAudio()
                stopCameraLearning()
                stopHybridPlayback(clear = false)
                memory.setCognitiveMode(CognitiveMode.PLAY)
                syncCognitiveModeUi()
                val ok = memory.memoryPlaybackStep()
                memoryModeStatus.text = if (ok) "ARCHIVE: Einzelschritt" else "ARCHIVE: noch keine gelernten Makros"
                refreshMemoryPage()
                updateMemoryStats()
            }
        }
        val stop = Button(this).apply {
            text = "STOP"
            setOnClickListener { stopMemoryPlayback(clear = false) }
        }
        row.addView(start, weightedButtonParams())
        row.addView(step, weightedButtonParams())
        row.addView(stop, weightedButtonParams())
        memoryPage.addView(row, buttonParams(10))

        val dreamRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        dreamRow.addView(Button(this).apply {
            text = "BRANCH RUN"
            setOnClickListener { startDreamPlayback() }
        }, weightedButtonParams())
        dreamRow.addView(Button(this).apply {
            text = "BRANCH STEP"
            setOnClickListener {
                stopMemoryPlayback(clear = false)
                stopAudio()
                stopCameraLearning()
                stopHybridPlayback(clear = false)
                memory.setCognitiveMode(CognitiveMode.DREAM)
                syncCognitiveModeUi()
                val ok = memory.autonomousDreamStep()
                dreamModeStatus.text = if (ok) "BRANCH WALK: Einzelschritt" else "BRANCH WALK: noch keine gelernten Makros"
                refreshMemoryPage()
                updateMemoryStats()
            }
        }, weightedButtonParams())
        dreamRow.addView(Button(this).apply {
            text = "BRANCH STOP"
            setOnClickListener { stopDreamPlayback(clear = false) }
        }, weightedButtonParams())
        memoryPage.addView(dreamRow, buttonParams(6))

        memoryAutoOrchestra = CheckBox(this).apply {
            text = "Generatives Orchester automatisch aus aktuellem Memory-State"
            setTextColor(Color.WHITE)
            isChecked = false
        }
        memoryPage.addView(memoryAutoOrchestra)

        memoryOrchestraStatus = label("Unified Audio: AUS", 13f, 8).apply {
            setTextColor(Color.rgb(125, 220, 255))
        }
        memoryPage.addView(memoryOrchestraStatus)

        memoryPage.addView(Button(this).apply {
            text = "ARCHIVE AUDIO PREVIEW"
            setOnClickListener {
                unifiedAudio.stop()
                val state = outputBridge.snapshot()
                unifiedAudio.playFinite(state) { text -> runOnUiThread { memoryOrchestraStatus.text = text } }
            }
        }, buttonParams(6))

        memoryPage.addView(label("CENTRAL ARCHIVE", 16f, 18).apply {
            setTextColor(Color.rgb(233, 211, 143))
        })
        memoryPage.addView(label(
            "BASE ist global und unveränderlich; PERSONAL/DERIVED liegen im Profil. BASE AUS + NEW ergibt einen echten Start von Null.",
            12f, 5
        ).apply { setTextColor(Color.LTGRAY) })
        memoryPage.addView(CheckBox(this).apply {
            text = "BASE KNOWLEDGE aktiv"
            setTextColor(Color.WHITE)
            isChecked = memory.baseKnowledgeEnabled()
            setOnCheckedChangeListener { _, enabled ->
                stopMemoryPlayback(clear = false)
                stopAudio()
                stopCameraLearning()
                stopHybridPlayback(clear = false)
                memory.setCognitiveMode(CognitiveMode.PLAY)
                val ok = memory.setBaseKnowledgeEnabled(enabled)
                if (!ok) {
                    setOnCheckedChangeListener(null)
                    isChecked = memory.baseKnowledgeEnabled()
                }
                syncCognitiveModeUi()
                refreshMemoryPage()
                updateMemoryStats()
            }
        })

        memoryPage.addView(label("ARCHIVE INTAKE", 14f, 10).apply {
            setTextColor(Color.rgb(205, 182, 245))
        })
        memoryPage.addView(label(
            "Forge Intermediate v1 bleibt das neutrale Build-Format. Kira v0.9.8 mountet nur validierte .kirabase Runtime-Pakete: Checksums → Staging → Commit. LIVE/PERSONAL bleibt dabei unangetastet.",
            12f, 4
        ).apply { setTextColor(Color.LTGRAY) })
        val activeBase = memory.baseManifest()
        externalKnowledgeStatus = label(
            if (activeBase.installed) "BASE aktiv: ${activeBase.packId} v${activeBase.version} · ${activeBase.surfaceCount} surfaces · ${activeBase.macroCount} Makros · ${activeBase.highestTier}"
            else "BASE: leer · LIVE/PERSONAL bleibt eigenständig",
            12f, 5
        ).apply { setTextColor(Color.rgb(190, 205, 235)) }
        memoryPage.addView(externalKnowledgeStatus)
        memoryPage.addView(Button(this).apply {
            text = "BASE PACKAGE · INSTALL"
            setOnClickListener { basePackageImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        }, buttonParams(5))
        val baseOps = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        baseOps.addView(Button(this).apply {
            text = "ROLLBACK"
            setOnClickListener { rollbackBasePackage() }
        }, weightedButtonParams())
        baseOps.addView(Button(this).apply {
            text = "BASE REMOVE"
            setOnClickListener { removeBasePackage() }
        }, weightedButtonParams())
        memoryPage.addView(baseOps, buttonParams(4))
        memoryPage.addView(Button(this).apply {
            text = "FORGE v1 · DRY-RUN"
            setOnClickListener { forgeIntermediateImportLauncher.launch(arrayOf("application/json", "text/json", "text/plain", "*/*")) }
        }, buttonParams(5))
        memoryPage.addView(Button(this).apply {
            text = "LEGACY EXTERNAL v1 · IMPORT"
            setOnClickListener { externalKnowledgeImportLauncher.launch(arrayOf("application/json", "text/json", "text/plain", "*/*")) }
        }, buttonParams(5))

        memoryPage.addView(label("CASE FILES", 16f, 18).apply {
            setTextColor(Color.rgb(233, 211, 143))
        })
        memoryPage.addView(label(
            "Eine Case File enthält das indexierte Langzeitgedächtnis plus adaptive Audio- und Visual-Codebooks. UI-Einstellungen bleiben global.",
            12f, 5
        ).apply { setTextColor(Color.LTGRAY) })

        profileStatus = label("Aktiv: ${profiles.activeProfileName()}", 13f, 6).apply {
            setTextColor(Color.rgb(205, 220, 235))
        }
        memoryPage.addView(profileStatus)
        profileSpinner = Spinner(this)
        memoryPage.addView(profileSpinner, buttonParams(4))
        refreshProfileControls()

        val profileRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        profileRow1.addView(Button(this).apply {
            text = "NEW"
            setOnClickListener { askProfileName("Neues Gedächtnis", "Memory ${profiles.listProfiles().size + 1}") { name ->
                stopAllForProfileSwitch()
                saveLiveIdentity()
                val created = profiles.createFresh(name)
                if (created != null) {
                    reloadLearnedIdentity()
                    refreshProfileControls(selectId = created.id)
                    profileStatus.text = "Neu und aktiv: ${created.name}"
                } else profileStatus.text = "Neues Profil konnte nicht erstellt werden"
            } }
        }, weightedButtonParams())
        profileRow1.addView(Button(this).apply {
            text = "SAVE"
            setOnClickListener {
                saveLiveIdentity()
                val ok = profiles.saveActive()
                refreshProfileControls()
                profileStatus.text = if (ok) "Gespeichert: ${profiles.activeProfileName()}" else "Profil konnte nicht gespeichert werden"
            }
        }, weightedButtonParams())
        profileRow1.addView(Button(this).apply {
            text = "LOAD"
            setOnClickListener {
                val target = selectedProfileInfo() ?: return@setOnClickListener
                stopAllForProfileSwitch()
                saveLiveIdentity()
                val ok = profiles.switchTo(target.id)
                if (ok) {
                    reloadLearnedIdentity()
                    refreshProfileControls(selectId = target.id)
                    profileStatus.text = "Aktiv: ${target.name}"
                } else profileStatus.text = "Profil konnte nicht geladen werden"
            }
        }, weightedButtonParams())
        memoryPage.addView(profileRow1, buttonParams(4))

        val profileRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        profileRow2.addView(Button(this).apply {
            text = "DUP"
            setOnClickListener {
                val source = selectedProfileInfo() ?: return@setOnClickListener
                askProfileName("Profil duplizieren", "${source.name} Copy") { name ->
                    saveLiveIdentity()
                    val copy = profiles.duplicate(source.id, name)
                    refreshProfileControls(selectId = copy?.id)
                    profileStatus.text = if (copy != null) "Dupliziert: ${copy.name}" else "Duplizieren fehlgeschlagen"
                }
            }
        }, weightedButtonParams())
        profileRow2.addView(Button(this).apply {
            text = "EXPORT"
            setOnClickListener {
                val selected = selectedProfileInfo() ?: return@setOnClickListener
                saveLiveIdentity()
                profileExportLauncher.launch(selected.name.replace(Regex("[^A-Za-z0-9._-]+"), "_") + ".eqx")
            }
        }, weightedButtonParams())
        profileRow2.addView(Button(this).apply {
            text = "IMPORT"
            setOnClickListener { profileImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        }, weightedButtonParams())
        memoryPage.addView(profileRow2, buttonParams(4))

        memoryPage.addView(Button(this).apply {
            text = "PROFIL LÖSCHEN"
            setOnClickListener {
                val selected = selectedProfileInfo() ?: return@setOnClickListener
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Profil löschen?")
                    .setMessage(selected.name)
                    .setNegativeButton("Abbrechen", null)
                    .setPositiveButton("Löschen") { _, _ ->
                        stopAllForProfileSwitch()
                        val wasActive = selected.active
                        val ok = profiles.delete(selected.id)
                        if (ok && wasActive) reloadLearnedIdentity()
                        refreshProfileControls()
                        profileStatus.text = if (ok) "Profil gelöscht" else "Mindestens ein Profil muss bestehen bleiben"
                    }.show()
            }
        }, buttonParams(4))

        memoryPage.addView(Button(this).apply {
            text = "AKTIVES FELD LEEREN"
            setOnClickListener {
                stopMemoryPlayback(clear = true)
                refreshMemoryPage()
            }
        }, buttonParams(6))
    }

    private fun refreshProfileControls(selectId: String? = null) {
        if (!::profileSpinner.isInitialized || !::profiles.isInitialized) return
        val items = profiles.listProfiles()
        val labels = items.map { (if (it.active) "● " else "") + it.name + if (it.bytes > 0L) " · ${it.bytes / 1024} KB" else "" }
        profileSpinnerSyncing = true
        profileSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val targetId = selectId ?: profiles.activeProfileId()
        val index = items.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
        if (items.isNotEmpty()) profileSpinner.setSelection(index, false)
        profileSpinnerSyncing = false
    }

    private fun selectedProfileInfo(): MemoryProfileManager.ProfileInfo? {
        if (!::profileSpinner.isInitialized || !::profiles.isInitialized) return null
        val items = profiles.listProfiles()
        return items.getOrNull(profileSpinner.selectedItemPosition)
    }

    private fun askProfileName(title: String, initial: String, onName: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            selectAll()
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("OK") { _, _ -> onName(input.text.toString()) }
            .show()
    }

    private fun saveLiveIdentity() {
        if (::memory.isInitialized) memory.saveNow()
        if (::adaptiveQuantizer.isInitialized) adaptiveQuantizer.saveNow()
        if (::adaptiveVisualQuantizer.isInitialized) adaptiveVisualQuantizer.saveNow()
    }

    private fun stopAllForProfileSwitch() {
        stopAudio()
        stopCameraLearning()
        stopMemoryPlayback(clear = true)
        stopDreamPlayback(clear = true)
        if (hybridPlaybackRunning) stopHybridPlayback(clear = true)
        if (unifiedAudio.isRunning()) unifiedAudio.stop()
        memory.setCognitiveMode(CognitiveMode.PLAY)
        syncCognitiveModeUi()
    }

    /** Reloads only learned identity; UI/display preferences remain untouched. */
    private fun reloadLearnedIdentity() {
        memory.reloadFromDisk()
        adaptiveQuantizer = AdaptiveAudioQuantizer(this)
        adaptiveVisualQuantizer = AdaptiveVisualQuantizer(this)
        visualAnalyzer = null
        visualFrameCount = 0L
        visualObservationCount = 0L
        refreshMemoryPage()
        updateMemoryStats()
        refreshGraph()
        if (::adaptiveQuantizerStatus.isInitialized) adaptiveQuantizerStatus.text = adaptiveQuantizerText()
        if (::adaptiveVisualQuantizerStatus.isInitialized) adaptiveVisualQuantizerStatus.text = adaptiveVisualQuantizerText()
    }

    private fun showBasePackageDryRun(report: KiraBasePackageReport) {
        val payload = report.payload
        val m = payload?.manifest
        val issuePreview = report.issues.take(8).joinToString("\n") { issue ->
            val prefix = if (issue.severity == KiraBasePackageIssue.Severity.ERROR) "ERROR" else "WARN"
            "$prefix ${issue.code}: ${issue.message}"
        }
        val summary = buildString {
            append("Format: ").append(KiraBasePackageSpec.FORMAT)
            append("\nPackage: ").append(m?.packageId ?: "—")
            if (m != null) append(" v").append(m.version)
            append("\nNamespace: ").append(m?.namespace ?: "—")
            append("\nSource: ").append(m?.sourceId ?: "—")
            append("\nAdapter: ").append(m?.adapterId ?: "—").append(" ").append(m?.adapterVersion ?: "")
            append("\nSurfaces: ").append(m?.surfaceCount ?: 0)
            append("\nMakros: ").append(m?.macroCount ?: 0)
            append("\nRelationen: ").append(m?.relationCount ?: 0)
            append("\nTraits: ").append(m?.traitCount ?: 0)
            append("\nHöchste Tiefe: ").append(m?.highestDepth ?: 0).append(" · ").append(m?.highestTier ?: "—")
            append("\nDigest: ").append(payload?.payloadDigest?.take(20) ?: "—")
            append("\nWarnings: ").append(report.warnings.size).append(" · Errors: ").append(report.errors.size)
            if (issuePreview.isNotBlank()) append("\n\n").append(issuePreview)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (report.valid) "Kira BASE v1 — validiert" else "Kira BASE v1 — abgelehnt")
            .setMessage(summary)
            .setNegativeButton("Schließen", null)
        if (report.valid && payload != null) {
            val requirement = memory.baseRequirement()
            val compatible = !requirement.required || (
                payload.manifest.packageId == requirement.packId && payload.manifest.version >= requirement.minimumVersion
            )
            if (compatible) {
                builder.setPositiveButton("ATOMAR INSTALLIEREN") { _, _ -> installBasePackage(payload) }
            } else {
                builder.setMessage(summary + "\n\nINSTALL BLOCKIERT: Aktives Profil benötigt ${requirement.packId} >= v${requirement.minimumVersion}.")
            }
        }
        builder.show()
        if (::externalKnowledgeStatus.isInitialized) {
            externalKnowledgeStatus.text = if (report.valid && m != null) {
                "BASE validiert: ${m.packageId} v${m.version} · ${m.surfaceCount} surfaces · ${m.macroCount} Makros"
            } else "BASE abgelehnt · ${report.errors.size} Fehler"
        }
    }

    private fun installBasePackage(payload: KiraBasePackagePayload) {
        stopAllForProfileSwitch()
        saveLiveIdentity()
        if (::externalKnowledgeStatus.isInitialized) externalKnowledgeStatus.text = "BASE installiere ${payload.manifest.packageId} v${payload.manifest.version}…"
        thread(name = "Kira-BasePackage-Install") {
            val result = BaseKnowledgeRepository(this).installPackage(payload)
            runOnUiThread {
                if (result.success) {
                    reloadLearnedIdentity()
                    refreshDreamKnowledgeBridge()
                    externalKnowledgeStatus.text = "BASE aktiv: ${result.manifest.packId} v${result.manifest.version} · ${result.manifest.surfaceCount} surfaces · ${result.manifest.macroCount} Makros"
                } else {
                    externalKnowledgeStatus.text = "BASE-Install fehlgeschlagen: ${result.message}"
                }
                updateMemoryStats()
                refreshMemoryPage()
            }
        }
    }

    private fun rollbackBasePackage() {
        stopAllForProfileSwitch()
        saveLiveIdentity()
        thread(name = "Kira-BasePackage-Rollback") {
            val result = BaseKnowledgeRepository(this).rollback()
            runOnUiThread {
                if (result.success) { reloadLearnedIdentity(); refreshDreamKnowledgeBridge() }
                if (::externalKnowledgeStatus.isInitialized) externalKnowledgeStatus.text = result.message
                updateMemoryStats(); refreshMemoryPage()
            }
        }
    }

    private fun removeBasePackage() {
        stopAllForProfileSwitch()
        saveLiveIdentity()
        thread(name = "Kira-BasePackage-Remove") {
            val result = BaseKnowledgeRepository(this).removeActive()
            runOnUiThread {
                if (result.success) { reloadLearnedIdentity(); refreshDreamKnowledgeBridge() }
                if (::externalKnowledgeStatus.isInitialized) externalKnowledgeStatus.text = result.message
                updateMemoryStats(); refreshMemoryPage()
            }
        }
    }

    private fun showForgeIntermediateDryRun(result: ForgeIntermediateJson.ParseResult) {
        val plan = result.plan
        val issuePreview = result.issues.take(8).joinToString("\n") {
            val prefix = if (it.severity == ForgeValidationIssue.Severity.ERROR) "ERROR" else "WARN"
            "$prefix ${it.code}: ${it.message}"
        }
        val summary = buildString {
            append("Format: ").append(result.document?.format ?: "—")
            append("\nSchema: ").append(result.document?.schemaVersion ?: 0)
            append("\nPackage: ").append(plan?.packageInfo?.id ?: "—")
            if (plan != null) append(" v").append(plan.packageInfo.version)
            append("\nNamespace: ").append(plan?.packageInfo?.namespace ?: "—")
            append("\nSource: ").append(plan?.source?.id ?: "—")
            append("\nAdapter: ").append(plan?.source?.adapterId ?: "—")
            append("\nAtoms/Sequences/Makros: ")
                .append(result.document?.atoms?.size ?: 0).append(" / ")
                .append(result.document?.sequences?.size ?: 0).append(" / ")
                .append(result.document?.macroCandidates?.size ?: 0)
            append("\nRelationen: ").append(result.document?.relations?.size ?: 0)
            append("\nVectors: ").append(result.document?.vectors?.size ?: 0)
            append("\nTraits: ").append(result.document?.traits?.size ?: 0)
            append("\nCompiled nodes: ").append(plan?.nodes?.size ?: 0)
            append("\nHöchste Tiefe: ").append(plan?.highestDepth ?: 0).append(" · ").append(plan?.highestTier ?: "—")
            append("\nWarnings: ").append(plan?.warnings?.size ?: 0).append(" · Errors: ").append(result.issues.count { it.severity == ForgeValidationIssue.Severity.ERROR })
            if (issuePreview.isNotBlank()) append("\n\n").append(issuePreview)
            if (result.valid) append("\n\nVALIDIERT. Der Ofen kann dieses Dokument jetzt mit kira_base_package_v1.py in ein checksummiertes .kirabase Runtime-Paket materialisieren; installiert wird ausschließlich das fertige BASE-Paket.")
        }
        AlertDialog.Builder(this)
            .setTitle(if (result.valid) "Forge Intermediate v1 — OK" else "Forge Intermediate v1 — abgelehnt")
            .setMessage(summary)
            .setPositiveButton("Schließen", null)
            .show()
        if (::externalKnowledgeStatus.isInitialized) {
            externalKnowledgeStatus.text = if (result.valid) {
                "Forge v1 validiert: ${plan?.packageInfo?.id} · ${plan?.nodes?.size ?: 0} Nodes · ${plan?.highestTier ?: "—"}"
            } else "Forge v1 abgelehnt · ${result.issues.count { it.severity == ForgeValidationIssue.Severity.ERROR }} Fehler"
        }
    }

    private fun showExternalKnowledgeDryRun(report: ExternalImportReport) {
        if (!::externalKnowledgeStatus.isInitialized) return
        val issuePreview = report.issues.take(5).joinToString("\n") { issue ->
            "${if (issue.severity == ImportIssue.Severity.ERROR) "ERROR" else "WARN"}: ${issue.message}"
        }
        externalKnowledgeStatus.text = if (report.valid) {
            "Dry-Run OK: ${report.packId} v${report.packVersion} · ${report.macrosCompiled} Makros · ${report.finalRelations} Relationen"
        } else {
            "Dry-Run FEHLER: ${report.errors.size} Error(s) · ${report.warnings.size} Warnung(en)"
        }

        val summary = buildString {
            append("Quelle: ").append(report.sourceId.ifBlank { "—" })
            if (report.sourceVersion.isNotBlank()) append(" ").append(report.sourceVersion)
            append("\nBase: ").append(report.packId.ifBlank { "—" }).append(" v").append(report.packVersion)
            append("\nNodes: ").append(report.nodesRead)
            append("\nMakros: ").append(report.macrosCompiled)
            append("\nExplizite Relationen: ").append(report.explicitRelationsRead)
            append("\nEmbedding-Relationen: ").append(report.embeddingRelationsDerived)
            append("\nFinale Relationen: ").append(report.finalRelations)
            append("\nWarnings: ").append(report.warnings.size).append(" · Errors: ").append(report.errors.size)
            if (issuePreview.isNotBlank()) append("\n\n").append(issuePreview)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (report.valid) "External Knowledge — Dry-Run OK" else "External Knowledge — abgelehnt")
            .setMessage(summary)
            .setNegativeButton("Schließen", null)

        val compiledPack = report.compiledPack
        if (report.valid && compiledPack != null) {
            val requirement = memory.baseRequirement()
            val compatible = !requirement.required || (
                report.packId == requirement.packId && report.packVersion >= requirement.minimumVersion
            )
            if (compatible) {
                builder.setPositiveButton("BASE INSTALLIEREN") { _, _ -> installExternalBasePack(compiledPack) }
            } else {
                builder.setMessage(summary + "\n\nINSTALL BLOCKIERT: Aktives Profil benötigt ${requirement.packId} >= v${requirement.minimumVersion}. Ein anderer Base-Pack würde dessen DERIVED-Wissen quarantinen.")
            }
        }
        builder.show()
    }

    private fun installExternalBasePack(pack: CompiledBasePack) {
        stopAllForProfileSwitch()
        saveLiveIdentity()
        externalKnowledgeStatus.text = "External Knowledge: installiere ${pack.packId} v${pack.version}…"
        thread(name = "Equinox-BasePack-Install") {
            val result = BaseKnowledgeRepository(this).install(pack)
            runOnUiThread {
                if (result.success) {
                    reloadLearnedIdentity()
                    refreshDreamKnowledgeBridge()
                    externalKnowledgeStatus.text = "BASE installiert: ${result.manifest.packId} v${result.manifest.version} · ${result.manifest.macroCount} Makros · ${result.manifest.relationCount} Relationen"
                } else {
                    externalKnowledgeStatus.text = "BASE-Install fehlgeschlagen: ${result.message}"
                }
                updateMemoryStats()
            }
        }
    }

    private fun buildChatOverlay(rootFrame: FrameLayout) {
        chatOverlay = FrameLayout(this).apply {
            visibility = View.VISIBLE
            setBackgroundColor(Color.TRANSPARENT)
        }

        chatContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(8))
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Kept as hidden state carriers for compatibility with the existing status/update path.
        chatStatus = TextView(this).apply { visibility = View.GONE }
        chatStyleButton = TextView(this).apply { visibility = View.GONE }

        chatMessages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(62), dp(2), dp(12))
        }
        chatScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(Color.TRANSPARENT)
            addView(chatMessages, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        chatContent.addView(chatScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        chatComposer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = roundedBackground(Color.argb(205, 0, 0, 0), 16f, Color.argb(44, 0, 255, 0), 1)
        }
        val prompt = TextView(this).apply {
            text = EquinoxTerminalIdentity.COMPOSER_PREFIX
            textSize = 19f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.rgb(0, 255, 0))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(10), 0)
        }
        chatComposer.addView(prompt, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        chatInput = EditText(this).apply {
            hint = ""
            minLines = 1
            maxLines = 4
            textSize = 16f
            includeFontPadding = false
            setPadding(0, dp(7), 0, dp(7))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine(true)
            setHorizontallyScrolling(true)
            background = null
            setOnEditorActionListener { _, actionId, event ->
                val imeSend = actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE
                val enterSend = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP && !event.isShiftPressed
                if (imeSend || enterSend) {
                    sendChatInput()
                    true
                } else false
            }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP && !event.isShiftPressed) {
                    sendChatInput()
                    true
                } else false
            }
        }
        chatComposer.addView(chatInput, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))

        // Minimal fallback sender: no button chrome, only a terminal arrow. This
        // keeps touch sending reliable even when a keyboard does not emit IME_SEND.
        chatSendButton = TextView(this).apply {
            text = "→"
            textSize = 21f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(0, 255, 0))
            setPadding(dp(10), dp(4), 0, dp(4))
            background = null
            isClickable = true
            isFocusable = true
            contentDescription = "Senden"
            setOnClickListener { sendChatInput() }
        }
        chatComposer.addView(chatSendButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        mainViewDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
            setBackgroundColor(Color.TRANSPARENT)
        }
        fun quickButton(label: String, description: String, action: (View) -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 19f
            letterSpacing = 0.06f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(7), dp(5), dp(7))
            background = null
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action(this)
            }
        }
        textViewModeButton = quickButton("✎", "Text-Chat ein- oder ausblenden") {
            textOutputEnabled = !textOutputEnabled
            persistModalityRouting()
            applyOutputRouting(syncSensors = false)
            if (textOutputEnabled) refocusComposer(showKeyboard = false)
        }
        musicViewModeButton = quickButton("▧", "Video-Einstellungen öffnen") { openSettingsAction?.invoke(SettingsScreen.VIDEO) }
        videoViewModeButton = quickButton("🚪", "Das Tor zum Jetzt ein- oder ausschalten") {
            videoOutputEnabled = !videoOutputEnabled
            persistModalityRouting()
            applyOutputRouting(syncSensors = false)
        }
        mainViewDock.addView(textViewModeButton, weightedButtonParams())
        mainViewDock.addView(musicViewModeButton, weightedButtonParams())
        mainViewDock.addView(videoViewModeButton, weightedButtonParams())

        chatRule = View(this).apply { setBackgroundColor(Color.rgb(0, 84, 0)) }
        // v0.8.4: no secondary view selector at the bottom. Every renderer/capability
        // is composed from the single Output menu at the top.
        mainViewDock.visibility = View.GONE
        chatContent.addView(chatRule, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ))
        chatContent.addView(chatComposer)
        chatOverlay.addView(chatContent, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        rootFrame.addView(chatOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        applyChatDisplayMode()
        refreshMainViewDock()
        applyLayerComposition()
        renderChatHistory()
    }

    private fun setChatVisible(visible: Boolean) {
        // Legacy hidden control: preserve behavior without hiding the shared composer/nav.
        textOutputEnabled = visible
        persistModalityRouting()
        applyOutputRouting(syncSensors = true)
        renderChatHistory()
    }

    private fun setChatDisplayMode(mode: ChatDisplayMode) {
        chatDisplayMode = mode
        chatEngine.setDisplayMode(mode)
        applyChatDisplayMode()
        renderChatHistory()
    }

    private fun applyChatDisplayMode() {
        if (!::chatOverlay.isInitialized) return
        chatStyleButton.text = chatDisplayMode.name
        when (chatDisplayMode) {
            ChatDisplayMode.MATRIX -> {
                chatInput.setTextColor(Color.rgb(0, 255, 0))
                chatInput.setHintTextColor(Color.rgb(0, 92, 0))
                chatInput.typeface = Typeface.MONOSPACE
                chatInput.background = null
            }
            ChatDisplayMode.CLEAN -> {
                chatInput.setTextColor(Color.rgb(232, 236, 240))
                chatInput.setHintTextColor(Color.rgb(105, 112, 120))
                chatInput.typeface = Typeface.MONOSPACE
                chatInput.background = null
            }
            ChatDisplayMode.RAW -> {
                chatInput.setTextColor(Color.rgb(188, 205, 188))
                chatInput.setHintTextColor(Color.rgb(70, 82, 70))
                chatInput.typeface = Typeface.MONOSPACE
                chatInput.background = null
            }
        }
        refreshMainViewDock()
        applyLayerComposition()
        refreshChatStatus()
    }

    private fun refreshMainViewDock() {
        if (!::mainViewDock.isInitialized) return
        fun style(button: TextView, enabled: Boolean) {
            button.setTextColor(if (enabled) Color.rgb(0, 255, 0) else Color.rgb(92, 108, 96))
            button.typeface = Typeface.create(Typeface.MONOSPACE, if (enabled) Typeface.BOLD else Typeface.NORMAL)
            button.background = if (enabled) roundedBackground(Color.argb(26, 0, 255, 0), 12f, Color.argb(78, 0, 255, 0), 1) else null
            button.alpha = if (enabled) 1f else 0.68f
        }
        style(textViewModeButton, textOutputEnabled)
        style(musicViewModeButton, mediaBackgroundEnabled || idFlowOutputEnabled || idWaterEnabled)
        style(videoViewModeButton, videoOutputEnabled)
    }

    /**
     * v0.8.3 has one compositing stage rather than mutually exclusive views.
     * Background medium, Now Gate, ID-flow and Kira glass chat may coexist.
     */
    private fun applyLayerComposition() {
        // Settings owns primary output visibility. FEATURE may configure effects, but it
        // cannot bypass the currently permitted primary output surfaces.
        val filmEnabled = OutputCapability.FILM in activeOutputCapabilities
        val visualStageEnabled = textOutputEnabled || videoOutputEnabled || filmEnabled
        if (::mediaBackdropView.isInitialized) {
            mediaBackdropView.setMediaEnabled(visualStageEnabled && mediaBackgroundEnabled && mediaBackdropView.hasMedia())
            mediaBackdropView.setIdWaterEnabled(visualStageEnabled && idWaterEnabled)
            mediaBackdropView.setWaterStrength(idWaterStrength)
            mediaBackdropView.setNowGateEnabled(visualStageEnabled && nowGateEffectEnabled)
            mediaBackdropView.setNowGateSpeed(nowGateSpeed)
        }
        // ID-flow is an effect layer. In AUDIO-only output it is suppressed even when
        // its FEATURE switch remains stored as enabled.
        if (::dreamWorldView.isInitialized) {
            val showDream = visualStageEnabled && dreamVisualEnabled && dreamPlaybackRunning
            if (showDream) {
                dreamWorldView.setWorldState(dreamRuntime.snapshot(), animate = true)
            } else {
                dreamWorldView.visibility = View.GONE
            }
        }
        if (::matrixRainView.isInitialized) {
            val showIdFlow = visualStageEnabled && idFlowOutputEnabled
            matrixRainView.visibility = if (showIdFlow) View.VISIBLE else View.GONE
            matrixRainView.setGlyphsVisible(showIdFlow)
            matrixRainView.alpha = if (showIdFlow) 0.82f else 1f
        }
        if (::chatScroll.isInitialized) chatScroll.visibility = if (textOutputEnabled) View.VISIBLE else View.INVISIBLE
        if (::chatComposer.isInitialized) chatComposer.visibility = if (textOutputEnabled && textInputEnabled) View.VISIBLE else View.GONE
        if (::chatRule.isInitialized) chatRule.visibility = if (textOutputEnabled) View.VISIBLE else View.GONE
        chatVisible = textOutputEnabled
        refreshMainViewDock()
        syncExpressionRenderers()
    }

    private fun currentCoreStimulusMask(): CoreStimulusMask = outputMaskForCoreMode(coreOutputMode)

    private fun unifiedExpressionWorkingSnapshot(): WorkingSetSnapshot {
        val mask = combinedActiveOutputMask()
        val raw = memory.unifiedWorkingSnapshot()
        val sensor = filterSensorSnapshot(raw, mask)
        val mind = if (mask.text) mindRepository.workingSnapshot() else WorkingSetSnapshot(emptyList(), emptyList())
        return mergeWorkingSnapshots(sensor, mind)
    }

    private fun filterSensorSnapshot(snapshot: WorkingSetSnapshot, mask: CoreStimulusMask = currentCoreStimulusMask()): WorkingSetSnapshot {
        val nodes = snapshot.nodes.filter { node ->
            when (KiraModalityClassifier.familyOf(node.modality)) {
                KiraModalityFamily.TEXT -> mask.text
                KiraModalityFamily.AUDIO -> audioInputEnabled && mask.audio
                KiraModalityFamily.VISION -> cameraInputEnabled && mask.camera
                // v0.9.3 cross-modal memory is one universal macro family. The currently
                // learned joint nodes are audio+vision pairs, so both input gates must pass.
                KiraModalityFamily.CROSSMODAL -> audioInputEnabled && cameraInputEnabled && mask.audio && mask.camera
                KiraModalityFamily.FORGE -> true
                KiraModalityFamily.OTHER -> true
            }
        }
        val allowed = nodes.map { it.id }.toHashSet()
        return WorkingSetSnapshot(
            nodes = nodes,
            exchanges = snapshot.exchanges.filter { it.fromId in allowed && it.toId in allowed },
            predictions = snapshot.predictions.filter { it.sourceId in allowed && it.targetId in allowed },
            crossModalLinks = snapshot.crossModalLinks.filter { link ->
                link.jointId in allowed || (link.audioId in allowed && link.visualId in allowed)
            },
        )
    }

    private fun unifiedExpressionIds(): List<Long> {
        val ids = linkedSetOf<Long>()
        val mask = combinedActiveOutputMask()
        if (mask.text && ::mindRepository.isInitialized) ids += mindRepository.activeIdSnapshot(56)
        val working = runCatching { unifiedExpressionWorkingSnapshot() }.getOrNull()
        working?.nodes.orEmpty().take(28).forEach { node ->
            ids += EquinoxId64.stableId("working_node", "${node.modality}:${node.id}:${node.abstractionLevel}")
        }
        working?.exchanges.orEmpty().take(24).forEach { edge ->
            ids += EquinoxId64.stableId("working_relation", "${edge.fromId}:${edge.toId}")
        }
        return ids.filter { it != 0L }.distinct().take(64)
    }

    private fun syncExpressionRenderers() {
        val working = runCatching { unifiedExpressionWorkingSnapshot() }.getOrNull()
        val ids = unifiedExpressionIds()
        val videoWorking = runCatching { workingSnapshotForTarget(MasterOutputTarget.VIDEO) }.getOrNull()
        val videoIds = expressionIdsForTarget(MasterOutputTarget.VIDEO)
        applyOperationalOutputTuning(working, ids)
        if (::mediaBackdropView.isInitialized) {
            mediaBackdropView.setNowGateSpeed(nowGateSpeed)
            mediaBackdropView.setNowGateState(
                videoWorking,
                animate = (videoOutputEnabled || OutputCapability.FILM in activeOutputCapabilities) && nowGateEffectEnabled && videoWorking?.nodes?.isNotEmpty() == true,
            )
            mediaBackdropView.setIdWaterState(
                videoIds,
                animate = idWaterEnabled && isEquinoxWorking(),
            )
        }
        if (::dialecticTierSlabView.isInitialized) {
            dialecticTierSlabView.visibility = if (dialecticTierVisible) View.VISIBLE else View.GONE
            dialecticTierSlabView.setState(dialecticState(working))
        }
        if (::macroDepthSlabView.isInitialized) {
            macroDepthSlabView.visibility = if (macroDepthVisible) View.VISIBLE else View.GONE
            macroDepthSlabView.setState(coreOutputMode, working)
        }
        if (::matrixRainView.isInitialized) {
            matrixRainView.setSpeedMultiplier(idFlowSpeed)
            matrixRainView.setPulseEnabled(idFlowPulse)
            matrixRainView.setGlyphsVisible(idFlowOutputEnabled)
            // The ID matrix owns its own persistent lanes. New turns only replace ID
            // identities; the physical stream never reloads between questions.
            matrixRainView.setFlowState(ids, animate = idFlowOutputEnabled && isEquinoxWorking())
        }
    }

    private fun isEquinoxWorking(): Boolean {
        val mask = combinedActiveOutputMask()
        return chatSending ||
            (mask.audio && audioInputEnabled && running) ||
            (mask.camera && cameraInputEnabled && cameraRunning) ||
            memoryPlaybackRunning || dreamPlaybackRunning || hybridPlaybackRunning
    }

    private fun refreshChatStatus() {
        if (!::chatStatus.isInitialized) return
        val base = if (mindRepository.forgePack != null) "BASE ON" else "BASE OFF"
        val stats = runCatching { memory.stats() }.getOrNull()
        val textDepth = runCatching {
            memory.unifiedWorkingSnapshot().nodes
                .filter { KiraModalityClassifier.familyOf(it.modality) == KiraModalityFamily.TEXT }
                .maxOfOrNull { it.abstractionLevel } ?: 0
        }.getOrDefault(0)
        val memories = stats?.persistentTextMemories ?: 0
        val candidates = stats?.persistentCandidates ?: 0
        chatStatus.text = "KIRA · ${coreOutputMode.label} · ${chatDisplayMode.name}\n$base · ${mindRepository.localCount()} rel · $memories mem · $candidates cand · depth $textDepth"
    }

    private fun sendChatInput() {
        if (!::chatInput.isInitialized || chatSending || !textInputEnabled) return
        val text = chatInput.text.toString().trim()
        if (text.isEmpty()) {
            refocusComposer()
            return
        }

        if (handleTerminalCommand(text)) {
            chatInput.setText("")
            renderChatHistory()
            refocusComposer()
            return
        }

        lastDreamText = text
        chatSending = true
        chatInput.setText("")
        syncExpressionRenderers()
        if (::chatSendButton.isInitialized) {
            chatSendButton.isEnabled = false
            chatSendButton.text = "…"
        }

        val seedIds = ChatTextLogic.activityIdsForInput(text)
        val learnText = textInputEnabled && !KiraDialoguePolicy.isIdentityControlInput(text)
        // v0.9.2.1: identity protocol is fixed dialogue control and is not written as learned knowledge.
        // v0.9.2: a text turn is ingested once by the unified memory. This writes both the
        // recursive F→Ω+n evidence and the persistent semantic surface used for recall.
        memory.observeTextTurn(text, learn = learnText)
        val contextSignals = currentTextContextSignals()
        val moduleActive = activeOperationalModules()
        val moduleTuning = currentModuleTuning()
        val moduleWorking = runCatching { workingSnapshotForTarget(MasterOutputTarget.TEXT) }.getOrNull()
        val moduleIds = expressionIdsForTarget(MasterOutputTarget.TEXT).ifEmpty { seedIds }
        val moduleHints = chatEngine.recallHints(text, 5)
        val moduleContext = KiraModuleContext(
            text = text,
            activeIds = moduleIds,
            memoryHints = moduleHints,
            state = stateVector(moduleWorking),
        )
        if (::matrixRainView.isInitialized) matrixRainView.updateActiveIds(seedIds)

        thread(name = "Equinox-Chat-Submit") {
            val moduleGeneration = runCatching {
                operationalRegistry.generateText(moduleActive, moduleContext, moduleTuning)
            }.getOrNull()
            val result = runCatching {
                chatEngine.submit(
                    text, contextSignals, moduleGeneration,
                    learnToMind = learnText,
                    dialecticState = decisionStateForTarget(MasterOutputTarget.TEXT).toDialectic(),
                )
            }
            val activeIds = mindRepository.activeIdSnapshot()
            runOnUiThread {
                chatSending = false
                if (::chatSendButton.isInitialized) {
                    chatSendButton.isEnabled = true
                    chatSendButton.text = "→"
                }
                if (::matrixRainView.isInitialized) matrixRainView.updateActiveIds(activeIds.ifEmpty { seedIds })
                syncExpressionRenderers()

                result.onSuccess {
                    terminalNotice = null
                    renderChatHistory()
                    runCatching {
                        if (videoOutputEnabled && ::mediaBackdropView.isInitialized) {
                            mediaBackdropView.setNowGateState(unifiedExpressionWorkingSnapshot(), animate = true)
                        }
                        if (musicOutputEnabled && unifiedAudio.isRunning()) pushContinuousOrchestraState()
                    }
                }.onFailure { error ->
                    renderChatHistory()
                    val fallback = EquinoxChatMessage(
                        id = EquinoxId64.stableId("chat_error", "${System.currentTimeMillis()}:${error.message ?: "error"}"),
                        timestampMs = System.currentTimeMillis(),
                        role = ChatRole.EQUINOX,
                        text = "◇ fehler",
                        kind = ChatReplyKind.UNSTABLE,
                        meta = "runtime_error:${error.message ?: error::class.java.simpleName}",
                    )
                    appendChatMessage(fallback)
                    refreshChatStatus()
                    chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                }
                refocusComposer()
            }
        }
    }

    private fun refocusComposer(showKeyboard: Boolean = true) {
        if (!::chatInput.isInitialized || !textInputEnabled) return
        chatInput.post {
            chatInput.isFocusableInTouchMode = true
            chatInput.requestFocus()
            chatInput.setSelection(chatInput.text.length)
            if (showKeyboard) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(chatInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun handleTerminalCommand(text: String): Boolean {
        if (!text.startsWith("/")) return false
        val parts = text.trim().split(Regex("\\s+"), limit = 3)
        return when (parts.firstOrNull()?.lowercase()) {
            "/render" -> {
                val requested = parts.getOrNull(1)?.lowercase()
                val mode = when (requested) {
                    "matrix", "terminal", "green" -> ChatDisplayMode.MATRIX
                    "clean", "minimal" -> ChatDisplayMode.CLEAN
                    "raw", "debug" -> ChatDisplayMode.RAW
                    else -> null
                }
                if (mode == null) {
                    terminalNotice = "render modes: matrix | clean | raw"
                } else {
                    setChatDisplayMode(mode)
                    terminalNotice = "render=${mode.name.lowercase()}"
                }
                true
            }
            else -> false
        }
    }

    private fun renderChatHistory() {
        if (!::chatMessages.isInitialized) return
        chatMessages.removeAllViews()
        val history = chatEngine.history()

        if (history.isEmpty()) {
            if (textOutputEnabled) {
                addIdentityLine(ChatRole.EQUINOX, if (chatDisplayMode == ChatDisplayMode.RAW) "bereit · basis" else "bereit")
            }
        } else {
            var lastUserText = ""
            history.forEach { message ->
                if (message.role == ChatRole.USER) {
                    lastUserText = message.text
                    appendChatMessage(message)
                } else if (textOutputEnabled) {
                    val visibleText = compactLegacyChatSurface(message, lastUserText)
                    val trace = message.trace.ifBlank {
                        ChatTextLogic.relationTrace(
                            lastUserText,
                            ChatReplyPlan(message.kind ?: ChatReplyKind.UNSTABLE, "", occurrence = 1),
                        )
                    }
                    appendChatMessage(message, visibleText, trace)
                }
            }
        }
        terminalNotice?.takeIf { textOutputEnabled }?.let { notice ->
            addIdentityLine(ChatRole.EQUINOX, notice)
        }
        refreshChatStatus()
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }


    private fun compactLegacyChatSurface(message: EquinoxChatMessage, lastUserText: String): String {
        if (message.kind == ChatReplyKind.MODULE || message.kind == ChatReplyKind.QUESTION || message.kind == ChatReplyKind.IDENTITY || message.kind == ChatReplyKind.NATIVE) return message.text
        val text = message.text.trim()
        return when {
            text.startsWith("◆ antwort · ") -> text.removePrefix("◆ antwort · ").substringBefore(" · kontext ↔").trim()
            text.startsWith("◇ offen · ") -> "noch offen"
            text.startsWith("◇ lernen · ") -> "gelernt"
            text.startsWith("◇ verbinden · ") -> "verbunden"
            text.startsWith("↻ erinnern · ") -> "erinnert"
            text.startsWith("◆ wissen · ") -> "bekannt"
            "surface=" in text || "concepts=" in text || text.any { it.isDigit() } ->
                ChatTextLogic.structuralReply(lastUserText, ChatReplyPlan(message.kind ?: ChatReplyKind.UNSTABLE, ""))
            else -> text
        }
    }

    private fun addRelationTraceLine(trace: String) {
        if (!relationTraceVisible || trace.isBlank()) return
        val line = TextView(this).apply {
            text = trace
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            setTextColor(Color.rgb(108, 142, 116))
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = roundedBackground(Color.argb(170, 0, 0, 0), 12f, Color.argb(38, 120, 150, 126), 1)
            setTextIsSelectable(true)
        }
        chatMessages.addView(line, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(18)
            marginEnd = dp(54)
            bottomMargin = dp(5)
        })
    }

    private fun identityStyledText(role: ChatRole, body: String): SpannableStringBuilder {
        val name = if (role == ChatRole.USER) EquinoxChatEngine.CURATOR_PREFIX else EquinoxChatEngine.KIRA_PREFIX
        val out = SpannableStringBuilder()
        val start = out.length
        out.append(name)
        val end = out.length
        out.setSpan(ForegroundColorSpan(Color.rgb(0, 255, 0)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.append("  ")
        val bodyStart = out.length
        out.append(body)
        val bodyColor = when (chatDisplayMode) {
            ChatDisplayMode.MATRIX -> if (role == ChatRole.USER) Color.rgb(205, 235, 210) else Color.rgb(154, 220, 165)
            ChatDisplayMode.CLEAN -> if (role == ChatRole.USER) Color.rgb(232, 236, 240) else Color.rgb(190, 208, 194)
            ChatDisplayMode.RAW -> Color.rgb(155, 205, 160)
        }
        out.setSpan(ForegroundColorSpan(bodyColor), bodyStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    private fun addIdentityLine(role: ChatRole, body: String) {
        val glassAlpha = when (chatDisplayMode) {
            ChatDisplayMode.MATRIX -> 194
            ChatDisplayMode.CLEAN -> 214
            ChatDisplayMode.RAW -> 182
        }
        val stroke = if (role == ChatRole.USER) Color.argb(72, 0, 255, 0) else Color.argb(48, 0, 210, 80)
        val bubble = TextView(this).apply {
            text = identityStyledText(role, body)
            textSize = if (chatDisplayMode == ChatDisplayMode.RAW) 12.5f else 15f
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(Color.argb(glassAlpha, 0, 0, 0), 14f, stroke, 1)
            setTextIsSelectable(true)
        }
        chatMessages.addView(bubble, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
            if (role == ChatRole.USER) marginStart = dp(34) else marginEnd = dp(34)
        })
    }

    private fun appendChatMessage(message: EquinoxChatMessage, visibleText: String = message.text, trace: String = message.trace) {
        if (!::chatMessages.isInitialized) return
        if (message.role == ChatRole.EQUINOX && !textOutputEnabled) return
        addIdentityLine(message.role, visibleText)
        if (message.role == ChatRole.EQUINOX) addRelationTraceLine(trace)
    }

    private fun buildMindPage() {
        mindPage.addView(label("UNIFIED MIND — LANGUAGE / LOOM / KERNEL", 20f, 18).apply {
            setTextColor(Color.rgb(120, 235, 205))
        })
        mindPage.addView(label(
            "Die App läuft eigenständig: Equinox Language v0.3 (Tree/Mirror + Forest/Loom + universelle Compute-Fabric) erzeugt lokal denselben numerischen ID-Raum. Ein externer Forge-v0.56-Ofen ist optional und liefert nur einen signierten .eqkernel.json BASE-Layer; Hugging Face läuft nicht auf dem Telefon.",
            12f, 5
        ).apply { setTextColor(Color.LTGRAY) })

        mindStatus = label("MIND: ${mindRepository.status()}", 12f, 6).apply {
            setTextColor(Color.rgb(185, 225, 215))
            typeface = Typeface.MONOSPACE
        }
        mindPage.addView(mindStatus)

        mindSource = EditText(this).apply {
            setText(
                "FABRIC local CELLS 2\n" +
                    "WORK vogel Vogel faehigkeit fliegen MIRROR tree:Vogel HOLD_INFINITY\n" +
                    "WORK omega Omega verfeinerung naechste_schranke RELATE process:Omega HOLD_INFINITY\n" +
                    "RUN"
            )
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.rgb(12, 15, 21))
            typeface = Typeface.MONOSPACE
            textSize = 13f
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
            hint = "TREE / FOREST / FABRIC source"
        }
        mindPage.addView(mindSource, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(250)
        ).apply { topMargin = dp(8) })

        val runRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        runRow.addView(Button(this).apply {
            text = "RUN LOCAL"
            setOnClickListener {
                val source = mindSource.text.toString()
                mindStatus.text = "MIND: kompiliert / führt aus…"
                thread(name = "Equinox-LocalLanguage") {
                    val result = runCatching { mindLanguage.run(source) }
                    runOnUiThread {
                        result.onSuccess { run ->
                            mindRepository.commitLanguage(run)
                            surfaceVideo.setModeLabel("MIND")
                            surfaceVideo.setWorkingState(mindRepository.workingSnapshot())
                            mindStatus.text = "MIND: ${run.summary}\n${mindRepository.status()}"
                        }.onFailure {
                            mindStatus.text = "MIND ERROR: ${it.message ?: it::class.java.simpleName}"
                        }
                    }
                }
            }
        }, weightedButtonParams())
        runRow.addView(Button(this).apply {
            text = "TOR ZUM JETZT ← MIND"
            setOnClickListener {
                surfaceVideo.setModeLabel("MIND")
                surfaceVideo.setPresentationMode(UnifiedPresentationMode.HYPNO)
                surfaceVideo.setWorkingState(mindRepository.workingSnapshot())
                mindStatus.text = "MIND: Working-State an das Tor zum Jetzt projiziert\n${mindRepository.status()}"
            }
        }, weightedButtonParams())
        mindPage.addView(runRow, buttonParams(6))

        mindPage.addView(label("CHAT DISPLAY", 15f, 14).apply {
            setTextColor(Color.rgb(125, 235, 175))
        })
        mindPage.addView(label(
            "Der Chat ist nur eine Anzeige-/Eingabeschicht über MIND. MATRIX, CLEAN und RAW verändern ausschließlich die Darstellung; der Verlauf und die numerischen Lernspuren bleiben identisch.",
            11f, 3
        ).apply { setTextColor(Color.LTGRAY) })
        val chatStyleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        ChatDisplayMode.values().forEach { mode ->
            chatStyleRow.addView(Button(this).apply {
                text = mode.name
                setOnClickListener { setChatDisplayMode(mode) }
            }, weightedButtonParams())
        }
        mindPage.addView(chatStyleRow, buttonParams(4))
        mindPage.addView(Button(this).apply {
            text = "CHAT VERLAUF LEEREN"
            setOnClickListener {
                chatEngine.clear()
                renderChatHistory()
                mindStatus.text = "MIND: Chat-Verlauf geleert · numerische MIND-Spuren bleiben erhalten\n${mindRepository.status()}"
            }
        }, buttonParams(4))

        mindPage.addView(label("LEGACY FORGE KERNEL — v0.56", 15f, 14).apply {
            setTextColor(Color.rgb(205, 182, 245))
        })
        mindPage.addView(label(
            "Kompatibilitätspfad für bestehende .eqkernel-Dateien. Neue Teacher erzeugen Forge Intermediate v1; der Ofen kompiliert daraus .kirabase, das Kira atomar mountet.",
            12f, 4
        ).apply { setTextColor(Color.LTGRAY) })

        val forgeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        forgeRow.addView(Button(this).apply {
            text = "MOUNT .EQKERNEL"
            setOnClickListener { forgeKernelImportLauncher.launch(arrayOf("application/json", "text/json", "text/plain", "*/*")) }
        }, weightedButtonParams())
        forgeRow.addView(Button(this).apply {
            text = "UNMOUNT BASE"
            setOnClickListener {
                mindRepository.unmountForge()
                refreshDreamKnowledgeBridge()
                mindStatus.text = "MIND: Forge BASE ausgehängt\n${mindRepository.status()}"
                refreshChatStatus()
                surfaceVideo.setWorkingState(mindRepository.workingSnapshot())
            }
        }, weightedButtonParams())
        mindPage.addView(forgeRow, buttonParams(5))

        val queryInput = EditText(this).apply {
            hint = "Forge query: <surface> <relation-id>  z.B. Eule 123456…"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true)
        }
        mindPage.addView(queryInput, buttonParams(5))
        mindPage.addView(Button(this).apply {
            text = "QUERY FORGE BASE"
            setOnClickListener {
                val parts = queryInput.text.toString().trim().split(Regex("\\s+"), limit = 2)
                val runtime = mindRepository.forgeRuntime
                if (runtime == null || parts.size != 2) {
                    mindStatus.text = "MIND: Forge BASE fehlt oder Query ist ungültig"
                    return@setOnClickListener
                }
                val relationId = parts[1].toLongOrNull()
                val ids = runtime.surfaceIds(parts[0])
                if (relationId == null || ids.size != 1) {
                    mindStatus.text = "MIND: Surface unbekannt/mehrdeutig oder Relation-ID ungültig"
                    return@setOnClickListener
                }
                val q = runtime.resolve(ids.single(), relationId)
                mindStatus.text = "FORGE QUERY ${q.status}: " + q.values.joinToString { "${it.objectId} [${it.routeKind}]" }
            }
        }, buttonParams(4))

        mindPage.addView(Button(this).apply {
            text = "LOCAL MIND LEEREN"
            setOnClickListener {
                mindRepository.clearLocal()
                mindStatus.text = "MIND: lokaler Language-Layer geleert\n${mindRepository.status()}"
                refreshChatStatus()
                surfaceVideo.setWorkingState(mindRepository.workingSnapshot())
            }
        }, buttonParams(8))

        mindPage.addView(label(
            "Betriebsregel: lokale Language-Prozesse dürfen BASE nicht überschreiben. BASE ist externer Input; lokale Tree/Forest/Fabric-Ergebnisse bleiben ein eigener persistenter Layer. HOLD_INFINITY bedeutet fortsetzbar ohne Fortsetzungszwang.",
            11f, 8
        ).apply { setTextColor(Color.rgb(160, 175, 195)) })
    }

    private fun showForgeKernelDryRun(text: String, pack: ForgeKernelPackAndroid) {
        val message = buildString {
            append("Format: ").append("equinox-unified-numeric-execution-kernel-v1 / 0.55")
            append("\nDigest: ").append(pack.digest)
            append("\nFacts: ").append(pack.facts.size)
            append("\nSurfaces: ").append(pack.surfaces.size)
            append("\nConcept shortcuts: ").append(pack.shortcuts.size)
            append("\nRelation routes: ").append(pack.routes.size)
            append("\nInheritance predicates: ").append(pack.inheritance.size)
            append("\n\nDer Teacher/Forge wird nach dem Mount nicht benötigt.")
        }
        AlertDialog.Builder(this)
            .setTitle("Forge Kernel — Dry-Run OK")
            .setMessage(message)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("BASE MOUNTEN") { _, _ ->
                val mounted = runCatching { mindRepository.mountForge(text) }
                mounted.onSuccess {
                    refreshDreamKnowledgeBridge()
                    mindStatus.text = "MIND: Forge BASE gemountet · ${it.facts.size} Facts · ${it.routes.size} Routes\n${mindRepository.status()}"
                    refreshChatStatus()
                    surfaceVideo.setModeLabel("MIND")
                    surfaceVideo.setWorkingState(mindRepository.workingSnapshot())
                }.onFailure {
                    mindStatus.text = "MIND: Mount fehlgeschlagen · ${it.message}"
                }
            }
            .show()
    }

    private fun buildLabPage() {
        labPage.addView(label("CONTROL BUREAU — DEBUG / ARCHIVE / PARAMETERS", 20f, 18).apply {
            setTextColor(Color.rgb(150, 175, 210))
        })

        labPage.addView(label("PRODUCT RENDERER", 16f, 12).apply {
            setTextColor(Color.rgb(178, 140, 255))
        })
        val productRendererStatus = label("Primär: TOR ZUM JETZT · relationale Zustandsprojektion", 13f, 5).apply {
            setTextColor(Color.rgb(210, 190, 255))
        }
        labPage.addView(productRendererStatus)
        labPage.addView(label(
            "Das Tor zum Jetzt ist die Consumer-Darstellung des relationalen Zustands. STRUCTURE bleibt nur eine temporäre LAB-Vorschau. Tor und Unified Audio lesen denselben normalisierten Präsentationszustand; bei Live-Kamera sogar denselben verzögerten WorkingSetSnapshot. Nichts davon schreibt in MemoryModel zurück.",
            12f,
            4
        ).apply { setTextColor(Color.LTGRAY) })
        val rendererRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val hypnoRendererButton = Button(this).apply { text = "TOR"; isEnabled = false }
        val structureRendererButton = Button(this).apply { text = "STRUCTURE" }
        fun refreshRendererButtons() {
            val hypno = surfaceVideo.presentationMode() == UnifiedPresentationMode.HYPNO
            hypnoRendererButton.isEnabled = !hypno
            structureRendererButton.isEnabled = hypno
            productRendererStatus.text = if (hypno)
                "Primär: TOR ZUM JETZT · relationale Zustandsprojektion"
            else
                "Lab: STRUCTURE · technische Tier-/Gewebe-Projektion"
        }
        hypnoRendererButton.setOnClickListener {
            surfaceVideo.setPresentationMode(UnifiedPresentationMode.HYPNO)
            surfaceVideo.setModeLabel("NOW GATE")
            refreshRendererButtons()
        }
        structureRendererButton.setOnClickListener {
            surfaceVideo.setPresentationMode(UnifiedPresentationMode.STRUCTURE)
            surfaceVideo.setModeLabel("STRUCTURE")
            refreshRendererButtons()
        }
        rendererRow.addView(hypnoRendererButton, weightedButtonParams())
        rendererRow.addView(structureRendererButton, weightedButtonParams())
        labPage.addView(rendererRow, buttonParams(6))
        refreshRendererButtons()

        labPage.addView(label("AUDIO-ENCODER HARD TEST", 16f, 12).apply {
            setTextColor(Color.rgb(145, 210, 255))
        })
        labPage.addView(label(
            "Drei getrennte Alphabete: FFT-Frequenz, festes DIRECT-16-bit und ADAPTIVE. ADAPTIVE nutzt keine FFT und keine festen Bins: ein persistentes Codebook lernt stabile Prototypen aus Zeitbereichsmerkmalen.",
            12f,
            4
        ).apply { setTextColor(Color.LTGRAY) })
        labAudioEncoderStatus = label("Aktiv: ${audioEncoderMode.label}", 13f, 8).apply {
            setTextColor(Color.rgb(180, 220, 255))
        }
        labPage.addView(labAudioEncoderStatus)
        val encoderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val fftEncoderButton = Button(this).apply { text = "FFT" }
        val directEncoderButton = Button(this).apply { text = "DIRECT" }
        val adaptiveEncoderButton = Button(this).apply { text = "ADAPTIVE" }
        fun refreshEncoderButtons() {
            fftEncoderButton.isEnabled = audioEncoderMode != AudioEncoderMode.FFT_FREQUENCY
            directEncoderButton.isEnabled = audioEncoderMode != AudioEncoderMode.DIRECT_PATTERN
            adaptiveEncoderButton.isEnabled = audioEncoderMode != AudioEncoderMode.ADAPTIVE_PATTERN
            labAudioEncoderStatus.text = "Aktiv: ${audioEncoderMode.label}"
            if (::audioEncoderStatus.isInitialized) audioEncoderStatus.text = "Signal-Parser: ${audioEncoderMode.label}"
        }
        fftEncoderButton.setOnClickListener {
            setAudioEncoderMode(AudioEncoderMode.FFT_FREQUENCY)
            refreshEncoderButtons()
        }
        directEncoderButton.setOnClickListener {
            setAudioEncoderMode(AudioEncoderMode.DIRECT_PATTERN)
            refreshEncoderButtons()
        }
        adaptiveEncoderButton.setOnClickListener {
            setAudioEncoderMode(AudioEncoderMode.ADAPTIVE_PATTERN)
            refreshEncoderButtons()
        }
        encoderRow.addView(fftEncoderButton, weightedButtonParams())
        encoderRow.addView(directEncoderButton, weightedButtonParams())
        encoderRow.addView(adaptiveEncoderButton, weightedButtonParams())
        labPage.addView(encoderRow, buttonParams(5))
        refreshEncoderButtons()

        adaptiveQuantizerStatus = label(adaptiveQuantizerText(), 12f, 5).apply {
            setTextColor(Color.rgb(220, 190, 255))
        }
        labPage.addView(adaptiveQuantizerStatus)

        labPage.addView(label("PARALLELER KONVERGENZTEST", 16f, 16).apply {
            setTextColor(Color.rgb(120, 235, 190))
        })
        labPage.addView(label(
            "Im Test sehen FFT, DIRECT und ADAPTIVE exakt dieselben PCM-Frames und lernen gleichzeitig in getrennten Modalitäten. Gemessen werden ID-Neuheit, Wiederholung, Übergangsstabilität, Entropie und Makroaufbau. ADAPTIVE lernt dabei zusätzlich sein eigenes Codebook.",
            12f,
            4
        ).apply { setTextColor(Color.LTGRAY) })

        encoderBenchmarkToggle = CheckBox(this).apply {
            text = "Alle 3 Encoder parallel lernen + messen"
            setTextColor(Color.WHITE)
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                parallelEncoderBenchmarkEnabled = checked
                if (checked) {
                    encoderBenchmark.resetAndStart(memory.stats())
                } else {
                    encoderBenchmark.stop()
                }
                refreshEncoderBenchmarkStatus()
            }
        }
        labPage.addView(encoderBenchmarkToggle)

        encoderBenchmarkStatus = label("Konvergenztest: AUS", 12f, 6).apply {
            setTextColor(Color.rgb(180, 235, 205))
        }
        labPage.addView(encoderBenchmarkStatus)

        val benchmarkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        benchmarkRow.addView(Button(this).apply {
            text = "TEST NEU"
            setOnClickListener {
                encoderBenchmark.resetAndStart(memory.stats())
                parallelEncoderBenchmarkEnabled = true
                encoderBenchmarkToggle.isChecked = true
                refreshEncoderBenchmarkStatus()
            }
        }, weightedButtonParams())
        benchmarkRow.addView(Button(this).apply {
            text = "CSV"
            setOnClickListener {
                encoderBenchmark.updateMemory(memory.stats())
                val f = encoderBenchmark.writeCsvExport()
                refreshEncoderBenchmarkStatus()
                encoderBenchmarkStatus.text = encoderBenchmarkStatus.text.toString() + "\nCSV intern: ${f.name} (${f.length()} Bytes)"
            }
        }, weightedButtonParams())
        benchmarkRow.addView(Button(this).apply {
            text = "STOP"
            setOnClickListener {
                parallelEncoderBenchmarkEnabled = false
                encoderBenchmark.stop()
                encoderBenchmarkToggle.isChecked = false
                refreshEncoderBenchmarkStatus()
            }
        }, weightedButtonParams())
        labPage.addView(benchmarkRow, buttonParams(5))
        refreshEncoderBenchmarkStatus()

        labMemoryStats = label(memoryText(), 13f, 8).apply { setTextColor(Color.rgb(180, 220, 215)) }
        labPage.addView(labMemoryStats)

        graphView = RelationGraphView(this)
        graphView.setDisplayPrefs(displayPrefsStore.load())
        graphView.setSnapshot(outputBridge.snapshot().graph)
        labPage.addView(
            graphView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(480)).apply { topMargin = dp(14) }
        )

        val geometryMemory = GeometryGroundMemory.standard()
        val displayModeLabel = label(
            "Darstellung: 2D · Geometrie-Grundgedächtnis: ${geometryMemory.totalTemplates} Formregeln",
            13f,
            8
        ).apply { setTextColor(Color.rgb(170, 205, 255)) }
        labPage.addView(displayModeLabel)

        val displayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val button2D = Button(this).apply { text = "2D"; isEnabled = false }
        val button3D = Button(this).apply { text = "3D" }
        fun refreshDisplayButtons() {
            val is2D = graphView.getDisplayMode() == GraphDisplayMode.TWO_D
            button2D.isEnabled = !is2D
            button3D.isEnabled = is2D
            displayModeLabel.text =
                "Darstellung: ${if (is2D) "2D" else "3D"} · Geometrie-Grundgedächtnis: ${geometryMemory.totalTemplates} Formregeln"
        }
        button2D.setOnClickListener { graphView.setDisplayMode(GraphDisplayMode.TWO_D); refreshDisplayButtons() }
        button3D.setOnClickListener { graphView.setDisplayMode(GraphDisplayMode.THREE_D); refreshDisplayButtons() }
        displayRow.addView(button2D, weightedButtonParams())
        displayRow.addView(button3D, weightedButtonParams())
        labPage.addView(displayRow, buttonParams(6))

        labPage.addView(label("DARSTELLUNG", 16f, 16).apply { setTextColor(Color.rgb(130, 220, 210)) })
        val currentPrefs = displayPrefsStore.load()
        fun colorField(labelText: String, initial: Int): EditText {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(label(labelText, 13f, 0).apply { setTextColor(Color.LTGRAY) }, weightedLabelParams())
            val input = EditText(this).apply {
                setSingleLine(true)
                setText(displayPrefsStore.toHex(initial))
                textSize = 14f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
            }
            row.addView(input, weightedButtonParams())
            labPage.addView(row)
            return input
        }
        val bgInput = colorField("Hintergrund HEX", currentPrefs.backgroundColor)
        val edgeInput = colorField("Mikro-Kanten HEX", currentPrefs.audioEdgeColor)
        val l1Input = colorField("F HEX", currentPrefs.macroLevel1Color)
        val l2Input = colorField("E HEX", currentPrefs.macroLevel2Color)
        val l3Input = colorField("D HEX", currentPrefs.macroLevel3Color)
        val l4Input = colorField("C+ Basis HEX", currentPrefs.macroLevel4Color)
        val glowCheck = CheckBox(this).apply {
            text = "Glow aktiv"
            isChecked = currentPrefs.glowEnabled
            setTextColor(Color.WHITE)
        }
        labPage.addView(glowCheck)
        val settingsStatus = label("Darstellung: geladen", 13f, 4).apply { setTextColor(Color.rgb(185, 195, 210)) }
        labPage.addView(settingsStatus)
        labPage.addView(Button(this).apply {
            text = "DARSTELLUNG SPEICHERN"
            setOnClickListener {
                val colors = listOf(bgInput, edgeInput, l1Input, l2Input, l3Input, l4Input)
                    .map { displayPrefsStore.parseHexOrNull(it.text.toString()) }
                if (colors.any { it == null }) {
                    settingsStatus.text = "Fehler: HEX als #RRGGBB eingeben"
                } else {
                    val saved = DisplayPrefs(
                        backgroundColor = colors[0]!!,
                        audioEdgeColor = colors[1]!!,
                        macroLevel1Color = colors[2]!!,
                        macroLevel2Color = colors[3]!!,
                        macroLevel3Color = colors[4]!!,
                        macroLevel4Color = colors[5]!!,
                        glowEnabled = glowCheck.isChecked,
                        glowStrength = 90
                    )
                    displayPrefsStore.save(saved)
                    graphView.setDisplayPrefs(saved)
                    settingsStatus.text = "Darstellung gespeichert und angewendet"
                }
            }
        }, buttonParams(6))

        labPage.addView(label("SAVE SLOTS — v0.6", 16f, 16).apply { setTextColor(Color.rgb(130, 220, 210)) })
        val slotStatus = label("3 lokale v0.6-Speicherstände verfügbar", 13f, 5).apply {
            setTextColor(Color.rgb(185, 195, 210))
        }
        labPage.addView(slotStatus)
        for (slot in 1..3) {
            val slotLabel = label(saveSlots.exportSummary(slot), 12f, 6).apply { setTextColor(Color.LTGRAY) }
            labPage.addView(slotLabel)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val saveButton = Button(this).apply {
                text = "S$slot SAVE"
                setOnClickListener {
                    memory.saveNow()
                    val ok = saveSlots.saveToSlot(slot)
                    slotLabel.text = saveSlots.exportSummary(slot)
                    slotStatus.text = if (ok) "Slot $slot gespeichert" else "Slot $slot konnte nicht gespeichert werden"
                }
            }
            val loadButton = Button(this).apply {
                text = "S$slot LOAD"
                setOnClickListener {
                    stopAudio()
                    stopCameraLearning()
                    stopMemoryPlayback(clear = true)
                    val ok = saveSlots.loadFromSlot(slot)
                    if (ok) {
                        memory.reloadFromDisk()
                        updateMemoryStats()
                        refreshGraph()
                        slotStatus.text = "Slot $slot geladen"
                    } else slotStatus.text = "Slot $slot ist leer"
                }
            }
            val deleteButton = Button(this).apply {
                text = "S$slot DEL"
                setOnClickListener {
                    val ok = saveSlots.deleteSlot(slot)
                    slotLabel.text = saveSlots.exportSummary(slot)
                    slotStatus.text = if (ok) "Slot $slot gelöscht" else "Slot $slot konnte nicht gelöscht werden"
                }
            }
            row.addView(saveButton, weightedButtonParams())
            row.addView(loadButton, weightedButtonParams())
            row.addView(deleteButton, weightedButtonParams())
            labPage.addView(row)
        }

        labPage.addView(label("ARCHIVE DIAGNOSTICS", 16f, 16).apply { setTextColor(Color.rgb(130, 220, 210)) })
        diagnosticsStatus = label("Noch keine Diagnose-Samples", 12f, 5).apply { setTextColor(Color.rgb(190, 205, 220)) }
        labPage.addView(diagnosticsStatus)
        fun refreshDiagnosticsStatus() {
            val d = diagnostics.summary()
            diagnosticsStatus.text = if (d.samples == 0) {
                "Noch keine Diagnose-Samples"
            } else {
                "Samples: ${d.samples} · ΔObs ${d.observationsSinceFirst}\n" +
                    "ΔMikro ${d.newMicroSinceFirst} · ΔMakro ${d.newMacroSinceFirst} · ΔSeq ${d.newSequencesSinceFirst}\n" +
                    "Neue Mikro/1000 Obs: ${"%.3f".format(d.microPer1000Observations)} · neue Seq/1000 Obs: ${"%.3f".format(d.sequencePer1000Observations)}\n" +
                    "Memory-Datei: ${d.latestMemoryBytes / 1024} KB · Java-Heap: ${d.latestHeapBytes / (1024 * 1024)} MB"
            }
        }
        val diagRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        diagRow.addView(Button(this).apply {
            text = "SAMPLE"
            setOnClickListener { memory.saveNow(); diagnostics.capture(memory.stats()); refreshDiagnosticsStatus() }
        }, weightedButtonParams())
        diagRow.addView(Button(this).apply {
            text = "CSV"
            setOnClickListener {
                val f = diagnostics.writeCsvExport()
                refreshDiagnosticsStatus()
                diagnosticsStatus.text = diagnosticsStatus.text.toString() + "\nCSV intern: ${f.name} (${f.length()} Bytes)"
            }
        }, weightedButtonParams())
        diagRow.addView(Button(this).apply {
            text = "RESET"
            setOnClickListener { diagnostics.reset(); refreshDiagnosticsStatus() }
        }, weightedButtonParams())
        labPage.addView(diagRow)
        refreshDiagnosticsStatus()

        labPage.addView(label("CONTINUITY AUDIT", 16f, 18).apply { setTextColor(Color.rgb(245, 190, 95)) })
        labPage.addView(label(
            "Misst nur. Der Test verändert keine Lernregeln. Automatisches Sample alle 5 Minuten; für verwertbare Trends sollte die App über längere Zeit mit ähnlichen Alltagsbedingungen laufen.",
            12f,
            4
        ).apply { setTextColor(Color.LTGRAY) })
        hardTestStatus = label("Continuity Audit: noch keine Daten", 12f, 6).apply { setTextColor(Color.rgb(225, 215, 175)) }
        labPage.addView(hardTestStatus)
        val hardTestRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        hardTestRow1.addView(Button(this).apply {
            text = "TEST START 5MIN"
            setOnClickListener {
                hardTest.startNewSession()
                captureHardTestSample(saveMemory = true)
                uiHandler.removeCallbacks(hardTestLoop)
                uiHandler.postDelayed(hardTestLoop, LongTermHardTestManager.DEFAULT_INTERVAL_MS)
                refreshHardTestStatus()
            }
        }, weightedButtonParams())
        hardTestRow1.addView(Button(this).apply {
            text = "SAMPLE"
            setOnClickListener { captureHardTestSample(saveMemory = true) }
        }, weightedButtonParams())
        hardTestRow1.addView(Button(this).apply {
            text = "STOP"
            setOnClickListener {
                hardTest.stop()
                uiHandler.removeCallbacks(hardTestLoop)
                refreshHardTestStatus()
            }
        }, weightedButtonParams())
        labPage.addView(hardTestRow1)
        val hardTestRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        hardTestRow2.addView(Button(this).apply {
            text = "CSV"
            setOnClickListener {
                val f = hardTest.writeCsvExport()
                refreshHardTestStatus()
                hardTestStatus.text = hardTestStatus.text.toString() + "\nCSV intern: ${f.name} (${f.length()} Bytes)"
            }
        }, weightedButtonParams())
        hardTestRow2.addView(Button(this).apply {
            text = "RESET"
            setOnClickListener {
                hardTest.reset()
                uiHandler.removeCallbacks(hardTestLoop)
                refreshHardTestStatus()
            }
        }, weightedButtonParams())
        labPage.addView(hardTestRow2)
        refreshHardTestStatus()
        if (hardTest.isRunning()) {
            uiHandler.removeCallbacks(hardTestLoop)
            uiHandler.postDelayed(hardTestLoop, LongTermHardTestManager.DEFAULT_INTERVAL_MS)
        }

        intervalSpinner = Spinner(this)
        val intervals = arrayOf("5 Sekunden", "15 Sekunden", "30 Sekunden", "1 Minute", "5 Minuten", "15 Minuten", "30 Minuten")
        intervalSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, intervals)
        consolidationStatus = label("Archive Consolidation: 5 s · nächste: —\n$lastConsolidationSummary", 13f, 16).apply {
            setTextColor(Color.rgb(255, 205, 105))
        }
        labPage.addView(consolidationStatus)
        labPage.addView(intervalSpinner, buttonParams(6))

        labPage.addView(Button(this).apply {
            text = "ARCHIVE CONSOLIDATION · JETZT"
            setOnClickListener {
                val result = memory.consolidate()
                lastConsolidationMs = System.currentTimeMillis()
                lastConsolidationSummary = consolidationSummary(result)
                updateConsolidationStatus()
                updateMemoryStats()
                refreshGraph()
            }
        }, buttonParams(6))

        labPage.addView(Button(this).apply {
            fun refreshText() { text = if (memory.isMacroFrozen()) "MAKRO-ERZEUGUNG: GEFROREN" else "MAKRO-ERZEUGUNG: AKTIV" }
            refreshText()
            setOnClickListener {
                memory.setMacroFrozen(!memory.isMacroFrozen())
                refreshText()
                updateMemoryStats()
                refreshGraph()
            }
        }, buttonParams(6))

        labPage.addView(Button(this).apply {
            text = "PRUNING PROTOCOL · JETZT"
            setOnClickListener {
                val r = memory.runMemoryCompetition(force = true)
                lastConsolidationSummary = "Pruning Protocol: ${r.macrosExamined} geprüft · ${r.macrosPruned} Makros · ${r.relationsPruned} Relationen · ${r.candidatesPruned} Kandidaten gepruned · ${r.protectedMacros} geschützt"
                updateConsolidationStatus()
                updateMemoryStats()
                refreshGraph()
            }
        }, buttonParams(6))

        sonificationStatus = label("Sonifikation: AUS", 13f, 12).apply { setTextColor(Color.rgb(255, 190, 90)) }
        labPage.addView(sonificationStatus)
        labPage.addView(Button(this).apply {
            text = "GEDÄCHTNIS HÖRBAR MACHEN"
            setOnClickListener {
                if (running) stopAudio()
                val state = outputBridge.snapshot()
                unifiedAudio.playFinite(state) { text -> runOnUiThread { sonificationStatus.text = text } }
            }
        }, buttonParams(6))
        labPage.addView(Button(this).apply {
            text = "KLANG STOPP"
            setOnClickListener { unifiedAudio.stop(); sonificationStatus.text = "Sonifikation: AUS" }
        }, buttonParams(6))

        labOrchestraStatus = label("Orchester: AUS", 13f, 10).apply { setTextColor(Color.rgb(125, 220, 255)) }
        labPage.addView(labOrchestraStatus)
        labPage.addView(Button(this).apply {
            text = "STATE AUDIO PREVIEW"
            setOnClickListener {
                if (running) stopAudio()
                unifiedAudio.stop()
                val state = outputBridge.snapshot()
                unifiedAudio.playFinite(state) { text -> runOnUiThread { labOrchestraStatus.text = text } }
            }
        }, buttonParams(6))
        labPage.addView(Button(this).apply {
            text = "AUDIO PREVIEW STOPP"
            setOnClickListener {
                unifiedAudio.stop()
                labOrchestraStatus.text = "Orchester: AUS"
                if (::memoryOrchestraStatus.isInitialized) memoryOrchestraStatus.text = "Unified Audio: AUS"
                if (::liveOrchestraStatus.isInitialized) liveOrchestraStatus.text = "Live-Orchester: AUS"
            }
        }, buttonParams(6))

        labPage.addView(Button(this).apply {
            text = "GEDÄCHTNIS LÖSCHEN"
            setOnClickListener {
                stopMemoryPlayback(clear = true)
                memory.reset()
                updateMemoryStats()
                refreshGraph()
            }
        }, buttonParams(16))

        labPage.addView(label(
            "Step 24 ergänzt den indexierten Langzeitspeicher um einen kontrollierten Hard Test. Er protokolliert über Stunden/Tage Neuheitsraten, Makrowachstum, Kompression, Prediction, Vergessen sowie Storage- und Heap-Wachstum. Die Messung ist read-only gegenüber der Lernlogik und kann als CSV exportiert werden.",
            12f,
            14
        ).apply { setTextColor(Color.LTGRAY) })
    }

    private fun pageContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
    }

    private fun showPage(page: AppPage) {
        if (page == currentPage && livePage.visibility == View.VISIBLE) return

        when (page) {
            AppPage.LIVE -> {
                stopMemoryPlayback(clear = true)
                stopHybridPlayback(clear = false)
                unifiedAudio.stop()
                hybridPage.visibility = View.GONE
                memoryPage.visibility = View.GONE
                mindPage.visibility = View.GONE
                labPage.visibility = View.GONE
                livePage.visibility = View.VISIBLE
            }
            AppPage.HYBRID -> {
                stopMemoryPlayback(clear = false)
                unifiedAudio.stop()
                livePage.visibility = View.GONE
                memoryPage.visibility = View.GONE
                mindPage.visibility = View.GONE
                labPage.visibility = View.GONE
                hybridPage.visibility = View.VISIBLE
                refreshSensorUiState()
                refreshHybridPage(ok = true)
            }
            AppPage.MEMORY -> {
                stopHybridPlayback(clear = true)
                stopAudio()
                stopCameraLearning()
                unifiedAudio.stop()
                stopContinuousOrchestra()
                memory.clearMomentaryState()
                livePage.visibility = View.GONE
                hybridPage.visibility = View.GONE
                mindPage.visibility = View.GONE
                labPage.visibility = View.GONE
                memoryPage.visibility = View.VISIBLE
                memoryModeStatus.text = if (memory.hasLearnedMacros()) "ARCHIVE: bereit" else "ARCHIVE: noch keine gelernten Makros"
                if (::dreamModeStatus.isInitialized) dreamModeStatus.text = if (memory.hasLearnedMacros()) "BRANCH WALK: bereit" else "BRANCH WALK: noch keine gelernten Makros"
            }
            AppPage.MIND -> {
                stopMemoryPlayback(clear = false)
                stopHybridPlayback(clear = false)
                unifiedAudio.stop()
                livePage.visibility = View.GONE
                hybridPage.visibility = View.GONE
                memoryPage.visibility = View.GONE
                labPage.visibility = View.GONE
                mindPage.visibility = View.VISIBLE
                if (::mindStatus.isInitialized) {
                    mindStatus.text = "MIND: ${mindRepository.status()}"
                }
            }
            AppPage.LAB -> {
                stopMemoryPlayback(clear = false)
                stopHybridPlayback(clear = false)
                livePage.visibility = View.GONE
                hybridPage.visibility = View.GONE
                memoryPage.visibility = View.GONE
                mindPage.visibility = View.GONE
                labPage.visibility = View.VISIBLE
            }
        }
        currentPage = page
        refreshGraph()
    }

    private fun preferredLiveCognitiveMode(): CognitiveMode =
        if (modalityPrefs.getBoolean("product_learning_enabled", true)) CognitiveMode.LEARN else CognitiveMode.PLAY

    private fun restorePreferredLiveCognitiveMode() {
        memory.setCognitiveMode(preferredLiveCognitiveMode())
    }

    private fun enterLearnMode() {
        stopDreamPlayback(clear = false)
        stopMemoryPlayback(clear = false)
        stopHybridPlayback(clear = false)
        modalityPrefs.edit().putBoolean("product_learning_enabled", true).apply()
        memory.setCognitiveMode(CognitiveMode.LEARN)
        syncCognitiveModeUi()
        refreshGraph()
    }

    private fun enterPlayMode(stopInternalPlayback: Boolean = true, rememberPreference: Boolean = true) {
        if (stopInternalPlayback) {
            stopDreamPlayback(clear = false)
            stopMemoryPlayback(clear = false)
            stopHybridPlayback(clear = false)
        }
        if (rememberPreference) modalityPrefs.edit().putBoolean("product_learning_enabled", false).apply()
        memory.setCognitiveMode(CognitiveMode.PLAY)
        syncCognitiveModeUi()
        refreshGraph()
    }

    private fun syncCognitiveModeUi() {
        val memoryMode = memory.cognitiveMode()
        val dreamActive = dreamPlaybackRunning
        if (::productModeButton.isInitialized) {
            productModeButton.text = if (dreamActive) "DREAM" else when (memoryMode) {
                CognitiveMode.LEARN -> "LEARN"
                CognitiveMode.PLAY -> "LIVE"
                CognitiveMode.DREAM -> "DREAM"
            }
            productModeButton.setTextColor(if (dreamActive) {
                Color.rgb(255, 176, 112)
            } else when (memoryMode) {
                CognitiveMode.LEARN -> Color.rgb(105, 225, 235)
                CognitiveMode.PLAY -> Color.rgb(145, 230, 210)
                CognitiveMode.DREAM -> Color.rgb(255, 176, 112)
            })
        }
        if (::cognitiveModeStatus.isInitialized) {
            cognitiveModeStatus.text = if (dreamActive) {
                "Modus: DREAM · ${dreamControlMode.label} · persistente Weltmaschine"
            } else when (memoryMode) {
                CognitiveMode.LEARN -> "Modus: LEARN · Sensoren schreiben neue Erfahrung"
                CognitiveMode.PLAY -> "Modus: PLAY · Sensoren aktivieren nur Bekanntes"
                CognitiveMode.DREAM -> "Modus: DREAM · interne Memory-Exploration"
            }
        }
        refreshSensorUiState()
    }

    private fun startHybridPlayback() {
        enterPlayMode(stopInternalPlayback = false, rememberPreference = false)
        if (!memory.hasLearnedMacros()) {
            hybridModeStatus.text = "HYBRID: noch keine gelernten Makros"
            return
        }
        stopMemoryPlayback(clear = false)
        if (!hybridPlaybackRunning) {
            hybridPlaybackRunning = true
            hybridModeStatus.text = "HYBRID: RUN · Live ankert, Memory propagiert read-only"
            uiHandler.removeCallbacks(hybridLoop)
            uiHandler.post(hybridLoop)
        }
    }

    private fun stopHybridPlayback(clear: Boolean) {
        val wasRunning = hybridPlaybackRunning
        hybridPlaybackRunning = false
        uiHandler.removeCallbacks(hybridLoop)
        if (clear) memory.clearMomentaryState()
        if (wasRunning && memory.cognitiveMode() == CognitiveMode.PLAY && preferredLiveCognitiveMode() == CognitiveMode.LEARN) {
            restorePreferredLiveCognitiveMode()
        }
        if (::hybridModeStatus.isInitialized) {
            hybridModeStatus.text = if (memory.hasLearnedMacros()) "HYBRID: gestoppt" else "HYBRID: noch keine gelernten Makros"
        }
    }

    private fun refreshHybridPage(ok: Boolean) {
        val w = memory.unifiedWorkingSnapshot()
        livePipeline.offerState(w)
        if (::surfaceVideo.isInitialized && videoOutputEnabled) {
            surfaceVideo.setWorkingState(routedVideoWorkingSnapshot())
            scheduleLowLatencyRender()
        }
        if (unifiedAudio.isRunning() && !cameraRunning) {
            val audioWorking = routedMusicWorkingSnapshot()
            unifiedAudio.updateState(
                memory.sonificationSnapshot(maxMicro = 0, maxMacros = 12),
                audioWorking,
                crossModalProjectionForTarget(MasterOutputTarget.AUDIO, source = audioWorking),
            )
        }
        syncExpressionRenderers()
        if (settingsOpen && ::hybridModeStatus.isInitialized) {
            hybridModeStatus.text = if (!ok && w.nodes.none { it.activation > 0.12 }) {
                "HYBRID: wartet auf Live-Makro"
            } else if (hybridPlaybackRunning) {
                "HYBRID: RUN · ${w.nodes.count { it.activation > 0.12 }} live/aktiv · ${w.nodes.count { it.retrieved }} erinnert · ${w.predictions.size} Prediction · ${w.exchanges.size} Austausch"
            } else {
                "HYBRID: ${w.nodes.size} Working-Set-Knoten · ${w.predictions.size} Prediction"
            }
        }
        if (settingsOpen) refreshSensorUiState()
    }

    private fun refreshSensorUiState() {
        if (!settingsOpen) return
        if (::hybridSensorStatus.isInitialized) {
            hybridSensorStatus.text = "Sensoren: Audio ${if (running) "AKTIV" else "AUS"} · Kamera ${if (cameraRunning) "AKTIV" else "AUS"}"
        }
        if (::status.isInitialized) status.text = if (running) "Audio: AKTIV" else "Audio-Feed: AUS"
        if (::startButton.isInitialized) startButton.isEnabled = !running
        if (::stopButton.isInitialized) stopButton.isEnabled = running
        if (::cameraStatus.isInitialized) cameraStatus.text = if (cameraRunning) "Kamera: AKTIV · ${memory.cognitiveMode().label} · coarse→fine" else "Optical Feed: AUS"
        if (::cameraButton.isInitialized) cameraButton.isEnabled = !cameraRunning
        if (::cameraStopButton.isInitialized) cameraStopButton.isEnabled = cameraRunning
    }

    private fun startMemoryPlayback() {
        enterPlayMode(stopInternalPlayback = false, rememberPreference = false)
        if (!memory.hasLearnedMacros()) {
            memoryModeStatus.text = "ARCHIVE: noch keine gelernten Makros"
            return
        }
        stopAudio()
        stopCameraLearning()
        stopHybridPlayback(clear = false)
        unifiedAudio.stop()
        stopContinuousOrchestra()
        stopDreamPlayback(clear = false)
        if (!memoryPlaybackRunning) {
            memoryPlaybackRunning = true
            memoryModeStatus.text = "ARCHIVE: RUN · nur gelernte Aktenlage"
            uiHandler.removeCallbacks(memoryLoop)
            uiHandler.post(memoryLoop)
        }
    }

    private fun stopMemoryPlayback(clear: Boolean) {
        val wasRunning = memoryPlaybackRunning
        memoryPlaybackRunning = false
        dreamPlaybackRunning = false
        uiHandler.removeCallbacks(memoryLoop)
        uiHandler.removeCallbacks(dreamLoop)
        unifiedAudio.setDreamWorld(null, linked = false)
        if (::dreamWorldView.isInitialized) dreamWorldView.visibility = View.GONE
        if (clear) memory.clearMomentaryState()
        if (wasRunning && memory.cognitiveMode() == CognitiveMode.PLAY && preferredLiveCognitiveMode() == CognitiveMode.LEARN) {
            restorePreferredLiveCognitiveMode()
        }
        if (::memoryModeStatus.isInitialized) {
            memoryModeStatus.text = if (memory.hasLearnedMacros()) "ARCHIVE: gestoppt" else "ARCHIVE: noch keine gelernten Makros"
        }
        if (::dreamModeStatus.isInitialized) {
            dreamModeStatus.text = if (memory.hasLearnedMacros()) "BRANCH WALK: gestoppt" else "BRANCH WALK: noch keine gelernten Makros"
        }
    }

    private fun startDreamPlayback() {
        stopMemoryPlayback(clear = false)
        stopHybridPlayback(clear = false)
        // DREAM is a world runtime, not a synonym for "memory only". In autonomous mode
        // MemoryModel remains isolated; reactive/instrument/fusion keep sensor intake live.
        if (dreamControlMode == DreamControlMode.AUTONOMOUS) {
            memory.setCognitiveMode(CognitiveMode.DREAM)
        } else if (memory.cognitiveMode() == CognitiveMode.DREAM) {
            restorePreferredLiveCognitiveMode()
        }
        dreamMemoryStepCounter = 0
        dreamPlaybackRunning = true
        val world = dreamRuntime.snapshot().copy(controlMode = dreamControlMode)
        if (::dreamWorldView.isInitialized) {
            val visualAllowed = dreamVisualEnabled && (videoOutputEnabled || OutputCapability.FILM in activeOutputCapabilities)
            dreamWorldView.setWorldState(world.takeIf { visualAllowed }, animate = visualAllowed)
        }
        unifiedAudio.setDreamWorld(world, linked = dreamAudioLinked)
        if (::dreamModeStatus.isInitialized) dreamModeStatus.text = "DREAM: RUN · ${dreamControlMode.label} · world tick ${world.tick}"
        syncCognitiveModeUi()
        applyLayerComposition()
        applyOutputRouting(syncSensors = dreamControlMode != DreamControlMode.AUTONOMOUS)
        uiHandler.removeCallbacks(dreamLoop)
        uiHandler.post(dreamLoop)
    }

    private fun stopDreamPlayback(clear: Boolean) {
        dreamPlaybackRunning = false
        uiHandler.removeCallbacks(dreamLoop)
        unifiedAudio.setDreamWorld(null, linked = false)
        if (::dreamWorldView.isInitialized) dreamWorldView.visibility = View.GONE
        if (memory.cognitiveMode() == CognitiveMode.DREAM) restorePreferredLiveCognitiveMode()
        if (clear) {
            memory.clearMomentaryState()
            dreamRuntime.reset()
        }
        if (::dreamModeStatus.isInitialized) {
            dreamModeStatus.text = "DREAM: gestoppt · world tick ${dreamRuntime.snapshot().tick}"
        }
        syncCognitiveModeUi()
        applyLayerComposition()
    }

    private fun refreshMemoryPage() {
        if (::externalKnowledgeStatus.isInitialized && ::memory.isInitialized) {
            val base = memory.baseManifest()
            externalKnowledgeStatus.text = if (base.installed) {
                "BASE aktiv: ${base.packId} v${base.version} · ${base.surfaceCount} surfaces · ${base.macroCount} Makros · ${base.highestTier}"
            } else "BASE: leer · LIVE/PERSONAL bleibt eigenständig"
        }
        val state = outputBridge.snapshot()
        if (::surfaceVideo.isInitialized && videoOutputEnabled) surfaceVideo.setWorkingState(routedVideoWorkingSnapshot())
        if (unifiedAudio.isRunning()) {
            val audioWorking = routedMusicWorkingSnapshot()
            unifiedAudio.updateState(
                state.sound,
                audioWorking,
                crossModalProjectionForTarget(MasterOutputTarget.AUDIO, source = audioWorking),
            )
        }
        syncExpressionRenderers()
        val w = state.working
        if (settingsOpen && ::memoryModeStatus.isInitialized && memoryPlaybackRunning) {
            memoryModeStatus.text = "ARCHIVE: RUN · ${w.nodes.size} aktiv · ${w.exchanges.size} Austausch"
        }
        if (settingsOpen && ::dreamModeStatus.isInitialized && dreamPlaybackRunning) {
            dreamModeStatus.text = "DREAM: ${dreamRuntime.snapshot().compactSummary()}"
        }
    }

    private fun refreshGraph() {
        // Step 30: one surface always receives the compact WorkingSet. The camera frame, if
        // available, is synchronized separately by LowLatencyLivePipeline. Memory-only/Dream
        // states therefore render on the same GPU canvas without requiring a second View.
        if (::surfaceVideo.isInitialized) {
            val working = memory.unifiedWorkingSnapshot()
            if (videoOutputEnabled) surfaceVideo.setWorkingState(routedVideoWorkingSnapshot())
            livePipeline.offerState(working)
            if (videoOutputEnabled) scheduleLowLatencyRender()
        }
        syncExpressionRenderers()

        val labDue = settingsOpen && currentPage == AppPage.LAB &&
            ::graphView.isInitialized && labPage.visibility == View.VISIBLE &&
            System.currentTimeMillis() - lastLabRenderMs >= 250L
        if (!labDue) return

        val state = outputBridge.snapshot()
        lastLabRenderMs = System.currentTimeMillis()
        graphView.setSnapshot(state.graph)
    }

    private fun scheduleLowLatencyRender() {
        if (!::surfaceVideo.isInitialized) return
        if (!liveRenderPosted.compareAndSet(false, true)) return

        uiHandler.post {
            liveRenderPosted.set(false)
            if (!::surfaceVideo.isInitialized || surfaceVideo.visibility != View.VISIBLE) return@post

            // Feed the controller the cost of the previous draw before selecting the next frame.
            livePipeline.reportRenderCost(surfaceVideo.lastRenderCostMs())
            applyPerformanceGovernor()
            val packet = livePipeline.poll() ?: return@post
            val preset = packet.configuredPreset.label
            val info = if (packet.configuredPreset == LowLatencyLivePipeline.DelayPreset.AUTO) {
                "Sync AUTO ${packet.effectiveDelayMs}ms · Frame ${packet.actualFrameAgeMs}ms · drop ${packet.droppedFramesTotal}"
            } else {
                "Sync $preset · Frame ${packet.actualFrameAgeMs}ms · drop ${packet.droppedFramesTotal}"
            }
            surfaceVideo.setSynchronizedFrame(packet.frame, packet.working, info)
            // Step 44: when camera presentation is active, audio receives the exact same
            // delayed WorkingSetSnapshot that HYPNO receives. This prevents semantic AV skew
            // from independently sampling the live memory frontier.
            if (unifiedAudio.isRunning() && packet.working != null) {
                val packetAudioWorking = routeWorkingSnapshot(packet.working, MasterOutputTarget.AUDIO)
                unifiedAudio.updateState(
                    memory.sonificationSnapshot(maxMicro = 0, maxMacros = 12),
                    packetAudioWorking,
                    crossModalProjectionForTarget(
                        MasterOutputTarget.AUDIO,
                        source = packet.working,
                        liveText = lastDreamText,
                    ),
                )
            }
            if (settingsOpen) updateLiveLatencyStatus()
        }
    }

    private fun applyPerformanceGovernor(force: Boolean = false) {
        if (!::surfaceVideo.isInitialized) return
        val now = System.currentTimeMillis()
        if (!force && now - lastGovernorUpdateMs < 500L) return
        lastGovernorUpdateMs = now

        val st = livePipeline.stats()
        val dropRatio = if (st.offeredFrames <= 0L) 0.0 else
            (st.droppedFrames.toDouble() / st.offeredFrames.toDouble()).coerceIn(0.0, 1.0)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0
        } else 0
        val gov = performanceGovernor.update(
            renderMs = surfaceVideo.lastRenderCostMs(),
            analysisMs = st.analysisCostEwmaMs,
            dropRatio = dropRatio,
            thermalStatus = thermal,
            nowMs = now
        )
        surfaceVideo.setPerformanceProfile(gov.profile)
        visualAnalyzer?.setPerformanceProfile(gov.profile)
    }

    private fun updateLiveLatencyStatus() {
        if (!::liveLatencyStatus.isInitialized) return
        val st = livePipeline.stats()
        val g = performanceGovernor.snapshot()
        liveLatencyStatus.text =
            "Temporal Sync: ${st.preset.label} · effektiv ${st.effectiveDelayMs} ms · Analyse Ø ${"%.1f".format(st.analysisCostEwmaMs)} ms · Render Ø ${"%.1f".format(st.renderCostEwmaMs)} ms · Frames ${st.renderedFrames}/${st.offeredFrames} · verworfen ${st.droppedFrames} · Buffer ${st.bufferedFrames} · Governor ${g.profile.label} (${g.profile.fieldBudget}F/${g.profile.linkBudget}L)"
    }

    private fun startUnifiedCapture(resultCode: Int, data: android.content.Intent) {
        if (captureRecorder.isRecording()) return
        val metrics = resources.displayMetrics
        unifiedAudio.setPcmTap { pcm -> captureRecorder.offerPcm(pcm) }
        captureButton.text = "■"
        captureButton.setTextColor(Color.rgb(255, 92, 92))
        captureRecorder.start(
            resultCode = resultCode,
            resultData = data,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            onState = { text -> runOnUiThread {
                if (::captureStatus.isInitialized) captureStatus.text = "Aufnahme: $text"
            } },
            onFinished = { result -> runOnUiThread {
                unifiedAudio.setPcmTap(null)
                CaptureForegroundService.stop(this)
                captureButton.text = "REC"
                captureButton.setTextColor(Color.rgb(255, 112, 112))
                if (::captureStatus.isInitialized) {
                    captureStatus.text = if (result.error == null) {
                        val where = result.uri?.toString() ?: result.path.orEmpty()
                        "Aufnahme gespeichert · ${"%.1f".format(result.durationMs / 1000.0)} s · $where"
                    } else "Aufnahmefehler: ${result.error}"
                }
            } }
        )
    }

    private fun stopUnifiedCapture() {
        if (!captureRecorder.isRecording()) return
        captureButton.text = "…"
        captureRecorder.stop(
            onState = { text -> runOnUiThread { if (::captureStatus.isInitialized) captureStatus.text = "Aufnahme: $text" } },
            onFinished = { result -> runOnUiThread {
                unifiedAudio.setPcmTap(null)
                CaptureForegroundService.stop(this)
                captureButton.text = "REC"
                captureButton.setTextColor(Color.rgb(255, 112, 112))
                if (::captureStatus.isInitialized) {
                    captureStatus.text = if (result.error == null)
                        "Aufnahme gespeichert · ${"%.1f".format(result.durationMs / 1000.0)} s"
                    else "Aufnahmefehler: ${result.error}"
                }
            } }
        )
    }

    private fun toggleContinuousOrchestra() {
        if (unifiedAudio.isRunning()) {
            stopContinuousOrchestra()
        } else {
            unifiedAudio.stop()
            unifiedAudio.start { text ->
                runOnUiThread {
                    updateContinuousOrchestraUi(text)
                    updateEchoCancelerState()
                }
            }
            pushContinuousOrchestraState()
            updateEchoCancelerState()
            updateContinuousOrchestraUi("Unified Audio: STARTET …")
        }
    }

    private fun stopContinuousOrchestra() {
        if (unifiedAudio.isRunning()) unifiedAudio.stop()
        updateEchoCancelerState()
        updateContinuousOrchestraUi("Unified Audio: AUS")
    }

    private fun pushContinuousOrchestraState() {
        if (!unifiedAudio.isRunning()) return
        val sound = memory.sonificationSnapshot(maxMicro = 0, maxMacros = 18)
        val working = routedMusicWorkingSnapshot()
        applyOperationalOutputTuning(working, expressionIdsForTarget(MasterOutputTarget.AUDIO))
        unifiedAudio.updateState(
            sound,
            working,
            crossModalProjectionForTarget(
                MasterOutputTarget.AUDIO,
                source = working,
                liveText = lastDreamText,
            ),
        )
    }

    private fun updateContinuousOrchestraUi(text: String) {
        val guard = if (feedbackProtectionEnabled) {
            val aec = if (echoCanceler?.enabled == true) "AEC AN" else if (AcousticEchoCanceler.isAvailable()) "AEC bereit" else "AEC n/a"
            " · $aec · SelfGuard ${lastSelfOutputComponents}/${"%.1f".format(lastSelfOutputRemovedRatio * 100.0)}%"
        } else " · Feedback-Schutz AUS"
        val full = text + guard
        if (::liveOrchestraStatus.isInitialized) liveOrchestraStatus.text = full
        if (::hybridOrchestraStatus.isInitialized) hybridOrchestraStatus.text = full
        if (::productAudioButton.isInitialized) {
            productAudioButton.text = if (unifiedAudio.isRunning()) "■" else "♪"
            productAudioButton.setTextColor(if (unifiedAudio.isRunning()) Color.rgb(125, 220, 255) else Color.WHITE)
        }
        if (::liveContinuousButton.isInitialized) liveContinuousButton.text = if (unifiedAudio.isRunning()) "AUDIO STOP" else "AUDIO START"
        if (::hybridContinuousButton.isInitialized) hybridContinuousButton.text = if (unifiedAudio.isRunning()) "AUDIO STOP" else "AUDIO START"
    }

    private fun setFeedbackProtection(enabled: Boolean) {
        if (feedbackUiSyncing) return
        feedbackProtectionEnabled = enabled
        feedbackUiSyncing = true
        try {
            if (::liveFeedbackProtectionToggle.isInitialized && liveFeedbackProtectionToggle.isChecked != enabled) liveFeedbackProtectionToggle.isChecked = enabled
            if (::hybridFeedbackProtectionToggle.isInitialized && hybridFeedbackProtectionToggle.isChecked != enabled) hybridFeedbackProtectionToggle.isChecked = enabled
        } finally {
            feedbackUiSyncing = false
        }
        updateEchoCancelerState()
        updateContinuousOrchestraUi(if (unifiedAudio.isRunning()) "Unified Audio: ${unifiedAudio.sceneSummary()}" else "Unified Audio: AUS")
    }

    private fun setupEchoCanceler(recorder: AudioRecord) {
        releaseEchoCanceler()
        if (!AcousticEchoCanceler.isAvailable()) return
        echoCanceler = try { AcousticEchoCanceler.create(recorder.audioSessionId) } catch (_: Exception) { null }
        updateEchoCancelerState()
    }

    private fun updateEchoCancelerState() {
        val shouldEnable = feedbackProtectionEnabled && unifiedAudio.isRunning()
        try { echoCanceler?.enabled = shouldEnable } catch (_: Exception) { }
    }

    private fun releaseEchoCanceler() {
        try { echoCanceler?.enabled = false } catch (_: Exception) { }
        try { echoCanceler?.release() } catch (_: Exception) { }
        echoCanceler = null
    }

    private fun ensurePermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAudio()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAudio()
                syncContextSensors()
            } else {
                routingOwnsAudio = false
                audioInputEnabled = false
                persistModalityRouting()
                refreshModalityDock()
                if (::status.isInitialized) status.text = "Audio: Mikrofon-Berechtigung fehlt"
                val cameraStillNeeded = cameraInputEnabled
                if (cameraStillNeeded && !cameraRunning && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCameraLearning()
            }
        } else if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraLearning()
                syncContextSensors()
            } else {
                routingOwnsCamera = false
                cameraInputEnabled = false
                persistModalityRouting()
                refreshModalityDock()
                if (::cameraStatus.isInitialized) cameraStatus.text = "Kamera: Berechtigung fehlt"
                val audioStillNeeded = audioInputEnabled
                if (audioStillNeeded && !running && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startAudio()
            }
        } else if (requestCode == REQ_MULTI) {
            val grants = permissions.indices.associate { index -> permissions[index] to (grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED) }
            val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true
            val cameraGranted = grants[Manifest.permission.CAMERA] == true
            if (audioGranted && audioInputEnabled && !running) startAudio() else if (!audioGranted) routingOwnsAudio = false
            if (cameraGranted && cameraInputEnabled && !cameraRunning) startCameraLearning() else if (!cameraGranted) routingOwnsCamera = false
            if (!audioGranted) audioInputEnabled = false
            if (!cameraGranted) cameraInputEnabled = false
            if (!audioGranted || !cameraGranted) {
                persistModalityRouting()
                refreshModalityDock()
            }
            if (!audioGranted && ::status.isInitialized) status.text = "Audio: Mikrofon-Berechtigung fehlt"
            if (!cameraGranted && ::cameraStatus.isInitialized) cameraStatus.text = "Kamera: Berechtigung fehlt"
        }
    }

    private fun startAudio() {
        if (running) return
        if (memory.cognitiveMode() == CognitiveMode.DREAM) { restorePreferredLiveCognitiveMode(); syncCognitiveModeUi() }
        stopMemoryPlayback(clear = true)

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            status.text = "Audio: Gerät meldet ungültigen Puffer"
            return
        }

        val bufferBytes = max(minBuffer, FFT_SIZE * 2 * 4)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
        } catch (_: SecurityException) {
            status.text = "Audio: keine Mikrofon-Berechtigung"
            return
        } catch (e: Exception) {
            status.text = "Audio: Startfehler ${e.javaClass.simpleName}"
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            status.text = "Audio: AudioRecord konnte nicht initialisiert werden"
            return
        }

        audioRecord = recorder
        setupEchoCanceler(recorder)
        running = true
        syncExpressionRenderers()
        startButton.isEnabled = false
        stopButton.isEnabled = true
        status.text = "Audio-Feed: AKTIV"
        refreshSensorUiState()
        lastConsolidationMs = System.currentTimeMillis()

        recorder.startRecording()
        val selectedEncoder = audioEncoderMode
        worker = thread(name = when (selectedEncoder) {
            AudioEncoderMode.FFT_FREQUENCY -> "EquinoxAudioFFT"
            AudioEncoderMode.DIRECT_PATTERN -> "EquinoxAudioDirectID"
            AudioEncoderMode.ADAPTIVE_PATTERN -> "EquinoxAudioAdaptiveID"
        }) {
            val frame = ShortArray(FFT_SIZE)
            var frameCount = 0L

            while (running) {
                var offset = 0
                while (running && offset < FFT_SIZE) {
                    val read = recorder.read(frame, offset, FFT_SIZE - offset)
                    if (read > 0) offset += read else break
                }
                if (!running || offset < FFT_SIZE) continue

                frameCount++
                val selfGuard = if (feedbackProtectionEnabled && unifiedAudio.isRunning()) {
                    selfOutputSuppressor.suppress(frame, unifiedAudio.suppressionFrequencies())
                } else null
                val analysisFrame = selfGuard?.samples ?: frame
                if (selfGuard != null) {
                    lastSelfOutputRemovedRatio = selfGuard.removedRmsRatio
                    lastSelfOutputComponents = selfGuard.removedComponents
                } else {
                    lastSelfOutputRemovedRatio = 0.0
                    lastSelfOutputComponents = 0
                }

                var primaryText: String
                var idText: String
                var strengthText: String

                val parallelTest = parallelEncoderBenchmarkEnabled && encoderBenchmark.isActive()
                if (parallelTest) {
                    // Fair hard test: all three encoders see the exact same PCM frame.
                    val mags = Fft.magnitudes(analysisFrame)
                    val minBin = (40.0 * FFT_SIZE / SAMPLE_RATE).toInt().coerceAtLeast(1)
                    val maxBin = (18_000.0 * FFT_SIZE / SAMPLE_RATE).toInt().coerceAtMost(mags.lastIndex)
                    var bestBin = minBin
                    var bestMag = 0.0
                    for (bin in minBin..maxBin) {
                        if (mags[bin] > bestMag) { bestMag = mags[bin]; bestBin = bin }
                    }
                    val fftHz = bestBin.toDouble() * SAMPLE_RATE / FFT_SIZE
                    val fftId = AudioQuantizer.idForHz(fftHz)
                    val direct = DirectAudioQuantizer.quantize(analysisFrame)
                    val adaptive = adaptiveQuantizer.quantize(analysisFrame, learn = memory.cognitiveMode() == CognitiveMode.LEARN && audioInputEnabled)

                    fun feed(mode: AudioEncoderMode) {
                        if (!audioInputEnabled) return
                        when (mode) {
                            AudioEncoderMode.FFT_FREQUENCY -> memory.observe(fftId, fftHz)
                            AudioEncoderMode.DIRECT_PATTERN -> if (!direct.silent) memory.observeDirectAudio(direct.id)
                            AudioEncoderMode.ADAPTIVE_PATTERN -> if (!adaptive.silent && adaptive.recognized) memory.observeAdaptiveAudio(adaptive.id)
                        }
                    }
                    // Feed the selected stream last so the momentary LIVE working set remains
                    // centered on what the user chose while all long-term streams learn.
                    AudioEncoderMode.values().filter { it != selectedEncoder }.forEach(::feed)
                    feed(selectedEncoder)
                    encoderBenchmark.recordTriple(
                        fftId,
                        if (direct.silent) null else direct.id,
                        if (adaptive.silent || !adaptive.recognized) null else adaptive.id
                    )

                    when (selectedEncoder) {
                        AudioEncoderMode.FFT_FREQUENCY -> {
                            primaryText = "Dominante Frequenz: %.1f Hz · Paralleltest".format(fftHz)
                            idText = "Frequenz-ID: $fftId"
                            strengthText = "FFT-Stärke: %.5f".format(bestMag)
                        }
                        AudioEncoderMode.DIRECT_PATTERN -> {
                            primaryText = if (direct.silent) "Direktmuster: Stille — nicht gelernt · Paralleltest" else "Direktmuster: ${direct.debugText} · Paralleltest"
                            idText = if (direct.silent) "Pattern-ID: —" else "Pattern-ID: ${direct.id}"
                            strengthText = "PCM-Aktivität RMS: %.5f".format(direct.activity)
                        }
                        AudioEncoderMode.ADAPTIVE_PATTERN -> {
                            primaryText = if (adaptive.silent) "Adaptive-ID: Stille — nicht gelernt · Paralleltest" else "Adaptive-ID: ${adaptive.debugText} · Paralleltest"
                            idText = if (adaptive.silent) "Adaptive Pattern-ID: —" else "Adaptive Pattern-ID: ${adaptive.id}"
                            strengthText = "PCM-Aktivität RMS: %.5f · Codebook ${adaptive.codebookSize}".format(adaptive.activity)
                        }
                    }
                } else {
                    when (selectedEncoder) {
                        AudioEncoderMode.FFT_FREQUENCY -> {
                            val mags = Fft.magnitudes(analysisFrame)
                            val minBin = (40.0 * FFT_SIZE / SAMPLE_RATE).toInt().coerceAtLeast(1)
                            val maxBin = (18_000.0 * FFT_SIZE / SAMPLE_RATE).toInt().coerceAtMost(mags.lastIndex)
                            var bestBin = minBin
                            var bestMag = 0.0
                            for (bin in minBin..maxBin) {
                                if (mags[bin] > bestMag) { bestMag = mags[bin]; bestBin = bin }
                            }
                            val hz = bestBin.toDouble() * SAMPLE_RATE / FFT_SIZE
                            val id = AudioQuantizer.idForHz(hz)
                            if (audioInputEnabled) memory.observe(id, hz)
                            primaryText = "Dominante Frequenz: %.1f Hz".format(hz)
                            idText = "Frequenz-ID: $id"
                            strengthText = "FFT-Stärke: %.5f".format(bestMag)
                        }
                        AudioEncoderMode.DIRECT_PATTERN -> {
                            val direct = DirectAudioQuantizer.quantize(analysisFrame)
                            if (!direct.silent && audioInputEnabled) memory.observeDirectAudio(direct.id)
                            primaryText = if (direct.silent) "Direktmuster: Stille — nicht gelernt" else "Direktmuster: ${direct.debugText}"
                            idText = if (direct.silent) "Pattern-ID: —" else "Pattern-ID: ${direct.id}"
                            strengthText = "PCM-Aktivität RMS: %.5f".format(direct.activity)
                        }
                        AudioEncoderMode.ADAPTIVE_PATTERN -> {
                            val adaptive = adaptiveQuantizer.quantize(analysisFrame, learn = memory.cognitiveMode() == CognitiveMode.LEARN && audioInputEnabled)
                            if (!adaptive.silent && adaptive.recognized && audioInputEnabled) memory.observeAdaptiveAudio(adaptive.id)
                            primaryText = if (adaptive.silent) "Adaptive-ID: Stille — nicht gelernt" else "Adaptive-ID: ${adaptive.debugText}"
                            idText = if (adaptive.silent) "Adaptive Pattern-ID: —" else "Adaptive Pattern-ID: ${adaptive.id}"
                            strengthText = "PCM-Aktivität RMS: %.5f · Codebook ${adaptive.codebookSize}".format(adaptive.activity)
                        }
                    }
                }

                if (selfGuard != null && selfGuard.removedComponents > 0) {
                    strengthText += " · SelfGuard ${selfGuard.removedComponents} Komp. / ${"%.1f".format(selfGuard.removedRmsRatio * 100.0)}% RMS"
                }

                if (unifiedAudio.isRunning() && !cameraRunning && frameCount % 2L == 0L) {
                    // Audio-only operation: no video packet exists to provide the synchronized state.
                    pushContinuousOrchestraState()
                }

                var didConsolidate = false
                val now = System.currentTimeMillis()
                val interval = selectedIntervalMs()
                if (memory.cognitiveMode() == CognitiveMode.LEARN && now - lastConsolidationMs >= interval) {
                    val result = memory.consolidate()
                    lastConsolidationMs = now
                    lastConsolidationSummary = consolidationSummary(result)
                    didConsolidate = true
                }

                runOnUiThread {
                    if (running) {
                        if (settingsOpen) {
                            frequency.text = primaryText
                            frequencyId.text = idText
                            strength.text = strengthText
                            frames.text = "Frames verarbeitet: $frameCount"
                            if (frameCount % 5L == 0L) {
                                updateMemoryStats()
                                updateConsolidationStatus()
                                if (::adaptiveQuantizerStatus.isInitialized) adaptiveQuantizerStatus.text = adaptiveQuantizerText()
                            }
                            if (parallelTest && frameCount % 24L == 0L) refreshEncoderBenchmarkStatus()
                        }
                        if (frameCount % 4L == 0L) syncExpressionRenderers()
                        if (frameCount % 16L == 0L) maybeEmitCrossModalTextPulse("audio")
                        if (didConsolidate || frameCount % 12L == 0L) refreshGraph()
                    }
                }
            }
        }
    }

    private fun ensureCameraPermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraLearning()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    private fun startCameraLearning() {
        if (cameraRunning) return
        if (memory.cognitiveMode() == CognitiveMode.DREAM) { restorePreferredLiveCognitiveMode(); syncCognitiveModeUi() }
        stopMemoryPlayback(clear = true)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val visualAnalyzerInstance = VisualGridAnalyzer(
                        adaptiveVisualQuantizer,
                        onFrame = { result ->
                            if (!cameraRunning) return@VisualGridAnalyzer
                            if (cameraInputEnabled) {
                                // Frame-local reset prevents one spatial macro candidate from crossing
                                // the image boundary; learned identities/macros themselves remain intact.
                                memory.beginAdaptiveVisualFrame()
                                // Step 38: learning consumes contiguous coupled regions, not every
                                // redundant adaptive camera patch. Raw samples remain diagnostics only.
                                result.coupledObservations.forEach { region ->
                                    memory.observeAdaptiveVisual(region.tokenId, region.packedRgb)
                                }
                            }
                            visualFrameCount++
                            visualObservationCount += result.coupledObservations.size
                            livePipeline.reportAnalysisCost(result.analysisCostMs.toDouble() + result.videoSampleCostMs.toDouble())

                            runOnUiThread {
                                if (settingsOpen) {
                                    cameraFrames.text =
                                        "Visuelle Frames: $visualFrameCount · Core $visualObservationCount · ${memory.cognitiveMode().label} · Samples ${result.sampledRegions} · Leaves ${result.spatialLeafRegions} → gekoppelt ${result.spatialCoupledRegions} (-${"%.0f".format(result.spatialCouplingRatio * 100.0)}%) · Tiefe ${result.maxResolutionDepth} · Bekannt ${"%.0f".format(result.meanFamiliarity * 100.0)}% · +${result.createdThisFrame} IDs · Ød ${"%.3f".format(result.meanMatchDistance)} · Analyse ${result.analysisCostMs}ms · Video ${result.videoSampleCostMs}ms/${result.videoIntervalMs}ms"
                                    updateLiveLatencyStatus()
                                    adaptiveVisualQuantizerStatus.text = adaptiveVisualQuantizerText()
                                    if (visualFrameCount % 2L == 0L) updateMemoryStats()
                                }
                                if (visualFrameCount % 3L == 0L) syncExpressionRenderers()
                                if (visualFrameCount % 6L == 0L) maybeEmitCrossModalTextPulse("camera")
                                if (visualFrameCount % 2L == 0L) refreshGraph()
                            }
                        },
                        onVideoFrame = { video ->
                            if (!cameraRunning) return@VisualGridAnalyzer
                            livePipeline.offerFrame(video)
                            scheduleLowLatencyRender()
                        },
                        learningEnabled = { memory.cognitiveMode() == CognitiveMode.LEARN && cameraInputEnabled }
                    )
                visualAnalyzer = visualAnalyzerInstance
                visualAnalyzerInstance.setPerformanceProfile(performanceGovernor.snapshot().profile)
                analysis.setAnalyzer(cameraExecutor, visualAnalyzerInstance)

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                cameraRunning = true
                syncExpressionRenderers()
                cameraStatus.text = "Optical Feed: AKTIV · ${memory.cognitiveMode().label} · coarse→fine"
                cameraButton.isEnabled = false
                cameraStopButton.isEnabled = true
                refreshSensorUiState()
            } catch (e: Exception) {
                cameraRunning = false
                cameraStatus.text = "Kamera: Startfehler ${e.javaClass.simpleName}"
                cameraButton.isEnabled = true
                cameraStopButton.isEnabled = false
            }
        }, mainExecutor)
    }

    private fun stopCameraLearning() {
        if (!::cameraButton.isInitialized) return
        if (!cameraRunning && cameraProvider == null) return
        cameraRunning = false
        syncExpressionRenderers()
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        cameraProvider = null
        visualAnalyzer = null
        livePipeline.clearFrames()
        adaptiveVisualQuantizer.saveNow()
        cameraStatus.text = "Optical Feed: AUS"
        if (::adaptiveVisualQuantizerStatus.isInitialized) adaptiveVisualQuantizerStatus.text = adaptiveVisualQuantizerText()
        cameraButton.isEnabled = true
        cameraStopButton.isEnabled = false
        refreshSensorUiState()
        updateMemoryStats()
        refreshGraph()
    }

    private fun selectedIntervalMs(): Long = when (intervalSpinner.selectedItemPosition) {
        0 -> 5_000L
        1 -> 15_000L
        2 -> 30_000L
        3 -> 60_000L
        4 -> 300_000L
        5 -> 900_000L
        else -> 1_800_000L
    }

    private fun updateConsolidationStatus() {
        if (!::consolidationStatus.isInitialized) return
        val interval = selectedIntervalMs()
        val remaining = if (running) {
            (interval - (System.currentTimeMillis() - lastConsolidationMs)).coerceAtLeast(0L)
        } else interval
        val seconds = (remaining + 999L) / 1000L
        val label = intervalSpinner.selectedItem?.toString() ?: "—"
        consolidationStatus.text = "Archive Consolidation: $label · nächste in ${seconds}s\n$lastConsolidationSummary"
    }

    private fun refreshEncoderBenchmarkStatus() {
        if (!::encoderBenchmarkStatus.isInitialized) return
        encoderBenchmark.updateMemory(memory.stats())
        val b = encoderBenchmark.summary()
        if (b.pairedFrames <= 0L) {
            encoderBenchmarkStatus.text = if (b.active) "Konvergenztest: bereit · noch keine PCM-Frames" else "Konvergenztest: AUS"
            return
        }
        fun line(name: String, t: EncoderTrackSummary): String =
            "$name: akzeptiert ${t.acceptedFrames}/${t.inputFrames} · IDs ${t.uniqueIds} · neu/1000 ${"%.1f".format(t.newIdsPer1000Accepted)} · neu letzte256 ${"%.1f".format(t.recentNoveltyRate * 100.0)}% · Übergang-neu ${"%.1f".format(t.recentTransitionNoveltyRate * 100.0)}% · H ${"%.2f".format(t.entropyBits)} bit · +Makro ${t.macroDelta}"
        encoderBenchmarkStatus.text =
            "Konvergenztest: ${if (b.active) "AKTIV" else "GESTOPPT"} · gemeinsame PCM-Frames ${b.pairedFrames}\n" +
                line("FFT", b.fft) + "\n" +
                line("DIRECT", b.direct) + "\n" +
                line("ADAPTIVE", b.adaptive) + "\n" +
                b.interpretation
    }

    private fun captureHardTestSample(saveMemory: Boolean) {
        if (!::hardTest.isInitialized || !::adaptiveQuantizer.isInitialized || !::adaptiveVisualQuantizer.isInitialized) return
        if (saveMemory) memory.saveNow()
        hardTest.capture(memory.stats(), adaptiveQuantizer.stats(), adaptiveVisualQuantizer.stats())
        refreshHardTestStatus()
    }

    private fun refreshHardTestStatus() {
        if (!::hardTestStatus.isInitialized || !::hardTest.isInitialized) return
        val h = hardTest.summary()
        if (h.samples == 0) {
            hardTestStatus.text = "Continuity Audit: ${if (h.running) "LÄUFT" else "AUS"} · noch keine Samples"
            return
        }
        val hours = h.elapsedMs / 3_600_000.0
        val heapMb = h.latestHeapBytes / (1024.0 * 1024.0)
        val heapDeltaMb = h.heapDeltaBytes / (1024.0 * 1024.0)
        val storageMb = h.latestStorageBytes / (1024.0 * 1024.0)
        val storageDeltaMb = h.storageDeltaBytes / (1024.0 * 1024.0)
        val early = h.earlyMacroRate?.let { "%.2f".format(it) } ?: "—"
        val recent = h.recentMacroRate?.let { "%.2f".format(it) } ?: "—"
        hardTestStatus.text =
            "Continuity Audit: ${if (h.running) "LÄUFT" else "GESTOPPT"} · ${h.samples} Samples · ${"%.2f".format(hours)} h · ΔObs ${h.observationsDelta}\n" +
            "Makros Δ ${h.macroDelta} · Relationen Δ ${h.relationDelta} · Makros/10k Obs ${"%.2f".format(h.macrosPer10kObservations)} · Trend $early→$recent (${h.macroGrowthTrend})\n" +
            "Varianz: Audio ${"%.1f".format(h.latestAudioNovelty * 100.0)}% · Vision ${"%.1f".format(h.latestVisualNovelty * 100.0)}% · Kompression ${"%.1f".format(h.latestCompressionGain * 100.0)}%\n" +
            "Prediction Intervall: ${h.intervalPredictionHits}/${h.intervalPredictionChecks} (${"%.1f".format(h.intervalPredictionHitRate * 100.0)}%) · vergessen Δ ${h.prunedDelta}\n" +
            "Store ${"%.2f".format(storageMb)} MB (Δ ${"%+.2f".format(storageDeltaMb)} MB) · Heap ${"%.1f".format(heapMb)} MB (Δ ${"%+.1f".format(heapDeltaMb)} MB)\n" +
            "disk-backed ${h.latestDiskBackedMacros} Makros · Token-Reads ${h.latestTokenReads}"
    }

    private fun adaptiveQuantizerText(): String {
        val s = adaptiveQuantizer.stats()
        return "Adaptive-Codebook: ${s.prototypes} Prototypen · offen · Frames ${s.acceptedFrames} · neu ${s.createdIds} · reuse ${s.reusedIds} · Neuheit letzte256 ${"%.1f".format(s.recentNoveltyRate * 100.0)}% · ØDist ${"%.3f".format(s.meanMatchDistance)}"
    }

    private fun adaptiveVisualQuantizerText(): String {
        val s = adaptiveVisualQuantizer.stats()
        return "Visual-Codebook: ${s.prototypes} Prototypen · offen · Patches ${s.acceptedPatches} · neu ${s.createdIds} · reuse ${s.reusedIds} · Neuheit letzte256 ${"%.1f".format(s.recentNoveltyRate * 100.0)}% · ØDist ${"%.3f".format(s.meanMatchDistance)}"
    }

    private fun updateMemoryStats() {
        if (!settingsOpen) return
        val text = memoryText()
        if (::memoryStats.isInitialized && livePage.visibility == View.VISIBLE) memoryStats.text = text
        if (::labMemoryStats.isInitialized && labPage.visibility == View.VISIBLE) labMemoryStats.text = text
    }

    private fun memoryText(): String {
        val s = memory.stats()
        adaptiveQuantizer.updateStructureContext(s.adaptiveAudioMacros, s.macroRelations)
        adaptiveVisualQuantizer.updateStructureContext(s.adaptiveVisualMacros, s.crossModalMacros)
        val levelText = if (s.levelCounts.isEmpty()) "noch keine" else s.levelCounts.entries.joinToString(" · ") { "${AbstractionTier.labelForMacroDepth(it.key)}:${it.value}" }
        return "Case State: ${memory.cognitiveMode().label} · ${if (memory.cognitiveMode() == CognitiveMode.LEARN) "PERSISTENT WRITES AN" else "READ-ONLY"}\n" +
            "Quanten: ${s.persistentCodes} | Kandidaten: ${s.temporaryCodes} | Beobachtungen: ${s.observations}\n" +
            "Audio-Alphabete: FFT ${s.fftAudioCodes} Q/${s.fftAudioMacros} M · Direct ${s.directAudioCodes} Q/${s.directAudioMacros} M · Adaptive ${s.adaptiveAudioCodes} Q/${s.adaptiveAudioMacros} M\n" +
            "Vision: adaptive ${s.adaptiveVisualCodes} Q/${s.adaptiveVisualMacros} M · visuell gesamt ${s.visualPersistentCodes} Q\n" +
            "Crossmodal: ${s.crossModalMacros} Makros · ${s.crossModalCandidates} Kandidaten · Aktivierungen ${s.crossModalActivations} · stärkster Kandidat ${s.strongestCrossModalSupport}/4\n" +
            "Makros: ${s.macroCodes} | $levelText | Relationen: ${s.macroRelations}\n" +
            "Archive Layers: BASE ${s.baseKnowledgeMacros} · PERSONAL ${s.personalKnowledgeMacros} · DERIVED ${s.derivedKnowledgeMacros} | Base ${if (s.basePackId.isBlank()) "—" else s.basePackId + " v" + s.basePackVersion} · ${if (s.baseKnowledgeEnabled) "AN" else "AUS/BLANK"} | kompatibel ${if (s.baseCompatibilityOk) "ja" else "NEIN"}\n" +
            "Working Set: ${s.activeMacros} | erinnert: ${s.retrievedMacros} | Austausch: ${s.overexchangePairs} | Ø|R| ${"%.3f".format(s.meanAbsOverexchange)}\n" +
            "Retrieval Control: ${s.recallRetrieved}/${s.recallCandidates} materialisiert · verworfen ${s.recallRejected} · InfoGain Ø ${"%.3f".format(s.recallMeanInformationGain)} · Recall-Faktor ${"%.2f".format(s.recallFactor)} · schwach verfallen ${s.weakCandidatesExpired}\n" +
            "Branch Forecast: ${s.predictionRawCandidates}→${s.predictionCandidates} Kandidaten | Top ${"%.3f".format(s.predictionTopConfidence)} | Coverage ${s.predictionHits}/${s.predictionChecks} (${"%.1f".format(s.predictionHitRate * 100.0)}%) | Top-1 ${s.predictionTop1Hits}/${s.predictionTop1Checks} (${"%.1f".format(s.predictionTop1HitRate * 100.0)}%) vs Baseline ${"%.1f".format(s.predictionBaselineHitRate * 100.0)}% | Lift ${if (s.predictionLift >= 0.0) "+" else ""}${"%.1f".format(s.predictionLift * 100.0)} pp\n" +
            "Live Branch: ${s.hybridSteps} interne Schritte | letzter Seed ${if (s.hybridSeedId == 0) "—" else s.hybridSeedId}\n" +
            "Branch Walk: ${s.dreamSteps} Schritte | P ${s.dreamPredictionHops} · R ${s.dreamRelationHops} · S ${s.dreamSubstitutionHops} · X ${s.dreamCrossModalHops} · Restart ${s.dreamRestarts} | letzter Modus ${s.dreamLastMode}\n" +
            "Höchster aktiver Tier: ${s.highestActiveTier} · höchste Reife ${"%.2f".format(s.highestActiveMaturityScore)}\n" +
            "Templates: ${s.templateCodes} | aktiv: ${s.activeTemplates} | Varianten: ${s.templateVariantLinks} | max/Slot: ${s.widestTemplateVariants}\n" +
            "Substitutionsklassen: ${s.substitutionClasses} | aktiv: ${s.activeSubstitutionClasses} | Mitglieder: ${s.substitutionMembers} | stärkste Rollenstütze: ${s.strongestSubstitutionSupport}\n" +
            "Relevanz aktiv: Ø ${"%.3f".format(s.meanActiveRelevance)} | max ${"%.3f".format(s.highestActiveRelevance)} | Kontextentropie Ø ${"%.3f".format(s.meanActiveContextEntropy)} | Generalität max ${"%.3f".format(s.highestActiveGenerality)}\n" +
            "Adaptive Hierarchie: Q ${s.hierarchyBaseInputTokens} Token | Makro-Emissionen ${s.hierarchyBaseMacroEmissions} | gespart ${s.hierarchyBaseSavedTokens} (${"%.1f".format(s.hierarchyBaseCompressionGain * 100.0)}%) | ØSpan ${"%.2f".format(s.hierarchyMeanAcceptedSpan)} | Auto-Makros ${s.hierarchyAutoPromotions}\n" +
            "Routing Desk: Treffer ${s.routingHits}/${s.routingQueries} (${"%.1f".format(s.routingHitRate * 100.0)}%) | ØSpan ${"%.2f".format(s.routingMeanMatchedSpan)} | Signaturchecks ${s.routingSignatureChecks} | vermieden ${s.routingChecksAvoided} | Index ${s.routingIndexStreams} Streams/${s.routingIndexAnchors} Anker/${s.routingIndexSignatures} Makros\n" +
            "Pruning Protocol: Läufe ${s.competitionRuns} | entfernt gesamt ${s.competitionMacrosPruned} M/${s.competitionRelationsPruned} R/${s.competitionCandidatesPruned} K | letzter Lauf ${s.competitionLastPruned} entfernt · ${s.competitionLastProtected} geschützt · Druckgruppen ${s.competitionLastPressuredGroups}\n" +
            "Deep Archive: ${s.storageBackend} · ${s.storageBytes} Bytes · disk-backed Makros ${s.storageDiskBackedMacros}/${s.macroCodes} · Token-Lesezugriffe ${s.storageTokenReads}\n" +
            "Open Case Space: offen · aktuell ${s.temporaryCodes} temporäre Kandidaten"
    }

    private fun consolidationSummary(result: ConsolidationResult): String {
        val levelPart = if (result.highestNewLevel > 0) " · höchste neue Stufe ${AbstractionTier.labelForMacroDepth(result.highestNewLevel)}" else ""
        val templatePart = if (result.templatesDiscovered > 0) " · +${result.templatesDiscovered} Templates" else ""
        val classPart = if (result.substitutionClassesDiscovered > 0) " · +${result.substitutionClassesDiscovered} Substitutionsklassen" else ""
        val competitionPart = if (result.competitionPruned > 0) " · -${result.competitionPruned} schwache Blatt-Makros" else ""
        return "Letzte Änderung: +${result.microPromoted} Mikro · +${result.macroPromoted} Abstraktionsknoten · davon ${result.sequencePromoted} Sequenzen$levelPart$templatePart$classPart$competitionPart"
    }

    private fun setAudioEncoderMode(mode: AudioEncoderMode) {
        if (audioEncoderMode == mode) return
        if (running) stopAudio()
        audioEncoderMode = mode
        audioEncoderModeStore.save(mode)
        if (::audioEncoderStatus.isInitialized) audioEncoderStatus.text = "Signal-Parser: ${mode.label}"
        if (::labAudioEncoderStatus.isInitialized) labAudioEncoderStatus.text = "Aktiv: ${mode.label}"
        if (::frequency.isInitialized) {
            frequency.text = when (mode) {
                AudioEncoderMode.FFT_FREQUENCY -> "Dominante Frequenz: — Hz"
                AudioEncoderMode.DIRECT_PATTERN -> "Direktmuster: —"
                AudioEncoderMode.ADAPTIVE_PATTERN -> "Adaptive-ID: —"
            }
        }
        if (::frequencyId.isInitialized) {
            frequencyId.text = when (mode) {
                AudioEncoderMode.FFT_FREQUENCY -> "Frequenz-ID: —"
                AudioEncoderMode.DIRECT_PATTERN -> "Pattern-ID: —"
                AudioEncoderMode.ADAPTIVE_PATTERN -> "Adaptive Pattern-ID: —"
            }
        }
        if (::strength.isInitialized) strength.text = "Signalstärke: —"
    }

    private fun stopAudio() {
        if (!running) return
        running = false
        syncExpressionRenderers()
        try { audioRecord?.stop() } catch (_: Exception) { }
        try { worker?.join(500) } catch (_: InterruptedException) { }
        releaseEchoCanceler()
        try { audioRecord?.release() } catch (_: Exception) { }
        audioRecord = null
        worker = null
        adaptiveQuantizer.saveNow()
        status.text = "Audio-Feed: AUS"
        startButton.isEnabled = true
        stopButton.isEnabled = false
        refreshSensorUiState()
        val result = memory.consolidate()
        lastConsolidationSummary = consolidationSummary(result)
        updateConsolidationStatus()
        updateMemoryStats()
        if (::encoderBenchmark.isInitialized && encoderBenchmark.isActive()) refreshEncoderBenchmarkStatus()
        refreshGraph()
    }

    private fun polishProductTree(root: ViewGroup) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            when (child) {
                is Button -> {
                    child.isAllCaps = false
                    child.textSize = 13f
                    child.setTextColor(Color.rgb(235, 237, 244))
                    child.background = roundedBackground(
                        Color.argb(46, 255, 255, 255), 16f, Color.argb(28, 255, 255, 255), 1
                    )
                    child.minHeight = dp(48)
                    attachPressMotion(child)
                }
                is CheckBox -> {
                    child.textSize = 13f
                    child.setTextColor(Color.rgb(218, 222, 232))
                    child.setPadding(child.paddingLeft, dp(8), child.paddingRight, dp(8))
                    attachPressMotion(child)
                }
                is EditText -> {
                    child.setTextColor(Color.WHITE)
                    child.setHintTextColor(Color.rgb(130, 136, 150))
                    child.background = roundedBackground(
                        Color.argb(34, 255, 255, 255), 14f, Color.argb(34, 255, 255, 255), 1
                    )
                    child.setPadding(dp(12), dp(10), dp(12), dp(10))
                }
            }
            if (child is ViewGroup) polishProductTree(child)
        }
    }

    private fun roundedBackground(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeWidthDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
            if (strokeColor != null && strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }

    private fun attachPressMotion(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.82f).setDuration(70L).start()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150L)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.15f)).start()
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120L).start()
                }
            }
            false
        }
    }

    private fun productButton(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 12f
        letterSpacing = 0.08f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(232, 235, 242))
        setPadding(dp(10), dp(12), dp(10), dp(12))
        background = roundedBackground(Color.argb(38, 255, 255, 255), 16f, Color.argb(28, 255, 255, 255), 1)
        isClickable = true
        isFocusable = true
        attachPressMotion(this)
    }

    /**
     * Fixed-height diagnostic slot for live values. The settings sheet must not relayout
     * every time a counter grows, a percentage appears or a status string changes length.
     * Keeping a stable line budget removes the visible vertical "jumping" while sensors run.
     */
    private fun stableDiagnosticLabel(
        textValue: String,
        size: Float = 13f,
        top: Int = 4,
        lines: Int = 1
    ): TextView = TextView(this).apply {
        text = textValue
        textSize = size
        typeface = Typeface.MONOSPACE
        setTextColor(Color.rgb(218, 224, 228))
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(top), dp(8), dp(2))
        minLines = lines
        maxLines = lines
        setLines(lines)
        ellipsize = TextUtils.TruncateAt.END
        background = roundedBackground(
            Color.argb(22, 24, 38, 31),
            8f,
            Color.argb(28, 76, 226, 128),
            1
        )
    }

    private fun label(textValue: String, size: Float = 18f, top: Int = 16): TextView = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(0, dp(top), 0, 0)
    }

    private fun buttonParams(topDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(topDp) }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f
    ).apply { marginStart = dp(2); marginEnd = dp(2) }

    private fun weightedLabelParams() = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1.2f
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onResume() {
        super.onResume()
        if (::surfaceVideo.isInitialized) surfaceVideo.onResume()
    }

    override fun onPause() {
        if (::surfaceVideo.isInitialized) surfaceVideo.onPause()
        // Text-macro learning can happen without an audio/camera session. Persist the
        // shared archive whenever the app leaves the foreground so normal APK updates
        // do not lose the newest recursive text structure.
        if (::memory.isInitialized) runCatching { memory.saveNow() }
        persistDreamWorldState()
        super.onPause()
    }

    override fun onDestroy() {
        persistDreamWorldState()
        uiHandler.removeCallbacks(hardTestLoop)
        if (::adaptiveQuantizer.isInitialized) adaptiveQuantizer.saveNow()
        if (::adaptiveVisualQuantizer.isInitialized) adaptiveVisualQuantizer.saveNow()
        stopMemoryPlayback(clear = false)
        stopHybridPlayback(clear = false)
        stopCameraLearning()
        try { cameraExecutor.shutdownNow() } catch (_: Exception) { }
        stopAudio()
        unifiedAudio.stop()
        if (::captureRecorder.isInitialized && captureRecorder.isRecording()) {
            unifiedAudio.setPcmTap(null)
            captureRecorder.stop({}, {})
        }
        CaptureForegroundService.stop(this)
        stopContinuousOrchestra()
        releaseEchoCanceler()
        super.onDestroy()
    }
}
