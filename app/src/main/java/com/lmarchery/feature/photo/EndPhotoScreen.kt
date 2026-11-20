package com.lmarchery.feature.photo

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.scoring.calculateScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

private data class PhotoSource(val uri: Uri?, val captureName: String? = null)

private val anchorLabels = listOf("gauche", "haut", "droite", "bas")

@Composable
internal fun EndPhotoScreen(
    key: EndPhotoKey,
    endLabel: String,
    editable: Boolean,
    remaining: Int,
    existingImpactIds: Set<String>,
    targetType: TargetType,
    onConfirm: (Map<String, NormalizedPoint>) -> Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val photos = remember(context) { AndroidEndPhotos(context) }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<EndPhoto?>(null) }
    var draft by remember { mutableStateOf<EndPhoto?>(null) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadSucceeded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var anchor by remember { mutableStateOf<Int?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Map<String, NormalizedPoint>?>(null) }
    var capture by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var source by remember { mutableStateOf<PhotoSource?>(null) }

    suspend fun decode(image: PhotoImage): ImageBitmap = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size, bounds)
        check(bounds.outWidth == image.width && bounds.outHeight == image.height)
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size))
        decoded.asImageBitmap()
    }

    suspend fun display(photo: EndPhoto?) {
        val rendered = photo?.let { decode(it.image) }
        bitmap = rendered
        draft = photo
    }

    LaunchedEffect(key) {
        try {
            val photo = withContext(Dispatchers.IO) { photos.load(key) }
            display(photo)
            saved = photo
            loadSucceeded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Photo locale illisible. Le fichier est conservé."
        } finally {
            loading = false
        }
    }

    val canEdit = editable && loadSucceeded && !loading
    val ready = canEdit && !busy && capture == null && !picking && source == null
    val dirty = draft != saved
    val lockedMarks = draft?.marks?.any { it.uuid in existingImpactIds } == true
    val latestCanImport by rememberUpdatedState(canEdit && !lockedMarks)
    val latestApply by rememberUpdatedState(onConfirm)
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) source = PhotoSource(uri)
        picking = false
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val name = capture
        if (name != null) source = PhotoSource(if (taken) photos.captureUri(name) else null, name)
        capture = null
    }
    LaunchedEffect(source, loading) {
        val result = source ?: return@LaunchedEffect
        if (loading) return@LaunchedEffect
        busy = true
        error = null
        try {
            val uri = result.uri
            if (uri != null && latestCanImport) {
                val image = withContext(Dispatchers.IO) { photos.importImage(uri) }
                if (latestCanImport) {
                    display(image)
                    anchor = null
                    selected = null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Impossible d’ouvrir cette image. Choisissez une photo lisible."
        } finally {
            if (result.captureName != null) photos.removeCapture(result.captureName)
            source = null
            busy = false
        }
    }

    val close = {
        if (!busy && capture == null && !picking && source == null) {
            if (dirty) discard = true else onClose()
        }
    }
    BackHandler(onBack = close)

    val proposed = draft?.projectedMarks(existingImpactIds)
    val canScore = ready && !dirty && anchor == null && selected == null &&
        proposed != null && proposed.isNotEmpty() && proposed.size <= remaining
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(close, enabled = !busy && capture == null && !picking && source == null) { Text("‹ Retour à la volée") }
                Text(endLabel, modifier = Modifier.padding(top = 15.dp))
            }
            Text("Photo du blason", style = MaterialTheme.typography.headlineSmall)
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val photo = draft
            val image = bitmap
            if (photo != null && image != null) {
                PhotoCanvas(image, photo, selected, existingImpactIds, Modifier.fillMaxWidth().weight(1f)) { point ->
                    val current = draft ?: return@PhotoCanvas
                    val moving = selected
                    val anchorIndex = anchor
                    if (!ready || confirmation != null || current !== photo) return@PhotoCanvas
                    when {
                        anchorIndex != null -> {
                            draft = current.copy(calibration = current.calibration.withAnchor(anchorIndex, point))
                            anchor = (anchorIndex + 1).takeIf { it < anchorLabels.size }
                        }
                        moving != null && moving !in existingImpactIds -> {
                            draft = current.copy(marks = current.marks.map {
                                if (it.uuid == moving) it.copy(point = point) else it
                            })
                            selected = null
                        }
                        current.marks.size < MAX_PHOTO_MARKS -> {
                            draft = current.copy(marks = current.marks + PhotoMark(UUID.randomUUID().toString(), point))
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (photo == null && !loading && error == null) {
                    Text(if (editable) "Prenez une photo de la cible ou choisissez une image existante."
                    else "Aucune photo enregistrée pour cette volée.")
                }
                if (photo != null) {
                    val incomplete = photo.calibration.circle(photo.image.width, photo.image.height) == null
                    Text(when {
                        anchor != null -> "Touchez le bord extérieur du blason, côté ${anchorLabels[anchor!!]}."
                        selected != null -> "Touchez le nouvel emplacement du repère."
                        lockedMarks && photo.marks.all { it.uuid in existingImpactIds } -> "Repères déjà enregistrés au score."
                        incomplete -> "Calibration insuffisante pour le score. Vous pouvez conserver la photo et ses repères."
                        proposed == null -> "Un repère est hors du cercle calibré. Ajustez-le avant d’ajouter les flèches."
                        proposed.isEmpty() -> "Touchez la photo pour marquer les flèches."
                        proposed.size > remaining -> "Capacité insuffisante pour ajouter tous ces repères."
                        else -> "${proposed.size} flèche(s) proposée(s). Vérifiez les repères et les scores."
                    })
                    if (ready && !lockedMarks) {
                        if (anchor == null) TextButton({
                            selected = null
                            draft = photo.copy(calibration = PhotoCalibration())
                            anchor = 0
                        }) { Text("Aligner le blason") }
                        else TextButton({ anchor = null }) { Text("Interrompre l’alignement") }
                    }
                    photo.marks.forEachIndexed { index, mark ->
                        val committed = mark.uuid in existingImpactIds
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Repère ${index + 1}" + when {
                                committed -> " · enregistré"
                                proposed?.containsKey(mark.uuid) == true -> " · ${photoScoreLabel(targetType, proposed.getValue(mark.uuid))}"
                                else -> ""
                            }, modifier = Modifier.padding(top = 12.dp))
                            if (ready && !committed && anchor == null) {
                                TextButton({ selected = if (selected == mark.uuid) null else mark.uuid }) {
                                    Text(if (selected == mark.uuid) "Annuler" else "Déplacer")
                                }
                                TextButton({
                                    draft = photo.copy(marks = photo.marks.filterNot { it.uuid == mark.uuid })
                                    selected = null
                                }) { Text("Retirer") }
                            }
                        }
                    }
                }
                if (ready && !dirty && !lockedMarks && anchor == null && selected == null) {
                    OutlinedButton({
                        try {
                            val name = photos.newCapture()
                            capture = name
                            camera.launch(photos.captureUri(name))
                        } catch (_: Exception) {
                            capture?.let(photos::removeCapture)
                            capture = null
                            error = "Appareil photo indisponible. Vous pouvez importer une image."
                        }
                    }, Modifier.fillMaxWidth()) { Text("Photographier le blason") }
                    OutlinedButton({
                        if (!picking) {
                            picking = true
                            try {
                                gallery.launch(arrayOf("image/*"))
                            } catch (_: Exception) {
                                picking = false
                                error = "Sélecteur de photos indisponible."
                            }
                        }
                    }, Modifier.fillMaxWidth()) { Text("Importer une photo") }
                }
                if (photo != null && editable) {
                    Button({
                        if (!busy && draft === photo) {
                            busy = true
                            scope.launch {
                                error = null
                                try {
                                    withContext(Dispatchers.IO) { photos.save(key, photo) }
                                    saved = photo
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error = "La photo n’a pas pu être enregistrée."
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    }, Modifier.fillMaxWidth(), enabled = ready && dirty && selected == null && anchor == null) {
                        Text("Enregistrer la photo")
                    }
                    if (!dirty) Text("Photo enregistrée sur cet appareil.")
                }
                Text("Photo et repères locaux, exclus des sauvegardes JSON.", style = MaterialTheme.typography.bodySmall)
            }
            if (photo != null && editable) {
                Button({ confirmation = proposed }, Modifier.fillMaxWidth().padding(bottom = 8.dp), enabled = canScore) {
                    Text("Ajouter ${proposed?.size ?: 0} flèche(s) au score")
                }
            }
        }
    }

    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text("Quitter la photo sans enregistrer ?") },
        text = { Text("Les modifications locales de la photo et des repères seront abandonnées.") },
        confirmButton = { TextButton(onClose) { Text("Quitter sans enregistrer") } },
        dismissButton = { TextButton({ discard = false }) { Text("Rester sur la photo") } },
    )
    confirmation?.let { points ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("Ajouter les flèches proposées ?") },
            text = { Text("${points.size} nouvelle(s) flèche(s) seront ajoutées à cette volée. Vérifiez la position et le score de chaque repère.") },
            confirmButton = {
                Button({
                    if (confirmation === points) {
                        confirmation = null
                        if (canScore && points == proposed && latestApply(points)) onClose()
                        else error = "La volée ou les repères ont changé. Vérifiez la séance."
                    }
                }) { Text("Confirmer l’ajout") }
            },
            dismissButton = { TextButton({ confirmation = null }) { Text("Annuler") } },
        )
    }
}

