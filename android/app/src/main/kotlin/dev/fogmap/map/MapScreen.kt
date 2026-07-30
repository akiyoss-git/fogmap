package dev.fogmap.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.fogmap.FogStore
import dev.fogmap.R
import dev.fogmap.core.routing.RevealedLookup
import dev.fogmap.core.routing.RoutePoint
import dev.fogmap.core.routing.clipRoute
import dev.fogmap.data.RoutingClient
import dev.fogmap.data.api.SocialRepository
import dev.fogmap.data.api.SyncRepository
import dev.fogmap.social.SocialSheet
import dev.fogmap.sync.AuthDialog
import dev.fogmap.sync.Sync
import dev.fogmap.tracking.Tracking
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Стиль OpenStreetMap от OpenFreeMap: без ключа и без лимитов.
 * Публичный tile-сервер OSM использовать нельзя — см. инвариант 1 в CLAUDE.md.
 */
private const val STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"

private val INITIAL_TARGET = LatLng(55.7558, 37.6173)
private const val INITIAL_ZOOM = 15.0

/** Позиция тумана среди детей MapView: над GL-поверхностью, под логотипом и атрибуцией. */
private const val FOG_CHILD_INDEX = 1

@Composable
fun MapScreen(
    store: FogStore,
    routingClient: RoutingClient,
    syncRepository: SyncRepository,
    socialRepository: SocialRepository,
) {
    val context = LocalContext.current
    val overlay = remember { FogOverlayView(context) }
    val area by store.areaM2.collectAsState()
    val tracking by Tracking.running.collectAsState()
    var permissionDenied by remember { mutableStateOf(false) }
    var showAuth by remember { mutableStateOf(false) }
    var showSocial by remember { mutableStateOf(false) }
    var authenticated by remember { mutableStateOf(syncRepository.isAuthenticated) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(authenticated) {
        if (authenticated) Sync.schedulePeriodic(context)
    }

    val routeGeometry = remember { mutableStateOf<List<RoutePoint>>(emptyList()) }
    val destination = remember { mutableStateOf<RoutePoint?>(null) }
    val routeFailed = remember { mutableStateOf(false) }

    LaunchedEffect(store) { store.load() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            permissionDenied = false
            Tracking.start(context)
        } else {
            permissionDenied = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        MapLibreView(
            modifier = Modifier.fillMaxSize(),
            onMapReady = { mapView, map ->
                // Оверлей добавляется ребёнком MapView, а не соседом в Compose: тогда тап, который
                // оверлей не обработал, доходит до карты по обычным правилам ViewGroup. И только
                // после готовности карты — иначе GL-поверхность легла бы поверх тумана.
                //
                // Индекс 1, а не конец списка: сразу над GL-поверхностью, но под логотипом и
                // атрибуцией MapLibre. Туман непрозрачный и иначе скрыл бы их, а атрибуция OSM
                // обязательна по ODbL.
                if (overlay.parent == null) {
                    mapView.addView(
                        overlay,
                        FOG_CHILD_INDEX,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }

                map.setStyle(Style.Builder().fromUri(STYLE_URI))
                map.cameraPosition = CameraPosition.Builder()
                    .target(INITIAL_TARGET)
                    .zoom(INITIAL_ZOOM)
                    .build()

                // Оверлей считает тайл прямоугольником на экране, поэтому поворот и наклон
                // выключены — иначе туман разъедется с картой.
                map.uiSettings.setRotateGesturesEnabled(false)
                map.uiSettings.setTiltGesturesEnabled(false)

                overlay.attach(map, store.mask)
                map.addOnCameraMoveListener { overlay.invalidate() }
                map.addOnCameraIdleListener { overlay.invalidate() }
                map.addOnMapClickListener { point ->
                    store.reveal(point.latitude, point.longitude)
                    true
                }
                map.addOnMapLongClickListener { point ->
                    // Старт — последняя позиция трекера; если он не работал, центр карты.
                    val origin = Tracking.lastFix.value?.let { RoutePoint(it.lat, it.lon) }
                        ?: map.cameraPosition.target?.let { RoutePoint(it.latitude, it.longitude) }
                    val target = RoutePoint(point.latitude, point.longitude)
                    if (origin != null) {
                        scope.launch {
                            val geometry = routingClient.route(origin, target)
                            routeFailed.value = geometry == null
                            routeGeometry.value = geometry.orEmpty()
                            destination.value = if (geometry == null) null else target
                        }
                    }
                    true
                }
            },
        )

        AreaBadge(
            areaM2 = area,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        )

        Hint(
            text = when {
                // Маршруты считает наш сервер, поэтому без входа их просто нет — и сказать об
                // этом надо прямо, а не общим «не построен».
                routeFailed.value && !authenticated -> stringResource(R.string.route_needs_login)
                routeFailed.value -> stringResource(R.string.route_failed)
                routeGeometry.value.isEmpty() -> stringResource(R.string.route_hint)
                else -> null
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 64.dp),
        )

        if (authenticated) {
            Button(
                onClick = { showSocial = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) {
                Text(stringResource(R.string.social))
            }
        }

        BottomBar(
            tracking = tracking,
            authenticated = authenticated,
            permissionDenied = permissionDenied,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
            onTrackingClick = {
                if (tracking) {
                    Tracking.stop(context)
                } else if (hasLocationPermission(context)) {
                    Tracking.start(context)
                } else {
                    permissionLauncher.launch(requiredPermissions())
                }
            },
            onSyncClick = {
                if (authenticated) Sync.syncNow(context) else showAuth = true
            },
        )
    }

    if (showAuth) {
        AuthDialog(
            syncRepository = syncRepository,
            onAuthenticated = {
                authenticated = true
                showAuth = false
                Sync.syncNow(context)
            },
            onDismiss = { showAuth = false },
        )
    }

    if (showSocial) {
        SocialSheet(
            repository = socialRepository,
            onDeleteAccount = {
                syncRepository.deleteAccount()
                store.wipe()
                authenticated = false
            },
            onDismiss = { showSocial = false },
        )
    }

    LaunchedEffect(overlay) {
        store.changedTiles.collect { overlay.onTilesChanged(it) }
    }

    // Маршрут пересекается заново при каждом изменении маски: пока идёшь, скрытые участки
    // впереди становятся открытыми, и линия должна за этим успевать.
    LaunchedEffect(overlay, routeGeometry.value) {
        val geometry = routeGeometry.value
        if (geometry.isEmpty()) {
            overlay.setRoute(emptyList(), null)
            return@LaunchedEffect
        }
        val lookup = RevealedLookup { lat, lon -> store.mask.isRevealed(lat, lon) }
        overlay.setRoute(clipRoute(geometry, lookup), destination.value)
        store.changedTiles.collect {
            overlay.setRoute(clipRoute(geometry, lookup), destination.value)
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    // До Android 13 уведомления разрешения не требуют, а запрос несуществующего падает.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

@Composable
private fun BottomBar(
    tracking: Boolean,
    authenticated: Boolean,
    permissionDenied: Boolean,
    onTrackingClick: () -> Unit,
    onSyncClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onTrackingClick) {
                Text(
                    stringResource(
                        if (tracking) R.string.tracking_stop else R.string.tracking_start,
                    ),
                )
            }
            Button(onClick = onSyncClick) {
                Text(
                    stringResource(
                        if (authenticated) R.string.sync_now else R.string.sync_sign_in,
                    ),
                )
            }
        }
        if (permissionDenied) {
            Text(
                text = stringResource(R.string.tracking_permission_needed),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(bottom = 56.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Hint(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Text(
        text = text,
        color = Color.White,
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun AreaBadge(areaM2: Double, modifier: Modifier = Modifier) {
    val text = if (areaM2 < 1_000_000) {
        "%,.0f м²".format(areaM2)
    } else {
        "%.2f км²".format(areaM2 / 1_000_000)
    }
    Text(
        text = text,
        color = Color.White,
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun MapLibreView(
    modifier: Modifier,
    onMapReady: (MapView, MapLibreMap) -> Unit,
) {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnMapReady by rememberUpdatedState(onMapReady)

    // Порядок важен: наблюдатель за жизненным циклом объявлен до LaunchedEffect, поэтому
    // onCreate успевает пройти до getMapAsync.
    DisposableEffect(mapView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { currentOnMapReady(mapView, it) }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
