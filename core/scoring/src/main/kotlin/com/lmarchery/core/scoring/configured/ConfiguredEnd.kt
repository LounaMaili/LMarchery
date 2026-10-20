package com.lmarchery.core.scoring.configured

import com.lmarchery.core.model.session.End
import com.lmarchery.core.model.session.EndConfiguration
import com.lmarchery.core.model.time.SessionDateTime

enum class InitialConfigurationOrigin { CREATION, LEGACY_DERIVED }

/** Capture provenance, without an invented choice date or confirmation event. */
sealed interface EndConfigurationOrigin {
    data object Initial : EndConfigurationOrigin
    data class Inherited(val previousEndUuid: String) : EndConfigurationOrigin
    data object Explicit : EndConfigurationOrigin
    data object LegacyDerived : EndConfigurationOrigin
}

/** Owned by ConfiguredSession. No public constructor or copy can replace its configuration. */
class ConfiguredEnd internal constructor(
    val sessionUuid: String,
    val end: End,
    val configuration: EndConfiguration,
    val origin: EndConfigurationOrigin,
) {
    val uuid: String get() = end.uuid
    private val content = listOf(sessionUuid, end, configuration, origin)
    override fun equals(other: Any?): Boolean = other is ConfiguredEnd && content == other.content
    override fun hashCode(): Int = content.hashCode()
}

/** Opaque equipment references are preserved, not resolved into catalog entries. */
data class ConfiguredSessionMetadata(
    val date: SessionDateTime,
    val title: String,
    val plannedEndCount: Int? = null,
    val bowUuid: String? = null,
    val arrowSetUuid: String? = null,
    val locationName: String? = null,
    val notes: String? = null,
) {
    init {
        require(title.isNotBlank()) { "Session title must not be blank." }
        require(plannedEndCount == null || plannedEndCount > 0) { "plannedEndCount must be positive or absent." }
        require(bowUuid == null || bowUuid.isNotBlank()) { "bowUuid must not be blank." }
        require(arrowSetUuid == null || arrowSetUuid.isNotBlank()) { "arrowSetUuid must not be blank." }
    }
}

enum class ConfiguredSessionIssue {
    INVALID_IDENTITY, IDENTITY_COLLISION, WRONG_OWNER, UNKNOWN_END, UNKNOWN_IMPACT,
    CONFIGURATION_CONFLICT, HISTORY_CONFLICT, TEMPORAL_CONFLICT, FORBIDDEN_TRANSITION,
    INDEX_OVERFLOW, MISSING_HISTORICAL_SOURCE,
}

class ConfiguredSessionException(val code: ConfiguredSessionIssue, val path: String, detail: String) :
    IllegalArgumentException("$code · $path: $detail")

internal fun requireConfigured(condition: Boolean, code: ConfiguredSessionIssue, path: String, detail: String) {
    if (!condition) throw ConfiguredSessionException(code, path, detail)
}
