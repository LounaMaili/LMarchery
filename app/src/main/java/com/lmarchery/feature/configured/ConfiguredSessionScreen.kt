package com.lmarchery.feature.configured

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.feature.sessions.*
import com.lmarchery.feature.photo.EndPhotoKey
import com.lmarchery.feature.photo.EndPhotoScreen

@Composable
fun ConfiguredSessionScreen(controller: ConfiguredSessionController, onReturnToHistory: () -> Unit = {}) {
    val state by controller.state.collectAsStateWithLifecycle()
    var photoEndUuid by rememberSaveable(state.saved?.uuid) { mutableStateOf<String?>(null) }
    state.saved?.let { session ->
        val captured = session.ends.singleOrNull { it.uuid == photoEndUuid }
        if (captured != null) {
            val targetContext = state.targetContext?.takeIf { state.selectedEndUuid == captured.uuid }
            val configuration = captured.configuration
            key(captured.uuid) {
                EndPhotoScreen(
                    EndPhotoKey(session.uuid, captured.uuid), "Volée ${captured.end.index}",
                    editable = state.canNavigate && state.editableEnd && targetContext != null,
                    remaining = configuration.arrowCountPerEnd?.minus(captured.end.activeImpacts.size) ?: Int.MAX_VALUE,
                    existingImpactIds = captured.end.impacts.mapTo(mutableSetOf()) { it.uuid },
                    targetType = configuration.targetType,
                    onConfirm = { points -> targetContext != null && controller.confirmPhotoImpacts(targetContext, points) },
                    onClose = { photoEndUuid = null },
                )
            }
            return
        }
    }
    var leave by rememberSaveable { mutableStateOf(false) }
    var cancellationLeaveToken by rememberSaveable { mutableStateOf<Long?>(null) }
    state.cancellation?.let { dialog ->
        ImpactCancellationConfirmation(dialog, state.busy, state.pending != null, state.problem?.message, state.canRetryExact,
            controller::cancellationComment, controller::closeCancellation, controller::confirmCancellation, controller::reconcile, controller::retryExact,
            { token -> if (controller.canAbandonCancellationDisplay(token)) { cancellationLeaveToken = token; leave = true } })
    }
    val creation = state.form as? ConfiguredForm.Creation
    val presets = state.presets
    if (creation != null && presets?.nameDraft != null) {
        val token = creation.token
        val dismiss = { controller.cancelPresetName(token) }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Enregistrer un preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(presets.nameDraft, { controller.presetName(token, it) }, enabled = state.ready,
                        label = { Text("Nom du preset") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Les réglages actuels seront disponibles sur cet appareil.")
                    presets.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button({ controller.savePreset(token) }, enabled = state.ready && presets.nameDraft.isNotBlank()) {
                    Text("Enregistrer le preset")
                }
            },
            dismissButton = { TextButton(dismiss, enabled = state.ready) { Text("Annuler") } },
        )
    }
    (state.form as? ConfiguredForm.PresetDeletion)?.let { deletion ->
        val dismiss = { controller.cancelForm(deletion.token) }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Supprimer ce preset ?") },
            text = { Text("« ${deletion.expected.name} » sera supprimé définitivement. Les sauvegardes JSON/SAF ne permettent pas de le restaurer. Les séances existantes sont conservées.") },
            confirmButton = { Button({ controller.confirmForm(deletion.token) }, enabled = state.ready) { Text("Supprimer définitivement") } },
            dismissButton = { TextButton(dismiss, enabled = state.ready) { Text("Annuler") } },
        )
    }
    var finishEpoch by rememberSaveable { mutableStateOf<Long?>(null) }
    val back = { if (!state.busy) {
        val form = state.form
        if (state.impactPicking != null) controller.cancelImpactPicking(state.impactPicking!!.context)
        else if (form is ConfiguredForm.PresetEdit || form is ConfiguredForm.PresetDeletion) controller.cancelForm(form.token)
        else if (state.pending != null || form != null || state.hasProposal) leave = true else {
            if (state.saved != null) onReturnToHistory()
            controller.close()
        }
    } }
    BackHandler(onBack = back)
    if (state.saved?.status == SessionStatus.IN_PROGRESS && state.form == null) {
        ConfiguredRange(state, controller, back, { finishEpoch = state.epoch }, { photoEndUuid = it })
    } else {
        Scaffold(
            bottomBar = {
                creation?.let { form ->
                    Surface(tonalElevation = 3.dp) {
                        Column(
                            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val enabled = state.ready && presets?.nameDraft == null
                            if (state.pending == null) state.problem?.let {
                                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            Button({ controller.createAndStart(form.token) }, Modifier.fillMaxWidth(), enabled = enabled) {
                                Text("Créer et démarrer")
                            }
                            TextButton({ controller.confirmForm(form.token) }, Modifier.fillMaxWidth(), enabled = enabled) {
                                Text("Enregistrer comme brouillon")
                            }
                        }
                    }
                }
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item { TextButton(onClick = back, enabled = !state.busy) { Text("‹  Retour aux séances") } }
                if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.problem?.takeUnless { creation != null || state.form is ConfiguredForm.PresetEdit }?.let { problem ->
                    item { Text(problem.message, color = MaterialTheme.colorScheme.error) }
                }
                state.confirmation?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
                if (state.pending != null && !state.busy) item { ConfiguredReconciliationActions(state, controller) }

                val form = state.form
                if (form != null && form !is ConfiguredForm.PresetDeletion) {
                    item(key = form.token) { ConfiguredFormFields(form, state, controller) }
                }
                if (form == null) state.saved?.let { session ->
                    item { ConfiguredSessionSummary(session, readOnly = session.status == SessionStatus.COMPLETED) }
                    if (session.status == SessionStatus.DRAFT) item {
                        Button(controller::start, enabled = state.canForm) { Text("Démarrer la séance") }
                        TextButton(controller::editMetadata, enabled = state.canForm) { Text("Modifier les métadonnées et le matériel") }
                    }
                    items(session.ends.sortedBy { it.end.index }, key = { it.uuid }) { captured ->
                        ConfiguredEndCard(session, captured)
                        TextButton({ photoEndUuid = captured.uuid }, enabled = state.canNavigate) {
                            Text("Photo · volée ${captured.end.index}")
                        }
                    }
                }
            }
        }
    }
    if (leave) AlertDialog(onDismissRequest = { leave = false; cancellationLeaveToken = null }, title = { Text(if (state.pending != null) "Quitter une écriture non confirmée ?" else "Abandonner le candidat local ?") },
        text = { Text(if (state.pending != null) "Le commit peut avoir eu lieu. Quitter n’annule aucune écriture. Relisez la séance avant toute nouvelle opération ; l’intention locale sera perdue."
            else "Ce formulaire ou cette correction locale n’a pas été soumis. Aucun enregistrement n’est créé en l’abandonnant.") },
        confirmButton = { TextButton({
            val ticket = cancellationLeaveToken
            leave = false; cancellationLeaveToken = null
            if (ticket == null || controller.canAbandonCancellationDisplay(ticket)) {
                if (controller.state.value.saved != null) onReturnToHistory()
                if (controller.state.value.pending != null) controller.abandonSubmittedAndClose() else controller.abandonLocalAndClose()
            }
        }) { Text("Abandonner l’affichage et revenir") } },
        dismissButton = { TextButton({ leave = false; cancellationLeaveToken = null }) { Text("Conserver") } })
    finishEpoch?.let { ticket ->
        if (ticket == state.epoch && state.canForm && state.sessionActive) {
            AlertDialog(
                onDismissRequest = { finishEpoch = null },
                title = { Text("Terminer la séance ?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cette séance sera définitivement terminée.")
                        Text("Les volées validées conserveront leurs scores.")
                        Text("Les volées provisoires resteront provisoires et seront figées.")
                        Text("Aucune flèche ni volée manquante ne sera ajoutée.")
                    }
                },
                confirmButton = {
                    Button({
                        if (finishEpoch == ticket) {
                            finishEpoch = null
                            if (controller.state.value.epoch == ticket) controller.complete()
                        }
                    }) { Text("Terminer définitivement") }
                },
                dismissButton = { TextButton({ finishEpoch = null }) { Text("Annuler") } },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfiguredFormFields(form: ConfiguredForm, state: ConfiguredEditorState, c: ConfiguredSessionController) {
    val token = form.token
    val creation = form as? ConfiguredForm.Creation
    val editingPreset = form is ConfiguredForm.PresetEdit
    var more by rememberSaveable(token) { mutableStateOf(form is ConfiguredForm.Metadata) }
    val metadata = when (form) {
        is ConfiguredForm.Creation -> form.metadata
        is ConfiguredForm.Metadata -> form.metadata
        is ConfiguredForm.PresetEdit -> form.metadata
        else -> null
    }
    val config = when (form) {
        is ConfiguredForm.Creation -> form.configuration
        is ConfiguredForm.NextEnd -> form.configuration
        is ConfiguredForm.PresetEdit -> form.configuration
        else -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(when (form) {
            is ConfiguredForm.Creation -> "Nouvelle séance"
            is ConfiguredForm.NextEnd -> "Prochaine volée"
            is ConfiguredForm.PresetEdit -> "Modifier le preset"
            else -> "Métadonnées"
        }, style = MaterialTheme.typography.headlineMedium)
        if (creation != null) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SuggestionPicker(token, state.ready, c)
                    state.presets?.let { PresetPicker(token, it, state.ready, c) }
                }
            }
        }
        if (metadata != null && form !is ConfiguredForm.Metadata) {
            OutlinedTextField(
                metadata.title, { c.title(token, it) }, Modifier.fillMaxWidth(), enabled = state.ready,
                label = { Text(if (editingPreset) "Nom du preset" else "Titre de la séance") }, singleLine = true,
                supportingText = creation?.configuration?.suggestedTitle?.takeIf { metadata.title.isBlank() }?.let { title ->
                    { Text("Titre proposé · $title") }
                },
            )
        }
        if (config != null) {
            val enabled = state.ready
            ConfigurationBasics(token, config, enabled, c)
            OutlinedButton({ more = !more }, Modifier.fillMaxWidth()) {
                Text(if (more) "Masquer les options" else if (metadata != null) "Options de tir et matériel" else "Options de tir")
            }
            if (more) {
                Text("Lieu de tir", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(config.indoor, { c.indoor(token, true) }, { Text("Salle") }, enabled = enabled)
                    FilterChip(!config.indoor, { c.indoor(token, false) }, { Text("Extérieur") }, enabled = enabled)
                }
                Text("Mode de comptage", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScoringMode.entries.forEach { mode ->
                        FilterChip(config.scoringMode == mode, { c.scoring(token, mode) }, { Text(scoringLabel(mode)) }, enabled = enabled)
                    }
                }
            }
        }
        if (metadata != null && more) {
            if (form is ConfiguredForm.Metadata)
                OutlinedTextField(metadata.title, { c.title(token, it) }, Modifier.fillMaxWidth(),
                    enabled = state.ready, label = { Text("Titre de la séance") }, singleLine = true)
            OutlinedTextField(metadata.plannedEnds, { c.planned(token, it) }, Modifier.fillMaxWidth(),
                enabled = state.ready, label = { Text("Volées prévues (facultatif)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (!editingPreset) {
                OutlinedTextField(metadata.location.orEmpty(), { c.location(token, it) }, Modifier.fillMaxWidth(),
                    enabled = state.ready, label = { Text("Lieu (facultatif)") })
                OutlinedTextField(metadata.notes.orEmpty(), { c.notes(token, it) }, Modifier.fillMaxWidth(),
                    enabled = state.ready, label = { Text("Notes (facultatif)") })
            }
            val original = when (form) {
                is ConfiguredForm.Creation -> form.presetEquipment
                is ConfiguredForm.PresetEdit -> EquipmentSelection(form.expected.bowUuid, form.expected.arrowSetUuid)
                else -> state.saved?.metadata?.let { EquipmentSelection(it.bowUuid, it.arrowSetUuid) } ?: EquipmentSelection()
            }
            EquipmentChoiceFields(state.equipmentChoices, metadata.equipment, state.ready, original = original,
                choose = { kind, uuid -> c.chooseEquipment(token, kind, uuid) })
        }
        if (creation != null && state.presets?.items != null) {
            TextButton({ c.namePreset(token) }, enabled = state.ready) { Text("Enregistrer comme preset") }
            state.presets.savedName?.let { Text("Preset enregistré : $it", color = MaterialTheme.colorScheme.primary) }
        }
        if (editingPreset) state.problem?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
        if (creation == null) {
            Button({ c.confirmForm(token) }, Modifier.fillMaxWidth(), enabled = state.ready) {
                Text(when (form) {
                    is ConfiguredForm.PresetEdit -> "Enregistrer les modifications"
                    is ConfiguredForm.NextEnd -> "Créer la volée"
                    else -> "Enregistrer"
                })
            }
        }
        TextButton({ c.cancelForm(token) }, enabled = state.ready) { Text("Annuler sans enregistrer") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigurationBasics(token: Long, draft: ConfigurationDraft, enabled: Boolean, c: ConfiguredSessionController) {
    var profiles by rememberSaveable(token) { mutableStateOf(false) }
    var custom by rememberSaveable(token, draft.profile.definition.uuid) { mutableStateOf(false) }
    Text("Réglages de tir", style = MaterialTheme.typography.titleMedium)
    OutlinedButton({ profiles = true }, Modifier.fillMaxWidth(), enabled = enabled) {
        Column(Modifier.weight(1f)) {
            Text("Blason", style = MaterialTheme.typography.labelMedium)
            Text(draft.profile.definition.name, style = MaterialTheme.typography.bodyLarge)
        }
        Text("Changer")
    }
    if (profiles) AlertDialog(
        onDismissRequest = { profiles = false },
        title = { Text("Choisir un blason") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(BuiltInTargetRegistry.all, key = { it.definition.uuid }) { entry ->
                    TextButton({
                        c.chooseProfile(token, entry.definition.uuid)
                        profiles = false
                    }, Modifier.fillMaxWidth(), enabled = enabled) { Text(entry.definition.name) }
                }
            }
        },
        confirmButton = { TextButton({ profiles = false }) { Text("Fermer") } },
    )
    OutlinedTextField(draft.distance, { c.distance(token, it) }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text("Distance") }, suffix = { Text("m") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    Text("Flèches par volée", style = MaterialTheme.typography.titleSmall)
    val fixed = draft.arrowCountMode == ArrowCountMode.FIXED
    val other = custom || (fixed && draft.fixedCount !in listOf("3", "6"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EndArrowCountShortcut.entries.forEach { shortcut ->
            FilterChip(
                selected = if (shortcut == EndArrowCountShortcut.FREE) !fixed
                    else fixed && !other && draft.fixedCount == shortcut.arrowCountPerEnd.toString(),
                onClick = { custom = false; c.shortcut(token, shortcut) },
                label = { Text(shortcut.arrowCountPerEnd?.toString() ?: "Libre") },
                enabled = enabled,
            )
        }
        FilterChip(fixed && other, { custom = true; c.count(token, draft.fixedCount.ifEmpty { "3" }) },
            { Text("Autre") }, enabled = enabled)
    }
    if (fixed && other) {
        OutlinedTextField(draft.fixedCount, { c.count(token, it) }, Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text("Nombre de flèches") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
}

private fun scoringLabel(mode: ScoringMode) = when (mode) {
    ScoringMode.TRAINING -> "Entraînement"
    ScoringMode.COMPETITION -> "Compétition"
    ScoringMode.MANUAL_CORDON -> "Manuel"
}

@Composable
private fun SuggestionPicker(token: Long, enabled: Boolean, c: ConfiguredSessionController) {
    var open by rememberSaveable(token) { mutableStateOf(false) }
    OutlinedButton({ open = true }, enabled = enabled) { Text("Suggestions intégrées") }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Suggestions intégrées") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Réglages d’entraînement modifiables après sélection.") }
                items(SessionSuggestion.entries, key = { it.name }) { suggestion ->
                    OutlinedButton(onClick = {
                        c.applySuggestion(token, suggestion)
                        open = false
                    }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(suggestion.label)
                            Text(
                                "${suggestion.configuration.profile.definition.name} · ${suggestion.configuration.fixedCount} flèches",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ open = false }) { Text("Fermer") } },
    )
}

@Composable
private fun PresetPicker(token: Long, state: SessionPresetsState, enabled: Boolean, c: ConfiguredSessionController) {
    var open by rememberSaveable(token) { mutableStateOf(false) }
    when {
        state.loadFailed -> {
            Text("Impossible de lire les presets enregistrés.", color = MaterialTheme.colorScheme.error)
            TextButton({ c.reloadPresets(token) }, enabled = enabled) { Text("Recharger les presets") }
        }
        state.items == null -> OutlinedButton({}, enabled = false) { Text("Mes presets") }
        else -> {
            OutlinedButton({ open = true }, enabled = enabled) { Text("Mes presets") }
            if (open) AlertDialog(
                onDismissRequest = { open = false },
                title = { Text("Mes presets") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.items.isEmpty()) item { Text("Aucun preset enregistré sur cet appareil.") }
                        items(state.items, key = { it.uuid }) { preset ->
                            Column {
                                OutlinedButton(onClick = {
                                    c.applyPreset(token, preset.uuid)
                                    open = false
                                }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                                    Column {
                                        Text(preset.name)
                                        Text("${preset.configuration.targetType.name} · ${preset.configuration.distanceM} m",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton({
                                        c.editPreset(token, preset.uuid)
                                        open = false
                                    }, enabled = enabled) { Text("Modifier") }
                                    TextButton({
                                        c.requestPresetDeletion(token, preset.uuid)
                                        open = false
                                    }, enabled = enabled) { Text("Supprimer") }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton({ open = false }) { Text("Fermer") } },
            )
        }
    }
}
