package dev.fogmap.data.api

import retrofit2.HttpException

/**
 * Подставляет access-токен и обновляет его по 401.
 *
 * Вынесено из [SyncRepository], когда та же логика понадобилась социальным вызовам: access живёт
 * 15 минут и протухает часто, дублировать обработку по репозиториям смысла нет.
 */
internal class AuthGuard(private val api: FogmapApi, private val tokens: TokenStore) {

    /** Одна повторная попытка после обновления токена; второй 401 уходит наверх как есть. */
    suspend fun <T> call(block: suspend (String) -> T): T {
        val access = tokens.accessToken ?: throw IllegalStateException("не авторизован")
        return try {
            block("Bearer $access")
        } catch (e: HttpException) {
            if (e.code() != 401) throw e
            val refresh = tokens.refreshToken ?: throw e
            tokens.save(api.refresh(RefreshRequest(refresh)))
            block("Bearer ${tokens.accessToken}")
        }
    }
}
