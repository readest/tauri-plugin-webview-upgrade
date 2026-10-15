package com.readest.webview_upgrade

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.appcompat.app.AppCompatDelegate
import androidx.startup.Initializer

// On e-ink devices the native layer must present light chrome regardless of
// the system appearance. Every appcompat dialog resolves its theme from the
// activity's DayNight resources, so while the system dark mode is on — and
// e-ink ROMs keep it on for long stretches (Hanvon schedules it 22:00-06:00)
// — this plugin's outdated-WebView AlertDialog flips to the dark palette,
// over a host app whose own theme may be light. Verified on a Hanvon
// Clear7 (Android 14, WebView 109): the alert followed the system
// appearance, light in day mode and dark in night mode.
//
// Pinning MODE_NIGHT_NO once here — before any Activity exists — resolves
// every AppCompat activity in the process to light, so this plugin's dialog
// and the host's other dialogs all render light on e-ink. Devices that are
// not e-ink keep following the system appearance.
class EinkNightModeInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        if (!isMainProcess(context)) return
        if (!isEinkDevice()) return
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    // Mirrors the host app's Rust detector (readest/readest
    // src-tauri/src/android/eink.rs) via the EinkIdentity table above —
    // same properties, same semantics, so both sides agree on the device
    // class.
    private fun isEinkDevice(): Boolean {
        cachedIsEink?.let { return it }
        val manufacturer = (readSystemProperty("ro.product.manufacturer") ?: Build.MANUFACTURER)
            .lowercase()
        val brand = (readSystemProperty("ro.product.brand") ?: Build.BRAND).lowercase()
        val model = (readSystemProperty("ro.product.model") ?: Build.MODEL).lowercase()
        val device = (readSystemProperty("ro.product.device") ?: Build.DEVICE).lowercase()
        val isEink = isEinkIdentity(manufacturer, brand, model, device) ||
            // Boolean-style flag.
            readSystemProperty("ro.eink.support")?.let {
                it == "1" || it.lowercase() == "true"
            } == true ||
            // BOOX reports a free-form device name (e.g. "Note Air");
            // presence is the signal, like Rust's get_system_property(...).is_some().
            readSystemProperty("ro.onyx.devicename") != null
        cachedIsEink = isEink
        return isEink
    }

    private fun readSystemProperty(prop: String): String? = try {
        val get = Class
            .forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
        (get.invoke(null, prop) as? String)?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    // App Startup invokes Initializers in the main process only when they're
    // declared under InitializationProvider, but the host may also pull
    // androidx.startup into sandboxed child processes transitively. Guarding
    // here keeps the hook strictly main-process even in that case.
    private fun isMainProcess(context: Context): Boolean {
        val expected = context.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return expected == Application.getProcessName()
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true
        val pid = Process.myPid()
        val processes = am.runningAppProcesses ?: return true
        return processes.firstOrNull { it.pid == pid }?.processName == expected
    }

    companion object {
        private var cachedIsEink: Boolean? = null
    }
}
