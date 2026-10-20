package dev.glorioustr.mtzstudio

internal enum class RecoveryAction { NONE, PREPARE, INSTALL, RECOVER }

/** A legacy PREPARED journal without verified public copies is preparation, not install-ready. */
internal fun recoveryAction(phase: String?, canInstall: Boolean, needsRecovery: Boolean): RecoveryAction = when {
    needsRecovery -> RecoveryAction.RECOVER
    phase == "PREPARED" && canInstall -> RecoveryAction.INSTALL
    phase in setOf("IDLE", "FAILED", "PREPARED") -> RecoveryAction.PREPARE
    else -> RecoveryAction.NONE
}

/** Journal and thrown errors are not UI copy; only a controlled failure code may be summarized. */
internal fun controlledRecoveryReason(message: String?): String? = message?.let {
    Regex("(?:^|\\b)Reason: ([A-Z][A-Z0-9_]{0,99})\\b").find(it)?.groupValues?.get(1)
        ?: it.takeIf { value -> Regex("[A-Z][A-Z0-9_]{0,99}").matches(value) }
}
