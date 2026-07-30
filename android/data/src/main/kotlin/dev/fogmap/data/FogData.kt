package dev.fogmap.data

import android.content.Context
import dev.fogmap.data.api.AuthGuard
import dev.fogmap.data.api.FogmapApi
import dev.fogmap.data.api.SocialRepository
import dev.fogmap.data.api.SyncRepository
import dev.fogmap.data.api.TokenStore
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Единственная точка входа в модуль. Room и Retrofit не выходят за пределы `data` — снаружи видны
 * только репозитории и типы из `core:*`.
 */
class FogData(context: Context) {

    private val database = FogDatabase.open(context)
    private val tokens = TokenStore(context)

    private val api: FogmapApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(
            Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()),
        )
        .build()
        .create(FogmapApi::class.java)

    private val guard = AuthGuard(api, tokens)

    val fogRepository: FogRepository = FogRepository(database.fogTiles())
    val obstacleRepository: ObstacleRepository =
        ObstacleRepository(database.obstacleTiles(), api, guard)
    val routingClient: RoutingClient = RoutingClient(api, guard)
    val syncRepository: SyncRepository = SyncRepository(api, tokens, fogRepository, guard)
    val socialRepository: SocialRepository = SocialRepository(api, guard)

    private companion object {
        /**
         * 10.0.2.2 — это хост, на котором крутится эмулятор. Для реального устройства и для
         * релиза адрес другой; вынести в конфигурацию сборки, когда появится развёрнутый сервер.
         */
        const val BASE_URL = "http://10.0.2.2:8080/"
    }
}
