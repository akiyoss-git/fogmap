package dev.fogmap.routing;

import dev.fogmap.routing.RoutingDtos.RouteRequest;
import dev.fogmap.routing.RoutingDtos.RouteResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/routes")
public class RoutingController {

    private final RoutingService routing;

    public RoutingController(RoutingService routing) {
        this.routing = routing;
    }

    @PostMapping
    public RouteResponse route(@Valid @RequestBody RouteRequest request) {
        return routing.route(request.fromLat(), request.fromLon(), request.toLat(), request.toLon());
    }
}
