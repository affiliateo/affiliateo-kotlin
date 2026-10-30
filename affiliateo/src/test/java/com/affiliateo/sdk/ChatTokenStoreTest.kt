package com.affiliateo.sdk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

/**
 * 4.15.4 (review S18): the thread key lives in a file in the no-backup
 * folder, not in SharedPreferences (which Auto Backup copies). An older
 * version's key moves over once; a sign-out removes it everywhere.
 */
class ChatTokenStoreTest {
    private val token = "lct_" + "A".repeat(42) + "_"
    private val other = "lct_" + "B".repeat(42) + "_"
    private lateinit var dir: File
    private lateinit var legacy: MemoryLegacy

    /** Runs each disk write at once. */
    private val now = Executor { it.run() }

    /** Holds disk writes until run() is called, like the real background thread. */
    private class Later : Executor {
        val waiting = ArrayList<Runnable>()
        override fun execute(command: Runnable) {
            waiting.add(command)
        }
        fun run() {
            val all = waiting.toList()
            waiting.clear()
            all.forEach { it.run() }
        }
    }

    private class MemoryLegacy : ChatTokenStore.Legacy {
        val values = HashMap<String, String>()
        override fun get(key: String): String? = values[key]
        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private fun store(disk: Executor = now) = ChatTokenStore(dir, legacy, disk)
    private fun file(appId: String) = File(dir, ChatRules.tokenFileName(appId))

    @Before
    fun setUp() {
        dir = File(Files.createTempDirectory("affiliateo-chat-token").toFile(), "affiliateo")
        legacy = MemoryLegacy()
    }

    @After
    fun tearDown() {
        dir.parentFile?.deleteRecursively()
    }

    @Test
    fun nothingSavedIsNoKey() {
        assertNull(store().token("app"))
    }

    @Test
    fun aSavedKeyIsInTheNoBackupFileOnly() {
        store().save("app", token)
        assertEquals(token, file("app").readText())
        assertTrue(legacy.values.isEmpty())
        // A new start of the app (a new store) reads it from the file.
        assertEquals(token, store().token("app"))
        // Another app's thread is its own.
        assertNull(store().token("other-app"))
    }

    @Test
    fun anOlderVersionsKeyMovesOverOnce() {
        legacy.values[ChatRules.tokenKey("app")] = token
        assertEquals(token, store().token("app"))
        assertEquals(token, file("app").readText())
        assertNull(legacy.values[ChatRules.tokenKey("app")])
        assertEquals(token, store().token("app"))
    }

    @Test
    fun anOlderKeyStaysWhereItWasUntilTheFileIsWritten() {
        legacy.values[ChatRules.tokenKey("app")] = token
        // A plain file where the folder should be: nothing can be written.
        dir.parentFile!!.mkdirs()
        dir.writeText("not a folder")
        assertEquals(token, store().token("app"))
        assertEquals(token, legacy.values[ChatRules.tokenKey("app")])
        // Writable again: the next start moves it.
        dir.delete()
        assertEquals(token, store().token("app"))
        assertEquals(token, file("app").readText())
        assertNull(legacy.values[ChatRules.tokenKey("app")])
    }

    @Test
    fun aNewKeyLeavesNoOlderCopyBehind() {
        legacy.values[ChatRules.tokenKey("app")] = token
        val disk = Later()
        val s = store(disk)
        assertEquals(token, s.token("app"))
        // The page hands over a new key before the move reached the disk.
        s.save("app", other)
        disk.run()
        assertEquals(other, file("app").readText())
        assertNull(legacy.values[ChatRules.tokenKey("app")])
        assertEquals(other, store().token("app"))
    }

    @Test
    fun anOlderValueThatIsNoKeyIsDropped() {
        legacy.values[ChatRules.tokenKey("app")] = "lct_bad"
        assertNull(store().token("app"))
        assertNull(legacy.values[ChatRules.tokenKey("app")])
        assertFalse(file("app").exists())
    }

    @Test
    fun onlyKeysAreSavedOrRead() {
        store().save("app", "lct_bad")
        assertFalse(file("app").exists())
        assertNull(store().token("app"))
        dir.mkdirs()
        file("app").writeText("lct_bad")
        assertNull(store().token("app"))
    }

    @Test
    fun aNewKeyReplacesTheOld() {
        val s = store()
        s.save("app", token)
        s.save("app", other)
        assertEquals(other, s.token("app"))
        assertEquals(other, store().token("app"))
    }

    @Test
    fun signingOutRemovesItEverywhere() {
        val s = store()
        s.save("app", token)
        legacy.values[ChatRules.tokenKey("app")] = other
        s.clear("app")
        assertNull(s.token("app"))
        assertFalse(file("app").exists())
        assertNull(legacy.values[ChatRules.tokenKey("app")])
        assertNull(store().token("app"))
    }

    @Test
    fun aWriteStillWaitingWhenThePersonSignsOutIsDropped() {
        val disk = Later()
        val s = store(disk)
        s.save("app", token)
        s.clear("app")
        disk.run()
        assertFalse(file("app").exists())
        assertNull(s.token("app"))
        assertNull(store().token("app"))
    }

    @Test
    fun anOddAppIdStaysInsideTheFolder() {
        val appId = "../../shared_prefs/affiliateo"
        store().save(appId, token)
        assertEquals(token, store().token(appId))
        assertEquals(listOf(ChatRules.tokenFileName(appId)), dir.list()!!.toList())
    }
}
