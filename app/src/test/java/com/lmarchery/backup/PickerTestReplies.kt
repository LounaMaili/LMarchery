package com.lmarchery.backup

import com.lmarchery.feature.archiveimport.*
import com.lmarchery.feature.export.*

// Existing suites explicitly reply to their current request; all assertions remain unchanged.
// Stale-request regressions separately retain and submit the actual earlier ticket.
internal fun ImportCoordinator.selectedForTest(uri: String?) = selected((state.value as? ImportState.Choosing)?.requestId, uri)
internal fun ExportCoordinator.destinationChosenForTest(uri: String?) = destinationChosen((state.value as? ExportState.Choosing)?.requestId, uri)
internal fun ImportCoordinator.pickerUnavailableForTest() = pickerUnavailable((state.value as? ImportState.Choosing)?.requestId)
internal fun ExportCoordinator.pickerUnavailableForTest() = pickerUnavailable((state.value as? ExportState.Choosing)?.requestId)
