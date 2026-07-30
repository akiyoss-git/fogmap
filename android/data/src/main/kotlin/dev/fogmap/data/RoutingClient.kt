package dev.fogmap.data

import dev.fogmap.core.routing.RoutePoint
import dev.fogmap.data.api.AuthGuard
import dev.fogmap.data.api.FogmapApi
import dev.fogmap.data.api.RouteRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Чем закончилась попытка построить маршрут.
 *
 * Отдельный случай для «региона нет на сервере» нужен ради сообщения пользователю: снаружи это
 * выглядит так же, как сбой, но означает совсем другое и другого от человека требует.
 */
sealed interface RouteResult {
    data class Success(val points: List<RoutePoint>) : RouteResult

    /** Точки вне покрытия загруженного экстракта либо маршруты на сервере вообще не настроены. */
    data object OutOfCoverage : RouteResult

    data object Failed : RouteResult
}

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

    suspend fun route(from: RoutePoint, to: RoutePoint): RouteResult = withContext(Dispatchers.IO) {
        try {
            val response = guard.call { bearer ->
                api.route(bearer, RouteRequestDto(from.lat, from.lon, to.lat, to.lon))
            }
            val points = response.points.map { RoutePoint(it.lat, it.lon) }
            if (points.size >= 2) RouteResult.Success(points) else RouteResult.Failed
        } catch (e: HttpException) {
            // 422 — точки вне графа, 503 — маршруты на сервере не настроены. Для пользователя
            // это одно и то же: здесь маршруты пока не работают.
            if (e.code() == 422 || e.code() == 503) RouteResult.OutOfCoverage else RouteResult.Failed
        } catch (e: Exception) {
            RouteResult.Failed
        }
    }
}
