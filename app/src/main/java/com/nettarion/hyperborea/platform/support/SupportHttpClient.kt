package com.nettarion.hyperborea.platform.support

interface SupportHttpClient {
    /** POST a diagnostics bundle to the configured server. Returns the response body, or null on error. */
    fun upload(jsonBody: String): String?
}
