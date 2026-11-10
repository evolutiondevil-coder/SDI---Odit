package com.odit.obd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odit.obd.obd.DtcKind

object Palette {
    val Background = Color(0xFF0F1419)
    val Surface = Color(0xFF18212B)
    val SurfaceHigh = Color(0xFF223040)
    val Primary = Color(0xFF4FC3F7)
    val Ok = Color(0xFF66BB6A)
    val Warn = Color(0xFFFFB300)
    val Bad = Color(0xFFEF5350)
    val Muted = Color(0xFF9AA7B4)
}

@Composable
fun OditTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Palette.Primary,
        onPrimary = Color(0xFF002233),
        secondary = Palette.Warn,
        background = Palette.Background,
        onBackground = Color(0xFFE6EDF3),
        surface = Palette.Surface,
        onSurface = Color(0xFFE6EDF3),
        surfaceVariant = Palette.SurfaceHigh,
        onSurfaceVariant = Color(0xFFC5D0DA),
        surfaceContainer = Palette.Surface,
        surfaceContainerHigh = Palette.SurfaceHigh,
        error = Palette.Bad,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Standard page: top bar with back arrow, busy bar and snackbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    busy: String?,
    snackbar: SnackbarHostState,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = Palette.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                            }
                        }
                    },
                    actions = { actions() },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Background),
                )
                if (busy != null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        busy,
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
    ) { padding -> content(padding) }
}

@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    color: Color = Palette.Surface,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = color),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp)) {
        Text(
            text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(key, color = Palette.Muted, modifier = Modifier.width(130.dp), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = Palette.Primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
fun CenteredLoading(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(text, color = Palette.Muted)
        }
    }
}

/** "1 грешка", "2 грешки". */
fun errorsText(n: Int): String = if (n == 1) "1 грешка" else "$n грешки"

fun kindColor(kind: DtcKind): Color = when (kind) {
    DtcKind.ACTIVE, DtcKind.STORED -> Palette.Bad
    DtcKind.PENDING -> Palette.Warn
    DtcKind.PERMANENT -> Color(0xFFCE93D8)
    DtcKind.HISTORY -> Palette.Muted
}
