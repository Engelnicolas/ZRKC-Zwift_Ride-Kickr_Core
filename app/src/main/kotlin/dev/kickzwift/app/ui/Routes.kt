package dev.kickzwift.app.ui

/** Routes des écrans (spécification, « Interface »). */
object Routes {
    const val DEVICES = "devices"
    const val MODE = "mode"
    const val RIDE = "ride"
    const val SUMMARY = "summary"
    const val SETTINGS = "settings"
    const val ACTIVITIES = "activities"

    /** Une activité de l'historique, par son identifiant dans la base. */
    const val ACTIVITY = "activity/{id}"

    fun activity(id: Long) = "activity/$id"

    /** Une page des réglages, par le segment de [dev.kickzwift.app.ui.settings.SettingsPage]. */
    const val SETTINGS_PAGE = "settings/{page}"

    fun settingsPage(page: String) = "settings/$page"
}
