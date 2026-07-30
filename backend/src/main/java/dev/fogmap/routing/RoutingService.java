package dev.fogmap.routing;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.Profile;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.PointList;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Пеший роутинг на встроенном GraphHopper.
 *
 * <p>Заменяет публичный демо-сервер FOSSGIS, на котором держался M3: чужие ресурсы без SLA и без
 * разрешения на нагрузку от приложения использовать нельзя — ровно по той же причине, по которой
 * нельзя брать тайлы с публичного сервера OSM.
 *
 * <p>Граф строится в конструкторе, то есть на старте приложения. Первый импорт региона занимает
 * минуты, дальше граф читается из кэша за секунды. Если экстракт не задан, сервис поднимается без
 * маршрутов — это нормальный режим для тестов и для окружений, где роутинг не нужен.
 */
@Service
public class RoutingService {

    /** Имя профиля и готовой модели из jar GraphHopper. */
    private static final String PROFILE = "foot";

    /**
     * Величины, которые нужны модели `foot.json`.
     *
     * <p>GraphHopper 11 не выводит их из модели сам и падает на старте со списком недостающих —
     * этот список отсюда и взят. Если менять профиль, набор придётся пересобрать так же.
     */
    private static final String ENCODED_VALUES = String.join(", ",
            "foot_access", "foot_priority", "foot_average_speed", "foot_road_access",
            "hike_rating", "mtb_rating", "road_class", "country");

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private final GraphHopper hopper;

    public RoutingService(
            @Value("${fogmap.routing.osm-file:}") String osmFile,
            @Value("${fogmap.routing.graph-dir}") String graphDir) {
        this.hopper = osmFile.isBlank() ? null : build(osmFile, graphDir);
    }

    private static GraphHopper build(String osmFile, String graphDir) {
        log.info("Строю граф маршрутов из {} (кэш: {})", osmFile, graphDir);
        GraphHopper hopper = new GraphHopper();
        hopper.setOSMFile(osmFile);
        hopper.setGraphHopperLocation(graphDir);
        hopper.setEncodedValuesString(ENCODED_VALUES);
        hopper.setProfiles(new Profile(PROFILE)
                .setCustomModel(GHUtility.loadCustomModelFromJar(PROFILE + ".json")));
        hopper.importOrLoad();
        log.info("Граф маршрутов готов");
        return hopper;
    }

    public boolean isAvailable() {
        return hopper != null;
    }

    /** Геометрия пешего маршрута. Порядок координат — (lat, lon), как во всём проекте. */
    public RoutingDtos.RouteResponse route(double fromLat, double fromLon, double toLat, double toLon) {
        if (hopper == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "routing is not configured on this server");
        }

        GHResponse response = hopper.route(
                new GHRequest(fromLat, fromLon, toLat, toLon).setProfile(PROFILE));
        if (response.hasErrors()) {
            // Точки вне покрытия экстракта — обычная ситуация, а не поломка сервера.
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, response.getErrors().get(0).getMessage());
        }

        ResponsePath best = response.getBest();
        PointList points = best.getPoints();
        List<RoutingDtos.PointDto> geometry = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            geometry.add(new RoutingDtos.PointDto(points.getLat(i), points.getLon(i)));
        }
        return new RoutingDtos.RouteResponse(best.getDistance(), geometry);
    }

    @PreDestroy
    public void close() {
        if (hopper != null) {
            hopper.close();
        }
    }
}
