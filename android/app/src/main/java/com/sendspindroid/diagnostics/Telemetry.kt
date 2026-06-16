package com.sendspindroid.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Opt-in, anonymous upload of [HandoffEpisode]s to the collector
 * (https://sendspinapp.com/api/telemetry). Off by default. Sends only the
 * categorical/timing episode + an anonymous, rotatable install id -- never
 * addresses, names, or logs. See the telemetry design spec.
 *
 * Episodes queue locally and flush over an UNMETERED connection only (so we don't
 * spend the cellular data we're measuring); the recorded episode is unaffected by
 * a deferred upload. 4xx drops the item (our bug, don't retry forever); 5xx /
 * network keeps it for the next flush.
 */
object Telemetry {

    private const val TAG = "Telemetry"
    const val ENDPOINT = "https://sendspinapp.com/api/telemetry"

    private const val PREFS = "sendspin_telemetry"
    private const val KEY_ENABLED = "telemetry_enabled"
    private const val KEY_INSTALL_ID = "telemetry_install_id"
    private const val KEY_QUEUE = "telemetry_queue"
    private const val MAX_QUEUE = 100

    @Volatile
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val client by lazy { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }

    /** Call once at startup. Also flushes anything queued from a previous run. */
    fun init(context: Context) {
        appContext = context.applicationContext
        flush()
    }

    fun isEnabled(): Boolean = prefs()?.getBoolean(KEY_ENABLED, false) ?: false

    fun setEnabled(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        if (enabled) flush()
    }

    /** Stable-per-install, anonymous, rotatable. Generated lazily. */
    fun installId(): String {
        val p = prefs() ?: return ""
        p.getString(KEY_INSTALL_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also { p.edit().putString(KEY_INSTALL_ID, it).apply() }
    }

    fun resetInstallId() {
        prefs()?.edit()?.putString(KEY_INSTALL_ID, UUID.randomUUID().toString())?.apply()
    }

    /** Enqueue a closed episode for upload (no-op unless opted in), then try to flush. */
    fun submit(episode: HandoffEpisode) {
        if (!isEnabled()) return
        val ctx = appContext ?: return
        val payload = TelemetryWire.envelope(episode, installId(), appVersionOf(ctx), Build.VERSION.SDK_INT)
        enqueue(payload)
        flush()
    }

    fun flush() {
        val ctx = appContext ?: return
        if (!isEnabled()) return
        scope.launch {
            if (isMetered(ctx)) return@launch
            val p = prefs() ?: return@launch
            val batch = synchronized(lock) { readQueue(p) }
            var sent = 0
            for (payload in batch) {
                when (post(payload)) {
                    Result.DONE, Result.DROP -> sent++
                    Result.RETRY -> break
                }
            }
            if (sent > 0) synchronized(lock) {
                val current = readQueue(p).toMutableList()
                repeat(minOf(sent, current.size)) { current.removeAt(0) }
                writeQueue(p, current)
            }
        }
    }

    private enum class Result { DONE, DROP, RETRY }

    private fun post(payload: String): Result = try {
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            when {
                resp.isSuccessful -> Result.DONE
                resp.code in 400..499 -> Result.DROP
                else -> Result.RETRY
            }
        }
    } catch (e: Exception) {
        Log.d(TAG, "upload deferred: ${e.message}")
        Result.RETRY
    }

    private fun enqueue(payload: String) = synchronized(lock) {
        val p = prefs() ?: return
        val queue = readQueue(p).toMutableList().apply { add(payload) }
        while (queue.size > MAX_QUEUE) queue.removeAt(0)
        writeQueue(p, queue)
    }

    private fun readQueue(p: SharedPreferences): List<String> {
        val raw = p.getString(KEY_QUEUE, null) ?: return emptyList()
        return runCatching { Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content } }
            .getOrDefault(emptyList())
    }

    private fun writeQueue(p: SharedPreferences, queue: List<String>) {
        p.edit().putString(KEY_QUEUE, buildJsonArray { queue.forEach { add(it) } }.toString()).apply()
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun appVersionOf(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "unknown"

    private fun isMetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return cm.isActiveNetworkMetered
    }
}
