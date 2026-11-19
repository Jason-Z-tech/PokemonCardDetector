package de.pokemoncarddetector.recognition

/** Position des Scan-Rahmens in der Vorschau (Pixel der View). */
data class FrameGeometry(
    val viewWidth: Int,
    val viewHeight: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Behaelt nur Textzeilen, die im Scan-Rahmen liegen – so stoert Text von daneben
 * liegenden Karten nicht. Die Vorschau nutzt FILL_CENTER (Bild wird skaliert und
 * mittig beschnitten), genau diese Abbildung wird hier umgekehrt.
 */
object FrameFilter {

    fun filter(lines: List<OcrLine>, imageWidth: Int, imageHeight: Int, frame: FrameGeometry?): List<OcrLine> {
        if (frame == null || imageWidth <= 0 || imageHeight <= 0 || frame.viewWidth <= 0 || frame.viewHeight <= 0) return lines
        val scale = maxOf(frame.viewWidth / imageWidth.toFloat(), frame.viewHeight / imageHeight.toFloat())
        val dx = (frame.viewWidth - imageWidth * scale) / 2f
        val dy = (frame.viewHeight - imageHeight * scale) / 2f
        // Rahmen in Bildkoordinaten, mit etwas Toleranz
        val marginX = (frame.right - frame.left) * 0.12f
        val marginY = (frame.bottom - frame.top) * 0.08f
        val l = (frame.left - marginX - dx) / scale
        val r = (frame.right + marginX - dx) / scale
        val t = (frame.top - marginY - dy) / scale
        val b = (frame.bottom + marginY - dy) / scale
        val inside = lines.filter { line ->
            val cx = (line.left + line.right) / 2f
            val cy = (line.top + line.bottom) / 2f
            cx in l..r && cy in t..b
        }
        return if (inside.size >= 2) inside else lines
    }
}
