package com.affiliateo.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * 4.16.1: a flush sends the events of one app and device that sit in a row in
 * one request (EventBatch), under the server's caps, and settles the answer
 * for every entry the request carried.
 */
class EventBatchTest {
    private val api = "https://www.affiliateo.com"
    private val eventUrl = "$api/api/v1/mobile/event"
    private var next = 0

    /** A queued entry exactly as Affiliateo.enqueueEvent writes it. */
    private fun event(
        app: String = "app-1",
        device: String = "dev-1",
        url: String = eventUrl,
        note: String? = null,
        retries: Int = 0,
    ): EventQueue.QueuedEvent {
        val n = next++
        val event = JSONObject()
            .put("type", if (n % 2 == 0) "screen_view" else "custom")
            .put("timestamp", "2026-10-31T12:00:%02d.000Z".format(n % 60))
            .put("screen", "Screen%03d".format(n % 1000))
            .put("event_id", UUID.randomUUID().toString())
        if (note != null) event.put("metadata", JSONObject().put("note", note).put("step", 2))
        val payload = JSONObject()
            .put("campaign_id", app)
            .put("device_id", device)
            .put("events", JSONArray().put(event))
        return EventQueue.QueuedEvent("q$n", url, payload.toString(), retries)
    }

    private fun other(path: String, device: String = "dev-1"): EventQueue.QueuedEvent {
        val n = next++
        val payload = JSONObject()
            .put("campaign_id", "app-1")
            .put("device_id", device)
            .put("transaction_id", "GPA.$n")
        return EventQueue.QueuedEvent("q$n", "$api$path", payload.toString(), 0)
    }

    private fun idsOf(requests: List<EventBatch.Request>) = requests.map { it.ids }

    private fun eventsOf(entry: EventQueue.QueuedEvent): JSONArray = JSONObject(entry.payloadJson).getJSONArray("events")

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size

    /** Every request inside the server's caps; the events it carries are its entries' events, in order and unchanged. */
    private fun assertCarries(request: EventBatch.Request, entries: List<EventQueue.QueuedEvent>) {
        val byId = entries.associateBy { it.id }
        val carried = request.ids.map { byId.getValue(it) }
        val body = JSONObject(request.body)
        val events = body.getJSONArray("events")
        assertTrue(events.length() <= EventBatch.MAX_EVENTS)
        assertTrue(bytes(request.body) <= EventBatch.MAX_BODY_BYTES)
        assertEquals(setOf("campaign_id", "device_id", "events"), body.keySet())
        val first = JSONObject(carried.first().payloadJson)
        assertEquals(first.getString("campaign_id"), body.getString("campaign_id"))
        assertEquals(first.getString("device_id"), body.getString("device_id"))
        val expected = carried.flatMap { e -> eventsOf(e).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }
        assertEquals(expected.size, events.length())
        expected.forEachIndexed { i, want ->
            val got = events.getJSONObject(i)
            assertTrue("event $i changed: $got", want.similar(got))
            assertEquals(want.getString("event_id"), got.getString("event_id"))
            assertEquals(want.getString("timestamp"), got.getString("timestamp"))
        }
    }

    @Test
    fun eventsOfOneDeviceShareOneRequest() {
        val entries = listOf(event(), event(note = "café ☕"), event())
        val requests = EventBatch.plan(entries)
        assertEquals(listOf(listOf("q0", "q1", "q2")), idsOf(requests))
        assertEquals(eventUrl, requests[0].endpoint)
        assertCarries(requests[0], entries)
    }

    @Test
    fun aLoneEventGoesExactlyAsQueued() {
        val entry = event(note = "x")
        val requests = EventBatch.plan(listOf(entry))
        assertEquals(1, requests.size)
        assertEquals(entry.payloadJson, requests[0].body)
        assertEquals(listOf(entry.id), requests[0].ids)
    }

    @Test
    fun anotherDeviceAppOrServerIsNeverMerged() {
        val devices = listOf(event(), event(), event(device = "dev-2"), event(device = "dev-2"), event())
        assertEquals(
            listOf(listOf("q0", "q1"), listOf("q2", "q3"), listOf("q4")),
            idsOf(EventBatch.plan(devices)),
        )
        EventBatch.plan(devices).forEach { assertCarries(it, devices) }

        next = 0
        val apps = listOf(event(), event(), event(app = "app-2"), event(app = "app-2"), event())
        assertEquals(
            listOf(listOf("q0", "q1"), listOf("q2", "q3"), listOf("q4")),
            idsOf(EventBatch.plan(apps)),
        )

        next = 0
        val servers = listOf(event(), event(url = "https://staging.example.test/api/v1/mobile/event"), event())
        assertEquals(listOf(listOf("q0"), listOf("q1"), listOf("q2")), idsOf(EventBatch.plan(servers)))
    }

    @Test
    fun aHundredEventsAtMostPerRequest() {
        val exactly = List(100) { event() }
        assertEquals(1, EventBatch.plan(exactly).size)

        next = 0
        val entries = List(250) { event() }
        val requests = EventBatch.plan(entries)
        assertEquals(listOf(100, 100, 50), requests.map { it.ids.size })
        assertEquals(entries.map { it.id }, requests.flatMap { it.ids })
        requests.forEach { assertCarries(it, entries) }
    }

