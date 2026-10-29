package cn.coldlake.stellar.opensource.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.R
import cn.coldlake.stellar.opensource.appearance.*
import java.util.Locale
import kotlin.math.roundToInt

internal val CustomColorRole.label: Int get() = when (this) {
    CustomColorRole.PRIMARY -> R.string.ap_custom_primary
    CustomColorRole.SECONDARY -> R.string.ap_custom_secondary
    CustomColorRole.TERTIARY -> R.string.ap_custom_tertiary
    CustomColorRole.BACKGROUND -> R.string.ap_custom_background
}

private fun hex(color: Int) = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)
private fun parseHex(value: String): Int? = value.removePrefix("#")
    .takeIf { it.length == 6 && it.all { digit -> digit in '0'..'9' || digit in 'a'..'f' || digit in 'A'..'F' } }
    ?.toIntOrNull(16)?.or(0xFF000000.toInt())

@Composable
internal fun CustomThemePage(store: AppearanceStore, encoded: String, onDraft: (String) -> Unit,
    onEditColor: (Boolean, CustomColorRole) -> Unit, onSaved: () -> Unit) {
    val draft = remember(encoded) { CustomPalette.decode(encoded) }
    val currentDark = LocalAppearanceDark.current
    var dark by rememberSaveable { mutableStateOf(currentDark) }
    val scheme = remember(draft, dark) { customColorScheme(draft, dark) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("custom-theme-content"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ap_custom_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
            PrimaryTabRow(selectedTabIndex = if (dark) 1 else 0) {
                Tab(selected = !dark, onClick = { dark = false }, modifier = Modifier.testTag("custom-theme-light"),
                    text = { Text(stringResource(R.string.hs_light)) })
                Tab(selected = dark, onClick = { dark = true }, modifier = Modifier.testTag("custom-theme-dark"),
                    text = { Text(stringResource(R.string.hs_dark)) })
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("custom-theme-preview"),
                    shape = MaterialTheme.shapes.extraLarge, color = scheme.background, border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.ap_custom_preview), style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledIconButton(onClick = {}, colors = IconButtonDefaults.filledIconButtonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary)) {
                                Symbol(R.drawable.material_symbol_play_circle, tint = scheme.onPrimary,
                                    description = stringResource(R.string.ap_custom_primary))
                            }
                            listOf(scheme.secondary to scheme.onSecondary, scheme.tertiary to scheme.onTertiary).forEach { (color, onColor) ->
                                Box(Modifier.size(40.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                                    Symbol(R.drawable.material_symbol_check, tint = onColor)
                                }
                            }
                        }
                    }
                }
            }
            Column {
                CustomColorRole.entries.forEach { role ->
                    val color = draft.colors(dark).color(role)
                    ListItem(onClick = { onEditColor(dark, role) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().testTag("custom-color-${role.name}"),
                        shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        leadingContent = { Box(Modifier.size(40.dp).clip(CircleShape).background(Color(color))) },
                        supportingContent = { Text(hex(color), style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)) },
                    ) { Text(stringResource(role.label), style = MaterialTheme.typography.bodyLargeEmphasized) }
                }
            }
            TextButton(onClick = { onDraft(CustomPalette().encode()) },
                modifier = Modifier.padding(horizontal = 16.dp).testTag("custom-theme-reset")) {
                Text(stringResource(R.string.ap_custom_reset))
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(onClick = { store.customPalette(draft); onSaved() },
            modifier = Modifier.padding(16.dp).fillMaxWidth().testTag("custom-theme-save")) {
            Text(stringResource(R.string.ap_custom_save))
        }
    }
}

@Composable
internal fun ColorEditor(role: CustomColorRole, initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by rememberSaveable(role, initial) { mutableStateOf(hex(initial)) }
    var lastValid by rememberSaveable(role, initial) { mutableIntStateOf(initial) }
    val parsed = parseHex(text)
    val color = parsed ?: lastValid
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(56.dp).clip(MaterialTheme.shapes.large).background(Color(color)))
                OutlinedTextField(value = text, onValueChange = { value ->
                    text = value
                    parseHex(value)?.let { lastValid = it }
                }, modifier = Modifier.fillMaxWidth().testTag("custom-color-hex"),
                    label = { Text(stringResource(R.string.ap_custom_hex)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                    isError = parsed == null,
                    supportingText = { Text(stringResource(if (parsed == null) R.string.ap_custom_hex_error else R.string.ap_custom_hex_hint)) })
                listOf(R.string.ap_custom_red to 16, R.string.ap_custom_green to 8, R.string.ap_custom_blue to 0).forEach { (label, shift) ->
                    val name = stringResource(label)
                    Text(name, style = MaterialTheme.typography.labelLarge)
                    Slider(value = ((color ushr shift) and 255).toFloat(), onValueChange = { value ->
                        val updated = (color and (255 shl shift).inv()) or (value.roundToInt() shl shift)
                        lastValid = updated
                        text = hex(updated)
                    }, valueRange = 0f..255f, steps = 254,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = name })
                }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.hs_cancel)) }
            Button(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null,
                modifier = Modifier.testTag("custom-color-confirm")) { Text(stringResource(R.string.hs_done)) }
        }
    }
}
