package com.lmarchery.core.export

import com.google.gson.JsonObject
import com.lmarchery.core.scoring.archive.v5.DomainArchiveV5

class ArchiveV5JsonCodec {
    private val limits = JsonLimits()
    private val graph = PlaceGraphJson(assisted = false)

    fun encode(archive: DomainArchiveV5): ByteArray {
        val document = JsonObject().apply { addProperty("schemaVersion", 5) }
        graph.write(document, archive)
        return writeStrict(document, limits).also { check(decode(it) == archive) }
    }

    fun decode(bytes: ByteArray): DomainArchiveV5 = checkedObject(parseStrict(bytes, limits), "$",
        "schemaVersion", "sporting", "sites", "lines", "sessionPlaces") {
        require(int("schemaVersion") == 5)
        graph.read(this)
    }
}
