package com.nettarion.hyperborea.platform.update

import org.json.JSONObject

data class UpdateManifest(
    val app: AppUpdate?,
) {
    companion object {
        fun parse(json: String): UpdateManifest {
            val root = JSONObject(json)
            return UpdateManifest(
                app = root.optJSONObject("app")?.let { obj ->
                    AppUpdate(
                        versionCode = obj.getInt("versionCode"),
                        versionName = obj.getString("versionName"),
                        url = obj.getString("url"),
                        sha256 = obj.getString("sha256"),
                        releaseNotes = obj.optString("releaseNotes", ""),
                    )
                },
            )
        }
    }
}

data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String,
    val releaseNotes: String,
)
