package de.pokemoncarddetector.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

data class ChartPoint(val label: String, val value: Double)

/**
 * Wertverlauf als einzelne Linie (2 dp) mit leichter Flaeche, Haarlinien-Gitter und
 * Antippen/Ziehen zum Ablesen (Fadenkreuz + Wert). Eine Reihe -> keine Legende.
 */
@Composable
fun ValueLineChart(points: List<ChartPoint>, format: (Double) -> String, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val shown = selected?.let { points.getOrNull(it) } ?: points.lastOrNull()

    Column(modifier) {
        // Ablesezeile: gewaehlter Punkt oder letzter Wert
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(shown?.let { format(it.value) } ?: "–", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "  " + (shown?.label ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (points.size < 2) {
            Text(
                "Der Verlauf entsteht ab dem zweiten Tag mit Preisen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
            return@Column
        }
        val min = points.minOf { it.value }
        val max = points.maxOf { it.value }
        val lo = if (max - min < 1e-6) min * 0.9 else min - (max - min) * 0.1
        val hi = if (max - min < 1e-6) max * 1.1 + 1 else max + (max - min) * 0.1
        Box {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .padding(vertical = 8.dp)
                    .semantics { contentDescription = "Wertverlauf von ${points.first().label} bis ${points.last().label}" }
                    .pointerInput(points) {
                        fun pick(x: Float) {
                            val i = ((x / size.width) * (points.size - 1)).roundToInt().coerceIn(0, points.size - 1)
                            selected = i
                        }
                        detectTapGestures(onPress = { pick(it.x) })
                    }
                    .pointerInput(points) {
                        detectDragGestures { change, _ ->
                            val i = ((change.position.x / size.width) * (points.size - 1)).roundToInt().coerceIn(0, points.size - 1)
                            selected = i
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                fun x(i: Int) = w * i / (points.size - 1)
                fun y(v: Double) = (h - (v - lo) / (hi - lo) * h).toFloat()
                // Haarlinien-Gitter (unten, Mitte, oben)
                for (f in listOf(0f, 0.5f, 1f)) drawLine(grid, Offset(0f, h * f), Offset(w, h * f), strokeWidth = 1f)
                val path = Path().apply {
                    moveTo(x(0), y(points[0].value))
                    for (i in 1 until points.size) lineTo(x(i), y(points[i].value))
                }
                val area = Path().apply {
                    addPath(path)
                    lineTo(x(points.size - 1), h)
                    lineTo(x(0), h)
                    close()
                }
                drawPath(area, line.copy(alpha = 0.12f))
                drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val sel = selected ?: (points.size - 1)
                val sx = x(sel)
                val sy = y(points[sel].value)
                if (selected != null) drawLine(grid, Offset(sx, 0f), Offset(sx, h), strokeWidth = 1f)
                // Punkt mit Ring in Flaechenfarbe
                drawCircle(surface, radius = 6.dp.toPx(), center = Offset(sx, sy))
                drawCircle(line, radius = 4.dp.toPx(), center = Offset(sx, sy))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.first().label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(points.last().label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

data class BarItem(val label: String, val value: Double, val valueText: String)

/**
 * Sortierte horizontale Balken in einer Farbe (Groesse vergleichen).
 * Name links, Balken mit runder Spitze, Wert in Textfarbe am Ende.
 */
@Composable
fun RankedBars(items: List<BarItem>, modifier: Modifier = Modifier) {
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val max = items.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Column(Modifier.semantics { contentDescription = "${item.label}: ${item.valueText}" }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(item.valueText, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp).padding(top = 2.dp)) {
                    val fraction = (item.value / max).toFloat().coerceIn(0f, 1f)
                    Canvas(Modifier.width(maxWidth).height(8.dp)) {
                        val r = 4.dp.toPx()
                        drawRoundRect(track, size = size, cornerRadius = CornerRadius(r, r))
                        val bw = (size.width * fraction).coerceAtLeast(if (item.value > 0) r * 2 else 0f)
                        if (bw > 0f) {
                            // Spitze rund, Grundlinie eckig
                            val path = Path().apply {
                                addRoundRect(
                                    RoundRect(
                                        left = 0f, top = 0f, right = bw, bottom = size.height,
                                        topLeftCornerRadius = CornerRadius.Zero, bottomLeftCornerRadius = CornerRadius.Zero,
                                        topRightCornerRadius = CornerRadius(r, r), bottomRightCornerRadius = CornerRadius(r, r),
                                    )
                                )
                            }
                            drawPath(path, bar)
                        }
                    }
                }
            }
        }
    }
}

