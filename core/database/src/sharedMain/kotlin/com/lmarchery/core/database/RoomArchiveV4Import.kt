package com.lmarchery.core.database

import androidx.room.*
import com.lmarchery.core.model.weather.WeatherObservation
import com.lmarchery.core.scoring.archive.v6.DomainArchiveV6
import com.lmarchery.core.model.place.ShootingPlaces
import com.lmarchery.core.scoring.archive.v5.DomainArchiveV5
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.archive.v4.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException

/** Archive append only: one reader preview and one writer confirmation, never a loop of public create calls. */
internal class RoomArchiveV4Import(private val database: SessionDatabase, private val ensureOpen: () -> Unit) {
    suspend fun preview(source: DomainArchiveV4, places: ShootingPlaces? = null, weather: List<WeatherObservation>? = null): ArchiveV4PreviewResult = try {
        val caller = currentCoroutineContext()
        caller.ensureActive(); ensureOpen()
        val archive = validateStoredV4(source.toSnapshot())
        database.useReaderConnection { connection -> connection.deferredTransaction {
            val queries = StrictStorageQueries(connection)
            val before = collectArchiveV4(queries, caller, ArchiveV3Limits())
            val fingerprint = fingerprintStorage(connection)
            val plan = planArchiveImportV4(archive, before)
            caller.ensureActive()
            val baseline = database.places().readPlaces(queries)
            if (places != null) DomainArchiveV5(archive, places)
            val additions = placeAdditions(places ?: ShootingPlaces.EMPTY, baseline, archive, before)
            val stored = database.allWeather(queries)
            DomainArchiveV6(DomainArchiveV5(before, baseline), stored)
            val incoming = weatherAdditions(weather.orEmpty(), stored, before, archive, additions.appendTo(baseline))
            ArchiveV4PreviewResult.Ready(ArchiveV4ImportPreview(archive, plan, before, places, baseline, additions.count,
                weather, incoming.size, fingerprint))
        } }
    } catch (error: CorruptV3Storage) { ArchiveV4PreviewResult.NeedsIntervention(error.diagnostic) }
      catch (error: CorruptStorage) { ArchiveV4PreviewResult.NeedsIntervention(error.v3Diagnostic()) }
      catch (error: CancellationException) { throw error }
      catch (error: Exception) { ArchiveV4PreviewResult.TechnicalFailure(error) }

