package app.raum.ui

import app.raum.ui.onboarding.OnboardingFlow
import app.raum.ui.onboarding.OnboardingPath
import app.raum.ui.onboarding.OnboardingStep
import app.raum.ui.onboarding.OnboardingStep.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schrittfolge der geführten Einrichtung (ONB-001..006). */
class OnboardingFlowTest {

    private fun walk(path: OnboardingPath): List<OnboardingStep> =
        generateSequence(WELCOME) { s -> OnboardingFlow.next(s, path).takeIf { it != s } }.toList()

    @Test fun `neuer Pfad fragt nach Namen und durchläuft alle Pflichtschritte`() {
        assertEquals(listOf(WELCOME, HOME, REGION, PIN, CHECK, FABRIC, DONE), walk(OnboardingPath.NEW))
    }

    @Test fun `Wiederherstellung ersetzt den Namensschritt durch die Sicherung`() {
        assertEquals(listOf(WELCOME, RESTORE, REGION, PIN, CHECK, FABRIC, DONE), walk(OnboardingPath.RESTORE))
    }

    @Test fun `zurück führt denselben Weg zurück`() {
        for (path in OnboardingPath.entries) {
            val steps = walk(path)
            steps.zipWithNext().forEach { (a, b) -> assertEquals(a, OnboardingFlow.previous(b, path)) }
            assertEquals(WELCOME, OnboardingFlow.previous(WELCOME, path))
            assertEquals(DONE, OnboardingFlow.next(DONE, path))
        }
    }

    @Test fun `nach der Übernahme einer Sicherung gibt es kein Zurück`() {
        assertFalse(OnboardingFlow.canGoBack(WELCOME, restored = false))
        assertFalse(OnboardingFlow.canGoBack(DONE, restored = false))
        assertTrue(OnboardingFlow.canGoBack(REGION, restored = false))
        assertFalse(OnboardingFlow.canGoBack(REGION, restored = true))
        assertTrue(OnboardingFlow.canGoBack(PIN, restored = true))
    }
}
