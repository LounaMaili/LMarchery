package com.lmarchery.feature.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** One scrollable form with its actions outside the scroll area. */
@Composable
internal fun SessionFormDialog(
    title: String,
    dismiss: () -> Unit,
    submitLabel: String,
    submit: () -> Unit,
    page: Any = Unit,
    navigation: @Composable () -> Unit = {},
    fields: @Composable ColumnScope.() -> Unit,
) {
    Dialog(dismiss) {
        Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Text(title, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleLarge)
                navigation()
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(key(page) { rememberScrollState() }).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = fields,
                )
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(dismiss) { Text("Annuler") }
                    Button(submit, Modifier.weight(1f)) { Text(submitLabel) }
                }
            }
        }
    }
}
