@file:OptIn(ExperimentalMaterial3Api::class)

package com.personal.shelf

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private val StarColor = Color(0xFFF5B400)

@Composable
fun Stars(rating: Double, size: Dp = 14.dp) {
    Row {
        for (i in 1..5) {
            val fill = rating >= i - 0.25
            Icon(Icons.Default.Star, null, Modifier.size(size),
                tint = if (fill) StarColor else MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
fun StarPicker(selected: Int, size: Dp = 36.dp, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 1..5) {
            Icon(Icons.Default.Star, "$i stars",
                Modifier.size(size).clip(CircleShape).clickable { onSelect(i) },
                tint = if (i <= selected) StarColor else MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
fun Avatar(name: String, picture: String?, size: Dp) {
    val mod = Modifier.size(size).clip(CircleShape)
    if (picture != null) AsyncImage(model = picture, contentDescription = null, modifier = mod)
    else Box(mod.background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = MaterialTheme.colorScheme.onTertiaryContainer,
            style = if (size > 40.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AccountButton(onClick: () -> Unit) {
    val user by Account.user.collectAsState()
    IconButton(onClick = onClick) {
        val u = user
        if (u == null) Icon(Icons.Default.AccountCircle, "Sign in")
        else Avatar(u.name, u.picture, 28.dp)
    }
}

/** Ratings and reviews inside an expanded app card. */
@Composable
fun ReviewsSection(app: AppEntry, installed: Packages.Installed?, onNeedSignIn: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val user by Account.user.collectAsState()
    var data by remember(app.id) { mutableStateOf<AppReviews?>(null) }
    var error by remember(app.id) { mutableStateOf<String?>(null) }
    var reload by remember(app.id) { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Int?>(null) }     // star count the editor opens with
    var showAll by remember { mutableStateOf(false) }

    LaunchedEffect(app.id, reload, user?.id) {
        try {
            data = ReviewsApi.reviews(ctx, app.id); error = null
        } catch (e: Exception) {
            error = "Couldn't load reviews"
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        HorizontalDivider()
        Text("Ratings & reviews", Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.titleSmall)
        val d = data
        when {
            d == null && error == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            d == null -> Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else -> {
                if (d.count > 0) Summary(d)
                val mine = d.mine
                if (installed != null && mine == null) {
                    Text(if (user == null) "Sign in to rate this app" else "Rate this app",
                        Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    StarPicker(0, 34.dp) { if (user == null) onNeedSignIn() else editing = it }
                } else if (installed == null && mine == null && d.count == 0) {
                    Text("No reviews yet. Install the app to rate it.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                d.reviews.take(3).forEach { r ->
                    ReviewItem(r, onEdit = if (r.mine) ({ editing = r.rating }) else null)
                }
                if (d.reviews.size > 3) TextButton(onClick = { showAll = true }) { Text("See all ${d.count} reviews") }
            }
        }
    }

    editing?.let { stars ->
        ReviewDialog(app, installed, data?.mine, stars,
            onDismiss = { editing = null },
            onSaved = { editing = null; reload++; onChanged() })
    }
    if (showAll) data?.let { d ->
        ModalBottomSheet(onDismissRequest = { showAll = false }) {
            Text("${app.name} reviews", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
                item { Summary(d) }
                items(d.reviews, key = { it.id }) { ReviewItem(it, onEdit = null) }
            }
        }
    }
}

@Composable
private fun Summary(d: AppReviews) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("%.1f".format(d.average), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Medium)
            Stars(d.average, 14.dp)
            Text("${d.count} ${if (d.count == 1) "review" else "reviews"}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            val max = (d.histogram.maxOrNull() ?: 0).coerceAtLeast(1)
            for (star in 5 downTo 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$star", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(12.dp))
                    Box(Modifier.weight(1f).padding(vertical = 2.dp).heightIn(min = 6.dp, max = 6.dp)
                        .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        val frac = d.histogram.getOrElse(star - 1) { 0 }.toFloat() / max
                        if (frac > 0) Box(Modifier.fillMaxWidth(frac).heightIn(min = 6.dp, max = 6.dp).background(StarColor))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewItem(r: Review, onEdit: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(r.authorName, r.authorPicture, 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(if (r.mine) "${r.authorName} (you)" else r.authorName, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f))
            if (onEdit != null) TextButton(onClick = onEdit) { Text("Edit") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Stars(r.rating.toDouble(), 12.dp)
            Spacer(Modifier.width(8.dp))
            Text(listOfNotNull(r.updatedAt.take(10), r.versionName?.let { "v$it" }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (r.body.isNotBlank()) Text(r.body, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
        r.reply?.let {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text("Developer reply", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ReviewDialog(
    app: AppEntry, installed: Packages.Installed?, existing: Review?, initialStars: Int,
    onDismiss: () -> Unit, onSaved: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var stars by remember { mutableIntStateOf(initialStars) }
    var body by remember { mutableStateOf(existing?.body ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun act(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { block(); onSaved() } catch (e: Exception) { error = e.message ?: "Something went wrong" } finally { busy = false }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Rate ${app.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StarPicker(stars, 40.dp) { stars = it }
                OutlinedTextField(
                    value = body, onValueChange = { body = it.take(2000) },
                    label = { Text("What do you think? Bugs, ideas, anything") },
                    minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                Text("Posted publicly with your Google name and photo. The developer sees your device and app version.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = stars > 0 && !busy, onClick = { act { ReviewsApi.saveReview(ctx, app.id, stars, body.trim(), installed) } }) {
                Text(if (existing == null) "Post" else "Update")
            }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(enabled = !busy, onClick = { act { ReviewsApi.deleteReview(ctx, app.id) } }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Sign-in and profile: name, photo, your reviews, sign out. */
@Composable
fun ProfileSheet(apps: List<AppEntry>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val user by Account.user.collectAsState()
    var config by remember { mutableStateOf<ServerConfig?>(null) }
    var mine by remember { mutableStateOf<List<Review>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(user?.id) {
        if (user == null) runCatching { config = ReviewsApi.config(ctx) }
        else runCatching { mine = ReviewsApi.myReviews(ctx) }.onFailure { error = it.message }
    }

    fun act(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { block() } catch (e: Exception) { error = e.message ?: "Something went wrong" } finally { busy = false }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val u = user
            if (u == null) {
                Text("Your Shelf profile", style = MaterialTheme.typography.titleLarge)
                Text("Sign in to rate apps, write reviews and get replies from the developer.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val googleReady = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()
                Button(enabled = googleReady && !busy, modifier = Modifier.fillMaxWidth(),
                    onClick = { act { ReviewsApi.signInWithGoogle(ctx as Activity) } }) {
                    Text("Sign in with Google")
                }
                if (!googleReady) Text("Google sign-in isn't set up in this build yet.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (config?.devLogin == true) OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    onClick = { act { ReviewsApi.signInDev(ctx) } }) { Text("Test sign-in (dev server)") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(u.name, u.picture, 56.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(u.name, style = MaterialTheme.typography.titleLarge)
                        Text(u.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Your reviews", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                when (val list = mine) {
                    null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> if (list.isEmpty()) Text("None yet. Open an app you use and rate it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else list.forEach { r ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(apps.firstOrNull { it.id == r.appId }?.name ?: r.appId,
                                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                Stars(r.rating.toDouble(), 12.dp)
                            }
                            if (r.body.isNotBlank()) Text(r.body, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            r.reply?.let { Text("Developer: $it", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
                Row {
                    OutlinedButton(enabled = !busy, onClick = { act { ReviewsApi.signOut(ctx) } }) { Text("Sign out") }
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = !busy, onClick = { confirmDelete = true }) {
                        Text("Delete account", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete your account?") },
        text = { Text("Your profile and all your reviews are removed. This can't be undone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; act { ReviewsApi.deleteAccount(ctx) } }) {
            Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