private fun photoScoreLabel(targetType: TargetType, point: NormalizedPoint): String {
    val score = calculateScore(targetType, point)
    return if (score.isX) "X" else score.score.toString()
}

private data class PhotoViewport(val offset: IntOffset, val extent: IntSize) {
    fun shownAt(point: PhotoPoint) = Offset(offset.x + point.x * extent.width, offset.y + point.y * extent.height)
    fun imagePoint(touch: Offset): PhotoPoint? {
        val x = (touch.x - offset.x) / extent.width
        val y = (touch.y - offset.y) / extent.height
        return if (x in 0f..1f && y in 0f..1f) PhotoPoint(x, y) else null
    }

    companion object {
        fun fit(frame: IntSize, image: ImageBitmap): PhotoViewport {
            val scale = minOf(frame.width.toFloat() / image.width, frame.height.toFloat() / image.height)
            val width = (image.width * scale).roundToInt().coerceAtLeast(1)
            val height = (image.height * scale).roundToInt().coerceAtLeast(1)
            return PhotoViewport(IntOffset((frame.width - width) / 2, (frame.height - height) / 2), IntSize(width, height))
        }
    }
}

@Composable
private fun PhotoCanvas(
    image: ImageBitmap,
    photo: EndPhoto,
    selected: String?,
    committed: Set<String>,
    modifier: Modifier,
    onTap: (PhotoPoint) -> Unit,
) {
    val latestTap by rememberUpdatedState(onTap)
    Canvas(modifier.testTag("endPhotoCanvas").semantics { contentDescription = "Photo à annoter" }
        .pointerInput(image) {
            detectTapGestures { touch -> PhotoViewport.fit(size, image).imagePoint(touch)?.let(latestTap) }
        }) {
        val viewport = PhotoViewport.fit(IntSize(size.width.roundToInt(), size.height.roundToInt()), image)
        drawImage(image, dstOffset = viewport.offset, dstSize = viewport.extent)
        val circle = photo.calibration.circle(image.width, image.height)
        if (circle != null) {
            val center = viewport.shownAt(PhotoPoint(circle.centerX / image.width, circle.centerY / image.height))
            drawCircle(Color.Cyan, circle.radius * viewport.extent.width / image.width, center, style = Stroke(2.dp.toPx()))
        }
        for (anchor in photo.calibration.anchors().filterNotNull()) {
            drawCircle(Color.Cyan, 5.dp.toPx(), viewport.shownAt(anchor))
        }
        for (mark in photo.marks) {
            val position = viewport.shownAt(mark.point)
            val color = when (mark.uuid) {
                selected -> Color.Yellow
                in committed -> Color.Cyan
                else -> Color.White
            }
            drawCircle(Color.Black, 8.dp.toPx(), position)
            drawCircle(color, 6.dp.toPx(), position, style = Stroke(2.dp.toPx()))
            drawCircle(color, 2.dp.toPx(), position)
        }
    }
}
