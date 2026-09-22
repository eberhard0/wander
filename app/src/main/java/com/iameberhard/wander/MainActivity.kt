package com.iameberhard.wander

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WanderTheme { App(viewModel) } }
    }
}

@Composable
private fun WanderTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF3DDC97), onPrimary = Color(0xFF00382A))
    } else {
        lightColorScheme(primary = Color(0xFF00796B))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun App(vm: MainViewModel) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val dark = isSystemInDarkTheme()
    val session by MockService.session.collectAsStateWithLifecycle()

    val holder = remember {
        MapHolder(context, vm.places) { vm.select(Place("Dropped pin", it.lat, it.lon)) }
    }
    var mockAllowed by remember { mutableStateOf(MockService.isMockApp(context)) }
    var showPlaces by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<Place?>(null) }

    // Coming back from Developer options is the moment the answer can have changed.
    LifecycleResumeEffect(Unit) {
        mockAllowed = MockService.isMockApp(context)
        onPauseOrDispose { holder.saveCamera() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // Adding the observer replays start/resume, so the map comes up in step with the activity.
        lifecycle.addObserver(holder.lifecycleObserver)
        onDispose {
            lifecycle.removeObserver(holder.lifecycleObserver)
            holder.destroy()
        }
    }

    LaunchedEffect(dark) { holder.setDark(dark) }
    LaunchedEffect(vm.selection) { holder.showPin(vm.selection?.point) }
    LaunchedEffect(session.position) { holder.showMock(session.position) }
    val line = vm.preview?.points ?: session.route
    LaunchedEffect(line) { holder.showRoute(line, fit = vm.preview != null) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) showRealLocation(context, holder, vm)
    }

    fun go(place: Place) {
        focus.clearFocus()
        vm.select(place)
        holder.centreOn(place.point)
    }

    fun setFixed(place: Place) {
        // The "mock location active" notification is the only reminder once Wander is off screen.
        if (Build.VERSION.SDK_INT >= 33 && !context.has(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.setFixed(place)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { holder.view }, modifier = Modifier.fillMaxSize())

        Column(
            Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(12.dp)
                .widthIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchBar(
                vm,
                onSearch = {
                    focus.clearFocus()
                    vm.search()?.let { holder.centreOn(it.point) }
                },
                onPlaces = { showPlaces = true },
            )
            if (vm.results.isNotEmpty()) ResultList(vm.results, ::go)
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .safeDrawingPadding()
                .padding(12.dp)
                .widthIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    val mock = session.position
                    when {
                        mock != null -> holder.centreOn(mock)
                        context.has(Manifest.permission.ACCESS_COARSE_LOCATION) -> showRealLocation(context, holder, vm)
                        else -> locationPermission.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    }
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.Default.LocationOn, contentDescription = if (session.active) "Show mock location" else "Show my real location")
            }

            (vm.message ?: session.error)?.let { Notice(it) }

            val selection = vm.selection
            val preview = vm.preview
            val interrupted = vm.interrupted
            when {
                !mockAllowed -> SetupCard()
                else -> {
                    if (session.active) SessionCard(session, vm)
                    when {
                        preview != null -> PreviewCard(preview, vm)
                        selection != null -> SelectionCard(
                            place = selection,
                            sessionActive = session.active,
                            routing = vm.routing,
                            favourite = vm.isFavourite(selection),
                            onSet = { setFixed(selection) },
                            onWalk = { vm.planWalk(selection) },
                            onFavourite = { if (vm.isFavourite(selection)) vm.removeFavourite(selection) else naming = selection },
                            onClose = vm::clearSelection,
                        )
                        !session.active && interrupted != null -> InterruptedCard(interrupted, vm)
                        !session.active -> Notice("Tap the map, search for a place or type coordinates to choose a location.")
                    }
                }
            }

            Text(
                "© OpenFreeMap · © OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.End),
            )
        }
    }

    if (showPlaces) {
        PlacesSheet(vm, onPick = { showPlaces = false; go(it) }, onDismiss = { showPlaces = false })
    }
    naming?.let { place ->
        NameDialog(place, onSave = { vm.addFavourite(place, it); naming = null }, onDismiss = { naming = null })
    }
}

@Composable
private fun SearchBar(vm: MainViewModel, onSearch: () -> Unit, onPlaces: () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search, or type lat, lon", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    when {
                        vm.searching -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        vm.query.isNotEmpty() -> IconButton(onClick = vm::clearSearch) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            IconButton(onClick = onPlaces, modifier = Modifier.padding(end = 4.dp)) {
                Icon(Icons.Default.Star, contentDescription = "Favourites and history")
            }
        }
    }
}

