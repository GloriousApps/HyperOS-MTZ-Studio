package dev.glorioustr.mtzstudio

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.glorioustr.mtzstudio.shevery.SheveryAccess
import dev.glorioustr.mtzstudio.shevery.SheveryAuthorizationStatus
import dev.glorioustr.mtzstudio.tester.PrivilegedCommandResult
import dev.glorioustr.mtzstudio.tester.SuPrivilegedCommandRunner
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal fun shouldReportThemeGuardConnectionLost(previous: Boolean?, connected: Boolean): Boolean =
    previous == true && !connected

/**
 * Watches the actual Xiaomi theme component files while Shizuku is available. A BAK restore only
 * imports a theme into Xiaomi's private library; persistence is armed separately after the user
 * returns from the apply flow. When a component changes, the exact previously resolved Xiaomi
 * apply intent is offered again instead of guessing a version-specific activity.
 */
class ThemePersistenceGuardService : Service() {
    private val checking = AtomicBoolean(false)
    private var scheduler: ScheduledExecutorService? = null
    private var privilegeConnected: Boolean? = null
    private var directRootAvailable: Boolean? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action in GUARDED_ACTIONS && isOrderedBroadcast) {
                abortBroadcast()
                diagnostics.record(
                    "rootless_theme_check_blocked",
                    "Xiaomi yerel tema doğrulama yayını durduruldu",
                    mapOf("action" to intent.action),
                )
            }
            if (intent.action == Intent.ACTION_SCREEN_ON || intent.action == Intent.ACTION_USER_PRESENT) {
                scheduleImmediateCheck()
            }
        }
    }

    private val diagnostics by lazy { LiveDiagnosticsRecorder.get(this) }
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIFICATION_ID, monitoringNotification(ConnectionState.CHECKING))
        val filter = IntentFilter().apply {
            priority = 1_000
            GUARDED_ACTIONS.forEach(::addAction)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "mtz-theme-watch").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay(::checkTheme, INITIAL_CHECK_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS)
        }
        diagnostics.record("theme_guard_started", "Shizuku tema bileşeni izleyicisi başlatıldı")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply()
            getSystemService(NotificationManager::class.java).apply {
                cancel(REAPPLY_NOTIFICATION_ID)
                cancel(CONNECTION_NOTIFICATION_ID)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ARM) {
            val themeName = intent.getStringExtra(EXTRA_THEME_NAME).orEmpty()
            val applyIntentUri = intent.getStringExtra(EXTRA_APPLY_INTENT).orEmpty()
            if (themeName.isNotBlank() && applyIntentUri.isNotBlank()) {
                prefs.edit()
                    .putBoolean(KEY_ENABLED, true)
                    .putString(KEY_THEME_NAME, themeName)
                    .putString(KEY_APPLY_INTENT, applyIntentUri)
                    .remove(KEY_BASELINE)
                    .remove(KEY_LAST_ALERTED_FINGERPRINT)
                    .apply()
                diagnostics.record("theme_watch_armed", "Tema bileşeni izleme kaydı oluşturuldu", mapOf("theme" to themeName))
                scheduler?.schedule(::captureBaseline, BASELINE_DELAY_SECONDS, TimeUnit.SECONDS)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scheduler?.shutdownNow()
        scheduler = null
        runCatching { unregisterReceiver(receiver) }
        diagnostics.record("theme_guard_stopped", "Tema bileşeni izleyicisi durdu")
        super.onDestroy()
    }

    private fun scheduleImmediateCheck() {
        scheduler?.schedule(::checkTheme, 3, TimeUnit.SECONDS)
    }

    private fun captureBaseline() {
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        val fingerprint = readFingerprint() ?: return
        prefs.edit().putString(KEY_BASELINE, fingerprint).apply()
        diagnostics.record(
            "theme_watch_baseline_saved",
            "Uygulanan temanın bileşen imzası kaydedildi",
            mapOf("theme" to prefs.getString(KEY_THEME_NAME, ""), "fingerprint" to fingerprint.take(16)),
        )
    }

    private fun checkTheme() {
        if (!prefs.getBoolean(KEY_ENABLED, false) || !checking.compareAndSet(false, true)) return
        try {
            if (!hasPrivilegedAccess()) {
                updateConnectionState(connected = false)
                return
            }
            val baseline = prefs.getString(KEY_BASELINE, null)
            if (baseline.isNullOrBlank()) {
                captureBaseline()
                return
            }
            val current = readFingerprint() ?: return
            if (current == baseline) {
                if (prefs.contains(KEY_LAST_ALERTED_FINGERPRINT)) {
                    prefs.edit().remove(KEY_LAST_ALERTED_FINGERPRINT).apply()
                }
                return
            }
            if (prefs.getString(KEY_LAST_ALERTED_FINGERPRINT, null) == current) return
            prefs.edit().putString(KEY_LAST_ALERTED_FINGERPRINT, current).apply()
            diagnostics.record(
                "theme_revert_detected",
                "Aktif tema bileşenleri kaydedilen temadan farklılaştı",
                mapOf(
                    "theme" to prefs.getString(KEY_THEME_NAME, ""),
                    "expected" to baseline.take(16),
                    "actual" to current.take(16),
                ),
            )
            if (!attemptAutomaticRepair(current)) showReapplyNotification()
        } finally {
            checking.set(false)
        }
    }

    private fun attemptAutomaticRepair(detectedFingerprint: String): Boolean {
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            diagnostics.record("theme_auto_repair_deferred", "Otomatik tema onarımı cihaz kilidi açılana kadar ertelendi")
            prefs.edit().remove(KEY_LAST_ALERTED_FINGERPRINT).apply()
            return true
        }
        val now = System.currentTimeMillis()
        val lastAttempt = prefs.getLong(KEY_LAST_AUTO_REPAIR_AT, 0L)
        if (now - lastAttempt < AUTO_REPAIR_COOLDOWN_MS) {
            diagnostics.record("theme_auto_repair_rate_limited", "Otomatik tema onarımı tekrar döngüsünü önlemek için sınırlandı")
            return false
        }
        val uri = prefs.getString(KEY_APPLY_INTENT, null) ?: return false
        val target = runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return false
        val command = buildActivityCommand(target) ?: return false
        prefs.edit().putLong(KEY_LAST_AUTO_REPAIR_AT, now).apply()
        val result = runCatching { executePrivileged(command, AUTO_REPAIR_TIMEOUT_SECONDS) }
            .onFailure {
                diagnostics.record("theme_auto_repair_launch_failed", "Otomatik tema onarımı başlatılamadı", error = it)
            }.getOrNull() ?: return false
        if (result.exitCode != 0) {
            diagnostics.record(
                "theme_auto_repair_launch_failed",
                "Xiaomi Temalar otomatik onarım isteğini kabul etmedi",
                mapOf("exitCode" to result.exitCode, "output" to result.output.takeLast(500)),
            )
            return false
        }
        diagnostics.record(
            "theme_auto_repair_started",
            "Son uygulanan tema otomatik olarak yeniden uygulanıyor",
            mapOf("theme" to prefs.getString(KEY_THEME_NAME, ""), "source" to result.authorizationSource),
        )
        scheduler?.schedule(
            { verifyAutomaticRepair(detectedFingerprint) },
            AUTO_REPAIR_VERIFY_SECONDS,
            TimeUnit.SECONDS,
        )
        return true
    }

    private fun verifyAutomaticRepair(detectedFingerprint: String) {
        val baseline = prefs.getString(KEY_BASELINE, null) ?: return
        val current = readFingerprint() ?: run {
            showReapplyNotification()
            return
        }
        if (current == baseline) {
            prefs.edit().remove(KEY_LAST_ALERTED_FINGERPRINT).apply()
            getSystemService(NotificationManager::class.java).cancel(REAPPLY_NOTIFICATION_ID)
            diagnostics.record(
                "theme_auto_repair_completed",
                "Tema otomatik olarak yeniden uygulandı",
                mapOf("theme" to prefs.getString(KEY_THEME_NAME, "")),
            )
        } else {
            diagnostics.record(
                "theme_auto_repair_unverified",
                "Otomatik tema onarımı doğrulanamadı; elle yeniden uygulama sunuluyor",
                mapOf("detected" to detectedFingerprint.take(16), "actual" to current.take(16)),
            )
            showReapplyNotification()
        }
    }

    private fun buildActivityCommand(target: Intent): String? {
        val component = target.component?.flattenToShortString() ?: return null
        return buildString {
            append("/system/bin/am start --user 0 -W -n ").append(shellQuote(component))
            target.action?.takeIf(String::isNotBlank)?.let { append(" -a ").append(shellQuote(it)) }
            target.dataString?.let { append(" -d ").append(shellQuote(it)) }
            target.categories?.sorted()?.forEach { append(" -c ").append(shellQuote(it)) }
            if (target.flags != 0) append(" -f 0x").append(target.flags.toUInt().toString(16))
            target.extras?.keySet()?.sorted()?.forEach { key ->
                when (val value = target.extras?.get(key)) {
                    is String -> append(" --es ").append(shellQuote(key)).append(' ').append(shellQuote(value))
                    is Long -> append(" --el ").append(shellQuote(key)).append(' ').append(value)
                    is Int -> append(" --ei ").append(shellQuote(key)).append(' ').append(value)
                    is Boolean -> append(" --ez ").append(shellQuote(key)).append(' ').append(value)
                    is Float -> append(" --ef ").append(shellQuote(key)).append(' ').append(value)
                }
            }
        }
    }

    private fun executePrivileged(command: String, timeoutSeconds: Long): PrivilegedCommandResult {
        val shevery = SheveryAccess(applicationContext)
        return when (shevery.status()) {
            SheveryAuthorizationStatus.ADB_READY -> shevery.executeShell(command, timeoutSeconds)
            SheveryAuthorizationStatus.ROOT_READY -> shevery.executeRoot(command, timeoutSeconds)
            else -> SuPrivilegedCommandRunner().run(command, timeoutSeconds)
        }
    }

    private fun hasPrivilegedAccess(): Boolean {
        return when (SheveryAccess(applicationContext).status()) {
            SheveryAuthorizationStatus.ADB_READY,
            SheveryAuthorizationStatus.ROOT_READY,
            -> true
            SheveryAuthorizationStatus.PERMISSION_REQUIRED,
            SheveryAuthorizationStatus.SERVICE_NOT_RUNNING,
            -> directRootAvailable ?: runCatching {
                val result = SuPrivilegedCommandRunner().run("id -u", 5)
                result.exitCode == 0 && result.output.lineSequence().firstOrNull()?.trim() == "0"
            }.getOrDefault(false).also { directRootAvailable = it }
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private fun readFingerprint(): String? {
        val access = SheveryAccess(applicationContext)
        return runCatching {
            if (!access.status().isReady()) {
                updateConnectionState(connected = false)
                return@runCatching null
            }
            val result = executePrivileged(FINGERPRINT_COMMAND, 20)
            check(result.exitCode == 0 && result.output.isNotBlank()) { "Tema bileşenleri okunamadı" }
            updateConnectionState(connected = true)
            result.output.lineSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .sorted()
                .joinToString("|")
                .sha256()
        }.onFailure {
            // A live ADB-mode Shevery service can be healthy while the shell command is denied
            // by the ROM's theme-data SELinux policy.  That is not a lost Shevery connection and
            // must not produce the alarming "connection lost" notification shown at startup.
            val serviceReady = access.status().isReady()
            updateConnectionState(connected = serviceReady)
            diagnostics.record(
                if (serviceReady) "theme_watch_target_check_failed" else "theme_watch_check_failed",
                if (serviceReady) {
                    "Shizuku/Shevery bağlı ancak tema bileşenleri okunamadı"
                } else {
                    "Tema bileşeni kontrolü tamamlanamadı"
                },
                error = it,
            )
        }.getOrNull()
    }

    private fun SheveryAuthorizationStatus.isReady(): Boolean =
        this == SheveryAuthorizationStatus.ADB_READY || this == SheveryAuthorizationStatus.ROOT_READY

    private fun updateConnectionState(connected: Boolean) {
        val previous = privilegeConnected
        privilegeConnected = connected
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            monitoringNotification(if (connected) ConnectionState.ACTIVE else ConnectionState.WAITING),
        )
        if (shouldReportThemeGuardConnectionLost(previous, connected)) {
            diagnostics.record(
                "theme_guard_connection_lost",
                "Shizuku/Shevery bağlantısı kesildi; tema koruması bağlantıyı bekliyor",
            )
            showConnectionLostNotification()
        } else if (connected) {
            manager.cancel(CONNECTION_NOTIFICATION_ID)
            if (previous == false) {
                diagnostics.record(
                    "theme_guard_connection_restored",
                    "Shizuku/Shevery bağlantısı geri geldi; tema koruması otomatik sürdürüldü",
                )
            }
        }
    }

    private fun showConnectionLostNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.theme_guard_waiting))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.theme_guard_waiting)))
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(CONNECTION_NOTIFICATION_ID, notification)
    }

    private fun showReapplyNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            diagnostics.record("theme_watch_notification_unavailable", "Tema geri dönüş bildirimi için bildirim izni yok")
            return
        }
        val uri = prefs.getString(KEY_APPLY_INTENT, null) ?: return
        val target = runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
        if (target.resolveActivity(packageManager) == null) {
            diagnostics.record("theme_watch_target_unavailable", "Kaydedilen Xiaomi Temalar uygulama yolu artık kullanılamıyor")
            return
        }
        val pending = PendingIntent.getActivity(
            this,
            REAPPLY_NOTIFICATION_ID,
            target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val name = prefs.getString(KEY_THEME_NAME, "") ?: ""
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.rootless_restore_notification_text, name))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.rootless_restore_notification_text, name)))
            .setContentIntent(pending)
            .addAction(0, "Korumayı durdur", disablePendingIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(REAPPLY_NOTIFICATION_ID, notification)
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun monitoringNotification(state: ConnectionState) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(
            getString(
                when (state) {
                    ConnectionState.CHECKING -> R.string.theme_protection_checking
                    ConnectionState.ACTIVE -> R.string.theme_guard_active
                    ConnectionState.WAITING -> R.string.theme_guard_waiting
                },
            ),
        )
        .setContentIntent(openAppPendingIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(0, getString(R.string.theme_protection_disable), disablePendingIntent())
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun disablePendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, ThemePersistenceGuardService::class.java).setAction(ACTION_DISABLE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun String.sha256(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFS = "theme-persistence"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_THEME_NAME = "theme-name"
        private const val KEY_APPLY_INTENT = "apply-intent"
        private const val KEY_BASELINE = "component-baseline"
        private const val KEY_LAST_ALERTED_FINGERPRINT = "last-alerted-fingerprint"
        private const val KEY_LAST_AUTO_REPAIR_AT = "last-auto-repair-at"
        // A new channel is required because Android does not allow increasing an existing
        // notification channel's importance after it has been created.
        private const val CHANNEL = "theme-persistence-v2"
        private const val ALERT_CHANNEL = "theme-persistence-alert"
        private const val NOTIFICATION_ID = 23102
        private const val REAPPLY_NOTIFICATION_ID = 23103
        private const val CONNECTION_NOTIFICATION_ID = 23104
        private const val ACTION_ARM = "dev.glorioustr.mtzstudio.action.ARM_THEME_WATCH"
        private const val ACTION_DISABLE = "dev.glorioustr.mtzstudio.action.DISABLE_THEME_WATCH"
        private const val EXTRA_THEME_NAME = "theme-name"
        private const val EXTRA_APPLY_INTENT = "apply-intent"
        private const val INITIAL_CHECK_SECONDS = 3L
        private const val BASELINE_DELAY_SECONDS = 25L
        private const val CHECK_INTERVAL_SECONDS = 30L
        private const val AUTO_REPAIR_VERIFY_SECONDS = 10L
        private const val AUTO_REPAIR_TIMEOUT_SECONDS = 30L
        private const val AUTO_REPAIR_COOLDOWN_MS = 10 * 60 * 1_000L
        private val GUARDED_ACTIONS = setOf("miui.intent.action.CHECK_TIME_UP", "miui.intent.action.CHECK_THEME_UPDATE")
        private const val FINGERPRINT_COMMAND =
            "for f in description.xml icons lockscreen framework-res framework-miui-res com.android.systemui com.miui.home; do " +
                "p=/data/system/theme/\u0024f; if [ -f \u0022\u0024p\u0022 ]; then /system/bin/toybox sha256sum \u0022\u0024p\u0022; " +
                "else echo MISSING:\u0024f; fi; done"

        private enum class ConnectionState { CHECKING, ACTIVE, WAITING }

        fun start(context: Context) {
            val intent = Intent(context, ThemePersistenceGuardService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun arm(context: Context, prepared: PreparedThemeApply) {
            val intent = Intent(context, ThemePersistenceGuardService::class.java).apply {
                action = ACTION_ARM
                putExtra(EXTRA_THEME_NAME, prepared.themeName)
                putExtra(EXTRA_APPLY_INTENT, prepared.intent.toUri(Intent.URI_INTENT_SCHEME))
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun resumeIfArmed(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val validRecord = prefs.getBoolean(KEY_ENABLED, false) &&
                !prefs.getString(KEY_THEME_NAME, null).isNullOrBlank() &&
                !prefs.getString(KEY_APPLY_INTENT, null).isNullOrBlank()
            if (!validRecord) {
                // Versions before the component watcher only stored an "enabled" bit. Do not
                // present that legacy flag as an armed watcher without a verified apply target.
                prefs.edit().putBoolean(KEY_ENABLED, false).apply()
                pause(context)
                return
            }
            start(context)
        }

        /** Stops active checking without forgetting the protected theme while Shizuku is offline. */
        fun pause(context: Context) {
            context.stopService(Intent(context, ThemePersistenceGuardService::class.java))
        }

        fun disable(context: Context) {
            context.stopService(Intent(context, ThemePersistenceGuardService::class.java))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
        }
    }
}
