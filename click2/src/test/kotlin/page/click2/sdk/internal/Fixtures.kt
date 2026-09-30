package page.click2.sdk.internal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Loads the shared cases in fixtures/ (click2-ios runs the same files; keep them identical). */
internal object Fixtures {
    fun load(name: String): JSONObject {
        val dir = System.getProperty("click2.fixtures") ?: error("click2.fixtures system property not set")
        return JSONObject(File(dir, name).readText())
    }

    fun JSONObject.cases(): List<JSONObject> = getJSONArray("cases").let { a -> (0 until a.length()).map(a::getJSONObject) }

    fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
}
