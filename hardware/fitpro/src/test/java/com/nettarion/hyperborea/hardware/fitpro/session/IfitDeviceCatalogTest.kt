package com.nettarion.hyperborea.hardware.fitpro.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.MessageDigest

/**
 * Integrity gate for the packaged catalog resource. The SHA below was computed from the original
 * in-code data table at the moment it was migrated to `ifit_device_catalog.tsv` — re-serializing
 * the parsed arrays must reproduce it bit-for-bit, proving the loader is a faithful inverse of
 * the format and the resource wasn't accidentally edited. Regenerate the constant deliberately
 * whenever the catalog data is updated on purpose.
 */
class IfitDeviceCatalogTest {

    @Test
    fun `parsed catalog matches the migrated data table`() {
        val c = IfitDeviceCatalog
        assertThat(c.names.size).isEqualTo(270)
        assertThat(c.iconPartNumbers.size).isEqualTo(409)
        assertThat(c.partNumbers.size).isEqualTo(428)

        val sb = StringBuilder()
        sb.append("# ifit_device_catalog v1 — sections: names, iconPartNumbers, devices\n")
        sb.append("[names]\n")
        for (n in c.names) sb.append(n).append('\n')
        sb.append("[iconPartNumbers]\n")
        for (p in c.iconPartNumbers) sb.append(p).append('\n')
        sb.append("[devices]\n")
        for (i in c.partNumbers.indices) {
            sb.append(c.partNumbers[i]).append('\t')
                .append(c.nameIndices[i].toInt()).append('\t')
                .append(c.iconPartNumberIndices[i].toInt()).append('\t')
                .append(c.equipmentTypes[i].toInt()).append('\t')
                .append(c.maxResistances[i].toInt()).append('\t')
                .append(c.minInclines[i].toInt()).append('\t')
                .append(c.maxInclines[i].toInt()).append('\t')
                .append(c.maxSpeedTenthsKph[i].toInt()).append('\t')
                .append(c.powerCurveIndices[i].toInt()).append('\n')
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertThat(sha).isEqualTo("0e98ca5bd3bc1be4bd6e09f31835863c005349f6a2b7461adae156df761fbbd5")
    }

    @Test
    fun `parallel-array cross-references are in bounds`() {
        val c = IfitDeviceCatalog
        for (i in c.partNumbers.indices) {
            assertThat(c.nameIndices[i].toInt()).isIn(0 until c.names.size)
            val iconIdx = c.iconPartNumberIndices[i].toInt()
            if (iconIdx != -1) assertThat(iconIdx).isIn(0 until c.iconPartNumbers.size)
        }
    }
}
