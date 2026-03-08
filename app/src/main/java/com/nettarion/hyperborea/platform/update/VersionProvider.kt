package com.nettarion.hyperborea.platform.update

fun interface VersionProvider {
    fun getVersionCode(): Int
}