@Composable
private fun ResultList(results: List<Place>, onPick: (Place) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Column(
            Modifier
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            results.forEachIndexed { index, place ->
                if (index > 0) HorizontalDivider()
                Text(
                    place.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(place) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Notice(text: String) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

@Composable
private fun SetupCard() {
    val context = LocalContext.current
    val developerMode = Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    Panel {
        Text("One-time setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (developerMode) {
            Text("In Developer options, scroll to \"Select mock location app\" and choose Wander. Then come back here.")
            Button(onClick = { context.open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open Developer options")
            }
        } else {
            Text(
                "1. Turn on Developer options: About phone → Software information → tap \"Build number\" 7 times.\n" +
                    "2. Come back here and Wander will take you to the next step.",
            )
            Button(onClick = { context.open(Settings.ACTION_DEVICE_INFO_SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open About phone")
            }
        }
    }
}

@Composable
private fun SelectionCard(
    place: Place,
    sessionActive: Boolean,
    routing: Boolean,
    favourite: Boolean,
    onSet: () -> Unit,
    onWalk: () -> Unit,
    onFavourite: () -> Unit,
    onClose: () -> Unit,
) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(place.point.label(), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onFavourite) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = if (favourite) "Remove favourite" else "Save as favourite",
                    tint = if (favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSet, modifier = Modifier.weight(1f)) { Text(if (sessionActive) "Jump here" else "Set location") }
            OutlinedButton(onClick = onWalk, enabled = sessionActive && !routing, modifier = Modifier.weight(1f)) {
                Text(if (routing) "Routing…" else "Walk here")
            }
        }
        if (!sessionActive) {
            Text("To simulate a walk, set a starting location first, then pick a destination.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PreviewCard(route: Route, vm: MainViewModel) {
    Panel {
        Text(if (route.straightLine) "Straight-line route" else "Walking route", style = MaterialTheme.typography.titleMedium)
        Text("${formatDistance(route.distance)} · about ${formatDuration(route.distance / vm.speed)} at ${formatSpeed(vm.speed)}")
        SpeedChips(vm)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::startWalk, modifier = Modifier.weight(1f)) { Text("Start walk") }
            OutlinedButton(onClick = vm::clearSelection, modifier = Modifier.weight(1f)) { Text("Cancel") }
        }
    }
}

@Composable
private fun SessionCard(session: Session, vm: MainViewModel) {
    val context = LocalContext.current
    Panel {
        val title = when {
            session.mode == Mode.FIXED -> "Fixed location"
            session.arrived -> if (session.reversed) "Back at the start" else "Arrived – holding position"
            session.paused -> "Walk paused"
            else -> "Walking"
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        session.position?.let { Text(it.label(), style = MaterialTheme.typography.bodySmall) }

        if (session.mode == Mode.WALKING) {
            LinearProgressIndicator(
                progress = { if (session.total > 0) (session.travelled / session.total).toFloat() else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${formatDistance(session.travelled)} of ${formatDistance(session.total)} · ${formatSpeed(session.speedMps)}",
                style = MaterialTheme.typography.bodySmall,
            )
            SpeedChips(vm)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { if (session.paused) MockService.resume(context) else MockService.pause(context) },
                    enabled = !session.arrived,
                    modifier = Modifier.weight(1f),
                ) { Text(if (session.paused) "Resume" else "Pause") }
                OutlinedButton(onClick = { MockService.reverse(context) }, modifier = Modifier.weight(1f)) { Text("Reverse") }
            }
        }

        Button(
            onClick = vm::restoreRealLocation,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Restore real location") }
    }
}

@Composable
private fun InterruptedCard(point: LatLon, vm: MainViewModel) {
    Panel {
        Text("Last session was interrupted", style = MaterialTheme.typography.titleMedium)
        Text("The real location is already back. Wander was last reporting ${point.label()}.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::resumeInterrupted, modifier = Modifier.weight(1f)) { Text("Resume there") }
            OutlinedButton(onClick = vm::dismissInterrupted, modifier = Modifier.weight(1f)) { Text("Dismiss") }
        }
    }
}

@Composable
private fun SpeedChips(vm: MainViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, mps) in Speeds.presets) {
            FilterChip(
                selected = kotlin.math.abs(vm.speed - mps) < 0.01,
                onClick = { vm.changeSpeed(mps) },
                label = { Text(label) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacesSheet(vm: MainViewModel, onPick: (Place) -> Unit, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Favourites") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History") })
        }
        val list = if (tab == 0) vm.favourites else vm.history
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 420.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            if (list.isEmpty()) {
                Text(
                    if (tab == 0) "Tap the star on a selected place to keep it here." else "Locations you use will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            }
            for (place in list) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(place) }
                        .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(place.point.label(), style = MaterialTheme.typography.bodySmall)
                    }
                    if (tab == 0) {
                        IconButton(onClick = { vm.removeFavourite(place) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove ${place.name}")
                        }
                    }
                }
            }
            if (tab == 1 && list.isNotEmpty()) {
                TextButton(onClick = vm::clearHistory, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Clear history") }
            }
        }
    }
}

@Composable
private fun NameDialog(place: Place, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(place.name.substringBefore(',')) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save favourite") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.open(settingsAction: String) {
    try {
        startActivity(Intent(settingsAction))
    } catch (_: Exception) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

/** Centres the map on the phone's real position (only offered while nothing is being mocked). */
@SuppressLint("MissingPermission")
private fun showRealLocation(context: Context, holder: MapHolder, vm: MainViewModel) {
    val manager = context.getSystemService(LocationManager::class.java)
    val last = manager.getProviders(true)
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
    if (last != null && System.currentTimeMillis() - last.time < 120_000) {
        return holder.centreOn(LatLon(last.latitude, last.longitude))
    }
    val provider = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    if (provider == null) return vm.show("Location is turned off.")
    vm.show("Finding your location…")
    manager.getCurrentLocation(provider, null, context.mainExecutor) { fresh ->
        val location = fresh ?: last
        if (location == null) {
            vm.show("Couldn't get a location fix.")
        } else {
            vm.show("Centred on your real location.")
            holder.centreOn(LatLon(location.latitude, location.longitude))
        }
    }
}
