package com.nettarion.hyperborea.platform.update

import com.google.common.truth.Truth.assertThat
import org.json.JSONException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManifestParsingTest {

    @Test
    fun `parses full manifest with both sections`() {
        val json = """
            {
                "app": {
                    "versionCode": 2,
                    "versionName": "1.1",
                    "url": "https://example.com/app.apk",
                    "sha256": "abc123",
                    "releaseNotes": "Bug fixes."
                },
                "firmware": {
                    "version": "2.0.1",
                    "url": "https://example.com/firmware.zip",
                    "sha256": "def456",
                    "releaseNotes": "New kernel."
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

        assertThat(manifest.firmware).isNotNull()
        assertThat(manifest.firmware!!.version).isEqualTo("2.0.1")
        assertThat(manifest.firmware!!.url).isEqualTo("https://example.com/firmware.zip")
        assertThat(manifest.firmware!!.sha256).isEqualTo("def456")
        assertThat(manifest.firmware!!.releaseNotes).isEqualTo("New kernel.")
    }

    @Test
    fun `parses manifest with only app section`() {
        val json = """
            {
                "app": {
                    "versionCode": 3,
                    "versionName": "2.0",
                    "url": "https://example.com/app.apk",
                    "sha256": "abc123"
                }
            }
        """.trimIndent()

        val manifest = UpdateManifest.parse(json)

        assertThat(manifest.app).isNotNull()
        assertThat(manifest.app!!.versionCode).isEqualTo(3)
        assertThat(manifest.firmware).isNull()
    }

    @Test
    fun `parses manifest with only firmware section`() {
        val json = """
            {
                "firmware": {
                    "version": "3.0",
                    "url": "https://example.com/fw.zip",
                    "sha256": "aabbcc"
                }
            }
        """.trimIndent()

        val manifest = UpdateManifest.parse(json)

        assertThat(manifest.app).isNull()
        assertThat(manifest.firmware).isNotNull()
        assertThat(manifest.firmware!!.version).isEqualTo("3.0")
    }

    @Test
    fun `parses empty manifest`() {
        val manifest = UpdateManifest.parse("{}")

        assertThat(manifest.app).isNull()
        assertThat(manifest.firmware).isNull()
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
    fun `firmware section missing required version throws`() {
        val json = """
            {
                "firmware": {
                    "url": "https://example.com/fw.zip",
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
