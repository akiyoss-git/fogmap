package dev.fogmap

import dev.fogmap.core.fog.CELL_BITS
import dev.fogmap.core.fog.FogMask
import dev.fogmap.core.fog.FogTile
import dev.fogmap.core.fog.ObstacleMask
import dev.fogmap.core.fog.TileId
import dev.fogmap.core.fog.TileMath
import dev.fogmap.data.FogRepository
import dev.fogmap.data.ObstacleRepository
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Единственный владелец маски на весь процесс: в неё пишет и трекер из сервиса, и тап по карте.
 *
 * Маска не потокобезопасна, поэтому все методы вызываются с main-потока. Это осознанно, а не
 * лениво: рендер читает маску синхронно в `onDraw`, тоже на main, и любой другой поток-владелец
 * потребовал бы копий или блокировок на каждом кадре. Вскрытие стоит доли миллисекунды.
 */
class FogStore(
    private val repository: FogRepository,
    private val obstacleRepository: ObstacleRepository,
) {

    val mask = FogMask()

    /** Растр зданий. Пока пуст, вскрытие идёт обычным кругом. */
    private val obstacles = ObstacleMask()
    private var obstacleTilesRequested = emptySet<TileId>()
    private var fetchingObstacles = false

    private val _areaM2 = MutableStateFlow(0.0)
    val areaM2: StateFlow<Double> = _areaM2.asStateFlow()

    /** Тайлы, которые надо перерисовать. Оверлей слушает и сбрасывает их кэш битмапов. */
    private val _changedTiles = MutableSharedFlow<Set<TileId>>(replay = 1, extraBufferCapacity = 64)
    val changedTiles: SharedFlow<Set<TileId>> = _changedTiles.asSharedFlow()

    private val scope = MainScope()
    private var loadStarted = false

    /** Идемпотентно: и экран, и сервис дёргают при старте, кто первым поднялся. */
    fun load() {
        if (loadStarted) return
        loadStarted = true
        scope.launch {
            val stored = repository.loadTiles()
            // merge, а не put: трекер мог успеть вскрыть точку, пока читалась БД.
            stored.forEach { (id, tile) -> mask.merge(id.x, id.y, tile) }
            _areaM2.value = mask.areaM2()
            _changedTiles.tryEmit(mask.tileIds())

            val storedObstacles = obstacleRepository.loadStored()
            storedObstacles.forEach { (id, tile) -> obstacles.put(id.x, id.y, tile) }
            obstacleTilesRequested = obstacleRepository.storedKeys()
        }
    }

    /**
     * Вливает тайлы, пришедшие с сервера. Только с main-потока.
     *
     * Слияние побитовое, поэтому чужой вклад не затирает локальный и порядок не важен.
     */
    fun applyServerTiles(tiles: List<Pair<TileId, FogTile>>) {
        if (tiles.isEmpty()) return
        tiles.forEach { (id, tile) -> mask.merge(id.x, id.y, tile) }
        _areaM2.value = mask.areaM2()
        _changedTiles.tryEmit(tiles.map { it.first }.toSet())
    }

    /** Сбрасывает маску в памяти после удаления аккаунта; из базы её стирает репозиторий. */
    fun wipe() {
        val hadTiles = mask.tileIds()
        mask.wipe()
        _areaM2.value = 0.0
        _changedTiles.tryEmit(hadTiles)
    }

    fun reveal(lat: Double, lon: Double) {
        ensureObstaclesAround(lat, lon)

        // Радиусы разные намеренно: со стенами обзор 100 м, без данных о зданиях — прежние 60 м.
        // Раздувать вскрытие там, где мы не можем проверить стены, нечестно по отношению к тем,
        // у кого растр есть.
        val changed = if (obstacles.isEmpty) {
            mask.reveal(lat, lon)
        } else {
            mask.revealVisible(lat, lon, obstacles)
        }
        if (changed.isEmpty()) return

        _areaM2.value = mask.areaM2()
        _changedTiles.tryEmit(changed)

        val snapshots = mask.snapshot(changed)
        scope.launch { repository.save(snapshots) }
    }

    /**
     * Догружает растр зданий вокруг текущей точки.
     *
     * Запрошенные тайлы помечаются даже если сервер ничего не вернул: в тайле может не быть ни
     * одного здания, и без отметки мы бы просили его снова на каждом фиксе.
     */
    private fun ensureObstaclesAround(lat: Double, lon: Double) {
        if (fetchingObstacles) return

        val tileX = TileMath.cellX(lon) shr CELL_BITS
        val tileY = TileMath.cellY(lat) shr CELL_BITS
        val needed = buildSet {
            for (dy in -1..1) for (dx in -1..1) add(TileId(tileX + dx, tileY + dy))
        }
        if (obstacleTilesRequested.containsAll(needed)) return

        fetchingObstacles = true
        scope.launch {
            try {
                val fetched = obstacleRepository.fetch(tileX - 1, tileY - 1, tileX + 1, tileY + 1)
                fetched.forEach { (id, tile) -> obstacles.put(id.x, id.y, tile) }
                obstacleTilesRequested = obstacleTilesRequested + needed
            } catch (e: Exception) {
                // Нет сети, нет входа или сервер без растра — работаем кругом, это штатный режим.
            } finally {
                fetchingObstacles = false
            }
        }
    }
}
