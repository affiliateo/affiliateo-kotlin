package com.affiliateo.sdk

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * EventQueue: durable best-effort delivery for analytics events.
 *
 * Mirrors the @affiliateo/web, @affiliateo/react-native, and Affiliateo
 * Swift queues so all four platforms behave consistently for merchants
 * integrating across multiple targets.
 *
 * Architecture:
 *   - In-memory `queue` list of QueuedEvent (id + endpoint + JSON payload + retries)
 *   - Persisted to SharedPreferences under STORAGE_KEY as a JSON array on every mutation
 *   - Background coroutine timer fires every FLUSH_INTERVAL_MS to attempt delivery
 *   - A flush sends the events of one app and device that sit in a row in ONE
 *     request (4.16.1, EventBatch): up to 100 events and about 60 KB per
 *     request. Purchases and ad ids still go one request each, in order.
 *   - ConnectivityManager callback pauses flushing while offline; fires an
 *     immediate flush the moment connectivity returns (no waiting for the timer)
 *   - Retries (4.16.3, SendRetry): an answer that may change (no answer, 404,
 *     408, 429, 5xx) keeps the request's events, and the queue waits 5 s,
 *     doubling up to 5 minutes, before its next try, at least as long as a
 *     429 or 503 asks (Retry-After). The rest of that flush waits too. A
 *     refusal (another 4xx) drops them, with one debug line. Only the size cap
 *     drops a waiting event; it used to be dropped after its third failed try
 *   - The app going to the background flushes too (4.16.3, Affiliateo)
 *
 * Why SharedPreferences instead of Room / DataStore:
 *   SharedPreferences is the platform-idiomatic key-value store for small
 *   structured data. At 100 events × ~500 bytes each we're at 50 KB max,
 *   well inside its comfort zone. Room would add migration headaches +
 *   build-time annotation processing for no real benefit. DataStore is
 *   newer but coroutine-only API would force every consumer onto coroutines.
 *
 * Caps (matched to web + RN + Swift for cross-platform consistency):
 *   - maxQueueSize = 100      hard cap, FIFO drop on overflow
 *   - flushIntervalMs = 5_000 periodic auto-flush cadence
 *   - sizeFlushThreshold = 10 trigger flush when queue grows past
 */
