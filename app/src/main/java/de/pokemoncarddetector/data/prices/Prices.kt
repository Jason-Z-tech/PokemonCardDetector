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
 *  - Variante ohne eigenes Produkt (1. Edition, Staff, ...) -> Preis des geteilten Produkts, markiert als [PriceEntity.shared]
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
        val ownIds = details.map { ((it["thirdParty"] as? JsonObject)?.get("cardmarket") as? JsonPrimitive)?.longOrNull }
        val top = (json["pricing"] as? JsonObject)?.get("cardmarket") as? JsonObject
        val primary = primaryVariant(keys, ownIds, (top?.get("idProduct") as? JsonPrimitive)?.longOrNull)
        val base = details.indices.firstNotNullOfOrNull { i -> details[i].cardmarket()?.takeIf { keys[i] == primary } } ?: top
        val baseId = (base?.get("idProduct") as? JsonPrimitive)?.longOrNull

        val out = LinkedHashMap<String, PriceEntity>()
        details.forEachIndexed { i, v ->
            val key = keys[i]
            val ownId = ownIds[i]
            // Ohne eigene Preisangabe: nur das Haupt-Produkt und das einfache Reverse duerfen den Produktpreis erben
            // (Reverse = Holo-Preis desselben Produkts). Sonder-Varianten (Stempel, Muster, Untertyp) nie.
            val cm = v.cardmarket()
                ?: base?.takeIf { (key == primary || key == "reverse") && (ownId == null || ownId == baseId) }
                ?: return@forEachIndexed
            price(cardId, key, cm, key.startsWith("reverse"), now)?.let { out[key] = it }
        }
        // Varianten, die bei Cardmarket kein eigenes Produkt haben (z.B. 1. Edition: nur ein Merkmal des Angebots),
        // teilen sich das Produkt einer allgemeineren Variante. Ihr Preis bleibt als Untergrenze erhalten
        // (die 1. Edition ist meist mehr wert), wird aber als "gemeinsam" markiert und nie als eigener Preis gezeigt.
        val shared = out.keys.filter { key ->
            val parts = key.split('|').toSet()
            val id = out[key]?.idProduct ?: return@filter false
            out.any { (other, p) -> other != key && p.idProduct == id && other.split('|').toSet().let { it.size < parts.size && parts.containsAll(it) } }
        }
        shared.forEach { key -> out[key] = out.getValue(key).copy(shared = true) }
        // Karte ohne Varianten-Details: Preis der Karte als "ohne Variante"
        if (out.isEmpty() && top != null) {
            price(cardId, Variants.UNKNOWN, top, reverse = false, now)?.let { out[Variants.UNKNOWN] = it }
        }
        return out.values.toList()
    }

    /**
     * Haupt-Produkt der Karte (darf den Kartenpreis erben, wenn es selbst keine Preisangabe hat):
     *  1. die Variante, deren Cardmarket-Produkt der Kartenpreis ist (ohne Reverse, einfache Varianten zuerst),
     *  2. sonst die erste von Normal/Holo in TCGdex-Reihenfolge – bei Holo-Raren mit spaeter gelistetem "Normal"
     *     (dp6-5, hgss3-1, ex4-3) ist das der Holo-Druck, "Normal" ist dort ein Neben-Druck,
     *  3. sonst "normal".
     */
    internal fun primaryVariant(keys: List<String>, ownIds: List<Long?>, topId: Long?): String {
        val onTop = if (topId == null) emptyList() else keys.filterIndexed { i, k -> ownIds.getOrNull(i) == topId && !k.startsWith("reverse") }
        return onTop.firstOrNull { !it.contains('|') }
            ?: onTop.firstOrNull()
            ?: keys.firstOrNull { it == "normal" || it == "holo" }
            ?: "normal"
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

/** Preis einer Variante; [exact] = eigener Cardmarket-Preis genau dieser Variante, sonst eine Untergrenze ("ab"). */
data class VariantPrice(val euro: Double, val exact: Boolean)

/**
 * Preis fuer eine (gesammelte/gewuenschte) Variante:
 *  1. eigener Preis der Variante,
 *  2. sonst der Preis des Produkts, das sie mit einer allgemeineren Variante teilt (z.B. 1. Edition), als Untergrenze,
 *  3. sonst (oder bei unbekannter Variante) der niedrigste bekannte Preis der Karte – lieber zu niedrig als zu hoch.
 */
fun variantPrice(cardPrices: List<PriceEntity>, variant: String, metric: PriceMetric): VariantPrice? {
    cardPrices.firstOrNull { it.variant == variant && !it.shared }?.value(metric)?.let { return VariantPrice(it, exact = true) }
    cardPrices.firstOrNull { it.variant == variant && it.shared }?.value(metric)?.let { return VariantPrice(it, exact = false) }
    return cardPrices.mapNotNull { it.value(metric) }.minOrNull()?.let { VariantPrice(it, exact = false) }
}

/** Wert einer Variante fuer Sammlungswert und Wunschliste (siehe [variantPrice]). */
fun priceFor(cardPrices: List<PriceEntity>, variant: String, metric: PriceMetric): Double? =
    variantPrice(cardPrices, variant, metric)?.euro

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