    @Test
    fun bodyStaysUnderTheSizeCap() {
        // 500 emoji: 1,000 characters to the server, 2,000 bytes of UTF-8. The
        // cap counts bytes, the larger of the two.
        val entries = List(40) { event(note = "😀".repeat(500)) }
        val requests = EventBatch.plan(entries)
        assertTrue(requests.size >= 2)
        assertEquals(entries.map { it.id }, requests.flatMap { it.ids })
        requests.forEach { assertCarries(it, entries) }
        // Each request but the last is as full as the cap allows: one more
        // event (and its comma) would have passed it.
        val byId = entries.associateBy { it.id }
        requests.dropLast(1).forEachIndexed { i, request ->
            assertTrue(request.ids.size < EventBatch.MAX_EVENTS)
            val following = byId.getValue(requests[i + 1].ids.first())
            val nextEvent = eventsOf(following).getJSONObject(0).toString()
            assertTrue(bytes(request.body) + 1 + bytes(nextEvent) > EventBatch.MAX_BODY_BYTES)
        }
    }

    @Test
    fun sizeCapIsCountedExactly() {
        val entries = List(3) { event(note = "same size") }
        val two = EventBatch.plan(entries.take(2)).single()
        val twoBytes = bytes(two.body)
        assertEquals(listOf(listOf("q0", "q1"), listOf("q2")), idsOf(EventBatch.plan(entries, maxBodyBytes = twoBytes)))
        assertEquals(listOf(listOf("q0"), listOf("q1"), listOf("q2")), idsOf(EventBatch.plan(entries, maxBodyBytes = twoBytes - 1)))
    }

    @Test
    fun anEntryOverTheCapOnItsOwnGoesAloneAsQueued() {
        val big = event(note = "a".repeat(70_000))
        val entries = listOf(event(), event(), big, event(), event())
        val requests = EventBatch.plan(entries)
        val ids = entries.map { it.id }
        assertEquals(listOf(ids.subList(0, 2), listOf(big.id), ids.subList(3, 5)), idsOf(requests))
        assertEquals(big.payloadJson, requests[1].body)
    }

    @Test
    fun otherEndpointsAreNeverMergedAndKeepTheirPlace() {
        val entries = listOf(
            event(),
            event(),
            other("/api/v1/mobile/purchase"),
            other("/api/v1/mobile/ad-ids"),
            event(),
            event(),
            other("/api/v1/mobile/purchase"),
            other("/api/v1/mobile/purchase"),
            other("/api/v1/mobile/identify"),
            event(),
        )
        val requests = EventBatch.plan(entries)
        assertEquals(
            listOf(
                listOf("q0", "q1"), listOf("q2"), listOf("q3"), listOf("q4", "q5"),
                listOf("q6"), listOf("q7"), listOf("q8"), listOf("q9"),
            ),
            idsOf(requests),
        )
        val byId = entries.associateBy { it.id }
        for (request in requests) {
            val first = byId.getValue(request.ids.first())
            assertEquals(first.endpoint, request.endpoint)
            if (request.ids.size == 1) assertEquals(first.payloadJson, request.body)
        }
        assertCarries(requests[0], entries)
        assertCarries(requests[3], entries)
    }

    @Test
    fun anEventEntryItCannotReadGoesAloneAsQueued() {
        val broken = EventQueue.QueuedEvent("broken", eventUrl, "{not json", 0)
        val extraKey = """{"campaign_id":"app-1","device_id":"dev-1","events":[{"type":"custom"}],"source":"x"}"""
        val extra = EventQueue.QueuedEvent("extra", eventUrl, extraKey, 0)
        val empty = EventQueue.QueuedEvent("empty", eventUrl, """{"campaign_id":"app-1","device_id":"dev-1","events":[]}""", 0)
        val entries = listOf(event(), broken, event(), event(), extra, empty, event(), event())
        val requests = EventBatch.plan(entries)
        assertEquals(
            listOf(listOf("q0"), listOf("broken"), listOf("q1", "q2"), listOf("extra"), listOf("empty"), listOf("q3", "q4")),
            idsOf(requests),
        )
        assertEquals("{not json", requests[1].body)
        assertEquals(extraKey, requests[3].body)
    }

    @Test
    fun successRemovesEveryMergedEntry() {
        val entries = listOf(event(), event(), event(), other("/api/v1/mobile/purchase"), event())
        val queue = entries.toMutableList()
        val requests = EventBatch.plan(queue.toList())
        EventBatch.settle(queue, requests[0].ids, ok = true, maxRetries = 3)
        assertEquals(listOf("q3", "q4"), queue.map { it.id })
    }

    @Test
    fun failureKeepsEveryMergedEntry() {
        // Retries already used: 0, 1, 2 and 0. A failed batch is one failed try
        // for each, so the one on its third try is dropped, exactly as a single
        // entry is, and the others stay queued for the next flush.
        val queue = mutableListOf(event(), event(retries = 1), event(retries = 2), event(), other("/api/v1/mobile/purchase"))
        val batch = EventBatch.plan(queue.toList()).first()
        assertEquals(listOf("q0", "q1", "q2", "q3"), batch.ids)

        EventBatch.settle(queue, batch.ids, ok = false, maxRetries = 3)
        assertEquals(listOf("q0", "q1", "q3", "q4"), queue.map { it.id })
        assertEquals(listOf(1, 2, 1, 0), queue.map { it.retries })

        // The next flush merges what is left the same way.
        val again = EventBatch.plan(queue.toList()).first()
        assertEquals(listOf("q0", "q1", "q3"), again.ids)
        EventBatch.settle(queue, again.ids, ok = false, maxRetries = 3)
        assertEquals(listOf("q0", "q3", "q4"), queue.map { it.id })
        assertEquals(listOf(2, 2, 0), queue.map { it.retries })

        // An entry gone from the queue meanwhile (clear(), the size cap) is skipped.
        EventBatch.settle(queue, listOf("gone", "q0"), ok = false, maxRetries = 3)
        assertEquals(listOf("q3", "q4"), queue.map { it.id })
    }
}
