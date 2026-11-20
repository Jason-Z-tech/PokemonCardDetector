package de.pokemoncarddetector.ui.scan

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.camera.core.CameraSelector
import androidx.camera.core.TorchState
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.content.pm.PackageManager
import android.util.Size
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.Translate
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.foundation.layout.BoxWithConstraints
import de.pokemoncarddetector.data.AppSettings
import de.pokemoncarddetector.data.Variants
import de.pokemoncarddetector.data.db.CardItem
import de.pokemoncarddetector.recognition.FrameGeometry
import de.pokemoncarddetector.ui.appViewModel
import de.pokemoncarddetector.ui.card.CardSheet
import de.pokemoncarddetector.ui.components.CARD_ASPECT
import de.pokemoncarddetector.ui.components.CardTile
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onSearch: (String) -> Unit, onOpenSet: (String) -> Unit) {
    val vm = appViewModel { c, _ -> ScanViewModel(c.repository, c.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val view = LocalView.current

    val activity = context as? android.app.Activity
    fun cameraGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var hasPermission by remember { mutableStateOf(cameraGranted()) }
    // Nach zweimaligem Ablehnen zeigt Android keinen Dialog mehr -> Weg zu den Einstellungen anbieten
    var permanentlyDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permanentlyDenied = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }
    // Rueckkehr aus den Einstellungen: Berechtigung neu pruefen
    LifecycleResumeEffect(Unit) {
        if (!hasPermission && cameraGranted()) hasPermission = true
        onPauseOrDispose { }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.analyzeUri(context, uri)
    }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is ScanEvent.Message -> snackbar.showSnackbar(e.text)
                is ScanEvent.Added -> {
                    val r = snackbar.showSnackbar(e.text, actionLabel = "Rückgängig", duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) vm.restore(e.cardId, e.previous)
                }
            }
        }
    }
    // Kamera-Bildschirm ist dunkel: helle Symbole in der Statusleiste
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val old = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (old != null) controller.isAppearanceLightStatusBars = old }
    }
    // Kurze Vibration bei neuer Erkennung
    LaunchedEffect(state.detection?.serial) {
        if (state.detection != null && settings.vibrate) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val controller = remember { createController(context, vm, executor) }
    DisposableEffect(Unit) {
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            executor.shutdown()
        }
    }
    // Kamera ist erst nach der asynchronen Initialisierung benutzbar (sonst stuerzt takePicture ab)
    var cameraReady by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(controller) {
        val future = controller.initializationFuture
        future.addListener({
            if (runCatching { future.get() }.isFailure) {
                cameraError = "Kamera konnte nicht gestartet werden – nutze die Galerie."
                return@addListener
            }
            runCatching {
                if (!controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) && controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    controller.cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                }
            }
            cameraReady = true
        }, ContextCompat.getMainExecutor(context))
        onDispose { }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Taschenlampe: CameraX ist die Wahrheit (wird beim Pausieren der App automatisch ausgeschaltet)
    var torchOn by remember { mutableStateOf(false) }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = androidx.lifecycle.Observer<Int> { torchOn = it == TorchState.ON }
        controller.torchState.observe(lifecycleOwner, observer)
        onDispose { controller.torchState.removeObserver(observer) }
    }
    val takePhoto: () -> Unit = takePhoto@{
        if (!cameraReady || state.busy) return@takePhoto
        vm.onShutter()
        runCatching {
            controller.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) = vm.analyzeCapture(image)
                override fun onError(exception: ImageCaptureException) =
                    vm.onCaptureFailed("Foto fehlgeschlagen: ${exception.message ?: "unbekannter Fehler"}")
            })
        }.onFailure { vm.onCaptureFailed("Kamera ist noch nicht bereit – bitte kurz warten.") }
    }

    var showAlternatives by remember { mutableStateOf(false) }
    var openCard by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showAlternatives, openCard) { vm.setPaused(showAlternatives || openCard != null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (hasPermission) {
            CameraPreview(vm, controller, onError = { cameraError = it })
        }
        val detection = state.detection
        ScanLayout(
            hasPermission = hasPermission,
            autoAdd = settings.autoAdd,
            japanese = settings.scanJapanese,
            status = cameraError ?: state.status,
            showStatus = detection == null,
            detected = detection?.confident == true,
            // Kamera startet noch -> Ladeanzeige; Kamera-Fehler -> Ausloeser deaktiviert (Galerie geht weiter)
            busy = state.busy || (!cameraReady && cameraError == null),
            shutterEnabled = cameraError == null,
            hasFlash = hasPermission && cameraReady && controller.cameraInfo?.hasFlashUnit() == true,
            torchOn = torchOn,
            onFrame = { vm.frame = it },
            onToggleAutoAdd = { vm.setAutoAdd(!settings.autoAdd) },
            onToggleJapanese = { vm.setJapanese(!settings.scanJapanese) },
            onGallery = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onTorch = { runCatching { controller.enableTorch(!torchOn) } },
            onShutter = takePhoto,
            noPermission = {
                NoPermission(
                    permanentlyDenied = permanentlyDenied,
                    onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        )
                    },
                    onGallery = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
            },
        ) {
            SnackbarHost(snackbar)
            if (detection != null) {
                DetectionCard(
                    detection = detection,
                    settings = settings,
                    onAdd = { vm.add(detection.best.card.id, detection.variant) },
                    onSelectVariant = vm::selectVariant,
                    onDismiss = vm::dismissDetection,
                    onAlternatives = { showAlternatives = true },
                    onOpen = { openCard = detection.best.card.id },
                    onSearch = { onSearch(detection.best.card.nameEn.ifBlank { detection.best.card.name }) },
                )
            }
        }
    }

    if (showAlternatives) {
        val d = state.detection
        ModalBottomSheet(onDismissRequest = { showAlternatives = false }) {
            if (d != null) {
                AlternativesList(
                    detection = d,
                    settings = settings,
                    onChoose = { vm.choose(it); showAlternatives = false },
                    onAdd = { vm.add(it) },
                    onSearch = { showAlternatives = false; onSearch(d.best.card.nameEn.ifBlank { d.best.card.name }) },
                )
            }
        }
    }
    openCard?.let { id -> CardSheet(cardId = id, onDismiss = { openCard = null }, onOpenSet = onOpenSet) }
}

