package dev.kickzwift.app.ui.settings

import dev.kickzwift.app.R

/** Pages des réglages, dans l'ordre du menu ; [route] : segment de la route de la page. */
enum class SettingsPage(
    val route: String,
    val title: Int,
    val summary: Int,
) {
    BUTTONS("buttons", R.string.settings_buttons, R.string.settings_buttons_summary),
    GEARS("gears", R.string.settings_gears, R.string.settings_gears_summary),
    RIDER("rider", R.string.settings_rider, R.string.settings_rider_summary),
    DISPLAY("display", R.string.settings_display, R.string.settings_display_summary),
    COROS("coros", R.string.settings_coros, R.string.settings_coros_summary),
    ADVANCED("advanced", R.string.settings_advanced, R.string.settings_advanced_summary),
    ;

    companion object {
        fun of(route: String?): SettingsPage? = entries.firstOrNull { it.route == route }
    }
}
