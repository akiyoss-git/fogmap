package dev.fogmap.social;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AchievementService {

    private static final String METRIC_AREA = "AREA_M2";

    private final AchievementRepository achievements;

    public AchievementService(AchievementRepository achievements) {
        this.achievements = achievements;
    }

    /**
     * Пересматривает достижения после синхронизации.
     *
     * <p>Считается от значений, которые сервер вычислил сам, а не от присланных клиентом. Выдача
     * идемпотентна, поэтому вызывать можно на каждый синк.
     */
    @Transactional
    public void evaluate(long userId, long areaM2, int tilesCount) {
        for (AchievementRepository.Definition definition : achievements.all()) {
            long value = METRIC_AREA.equals(definition.metric()) ? areaM2 : tilesCount;
            if (value >= definition.threshold()) {
                achievements.award(userId, definition.code());
            }
        }
    }

    public List<AchievementRepository.Unlocked> unlocked(long userId) {
        return achievements.unlocked(userId);
    }
}
