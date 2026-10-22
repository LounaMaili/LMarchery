package com.lmarchery.core.scoring.archive.v3

import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.configured.*
import kotlin.test.*

class ArchiveV3ImportPlanTest {
    private val f=V3TestData
    private fun empty()=f.accepted(ArchiveV3Snapshot(3,1,emptyList(),emptyList(),emptyList(),emptyList()))
    @Test fun `empty archive is explicitly empty without requesting deletion`() {
        val plan=planArchiveImportV3(empty(),f.accepted());assertTrue(plan.isEmptyArchive);assertTrue(plan.canImport);assertFalse(plan.hasAdditions)
    }
    @Test fun `all six identity categories are classified including empty ends and unused profiles`() {
        val archive=f.accepted();val plan=planArchiveImportV3(archive,empty())
        assertEquals(1,plan.sessions.newUuids.size);assertEquals(4,plan.profiles.newUuids.size)
        assertEquals(6,plan.ends.newUuids.size);assertEquals(6,plan.impacts.newUuids.size)
        assertEquals(1,plan.bows.newUuids.size);assertEquals(1,plan.arrowSets.newUuids.size);assertTrue(plan.canImport)
    }
    @Test fun `exact complete content is identical independent of root discovery order`() {
        val archive=f.accepted();val snapshot=archive.toSnapshot()
        val reverse=f.accepted(snapshot.copy(profiles=snapshot.profiles!!.reversed()))
        val plan=planArchiveImportV3(reverse,archive);assertFalse(plan.hasAdditions);assertTrue(plan.canImport)
        assertEquals(archive.profiles.map { it.definition!!.uuid }.sorted(),plan.profiles.identicalUuids)
    }
    @Test fun `material only and explicitly empty catalogs are distinct legitimate inputs`() {
        val source=f.snapshot();val material=f.accepted(source.copy(profiles=emptyList(),sessions=emptyList()))
        val p=planArchiveImportV3(material,empty());assertTrue(p.canImport);assertEquals(listOf(f.bow().uuid),p.bows.newUuids)
        assertTrue(p.sessions.newUuids.isEmpty());assertTrue(planArchiveImportV3(empty(),material).isEmptyArchive)
    }
    @Test fun `different metadata is conflicting even with equal updatedAt`() {
        val s=f.mixed();val changed=s.withMetadata(s.metadata.copy(title="Other"),s.updatedAt)
        val source=f.accepted(snapshotArchiveV3(f.profiles(),listOf(changed),listOf(f.bow()),listOf(f.arrows())))
        val plan=planArchiveImportV3(source,f.accepted());assertFalse(plan.canImport);assertEquals(listOf(s.uuid),plan.sessions.conflictingUuids)
    }
    @Test fun `existing manual decisions and traces cannot be replaced by newer revision import`() {
        val s=f.mixed();val changed=s.withMetadata(s.metadata.copy(notes="Later"),s.updatedAt.plusSeconds(100))
        val plan=planArchiveImportV3(f.accepted(snapshotArchiveV3(f.profiles(),listOf(changed),listOf(f.bow()),listOf(f.arrows()))),f.accepted())
        assertFalse(plan.canImport);assertTrue(plan.conflicts.any { it.uuid==s.uuid })
    }
    @Test fun `profile edition conflict blocks dependent new session`() {
        val snapshot=f.small();val p=snapshot.profiles!!.single()
        val known=f.accepted(ArchiveV3Snapshot(3,1,listOf(p.copy(regulatoryEdition="other")),emptyList(),emptyList(),emptyList()))
        val plan=planArchiveImportV3(f.accepted(snapshot),known)
        assertFalse(plan.canImport);assertEquals(listOf(f.id(1)),plan.sessions.conflictingUuids)
        assertTrue(plan.conflicts.any { it.code=="DEPENDENCY_CONFLICT" })
    }
    @Test fun `profile content is not compared by radius or name only`() {
        val snapshot=f.small();val p=snapshot.profiles!!.single()
        val known=f.accepted(ArchiveV3Snapshot(3,1,listOf(p.copy(definition=p.definition!!.copy(name="Different"))),emptyList(),emptyList(),emptyList()))
        assertFalse(planArchiveImportV3(f.accepted(snapshot),known).canImport)
    }
    @Test fun `cross type collision bow versus impact is explicit`() {
        val source=f.accepted();val bow=com.lmarchery.core.model.equipment.Bow(f.id(100),"Collision fixture",createdAt=f.now,updatedAt=f.now)
        val known=f.accepted(ArchiveV3Snapshot(3,1,emptyList(),emptyList(),listOf(snapshotArchiveV2(emptyList(),emptyList(),listOf(bow),emptyList()).bows!!.single()),emptyList()))
        val plan=planArchiveImportV3(source,known);assertFalse(plan.canImport)
        assertTrue(plan.impacts.conflictingUuids.contains(f.id(100)));assertEquals(listOf(f.id(1)),plan.sessions.conflictingUuids)
    }
    @Test fun `cross type arrow set versus profile is explicit`() {
        val source=f.accepted(f.small());val arrow=com.lmarchery.core.model.equipment.ArrowSet(source.profiles.first().definition!!.uuid,"Collision fixture",createdAt=f.now,updatedAt=f.now)
        val shared=snapshotArchiveV2(emptyList(),emptyList(),emptyList(),listOf(arrow))
        val known=f.accepted(ArchiveV3Snapshot(3,1,emptyList(),emptyList(),emptyList(),shared.arrowSets))
        assertFalse(planArchiveImportV3(source,known).canImport)
    }
    @Test fun `new owner cannot adopt a known end even when the End value is equal`() {
        val snapshot=f.small();val root=snapshot.sessions!!.single();val newId=f.id(999)
        val received=f.accepted(snapshot.copy(sessions=listOf(root.copy(uuid=newId,ends=root.ends!!.map { it.copy(sessionUuid=newId) }))))
        val plan=planArchiveImportV3(received,f.accepted(snapshot));assertFalse(plan.canImport)
        assertTrue(plan.ends.conflictingUuids.contains(f.id(10)))
    }
    @Test fun `end capture origin remains significant even when configuration is equal`() {
        val snapshot=f.small();val root=snapshot.sessions!!.single();val child=root.ends!!.single()
        val altered=f.accepted(snapshot.copy(sessions=listOf(root.copy(ends=listOf(child.copy(
            origin=EndConfigurationOriginSnapshot(EndConfigurationOriginKind.EXPLICIT,null)))))))
        val plan=planArchiveImportV3(altered,f.accepted(snapshot));assertFalse(plan.canImport)
        assertEquals(listOf(child.end!!.uuid),plan.ends.conflictingUuids)
    }
    @Test fun `opaque unresolved equipment references do not claim entity identities`() {
        val source=f.accepted(f.snapshot().copy(bows=emptyList(),arrowSets=emptyList()))
        val plan=planArchiveImportV3(source,empty());assertTrue(plan.canImport);assertTrue(plan.bows.newUuids.isEmpty());assertTrue(plan.arrowSets.newUuids.isEmpty())
    }
    @Test fun `one conflicting entity blocks entire archive despite independent additions`() {
        val source=f.accepted();val altered=f.bow().copy(name="Conflicting")
        val shared=snapshotArchiveV2(emptyList(),emptyList(),listOf(altered),emptyList())
        val known=f.accepted(ArchiveV3Snapshot(3,1,emptyList(),emptyList(),shared.bows,emptyList()))
        val plan=planArchiveImportV3(source,known);assertFalse(plan.canImport);assertTrue(plan.hasAdditions)
    }
    @Test fun `plan collections are immutable and deterministic`() {
        val source=f.accepted();val a=planArchiveImportV3(source,empty());val b=planArchiveImportV3(source,empty())
        assertEquals(a,b);assertEquals(a.hashCode(),b.hashCode())
        assertFailsWith<UnsupportedOperationException> { (a.ends.newUuids as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (a.conflicts as MutableList).add(ImportConflict(null,"bad","bad","bad")) }
    }
}
