package com.lmarchery.feature.configured

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.lmarchery.captureUxScreen
import com.lmarchery.app.MainActivity
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class SessionNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun favouriteCanBeCreatedAndFoundBeforeAnySession() {
        val name = "Lieu TEST ${UUID.randomUUID().toString().take(8)}"
        click("Mes lieux de tir")
        click("Ajouter un lieu")
        compose.onNode(hasSetTextAction() and hasText("Nom")).performTextInput(name)
        click("Coordonnées (facultatives)")
        compose.onNode(hasSetTextAction() and hasText("Latitude WGS84 (°)")).performScrollTo().performTextInput("0.25")
        compose.onNode(hasSetTextAction() and hasText("Longitude WGS84 (°)")).performScrollTo().performTextInput("0.5")
        click("Enregistrer")
        awaitText("Latitude · 0.25°")
        awaitText("Longitude · 0.5°")
        captureUxScreen("fiche-lieu")
        addLine("Ligne A", "90")
        addLine("Ligne B", "180")
        click("‹ Mes lieux de tir")
        click("‹ Accueil")
        click("Nouvelle séance configurable")
        val title = "Séance $name"
        val titleField = hasSetTextAction() and hasText("Titre de la séance")
        compose.waitUntil(8_000) {
            compose.onAllNodes(titleField).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(titleField).performScrollTo().assertIsEnabled().performTextInput(title)
        click("Lieu")
        click("Lieu · Aucun lieu")
        click(name)
        awaitText("Lieu · $name")
        click("Ligne · Aucune ligne")
        click("Ligne A")
        click("Modifier pour cette séance")
        compose.onNode(hasSetTextAction() and hasText("Azimut vers les cibles (°), facultatif"))
            .performTextReplacement("110")
        click("Enregistrer")
        awaitText("110.0° · nord géographique")
        click("Créer et démarrer")
        click("Séance")
        awaitText("$name · Ligne A · 110.0° · nord géographique")
        click("‹ Retour au tir")
        click("‹ Historique")
        click("Accueil")
        click("Mes lieux de tir")
        click(name)
        click("Modifier le lieu")
        compose.onNode(hasSetTextAction() and hasText("Nom")).performTextReplacement("$name modifié")
        click("Enregistrer")
        awaitText("$name modifié")
        click("‹ Mes lieux de tir")
        click("‹ Accueil")
        val resume = hasClickAction() and hasText("Reprendre la séance") and hasAnySibling(hasText(title))
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(resume)
        awaitAction("Reprendre $title", resume)
        click("Séance")
        awaitText("$name · Ligne A · 110.0° · nord géographique")
    }

    @Test fun configuredSummaryAndInformationReturnToTheSameEnd() {
        click("Nouvelle séance configurable")
        val title = "TEST Résumé ${UUID.randomUUID().toString().take(8)}"
        val titleField = hasSetTextAction() and hasText("Titre de la séance")
        compose.waitUntil(8_000) { compose.onAllNodes(titleField and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(titleField).performTextInput(title)
        click("Créer et démarrer")
        waitForTarget().performTouchInput { click(center) }
        awaitText("1 / 3")
        waitForTarget().performTouchInput { click(percentOffset(0.05f, 0.05f)) }
        awaitText("2 / 3")
        click("Bilan")
        awaitText("Flèches retenues pour les mesures · 2 sur 2")
        captureUxScreen("bilan-visuel")
        awaitText("Score de la volée · 10 points")
        compose.onNodeWithText("Inclure les flèches notées M dans les mesures de groupement")
            .performScrollTo().performClick()
        awaitText("Flèches retenues pour les mesures · 1 sur 2")
        awaitText("○ Flèche M (0 point) exclue des mesures")
        awaitText("Il faut au moins deux flèches retenues pour mesurer leur écart.")
        awaitText("Score de la volée · 10 points")
        click("‹ Retour à la volée")
        click("Séance")
        awaitText("Informations de séance")
        awaitText("Aucun relevé météo.")
        compose.onAllNodesWithText(title).assertCountEquals(1)
        awaitText("Score provisoire · 10 points")
        compose.onAllNodesWithText("Score provisoire · 10 points").assertCountEquals(1)
        compose.onAllNodesWithText("Configuration initiale", substring = true).assertCountEquals(0)
        captureUxScreen("informations-seance")
        click("Informations techniques")
        awaitText("Configuration initiale · CREATION")
        click("Masquer les informations techniques")
        click("Ajouter un relevé météo")
        awaitText("Température (°C)")
        captureUxScreen("meteo-formulaire-app")
        click("Annuler")
        click("‹ Retour au tir")
        awaitText("2 / 3")
    }

    @Test fun historicalSummaryStillReturnsToTheEndWithoutChangingTheScore() {
        click("Séance salle rapide")
        awaitText("Titre facultatif")
        val suffix = UUID.randomUUID().toString().take(8)
        compose.onNode(hasSetTextAction() and hasText("Titre facultatif")).performTextInput("TEST Bilan $suffix")
        click("Créer et démarrer")
        waitForTarget().performScrollTo().assertIsDisplayed().performTouchInput { click(center) }
        awaitText("Volée 1 · En saisie · 1/3 flèches")
        click("Bilan")
        awaitText("Flèches retenues pour les mesures · 1 sur 1")
        awaitText("Score de la volée · 10 points")
        click("‹ Retour à la volée")
        click("Bilan")
        awaitText("Flèches retenues pour les mesures · 1 sur 1")
        awaitText("Score de la volée · 10 points")
    }

    private fun awaitText(text: String) {
        try {
            compose.waitUntil(8_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("Texte attendu absent : $text", error)
        }
    }

    private fun addLine(name: String, angle: String) {
        click("Ajouter une ligne")
        compose.onNode(hasSetTextAction() and hasText("Nom")).performTextInput(name)
        compose.onNode(hasSetTextAction() and hasText("Azimut vers les cibles (°), facultatif"))
            .performScrollTo().performTextInput(angle)
        click("Enregistrer")
        awaitText(name)
    }

    private fun click(text: String) {
        awaitAction(text, hasText(text) and hasClickAction())
    }

    private fun awaitAction(label: String, matcher: SemanticsMatcher) {
        val action = matcher and isEnabled()
        try {
            compose.waitUntil(8_000) { compose.onAllNodes(action).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("Action disponible attendue : $label", error)
        }
        compose.onNode(action).performClick()
    }

    private fun waitForTarget(): SemanticsNodeInteraction {
        val description = "Blason interactif LMarchery"
        compose.waitUntil(8_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size == 1 }
        return compose.onNodeWithContentDescription(description)
    }
}
