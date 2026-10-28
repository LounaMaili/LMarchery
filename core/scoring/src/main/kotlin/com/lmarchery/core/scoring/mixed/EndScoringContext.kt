package com.lmarchery.core.scoring.mixed

import com.lmarchery.core.model.session.End
import com.lmarchery.core.model.session.EndConfiguration
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import java.util.Collections

enum class MixedScoringIssue {
    MISSING_CONFIGURATION, DUPLICATE_CONFIGURATION, UNKNOWN_END, WRONG_END,
    PROFILE_MISMATCH, PROFILE_IDENTITY_CONFLICT, UNKNOWN_BUILT_IN,
    DUPLICATE_END, DUPLICATE_IMPACT, IDENTITY_COLLISION, NUMERIC_OVERFLOW,
}

class MixedScoringException(val code: MixedScoringIssue, val path: String, detail: String) :
    IllegalArgumentException("$code · $path: $detail")

/** A declared owner, not proof of the historical capture of this configuration. */
data class EndConfigurationBinding(val endUuid: String, val configuration: EndConfiguration) {
    init { require(endUuid.isNotBlank()) { "Configuration owner must not be blank." } }
}

/** Immutable calculation context only; never makes a mixed Session persistable. Copy revalidates. */
data class EndScoringContext(val end: End, val binding: EndConfigurationBinding) {
    val configuration get() = binding.configuration
    init { validateEndScoringContext(end, binding) }
}

/** Exact geometry/identity and declared edition, without recalculating an impact or a decision. */
fun validateEndScoringContext(end: End, binding: EndConfigurationBinding) {
    val path = "ends[${end.uuid}].configuration"
    if (binding.endUuid != end.uuid) fail(MixedScoringIssue.WRONG_END, path, "Binding belongs to ${binding.endUuid}.")
    val configuration = binding.configuration
    val target = configuration.targetType
    val known = BuiltInTargetRegistry.find(target.uuid)
    if (known != null && (known.definition != target || known.regulatoryEdition != configuration.regulatoryEdition))
        fail(MixedScoringIssue.PROFILE_IDENTITY_CONFLICT, path, "Published definition and edition must be exact.")
    if (known == null && target.isBuiltIn)
        fail(MixedScoringIssue.UNKNOWN_BUILT_IN, path, "Unknown published profile; no substitution.")
    if (end.uuid == target.uuid) fail(MixedScoringIssue.IDENTITY_COLLISION, path, "End UUID also belongs to its profile.")
    end.impacts.forEach { impact ->
        if (impact.uuid == end.uuid || impact.uuid == target.uuid)
            fail(MixedScoringIssue.IDENTITY_COLLISION, "ends[${end.uuid}].impacts[${impact.uuid}]", "Impact UUID is owned by another type.")
        if (impact.targetType != target)
            fail(MixedScoringIssue.PROFILE_MISMATCH, "ends[${end.uuid}].impacts[${impact.uuid}]", "Impact belongs to a different exact profile.")
    }
}

/** Strict bijection, including empty ends: no default, last-value-wins map, extra or duplicate binding. */
fun bindEndConfigurations(ends: List<End>, bindings: List<EndConfigurationBinding>): List<EndScoringContext> {
    val ownedEnds = ends.toList()
    val ownedBindings = bindings.toList()
    if (ownedEnds.map { it.uuid }.distinct().size != ownedEnds.size)
        fail(MixedScoringIssue.DUPLICATE_END, "ends", "An end must occur only once.")
    if (ownedBindings.map { it.endUuid }.distinct().size != ownedBindings.size)
        fail(MixedScoringIssue.DUPLICATE_CONFIGURATION, "configurations", "An end must have exactly one binding.")
    val byUuid = ownedBindings.associateBy { it.endUuid }
    val endIds = ownedEnds.map { it.uuid }.toSet()
    ownedBindings.firstOrNull { it.endUuid !in endIds }?.let {
        fail(MixedScoringIssue.UNKNOWN_END, "configurations[${it.endUuid}]", "No matching end.")
    }
    val contexts = ownedEnds.sortedWith(compareBy<End> { it.index }.thenBy { it.uuid }).map { end ->
        val binding = byUuid[end.uuid] ?: fail(MixedScoringIssue.MISSING_CONFIGURATION,
            "ends[${end.uuid}].configuration", "An explicit binding is required, even for an empty end.")
        EndScoringContext(end, binding)
    }
    validateContextCollection(contexts)
    return Collections.unmodifiableList(contexts)
}

internal fun validateContextCollection(contexts: List<EndScoringContext>) {
    val profiles = mutableMapOf<String, EndConfiguration>()
    val endIds = mutableSetOf<String>()
    contexts.forEach { context ->
        val end = context.end; val c = context.configuration
        validateEndScoringContext(end, context.binding)
        if (!endIds.add(end.uuid)) fail(MixedScoringIssue.DUPLICATE_END, "ends[${end.uuid}]", "Repeated end UUID.")
        val previous = profiles.putIfAbsent(c.targetType.uuid, c)
        if (previous != null && (previous.targetType != c.targetType || previous.regulatoryEdition != c.regulatoryEdition))
            fail(MixedScoringIssue.PROFILE_IDENTITY_CONFLICT, "profiles[${c.targetType.uuid}]", "One identity cannot have two definitions or editions.")
    }
    val impactIds = mutableSetOf<String>()
    contexts.forEach { context ->
        val end = context.end
        if (end.uuid in profiles) fail(MixedScoringIssue.IDENTITY_COLLISION, "ends[${end.uuid}]", "End UUID also belongs to a profile.")
        end.impacts.forEach { impact ->
            if (impact.uuid in profiles || impact.uuid in endIds)
                fail(MixedScoringIssue.IDENTITY_COLLISION, "impacts[${impact.uuid}]", "UUID is owned by another entity type.")
            if (!impactIds.add(impact.uuid)) fail(MixedScoringIssue.DUPLICATE_IMPACT, "impacts[${impact.uuid}]", "An impact cannot be counted under two ends.")
        }
    }
}

internal fun fail(code: MixedScoringIssue, path: String, detail: String): Nothing = throw MixedScoringException(code, path, detail)
