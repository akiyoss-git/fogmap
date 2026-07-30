package dev.fogmap.obstacle;

import java.util.List;

public final class ObstacleDtos {

    private ObstacleDtos() {
    }

    /** Маска препятствий тайла: 8192 байта, бит означает «здесь стена». */
    public record TileDto(int x, int y, byte[] mask) {
    }

    public record TilesResponse(List<TileDto> tiles) {
    }
}
