package com.tunombre.tvbridge.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Mismos colores y formas que la app de TV (ver res/values/colors.xml y res/drawable/bg_*.xml de la TV). */
object Wb {
    val Card = Color(0xFF171B24)
    val CardBorder = Color(0xFF262B36)
    val Accent = Color(0xFF5B8DEF)
    val TabFill = Color(0x335B8DEF)
    val NavSelectedFill = Color(0x335B8DEF)
    val ButtonFill = Color(0x2AFFFFFF)
    val Coral = Color(0xFFFF7A59)
    val Live = Color(0xFFFF4D4F)
    val IconTint = Color(0xFF9AA1AC)
    val TextSecondary = Color(0xFF9AA1AC)
    val TextTertiary = Color(0xFF6B7280)
    val Danger = Color(0xFFE5484D)
}

/** True cuando la pantalla es ancha (tablet / movil en horizontal): se usa la barra lateral de la TV en vez de la de abajo. */
val LocalWideNav = compositionLocalOf { false }

/** Tarjeta de la TV: fondo `surface_card`, esquinas de 16dp y un borde fino. */
fun Modifier.wbCard(radius: Int = 16): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    return this.clip(shape).background(Wb.Card).border(1.dp, Wb.CardBorder, shape)
}

/** Boton neutro de la TV (`bg_button_default`): relleno blanco translucido, 14dp y borde fino. */
@Composable
fun WbButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = Color.White,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Wb.ButtonFill, contentColor = contentColor, disabledContainerColor = Wb.ButtonFill.copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, Wb.CardBorder),
        content = content
    )
}

/** Boton "pastilla" con borde azul (como el de Genero de la TV). */
@Composable
fun WbPillButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.5.dp, Wb.Accent),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        content = content
    )
}

/** Pestana en forma de pastilla de la TV (`bg_iptv_tab`): con borde azul y, la elegida, rellena de azul. */
@Composable
fun WbTab(selected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Text(
        label,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(if (selected) Wb.Accent else Wb.TabFill)
            .then(if (selected) Modifier else Modifier.border(1.5.dp, Wb.Accent, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** Fila de ajustes de la TV (`item_setting_row`): tarjeta con icono, titulo, valor o pastilla y flecha. */
@Composable
fun SettingRow(
    iconRes: Int,
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    subtitle: String? = null,
    chevron: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .wbCard()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 60.dp)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = if (danger) Wb.Danger else Wb.IconTint, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, color = if (danger) Wb.Danger else Color.White, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = Wb.TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) {
            Text(value, color = Wb.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).width(110.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        if (chevron) Icon(painterResource(R.drawable.ic_set_chevron), contentDescription = null, tint = Wb.TextTertiary, modifier = Modifier.padding(start = 6.dp).size(22.dp))
    }
}

/** Quita el emoji del principio de textos como "🎁 Referrals" (los botones antiguos lo llevaban en el texto). */
fun String.withoutLeadingEmoji(): String = dropWhile { !it.isLetterOrDigit() }.ifBlank { this }

val WbRowSpacing = Arrangement.spacedBy(8.dp)
val WbScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
