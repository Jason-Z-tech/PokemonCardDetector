package de.pokemoncarddetector.data.catalog

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * Probe gegen die echte TCGdex-API: Welche japanischen Sets wuerde ein Abgleich mit dem mitgelieferten
 * catalog_ja.json laden? Braucht Internet, deshalb abgeschaltet. Zum Ausfuehren @Ignore entfernen:
 * ./gradlew :app:testDebugUnitTest --tests '*JapaneseSyncLiveProbe*' -i
 */
@Ignore("braucht Internet (api.tcgdex.net, assets.tcgdex.net)")
class JapaneseSyncLiveProbe {

    @Test
    fun abgleichGegenLiveApi() {
        val json = Json { ignoreUnknownKeys = true }
        val file = listOf("src/main/assets/catalog_ja.json", "app/src/main/assets/catalog_ja.json").map(::File).first { it.exists() }
        val catalog = json.decodeFromString(CatalogFile.serializer(), file.readText())
        val cards = catalog.sets.associate { s -> s.id to s.parsedCards(catalog.variants).mapIndexed { i, c -> c.toEntity(s.id, i) } }
        val known = catalog.sets.associate { s -> s.id to s.toEntity(cards.getValue(s.id).size) }

        val requests = Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder()
            .addInterceptor { chain -> requests += "${chain.request().method} ${chain.request().url}"; chain.proceed(chain.request()) }
            .build()

        val list = client.newCall(Request.Builder().url(japaneseApiUrl(JapaneseCatalogSync.API_BASE, "ja", "sets")).build()).execute()
            .use { json.parseToJsonElement(it.body!!.string()).jsonArray.map { e -> e.jsonObject } }
        val fetch = japaneseSetsToFetch(list, known)
        println("Live-Liste: ${list.size} Sets, davon CS*: ${list.count { it["id"]!!.jsonPrimitive.content.startsWith("CS") }}")
        println("Wuerde laden: $fetch")
        // Alte Regel zum Vergleich (o.total() > k.cardCount)
        val old = list.mapNotNull { o: JsonObject ->
            val id = o["id"]!!.jsonPrimitive.content
            val total = (o["cardCount"] as? JsonObject)?.get("total")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val k = known["ja:$id"] ?: return@mapNotNull id.takeIf { total > 0 }
            id.takeIf { total > k.cardCount }
        }
        println("Alte Regel haette geladen: $old")

        requests.clear()
        val out = runBlocking {
            JapaneseCatalogSync(client, germanSpecies = { emptyMap() }).run(known, { cards[it].orEmpty() }) { msg, _ -> println("  $msg") }
        }
        println("Ergebnis: neu=${out.newSets} aktualisiert=${out.updatedSets} gesamt=${out.totalSets} fehlgeschlagen=${out.failedSets}")
        println("Geladen: ${out.fetched}, zu speichern: ${out.payloads.map { it.meta.id }}")
        println("Anfragen: ${requests.size} (GET ${requests.count { it.startsWith("GET") }}, HEAD ${requests.count { it.startsWith("HEAD") }})")
        requests.forEach { println("  $it") }
        assertEquals(emptyList<String>(), fetch)
        assertEquals(0, out.newSets + out.updatedSets + out.failedSets)
    }
}
