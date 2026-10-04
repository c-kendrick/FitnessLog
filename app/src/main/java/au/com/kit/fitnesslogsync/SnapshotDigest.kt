package au.com.kit.fitnesslogsync

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Compare data, not read timestamps. Only acknowledged digests may suppress an upload. */
object SnapshotDigest {
    private val ignored = setOf("observed_at", "request_id", "backfill_next", "_digest_key", "_digest")
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().filter { it !in ignored }.sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }.sorted().joinToString(",", "[", "]")
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    fun of(payload: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(payload).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
