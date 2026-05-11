package com.nettarion.hyperborea.core.system

/**
 * System-level actions Hyperborea can perform without privileged permissions.
 *
 * Earlier revisions exposed force-stop, component-disable, settings-write, and silent
 * USB-permission grant — all of which required `signature|privileged` permissions only
 * available when installed in `/system/priv-app/`. Those operations are now done once
 * by the deploy script (`adb shell pm disable-user --user 0 …` for iFit packages, and
 * the user-facing dialog for USB), so the runtime surface shrinks to the only system
 * call still required: prompting for USB-device permission.
 */
interface SystemController {
    /**
     * Fire the standard Android USB-permission dialog for the currently-attached FitPro
     * device. Returns true if the request was dispatched to the system; the user may
     * still cancel. With the manifest USB intent-filter in place, the user only sees
     * this dialog the first time the device is attached after install — subsequent
     * attaches dispatch silently.
     */
    suspend fun requestUsbPermission(): Boolean
}
