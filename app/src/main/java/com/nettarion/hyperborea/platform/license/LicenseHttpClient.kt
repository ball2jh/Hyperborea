package com.nettarion.hyperborea.platform.license

interface LicenseHttpClient {
    /** GET /api/device/status with Bearer auth and replay-protection nonce.
     *  Returns response body or null on network/server error. */
    fun fetchStatus(authToken: String, nonce: String): String?
    /** POST /api/device/pair with JSON body. Returns response body or null on HTTP error. */
    fun requestPairing(deviceUuid: String): String?
    /** GET /api/device/pair/status?token=X. Returns response body or null on HTTP error. */
    fun pollPairingStatus(pairingToken: String): String?
    /** POST /api/device/unlink with Bearer auth. Returns true if server accepted (2xx). */
    fun unlink(authToken: String): Boolean
}
