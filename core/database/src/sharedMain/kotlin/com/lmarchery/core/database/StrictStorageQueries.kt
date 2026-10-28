package com.lmarchery.core.database

import androidx.room.PooledConnection
import androidx.sqlite.*
import com.lmarchery.core.scoring.archive.v3.*

/** Strict row typing for both Room reader transactions and migration's existing SQLite connection.
 * Neither adapter acquires another connection, starts a transaction, normalizes data or writes SQL. */
internal class StrictStorageQueries private constructor(private val connection: ReadConnection) :
    StorageReadQueries, EquipmentReadQueries, ConfigurationReadQueries {
    constructor(connection: SQLiteConnection) : this(object : ReadConnection {
        override suspend fun <T> prepared(sql: String, read: (SQLiteStatement) -> T): T = connection.prepare(sql).use(read)
    })
    constructor(connection: PooledConnection) : this(object : ReadConnection {
        override suspend fun <T> prepared(sql: String, read: (SQLiteStatement) -> T): T = connection.usePrepared(sql, read)
    })
    private interface ReadConnection { suspend fun <T> prepared(sql: String, read: (SQLiteStatement) -> T): T }
    private var textLimit = ArchiveV3Limits().maxTextLength
    /** SQL preflight bounds materialization before reading large fields/graphs, without changing any row. */
    internal suspend fun checkBudgets(limits: ArchiveV3Limits, configured: Boolean) {
        textLimit = limits.maxTextLength
        val counts = mutableMapOf<String,Long>()
        for ((table,columns) in textColumns) {
            if (!configured && table.endsWith("_configurations")) continue
            val count = connection.prepared("SELECT count(*) FROM `$table`") { it.step(); it.getLong(0) }
            if (count > limits.maxTotalElements) storageBudget(table,"Physical collection exceeds the total archive budget.")
            counts[table] = count
            // SQLite's Unicode length stops at NUL; BLOB byte length bounds text even in that case.
            // Four bytes per UTF-16 unit is a conservative allocation bound; exact UTF-16 is checked in Row.
            for (column in columns) {
                val tooLarge = connection.prepared("SELECT 1 FROM `$table` WHERE length(CAST(`$column` AS BLOB)) > ${limits.maxTextLength.toLong() * 4} LIMIT 1") { it.step() }
                if (tooLarge) storageBudget("$table.$column","Stored text exceeds the archive text budget.")
            }
        }
        for ((table,path) in mapOf("target_profiles" to "profiles","sessions" to "sessions","bows" to "bows","arrow_sets" to "arrowSets"))
            if (counts.getValue(table) > limits.maxCollectionElements) storageBudget(path,"Root collection exceeds the archive budget.")
        val total = listOf("target_profiles","sessions","bows","arrow_sets","target_zones","ends","impacts","impact_repositions")
            .fold(0L) { n,table -> Math.addExact(n,counts.getValue(table)) }
        if (total > limits.maxTotalElements) storageBudget("archive","Cumulative archive element budget exceeded.")
    }

    private suspend fun <T> rows(sql: String, args: List<String> = emptyList(), read: (Row) -> T): List<T> =
        connection.prepared(sql) { statement ->
            args.forEachIndexed { i, value -> statement.bindText(i + 1, value) }
            buildList { while (statement.step()) add(read(Row(statement,textLimit))) }
        }
    override suspend fun identities(uuids: List<String>): List<IdentityRow> {
        if (uuids.isEmpty()) return emptyList()
        return rows("SELECT * FROM entity_identities WHERE uuid IN (" + uuids.joinToString(",") { "?" } + ")", uuids) { r -> IdentityRow(r.s("uuid"), r.s("kind")) }
    }
    override suspend fun allIdentities(): List<IdentityRow> =
        rows("SELECT * FROM entity_identities ORDER BY uuid", emptyList()) { r -> IdentityRow(r.s("uuid"),r.s("kind")) }
    override suspend fun allRowIdentities(): List<IdentityRow> =
        rows("SELECT uuid, 'PROFILE' AS kind FROM target_profiles UNION ALL SELECT uuid, 'SESSION' AS kind FROM sessions UNION ALL SELECT uuid, 'END' AS kind FROM ends UNION ALL SELECT uuid, 'IMPACT' AS kind FROM impacts UNION ALL SELECT uuid, 'BOW' AS kind FROM bows UNION ALL SELECT uuid, 'ARROW_SET' AS kind FROM arrow_sets", emptyList()) { r -> IdentityRow(r.s("uuid"),r.s("kind")) }
    override suspend fun orphanChildPaths(): List<String> =
        rows("SELECT 'target_zones[' || profileUuid || '][' || position || ']' FROM target_zones WHERE NOT EXISTS (SELECT 1 FROM target_profiles WHERE uuid = profileUuid) UNION ALL SELECT 'score_decisions[' || impactUuid || '][' || revision || ']' FROM score_decisions WHERE NOT EXISTS (SELECT 1 FROM impacts WHERE uuid = impactUuid) UNION ALL SELECT 'impact_repositions[' || impactUuid || '][' || sequence || ']' FROM impact_repositions WHERE NOT EXISTS (SELECT 1 FROM impacts WHERE uuid = impactUuid) ORDER BY 1", emptyList()) { r -> r.firstString() }
    override suspend fun profileUuids(): List<String> =
        rows("SELECT uuid FROM target_profiles ORDER BY uuid", emptyList()) { r -> r.firstString() }
    override suspend fun profile(uuid: String): ProfileRow? =
        rows("SELECT * FROM target_profiles WHERE uuid = ?", listOf(uuid)) { r -> r.profile() }.singleOrNull()
    override suspend fun zones(uuid: String): List<ZoneRow> =
        rows("SELECT * FROM target_zones WHERE profileUuid = ? ORDER BY position", listOf(uuid)) { r -> r.zone() }
    override suspend fun sessionUuids(): List<String> =
        rows("SELECT uuid FROM sessions UNION SELECT uuid FROM entity_identities WHERE kind = 'SESSION' ORDER BY uuid", emptyList()) { r -> r.firstString() }
    override suspend fun session(uuid: String): SessionRow? =
        rows("SELECT * FROM sessions WHERE uuid = ?", listOf(uuid)) { r -> r.session() }.singleOrNull()
    override suspend fun ends(uuid: String): List<EndRow> =
        rows("SELECT * FROM ends WHERE sessionUuid = ? ORDER BY position", listOf(uuid)) { r -> r.end() }
    override suspend fun impacts(uuid: String): List<ImpactRow> =
        rows("SELECT * FROM impacts WHERE endUuid = ? ORDER BY position", listOf(uuid)) { r -> r.impact() }
    override suspend fun decisions(uuid: String): List<DecisionRow> =
        rows("SELECT * FROM score_decisions WHERE impactUuid = ? ORDER BY revision", listOf(uuid)) { r -> r.decision() }
    override suspend fun repositions(uuid: String): List<RepositionRow> =
        rows("SELECT * FROM impact_repositions WHERE impactUuid = ? ORDER BY sequence", listOf(uuid)) { r -> r.reposition() }
    override suspend fun bowUuids(): List<String> =
        rows("SELECT uuid FROM bows UNION SELECT uuid FROM entity_identities WHERE kind = 'BOW' ORDER BY uuid", emptyList()) { r -> r.firstString() }
    override suspend fun arrowSetUuids(): List<String> =
        rows("SELECT uuid FROM arrow_sets UNION SELECT uuid FROM entity_identities WHERE kind = 'ARROW_SET' ORDER BY uuid", emptyList()) { r -> r.firstString() }
    override suspend fun bow(uuid: String): BowRow? =
        rows("SELECT * FROM bows WHERE uuid = ?", listOf(uuid)) { r -> r.bow() }.singleOrNull()
    override suspend fun arrowSet(uuid: String): ArrowSetRow? =
        rows("SELECT * FROM arrow_sets WHERE uuid = ?", listOf(uuid)) { r -> r.arrowSet() }.singleOrNull()
    override suspend fun sessionConfiguration(uuid: String): SessionConfigurationRow? =
        rows("SELECT * FROM session_configurations WHERE sessionUuid = ?", listOf(uuid)) { r -> SessionConfigurationRow(r.s("sessionUuid"),r.s("representation"),r.s("origin"),r.configuration()) }.singleOrNull()
    override suspend fun endConfiguration(uuid: String): EndConfigurationRow? =
        rows("SELECT * FROM end_configurations WHERE endUuid = ?", listOf(uuid)) { r -> EndConfigurationRow(r.s("endUuid"),r.s("sessionUuid"),r.s("origin"),r.ns("previousEndUuid"),r.configuration()) }.singleOrNull()
    override suspend fun orphanConfigurationPaths(): List<String> =
        rows("SELECT 'session_configurations[' || sessionUuid || ']' FROM session_configurations WHERE NOT EXISTS (SELECT 1 FROM sessions WHERE uuid = sessionUuid) UNION ALL SELECT 'end_configurations[' || endUuid || ']' FROM end_configurations WHERE NOT EXISTS (SELECT 1 FROM ends WHERE uuid = endUuid) OR NOT EXISTS (SELECT 1 FROM session_configurations s WHERE s.sessionUuid = end_configurations.sessionUuid) OR (previousEndUuid IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ends WHERE uuid = previousEndUuid)) ORDER BY 1", emptyList()) { r -> r.firstString() }
    override suspend fun nonLegacyConfigurationUuids(): List<String> =
        rows("SELECT sessionUuid FROM session_configurations WHERE representation != 'LEGACY_HOMOGENEOUS' ORDER BY sessionUuid", emptyList()) { r -> r.firstString() }
}

