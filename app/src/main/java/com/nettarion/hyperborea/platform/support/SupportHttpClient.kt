package com.nettarion.hyperborea.platform.support

interface SupportHttpClient {
    /** POST bundle to server with Bearer auth. Returns response body or null on HTTP error. */
    fun upload(authToken: String, jsonBody: String): String?
}
