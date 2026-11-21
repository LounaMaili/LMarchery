package com.lmarchery.core.export

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.lmarchery.core.scoring.archive.v6.DomainArchiveV6
import com.lmarchery.core.scoring.archive.v7.DomainArchiveV7

/** V8 extends the place orientations in the same complete aggregate used by the importer. */
class ArchiveV8JsonCodec {
    private val limits = JsonLimits()
    private val graph = PlaceGraphJson(assisted = true)

    fun encode(archive: DomainArchiveV7): ByteArray {
        val document = JsonObject().apply {
            addProperty("schemaVersion", 8)
            graph.write(this, archive.base.base)
            add("observations", JsonArray().apply { archive.base.observations.forEach { add(encodeObservation(it)) } })
            add("estimates", JsonArray().apply { archive.estimates.forEach { add(encodeEstimate(it)) } })
        }
        return writeStrict(document, limits).also { check(decode(it) == archive) }
    }

    fun decode(bytes: ByteArray): DomainArchiveV7 = checkedObject(parseStrict(bytes, limits), "$",
        "schemaVersion", "sporting", "sites", "lines", "sessionPlaces", "observations", "estimates") {
        require(int("schemaVersion") == 8)
        DomainArchiveV7(DomainArchiveV6(graph.read(this), list("observations", ::readObservation)), list("estimates", ::decodeEstimate))
    }
}
