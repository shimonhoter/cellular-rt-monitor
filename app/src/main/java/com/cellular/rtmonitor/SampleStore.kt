package com.cellular.rtmonitor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistent, append-only store of every RF sample (one per >= ~1 s).
 * Lives in SQLite so it survives app exit / process kill and scales to millions of rows.
 * Only [clear] ever deletes data.
 */
class SampleStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "rf_samples.db", null, 1) {

    companion object {
        /** Column order used for rows sent to JS and for CSV export. */
        val COLS = listOf(
            "id", "ts", "lat", "lon", "acc", "rat", "tech", "band", "plmn", "pci", "gnb", "arfcn",
            "tac", "ta", "rsrp", "rsrq", "sinr", "rssi", "tx", "ho", "nb"
        )
        private const val MIN_INTERVAL_MS = 900L
    }

    private val writer = Executors.newSingleThreadExecutor()
    private val count = AtomicLong(-1)
    @Volatile private var lastInsertTs = 0L

    init {
        enableWriteAheadLogging()
        writer.execute { count.set(queryCount()) }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE samples(
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL,
                lat REAL, lon REAL, acc REAL,
                rat TEXT, tech TEXT, band TEXT, plmn TEXT,
                pci INTEGER, gnb INTEGER, arfcn INTEGER, tac INTEGER, ta INTEGER,
                rsrp REAL, rsrq REAL, sinr REAL, rssi REAL, tx REAL, ho INTEGER, nb INTEGER)"""
        )
        db.execSQL("CREATE INDEX idx_samples_ts ON samples(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun queryCount(): Long = try {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM samples", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    } catch (_: Exception) { 0L }

    fun total(): Long {
        val v = count.get()
        return if (v >= 0) v else queryCount().also { count.set(it) }
    }

    private fun num(o: JSONObject, k: String): Double? {
        if (o.isNull(k) || !o.has(k)) return null
        val d = o.optDouble(k, Double.NaN)
        return if (d.isNaN() || d >= 2147483647.0) null else d
    }

    /** Stores one sample from a telemetry payload. Throttles to ~1 Hz. */
    fun addFromPayload(payload: JSONObject, lat: Double?, lon: Double?, acc: Float?) {
        val now = System.currentTimeMillis()
        if (now - lastInsertTs < MIN_INTERVAL_MS) return
        // Every read is stored exactly as the modem reported it; missing fields stay empty (NULL), nothing is filled in.
        val sc = payload.optJSONObject("servingCell") ?: JSONObject()
        val rsrp = num(sc, "rsrp"); val rsrq = num(sc, "rsrq"); val sinr = num(sc, "sinr"); val rssi = num(sc, "rssi")
        lastInsertTs = now
        val ho = sc.optBoolean("handoverOccurred", false)
        val nb = payload.optJSONArray("neighbors")?.length() ?: 0
        val cv = ContentValues().apply {
            put("ts", now)
            if (lat != null && lon != null) { put("lat", lat); put("lon", lon); if (acc != null) put("acc", acc.toDouble()) }
            put("rat", sc.optString("rat")); put("tech", sc.optString("tech")); put("band", sc.optString("band"))
            put("plmn", payload.optString("plmn"))
            num(sc, "pci")?.let { put("pci", it.toLong()) }
            num(sc, "gnb")?.let { put("gnb", it.toLong()) }
            num(sc, "arfcn")?.let { put("arfcn", it.toLong()) }
            num(sc, "tac")?.let { put("tac", it.toLong()) }
            num(sc, "ta")?.let { put("ta", it.toLong()) }
            rsrp?.let { put("rsrp", it) }; rsrq?.let { put("rsrq", it) }
            sinr?.let { put("sinr", it) }; rssi?.let { put("rssi", it) }
            num(sc, "txPower")?.let { put("tx", it) }
            put("ho", if (ho) 1 else 0); put("nb", nb)
        }
        writer.execute {
            try {
                writableDatabase.insert("samples", null, cv)
                count.incrementAndGet()
            } catch (_: Exception) {}
        }
    }

    private fun rowToJson(c: android.database.Cursor): JSONArray {
        val a = JSONArray()
        for (i in 0 until c.columnCount) {
            when (c.getType(i)) {
                android.database.Cursor.FIELD_TYPE_NULL -> a.put(JSONObject.NULL)
                android.database.Cursor.FIELD_TYPE_INTEGER -> a.put(c.getLong(i))
                android.database.Cursor.FIELD_TYPE_FLOAT -> a.put(c.getDouble(i))
                else -> a.put(c.getString(i))
            }
        }
        return a
    }

    private fun query(where: String, args: Array<String>, order: String, limit: Int): String {
        val out = JSONArray()
        try {
            readableDatabase.rawQuery(
                "SELECT ${COLS.joinToString(",")} FROM samples $where ORDER BY id $order LIMIT $limit", args
            ).use { c -> while (c.moveToNext()) out.put(rowToJson(c)) }
        } catch (_: Exception) {}
        return out.toString()
    }

    /** Newest-first page of rows older than [beforeId] (0 = from the newest). */
    fun page(beforeId: Long, limit: Int): String =
        if (beforeId > 0) query("WHERE id < ?", arrayOf(beforeId.toString()), "DESC", limit.coerceIn(1, 2000))
        else query("", emptyArray(), "DESC", limit.coerceIn(1, 2000))

    /** Rows newer than [afterId], newest-first. */
    fun since(afterId: Long, limit: Int): String =
        query("WHERE id > ?", arrayOf(afterId.toString()), "DESC", limit.coerceIn(1, 2000))

    /** Oldest-first chunk starting after [afterId] — used to stream the entire history to the analyzer. */
    fun chunkAsc(afterId: Long, limit: Int): String =
        query("WHERE id > ?", arrayOf(afterId.toString()), "ASC", limit.coerceIn(1, 5000))

    fun clear() {
        writer.execute {
            try {
                writableDatabase.execSQL("DELETE FROM samples")
                writableDatabase.execSQL("DELETE FROM sqlite_sequence WHERE name='samples'")
                count.set(0)
            } catch (_: Exception) {}
        }
    }

    private fun csvEsc(s: String?): String {
        if (s == null) return ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    /** Streams every row. CSV or JSON. Returns the number of rows written. */
    fun export(out: OutputStream, json: Boolean): Long {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val local = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val w = out.bufferedWriter(Charsets.UTF_8)
        var n = 0L
        if (json) w.write("{\"exportedAt\":\"${iso.format(Date())}\",\"samples\":[\n")
        else {
            w.write('﻿'.code)
            w.write("timestamp,local_time,epoch_ms,lat,lon,gps_acc_m,rat,tech,band,plmn,pci,gnb,arfcn,tac,ta,rsrp,rsrq,sinr,rssi,tx_power,handover,neighbors\n")
        }
        readableDatabase.rawQuery("SELECT ${COLS.joinToString(",")} FROM samples ORDER BY id ASC", null).use { c ->
            fun s(i: Int): String = if (c.isNull(i)) "" else c.getString(i)
            while (c.moveToNext()) {
                val ts = c.getLong(1)
                val d = Date(ts)
                if (json) {
                    if (n > 0) w.write(",\n")
                    val o = JSONObject()
                    val arr = rowToJson(c)
                    o.put("timestamp", iso.format(d)); o.put("epoch_ms", ts)
                    for (i in 2 until COLS.size) if (!c.isNull(i)) o.put(COLS[i], arr.get(i))
                    w.write(o.toString())
                } else {
                    w.write(
                        listOf(iso.format(d), local.format(d), ts.toString(), s(2), s(3), s(4), csvEsc(s(5)), csvEsc(s(6)),
                            csvEsc(s(7)), csvEsc(s(8)), s(9), s(10), s(11), s(12), s(13), s(14), s(15), s(16), s(17), s(18),
                            s(19), s(20)).joinToString(",")
                    )
                    w.write("\n")
                }
                n++
            }
        }
        if (json) w.write("\n]}\n")
        w.flush()
        return n
    }
}