private fun createController(
    context: android.content.Context,
    vm: ScanViewModel,
    executor: java.util.concurrent.Executor,
): LifecycleCameraController = LifecycleCameraController(context).apply {
    setEnabledUseCases(CameraController.IMAGE_ANALYSIS or CameraController.IMAGE_CAPTURE)
    // Hohe Aufloesung fuer die Analyse, damit die kleine Kartennummer lesbar ist
    imageAnalysisResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
        )
        .build()
    previewResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .build()
    // Fotos: gross genug fuer die Texterkennung, aber nicht volle Sensoraufloesung (Speicher/Tempo)
    imageCaptureResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(Size(2560, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
        )
        .build()
    imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
    setImageAnalysisAnalyzer(executor, vm.recognizer.analyzer { lines, w, h -> vm.onFrame(lines, w, h) })
}

@Composable
private fun CameraPreview(vm: ScanViewModel, controller: LifecycleCameraController, onError: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        try {
            controller.bindToLifecycle(lifecycleOwner)
        } catch (e: Exception) {
            onError("Keine passende Kamera gefunden – nutze die Galerie.")
        }
        onDispose { controller.unbind() }
    }
    // ML Kit soll Koordinaten im Hochformat der Vorschau liefern (App ist auf Hochformat fixiert),
    // auch wenn das Handy gedreht wird -> feste Drehung = Sensorausrichtung
    val hostView = LocalView.current
    LaunchedEffect(controller) {
        while (controller.cameraInfo == null) kotlinx.coroutines.delay(100)
        val displayRotation = hostView.display?.rotation ?: android.view.Surface.ROTATION_0
        vm.recognizer.fixedRotationDegrees = runCatching { controller.cameraInfo?.getSensorRotationDegrees(displayRotation) }.getOrNull()
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                this.controller = controller
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Bedienoberflaeche ueber der Kameravorschau: Schalter oben, darunter der Rahmen im Kartenformat im
 * freien Platz, die Statuszeile direkt unter dem Rahmen und unten Galerie / Ausloeser / Licht.
 * Nichts davon liegt im Rahmen. [onFrame] meldet den Rahmen in Pixeln relativ zu dieser Flaeche
 * (= Groesse der Kameravorschau), damit die Erkennung nur Text im Rahmen auswertet.
 * [overlay] (Ergebnis-Karte, Snackbar) liegt unten ueber der Statuszeile, direkt ueber den Knoepfen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScanLayout(
    hasPermission: Boolean,
    autoAdd: Boolean,
    japanese: Boolean,
    status: String,
    showStatus: Boolean,
    detected: Boolean,
    busy: Boolean,
    shutterEnabled: Boolean,
    hasFlash: Boolean,
    torchOn: Boolean,
    onFrame: (FrameGeometry) -> Unit,
    onToggleAutoAdd: () -> Unit,
    onToggleJapanese: () -> Unit,
    onGallery: () -> Unit,
    onTorch: () -> Unit,
    onShutter: () -> Unit,
    noPermission: @Composable () -> Unit,
    overlay: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var slot by remember { mutableStateOf<Rect?>(null) }
    var bottomBarHeight by remember { mutableIntStateOf(0) }
    var topBarHeight by remember { mutableIntStateOf(0) }
    val frame = slot?.let { with(density) { fitCardFrame(it, marginX = 20.dp.toPx(), marginY = 8.dp.toPx()) } }
    val currentOnFrame by rememberUpdatedState(onFrame)
    LaunchedEffect(frame, rootSize) {
        if (rootSize == IntSize.Zero) return@LaunchedEffect
        // Kein Platz fuer einen Rahmen (winziges Fenster): ganze Vorschau – nie die Geometrie eines alten Layouts
        val f = frame ?: Rect(0f, 0f, rootSize.width.toFloat(), rootSize.height.toFloat())
        currentOnFrame(FrameGeometry(rootSize.width, rootSize.height, f.left, f.top, f.right, f.bottom))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = it },
    ) {
        if (hasPermission && frame != null) FrameOverlay(frame, detected)

        Column(Modifier.fillMaxSize()) {
            ScanToggles(
                autoAdd = autoAdd,
                japanese = japanese,
                onToggleAutoAdd = onToggleAutoAdd,
                onToggleJapanese = onToggleJapanese,
                modifier = Modifier
                    .onSizeChanged { topBarHeight = it.height }
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (hasPermission) {
                // Freier Platz fuer den Rahmen (die Spalte beginnt bei 0,0 der Flaeche)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onGloballyPositioned { slot = it.boundsInParent() },
                )
                StatusLine(status = status, visible = showStatus)
                ScanBottomBar(
                    busy = busy,
                    shutterEnabled = shutterEnabled,
                    hasFlash = hasFlash,
                    torchOn = torchOn,
                    onGallery = onGallery,
                    onTorch = onTorch,
                    onShutter = onShutter,
                    modifier = Modifier.onSizeChanged { bottomBarHeight = it.height },
                )
            } else {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) { noPermission() }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    top = with(density) { topBarHeight.toDp() },
                    bottom = with(density) { (if (hasPermission) bottomBarHeight else 0).toDp() },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = overlay,
        )
    }
}

