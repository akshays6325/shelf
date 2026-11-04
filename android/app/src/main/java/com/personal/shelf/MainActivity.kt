@file:OptIn(ExperimentalMaterial3Api::class)

package com.personal.shelf

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ShelfTheme { ShelfScreen() } }
    }
}

@Composable
fun ShelfTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF8AB4F8))
        else -> lightColorScheme(primary = Color(0xFF1A73E8))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private data class AppRow(val app: AppEntry, val installed: Packages.Installed?) {
    val hasUpdate get() = installed != null && app.versionCode > installed.versionCode
}

@Composable
fun ShelfScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(Catalog.cached(ctx)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }          // bumps to re-read installed versions
    var canInstall by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var lastChecked by remember { mutableStateOf(Prefs.lastChecked(ctx)) }
    val states by Installer.states.collectAsState()

    fun refresh() {
        if (loading) return
        loading = true
        scope.launch {
            try {
                apps = Catalog.fetch(ctx)
                error = null
                Prefs.setLastChecked(ctx, System.currentTimeMillis())
                lastChecked = Prefs.lastChecked(ctx)
                UpdateWorker.runUpdates(ctx, apps, background = false)
            } catch (e: Exception) {
                error = "Couldn't reach the catalog: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        refresh()
    }
    LaunchedEffect(Unit) { Installer.changed.collect { tick++ } }
    LaunchedEffect(loading) { canInstall = ctx.packageManager.canRequestPackageInstalls() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        tick++
        canInstall = ctx.packageManager.canRequestPackageInstalls()
    }

    val rows = remember(apps, tick) { apps.map { AppRow(it, Packages.installed(ctx, it.packageName)) } }
    val updates = rows.filter { it.hasUpdate }
    val installed = rows.filter { it.installed != null && !it.hasUpdate }
    val available = rows.filter { it.installed == null }

    fun install(app: AppEntry) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            openUnknownSources(ctx)
            return
        }
        scope.launch { Installer.install(ctx, app) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shelf", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (loading) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 2.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "Check for updates") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp,
                bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!canInstall) item {
                Notice("Allow Shelf to install apps",
                    "Android asks once before an app can install other apps.", "Allow") { openUnknownSources(ctx) }
            }
            error?.let { msg -> item { Notice("Offline", msg, "Retry") { refresh() } } }
            if (apps.isEmpty() && !loading && error == null) item {
                Text("No apps in the catalog yet.", Modifier.padding(top = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (updates.isNotEmpty()) {
                item {
                    SectionHeader("Updates · ${updates.size}") {
                        if (updates.size > 1) TextButton(onClick = {
                            updates.sortedBy { it.app.packageName == ctx.packageName }.forEach { install(it.app) }
                        }) { Text("Update all") }
                    }
                }
                items(updates, key = { "u" + it.app.id }) { AppCard(it, states[it.app.packageName], ::install) }
            }
            if (installed.isNotEmpty()) {
                item { SectionHeader("Installed") {} }
                items(installed, key = { "i" + it.app.id }) { AppCard(it, states[it.app.packageName], ::install) }
            }
            if (available.isNotEmpty()) {
                item { SectionHeader("Available") {} }
                items(available, key = { "a" + it.app.id }) { AppCard(it, states[it.app.packageName], ::install) }
            }
            if (lastChecked > 0) item {
                val ago = System.currentTimeMillis() - lastChecked
                Text("Checked " + if (ago < 60_000) "just now" else DateUtils.getRelativeTimeSpanString(lastChecked,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showSettings) SettingsDialog(onDismiss = { showSettings = false }, onFeedChanged = { apps = emptyList(); refresh() })
}

private fun openUnknownSources(ctx: Context) {
    ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
}

@Composable
private fun SectionHeader(title: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun Notice(title: String, body: String, action: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun AppCard(row: AppRow, state: InstallState?, onInstall: (AppEntry) -> Unit) {
    val ctx = LocalContext.current
    val app = row.app
    var expanded by rememberSaveable(app.id) { mutableStateOf(false) }
    val isSelf = app.packageName == ctx.packageName

    Card(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, row.installed != null)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = when {
                        row.hasUpdate -> "${row.installed?.versionName ?: "?"} → ${app.versionName}"
                        row.installed != null -> "Version ${row.installed.versionName ?: app.versionName}"
                        else -> "${app.versionName} · ${Formatter.formatShortFileSize(ctx, app.size)}"
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                    when (state) {
                        is InstallState.Downloading -> CircularProgressIndicator(
                            progress = { state.progress }, modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                        InstallState.Installing -> CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
                        else -> when {
                            row.hasUpdate -> Button(onClick = { onInstall(app) }) { Text("Update") }
                            row.installed == null -> Button(onClick = { onInstall(app) }) { Text("Install") }
                            isSelf -> Text("Up to date", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> OutlinedButton(onClick = {
                                ctx.packageManager.getLaunchIntentForPackage(app.packageName)?.let { ctx.startActivity(it) }
                            }) { Text("Open") }
                        }
                    }
                }
            }
            if (state is InstallState.Failed) {
                Text(state.message, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            if (app.description.isNotBlank()) {
                Text(app.description, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            }
            if (expanded) {
                if (app.changelog.isNotBlank()) {
                    Text("What's new in ${app.versionName}", Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.labelLarge)
                    Text(app.changelog, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(listOfNotNull(
                        Formatter.formatShortFileSize(ctx, app.size),
                        app.updatedAt.take(10).takeIf { it.isNotBlank() }?.let { "released $it" },
                    ).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (row.installed != null && !isSelf) TextButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
                    }) { Text("Uninstall") }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(app: AppEntry, installed: Boolean) {
    val ctx = LocalContext.current
    val mod = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
    val local = remember(app.packageName, installed) {
        if (!installed) null else runCatching {
            ctx.packageManager.getApplicationIcon(app.packageName).toBitmap(144, 144).asImageBitmap()
        }.getOrNull()
    }
    when {
        local != null -> Image(local, null, mod)
        app.iconUrl != null -> AsyncImage(model = app.iconUrl, contentDescription = null, modifier = mod)
        else -> Box(mod.background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(app.name.take(1), style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit, onFeedChanged: () -> Unit) {
    val ctx = LocalContext.current
    var auto by remember { mutableStateOf(Prefs.autoUpdate(ctx)) }
    val original = remember { Prefs.feedUrl(ctx) }
    var feed by remember { mutableStateOf(original) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Update automatically", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (Build.VERSION.SDK_INT >= 31) "Apps installed through Shelf update in the background, no prompt."
                            else "Needs Android 12 or newer. You'll get a notification instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = auto, onCheckedChange = { auto = it; Prefs.setAutoUpdate(ctx, it) })
                }
                OutlinedTextField(value = feed, onValueChange = { feed = it }, label = { Text("Catalog URL") },
                    singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { feed = BuildConfig.FEED_URL }) { Text("Reset to default") }
                Text("Shelf ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (feed.trim() != original) {
                    Prefs.setFeedUrl(ctx, feed.takeIf { it.trim() != BuildConfig.FEED_URL })
                    onFeedChanged()
                }
                onDismiss()
            }) { Text("Done") }
        },
    )
}
