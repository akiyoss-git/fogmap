package dev.fogmap.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import android.view.View
import dev.fogmap.core.fog.CELL_BITS
import dev.fogmap.core.fog.FogMask
import dev.fogmap.core.fog.TileId
import dev.fogmap.core.fog.TileMath
import dev.fogmap.core.routing.RoutePoint
import dev.fogmap.core.routing.RouteSegment
import dev.fogmap.core.routing.SegmentKind
import dev.fogmap.core.routing.distanceM
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * Туман поверх карты.
 *
 * Заливает всё облачной текстурой и вычитает из неё вскрытое: каждый тайл маски превращается в
 * битмап 256×256 с мягкой границей, который накладывается режимом DST_OUT на прямоугольник тайла
 * на экране. Как именно получается мягкая рваная граница — см. [FogTexture].
 *
 * Ограничения этого подхода (см. docs/ARCHITECTURE.md §3 — на M1 их и надо замерить):
 * - прямоугольник тайла считается по двум углам, поэтому поворот и наклон карты сломали бы
 *   геометрию; в [MapScreen] оба жеста выключены;
 * - при мелком зуме видимых тайлов слишком много, тогда рисуется сплошной туман без дырок.
 */
@SuppressLint("ViewConstructor")
class FogOverlayView(context: Context) : View(context) {

    private var map: MapLibreMap? = null
    private var mask: FogMask? = null

