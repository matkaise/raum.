package app.raum.ui.onboarding

import androidx.annotation.StringRes
import app.raum.R

/** Schritte der Erstinbetriebnahme (Spez. 7.2). */
enum class OnboardingStep(@StringRes val titleRes: Int) {
    WELCOME(R.string.onb_step_welcome),
    RESTORE(R.string.onb_step_restore),
    HOME(R.string.onb_step_home),
    REGION(R.string.onb_step_region),
    PIN(R.string.onb_step_pin),
    CHECK(R.string.onb_step_check),
    FABRIC(R.string.onb_step_fabric),
    DONE(R.string.onb_step_done),
}

/** Neu einrichten oder aus einer Sicherung wiederherstellen (z. B. Ersatzpanel). */
enum class OnboardingPath { NEW, RESTORE }

/** Reihenfolge der Schritte – rein funktional, damit testbar. */
object OnboardingFlow {
    fun steps(path: OnboardingPath): List<OnboardingStep> = when (path) {
        OnboardingPath.NEW -> listOf(
            OnboardingStep.WELCOME, OnboardingStep.HOME, OnboardingStep.REGION, OnboardingStep.PIN,
            OnboardingStep.CHECK, OnboardingStep.FABRIC, OnboardingStep.DONE,
        )
        // Name, Räume, Sprache, Einheit und Standort kommen aus der Sicherung; PIN ist nie enthalten.
        OnboardingPath.RESTORE -> listOf(
            OnboardingStep.WELCOME, OnboardingStep.RESTORE, OnboardingStep.REGION, OnboardingStep.PIN,
            OnboardingStep.CHECK, OnboardingStep.FABRIC, OnboardingStep.DONE,
        )
    }

    fun next(step: OnboardingStep, path: OnboardingPath): OnboardingStep {
        val list = steps(path)
        return list.getOrElse(list.indexOf(step) + 1) { OnboardingStep.DONE }
    }

    fun previous(step: OnboardingStep, path: OnboardingPath): OnboardingStep {
        val list = steps(path)
        return list.getOrElse(list.indexOf(step) - 1) { OnboardingStep.WELCOME }
    }

    /** Zurück ist nach unumkehrbaren Schritten nicht sinnvoll (Wiederherstellung erfolgt, Fabric erzeugt). */
    fun canGoBack(step: OnboardingStep, restored: Boolean): Boolean = when (step) {
        OnboardingStep.WELCOME, OnboardingStep.DONE -> false
        OnboardingStep.REGION -> !restored
        else -> true
    }
}
