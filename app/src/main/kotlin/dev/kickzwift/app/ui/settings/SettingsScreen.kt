package dev.kickzwift.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kickzwift.app.BuildConfig
import dev.kickzwift.app.R
import dev.kickzwift.app.coros.CorosStatus
import dev.kickzwift.app.session.ResendTestFailure
import dev.kickzwift.app.session.ResendTestState
import dev.kickzwift.app.ui.common.CorosStatusText
import dev.kickzwift.app.ui.common.Format
import dev.kickzwift.app.ui.common.HelpText
import dev.kickzwift.app.ui.common.Stepper
import dev.kickzwift.app.ui.common.actionText
import dev.kickzwift.app.ui.common.buttonLabel
import dev.kickzwift.app.ui.common.gestureLabel
import dev.kickzwift.app.ui.common.shareFile
import dev.kickzwift.protocol.Button
import dev.kickzwift.protocol.Gesture
import dev.kickzwift.protocol.coros.CorosUpload
import dev.kickzwift.protocol.mapping.ButtonMapping
import dev.kickzwift.protocol.settings.GearMode
import dev.kickzwift.protocol.settings.OrientationChoice
import dev.kickzwift.protocol.settings.ThemeChoice
import dev.kickzwift.protocol.trainer.ResendTestPlan
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Écran Réglages : le menu des pages, chacune ouverte par [onOpen] dans [SettingsPageScreen],
 * puis le bouton du support et le numéro du build.
 */
@Composable
fun SettingsScreen(onOpen: (SettingsPage) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall) }
        items(SettingsPage.entries, key = { it.route }) { page ->
            ListItem(
                headlineContent = { Text(stringResource(page.title)) },
                supportingContent = { Text(stringResource(page.summary)) },
                trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
                modifier = Modifier.clip(MaterialTheme.shapes.small).clickable { onOpen(page) },
            )
        }
        item { SupportFooter() }
    }
}

/** Bouton du support (e-mail, version en objet), puis version, numéro et date du build en petit. */
@Composable
private fun SupportFooter() {
    val context = LocalContext.current
    val email = stringResource(R.string.support_email)
    val subject = stringResource(R.string.app_name) + " " + BuildConfig.VERSION_NAME
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val intent = Intent(Intent.ACTION_SENDTO, "mailto:$email".toUri()).putExtra(Intent.EXTRA_SUBJECT, subject)
            // Aucune app de messagerie : rien à ouvrir.
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
            }
        }) {
            Text(stringResource(R.string.settings_email_support))
        }
        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_DATE),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Une page des réglages, avec Retour. Quitter la page des boutons arrête le mode « Tester » :
 * sinon les manettes resteraient sans effet jusque dans la sortie suivante.
 */
@Composable
fun SettingsPageScreen(
    viewModel: SettingsViewModel,
    page: SettingsPage,
    onBack: () -> Unit,
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    if (page == SettingsPage.BUTTONS) DisposableEffect(viewModel) { onDispose { viewModel.setTesting(false) } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                Text(stringResource(page.title), style = MaterialTheme.typography.headlineSmall)
            }
        }
        when (page) {
            SettingsPage.BUTTONS -> buttonsSection(ui, viewModel)
            SettingsPage.GEARS -> gearsSection(ui, viewModel)
            SettingsPage.RIDER -> riderSection(ui, viewModel)
            SettingsPage.DISPLAY -> displaySection(ui, viewModel)
            SettingsPage.COROS -> corosSection(viewModel)
            SettingsPage.ADVANCED -> advancedSection(ui, viewModel)
        }
    }
}

private fun LazyListScope.buttonsSection(
    ui: SettingsUi,
    vm: SettingsViewModel,
) {
    item {
        SwitchRow(stringResource(R.string.settings_test), ui.testing, vm::setTesting)
        if (ui.testing) HelpText(stringResource(R.string.settings_test_hint))
    }
    items(Button.entries, key = { it.name }) { button ->
        val highlight = if (ui.pressed == button) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        Column(Modifier.fillMaxWidth().background(highlight, MaterialTheme.shapes.small).padding(8.dp)) {
            Text(stringResource(buttonLabel(button)), style = MaterialTheme.typography.titleSmall)
            Gesture.entries.forEach { gesture ->
                GestureRow(gesture, ui.settings.mapping.actionFor(button, gesture)) { vm.bind(button, gesture, it) }
            }
        }
    }
    item { TextButton(onClick = vm::resetMapping) { Text(stringResource(R.string.settings_reset_mapping)) } }
}

