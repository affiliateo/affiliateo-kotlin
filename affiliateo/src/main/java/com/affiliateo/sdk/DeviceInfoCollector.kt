package com.affiliateo.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.*

internal object DeviceInfoCollector {

    fun collect(context: Context): DeviceInfo {
        val displayMetrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: PackageManager.NameNotFoundException) {
            "1.0.0"
        }

        return DeviceInfo(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            os = "Android",
            osVersion = Build.VERSION.RELEASE,
            appVersion = appVersion,
            screenWidth = displayMetrics.widthPixels,
            screenHeight = displayMetrics.heightPixels,
            timezone = TimeZone.getDefault().id,
            language = Locale.getDefault().language
        )
    }
}
