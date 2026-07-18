package org.citron.citron_emu.utils

import android.content.Context
import android.content.Intent
import org.citron.citron_emu.BuildConfig

object GpuInstallIntents {
    val GPU_INSTALL_ACTION: String = "${BuildConfig.APPLICATION_ID}.GPU_INSTALL"
    const val EXTRA_DRIVER_PATH = "driver_path"

    fun createInstallGpuIntent(context: Context, driverPath: String): Intent {
        return Intent(GPU_INSTALL_ACTION)
            .setPackage(context.packageName)
            .putExtra(EXTRA_DRIVER_PATH, driverPath)
    }
}