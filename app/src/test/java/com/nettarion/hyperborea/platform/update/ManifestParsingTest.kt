package com.nettarion.hyperborea.platform.update

import com.google.common.truth.Truth.assertThat
import org.json.JSONException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManifestParsingTest {

    @Test
    fun `parses full manifest`() {
        val json = """
            {
                "app": {
                    "versionCode": 2,
                    "versionName": "1.1",
                    "url": "https://example.com/app.apk",
                    "sha256": "abc123",
                    "releaseNotes": "Bug fixes."
                }
            }
        """.trimIndent()

        val manifest = UpdateManifest.parse(json)

        assertThat(manifest.app).isNotNull()
        assertThat(manifest.app!!.versionCode).isEqualTo(2)
        assertThat(manifest.app!!.versionName).isEqualTo("1.1")
        assertThat(manifest.app!!.url).isEqualTo("https://example.com/app.apk")
        assertThat(manifest.app!!.sha256).isEqualTo("abc123")
        assertThat(manifest.app!!.releaseNotes).isEqualTo("Bug fixes.")
    }

    @Test
    fun `parses empty manifest`() {
        val manifest = UpdateManifest.parse("{}")

        assertThat(manifest.app).isNull()
    }

    @Test
    fun `missing releaseNotes defaults to empty string`() {
        val json = """
            {
                "app": {
                    "versionCode": 1,
                    "versionName": "1.0",
                    "url": "https://example.com/app.apk",
                    "sha256": "abc"
                }
            }
        """.trimIndent()

        val manifest = UpdateManifest.parse(json)

        assertThat(manifest.app!!.releaseNotes).isEmpty()
    }

    @Test(expected = JSONException::class)
    fun `app section missing required versionCode throws`() {
        val json = """
            {
                "app": {
                    "versionName": "1.0",
                    "url": "https://example.com/app.apk",
                    "sha256": "abc"
                }
            }
        """.trimIndent()

        UpdateManifest.parse(json)
    }

    @Test(expected = JSONException::class)
    fun `malformed JSON throws`() {
        UpdateManifest.parse("not json at all")
    }
}