/** Groesster Rahmen im Kartenformat, der mit Rand in [slot] passt, mittig; null wenn kein Platz. */
internal fun fitCardFrame(slot: Rect, marginX: Float, marginY: Float): Rect? {
    val maxW = slot.width - 2 * marginX
    val maxH = slot.height - 2 * marginY
    if (maxW <= 0f || maxH <= 0f) return null
    val fw = minOf(maxW, maxH * CARD_ASPECT)
    val fh = fw / CARD_ASPECT
    val left = slot.left + (slot.width - fw) / 2f
    val top = slot.top + (slot.height - fh) / 2f
    return Rect(left, top, left + fw, top + fh)
}

private val ScanGreen = Color(0xFF4CAF50)
private val TextShadow = Shadow(Color.Black.copy(alpha = 0.7f), offset = Offset(0f, 1f), blurRadius = 6f)

@Composable
private fun FrameOverlay(frame: Rect, detected: Boolean) {
    val accent = if (detected) ScanGreen else Color.White
    Canvas(Modifier.fillMaxSize()) {
        val r = minOf(20.dp.toPx(), frame.width * 0.07f)
        // Abdunkeln ausserhalb des Rahmens (Maske statt Ausstanzen): Bedienelemente bleiben auch vor hellem
        // Hintergrund lesbar
        val scrim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(RoundRect(frame, CornerRadius(r)))
        }
        drawPath(scrim, Color.Black.copy(alpha = 0.6f))
        drawRoundRect(accent.copy(alpha = 0.5f), frame.topLeft, frame.size, CornerRadius(r), style = Stroke(width = 1.5.dp.toPx()))
        // Betonte Ecken
        val len = minOf(40.dp.toPx(), frame.width / 4f)
        val (l, t, rt, b) = listOf(frame.left, frame.top, frame.right, frame.bottom)
        val path = Path().apply {
            moveTo(l, t + len); lineTo(l, t + r)
            arcTo(Rect(l, t, l + 2 * r, t + 2 * r), 180f, 90f, false); lineTo(l + len, t)
            moveTo(rt - len, t); lineTo(rt - r, t)
            arcTo(Rect(rt - 2 * r, t, rt, t + 2 * r), 270f, 90f, false); lineTo(rt, t + len)
            moveTo(rt, b - len); lineTo(rt, b - r)
            arcTo(Rect(rt - 2 * r, b - 2 * r, rt, b), 0f, 90f, false); lineTo(rt - len, b)
            moveTo(l + len, b); lineTo(l + r, b)
            arcTo(Rect(l, b - 2 * r, l + 2 * r, b), 90f, 90f, false); lineTo(l, b - len)
        }
        drawPath(path, accent, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanToggles(
    autoAdd: Boolean,
    japanese: Boolean,
    onToggleAutoAdd: () -> Unit,
    onToggleJapanese: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Bricht bei wenig Platz / grosser Schrift um, statt abgeschnitten zu werden
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ScanToggle("Auto-Hinzufügen", autoAdd, if (autoAdd) Icons.Default.Check else Icons.Default.AutoMode, onToggleAutoAdd)
        ScanToggle("Japanisch", japanese, if (japanese) Icons.Default.Check else Icons.Default.Translate, onToggleJapanese)
    }
}

@Composable
private fun ScanToggle(label: String, checked: Boolean, icon: ImageVector, onToggle: () -> Unit) {
    Surface(
        checked = checked,
        onCheckedChange = { onToggle() },
        shape = RoundedCornerShape(50),
        color = if (checked) Color.White else Color.Black.copy(alpha = 0.6f),
        contentColor = if (checked) Color(0xFF1B1B1F) else Color.White,
        border = if (checked) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
        modifier = Modifier.semantics { stateDescription = if (checked) "an" else "aus" },
    ) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2)
        }
    }
}

