package com.nettarion.hyperborea.core

import kotlinx.coroutines.flow.StateFlow

interface Adapter {
    val prerequisites: List<Prerequisite>
    val state: StateFlow<AdapterState>
    fun canOperate(snapshot: SystemSnapshot): Boolean
}
