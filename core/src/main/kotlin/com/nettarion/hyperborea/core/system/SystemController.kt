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
     * Ensure Hyperborea has permission to talk to the currently-attached FitPro USB device.
     *
     * If permission is already granted, returns `true` immediately. Otherwise it fires the
     * standard Android USB-permission dialog and **suspends until the user responds**,
     * returning `true` if they grant it and `false` if they deny it (or if there is no
     * device attached / the request can't be dispatched). Callers are expected to bound
     * this with a timeout. With the manifest USB intent-filter in place the user only sees
     * this dialog the first time the device is attached after install — subsequent attaches
     * are granted silently and this returns `true` without prompting.
     */
    suspend fun requestUsbPermission(): Boolean
}
