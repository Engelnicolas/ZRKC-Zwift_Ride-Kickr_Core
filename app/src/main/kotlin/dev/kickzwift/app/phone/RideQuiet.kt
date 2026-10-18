package dev.kickzwift.app.phone

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import androidx.core.net.toUri
import dev.kickzwift.app.MainActivity
import dev.kickzwift.app.R

/**
 * Mode Android « ZRKC ride », actif pendant la sortie : plus aucun bandeau flottant par-dessus
 * l'écran Sortie, qui montre lui-même les notifications ([NotificationMirror]). Les notifications
 * retenues par le mode restent dans le volet, mais sans son ni vibration ; les appels, les
 * alarmes et la musique passent comme d'habitude.
 *
 * Il faut l'accès aux notifications (le même que pour le lecteur), qui vaut accès au mode
 * Ne pas déranger ; sans lui, rien ne change. Le mode est désactivé au lancement de l'app, au
 * cas où une sortie précédente se serait arrêtée sans le faire.
 */
class RideQuiet(
    context: Context,
    private val log: (String) -> Unit,
) {
    private val context = context.applicationContext
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val condition = "condition://${context.packageName}/ride".toUri()

    fun set(on: Boolean) {
        if (!notifications.isNotificationPolicyAccessGranted) return
        try {
            val id = ruleId() ?: if (on) notifications.addAutomaticZenRule(rule()) else return
            val state = if (on) Condition.STATE_TRUE else Condition.STATE_FALSE
            notifications.setAutomaticZenRuleState(id, Condition(condition, context.getString(R.string.ride_mode_name), state))
        } catch (e: SecurityException) {
            log("mode sortie refusé : $e")
        } catch (e: IllegalArgumentException) {
            log("mode sortie refusé : $e")
        }
    }

    private fun ruleId(): String? =
        notifications.automaticZenRules.entries
            .firstOrNull { it.value.conditionId == condition }
            ?.key

    private fun rule(): AutomaticZenRule {
        val policy =
            ZenPolicy
                .Builder()
                .disallowAllSounds()
                .allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE)
                .allowRepeatCallers(true)
                .allowAlarms(true)
                .allowMedia(true)
                .hideAllVisualEffects()
                .showStatusBarIcons(true)
                .showBadges(true)
                .showInNotificationList(true)
                .build()
        val name = context.getString(R.string.ride_mode_name)
        val settings = ComponentName(context, MainActivity::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            AutomaticZenRule
                .Builder(name, condition)
                .setConfigurationActivity(settings)
                .setZenPolicy(policy)
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                .setType(AutomaticZenRule.TYPE_OTHER)
                .setManualInvocationAllowed(false)
                .build()
        } else {
            AutomaticZenRule(name, null, settings, condition, policy, NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
        }
    }
}
