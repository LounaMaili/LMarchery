package com.lmarchery.core.model.session

/** A form candidate must only become Replace after an explicit choice; cancellation uses Inherit. */
sealed interface NextEndConfigurationChoice {
    data object Inherit : NextEndConfigurationChoice
    data class Replace(val configuration: EndConfiguration) : NextEndConfigurationChoice
}

/**
 * Pure selection: explicit choice, else the immediately preceding end, else the session's initial value.
 * The caller supplies the actual preceding configuration; no sorting, current preset or target default.
 * Immutable values can be shared without copying. No end/session is created or revised by this function.
 * The choice remains separate from value equality: Replace with equal content is still an explicit choice.
 */
fun resolveNextEndConfiguration(
    initial: EndConfiguration,
    previous: EndConfiguration? = null,
    choice: NextEndConfigurationChoice = NextEndConfigurationChoice.Inherit,
): EndConfiguration = when (choice) {
    NextEndConfigurationChoice.Inherit -> previous ?: initial
    is NextEndConfigurationChoice.Replace -> choice.configuration
}
