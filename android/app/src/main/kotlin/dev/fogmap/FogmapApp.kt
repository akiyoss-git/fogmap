package dev.fogmap

import android.app.Application
import dev.fogmap.data.FogData
import dev.fogmap.data.RoutingClient
import dev.fogmap.data.api.SocialRepository
import dev.fogmap.data.api.SyncRepository

class FogmapApp : Application() {

    private val data: FogData by lazy { FogData(this) }

    /** Один на процесс: сервис трекинга и экран карты пишут в одну маску. */
    val fogStore: FogStore by lazy { FogStore(data.fogRepository, data.obstacleRepository) }

    val routingClient: RoutingClient get() = data.routingClient
    val syncRepository: SyncRepository get() = data.syncRepository
    val socialRepository: SocialRepository get() = data.socialRepository
}
