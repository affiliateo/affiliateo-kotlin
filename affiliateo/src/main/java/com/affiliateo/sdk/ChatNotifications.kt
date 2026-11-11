package com.affiliateo.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * Live Chat's notifications (4.17.0): a reply from the team, and the greeting
 * once notifications are allowed, shown like a message: the Live Chat app's
 * name, then the words. A tap opens the app with the chat on top
 * (Affiliateo.handleChatIntent).
 *
 * The SDK draws them itself, with Android's own classes and no library:
 * - a push the app's messaging service hands to Affiliateo.handleChatPush
 *   (the server sends data only, so it looks the same open or closed);
 * - a reply that comes in on the thread's socket while the app is open and
 *   the server has no push token for this phone;
 * - the greeting, right after the app says notifications are allowed.
 *
 * They show under one channel, "Support messages" (in the phone's language),
 * which the person can switch off in Android's settings. The small icon is
 * the app's own: the drawable its manifest names under [META_ICON] (with an
 * optional color under [META_COLOR]), else the one it names for Firebase's
 * notifications, else a plain speech bubble.
 */
internal object ChatNotices {
    /** The extra on the intent a notification's tap opens the app with; its value tells one tap from the next. */
    const val EXTRA_OPEN = "com.affiliateo.sdk.chat.OPEN"

    /** `<meta-data android:name="com.affiliateo.sdk.chat_notification_icon" android:resource="@drawable/..." />` */
    const val META_ICON = "com.affiliateo.sdk.chat_notification_icon"

    /** `<meta-data android:name="com.affiliateo.sdk.chat_notification_color" android:resource="@color/..." />` */
    const val META_COLOR = "com.affiliateo.sdk.chat_notification_color"

    private const val FIREBASE_ICON = "com.google.firebase.messaging.default_notification_icon"
    private const val FIREBASE_COLOR = "com.google.firebase.messaging.default_notification_color"

    /** The one number Android knows the chat's notifications by; each has its own tag. */
    private const val NOTIFICATION_ID = 0x4C435F31

    private fun manager(context: Context): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /** Whether a notification would show: the person allows the app's notifications, and has not switched this channel off. */
    fun allowed(context: Context): Boolean {
        val manager = manager(context) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = manager.getNotificationChannel(ChatRules.NOTICE_CHANNEL)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    /** The app's own name, a notification's title when nothing better is known. */
    fun appLabel(context: Context): String = try {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    } catch (e: Exception) {
        ""
    }

    /**
     * Shows one notification; false when the person does not allow them (or
     * Android refused). [tag] names it: the same tag again takes the place of
     * the one before without ringing twice. [chatScreenUp]: the chat screen
     * is up behind another app, so a tap only has to bring it back.
     */
    fun post(context: Context, tag: String?, title: String, body: String, chatScreenUp: Boolean = false): Boolean {
        val app = context.applicationContext
        val manager = manager(app) ?: return false
        if (!allowed(app)) return false
        return try {
            val language = Locale.getDefault().toLanguageTag()
            if (Build.VERSION.SDK_INT >= 26) {
                // Making it again only renames it, in the phone's language now.
                manager.createNotificationChannel(
                    NotificationChannel(ChatRules.NOTICE_CHANNEL, ChatRules.chatWords(language).supportMessages, NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val nonce = UUID.randomUUID().toString()
            @Suppress("DEPRECATION")
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(app, ChatRules.NOTICE_CHANNEL)
            } else {
                Notification.Builder(app).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
            }
            builder
                .setSmallIcon(icon(app))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
            val tint = color(app)
            if (tint != null) builder.setColor(tint)
            val tap = if (chatScreenUp) backToChat(app, nonce) else openIntent(app, nonce)
            if (tap != null) builder.setContentIntent(tap)
            manager.notify(tag ?: "message:$nonce", NOTIFICATION_ID, builder.build())
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Takes the chat's notifications away: the person is looking at the chat. */
    fun clear(context: Context) {
        val manager = manager(context) ?: return
        try {
            manager.activeNotifications.filter { it.id == NOTIFICATION_ID && isOurs(it.notification) }.forEach { manager.cancel(it.tag, it.id) }
        } catch (e: Exception) {
            // Nothing to take away.
        }
    }

    private fun isOurs(notification: Notification): Boolean =
        if (Build.VERSION.SDK_INT >= 26) notification.channelId == ChatRules.NOTICE_CHANNEL else true

    /**
     * What a tap opens: the screen the app starts with, as its launcher icon
     * would, plus [EXTRA_OPEN]. An app that is already open comes to the
     * front as it was (its top screen gets the intent in onNewIntent), and
     * a closed one starts; either way the app passes the intent to
     * Affiliateo.handleChatIntent, which opens the chat on top.
     */
    private fun openIntent(context: Context, nonce: String): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.setPackage(context.packageName)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launch.putExtra(EXTRA_OPEN, nonce)
        return PendingIntent.getActivity(context, nonce.hashCode(), launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * What a tap opens while the chat screen is already up (the person left
     * the app with the chat open): that same screen, brought back to the
     * front as it is (single top, so no second one is made).
     */
    private fun backToChat(context: Context, nonce: String): PendingIntent {
        val chat = Intent(context, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, nonce.hashCode(), chat, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun metaData(context: Context) = try {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    } catch (e: Exception) {
        null
    }

    /** The app's own notification icon (a white shape on clear), or the SDK's speech bubble. */
    private fun icon(context: Context): Int {
        val meta = metaData(context)
        val named = meta?.getInt(META_ICON, 0)?.takeIf { it != 0 } ?: meta?.getInt(FIREBASE_ICON, 0)?.takeIf { it != 0 }
        return named ?: R.drawable.affiliateo_chat_notification
    }

    /** The color Android tints the small icon and the app's name with, when the app names one. */
    private fun color(context: Context): Int? {
        val meta = metaData(context)
        val id = meta?.getInt(META_COLOR, 0)?.takeIf { it != 0 } ?: meta?.getInt(FIREBASE_COLOR, 0)?.takeIf { it != 0 } ?: return null
        return try {
            context.getColor(id)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * The two calls that tell the server about this phone's push token
 * (/api/live-chat/push-devices): only JSON, no key and no cookie. Neither
 * ever throws.
 */
internal object ChatPushApi {
    /** Registers the phone (POST) or has it forgotten (DELETE); what the server said is read by ChatRules.pushAnswer. */
    suspend fun send(apiUrl: String, method: String, body: String): ChatRules.PushAnswer = withContext(Dispatchers.IO) {
        try {
            val conn = URL("${apiUrl.trimEnd('/')}/api/live-chat/push-devices").openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = if (code in 200..299) conn.inputStream.bufferedReader().readText() else null
                ChatRules.pushAnswer(code, text)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            ChatRules.PushAnswer.NONE
        }
    }
}
