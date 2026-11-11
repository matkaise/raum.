package app.raum.homeconnect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Antworten der Home-Connect-API in raum.-Modelle übersetzen. Rein, ohne Netz – in Unit-Tests prüfbar. */
object HcJson {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun data(body: String): JsonObject? = runCatching { json.parseToJsonElement(body).obj()?.get("data").obj() }.getOrNull()

    fun appliances(body: String): List<HcAppliance> =
        data(body)?.get("homeappliances").arr().orEmpty().mapNotNull { it.obj()?.let(::appliance) }

    fun appliance(o: JsonObject): HcAppliance? {
        val haId = o["haId"].str() ?: return null
        return HcAppliance(
            haId = haId,
            name = o["name"].str()?.takeIf { it.isNotBlank() } ?: o["type"].str().orEmpty(),
            brand = o["brand"].str().orEmpty(),
            type = HcType.from(o["type"].str()),
            connected = (o["connected"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    /** /status, /settings und Optionen: Liste von {key, value}. */
    fun items(body: String, listName: String): List<HcItem> =
        data(body)?.get(listName).arr().orEmpty().mapNotNull { it.obj()?.let(::item) }

    fun item(o: JsonObject): HcItem? {
        val key = o["key"].str() ?: return null
        return HcItem(key, o["value"] as? JsonPrimitive, (o["timestamp"] as? JsonPrimitive)?.longOrNull, o["uri"].str())
    }

    /** /programs/active bzw. /selected → Programmschlüssel und Optionen. */
    fun program(body: String): Pair<String, List<HcItem>>? {
        val d = data(body) ?: return null
        val key = d["key"].str() ?: return null
        return key to d["options"].arr().orEmpty().mapNotNull { it.obj()?.let(::item) }
    }

    fun availablePrograms(body: String): List<HcProgram> =
        data(body)?.get("programs").arr().orEmpty().mapNotNull { p ->
            val o = p.obj() ?: return@mapNotNull null
            val key = o["key"].str() ?: return@mapNotNull null
            // „selectonly“ lässt sich nicht aus der Ferne starten
            if (o["constraints"].obj()?.get("execution").str() == "selectonly") return@mapNotNull null
            HcProgram(key, o["name"].str()?.takeIf { it.isNotBlank() } ?: prettify(enumTail(key) ?: key))
        }

    fun programDetail(body: String): HcProgram? {
        val d = data(body) ?: return null
        val key = d["key"].str() ?: return null
        val options = d["options"].arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val k = o["key"].str() ?: return@mapNotNull null
            val c = o["constraints"].obj()
            HcOptionDef(
                key = k,
                name = o["name"].str()?.takeIf { it.isNotBlank() } ?: prettify(enumTail(k) ?: k),
                type = o["type"].str().orEmpty(),
                unit = o["unit"].str(),
                min = (c?.get("min") as? JsonPrimitive)?.doubleOrNull,
                max = (c?.get("max") as? JsonPrimitive)?.doubleOrNull,
                step = (c?.get("stepsize") as? JsonPrimitive)?.doubleOrNull,
                allowed = c?.get("allowedvalues").arr().orEmpty().mapNotNull { it.str() },
                display = c?.get("displayvalues").arr().orEmpty().map { it.str().orEmpty() },
                default = c?.get("default") as? JsonPrimitive,
            )
        }
        return HcProgram(key, d["name"].str()?.takeIf { it.isNotBlank() } ?: prettify(enumTail(key) ?: key), options, detailed = true)
    }

    /** /settings/BSH.Common.Setting.PowerState → erlaubte Werte. */
    fun allowedValues(body: String): List<String>? =
        data(body)?.get("constraints").obj()?.get("allowedvalues").arr()?.mapNotNull { it.str() }

    fun commands(body: String): Set<String> =
        data(body)?.get("commands").arr().orEmpty().mapNotNull { it.obj()?.get("key").str() }.toSet()

    /** Fehlerkörper: {"error":{"key","description"}} (API) oder {"error","error_description"} (OAuth). */
    fun error(body: String?): Pair<String?, String?> {
        val o = body?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null to null
        return when (val e = o["error"]) {
            is JsonObject -> e["key"].str() to e["description"].str()
            is JsonPrimitive -> e.contentOrNull to o["error_description"].str()
            else -> null to null
        }
    }

    /** Wert für den PUT-Körper (Optionen, Einstellungen). */
    /**
     * Startbefehl. Optionen mit Einheit tragen sie mit („unit": „ml") – Siemens-Kaffeevollautomaten (EQ.9) nehmen
     * den Start sonst an, verwerfen aber die Menge stillschweigend (gemessen am Gerät, 11.11.2026).
     */
    fun programBody(key: String, options: Map<String, JsonPrimitive>, units: Map<String, String> = emptyMap()): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(mapOf("data" to JsonObject(buildMap {
            put("key", JsonPrimitive(key))
            if (options.isNotEmpty()) put("options", JsonArray(options.map { (k, v) ->
                JsonObject(buildMap {
                    put("key", JsonPrimitive(k)); put("value", v)
                    units[k]?.takeIf { it.isNotBlank() }?.let { put("unit", JsonPrimitive(it)) }
                })
            }))
        }))),
    )

    fun valueBody(key: String, value: JsonPrimitive, unit: String? = null): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(mapOf("data" to JsonObject(buildMap {
            put("key", JsonPrimitive(key)); put("value", value)
            unit?.takeIf { it.isNotBlank() }?.let { put("unit", JsonPrimitive(it)) }
        }))),
    )

    /** JSON der Ereignis-Daten (Feld data: im Strom). */
    fun eventItems(data: String?): Pair<List<HcItem>, String?> {
        if (data.isNullOrBlank()) return emptyList<HcItem>() to null
        val o = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList<HcItem>() to null
        val items = (o["items"] as? JsonArray)?.mapNotNull { it.obj()?.let(::item) }.orEmpty()
        return items to o["haId"].str()
    }
}

/**
 * Server-Sent Events zeilenweise zerlegen (Felder event/id/data, Leerzeile beendet ein Ereignis, „:“ = Kommentar).
 * Die Home-Connect-API streut gelegentlich Fremdzeilen ein – dann [SseCorrupt], der Strom wird neu aufgebaut.
 */
class SseParser {
    private val fields = mutableMapOf<String, String>()

    class SseCorrupt(line: String) : Exception("unparseable SSE line: ${line.take(80)}")

    /** Liefert ein fertiges Ereignis, sobald eine Leerzeile es abschließt. */
    fun feed(line: String): HcEvent? {
        if (line.isEmpty()) {
            if (fields.isEmpty()) return null
            val done = build(fields.toMap())
            fields.clear()
            return done
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        if (colon < 0) {
            if (line in BARE) { fields.putIfAbsent(line, ""); return null }
            throw SseCorrupt(line)
        }
        val name = line.substring(0, colon)
        if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' }) throw SseCorrupt(line)
        val value = line.substring(colon + 1).removePrefix(" ")
        fields[name] = fields[name]?.let { "$it\n$value" } ?: value
        return null
    }

    private fun build(f: Map<String, String>): HcEvent? {
        val type = f["event"] ?: return null
        val (items, bodyHaId) = HcJson.eventItems(f["data"])
        // CONNECTED/DISCONNECTED tragen die haId teils nur im Körper
        return HcEvent(type, f["id"]?.takeIf { it.isNotBlank() } ?: bodyHaId, items)
    }

    private companion object { val BARE = setOf("event", "data", "id", "retry") }
}
