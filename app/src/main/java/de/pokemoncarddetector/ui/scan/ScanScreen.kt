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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.FlashOff
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
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFloatingActionButton
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
            CameraPreview(vm, controller, detected = state.detection?.confident == true, onError = { cameraError = it })
        } else {
            NoPermission(
                permanentlyDenied = permanentlyDenied,
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    )
                },
            )
        }

        // Obere Leiste
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Karte scannen",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                RoundIcon(onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Foto aus Galerie")
                }
                if (hasPermission && cameraReady && controller.cameraInfo?.hasFlashUnit() == true) {
                    RoundIcon(onClick = { runCatching { controller.enableTorch(!torchOn) } }) {
                        Icon(
                            if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = if (torchOn) "Licht ausschalten" else "Licht einschalten",
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AutoAddChip(settings.autoAdd, onToggle = { vm.setAutoAdd(!settings.autoAdd) })
                FilterChip(
                    selected = settings.scanJapanese,
                    onClick = { vm.setJapanese(!settings.scanJapanese) },
                    label = { Text(if (settings.scanJapanese) "Japanische Karten" else "Japanisch", maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Black.copy(alpha = 0.55f),
                        labelColor = Color.White,
                    ),
                )
            }
            if (hasPermission) {
                Surface(color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White, shape = RoundedCornerShape(50)) {
                    Text(
                        cameraError ?: state.status,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // Untere Leiste: Ergebnis + Ausloeser
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnackbarHost(snackbar)
            val detection = state.detection
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
                Spacer(Modifier.height(10.dp))
            }
            if (hasPermission) {
                ShutterButton(busy = state.busy || !cameraReady, onClick = takePhoto)
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
private fun CameraPreview(vm: ScanViewModel, controller: LifecycleCameraController, detected: Boolean, onError: (String) -> Unit) {
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

    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        // Rahmen im Kartenformat: 78 % Breite, max. 58 % Hoehe, etwas oberhalb der Mitte
        var fw = w * 0.78f
        var fh = fw / CARD_ASPECT
        if (fh > h * 0.58f) {
            fh = h * 0.58f; fw = fh * CARD_ASPECT
        }
        val left = (w - fw) / 2f
        val top = (h - fh) * 0.38f
        val frameRect = Rect(left, top, left + fw, top + fh)
        LaunchedEffect(w, h) {
            vm.frame = FrameGeometry(w.toInt(), h.toInt(), frameRect.left, frameRect.top, frameRect.right, frameRect.bottom)
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
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            drawRect(Color.Black.copy(alpha = 0.45f))
            val radius = CornerRadius(18.dp.toPx())
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(frameRect.left, frameRect.top),
                size = frameRect.size,
                cornerRadius = radius,
                blendMode = BlendMode.Clear,
            )
            drawRoundRect(
                color = if (detected) Color(0xFF4CAF50) else Color.White,
                topLeft = Offset(frameRect.left, frameRect.top),
                size = frameRect.size,
                cornerRadius = radius,
                style = Stroke(width = 3.dp.toPx()),
            )
        }
    }
}

@Composable
private fun RoundIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.55f), contentColor = Color.White),
        modifier = Modifier.padding(start = 6.dp),
    ) { content() }
}

@Composable
private fun AutoAddChip(enabled: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = enabled,
        onClick = onToggle,
        label = { Text(if (enabled) "Automatisch hinzufügen: an" else "Automatisch hinzufügen: aus", maxLines = 1) },
        leadingIcon = { Icon(if (enabled) Icons.Default.Check else Icons.Default.AutoMode, null, Modifier.size(18.dp)) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Black.copy(alpha = 0.55f),
            labelColor = Color.White,
            iconColor = Color.White,
        ),
    )
}

@Composable
private fun ShutterButton(busy: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LargeFloatingActionButton(onClick = { if (!busy) onClick() }, shape = RoundedCornerShape(50)) {
            if (busy) CircularProgressIndicator(Modifier.size(32.dp)) else Icon(Icons.Default.CameraAlt, contentDescription = "Foto aufnehmen und erkennen")
        }
        Text(
            "Erkennt automatisch – oder Foto für schwierige Karten",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun DetectionCard(
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
                    Text(item.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${item.setName} · Nr. ${item.numberLabel}", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.variantList.forEach { v ->
                        val have = item.quantities[v] ?: 0
                        FilterChip(
                            selected = detection.variant == v,
                            onClick = { onSelectVariant(v) },
                            label = { Text(Variants.shortLabel(v) + if (have > 0) " ($have)" else "", maxLines = 1) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onAdd, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Hinzufügen")
                }
                if (detection.alternatives.isNotEmpty()) {
                    OutlinedButton(onClick = onAlternatives) { Text("Andere (${detection.alternatives.size})") }
                } else {
                    OutlinedButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Suchen")
                    }
                }
            }
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
private fun NoPermission(permanentlyDenied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) =
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.CameraAlt, null, Modifier.size(64.dp))
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
                Button(onClick = onOpenSettings) { Text("Einstellungen öffnen") }
            } else {
                Button(onClick = onRequest) { Text("Kamera erlauben") }
            }
            Spacer(Modifier.height(8.dp))
            Text("Alternativ: Foto aus der Galerie (Symbol oben rechts)", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
