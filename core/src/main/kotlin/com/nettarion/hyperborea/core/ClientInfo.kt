package com.nettarion.hyperborea.core

data class ClientInfo(
    val id: String,
    val protocol: String,
    val connectedAt: Long,
)
