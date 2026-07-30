plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("org.owasp.dependencycheck") version "10.0.3"
}

/**
 * Проверка зависимостей на известные уязвимости: ./gradlew dependencyCheckAnalyze
 *
 * NVD с 2023 года требует ключ для вменяемой скорости выкачки: без него первая загрузка базы
 * занимает часы и упирается в лимиты. Ключ берётся бесплатно на nvd.nist.gov и передаётся через
 * переменную окружения NVD_API_KEY — в репозитории ему не место.
 */
dependencyCheck {
    nvd.apiKey = System.getenv("NVD_API_KEY")
    failBuildOnCVSS = 7.0f
    suppressionFile = "config/dependency-check-suppressions.xml"
}

group = "dev.fogmap"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation("com.graphhopper:graphhopper-core:11.0")
    // GraphHopper тянет commons-io 1.3.1 — библиотеку 2007 года, снятую с поддержки. В нашем
    // сценарии она видит только локальный .pbf, но держать её на classpath незачем.
    implementation("commons-io:commons-io:2.20.0")

    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = false }
}
