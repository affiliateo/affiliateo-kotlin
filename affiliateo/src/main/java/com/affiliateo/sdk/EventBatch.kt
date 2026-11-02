package com.affiliateo.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * One flush, fewer requests (4.16.1).
 *
 * Every page(), track() and session ping is still its own entry in the queue,
 * with its own event_id, persisted exactly as before. What changed is how a
 * flush sends them: a run of entries IN A ROW that all go to
 * /api/v1/mobile/event for the same app (campaign_id) and the same device
 * travels as ONE request, their `events` arrays put end to end in queue order.
 * Each event keeps its own event_id, timestamp and fields. Nothing waits
 * longer than before: the flush that used to send them one by one now sends
 * them together, on the same timer.
 *
 * Caps, kept under the server's (more than 100 events is a 400, a body over
 * 64 KB a 413):
 *   - at most [MAX_EVENTS] events in one request
 *   - a body of at most [MAX_BODY_BYTES] bytes of UTF-8 (the server counts
 *     characters, which are never more than the bytes)
 * An entry that would pass either cap starts the next request. One entry is
 * never split, so an entry over a cap on its own goes alone, as it always did.
 *
 * Everything else is one request of its own, in its place in the queue order,
 * with its payload sent byte for byte as queued: purchases, ad ids, an event
 * entry that cannot be read, and a lone event entry with nothing to join.
 *
 * Resending a batch after a lost answer is safe: the server keeps one row per
 * event_id.
 *
 * Plain JVM code (org.json only), so the unit tests run it without a phone.
 */
internal object EventBatch {
    const val MAX_EVENTS = 100
    const val MAX_BODY_BYTES = 60_000
    private const val EVENT_PATH = "/api/v1/mobile/event"
    private val EVENT_KEYS = setOf("campaign_id", "device_id", "events")

    /** One request of a flush: where it goes, its body, and the ids of the queued entries it carries, in queue order. */
    class Request(val endpoint: String, val body: String, val ids: List<String>)

    /** The requests that deliver [entries] (a snapshot of the queue, oldest first), in the same order. */
    fun plan(
        entries: List<EventQueue.QueuedEvent>,
        maxEvents: Int = MAX_EVENTS,
        maxBodyBytes: Int = MAX_BODY_BYTES,
    ): List<Request> {
        val requests = ArrayList<Request>()
        var run: Run? = null
        for (entry in entries) {
            val events = readEvents(entry)
            if (events == null) {
                run?.let { requests += it.request() }
                run = null
                requests += Request(entry.endpoint, entry.payloadJson, listOf(entry.id))
                continue
            }
            val current = run
            if (current != null && current.takes(events, maxEvents, maxBodyBytes)) {
                current.add(entry.id, events)
            } else {
                current?.let { requests += it.request() }
                run = Run(entry, events)
            }
        }
        run?.let { requests += it.request() }
        return requests
    }

    /**
     * What one request's answer does to the queue, for every entry it carried
     * (4.16.3, see SendRetry). Sent or refused removes them all. Kept is one
     * more try for each of them, a record only: nothing is dropped for it (the
     * third used to drop it). An id no longer in the queue (clear(), or the
     * size cap dropped it during the flush) is skipped.
     */
    fun settle(queue: MutableList<EventQueue.QueuedEvent>, ids: Collection<String>, outcome: SendRetry.Outcome) {
        val carried = ids.toHashSet()
        if (outcome != SendRetry.Outcome.KEPT) {
            queue.removeAll { it.id in carried }
            return
        }
        for (entry in queue) {
            if (entry.id in carried) entry.retries++
        }
    }

    /** An event entry's parts, read from its queued payload. */
    private class Events(
        val endpoint: String,
        val campaignId: String,
        val deviceId: String,
        /** Each event as compact JSON, in order. */
        val texts: List<String>,
    ) {
        /** These events written one after another with commas between them. */
        val bytes: Int = texts.sumOf { utf8Bytes(it) } + texts.size - 1
    }

    /** The events of an entry that may share a request, or null when it goes alone. */
    private fun readEvents(entry: EventQueue.QueuedEvent): Events? {
        if (!entry.endpoint.endsWith(EVENT_PATH)) return null
        return try {
            val payload = JSONObject(entry.payloadJson)
            // Only the shape enqueueEvent writes. Anything more would be lost
            // in a merged body, so it is sent as it was queued.
            val keys = HashSet<String>()
            payload.keys().forEach { keys += it }
            if (keys != EVENT_KEYS) return null
            val campaignId = payload.get("campaign_id") as? String ?: return null
            val deviceId = payload.get("device_id") as? String ?: return null
            if (campaignId.isEmpty() || deviceId.isEmpty()) return null
            val array = payload.get("events") as? JSONArray ?: return null
            if (array.length() == 0) return null
            val texts = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val event = array.get(i) as? JSONObject ?: return null
                // org.json answers null for an object it cannot write; that
                // fails below, inside this try, and the entry goes alone.
                texts += event.toString()
            }
            Events(entry.endpoint, campaignId, deviceId, texts)
        } catch (_: Exception) {
            null
        }
    }

    /** Event entries in a row for one app and device, on their way to becoming one request. */
    private class Run(first: EventQueue.QueuedEvent, events: Events) {
        private val endpoint = events.endpoint
        private val campaignId = events.campaignId
        private val deviceId = events.deviceId
        private val firstPayload = first.payloadJson
        private val head = "{\"campaign_id\":" + JSONObject.quote(campaignId) +
            ",\"device_id\":" + JSONObject.quote(deviceId) + ",\"events\":["
        private val ids = mutableListOf(first.id)
        private val texts = events.texts.toMutableList()
        /** The merged body's exact size: head, the events with commas between them, and "]}". */
        private var bytes = utf8Bytes(head) + events.bytes + 2

        fun takes(next: Events, maxEvents: Int, maxBodyBytes: Int): Boolean =
            next.endpoint == endpoint &&
                next.campaignId == campaignId &&
                next.deviceId == deviceId &&
                texts.size + next.texts.size <= maxEvents &&
                bytes + 1 + next.bytes <= maxBodyBytes

        fun add(id: String, next: Events) {
            ids += id
            texts += next.texts
            bytes += 1 + next.bytes
        }

        fun request(): Request =
            if (ids.size == 1) {
                Request(endpoint, firstPayload, ids.toList())
            } else {
                Request(endpoint, head + texts.joinToString(",") + "]}", ids.toList())
            }
    }

    private fun utf8Bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size
}
