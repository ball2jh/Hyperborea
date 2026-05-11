package com.nettarion.hyperborea.ecosystem.ifit

import com.nettarion.hyperborea.core.system.ComponentState
import com.nettarion.hyperborea.core.system.ComponentType
import com.nettarion.hyperborea.core.orchestration.EcosystemManager
import com.nettarion.hyperborea.core.orchestration.Prerequisite
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifies that the iFit apps competing for the FitPro USB device are not running.
 *
 * Earlier revisions actively pacified iFit at runtime via `forceStopPackage` and
 * `disableComponent` calls, which required `signature|privileged` permissions only
 * available when installed in `/system/priv-app/`. Now the deploy script disables
 * iFit's apps once via `adb shell pm disable-user --user 0 …`, so this manager just
 * checks the result. A disabled package can never be RUNNING, so the existing checks
 * pass naturally after a successful deploy. If iFit is somehow re-enabled and running,
 * the prerequisite fails and the user is prompted to re-run the deploy script.
 */
@Singleton
class IfitEcosystemManager @Inject constructor() : EcosystemManager {

    override val prerequisites = listOf(
        Prerequisite(
            id = "ifit-standalone-stopped",
            description = "iFit standalone must be stopped to release USB",
            isMet = { snapshot ->
                snapshot.components.none {
                    it.packageName == IFIT_STANDALONE_PACKAGE &&
                        (it.state == ComponentState.RUNNING ||
                            it.state == ComponentState.RUNNING_FOREGROUND)
                }
            },
        ),
        Prerequisite(
            id = "glassos-service-stopped",
            description = "GlassOS service must be stopped to release USB",
            isMet = { snapshot ->
                snapshot.components.none {
                    it.packageName == GLASSOS_SERVICE_PACKAGE &&
                        (it.state == ComponentState.RUNNING ||
                            it.state == ComponentState.RUNNING_FOREGROUND)
                }
            },
        ),
        Prerequisite(
            id = "eru-usb-receiver-disabled",
            description = "ERU USB receiver must be disabled to prevent USB cycling",
            isMet = { snapshot ->
                val receiver = snapshot.components.find {
                    it.packageName == ERU_PACKAGE &&
                        it.className == ERU_USB_RECEIVER &&
                        it.type == ComponentType.BROADCAST_RECEIVER
                }
                receiver == null || receiver.state == ComponentState.DISABLED
            },
        ),
    )

    private companion object {
        const val GLASSOS_SERVICE_PACKAGE = "com.ifit.glassos_service"
        const val IFIT_STANDALONE_PACKAGE = "com.ifit.standalone"
        const val ERU_PACKAGE = "com.ifit.eru"
        const val ERU_USB_RECEIVER = "com.ifit.eru.receivers.UsbDeviceAttachedReceiver"
    }
}
