package com.nettarion.hyperborea.core.model

data class ClientInfo(
    val id: String,
    val protocol: String,
    val connectedAt: Long,
)
