package dev.fogmap.routing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;

public final class RoutingDtos {

    private RoutingDtos() {
    }

    public record RouteRequest(
            @Min(-90) @Max(90) double fromLat,
            @Min(-180) @Max(180) double fromLon,
            @Min(-90) @Max(90) double toLat,
            @Min(-180) @Max(180) double toLon) {
    }

    public record PointDto(double lat, double lon) {
    }

    public record RouteResponse(double distanceM, List<PointDto> points) {
    }
}