private class Row(private val statement: SQLiteStatement, private val textLimit: Int) {
    private val columns = (0 until statement.getColumnCount()).associateBy { statement.getColumnName(it) }
    private fun path(name: String): String {
        val owner = listOf("uuid", "endUuid", "sessionUuid", "profileUuid", "impactUuid").firstNotNullOfOrNull { key ->
            columns[key]?.takeIf { statement.getColumnType(it) == SQLITE_DATA_TEXT }?.let { statement.getText(it).takeIf { value -> value.length <= 64 } ?: "oversized-owner" }
        }
        return if (owner == null) "storage.$name" else "storage[$owner].$name"
    }
    private fun index(name: String) = requireNotNull(columns[name]) { "Missing SQL column $name." }
    fun firstString(): String = statement.getText(0)
    fun s(name: String): String {
        val i = index(name)
        dataCheck(statement.getColumnType(i) == SQLITE_DATA_TEXT, path(name), "Expected a stored TEXT value without coercion.")
        return statement.getText(i).also { if (it.length > textLimit) storageBudget(path(name),"Stored text exceeds the UTF-16 budget.") }
    }
    fun ns(name: String): String? = if (statement.isNull(index(name))) null else s(name)
    private fun long(name: String): Long {
        val i = index(name)
        dataCheck(statement.getColumnType(i) == SQLITE_DATA_INTEGER, path(name), "Expected a stored INTEGER without truncation or coercion.")
        return statement.getLong(i)
    }
    fun i(name: String): Int {
        val n = long(name)
        dataCheck(n in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(), path(name), "Stored integer is outside Int range.")
        return n.toInt()
    }
    fun ni(name: String): Int? = if (statement.isNull(index(name))) null else i(name)
    private fun nl(name: String): Long? = if (statement.isNull(index(name))) null else long(name)
    private fun time(prefix: String) = SqlInstant(nl("${prefix}_epochSecond"), ni("${prefix}_nano"))
    private fun optionalTime(prefix: String): SqlInstant? = time(prefix).takeUnless { it.epochSecond == null && it.nano == null }
    fun profile() = ProfileRow(s("uuid"),s("name"),s("discipline"),i("diameterCm"),i("maxScore"),i("minScore"),
        i("defaultArrowCount"),i("defaultDistanceM"),i("isBuiltIn"),i("xRingEnabled"),s("xRingLabel"),i("innerTenRadiusBits"),ns("regulatoryEdition"),i("zoneCount"))
    fun zone() = ZoneRow(s("profileUuid"),i("position"),i("score"),i("innerRadiusBits"),i("outerRadiusBits"),s("colorName"),ns("colorHex"))
    fun session() = SessionRow(s("uuid"),s("profileUuid"),i("archiveSchemaVersion"),i("scoringContractVersion"),
        s("localDateTime"),s("zoneId"),s("offset"),s("status"),optionalTime("started"),optionalTime("ended"),
        ni("plannedEndCount"),s("title"),i("distanceM"),s("arrowCountMode"),ni("arrowCountPerEnd"),i("isIndoor"),s("scoringMode"),
        ns("bowUuid"),ns("arrowSetUuid"),ns("locationName"),ns("notes"),time("created"),time("updated"),i("endCount"))
    fun end() = EndRow(s("uuid"),s("sessionUuid"),i("position"),i("index"),s("status"),ns("notes"),time("created"),time("updated"),optionalTime("validated"),i("impactCount"))
    fun impact() = ImpactRow(s("uuid"),s("endUuid"),s("profileUuid"),i("position"),i("index"),i("xBits"),i("yBits"),s("inputMode"),ns("note"),time("created"),time("updated"),i("repositionCount"))
    fun decision() = DecisionRow(s("impactUuid"),i("revision"),s("kind"),i("selectedScore"),i("selectedIsX"),optionalTime("decided"),
        ns("boundaryType"),ni("boundaryRadiusBits"),ns("reason"),SqlScoreAssertions(i("assertions_distanceBits"),i("assertions_calculated"),i("assertions_final"),
            i("assertions_geometricIsX"),i("assertions_isX"),i("assertions_isMiss"),i("assertions_isCordDecisionManual")))
    fun reposition() = RepositionRow(s("impactUuid"),i("sequence"),time("impactCreated"),s("inputMode"),i("previousXBits"),i("previousYBits"),
        time("previousUpdated"),i("newXBits"),i("newYBits"),time("changed"),ns("reason"))
    fun bow() = BowRow(s("uuid"),s("name"),ns("type"),ni("drawWeightLbsBits"),ns("brand"),ns("model"),ns("notes"),time("created"),time("updated"))
    fun arrowSet() = ArrowSetRow(s("uuid"),s("name"),ns("spine"),ns("length"),ns("pointWeight"),ns("nock"),ns("vanes"),ns("material"),ns("notes"),time("created"),time("updated"))
    fun configuration() = StoredEndConfiguration(s("profileUuid"),ns("regulatoryEdition"),i("distanceM"),s("arrowCountMode"),ni("arrowCountPerEnd"),i("isIndoor"),s("scoringMode"))
}

