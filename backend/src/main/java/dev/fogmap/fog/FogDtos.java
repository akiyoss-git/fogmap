package dev.fogmap.fog;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public final class FogDtos {

    private FogDtos() {
    }

    /**
     * Тайл от клиента.
     *
     * <p>{@code revealedCells} принимается, но игнорируется: сервер считает popcount сам. Поле
     * оставлено осознанно — клиент шлёт то же, что хранит у себя, а тест
     * проверяет, что завышенное значение ни на что не влияет.
     */
    public record TileUpload(int x, int y, @NotNull byte[] mask, Integer revealedCells) {
    }

    public record SyncRequest(Long since, @NotNull List<TileUpload> tiles) {
    }

    public record TileDownload(int x, int y, byte[] mask, long updatedAt) {
    }

    public record SyncResponse(long areaM2, int tilesCount, List<TileDownload> tiles, long serverTime) {
    }
}
