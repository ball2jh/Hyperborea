package com.nettarion.hyperborea.core.system

interface SystemController {
    suspend fun stopService(packageName: String, className: String): Boolean
    suspend fun forceStopPackage(packageName: String): Boolean
    suspend fun disablePackage(packageName: String): Boolean
    suspend fun enablePackage(packageName: String): Boolean
    suspend fun uninstallPackage(packageName: String): Boolean

    suspend fun disableComponent(packageName: String, className: String): Boolean
    suspend fun enableComponent(packageName: String, className: String): Boolean

    suspend fun grantUsbPermission(packageName: String): Boolean
    suspend fun revokeUsbPermissions(packageName: String): Boolean
}