@Composable
private fun StatusLine(status: String, visible: Boolean) {
    val style = MaterialTheme.typography.titleSmall
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        // So viele Zeilen, wie der laengste Live-Hinweis bei dieser Breite und Schriftgroesse braucht, sind fest
        // eingeplant: der Rahmen springt beim Scannen nicht. Seltene laengere Texte (Fehler) vergroessern das Feld.
        val density = LocalDensity.current
        val textWidth = with(density) { (maxWidth - 32.dp).roundToPx() }.coerceAtLeast(1)
        val lines = remember(textWidth, style, measurer) {
            ScanTexts.LIVE.maxOf { measurer.measure(it, style, constraints = Constraints(maxWidth = textWidth)).lineCount }
        }
        Text(
            "",
            style = style,
            minLines = lines,
            color = Color.Transparent,
            modifier = Modifier
                .padding(vertical = 10.dp)
                .clearAndSetSemantics { },
        )
        if (visible) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                contentColor = Color.White,
                shape = RoundedCornerShape(20.dp),
            ) {
                // Keine Live-Region: der Hinweis wechselt mit jedem Kamerabild, TalkBack wuerde ununterbrochen vorlesen
                Text(
                    status,
                    style = style,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ScanBottomBar(
    busy: Boolean,
    shutterEnabled: Boolean,
    hasFlash: Boolean,
    torchOn: Boolean,
    onGallery: () -> Unit,
    onTorch: () -> Unit,
    onShutter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SideButton(Icons.Default.PhotoLibrary, label = "Galerie", description = "Foto aus der Galerie wählen", checked = false, onClick = onGallery)
        }
        ShutterButton(busy = busy, enabled = shutterEnabled, onClick = onShutter)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (hasFlash) {
                SideButton(
                    if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    label = "Licht",
                    description = if (torchOn) "Licht ausschalten" else "Licht einschalten",
                    checked = torchOn,
                    onClick = onTorch,
                )
            }
        }
    }
}

