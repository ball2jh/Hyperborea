package com.nettarion.hyperborea.core.adapter

import com.nettarion.hyperborea.core.orchestration.Prerequisite
import com.nettarion.hyperborea.core.system.SystemSnapshot

import kotlinx.coroutines.flow.StateFlow

interface Adapter {
    val prerequisites: List<Prerequisite>
    val state: StateFlow<AdapterState>
    fun canOperate(snapshot: SystemSnapshot): Boolean
}
