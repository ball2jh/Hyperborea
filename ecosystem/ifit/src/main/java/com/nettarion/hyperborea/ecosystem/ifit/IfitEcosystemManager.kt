package com.nettarion.hyperborea.ecosystem.ifit

import com.nettarion.hyperborea.core.ComponentState
import com.nettarion.hyperborea.core.EcosystemManager
import com.nettarion.hyperborea.core.FulfillResult
import com.nettarion.hyperborea.core.Prerequisite
import javax.inject.Inject
import javax.inject.Singleton

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
            fulfill = { controller ->
                if (controller.forceStopPackage(IFIT_STANDALONE_PACKAGE)) FulfillResult.Success
                else FulfillResult.Failed("Failed to force-stop $IFIT_STANDALONE_PACKAGE")
            },
        ),
    )

    private companion object {
        const val IFIT_STANDALONE_PACKAGE = "com.ifit.standalone"
    }
}
