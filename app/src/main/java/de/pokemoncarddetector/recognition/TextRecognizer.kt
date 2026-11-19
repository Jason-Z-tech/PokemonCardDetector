package de.pokemoncarddetector.recognition

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Kapselt ML Kit (Texterkennung laeuft offline auf dem Geraet). */
class CardTextRecognizer : Closeable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Feste Drehung fuer Kamerabilder (Sensorausrichtung bezogen auf Hochformat).
     * CameraX passt die Drehung sonst an die Handyhaltung an, die Vorschau bleibt aber im Hochformat –
     * dann passen die Textpositionen nicht mehr zum Scan-Rahmen.
     */
    @Volatile var fixedRotationDegrees: Int? = null

    suspend fun recognize(image: InputImage): List<OcrLine> = suspendCancellableCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { text -> if (cont.isActive) cont.resume(text.toLines()) }
            .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    }

    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): List<OcrLine> =
        recognize(InputImage.fromBitmap(bitmap, rotationDegrees))

    /**
     * Analyzer fuer die Live-Vorschau. Verarbeitet immer nur ein Bild gleichzeitig
     * (aeltere werden verworfen), damit die Vorschau fluessig bleibt.
     */
    fun analyzer(onLines: (lines: List<OcrLine>, imageWidth: Int, imageHeight: Int) -> Unit): ImageAnalysis.Analyzer =
        LiveAnalyzer(onLines)

    private inner class LiveAnalyzer(
        private val onLines: (List<OcrLine>, Int, Int) -> Unit,
    ) : ImageAnalysis.Analyzer {
        private val busy = AtomicBoolean(false)

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(proxy: ImageProxy) {
            val media = proxy.image
            if (media == null || !busy.compareAndSet(false, true)) {
                proxy.close()
                return
            }
            val rotation = fixedRotationDegrees ?: proxy.imageInfo.rotationDegrees
            val (w, h) = if (rotation % 180 == 0) proxy.width to proxy.height else proxy.height to proxy.width
            val input = InputImage.fromMediaImage(media, rotation)
            recognizer.process(input)
                .addOnSuccessListener { text -> onLines(text.toLines(), w, h) }
                .addOnCompleteListener {
                    busy.set(false)
                    proxy.close()
                }
        }
    }

    override fun close() = recognizer.close()

    companion object {
        fun Text.toLines(): List<OcrLine> = textBlocks.flatMap { block ->
            block.lines.map { line ->
                val box = line.boundingBox ?: Rect()
                OcrLine(line.text, box.left, box.top, box.right, box.bottom)
            }
        }.sortedWith(compareBy({ it.top }, { it.left }))
    }
}
