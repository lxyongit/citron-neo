package org.citron.citron_emu.utils

import android.content.Context
import android.content.Intent
import org.citron.citron_emu.BuildConfig

object HomeNavigationIntents {
    val HOME_SETTINGS_ACTION: String = "${BuildConfig.APPLICATION_ID}.HOME_SETTINGS"

    fun createOpenHomeSettingsIntent(context: Context): Intent {
        return Intent(HOME_SETTINGS_ACTION).setPackage(context.packageName)
    }
}