    private val fogPaint = Paint()
    private val holePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        isFilterBitmap = true
    }
    private val tileRect = RectF()

    private val bitmaps = object : LruCache<TileId, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: TileId, value: Bitmap): Int = value.byteCount
    }

    private var routeSegments: List<RouteSegment> = emptyList()
    private var destination: RoutePoint? = null

    private val routePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ROUTE_COLOR
    }
    private val crumbPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = ROUTE_COLOR
    }
    private val destinationPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = ROUTE_COLOR
    }
    private val destinationRingPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = 0xFFFFFFFF.toInt()
    }
    private val routePath = Path()

    init {
        fogPaint.shader = BitmapShader(
            FogTexture.cloudTile(CLOUD_TILE_PX),
            Shader.TileMode.REPEAT,
            Shader.TileMode.REPEAT,
        )
    }

    fun attach(map: MapLibreMap, mask: FogMask) {
        this.map = map
        this.mask = mask
        invalidate()
    }

    /**
     * Сбрасывает кэш изменившихся тайлов и перерисовывает. Соседи тоже сбрасываются: размытие
     * границы читает ячейки за краем тайла, поэтому вскрытие у границы меняет и картинку соседа.
     * Пересчёт при этом ленивый — в [drawHoles] строятся только видимые тайлы.
     */
    fun onTilesChanged(ids: Set<TileId>) {
        ids.forEach { id ->
            for (dy in -1..1) {
                for (dx in -1..1) bitmaps.remove(TileId(id.x + dx, id.y + dy))
            }
        }
        invalidate()
    }

    /** Маршрут уже разрезан на открытые и скрытые участки — см. `core:routing`. */
    fun setRoute(segments: List<RouteSegment>, destination: RoutePoint?) {
        this.routeSegments = segments
        this.destination = destination
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val map = map ?: return
        val mask = mask ?: return

        // DST_OUT должен вычитать из слоя оверлея, а не из содержимого окна: без saveLayer
        // дырки не появятся.
        val layer = canvas.saveLayer(null, null)
        canvas.drawPaint(fogPaint)
        drawHoles(canvas, map, mask)
        canvas.restoreToCount(layer)

        // Маршрут поверх тумана, а не слоем карты: крошки в тумане иначе были бы им закрыты.
        drawRoute(canvas, map)
    }

    private fun drawRoute(canvas: Canvas, map: MapLibreMap) {
        if (routeSegments.isEmpty() && destination == null) return
        val projection = map.projection

        for (segment in routeSegments) {
            when (segment.kind) {
                SegmentKind.REVEALED -> {
                    routePath.rewind()
                    for ((i, point) in segment.points.withIndex()) {
                        val screen = projection.toScreenLocation(LatLng(point.lat, point.lon))
                        if (i == 0) routePath.moveTo(screen.x, screen.y)
                        else routePath.lineTo(screen.x, screen.y)
                    }
                    canvas.drawPath(routePath, routePaint)
                }
                // В тумане геометрия улиц не показывается — только направление и примерная длина.
                SegmentKind.HIDDEN -> {
                    var sinceCrumb = CRUMB_STEP_M
                    for ((i, point) in segment.points.withIndex()) {
                        if (i > 0) sinceCrumb += distanceM(segment.points[i - 1], point)
                        if (sinceCrumb < CRUMB_STEP_M) continue
                        sinceCrumb = 0.0
                        val screen = projection.toScreenLocation(LatLng(point.lat, point.lon))
                        canvas.drawCircle(screen.x, screen.y, CRUMB_RADIUS_PX, crumbPaint)
                    }
                }
            }
        }

        // Точку назначения выбрал сам пользователь, поэтому она видна всегда — даже в тумане.
        destination?.let {
            val screen = projection.toScreenLocation(LatLng(it.lat, it.lon))
            canvas.drawCircle(screen.x, screen.y, DESTINATION_RADIUS_PX, destinationPaint)
            canvas.drawCircle(screen.x, screen.y, DESTINATION_RADIUS_PX, destinationRingPaint)
        }
    }

    private fun drawHoles(canvas: Canvas, map: MapLibreMap, mask: FogMask) {
        val projection = map.projection
        val bounds = projection.visibleRegion.latLngBounds

        val fromTileX = TileMath.cellX(bounds.longitudeWest) shr CELL_BITS
        val toTileX = TileMath.cellX(bounds.longitudeEast) shr CELL_BITS
        // Север — это меньший y.
        val fromTileY = TileMath.cellY(bounds.latitudeNorth) shr CELL_BITS
        val toTileY = TileMath.cellY(bounds.latitudeSouth) shr CELL_BITS
        if (toTileX < fromTileX || toTileY < fromTileY) return

        val visible = (toTileX - fromTileX + 1).toLong() * (toTileY - fromTileY + 1)
        if (visible > MAX_VISIBLE_TILES) return

        for (tileY in fromTileY..toTileY) {
            for (tileX in fromTileX..toTileX) {
                if (mask.tile(tileX, tileY) == null) continue
                val id = TileId(tileX, tileY)
                val bitmap = bitmaps.get(id)
                    ?: FogTexture.holeMask(mask, tileX, tileY).also { bitmaps.put(id, it) }

                val topLeft = projection.toScreenLocation(
                    LatLng(
                        TileMath.latOfCellY(tileY shl CELL_BITS),
                        TileMath.lonOfCellX(tileX shl CELL_BITS),
                    ),
                )
                val bottomRight = projection.toScreenLocation(
                    LatLng(
                        TileMath.latOfCellY((tileY + 1) shl CELL_BITS),
                        TileMath.lonOfCellX((tileX + 1) shl CELL_BITS),
                    ),
                )
                tileRect.set(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
                canvas.drawBitmap(bitmap, null, tileRect, holePaint)
            }
        }
    }

    private companion object {
        const val CACHE_BYTES = 8 * 1024 * 1024
        const val MAX_VISIBLE_TILES = 64L
        const val CLOUD_TILE_PX = 512

        const val ROUTE_COLOR = 0xFF1A56DB.toInt()

        /** Шаг крошек в тумане: видно направление и длину, но не геометрию улиц. */
        const val CRUMB_STEP_M = 300.0
        const val CRUMB_RADIUS_PX = 9f
        const val DESTINATION_RADIUS_PX = 18f
    }
}
