package com.nettarion.hyperborea.hardware.fitpro.session

/**
 * Catalog of ICON Fitness part numbers, display names, capabilities, and power-curve table
 * indices. The data lives in the packaged `ifit_device_catalog.tsv` Java resource and is parsed
 * lazily on first access — it's pure data, and keeping it out of compiled source means a catalog
 * update is a data-file change, not a 1100-line code diff.
 *
 * Exposed as sorted parallel arrays (sorted by [partNumbers]) so [DeviceDatabase] can keep its
 * binary-search lookups. All arrays in the `devices` section share one index; [nameIndices] and
 * [iconPartNumberIndices] point into [names]/[iconPartNumbers] respectively (-1 = none).
 *
 * Resource format (`v1`): a `[names]` section (one display name per line), an
 * `[iconPartNumbers]` section (one part-number string per line), then a `[devices]` section of
 * tab-separated rows: partNumber, nameIdx, iconPnIdx, equipmentType, maxResistance, minIncline,
 * maxIncline, maxSpeedTenthsKph, powerCurveIdx.
 */
internal object IfitDeviceCatalog {

    val names: Array<String> get() = data.names
    val iconPartNumbers: Array<String> get() = data.iconPartNumbers
    val partNumbers: IntArray get() = data.partNumbers
    val nameIndices: ShortArray get() = data.nameIndices
    val iconPartNumberIndices: ShortArray get() = data.iconPartNumberIndices
    val equipmentTypes: ByteArray get() = data.equipmentTypes
    val maxResistances: ByteArray get() = data.maxResistances
    val minInclines: ByteArray get() = data.minInclines
    val maxInclines: ByteArray get() = data.maxInclines
    val maxSpeedTenthsKph: ShortArray get() = data.maxSpeedTenthsKph
    val powerCurveIndices: ByteArray get() = data.powerCurveIndices

    private class CatalogData(
        val names: Array<String>,
        val iconPartNumbers: Array<String>,
        val partNumbers: IntArray,
        val nameIndices: ShortArray,
        val iconPartNumberIndices: ShortArray,
        val equipmentTypes: ByteArray,
        val maxResistances: ByteArray,
        val minInclines: ByteArray,
        val maxInclines: ByteArray,
        val maxSpeedTenthsKph: ShortArray,
        val powerCurveIndices: ByteArray,
    )

    private val data: CatalogData by lazy { load() }

    private fun load(): CatalogData {
        val stream = IfitDeviceCatalog::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: error("Missing packaged catalog resource $RESOURCE_PATH")

        val names = ArrayList<String>(300)
        val iconPns = ArrayList<String>(450)
        val rows = ArrayList<IntArray>(450)

        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            var section = ""
            for (line in lines) {
                when {
                    line.startsWith("[") -> section = line
                    section.isEmpty() -> {} // header comments before the first section
                    line.isEmpty() -> {}
                    section == "[names]" -> names.add(line)
                    section == "[iconPartNumbers]" -> iconPns.add(line)
                    section == "[devices]" -> {
                        val cols = line.split('\t')
                        require(cols.size == DEVICE_COLUMNS) {
                            "Malformed catalog row (${cols.size} columns): $line"
                        }
                        rows.add(IntArray(DEVICE_COLUMNS) { cols[it].toInt() })
                    }
                    else -> error("Unknown catalog section $section")
                }
            }
        }

        val n = rows.size
        val catalog = CatalogData(
            names = names.toTypedArray(),
            iconPartNumbers = iconPns.toTypedArray(),
            partNumbers = IntArray(n) { rows[it][0] },
            nameIndices = ShortArray(n) { rows[it][1].toShort() },
            iconPartNumberIndices = ShortArray(n) { rows[it][2].toShort() },
            equipmentTypes = ByteArray(n) { rows[it][3].toByte() },
            maxResistances = ByteArray(n) { rows[it][4].toByte() },
            minInclines = ByteArray(n) { rows[it][5].toByte() },
            maxInclines = ByteArray(n) { rows[it][6].toByte() },
            maxSpeedTenthsKph = ShortArray(n) { rows[it][7].toShort() },
            powerCurveIndices = ByteArray(n) { rows[it][8].toByte() },
        )

        // Binary-search precondition — fail loudly at load, not with silent lookup misses.
        for (i in 1 until n) {
            require(catalog.partNumbers[i - 1] < catalog.partNumbers[i]) {
                "Catalog partNumbers not strictly sorted at index $i"
            }
        }
        return catalog
    }

    private const val RESOURCE_PATH = "/ifit_device_catalog.tsv"
    private const val DEVICE_COLUMNS = 9
}
