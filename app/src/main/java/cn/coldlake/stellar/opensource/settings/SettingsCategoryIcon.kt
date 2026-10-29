package cn.coldlake.stellar.opensource.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.coldlake.stellar.opensource.appearance.LocalAppearanceDark

/** Fixed category hues, inspired by Pixel Settings; the surrounding row follows the app theme. */
internal enum class SettingsIconColor(val light: Color, val dark: Color, val foreground: Color) {
    BLUE(Color(0xFFD3E3FD), Color(0xFFA8C7FA), Color(0xFF0842A0)),
    CYAN(Color(0xFFC2E7FF), Color(0xFF5FD4F8), Color(0xFF004D65)),
    GREEN(Color(0xFFCEEAD6), Color(0xFFA8DAB5), Color(0xFF0F5223)),
    ORANGE(Color(0xFFFFE0C2), Color(0xFFFFB77C), Color(0xFF713600)),
    PURPLE(Color(0xFFEADDFF), Color(0xFFD0BCFF), Color(0xFF4F378B)),
    PINK(Color(0xFFFFD8ED), Color(0xFFFFAFE3), Color(0xFF87005B)),
}

@Composable
internal fun SettingsCategoryIcon(@DrawableRes icon: Int, color: SettingsIconColor, enabled: Boolean = true,
    preserveIconColors: Boolean = false) {
    Box(
        modifier = Modifier.size(40.dp).background(
            if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)
            else if (LocalAppearanceDark.current) color.dark else color.light,
            CircleShape,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(icon, tint = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
            else if (preserveIconColors) Color.Unspecified else color.foreground)
    }
}
