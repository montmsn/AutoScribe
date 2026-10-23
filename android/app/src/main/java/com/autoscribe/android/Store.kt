package com.autoscribe.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Settings plus the results log, kept as small JSON files in app storage. */
class Store(private val context: Context) {
    private val prefs = context.getSharedPreferences("autoscribe", Context.MODE_PRIVATE)
    private val resultsFile = File(context.filesDir, "results.jsonl")

    var selectedPops: Set<String>
        get() = prefs.getStringSet("pops", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("pops", v).apply()

    /** Ordered protocols, each with its chosen ports; disabled protocols are absent. */
    var protocols: LinkedHashMap<String, List<Int>>
        get() {
            val raw = prefs.getString("protocols", null) ?: return LinkedHashMap(Protocols.DEFAULTS)
            val out = LinkedHashMap<String, List<Int>>()
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ports = o.getJSONArray("ports")
                out[o.getString("id")] = (0 until ports.length()).map { ports.getInt(it) }
            }
            return out
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { (id, ports) -> arr.put(JSONObject().put("id", id).put("ports", JSONArray(ports))) }
            prefs.edit().putString("protocols", arr.toString()).apply()
        }

    var pingHost: String
        get() = prefs.getString("ping_host", "1.1.1.1") ?: "1.1.1.1"
        set(v) = prefs.edit().putString("ping_host", v).apply()

    var pingCount: Int
        get() = prefs.getInt("ping_count", 5)
        set(v) = prefs.edit().putInt("ping_count", v).apply()

    var connectTimeout: Int
        get() = prefs.getInt("connect_timeout", 25)
        set(v) = prefs.edit().putInt("connect_timeout", v).apply()

    var stopOnSuccess: Boolean
        get() = prefs.getBoolean("stop_on_success", false)
        set(v) = prefs.edit().putBoolean("stop_on_success", v).apply()

    var skipTested: Boolean
        get() = prefs.getBoolean("skip_tested", true)
        set(v) = prefs.edit().putBoolean("skip_tested", v).apply()

    /** Free Windscribe accounts cannot connect to Pro servers, so those are left out of the plan. */
    var freeAccount: Boolean
        get() = prefs.getBoolean("free_account", false)
        set(v) = prefs.edit().putBoolean("free_account", v).apply()

    /** Keeps the screen awake while a test runs, so the phone does not lock mid-test. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(v) = prefs.edit().putBoolean("keep_screen_on", v).apply()

    /** Assisted mode: the user connects Windscribe by hand and AutoScribe only measures. */
    var assisted: Boolean
        get() = prefs.getBoolean("assisted", false)
        set(v) = prefs.edit().putBoolean("assisted", v).apply()

    @Synchronized
    fun loadResults(): List<TestResult> {
        if (!resultsFile.exists()) return emptyList()
        return resultsFile.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                TestResult(
                    popKey = o.getString("key"),
                    label = o.getString("label"),
                    country = o.getString("country"),
                    protocol = o.getString("protocol"),
                    port = o.getInt("port"),
                    status = o.getString("status"),
                    duration = o.getDouble("duration"),
                    ping = if (o.isNull("ping")) null else o.getDouble("ping"),
                    loss = if (o.isNull("loss")) null else o.getDouble("loss"),
                    time = o.getLong("time"),
                )
            }.getOrNull()
        }
    }

    @Synchronized
    fun append(r: TestResult) {
        val o = JSONObject()
            .put("key", r.popKey).put("label", r.label).put("country", r.country)
            .put("protocol", r.protocol).put("port", r.port).put("status", r.status)
            .put("duration", r.duration).put("ping", r.ping ?: JSONObject.NULL)
            .put("loss", r.loss ?: JSONObject.NULL).put("time", r.time)
        resultsFile.appendText(o.toString() + "\n")
    }

    @Synchronized
    fun clearResults() {
        resultsFile.delete()
    }

    fun exportCsv(): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "autoscribe_${System.currentTimeMillis()}.csv")
        file.bufferedWriter().use { w ->
            w.write("location,country,protocol,port,status,duration,ping,packet_loss,timestamp\n")
            for (r in loadResults()) {
                val cells = listOf(
                    r.label, r.country, Protocols.displayName(r.protocol), r.port.toString(), r.status,
                    "%.2f".format(java.util.Locale.US, r.duration), r.ping?.let { "%.1f".format(java.util.Locale.US, it) } ?: "",
                    r.loss?.let { "%.0f".format(java.util.Locale.US, it) } ?: "", r.time.toString(),
                )
                w.write(cells.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } + "\n")
            }
        }
        return file
    }
}
