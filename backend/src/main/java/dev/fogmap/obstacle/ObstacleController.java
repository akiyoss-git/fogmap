package dev.fogmap.obstacle;

import dev.fogmap.obstacle.ObstacleDtos.TilesResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/obstacles")
public class ObstacleController {

    private final ObstacleService obstacles;

    public ObstacleController(ObstacleService obstacles) {
        this.obstacles = obstacles;
    }

    @GetMapping
    public TilesResponse tiles(
            @RequestParam int minX,
            @RequestParam int minY,
            @RequestParam int maxX,
            @RequestParam int maxY) {
        return new TilesResponse(obstacles.tilesIn(minX, minY, maxX, maxY));
    }
}
