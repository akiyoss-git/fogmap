package dev.fogmap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Планировщик нужен для уборки истёкших счётчиков попыток входа. */
@EnableScheduling
@SpringBootApplication
public class FogmapApplication {

    public static void main(String[] args) {
        SpringApplication.run(FogmapApplication.class, args);
    }
}