    suspend fun restore(confirmed: ArchiveV4ImportPreview): ArchiveV4ImportResult {
        var writesAttempted = false
        fun outcome() = if (writesAttempted) ArchiveV4CommitOutcome.UNCONFIRMED else ArchiveV4CommitOutcome.NOT_ATTEMPTED
        try {
            val caller = currentCoroutineContext()
            caller.ensureActive(); ensureOpen()
            val source = validateStoredV4(confirmed.archive.toSnapshot())
            return database.useWriterConnection { connection -> connection.immediateTransaction {
                caller.ensureActive(); ensureOpen()
                val queries = StrictStorageQueries(connection)
                val before = collectArchiveV4(queries, caller, ArchiveV3Limits())
                val fingerprint = fingerprintStorage(connection)
                val plan = planArchiveImportV4(source, before)
                val oldPlaces = database.places().readPlaces(queries)
                if (oldPlaces != confirmed.baselinePlaces || before != confirmed.baseline || fingerprint != confirmed.baselineFingerprint || plan != confirmed.plan || !plan.canImport)
                    return@immediateTransaction ArchiveV4ImportResult.Conflict(confirmed, StorageDiagnostic("importV4.preview",
                        "Exact local content, SQL representation or plan changed, or conflicts exist; explicitly preview again."))
                val places = placeAdditions(confirmed.incomingPlaces ?: ShootingPlaces.EMPTY, oldPlaces, source, before)
                val knownWeather = database.allWeather(queries)
                val weather = weatherAdditions(confirmed.weather.orEmpty(), knownWeather, before, source, places.appendTo(oldPlaces))
                if (!plan.hasAdditions && places.count == 0 && weather.isEmpty()) {
                    caller.ensureActive()
                    return@immediateTransaction ArchiveV4ImportResult.AlreadyPresent(confirmed, plan.identicalCounts(), plan.isEmptyArchive && (confirmed.incomingPlaces ?: ShootingPlaces.EMPTY) == ShootingPlaces.EMPTY)
                }
                val newProfiles = plan.profiles.newUuids.toSet()
                val newSessions = plan.sessions.newUuids.toSet()
                val newBows = plan.bows.newUuids.toSet()
                val newArrows = plan.arrowSets.newUuids.toSet()
                val profiles = source.profiles.filter { it.definition!!.uuid in newProfiles }
                val sessions = source.sessions.filter { it.uuid in newSessions }
                val bows = source.bows.filter { it.uuid in newBows }
                val arrows = source.arrowSets.filter { it.uuid in newArrows }
                // Validate the union and its cumulative budgets BEFORE the first INSERT.
                val expected = validateStoredV4(snapshotArchiveV4(
                    (before.profiles + profiles).sortedBy { it.definition!!.uuid }, (before.sessions + sessions).sortedBy { it.uuid },
                    (before.bows + bows).sortedBy { it.uuid }, (before.arrowSets + arrows).sortedBy { it.uuid }))
                DomainArchiveV6(DomainArchiveV5(expected, places.appendTo(oldPlaces)), knownWeather + weather)
                val weatherIds = weather.map { it.uuid }.toSet()
                val knownIds = before.identitiesV3()
                val newIds = source.identitiesV3().values.filter { it.uuid !in knownIds }
                val newEnds = sessions.flatMap { it.ends }.map { it.uuid }.toSet()
                val newImpacts = sessions.flatMap { it.ends.flatMap { e -> e.end.impacts.map { it.uuid } } }.toSet()
                val exclusions = mapOf(
                    "weather_observations" to ("uuid" to weatherIds), "weather_ends" to ("observationUuid" to weatherIds),
                    "entity_identities" to ("uuid" to (newIds.map { it.uuid } + places.sites.map { it.uuid } + places.lines.map { it.uuid } + weatherIds).toSet()),
                    "shooting_sites" to ("uuid" to places.sites.map { it.uuid }.toSet()),
                    "shooting_lines" to ("uuid" to places.lines.map { it.uuid }.toSet()),
                    "session_places" to ("sessionUuid" to places.snapshots.map { it.sessionUuid }.toSet()),
                    "target_profiles" to ("uuid" to newProfiles), "target_zones" to ("profileUuid" to newProfiles),
                    "sessions" to ("uuid" to newSessions), "session_configurations" to ("sessionUuid" to newSessions),
                    "ends" to ("sessionUuid" to newSessions), "end_configurations" to ("endUuid" to newEnds),
                    "impacts" to ("endUuid" to newEnds), "score_decisions" to ("impactUuid" to newImpacts),
                    "impact_repositions" to ("impactUuid" to newImpacts),
                    "impact_cancellations" to ("impactUuid" to newImpacts),
                    "bows" to ("uuid" to newBows), "arrow_sets" to ("uuid" to newArrows),
                )
                caller.ensureActive()
                writesAttempted = true
                val dao = database.records()
                newIds.forEach { caller.ensureActive(); dao.insertIdentity(it) }
                profiles.forEach { caller.ensureActive(); dao.writeProfile(it) }
                source.toSnapshot().sessions!!.filter { it.uuid in newSessions }.forEach { snapshot ->
                    caller.ensureActive()
                    // Every new V3 root is CONFIGURED, even when legitimately LEGACY_DERIVED.
                    // Identical local legacy roots are skipped and retain their physical representation.
                    dao.insertConfiguredRows(snapshot.configuredRows(), caller)
                }
                sessions.forEach { caller.ensureActive(); dao.writeCancellationDelta(null, it) }
                bows.forEach { caller.ensureActive(); database.equipment().insertBow(it.row()) }
                arrows.forEach { caller.ensureActive(); database.equipment().insertArrowSet(it.row()) }
                places.insert(database)
                for (observation in weather) database.insertWeather(observation)
                dataCheck(database.allWeather(queries) == (knownWeather + weather).sortedBy { it.uuid },
                    "weather", "Relecture des observations divergente.")
                dataCheck(database.places().readPlaces(queries) == places.appendTo(oldPlaces), "import.places", "Catalogue ou instantanés divergents.")
                // Never return a normal failure from this body after DML: THROW to roll back ALL additions.
                val after = collectArchiveV4(queries, caller, ArchiveV3Limits())
                dataCheck(after == expected, "importV4.verification", "Complete stored archive differs from source additions plus unchanged local data.")
                val preserved = fingerprintStorage(connection, exclusions)
                val changedTable = fingerprint.keys.firstOrNull { fingerprint[it] != preserved[it] }
                dataCheck(changedTable == null, "importV4.unchanged.$changedTable", "Existing raw SQL values changed; abort the entire restoration.")
                connection.usePrepared("PRAGMA foreign_key_check") { dataCheck(!it.step(), "importV4.foreignKeys", "Foreign key violation; abort restoration.") }
                caller.ensureActive()
                ArchiveV4ImportResult.Imported(confirmed, plan.addedCounts(), plan.identicalCounts())
            } }
        } catch (error: CorruptV3Storage) { return ArchiveV4ImportResult.NeedsIntervention(confirmed, error.diagnostic, outcome()) }
          catch (error: CorruptStorage) { return ArchiveV4ImportResult.NeedsIntervention(confirmed, error.v3Diagnostic(), outcome()) }
          catch (error: CancellationException) { throw ArchiveV4ImportCancelled(confirmed, outcome(), error) }
          catch (error: Exception) { return if (writesAttempted) ArchiveV4ImportResult.CommitUnconfirmed(confirmed, error)
              else ArchiveV4ImportResult.TechnicalFailure(confirmed, error) }
    }
}

private fun weatherAdditions(incoming: List<WeatherObservation>, stored: List<WeatherObservation>,
    known: DomainArchiveV4, source: DomainArchiveV4, places: ShootingPlaces): List<WeatherObservation> {
    val byId = stored.associateBy { it.uuid }
    val occupied = known.identitiesV3().keys + source.identitiesV3().keys + places.sites.map { it.uuid } + places.lines.map { it.uuid }
    dataCheck((byId.keys + incoming.map { it.uuid }).none { it in occupied }, "weather.uuid", "UUID déjà occupé.")
    for (o in incoming) dataCheck(byId[o.uuid]?.let { it == o } != false, "weather[${o.uuid}]", "Relevé connu divergent.")
    return incoming.filter { it.uuid !in byId }
}

private fun CorruptStorage.v3Diagnostic() = ArchiveV3StorageDiagnostic(ArchiveV3StorageIssueKind.STORAGE,
    diagnostic.path, diagnostic.detail, storageIssue = diagnostic)
private fun ArchiveV3ImportPlan.addedCounts() = ArchiveV4Counts(sessions.newUuids.size, profiles.newUuids.size,
    ends.newUuids.size, impacts.newUuids.size, bows.newUuids.size, arrowSets.newUuids.size)
private fun ArchiveV3ImportPlan.identicalCounts() = ArchiveV4Counts(sessions.identicalUuids.size, profiles.identicalUuids.size,
    ends.identicalUuids.size, impacts.identicalUuids.size, bows.identicalUuids.size, arrowSets.identicalUuids.size)
