package com.lmarchery.core.database

/** Read-only port shared by Room and the synchronous, transaction-local migration validator.
 * No connection acquisition or writes are available through this port. */
internal interface StorageReadQueries {
    suspend fun identities(uuids: List<String>): List<IdentityRow>
    suspend fun allIdentities(): List<IdentityRow>
    suspend fun allRowIdentities(): List<IdentityRow>
    suspend fun orphanChildPaths(): List<String>
    suspend fun profileUuids(): List<String>
    suspend fun profile(uuid: String): ProfileRow?
    suspend fun zones(uuid: String): List<ZoneRow>
    suspend fun sessionUuids(): List<String>
    suspend fun session(uuid: String): SessionRow?
    suspend fun ends(uuid: String): List<EndRow>
    suspend fun impacts(uuid: String): List<ImpactRow>
    suspend fun decisions(uuid: String): List<DecisionRow>
    suspend fun repositions(uuid: String): List<RepositionRow>
}

internal interface EquipmentReadQueries {
    suspend fun bowUuids(): List<String>
    suspend fun arrowSetUuids(): List<String>
    suspend fun bow(uuid: String): BowRow?
    suspend fun arrowSet(uuid: String): ArrowSetRow?
}

internal interface ConfigurationReadQueries {
    suspend fun sessionConfiguration(uuid: String): SessionConfigurationRow?
    suspend fun endConfiguration(uuid: String): EndConfigurationRow?
    suspend fun orphanConfigurationPaths(): List<String>
    suspend fun nonLegacyConfigurationUuids(): List<String>
}