@Composable
private fun SideButton(icon: ImageVector, label: String, description: String, checked: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .background(if (checked) Color.White else Color.Black.copy(alpha = 0.6f), CircleShape)
                .border(1.dp, Color.White.copy(alpha = if (checked) 0f else 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (checked) Color(0xFF1B1B1F) else Color.White)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(shadow = TextShadow),
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ShutterButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Foto aufnehmen und erkennen" }
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(if (busy) Color.White.copy(alpha = 0.55f) else Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Color(0xFF1B1B1F), strokeWidth = 3.dp)
            } else {
                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color(0xFF1B1B1F), modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
internal fun DetectionCard(
    detection: Detection,
    settings: AppSettings,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    onAlternatives: () -> Unit,
    onOpen: () -> Unit,
    onSearch: () -> Unit,
    onSelectVariant: (String) -> Unit,
) {
    val item = detection.best
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            // Bei wenig Platz (kleines Handy, grosse Schrift) scrollt der obere Teil – die Knoepfe bleiben immer ganz sichtbar
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (!detection.confident) {
                    Text(
                        "Nicht ganz sicher – stimmt diese Karte?",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardTile(
                        item = item,
                        showImage = settings.showImages,
                        language = settings.imageLanguage,
                        dimIfMissing = false,
                        showQuantityBadge = false,
                        modifier = Modifier
                            .width(72.dp)
                            .clickable(onClick = onOpen),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${item.setName} · Nr. ${item.numberLabel}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (item.quantity > 0) "Du hast: ${item.quantity}×" else "Neu für deine Sammlung!",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (item.quantity > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Verwerfen") }
                }
                if (item.variantList.size > 1) {
                    // Holo/Reverse sieht die Kamera nicht -> Variante hier waehlen
                    VariantPicker(
                        variants = item.variantList,
                        selected = detection.variant,
                        quantities = item.quantities,
                        onSelect = onSelectVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val add: @Composable (Modifier) -> Unit = { m ->
                Button(onClick = onAdd, modifier = m) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Hinzufügen")
                }
            }
            val other: @Composable (Modifier) -> Unit = { m ->
                if (detection.alternatives.isNotEmpty()) {
                    OutlinedButton(onClick = onAlternatives, modifier = m) { Text("Andere (${detection.alternatives.size})") }
                } else {
                    OutlinedButton(onClick = onSearch, modifier = m) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Suchen")
                    }
                }
            }
            if (LocalDensity.current.fontScale >= 1.5f) {
                // Sehr grosse Schrift: Knoepfe untereinander, damit "Hinzufügen" nicht mitten im Wort umbricht
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    add(Modifier.fillMaxWidth())
                    other(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    add(Modifier.weight(1f))
                    other(Modifier)
                }
            }
        }
    }
}

/**
 * Variantenwahl in genau einer Zeile: passen alle Varianten als Chips nebeneinander, werden sie gezeigt;
 * sonst ein Knopf "Variante: … ▾" mit der ganzen Liste. So wird nichts abgeschnitten und die Ergebnis-Karte
 * bleibt niedrig (die Knoepfe darunter bleiben sichtbar).
 */
@Composable
internal fun VariantPicker(
    variants: List<String>,
    selected: String,
    quantities: Map<String, Int>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun owned(v: String) = (quantities[v] ?: 0).let { if (it > 0) " ($it)" else "" }
    var open by remember { mutableStateOf(false) }
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val gap = 6.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val chips = subcompose("chips") {
            variants.forEach { v ->
                FilterChip(
                    selected = selected == v,
                    onClick = { onSelect(v) },
                    label = { Text(Variants.shortLabel(v) + owned(v), maxLines = 1) },
                )
            }
        }.map { it.measure(loose.copy(maxWidth = Constraints.Infinity)) }
        val total = chips.sumOf { it.width } + gap * (chips.size - 1).coerceAtLeast(0)
        if (total <= constraints.maxWidth) {
            val h = chips.maxOf { it.height }
            layout(constraints.maxWidth, h) {
                var x = 0
                chips.forEach { c -> c.placeRelative(x, (h - c.height) / 2); x += c.width + gap }
            }
        } else {
            val menu = subcompose("menu") {
                Box {
                    OutlinedButton(onClick = { open = true }) {
                        Text("Variante: " + Variants.shortLabel(selected) + owned(selected), modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        variants.forEach { v ->
                            DropdownMenuItem(
                                text = { Text(Variants.label(v) + owned(v)) },
                                onClick = { onSelect(v); open = false },
                                leadingIcon = {
                                    if (v == selected) Icon(Icons.Default.Check, contentDescription = "ausgewählt") else Spacer(Modifier.size(24.dp))
                                },
                            )
                        }
                    }
                }
            }.map { it.measure(loose) }
            val h = menu.maxOf { it.height }
            layout(constraints.maxWidth, h) { menu.forEach { it.placeRelative(0, 0) } }
        }
    }
}

@Composable
private fun AlternativesList(
    detection: Detection,
    settings: AppSettings,
    onChoose: (String) -> Unit,
    onAdd: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Column(Modifier.navigationBarsPadding()) {
        Text(
            "Welche Karte ist es?",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn {
            items(listOf(detection.best) + detection.alternatives, key = { it.card.id }) { item ->
                CandidateRow(item, settings, isBest = item.card.id == detection.best.card.id, onChoose = { onChoose(item.card.id) }, onAdd = { onAdd(item.card.id) })
            }
            item {
                TextButton(onClick = onSearch, modifier = Modifier.padding(16.dp)) {
                    Icon(Icons.Default.Search, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Nicht dabei? Manuell suchen")
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(item: CardItem, settings: AppSettings, isBest: Boolean, onChoose: () -> Unit, onAdd: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onChoose),
        leadingContent = {
            CardTile(item, settings.showImages, settings.imageLanguage, Modifier.width(52.dp), dimIfMissing = false, showQuantityBadge = false)
        },
        headlineContent = { Text(item.displayName + if (isBest) "  (bester Treffer)" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text("${item.setName} · ${item.setRelease.take(4)} · Nr. ${item.numberLabel}" + if (item.quantity > 0) " · ${item.quantity}× im Besitz" else "")
        },
        trailingContent = {
            FilledIconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Hinzufügen") }
        },
    )
}

@Composable
internal fun NoPermission(permanentlyDenied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit, onGallery: () -> Unit) =
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        // Zentriert, solange alles passt; sonst scrollbar (kleines Handy, grosse Schrift) – die Knoepfe bleiben erreichbar
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.CameraAlt, null, Modifier.size(if (LocalDensity.current.fontScale >= 1.5f) 40.dp else 64.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    if (permanentlyDenied) {
                        "Der Kamerazugriff wurde abgelehnt. Erlaube ihn in den App-Einstellungen unter „Berechtigungen“, um Karten zu scannen."
                    } else {
                        "Zum Scannen braucht die App Zugriff auf die Kamera. Die Bilder werden nur auf deinem Handy ausgewertet."
                    },
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                if (permanentlyDenied) {
                    Button(onClick = onOpenSettings) { Text("Einstellungen öffnen", textAlign = TextAlign.Center) }
                } else {
                    Button(onClick = onRequest) { Text("Kamera erlauben", textAlign = TextAlign.Center) }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onGallery) {
                    Icon(Icons.Default.PhotoLibrary, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Foto aus der Galerie", color = Color.White, textAlign = TextAlign.Center)
                }
            }
        }
    }
