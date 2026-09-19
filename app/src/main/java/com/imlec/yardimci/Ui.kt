package com.imlec.yardimci

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.roundToInt

@Composable
fun ImlecTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    var a11yOk by remember { mutableStateOf(Perms.accessibility(ctx)) }
    var notifOk by remember { mutableStateOf(Perms.notifications(ctx)) }
    var enabled by remember { mutableStateOf(Prefs.enabled(ctx)) }
    var sizeDp by remember { mutableStateOf(Prefs.sizeDp(ctx)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var test by rememberSaveable { mutableStateOf("") }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                a11yOk = Perms.accessibility(ctx)
                notifOk = Perms.notifications(ctx)
                enabled = Prefs.enabled(ctx)
                sizeDp = Prefs.sizeDp(ctx)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifOk = granted
    }

    // Acilista bildirim iznini iste (Android 13+)
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            if (!notifOk) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("İmleç yardımcısı", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Metin kutusuna odaklanınca imlecin yanında ◀ ▶ oklar çıkar.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val active = a11yOk && enabled
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (active) Icons.Default.CheckCircle else Icons.Default.Info, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (active) "Oklar aktif" else if (!a11yOk) "Erişilebilirlik izni gerekli" else "Duraklatıldı",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (active) "Herhangi bir uygulamada metin kutusuna dokun" else "Aşağıdan izni ver veya anahtarı aç",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { v ->
                        enabled = v
                        Prefs.setEnabled(ctx, v)
                        CursorAccessibilityService.instance?.onSettingsChanged()
                    }
                )
            }
        }

        Text(
            "İzinler",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PermissionCard(
            title = "Erişilebilirlik hizmeti",
            subtitle = "Odaktaki metin kutusunu görmek ve imleci kaydırmak için gerekli",
            granted = a11yOk,
            actionLabel = "Aç",
            onAction = { Perms.openAccessibilitySettings(ctx) }
        )
        if (!a11yOk) {
            Text(
                "APK ile kurulduysa Android 13+ anahtarı gri tutabilir: Ayarlar > Uygulamalar > İmleç yardımcısı > sağ üst ⋮ > Kısıtlı ayarlara izin ver.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        PermissionCard(
            title = "Bildirimler",
            subtitle = "Bildirimden okları duraklatmak / devam ettirmek için",
            granted = notifOk,
            actionLabel = "İzin ver",
            onAction = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        )

        Text(
            "Ok boyutu: $sizeDp dp",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = sizeDp.toFloat(),
            onValueChange = { v -> sizeDp = v.roundToInt() },
            onValueChangeFinished = {
                Prefs.setSizeDp(ctx, sizeDp)
                CursorAccessibilityService.instance?.onSettingsChanged()
            },
            valueRange = 36f..60f,
            steps = 5
        )

        Text(
            "Burada dene",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = test,
            onValueChange = { test = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Bir şeyler yaz, sonra imleci oklarla gezdir") },
            minLines = 3
        )
        Text(
            "◀ bir karakter sola, ▶ bir karakter sağa. Basılı tutarsan hızlanır.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (!a11yOk && !dismissed) {
        AlertDialog(
            onDismissRequest = { dismissed = true },
            title = { Text("Erişilebilirlik izni gerekli") },
            text = {
                Text("Oklar için erişilebilirlik ayarlarında İmleç yardımcısı'nı aç. Yalnızca imleç konumunu hesaplamak için odaktaki kutuya bakar; hiçbir şey kaydedilmez ve cihaz dışına gönderilmez (uygulamada internet izni yok).")
            },
            confirmButton = {
                TextButton(onClick = {
                    dismissed = true
                    Perms.openAccessibilitySettings(ctx)
                }) { Text("Ayarları aç") }
            },
            dismissButton = {
                TextButton(onClick = { dismissed = true }) { Text("Sonra") }
            }
        )
    }
}

@Composable
private fun PermissionCard(
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(if (granted) "Verildi" else subtitle, style = MaterialTheme.typography.bodySmall)
            }
            if (!granted) {
                FilledTonalButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