internal class EventQueue(
    private val context: Context,
    flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
    /** Gets the debug lines (a refused request, 4.16.3). */
    private val log: (String) -> Unit = {},
) {

    // internal (not private) so EventBatch, which plans a flush's requests
    // and settles their answers, can read it; the unit tests build them too.
    internal data class QueuedEvent(
        val id: String,
        val endpoint: String,
        val payloadJson: String,
        // Kept answers so far. A record only since 4.16.3: nothing is
        // dropped for it.
        var retries: Int,
    )

    companion object {
        private const val STORAGE_KEY = "affiliateo_event_queue"
        private const val PREFS_NAME = "affiliateo_queue"
        private const val DEFAULT_MAX_QUEUE_SIZE = 100
        private const val DEFAULT_FLUSH_INTERVAL_MS = 5_000L
        private const val SIZE_FLUSH_THRESHOLD = 10
    }

    // Configurable knobs, clamped to sane bounds at init so a misconfigured
    // host can't break the queue. min 1s flush so we don't hammer the
    // network; max 60s so the queue actually drains. Size cap [10, 1000].
    private val flushIntervalMs: Long = flushIntervalMs.coerceIn(1_000L, 60_000L)
    private val maxQueueSize: Int = maxQueueSize.coerceIn(10, 1000)

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ReentrantLock instead of @Synchronized so we can call into the lock
    // from coroutines without trapping the dispatcher. All mutations to
    // `queue` and the `isFlushing` flag go through the lock.
    private val lock = ReentrantLock()
    private val queue: MutableList<QueuedEvent> = mutableListOf()
    private val isFlushing = AtomicBoolean(false)
    @Volatile private var shuttingDown = false
    // Every usable network as Android reports it (4.16.2): offline only
    // when the last one goes, so a switch from Wi-Fi to mobile data never
    // leaves the queue waiting. Starts online, as before.
    private val reachability = Reachability<Network>()
    private val isConnected: Boolean get() = reachability.online
    // When the next try may go after a kept answer (4.16.3). Under the lock.
    private val retry = SendRetry()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        loadFromDisk()
        startNetworkMonitor()
        startFlushTimer()
        // Catch-up flush in case the previous app session ended with
        // events still queued. Best-effort: noops when offline.
        if (lock.withLock { queue.isNotEmpty() }) {
            scope.launch { flush() }
        }
    }

    /** Add an event to the queue. Returns immediately; persistence is sync
     *  (SharedPreferences.edit().commit() blocks but the data is small). */
    fun enqueue(endpoint: String, payload: Map<String, Any?>) {
        if (shuttingDown) return
        val payloadJson = mapToJsonString(payload)
        val event = QueuedEvent(
            id = UUID.randomUUID().toString(),
            endpoint = endpoint,
            payloadJson = payloadJson,
            retries = 0,
        )
        lock.withLock {
            queue.add(event)
            // FIFO drop when over cap. Network outage edge case: under
            // sustained failure the queue would grow until SharedPreferences
            // started slowing down on the JSON encode/decode round-trip.
            // 100 events is a sane upper bound that protects without
            // dropping anything during normal use.
            if (queue.size > maxQueueSize) {
                val excess = queue.size - maxQueueSize
                queue.subList(0, excess).clear()
            }
            persist()
            if (queue.size >= SIZE_FLUSH_THRESHOLD) {
                scope.launch { flush() }
            }
        }
    }

    /** Try to deliver every queued event, in queue order; the events of one
     *  app and device in a row share a request (EventBatch.plan). A request
     *  whose answer may change keeps its entries and ends the flush, so the
     *  rest waits with them (4.16.3, SendRetry and EventBatch.settle).
     *  Skipped entirely when offline, and until the wait after such an
     *  answer is over. */
    suspend fun flush() {
        if (!isConnected) return
        if (!isFlushing.compareAndSet(false, true)) return
        try {
            // Snapshot the queue so new enqueue()s during the flush land
            // in the live queue and get picked up on the NEXT flush.
            val snapshot = lock.withLock {
                if (retry.canSend(System.currentTimeMillis())) queue.toList() else emptyList()
            }
            if (snapshot.isEmpty()) return

            for (request in EventBatch.plan(snapshot)) {
                val answer = sendOnce(request.endpoint, request.body)
                val outcome = lock.withLock {
                    retry.answered(answer, System.currentTimeMillis()).also { EventBatch.settle(queue, request.ids, it) }
                }
                if (outcome == SendRetry.Outcome.REFUSED) {
                    log("dropped ${request.ids.size} queued event(s): the server refused them (HTTP ${answer.status}, ${request.endpoint})")
                }
                // The server is busy or out of reach for the rest too: they
                // wait behind the kept ones, in queue order.
                if (outcome == SendRetry.Outcome.KEPT) break
            }

            lock.withLock { persist() }
        } finally {
            isFlushing.set(false)
        }
    }

    /** Wipe all queued events. Called by reset() and optOut(). */
    fun clear() {
        lock.withLock {
            queue.clear()
            prefs.edit().remove(STORAGE_KEY).apply()
        }
    }

    /** Stop the timer + network callback. Idempotent. */
    fun shutdown() {
        shuttingDown = true
        flushJob?.cancel()
        flushJob = null
        unregisterNetworkCallback()
        // One last flush attempt. Best-effort: if offline it noops and
        // events stay on disk for the next app launch.
        scope.launch {
            try { flush() } finally { scope.cancel() }
        }
    }

    val size: Int
        get() = lock.withLock { queue.size }

    // MARK: - Private

    private fun loadFromDisk() {
        val raw = prefs.getString(STORAGE_KEY, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                queue.add(QueuedEvent(
                    id = obj.getString("id"),
                    endpoint = obj.getString("endpoint"),
                    payloadJson = obj.getString("payload"),
                    retries = obj.optInt("retries", 0),
                ))
            }
        } catch (_: Exception) {
            // Corrupt or wrong-shape entries — reset rather than crash on
            // load. Better to drop a few stuck events than block all
            // future tracking on a parse error.
            queue.clear()
        }
    }

    private fun persist() {
        val arr = JSONArray()
        for (e in queue) {
            val obj = JSONObject()
            obj.put("id", e.id)
            obj.put("endpoint", e.endpoint)
            obj.put("payload", e.payloadJson)
            obj.put("retries", e.retries)
            arr.put(obj)
        }
        prefs.edit().putString(STORAGE_KEY, arr.toString()).apply()
    }

    private fun startFlushTimer() {
        flushJob = scope.launch {
            while (isActive && !shuttingDown) {
                delay(flushIntervalMs)
                try { flush() } catch (_: Exception) { /* swallow */ }
            }
        }
    }

    private fun startNetworkMonitor() {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        // Filter for network requests that match "actually usable internet"
        // (not just a captive portal Wi-Fi splash page). Matches the
        // semantics our queue cares about: can we hit affiliateo.com?
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wasOffline = reachability.available(network)
                if (wasOffline) {
                    // Came back online: a wait for the network is over (one
                    // the server asked for still holds), so fire a catch-up
                    // flush right now instead of waiting up to flushIntervalMs.
                    lock.withLock { retry.networkBack() }
                    scope.launch { flush() }
                }
            }
            override fun onLost(network: Network) {
                // Offline only when no other usable network is left.
                reachability.lost(network)
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (_: Exception) {
            // Some older devices or restrictive ROMs throw on
            // registerNetworkCallback. Fall through to "assume connected"
            // — flushes will just fail and retry on their own cadence.
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) { /* swallow */ }
        networkCallback = null
    }

    /** One POST. Answers its status and Retry-After (4.16.3); the status is
     *  null when no answer came (offline, a time out). */
    private suspend fun sendOnce(endpoint: String, body: String): SendRetry.Answer = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val retryAfter = conn.getHeaderField("Retry-After")
            // Drain the response body so the underlying socket gets recycled
            // (Android's HttpURLConnection has a long-standing footgun where
            // unread bodies prevent connection reuse).
            try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.use { BufferedReader(InputStreamReader(it)).readText() }
            } catch (_: Exception) { /* swallow */ }
            conn.disconnect()
            // -1 is a reply that was not HTTP: no answer.
            SendRetry.Answer(code.takeIf { it > 0 }, retryAfter)
        } catch (_: Exception) {
            SendRetry.Answer(null)
        }
    }

    // Convert [String: Any?] to a JSON string. We use org.json (built-in
    // to Android) instead of pulling in kotlinx.serialization to keep
    // the SDK dependency-free. Drops keys with null values to match the
    // wire format the server expects.
    private fun mapToJsonString(map: Map<String, Any?>): String {
        return mapToJsonObject(map).toString()
    }

    private fun mapToJsonObject(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        for ((k, v) in map) {
            if (v == null) continue
            obj.put(k, toJsonValue(v))
        }
        return obj
    }

    private fun toJsonValue(value: Any): Any {
        return when (value) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                mapToJsonObject(value as Map<String, Any?>)
            }
            is List<*> -> {
                val arr = JSONArray()
                for (item in value) {
                    if (item == null) { arr.put(JSONObject.NULL); continue }
                    arr.put(toJsonValue(item))
                }
                arr
            }
            else -> value
        }
    }
}
