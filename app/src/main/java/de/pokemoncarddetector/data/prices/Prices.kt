package de.pokemoncarddetector.data.prices

import de.pokemoncarddetector.data.Variants
import de.pokemoncarddetector.data.db.CollectionRow
import de.pokemoncarddetector.data.db.PriceEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Welcher Cardmarket-Wert fuer Anzeige und Sammlungswert benutzt wird. */
enum class PriceMetric(val code: String, val label: String, val short: String) {
    TREND("trend", "Trendpreis", "Trend"),
    AVG30("avg30", "30-Tage-Durchschnitt", "Ø 30 Tage"),
    LOW("low", "Ab-Preis (günstigstes Angebot)", "ab");

    companion object {
        fun from(code: String?) = entries.firstOrNull { it.code == code } ?: TREND
    }
}

/** Tageswert fuer die gewaehlte Preisart (null = an diesem Tag fuer diese Preisart nicht gespeichert). */
fun de.pokemoncarddetector.data.db.ValueSnapshot.centsFor(metric: PriceMetric): Long? = when (metric) {
    PriceMetric.TREND -> trendCents
    PriceMetric.AVG30 -> avg30Cents
    PriceMetric.LOW -> lowCents
}

fun PriceEntity.value(metric: PriceMetric): Double? = when (metric) {
    PriceMetric.TREND -> trend ?: avg30 ?: avg7
    PriceMetric.AVG30 -> avg30 ?: trend
    PriceMetric.LOW -> low ?: trend
}?.takeIf { it > 0.0 }

/**
 * Liest die Preise aus einer TCGdex-Kartenantwort (/v2/{lang}/cards/{id}).
 *
 * Cardmarket fuehrt pro Produkt einen Normal- und einen Holo/Reverse-Preis ("trend" / "trend-holo").
 * TCGdex haengt jedes Produkt an seine Variante(n):
 *  - Normal                -> trend
 *  - Reverse (gleiches Produkt wie Normal) -> trend-holo
 *  - Holo / Sonder-Reverse mit eigenem Produkt -> trend bzw. bei Reverse trend-holo, falls vorhanden
 */
object PriceParser {

