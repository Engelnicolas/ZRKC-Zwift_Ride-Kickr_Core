package dev.kickzwift.app.ui

/** Routes des écrans (plan, « Navigation »), plus l'historique des activités. */
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
}
