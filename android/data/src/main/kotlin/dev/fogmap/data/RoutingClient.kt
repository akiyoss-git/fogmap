package dev.fogmap.data

import dev.fogmap.core.routing.RoutePoint
import dev.fogmap.data.api.AuthGuard
import dev.fogmap.data.api.FogmapApi
import dev.fogmap.data.api.RouteRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Пеший маршрут через свой бэкенд.
 *
 * До этого здесь был публичный демо-сервер FOSSGIS — он годился, чтобы собрать логику, но в релиз
 * идти не мог: чужие ресурсы без SLA и без разрешения на нагрузку от приложения. Теперь маршруты
 * считает встроенный в бэкенд GraphHopper.
 *
 * Побочное следствие: маршруты требуют входа, потому что считаются на нашем сервере.
 */
class RoutingClient internal constructor(
    private val api: FogmapApi,
    private val guard: AuthGuard,
) {

    /** Null, если маршрут построить не удалось: нет сети, нет входа или точки вне покрытия. */
    suspend fun route(from: RoutePoint, to: RoutePoint): List<RoutePoint>? =
        withContext(Dispatchers.IO) {
            try {
                val response = guard.call { bearer ->
                    api.route(
                        bearer,
                        RouteRequestDto(from.lat, from.lon, to.lat, to.lon),
                    )
                }
                response.points
                    .map { RoutePoint(it.lat, it.lon) }
                    .takeIf { it.size >= 2 }
            } catch (e: Exception) {
                // Для вызывающего разница между причинами нулевая: маршрута нет.
                null
            }
        }
}
