package com.apricotiris.IrisShot.core.app

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process

class ApplicationLabelResolver(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)

    fun resolve(packageName: String): String? {
        val cleanPackage = packageName.trim()
        if (cleanPackage.isEmpty()) return null
        val launcherLabel = runCatching {
            launcherApps.getActivityList(cleanPackage, Process.myUserHandle())
                .firstOrNull()
                ?.label
                ?.toString()
        }.getOrNull()
        val applicationLabel = runCatching {
            @Suppress("DEPRECATION")
            val info = packageManager.getApplicationInfo(cleanPackage, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
        return sequenceOf(launcherLabel, applicationLabel)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull { it != cleanPackage }
    }
}
