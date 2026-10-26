package dev.glorioustr.mtzstudio

import dev.glorioustr.mtzstudio.tester.PrivilegedCommandRunner
import dev.glorioustr.mtzstudio.tester.ThemeManagerContract
import dev.glorioustr.mtzstudio.tester.ThemeManagerInspector

/**
 * Describes and, after explicit confirmation, removes a user-installed Theme Manager update so
 * Android can re-enable the immutable factory package. This is intentionally a rootless shell
 * operation: it is only exposed while Shizuku/Shevery provides an ADB shell channel.
 */
internal class FactoryThemeManagerRestorer(
    private val inspector: ThemeManagerInspector,
    private val commandRunner: PrivilegedCommandRunner,
) {
    data class State(
        val activeVersionName: String?,
        val factoryVersionName: String?,
        val factoryVersionCode: Long?,
    ) {
        val eligible: Boolean
            get() = activeVersionName != null &&
                factoryVersionName != null &&
                ThemeManagerContract.compareVersionNames(factoryVersionName, FACTORY_VERSION_CEILING)?.let { it <= 0 } == true &&
                ThemeManagerContract.compareVersionNames(activeVersionName, factoryVersionName)?.let { it > 0 } == true
    }

    fun inspect(): State {
        val activeVersion = inspector.inspect().versionName
        val result = commandRunner.run(FACTORY_QUERY, 20)
        check(result.exitCode == 0) { result.output.ifBlank { "Fabrika Xiaomi Temalar sürümü okunamadı" } }
        val lines = result.output.lineSequence().map(String::trim).toList()
        return State(
            activeVersionName = activeVersion,
            factoryVersionName = lines.value("FACTORY_VERSION"),
            factoryVersionCode = lines.value("FACTORY_CODE")?.toLongOrNull(),
        )
    }

    fun restoreFactory(state: State = inspect()): State {
        check(state.eligible) {
            "Fabrika Xiaomi Temalar sürümü 3.0.5.14 veya daha eski değil"
        }
        val result = commandRunner.run(
            """
            set -eu
            pm uninstall --user 0 ${ThemeManagerContract.PACKAGE_NAME}
            cmd package install-existing --user 0 ${ThemeManagerContract.PACKAGE_NAME}
            echo FACTORY_THEME_MANAGER_RESTORED
            """.trimIndent(),
            60,
        )
        check(result.exitCode == 0 && result.output.lineSequence().any { it.trim() == "FACTORY_THEME_MANAGER_RESTORED" }) {
            result.output.ifBlank { "Fabrika Xiaomi Temalar sürümüne dönülemedi" }
        }
        return inspect()
    }

    private fun List<String>.value(prefix: String): String? =
        firstOrNull { it.startsWith("$prefix=") }?.substringAfter('=')?.trim()?.takeIf(String::isNotBlank)

    private companion object {
        const val FACTORY_VERSION_CEILING = "3.0.5.14"
        const val FACTORY_QUERY = """
            dumpsys package com.android.thememanager 2>/dev/null | awk '
              function factory_path(value) {
                return value ~ /\/(system|product|system_ext|vendor)\//
              }
              /codePath=/ {
                path = ${'$'}0
                sub(/^.*codePath=/, "", path)
                factory = factory_path(path)
              }
              /versionCode=/ {
                code = ${'$'}0
                sub(/^.*versionCode=/, "", code)
                sub(/[[:space:]].*${'$'}/, "", code)
              }
              /versionName=/ {
                name = ${'$'}0
                sub(/^.*versionName=/, "", name)
                sub(/[[:space:]].*${'$'}/, "", name)
                if (factory && name != "") {
                  print "FACTORY_VERSION=" name
                  print "FACTORY_CODE=" code
                  exit
                }
              }
            '
        """
    }
}

internal fun factoryThemeManagerRollbackAvailable(activeVersion: String?, factoryVersion: String?): Boolean =
    FactoryThemeManagerRestorer.State(activeVersion, factoryVersion, null).eligible
