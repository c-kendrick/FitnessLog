package au.com.kit.fitnesslogsync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class Pending(val id: String, val payload: JSONObject, val key: String, val value: String)

/** Outbox and checkpoints share a database. Advance only after verified remote acknowledgement. */
class Store(context: Context) : SQLiteOpenHelper(context, "fitness-sync.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE outbox (sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, payload TEXT NOT NULL, checkpoint_key TEXT NOT NULL, checkpoint_value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE logs (sequence INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT NOT NULL, message TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun get(key: String, fallback: String = ""): String = readableDatabase.rawQuery(
        "SELECT value FROM state WHERE key=?", arrayOf(key)
    ).use { if (it.moveToFirst()) it.getString(0) else fallback }
    fun put(key: String, value: String) {
        writableDatabase.insertWithOnConflict("state", null, ContentValues().apply {
            put("key", key); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun log(message: String) {
        writableDatabase.insert("logs", null, ContentValues().apply {
            put("time", Instant.now().toString()); put("message", message.take(600))
        })
        writableDatabase.execSQL("DELETE FROM logs WHERE sequence NOT IN (SELECT sequence FROM logs ORDER BY sequence DESC LIMIT 100)")
    }
    fun history(): String = readableDatabase.rawQuery(
        "SELECT time,message FROM logs ORDER BY sequence DESC LIMIT 20", null
    ).use { c -> buildString { while (c.moveToNext()) appendLine("${c.getString(0)}  ${c.getString(1)}") } }
    fun enqueue(payload: JSONObject, key: String = "", value: String = "") {
        val id = UUID.randomUUID().toString()
        payload.put("request_id", id)
        writableDatabase.insertOrThrow("outbox", null, ContentValues().apply {
            put("id", id); put("payload", payload.toString())
            put("checkpoint_key", key); put("checkpoint_value", value)
        })
    }
    /** Keep legacy pending steps for diagnosis, but never replay them over direct Samsung totals. */
    fun migrateDirectSteps() {
        if (get("direct_steps_migrated") == "true") return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS legacy_step_outbox (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            val legacy = mutableListOf<Pair<String,String>>()
            db.rawQuery("SELECT id,payload FROM outbox", null).use { c ->
                while (c.moveToNext()) {
                    val payload = JSONObject(c.getString(1))
                    if (payload.optString("action") == "daily" && payload.optString("category") == "steps")
                        legacy += c.getString(0) to c.getString(1)
                }
            }
            for ((id,payload) in legacy) {
                db.insertOrThrow("legacy_step_outbox", null, ContentValues().apply { put("id",id); put("payload",payload) })
                db.delete("outbox", "id=?", arrayOf(id))
            }
            put("direct_steps_migrated", "true")
            put("today_steps", ""); put("today_steps_status", "not_checked"); put("today_steps_date", "")
            put("status_steps", "direct_access_needed")
            put("last_success", "")
            log("v1.3.1: archived ${legacy.size} queued Health Connect step snapshots; direct Samsung totals will be read afresh.")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun next(): Pending? = readableDatabase.rawQuery(
        "SELECT id,payload,checkpoint_key,checkpoint_value FROM outbox ORDER BY sequence LIMIT 1", null
    ).use { if (it.moveToFirst()) Pending(it.getString(0), JSONObject(it.getString(1)), it.getString(2), it.getString(3)) else null }
    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox", null).use { it.moveToFirst(); it.getLong(0) }
    fun acknowledge(item: Pending, response: JSONObject) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (item.key.isNotEmpty()) put(item.key, item.value)
            if (item.payload.optString("action") == "steps") {
                val check = response.getJSONObject("step_check")
                if (check.getString("date") >= get("today_steps_date")) {
                    put("today_steps_date", check.getString("date"))
                    put("today_steps_status", check.getString("data_status"))
                    put("today_steps", if (check.getString("data_status") == "value") check.get("value").toString() else "")
                    put("checked_at_steps", check.getString("checked_at"))
                    put("status_steps", if (check.getString("data_status") == "value") "ok" else check.getString("data_status"))
                    put("steps_source", "samsung_health_direct")
                }
                put("step_issues", response.getJSONArray("step_issues").toString())
            }
            if (item.payload.has("_digest_key"))
                put(item.payload.getString("_digest_key"), item.payload.getString("_digest"))
            if (item.payload.has("backfill_next"))
                put("backfill_${item.payload.getString("category")}", item.payload.getString("backfill_next"))
            db.delete("outbox", "id=?", arrayOf(item.id))
            put("last_upload", Instant.now().toString())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
