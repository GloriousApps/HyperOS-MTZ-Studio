package dev.glorioustr.mtzstudio.themeservice

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import dev.glorioustr.mtzstudio.LiveDiagnosticsRecorder
import dev.glorioustr.mtzstudio.tester.ThemeServiceAvailability
import dev.glorioustr.mtzstudio.tester.ThemeServiceContract
import dev.glorioustr.mtzstudio.tester.ThemeServiceDeclaration
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.Method
import kotlin.coroutines.resume

/**
 * One unprivileged request: per-application icons, keyed by package name.
 *
 * The interface also exposes `saveWallpaper(String)` and `saveLockWallpaper(String)`, and they
 * are deliberately not used. Device testing on HyperOS OS3.0.308.0 showed Xiaomi Themes holds no
 * storage read permission at all (`READ_MEDIA_IMAGES` denied, `READ_EXTERNAL_STORAGE` appop
 * `ignore`, `MANAGE_EXTERNAL_STORAGE` rejected), so there is no path an unprivileged app could
 * pass that Themes could open. Wallpapers go through `WallpaperManager` instead — see
 * [RootlessPartialApply]. `saveCustomizedIcon` survives because the bitmap crosses the binder.
 */
data class ThemeServiceRequest(
    val customizedIcons: Map<String, Bitmap> = emptyMap(),
)

enum class ThemeServiceCall { ACCEPTED, REJECTED, FAILED, NOT_REQUESTED }

/**
 * What Themes actually answered. A [ThemeServiceCall.REJECTED] result means the service returned
 * false: the request reached Themes and Themes declined it. That is reported as-is and never
 * smoothed into success.
 */
data class ThemeServiceOutcome(
    val customizedIcons: Map<String, ThemeServiceCall> = emptyMap(),
    val bound: Boolean = false,
    val failureMessage: String? = null,
) {
    val requested: List<ThemeServiceCall>
        get() = customizedIcons.values.filter { it != ThemeServiceCall.NOT_REQUESTED }

    val anyAccepted: Boolean get() = requested.any { it == ThemeServiceCall.ACCEPTED }
    val allAccepted: Boolean get() = requested.isNotEmpty() && requested.all { it == ThemeServiceCall.ACCEPTED }

    fun describe(): Map<String, Any?> = mapOf(
        "bound" to bound,
        "iconsRequested" to customizedIcons.size,
        "iconsAccepted" to customizedIcons.values.count { it == ThemeServiceCall.ACCEPTED },
        "failure" to failureMessage,
    )
}

/**
 * Binds the unprivileged Themes wallpaper/icon service described by [ThemeServiceContract] and
 * sends one request. Availability is resolved by [ThemeServiceAvailability]; this class only
 * performs the call.
 *
 * Transaction codes are never hard-coded. DEX method order is not AIDL declaration order, so the
 * codes cannot be derived from the APK; the framework's own `IThemeService$Stub.asInterface` is
 * used instead, which is correct on every build that ships the interface.
 */
class ThemeServiceClient(private val context: Context) {

    private val diagnostics get() = LiveDiagnosticsRecorder.get(context.applicationContext)

    fun declaration(): ThemeServiceDeclaration = ThemeServiceAvailability.resolve(context)

