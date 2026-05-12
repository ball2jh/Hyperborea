package com.nettarion.hyperborea.core.orchestration

import com.nettarion.hyperborea.core.system.SystemController
import com.nettarion.hyperborea.core.system.SystemSnapshot

class Prerequisite(
    val id: String,
    val description: String,
    val isMet: (SystemSnapshot) -> Boolean,
    val fulfill: (suspend (SystemController) -> FulfillResult)? = null,
    /**
     * Optional override for how long [fulfill] may run before the orchestrator gives up.
     * Defaults to `Orchestrator.PREREQUISITE_TIMEOUT_MS` when null — raise it for
     * prerequisites that wait on a human (e.g. a system permission dialog).
     */
    val fulfillTimeoutMs: Long? = null,
) {
    override fun equals(other: Any?): Boolean = other is Prerequisite && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "Prerequisite(id=$id, description=$description)"
}
