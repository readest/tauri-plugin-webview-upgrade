package com.readest.webview_upgrade

import android.app.ActivityManager
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.startup.Initializer
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.norman.webviewup.lib.WebViewUpgrade
import com.norman.webviewup.lib.source.UpgradePackageSource

// Bootstraps a WebView swap on devices whose system WebView is too old
// (Huawei e-ink readers, Moaan / Rockchip ROMs, AOSP builds without Play
// Store, etc.) by redirecting our in-process WebView loader to a sideloaded
// com.google.android.webview when one is installed and recent enough.
//
// Runs during process attach via App Startup, *before* Application.onCreate
// and long before Tauri/Wry creates any WebView. That ordering is the only
// window in which WebViewUpgrade is allowed to swap the provider — once any
// WebView exists in the process, the binding is locked.
//
// Thresholds (MIN_UPGRADE_MAJOR / MIN_SUPPORTED_MAJOR) come from
// `plugins.webview-upgrade` in the host's tauri.conf.json — baked into
// WebViewUpgradeConfig at Gradle build time. See build.gradle.kts.
class WebViewUpgradeInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        if (!isMainProcess(context)) return
        try {
            maybeUpgradeWebView(context)
        } catch (t: Throwable) {
            Log.w(TAG, "WebView upgrade skipped due to unexpected error", t)
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private fun maybeUpgradeWebView(context: Context) {
        Log.i(
            TAG,
            "Device: manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} " +
                "sdk=${Build.VERSION.SDK_INT} abis=${Build.SUPPORTED_ABIS.joinToString(",")}"
        )
        Log.i(
            TAG,
            "Config: minUpgradeMajor=${WebViewUpgradeConfig.MIN_UPGRADE_MAJOR} " +
                "minSupportedMajor=${WebViewUpgradeConfig.MIN_SUPPORTED_MAJOR}"
        )

        val systemPkg = try {
            WebView.getCurrentWebViewPackage()
        } catch (t: Throwable) {
            Log.w(TAG, "getCurrentWebViewPackage failed", t)
            null
        }
        if (systemPkg != null) {
            Log.i(
                TAG,
                "System WebView: package=${systemPkg.packageName} " +
                    "versionName=${systemPkg.versionName} " +
                    "versionCode=${versionCodeCompat(systemPkg)}"
            )
        } else {
            Log.w(TAG, "System WebView: NONE / could not query")
        }

        // Probed for diagnostic logs. The manifest's <queries> entries must
        // match these or PackageManager.getPackageInfo() returns null on
        // Android 11+.
        for (pkg in PROBED_PACKAGES) {
            describeInstalled(context, pkg)
        }

        val systemMajor = majorOf(systemPkg?.versionName)
        val androidWebViewMajor = installedPackageMajor(context, ANDROID_WEBVIEW_PKG)
        val upgradeCandidates = UPGRADE_CANDIDATES.map { pkg ->
            pkg to installedPackageMajor(context, pkg)
        }

        val upgradedSuccessfully = maybeRunUpgrade(context, systemMajor, upgradeCandidates)

        // Notice condition: if neither com.android.webview nor any of the
        // upgrade candidates (com.google.android.webview plus the .beta /
        // .dev / .canary channels as fallbacks for devices where the stable
        // channel is also locked) meets the "supported" threshold AND we
        // didn't successfully upgrade to a recent provider, the user is
        // about to see a degraded render. Schedule a modal AlertDialog for
        // the first Activity that comes to the foreground.
        // "Not installed" (-1) is treated as "below threshold" — the user
        // can't render from it.
        //
        // Short-circuit: if the system's *currently active* WebView provider
        // already meets MIN_SUPPORTED_MAJOR (covers vendor providers such as
        // com.huawei.webview that aren't in UPGRADE_CANDIDATES), suppress the
        // notice — rendering will work, even if we couldn't swap to a Google
        // WebView candidate.
        if (!upgradedSuccessfully &&
            systemMajor < WebViewUpgradeConfig.MIN_SUPPORTED_MAJOR &&
            androidWebViewMajor < WebViewUpgradeConfig.MIN_SUPPORTED_MAJOR &&
            upgradeCandidates.all { (_, major) -> major < WebViewUpgradeConfig.MIN_SUPPORTED_MAJOR }
        ) {
            scheduleOutdatedNotice(context)
        }
    }

    private fun maybeRunUpgrade(
        context: Context,
        systemMajor: Int,
        candidates: List<Pair<String, Int>>,
    ): Boolean {
        if (systemMajor < 0) {
            Log.i(TAG, "Could not determine system WebView major version; leaving as-is")
            return false
        }
        if (systemMajor >= WebViewUpgradeConfig.MIN_UPGRADE_MAJOR) {
            Log.i(
                TAG,
                "System WebView major=$systemMajor >= ${WebViewUpgradeConfig.MIN_UPGRADE_MAJOR}; " +
                    "no upgrade needed"
            )
            return true
        }
        // Candidates are tried in preference order — stable channel first,
        // then the .dev channel for users whose stable WebView is itself
        // not updatable.
        val viable = candidates.firstOrNull { (_, major) ->
            major >= WebViewUpgradeConfig.MIN_UPGRADE_MAJOR
        }
        if (viable == null) {
            val summary = candidates.joinToString { (pkg, major) -> "$pkg=$major" }
            Log.w(
                TAG,
                "System WebView major=$systemMajor < ${WebViewUpgradeConfig.MIN_UPGRADE_MAJOR}, " +
                    "but no upgrade candidate is viable ($summary). " +
                    "Sideload a recent $GOOGLE_WEBVIEW_PKG APK to fix rendering."
            )
            return false
        }
        val (pkg, major) = viable

        Log.i(TAG, "Upgrading WebView: system major=$systemMajor -> $pkg major=$major")
        WebViewUpgrade.upgrade(UpgradePackageSource(context, pkg))

        return when {
            WebViewUpgrade.isCompleted() -> {
                Log.i(
                    TAG,
                    "WebView upgraded to ${WebViewUpgrade.getUpgradeWebViewPackageName()} " +
                        "${WebViewUpgrade.getUpgradeWebViewVersion()}"
                )
                true
            }
            WebViewUpgrade.isFailed() -> {
                Log.w(TAG, "WebView upgrade failed", WebViewUpgrade.getUpgradeError())
                false
            }
            else -> {
                Log.w(TAG, "WebView upgrade did not complete synchronously")
                false
            }
        }
    }

    // The Initializer runs before any Activity exists; an AlertDialog can only
    // be shown against a live Activity, so we hook ActivityLifecycleCallbacks
    // and surface the notice on the first onActivityResumed.
    //
    // AlertDialog (vs the old Toast): doesn't auto-dismiss, has a system-themed
    // OK button users must tap to acknowledge, and renders at the device's
    // standard dialog text size (Toast's font was too small for serious
    // messages). The text comes from the per-locale string resource, so the
    // user's system language picks one of the bundled translations.
    private fun scheduleOutdatedNotice(context: Context) {
        val app = context.applicationContext as? Application ?: return
        val message = app.getString(R.string.webview_upgrade_outdated_message)
        Log.w(TAG, "Scheduling outdated-WebView notice: $message")

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var shown = false

            override fun onActivityResumed(activity: Activity) {
                if (shown) return
                if (activity.isFinishing || activity.isDestroyed) return
                shown = true
                app.unregisterActivityLifecycleCallbacks(this)
                // Append download hyperlinks (Play Store's WebView is a split
                // bundle WebViewUpgrade can't load, so point at monolithic
                // builds; Uptodown is the backup mirror). Many e-ink ROMs ship
                // without a browser, so taps are guarded.
                val download = app.getString(
                    R.string.webview_upgrade_download_message,
                    *WEBVIEW_DOWNLOAD_LINKS.map { it.first }.toTypedArray()
                )
                val text = SpannableString("$message\n\n$download")
                for ((label, url) in WEBVIEW_DOWNLOAD_LINKS) {
                    val start = text.indexOf(label, message.length)
                    text.setSpan(
                        object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                try {
                                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                } catch (e: ActivityNotFoundException) {
                                    // Sealed ROMs (confirmed on an iReader A10:
                                    // zero https handlers) ship without any
                                    // browser, so the tap has nowhere to go.
                                    // Land the address on the clipboard so it
                                    // can be pasted once a browser is
                                    // installed — including bundled app-store
                                    // browsers — or sent to another device.
                                    Log.w(TAG, "Cannot open $url", e)
                                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as? ClipboardManager
                                    clipboard?.setPrimaryClip(
                                        ClipData.newPlainText("webview-download-url", url)
                                    )
                                    Toast.makeText(
                                        activity,
                                        R.string.webview_upgrade_no_browser_toast,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } catch (t: Throwable) {
                                    Log.w(TAG, "Cannot open $url", t)
                                }
                            }

                            override fun updateDrawState(ds: TextPaint) {
                                super.updateDrawState(ds)
                                // E-ink: pure black links (the Material
                                // accent renders as a gray link on the panel).
                                if (isEinkDevice()) {
                                    ds.color = Color.BLACK
                                    ds.isUnderlineText = true
                                }
                            }
                        },
                        start,
                        start + label.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                showOutdatedDialog(activity, text)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    // E-ink dialog chrome on the stock AlertDialog: the App already uses
    // Theme.MaterialComponents, so this dialog's fonts/layout/button are the
    // original Material design. Keep that component and change only the
    // chrome - repaint the window background as white with a black stroke
    // (no elevation/shadow, stock padding preserved via InsetDrawable) and
    // give the MaterialButton its own black stroke. E-ink only; other
    // platforms keep the dialog untouched.
    private fun showOutdatedDialog(activity: Activity, text: SpannableString) {
        val eink = isEinkDevice()
        val density = activity.resources.displayMetrics.density
        val stroke = (1f * density).toInt().coerceAtLeast(1)
        // Match the app's dialog tokens (themes.ts: --radius-box 1rem for
        // panels, --radius-field 0.5rem for buttons; CSS px ≈ dp here) so
        // the native dialog reads like every web dialog on the same panel.
        val radius = 16f * density
        val buttonRadius = 8f * density
        // Create first so the e-ink window attributes can be pinned BEFORE
        // show(): the window is added with whatever dim its attributes carry,
        // so clearing the dim only after show() briefly displays the stock
        // dim veil - on e-ink that is a real drawn frame that later
        // refreshes ghost.
        val dialog = AlertDialog.Builder(activity)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        if (eink) {
            dialog.window?.setDimAmount(0f)
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
        if (eink) {
            val panel = MaterialShapeDrawable(
                ShapeAppearanceModel.builder()
                    .setAllCorners(CornerFamily.ROUNDED, radius)
                    .build(),
            ).apply {
                fillColor = ColorStateList.valueOf(Color.WHITE)
                strokeWidth = stroke.toFloat()
                strokeColor = ColorStateList.valueOf(Color.BLACK)
                elevation = 0f
            }
            val pad = Rect()
            dialog.window?.decorView?.background?.getPadding(pad)
            val background = if (pad.left != 0 || pad.top != 0 || pad.right != 0 || pad.bottom != 0) {
                InsetDrawable(panel, pad.left, pad.top, pad.right, pad.bottom)
            } else {
                panel
            }
            dialog.window?.setBackgroundDrawable(background)
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(Color.BLACK)
            (dialog.getButton(AlertDialog.BUTTON_POSITIVE) as? MaterialButton)?.let { button ->
                button.setTextColor(Color.BLACK)
                button.setBackgroundColor(Color.WHITE)
                button.strokeWidth = stroke
                button.strokeColor = ColorStateList.valueOf(Color.BLACK)
                button.cornerRadius = buttonRadius.toInt()
            }
            // The dialog's outline shadow must be killed AFTER show():
            // generateLayout re-applies the theme-resolved elevation (16dp)
            // while showing, clobbering any pre-show value, and RenderThread
            // then draws the decor's shadow into the ~66px surface insets
            // around the panel - a gray gradient e-ink renders as a dirty
            // halo. window.setElevation reaches the live decor directly once
            // it exists, so zeroing here wins.
            dialog.window?.setElevation(0f)
        }
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod =
            LinkMovementMethod.getInstance()
    }

    private fun describeInstalled(context: Context, pkg: String) {
        val info = try {
            context.packageManager.getPackageInfo(pkg, 0)
        } catch (_: Throwable) {
            null
        }
        if (info == null) {
            Log.i(TAG, "Candidate $pkg: NOT installed (or hidden by package visibility)")
            return
        }
        val major = majorOf(info.versionName)
        val sourceDir = info.applicationInfo?.sourceDir
        Log.i(
            TAG,
            "Candidate $pkg: installed versionName=${info.versionName} " +
                "major=$major sourceDir=$sourceDir"
        )
    }

    private fun installedPackageMajor(context: Context, pkg: String): Int = try {
        majorOf(context.packageManager.getPackageInfo(pkg, 0).versionName)
    } catch (_: Throwable) {
        -1
    }

    private fun majorOf(versionName: String?): Int {
        if (versionName.isNullOrBlank()) return -1
        val head = versionName.takeWhile { it.isDigit() }
        return head.toIntOrNull() ?: -1
    }

    // PackageInfo.longVersionCode was introduced in API 28 (P). The plugin's
    // minSdk is 21, and devices in the wild (e.g. Kobo mooInk Plus 2 on API 27)
    // still call into here, so an unguarded read crashes with NoSuchMethodError.
    // Fall back to the deprecated int versionCode on API < 28.
    @Suppress("DEPRECATION")
    private fun versionCodeCompat(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

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
        private const val TAG = "WebViewUpgrade"
        private val WEBVIEW_DOWNLOAD_LINKS = listOf(
            "APKMirror" to "https://www.apkmirror.com/apk/google-inc/android-system-webview/",
            "Uptodown" to "https://android-system-webview.en.uptodown.com/android",
        )

        private const val GOOGLE_WEBVIEW_PKG = "com.google.android.webview"
        private const val GOOGLE_WEBVIEW_BETA_PKG = "com.google.android.webview.beta"
        private const val GOOGLE_WEBVIEW_DEV_PKG = "com.google.android.webview.dev"
        private const val GOOGLE_WEBVIEW_CANARY_PKG = "com.google.android.webview.canary"
        private const val ANDROID_WEBVIEW_PKG = "com.android.webview"

        // Upgrade preference order: stable first, then progressively less
        // stable channels as fallbacks for devices where the stable
        // com.google.android.webview is also pinned to an old version and
        // can't be updated through normal channels.
        private val UPGRADE_CANDIDATES = listOf(
            GOOGLE_WEBVIEW_PKG,
            GOOGLE_WEBVIEW_BETA_PKG,
            GOOGLE_WEBVIEW_DEV_PKG,
            GOOGLE_WEBVIEW_CANARY_PKG,
        )

        private val PROBED_PACKAGES = listOf(
            GOOGLE_WEBVIEW_PKG,
            GOOGLE_WEBVIEW_BETA_PKG,
            GOOGLE_WEBVIEW_DEV_PKG,
            GOOGLE_WEBVIEW_CANARY_PKG,
            ANDROID_WEBVIEW_PKG,
            "com.android.chrome",
            "com.huawei.webview",
        )
    }
}
