package dev.kickzwift.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kickzwift.app.BuildConfig
import dev.kickzwift.app.R
import dev.kickzwift.app.session.GearTestFailure
import dev.kickzwift.app.session.GearTestState
import dev.kickzwift.app.ui.common.Format
import dev.kickzwift.app.ui.common.HelpText
import dev.kickzwift.app.ui.common.Stepper
import dev.kickzwift.app.ui.common.actionText
import dev.kickzwift.app.ui.common.buttonLabel
import dev.kickzwift.app.ui.common.gestureLabel
import dev.kickzwift.app.ui.common.shareFile
import dev.kickzwift.protocol.Button
import dev.kickzwift.protocol.Gesture
import dev.kickzwift.protocol.mapping.ButtonMapping
import dev.kickzwift.protocol.settings.OrientationChoice
import dev.kickzwift.protocol.settings.ThemeChoice
import dev.kickzwift.protocol.trainer.GearTestPlan
import kotlinx.coroutines.launch
import java.util.Locale

/** Écran Réglages : boutons, vitesses, cycliste, affichage, options avancées, à propos. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall) }
        buttonsSection(ui, viewModel)
        gearsSection(ui, viewModel)
        riderSection(ui, viewModel)
        displaySection(ui, viewModel)
        advancedSection(ui, viewModel)
        aboutSection()
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(title, style = MaterialTheme.typography.titleLarge)
}

private fun LazyListScope.buttonsSection(
    ui: SettingsUi,
    vm: SettingsViewModel,
) {
    item {
        Section(stringResource(R.string.settings_buttons))
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
        Section(stringResource(R.string.settings_gears))
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
        Section(stringResource(R.string.settings_rider))
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
        Section(stringResource(R.string.settings_display))
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
        Section(stringResource(R.string.settings_advanced))
        ui.firmware.forEach {
            Text(stringResource(R.string.settings_firmware, it.name, it.version ?: stringResource(R.string.settings_firmware_unknown)))
        }
        SwitchRow(stringResource(R.string.settings_ftms), ui.settings.ftmsFallback, vm::setFtms)
        Text(stringResource(R.string.settings_ftms_hint), style = MaterialTheme.typography.bodySmall)
        SwitchRow(stringResource(R.string.settings_fit), ui.settings.fitExport, vm::setFit)
        OutlinedButton(onClick = { scope.launch { context.shareFile(vm.exportLog(), "text/plain") } }) {
            Text(stringResource(R.string.settings_export_log))
        }
        val test by vm.gearTest.collectAsStateWithLifecycle()
        GearTestBlock(test, vm::startGearTest, vm::stopGearTest)
    }
}

/** Essai des vitesses : Debug start et Debug stop, étape en cours, résultats. */
@Composable
private fun GearTestBlock(
    state: GearTestState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.gear_test_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.gear_test_hint), style = MaterialTheme.typography.bodySmall)
        val running = state is GearTestState.Running
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onStart, enabled = !running) { Text(stringResource(R.string.gear_test_start)) }
            OutlinedButton(onClick = onStop, enabled = running) { Text(stringResource(R.string.gear_test_stop)) }
        }
        when (state) {
            GearTestState.Idle -> Unit
            is GearTestState.Running -> {
                val step = state.step.ratio?.let { "${state.step.code} · ${"%.2f".format(Locale.ROOT, it)}" }
                Text(
                    stringResource(
                        R.string.gear_test_running,
                        state.index,
                        state.count,
                        step ?: stringResource(R.string.gear_test_warmup),
                        state.remainingS,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                GearTestResults(state.results)
            }
            is GearTestState.Done -> {
                Text(stringResource(R.string.gear_test_done))
                GearTestResults(state.results)
            }
            is GearTestState.Stopped -> {
                Text(stringResource(R.string.gear_test_stopped))
                GearTestResults(state.results)
            }
            is GearTestState.Failed -> {
                val reason =
                    when (state.reason) {
                        GearTestFailure.NO_TRAINER -> R.string.gear_test_no_trainer
                        GearTestFailure.RIDING -> R.string.gear_test_riding
                        GearTestFailure.FTMS -> R.string.gear_test_ftms
                        GearTestFailure.WRITE -> R.string.gear_test_write
                    }
                Text(stringResource(R.string.gear_test_failed, stringResource(reason)), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun GearTestResults(results: List<GearTestPlan.Result>) {
    results.forEach { r ->
        val way =
            when (r.step.way) {
                GearTestPlan.Way.GEAR_ONLY -> R.string.gear_test_way_a
                GearTestPlan.Way.AFTER_GRADE -> R.string.gear_test_way_b
                GearTestPlan.Way.WITH_MASSES -> R.string.gear_test_way_c
                GearTestPlan.Way.COMBINED, null -> R.string.gear_test_way_d
            }
        val step = "${r.step.code} · ${"%.2f".format(Locale.ROOT, r.step.ratio ?: 0.0)} · ${stringResource(way)}"
        Text(
            stringResource(R.string.gear_test_result, step, r.powerW, r.cadenceRpm, r.realBikeW),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Version et date du build, adresse de contact. */
private fun LazyListScope.aboutSection() {
    item {
        val context = LocalContext.current
        val email = stringResource(R.string.support_email)
        val subject = stringResource(R.string.app_name) + " " + BuildConfig.VERSION_NAME
        Section(stringResource(R.string.settings_about))
        Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.BUILD_DATE))
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
    }
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