private fun storageBudget(path: String, detail: String): Nothing = throw CorruptV3Storage(
    ArchiveV3StorageDiagnostic(ArchiveV3StorageIssueKind.DOMAIN,path,detail,ArchiveV3Issue(ArchiveV3IssueCode.LIMIT,path,detail)))

/** Schema 3 TEXT columns; names are compile-time storage constants, never caller-supplied SQL. */
private val textColumns = mapOf(
    "entity_identities" to listOf("uuid","kind"),
    "target_profiles" to listOf("uuid","name","discipline","xRingLabel","regulatoryEdition"),
    "target_zones" to listOf("profileUuid","colorName","colorHex"),
    "sessions" to listOf("uuid","profileUuid","localDateTime","zoneId","offset","status","title","arrowCountMode","scoringMode","bowUuid","arrowSetUuid","locationName","notes"),
    "ends" to listOf("uuid","sessionUuid","status","notes"),
    "impacts" to listOf("uuid","endUuid","profileUuid","inputMode","note"),
    "score_decisions" to listOf("impactUuid","kind","boundaryType","reason"),
    "impact_repositions" to listOf("impactUuid","inputMode","reason"),
    "bows" to listOf("uuid","name","type","brand","model","notes"),
    "arrow_sets" to listOf("uuid","name","spine","length","pointWeight","nock","vanes","material","notes"),
    "session_configurations" to listOf("sessionUuid","representation","origin","profileUuid","regulatoryEdition","arrowCountMode","scoringMode"),
    "end_configurations" to listOf("endUuid","sessionUuid","origin","previousEndUuid","profileUuid","regulatoryEdition","arrowCountMode","scoringMode"),
)
