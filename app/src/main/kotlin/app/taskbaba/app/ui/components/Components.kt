package app.taskbaba.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.taskbaba.app.ui.theme.Baba

@Composable
fun BabaCard(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Baba.colors.card,
        border = BorderStroke(1.dp, Baba.colors.cardBorder),
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.titleSmall, color = Baba.colors.textMuted, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall, color = Baba.colors.text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Baba.colors.textMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = if (filled) Color.White else color, maxLines = 1)
    }
}

enum class StatusTone { OK, WARN, ERROR, NEUTRAL, BUSY }

@Composable
fun toneColor(tone: StatusTone): Color = when (tone) {
    StatusTone.OK -> Baba.colors.success
    StatusTone.WARN -> Baba.colors.warning
    StatusTone.ERROR -> Baba.colors.danger
    StatusTone.BUSY -> Baba.colors.info
    StatusTone.NEUTRAL -> Baba.colors.textMuted
}

@Composable
fun StatusDot(tone: StatusTone, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(toneColor(tone)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Baba.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Baba.colors.saffron, contentColor = Baba.colors.onSaffron),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        border = BorderStroke(1.dp, if (danger) Baba.colors.danger.copy(alpha = 0.5f) else Baba.colors.cardBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) Baba.colors.danger else Baba.colors.text),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Baba.colors.saffron)
    }
}

/** A labelled settings row with a switch that directly drives behaviour. */
@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Baba.colors.text else Baba.colors.textMuted)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Baba.colors.textMuted)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Baba.colors.saffron, checkedThumbColor = Color.White),
        )
    }
}

@Composable
fun ErrorText(messages: List<String>) {
    if (messages.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        messages.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Baba.colors.danger) }
    }
}
