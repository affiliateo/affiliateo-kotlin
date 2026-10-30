package com.affiliateo.sdk

import android.content.Context
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The visitor's key to their Live Chat thread, on this phone (4.15.4, review
 * S18). Whoever holds the key can read and write the thread, so it lives in a
 * small file in the app's no-backup folder (Context.noBackupFilesDir), which
 * Android leaves out of every backup and every copy to a new phone. It used
 * to sit in the SDK's SharedPreferences, which Auto Backup copies; a key found
 * there moves over once and is deleted. Uninstalling the app removes the
 * folder, so a reinstall still starts a new thread.
 *
 * Reads come from memory after the first, and a new key goes to disk on a
 * background thread. A sign-out deletes the file at once, and a write still
 * waiting for its turn is dropped, so a key never outlives a sign-out.
 */
internal class ChatTokenStore(
    private val dir: File,
    private val legacy: Legacy,
    private val disk: Executor,
) {
    /** The SharedPreferences values older versions kept the key in. */
    interface Legacy {
        fun get(key: String): String?
        fun remove(key: String)
    }

    private val known = HashMap<String, String?>()

    /** The key for this app's thread, or null. */
    @Synchronized
    fun token(appId: String): String? {
        if (known.containsKey(appId)) return known[appId]
        val oldKey = ChatRules.tokenKey(appId)
        val old = legacy.get(oldKey)
        if (old != null && ChatRules.isToken(old)) {
            known[appId] = old
            // Out of SharedPreferences once it is safely in the file. Until
            // then it stays where it was, so the person keeps their thread.
            persist(appId, old)
            return old
        }
        if (old != null) legacy.remove(oldKey)
        val saved = readFile(appId)
        known[appId] = saved
        return saved
    }

    /** Keeps the key the chat page handed over. */
    @Synchronized
    fun save(appId: String, token: String) {
        if (!ChatRules.isToken(token)) return
        known[appId] = token
        persist(appId, token)
    }

    /**
     * On the disk thread: the file, and once it is written, no copy left in
     * SharedPreferences, so an older key there can never come back over it.
     */
    private fun persist(appId: String, token: String) {
        disk.execute { if (writeIfCurrent(appId, token)) legacy.remove(ChatRules.tokenKey(appId)) }
    }

    /** The person signed out (or another signed in): their key goes, wherever it was. */
    @Synchronized
    fun clear(appId: String) {
        known[appId] = null
        legacy.remove(ChatRules.tokenKey(appId))
        try {
            file(appId).delete()
        } catch (e: Exception) {
            // Nothing to delete, or no folder.
        }
    }

    /** Writes the key only while it is still this app's key: not after a sign-out or a newer key. */
    @Synchronized
    private fun writeIfCurrent(appId: String, token: String): Boolean =
        known[appId] == token && writeFile(appId, token)

    private fun file(appId: String) = File(dir, ChatRules.tokenFileName(appId))

    private fun readFile(appId: String): String? = try {
        file(appId).takeIf { it.isFile }?.readText()?.trim()?.takeIf { ChatRules.isToken(it) }
    } catch (e: Exception) {
        null
    }

    /** Written whole or not at all: a temporary file renamed over the old one. */
    private fun writeFile(appId: String, token: String): Boolean = try {
        if (!dir.isDirectory) dir.mkdirs()
        val target = file(appId)
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(token)
        temp.renameTo(target) || (target.delete() && temp.renameTo(target))
    } catch (e: Exception) {
        false
    }

    companion object {
        @Volatile private var shared: ChatTokenStore? = null

        /** The one store for this app (the SDK's SharedPreferences file is Affiliateo.kt OPT_OUT_PREFS). */
        fun of(context: Context): ChatTokenStore = shared ?: synchronized(this) {
            shared ?: run {
                val app = context.applicationContext
                val prefs = app.getSharedPreferences("affiliateo", Context.MODE_PRIVATE)
                ChatTokenStore(
                    dir = File(app.noBackupFilesDir, "affiliateo"),
                    legacy = object : Legacy {
                        override fun get(key: String): String? = prefs.getString(key, null)
                        override fun remove(key: String) {
                            prefs.edit().remove(key).apply()
                        }
                    },
                    disk = Executors.newSingleThreadExecutor { task -> Thread(task, "affiliateo-chat-key").apply { isDaemon = true } },
                ).also { shared = it }
            }
        }
    }
}
