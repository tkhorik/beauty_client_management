package com.beauty.app.ui.client

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.R
import com.beauty.app.ui.theme.RoseGoldPrimary
import com.beauty.app.ui.theme.TextLight
import com.beauty.app.ui.theme.TextMuted

private val TileBorder = Color(0x33E5B899)

/**
 * One editable custom attribute, drawn like the read-only attribute tile: a
 * small name above a bold value, both edited in place, with a remove button.
 */
@Composable
fun AttributeTile(
    name: String,
    value: String,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val nameInteraction = remember { MutableInteractionSource() }
    val valueInteraction = remember { MutableInteractionSource() }
    val nameFocused by nameInteraction.collectIsFocusedAsState()
    val valueFocused by valueInteraction.collectIsFocusedAsState()
    val nameLabel = stringResource(R.string.attribute)
    val valueLabel = stringResource(R.string.value_label)

    Row(
        modifier
            .fillMaxWidth()
            .border(1.dp, if (nameFocused || valueFocused) RoseGoldPrimary else TileBorder, RoundedCornerShape(10.dp))
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            InlineField(
                text = name,
                onChange = onNameChange,
                placeholder = nameLabel,
                enabled = enabled,
                interaction = nameInteraction,
                style = TextStyle(color = TextMuted, fontSize = 12.sp, letterSpacing = 0.5.sp)
            )
            InlineField(
                text = value,
                onChange = onValueChange,
                placeholder = valueLabel,
                enabled = enabled,
                interaction = valueInteraction,
                style = TextStyle(color = TextLight, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        IconButton(enabled = enabled, onClick = onRemove) {
            Icon(
                Icons.Default.Delete,
                stringResource(R.string.remove_attribute, name.ifBlank { valueLabel }),
                tint = Color(0xFFF87171)
            )
        }
    }
}

@Composable
private fun InlineField(
    text: String,
    onChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    interaction: MutableInteractionSource,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = text,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(RoseGoldPrimary),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box {
                if (text.isEmpty()) Text(placeholder, style = style.copy(color = TextMuted.copy(alpha = 0.6f), fontWeight = FontWeight.Normal))
                inner()
            }
        }
    )
}
