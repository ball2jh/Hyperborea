package com.nettarion.hyperborea.core.orchestration

import com.nettarion.hyperborea.core.system.SystemController
import com.nettarion.hyperborea.core.system.SystemSnapshot

class Prerequisite(
    val id: String,
    val description: String,
    val isMet: (SystemSnapshot) -> Boolean,
    val fulfill: (suspend (SystemController) -> FulfillResult)? = null,
) {
    override fun equals(other: Any?): Boolean = other is Prerequisite && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "Prerequisite(id=$id, description=$description)"
}