@Composable
private fun GestureRow(
    gesture: Gesture,
    current: dev.kickzwift.protocol.Action,
    onPick: (dev.kickzwift.protocol.Action) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(gestureLabel(gesture)), style = MaterialTheme.typography.bodyMedium)
        Column {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(actionText(current)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                ButtonMapping.CHOICES.forEach { action ->
                    DropdownMenuItem(text = { Text(actionText(action)) }, onClick = {
                        onPick(action)
                        open = false
                    })
                }
            }
        }
    }
}

private fun LazyListScope.gearsSection(
    ui: SettingsUi,
    vm: SettingsViewModel,
) {
    item {
        Text(stringResource(R.string.settings_gear_mode))
        Chips(
            GearMode.entries,
            ui.settings.gearMode,
            label = {
                stringResource(
                    when (it) {
                        GearMode.POWER -> R.string.settings_gear_mode_power
                        GearMode.TRAINER -> R.string.settings_gear_mode_trainer
                    },
                )
            },
            onPick = vm::setGearMode,
        )
        Text(
            stringResource(
                when (ui.settings.gearMode) {
                    GearMode.POWER -> R.string.settings_gear_mode_power_hint
                    GearMode.TRAINER -> R.string.settings_gear_mode_trainer_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        var text by remember(ui.settings.gears) { mutableStateOf(SettingsViewModel.formatGears(ui.settings.gears)) }
        var error by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                error = false
            },
            label = { Text(stringResource(R.string.settings_gear_table)) },
            isError = error,
            supportingText = { if (error) Text(stringResource(R.string.settings_gear_table_error)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { error = !vm.setGears(text) }) { Text(stringResource(R.string.settings_apply)) }
            TextButton(onClick = vm::zwiftPreset) { Text(stringResource(R.string.settings_zwift_preset)) }
        }
        Labeled(stringResource(R.string.settings_start_gear)) {
            Stepper("${ui.settings.startGear}", onMinus = { vm.stepStartGear(false) }, onPlus = { vm.stepStartGear(true) })
        }
        Labeled(stringResource(R.string.settings_write_interval)) {
            Stepper("${ui.settings.writeIntervalMs} ms", onMinus = { vm.stepWriteInterval(false) }, onPlus = { vm.stepWriteInterval(true) })
        }
    }
}

private fun LazyListScope.riderSection(
    ui: SettingsUi,
    vm: SettingsViewModel,
) {
    item {
        Labeled(stringResource(R.string.settings_rider_kg)) {
            Stepper(Format.kg(ui.settings.riderKg), onMinus = { vm.stepRider(false) }, onPlus = { vm.stepRider(true) })
        }
        Labeled(stringResource(R.string.settings_bike_kg)) {
            Stepper(Format.kg(ui.settings.bikeKg), onMinus = { vm.stepBike(false) }, onPlus = { vm.stepBike(true) })
        }
    }
}

private fun LazyListScope.displaySection(
    ui: SettingsUi,
    vm: SettingsViewModel,
) {
    item {
        Text(stringResource(R.string.settings_theme))
        Chips(
            ThemeChoice.entries,
            ui.settings.theme,
            {
                stringResource(
                    when (it) {
                        ThemeChoice.DARK -> R.string.settings_theme_dark
                        ThemeChoice.LIGHT -> R.string.settings_theme_light
                        ThemeChoice.SYSTEM -> R.string.settings_theme_system
                    },
                )
            },
            vm::setTheme,
        )
        Text(stringResource(R.string.settings_orientation))
        Chips(
            OrientationChoice.entries,
            ui.settings.orientation,
            {
                stringResource(
                    when (it) {
                        OrientationChoice.AUTO -> R.string.settings_orientation_auto
                        OrientationChoice.PORTRAIT -> R.string.settings_orientation_portrait
                        OrientationChoice.LANDSCAPE -> R.string.settings_orientation_landscape
                    },
                )
            },
            vm::setOrientation,
        )
        SwitchRow(stringResource(R.string.settings_haptics), ui.settings.haptics, vm::setHaptics)
        SwitchRow(stringResource(R.string.settings_imperial), ui.settings.imperial, vm::setImperial)
    }
}

private fun LazyListScope.advancedSection(
    ui: SettingsUi,
    vm: SettingsViewModel,
) {
    item {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        ui.firmware.forEach {
            Text(stringResource(R.string.settings_firmware, it.name, it.version ?: stringResource(R.string.settings_firmware_unknown)))
        }
        SwitchRow(stringResource(R.string.settings_ftms), ui.settings.ftmsFallback, vm::setFtms)
        Text(stringResource(R.string.settings_ftms_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { scope.launch { context.shareFile(vm.exportLog(), "text/plain") } }) {
            Text(stringResource(R.string.settings_export_log))
        }
        val test by vm.resendTest.collectAsStateWithLifecycle()
        ResendTestBlock(test, vm::startResendTest, vm::stopResendTest)
    }
}

/** Essai du renvoi : Debug start et Debug stop, étape en cours, résultats. */
@Composable
private fun ResendTestBlock(
    state: ResendTestState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.resend_test_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.resend_test_hint), style = MaterialTheme.typography.bodySmall)
        val running = state is ResendTestState.Running
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onStart, enabled = !running) { Text(stringResource(R.string.resend_test_start)) }
            OutlinedButton(onClick = onStop, enabled = running) { Text(stringResource(R.string.resend_test_stop)) }
        }
        when (state) {
            ResendTestState.Idle -> Unit
            is ResendTestState.Running -> {
                val step = state.step.ratio?.let { "${state.step.code} · ${"%.2f".format(Locale.ROOT, it)}" }
                Text(
                    stringResource(
                        R.string.resend_test_running,
                        state.index,
                        state.count,
                        step ?: stringResource(R.string.resend_test_warmup),
                        state.remainingS,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                ResendTestResults(state.results)
            }
            is ResendTestState.Done -> {
                Text(stringResource(R.string.resend_test_done))
                ResendTestResults(state.results)
            }
            is ResendTestState.Stopped -> {
                Text(stringResource(R.string.resend_test_stopped))
                ResendTestResults(state.results)
            }
            is ResendTestState.Failed -> {
                val reason =
                    when (state.reason) {
                        ResendTestFailure.NO_TRAINER -> R.string.resend_test_no_trainer
                        ResendTestFailure.RIDING -> R.string.resend_test_riding
                        ResendTestFailure.FTMS -> R.string.resend_test_ftms
                        ResendTestFailure.WRITE -> R.string.resend_test_write
                    }
                Text(stringResource(R.string.resend_test_failed, stringResource(reason)), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ResendTestResults(results: List<ResendTestPlan.Result>) {
    results.forEach { r ->
        val way =
            when (r.step.way) {
                ResendTestPlan.Way.NO_RESEND, null -> R.string.resend_test_way_e
                ResendTestPlan.Way.GRADE -> R.string.resend_test_way_f
                ResendTestPlan.Way.GRADE_AND_GEAR -> R.string.resend_test_way_g
            }
        val step = "${r.step.code} · ${"%.2f".format(Locale.ROOT, r.step.ratio ?: 0.0)} · ${stringResource(way)}"
        val early = r.earlyW?.toString() ?: "—"
        Text(
            stringResource(R.string.resend_test_result, step, early, r.powerW, r.cadenceRpm, r.realBikeW),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Compte COROS pour « Export for COROS » : saisie, puis vérification ou oubli. */
private fun LazyListScope.corosSection(vm: SettingsViewModel) {
    item {
        val account by vm.corosAccount.collectAsStateWithLifecycle()
        val status by vm.coros.collectAsStateWithLifecycle()
        Text(stringResource(R.string.settings_coros_hint), style = MaterialTheme.typography.bodySmall)
        val saved = account
        if (saved == null) {
            var region by remember { mutableStateOf(CorosUpload.Region.EUROPE) }
            var email by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            Text(stringResource(R.string.settings_coros_region))
            Chips(CorosUpload.Region.entries, region, label = { stringResource(regionLabel(it)) }, onPick = { region = it })
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.settings_coros_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.settings_coros_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    vm.saveCoros(email, password, region)
                    password = ""
                },
                enabled = '@' in email && password.isNotEmpty(),
            ) { Text(stringResource(R.string.settings_coros_save)) }
        } else {
            Text(stringResource(R.string.settings_coros_signed_in, saved.email, stringResource(regionLabel(saved.region))))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::checkCoros, enabled = status !is CorosStatus.Sending) {
                    Text(stringResource(R.string.settings_coros_check))
                }
                TextButton(onClick = vm::forgetCoros) { Text(stringResource(R.string.settings_coros_forget)) }
            }
            if (status.file == null) CorosStatusText(status, R.string.settings_coros_checked)
        }
    }
}

private fun regionLabel(region: CorosUpload.Region) =
    when (region) {
        CorosUpload.Region.EUROPE -> R.string.settings_coros_europe
        CorosUpload.Region.AMERICAS -> R.string.settings_coros_americas
    }

@Composable
private fun Labeled(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> Chips(
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { v -> FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(label(v)) }) }
    }
}