    /**
     * Binds Themes, sends every requested item, then unbinds. The connection is short-lived on
     * purpose: this is a user-initiated action, not a background channel to hold open.
     */
    suspend fun send(request: ThemeServiceRequest, timeoutMillis: Long = BIND_TIMEOUT_MILLIS): ThemeServiceOutcome {
        if (request.customizedIcons.isEmpty()) {
            return ThemeServiceOutcome(failureMessage = "Gönderilecek bir öğe yok")
        }
        val declaration = declaration()
        if (!declaration.usable) {
            diagnostics.record(
                "theme_service_unusable",
                "Xiaomi Temalar ThemeService bu cihazda kullanılamıyor",
                declaration.describe(),
            )
            return ThemeServiceOutcome(failureMessage = context.getString(dev.glorioustr.mtzstudio.R.string.theme_service_unavailable))
        }
        diagnostics.record(
            "theme_service_bind_started",
            "Xiaomi Temalar ThemeService bağlanıyor",
            declaration.describe(),
        )
        var connection: ServiceConnection? = null
        return try {
            val binder = withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine<IBinder?> { continuation ->
                    val c = object : ServiceConnection {
                        private var settled = false
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            if (settled) return
                            settled = true
                            continuation.resume(service)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) = Unit

                        override fun onNullBinding(name: ComponentName?) {
                            if (settled) return
                            settled = true
                            continuation.resume(null)
                        }
                    }
                    connection = c
                    val started = runCatching {
                        context.bindService(ThemeServiceContract.bindIntent(), c, Context.BIND_AUTO_CREATE)
                    }.getOrDefault(false)
                    if (!started && !continuation.isCompleted) continuation.resume(null)
                }
            }
            if (binder == null) {
                diagnostics.record("theme_service_bind_failed", "ThemeService bağlantısı kurulamadı")
                return ThemeServiceOutcome(
                    failureMessage = context.getString(dev.glorioustr.mtzstudio.R.string.theme_service_bind_failed),
                )
            }
            val handle = handleFor(binder)
                ?: return ThemeServiceOutcome(
                    bound = true,
                    failureMessage = context.getString(dev.glorioustr.mtzstudio.R.string.theme_service_unavailable),
                )
            val outcome = ThemeServiceOutcome(
                customizedIcons = request.customizedIcons.mapValues { (packageName, bitmap) ->
                    handle.sendCustomizedIcon(packageName, bitmap)
                },
                bound = true,
            )
            diagnostics.record(
                "theme_service_result",
                if (outcome.anyAccepted) {
                    "Xiaomi Temalar isteği kabul etti"
                } else {
                    "Xiaomi Temalar isteği kabul etmedi"
                },
                outcome.describe(),
            )
            outcome
        } catch (error: Throwable) {
            diagnostics.record("theme_service_error", "ThemeService çağrısı tamamlanamadı", error = error)
            ThemeServiceOutcome(failureMessage = error.message ?: error::class.simpleName)
        } finally {
            connection?.let { runCatching { context.unbindService(it) } }
        }
    }

    private fun handleFor(binder: IBinder): Handle? = runCatching {
        val stub = Class.forName(ThemeServiceContract.STUB_CLASS)
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        val service = asInterface.invoke(null, binder) ?: return@runCatching null
        Handle(service, diagnostics)
    }.onFailure { error ->
        diagnostics.record(
            "theme_service_interface_missing",
            "miui.content.res.IThemeService bu ROM'da bulunamadı",
            error = error,
        )
    }.getOrNull()

    /** Thin reflective wrapper; it resolves methods by name and arity, never by binder code. */
    private class Handle(
        private val service: Any,
        private val diagnostics: LiveDiagnosticsRecorder,
    ) {
        private fun method(name: String, arity: Int): Method? =
            service.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == arity }

        fun sendCustomizedIcon(packageName: String, bitmap: Bitmap): ThemeServiceCall {
            val target = method(ThemeServiceContract.SAVE_CUSTOMIZED_ICON, 2) ?: return ThemeServiceCall.FAILED
            val accepted = runCatching { target.invoke(service, packageName, bitmap) as? Boolean }
                .onFailure { error ->
                    diagnostics.record(
                        "theme_service_call_failed",
                        "ThemeService ikon çağrısı hata verdi",
                        mapOf("package" to packageName),
                        error,
                    )
                }
                .getOrNull()
            diagnostics.record(
                "theme_service_call",
                "ThemeService ikon çağrısı tamamlandı",
                mapOf(
                    "method" to ThemeServiceContract.SAVE_CUSTOMIZED_ICON,
                    "package" to packageName,
                    "accepted" to accepted,
                ),
            )
            return when (accepted) {
                true -> ThemeServiceCall.ACCEPTED
                false -> ThemeServiceCall.REJECTED
                null -> ThemeServiceCall.FAILED
            }
        }
    }

    private companion object {
        const val BIND_TIMEOUT_MILLIS = 8_000L
    }
}