    fun parse(
        cardId: String,
        json: JsonObject,
        catalogVariants: List<String>,
        canonicalKeys: Boolean,
        now: Long,
    ): List<PriceEntity> {
        val details = (json["variants_detailed"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        // Schluessel: aus den (englischen) Feldern, sonst ueber die Position im Katalog
        val keys = details.mapIndexed { i, v -> if (canonicalKeys) variantKey(v) else catalogVariants.getOrNull(i) ?: variantKey(v) }
        val top = (json["pricing"] as? JsonObject)?.get("cardmarket") as? JsonObject
        // Haupt-Produkt der Karte: Normal (bzw. Holo bei Holo-Raren) – oder der Kartenpreis
        val primary = if ("normal" in keys) "normal" else "holo"
        val base = details.indices.firstNotNullOfOrNull { i -> details[i].cardmarket()?.takeIf { keys[i] == primary } } ?: top
        val baseId = (base?.get("idProduct") as? JsonPrimitive)?.longOrNull

        val out = LinkedHashMap<String, PriceEntity>()
        details.forEachIndexed { i, v ->
            val key = keys[i]
            val ownId = ((v["thirdParty"] as? JsonObject)?.get("cardmarket") as? JsonPrimitive)?.longOrNull
            // Ohne eigene Preisangabe: nur das Haupt-Produkt und das einfache Reverse duerfen den Produktpreis erben
            // (Reverse = Holo-Preis desselben Produkts). Sonder-Varianten (Stempel, Muster, Untertyp) nie.
            val cm = v.cardmarket()
                ?: base?.takeIf { (key == primary || key == "reverse") && (ownId == null || ownId == baseId) }
                ?: return@forEachIndexed
            price(cardId, key, cm, key.startsWith("reverse"), now)?.let { out[key] = it }
        }
        // Varianten, die bei Cardmarket kein eigenes Produkt haben (z.B. 1. Edition: nur ein Merkmal des Angebots),
        // bekommen keinen eigenen Preis – sonst stuende der Preis der Grundkarte als "ihr" Preis da.
        val shared = out.keys.filter { key ->
            val parts = key.split('|').toSet()
            val id = out[key]?.idProduct ?: return@filter false
            out.any { (other, p) -> other != key && p.idProduct == id && other.split('|').toSet().let { it.size < parts.size && parts.containsAll(it) } }
        }
        shared.forEach { out.remove(it) }
        // Karte ohne Varianten-Details: Preis der Karte als "ohne Variante"
        if (out.isEmpty() && top != null) {
            price(cardId, Variants.UNKNOWN, top, reverse = false, now)?.let { out[Variants.UNKNOWN] = it }
        }
        return out.values.toList()
    }

    private fun JsonObject.cardmarket(): JsonObject? =
        ((this["pricing"] as? JsonObject)?.get("cardmarket") as? JsonObject)

    private fun price(cardId: String, variant: String, cm: JsonObject, reverse: Boolean, now: Long): PriceEntity? {
        fun d(k: String) = (cm[k] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0.0 }
        val trend = if (reverse) d("trend-holo") ?: d("trend") else d("trend")
        val avg30 = if (reverse) d("avg30-holo") ?: d("avg30") else d("avg30")
        val avg7 = if (reverse) d("avg7-holo") ?: d("avg7") else d("avg7")
        val low = if (reverse) d("low-holo") ?: d("low") else d("low")
        if (trend == null && avg30 == null && low == null) return null
        return PriceEntity(
            cardId = cardId,
            variant = variant,
            trend = trend,
            avg30 = avg30,
            avg7 = avg7,
            low = low,
            idProduct = (cm["idProduct"] as? JsonPrimitive)?.longOrNull,
            priceDate = (cm["updated"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            fetchedAt = now,
        )
    }

    /** Gleicher Schluessel wie tools/build_catalog.py, z.B. "reverse|foil=pokeball". */
    fun variantKey(v: JsonObject): String = buildList {
        add(v.str("type")?.lowercase() ?: "normal")
        v.str("subtype")?.let { add("sub=$it") }
        v.str("foil")?.let { add("foil=$it") }
        (v["stamp"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.replace(' ', '-') }
            ?.takeIf { it.isNotEmpty() }?.let { add("stamp=" + it.joinToString(",")) }
        v.str("size")?.takeIf { it != "standard" }?.let { add("size=$it") }
    }.joinToString("|")

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
}

/**
 * Preis fuer eine gesammelte Variante. Fehlt der Preis genau dieser Variante (oder ist die Variante
 * unbekannt), wird der niedrigste bekannte Preis der Karte genommen – lieber zu niedrig als zu hoch.
 */
fun priceFor(cardPrices: List<PriceEntity>, variant: String, metric: PriceMetric): Double? {
    cardPrices.firstOrNull { it.variant == variant }?.value(metric)?.let { return it }
    return cardPrices.mapNotNull { it.value(metric) }.minOrNull()
}

data class CollectionValue(
    val totalEuro: Double,
    /** Karten (verschieden) mit bekanntem Preis */
    val pricedCards: Int,
    val unpricedCards: Int,
    /** Wert je Karte (alle Varianten x Anzahl) */
    val perCard: Map<String, Double>,
)

fun collectionValue(rows: List<CollectionRow>, prices: List<PriceEntity>, metric: PriceMetric): CollectionValue {
    val byCard = prices.groupBy { it.cardId }
    val perCard = HashMap<String, Double>()
    val unpriced = HashSet<String>()
    for (r in rows) {
        val p = priceFor(byCard[r.cardId].orEmpty(), r.variant, metric)
        if (p == null) {
            unpriced += r.cardId
        } else {
            perCard[r.cardId] = (perCard[r.cardId] ?: 0.0) + p * r.quantity
        }
    }
    unpriced.removeAll(perCard.keys)
    return CollectionValue(perCard.values.sum(), perCard.size, unpriced.size, perCard)
}
