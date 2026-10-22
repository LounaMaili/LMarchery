package com.lmarchery.core.database

import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.database.ConfiguredWriteData as c
import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.configured.*
import kotlin.test.*

internal object ArchiveV3RestoreData {
    fun mixed():ConfiguredSession {
        var s=c.oneEnd(configuration=c.classic.copy(arrowCountMode=ArrowCountMode.FIXED,arrowCountPerEnd=3))
            .withEnd(f.session(c.SEED-1).ends.single()).validateEnd(f.id(6001),f.now.plusSeconds(8))
            .createNextEnd(f.id(6020),f.now.plusSeconds(10),NextEndConfigurationChoice.Replace(c.compound))
        s=s.withImpact(f.id(6020),ArrowImpact(f.id(6150),1,NormalizedPoint(.075f,-0.0f),inputMode=InputMode.AUTOMATIC,
            createdAt=f.now.plusSeconds(11),updatedAt=f.now.plusSeconds(11),targetType=c.compound.targetType))
            .createNextEnd(f.id(6021),f.now.plusSeconds(12))
            .createNextEnd(f.id(6022),f.now.plusSeconds(13),NextEndConfigurationChoice.Replace(c.compound))
            .createNextEnd(f.id(6023),f.now.plusSeconds(14),NextEndConfigurationChoice.Replace(c.classic))
        return s.withImpact(f.id(6023),ArrowImpact(f.id(6151),1,NormalizedPoint(0f,0f),inputMode=InputMode.MANUAL,
            createdAt=f.now.plusSeconds(15),updatedAt=f.now.plusSeconds(15),targetType=c.classic.targetType))
    }
    fun source():DomainArchiveV3 {
        val legacy=d.configured(d.source())
        val s=mixed();val extra=c.snapshot(s).profiles!!
        return accepted(snapshotArchiveV3((legacy.profiles+extra).distinctBy { it.definition!!.uuid }.sortedBy { it.definition!!.uuid },
            (legacy.sessions+listOf(s,c.draft(10000))).sortedBy { it.uuid },
            listOf(f.bow(),f.bow(802)),listOf(f.arrows(),f.arrows(803))))
    }
    fun accepted(snapshot:ArchiveV3Snapshot)=assertIs<ArchiveV3Reconstruction.Accepted>(reconstructArchiveV3(snapshot)).archive
    fun empty()=accepted(ArchiveV3Snapshot(3,1,emptyList(),emptyList(),emptyList(),emptyList()))
    fun onlyMixed()=accepted(c.snapshot(mixed()))
    fun material()=accepted(snapshotArchiveV3(emptyList(),emptyList(),listOf(f.bow()),listOf(f.arrows())))
}
internal suspend fun RoomSessionStore.previewV3(source:DomainArchiveV3):ArchiveV3ImportPreview = when(val result=previewImportV3(source)) {
    is ArchiveV3PreviewResult.Ready->result.preview
    else->error("Preview rejected: $result")
}
internal suspend fun RoomSessionStore.restoreV3(source:DomainArchiveV3)=importArchiveV3(previewV3(source))
