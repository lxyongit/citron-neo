package org.citron.citron_emu.utils

import android.content.Context
import android.content.Intent
import org.citron.citron_emu.BuildConfig

object GamePropertiesIntents {
    val GAME_SETTINGS_ACTION: String = "${BuildConfig.APPLICATION_ID}.GAME_SETTINGS"
    const val EXTRA_GAME_PATH = "game_path"

    fun createOpenGameSettingsIntent(context: Context, gamePath: String): Intent {
        return Intent(GAME_SETTINGS_ACTION)
            .setPackage(context.packageName)
            .putExtra(EXTRA_GAME_PATH, gamePath)
    }
}