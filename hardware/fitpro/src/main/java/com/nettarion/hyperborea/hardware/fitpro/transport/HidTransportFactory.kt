package com.nettarion.hyperborea.hardware.fitpro.transport

interface HidTransportFactory {
    fun create(vendorId: Int, productId: Int): HidTransportResult
}

data class HidTransportResult(
    val transport: HidTransport,
    val productId: Int,
)
