package de.pokemoncarddetector.ui.components

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val EURO = NumberFormat.getCurrencyInstance(Locale.GERMANY)

/** "1.234,56 €" bzw. "–" ohne Preis */
fun euro(value: Double?): String = if (value == null) "–" else EURO.format(value)

/** Ganze Euro fuer grosse Zahlen: "1.234 €" */
fun euroRounded(value: Double): String =
    NumberFormat.getCurrencyInstance(Locale.GERMANY).apply { maximumFractionDigits = 0 }.format(value)

/** "18.11.2026" aus einem ISO-Zeitstempel ("2026-11-18T22:16:18.987Z") oder yyyy-MM-dd */
fun germanDate(iso: String): String {
    val d = iso.take(10)
    if (d.length != 10) return iso
    return "${d.substring(8, 10)}.${d.substring(5, 7)}.${d.substring(0, 4)}"
}

/** "18.11. 14:05" aus Millisekunden */
fun germanDateTime(ms: Long): String = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(ms))
