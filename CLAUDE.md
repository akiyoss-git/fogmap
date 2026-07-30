# CLAUDE.md — fogmap

Fog-of-war карта на OpenStreetMap: Android-приложение (Kotlin) + бэкенд (Java / Spring Boot).
Детали архитектуры — [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), этапы — [docs/ROADMAP.md](docs/ROADMAP.md).

---

## Часть 1. Поведенческие правила

Источник: https://github.com/multica-ai/andrej-karpathy-skills/blob/main/CLAUDE.md

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to
overcomplication, and clarifying questions come before implementation rather than after mistakes.

---

## Часть 2. Инварианты проекта

Это не стилистические предпочтения — нарушение любого пункта ломает продукт. Если задача требует
нарушить инвариант, остановись и скажи об этом.

1. **Никогда не обращаться к `tile.openstreetmap.org`.** Tile Usage Policy OSM прямо запрещает
   использование их серверов в приложениях. Тайлы — только через провайдера из
   [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#тайлы).
2. **Туман живёт локально.** Приложение полностью работоспособно без сети: трекинг, открытие карты,
   рендер. Сеть нужна только для социальных фич и первичной загрузки тайлов.
3. **Сервер — источник истины для площади и достижений.** Клиент считает то же самое для мгновенного
   UI, но сервер пересчитывает из присланных маск сам и никогда не доверяет числам от клиента.
4. **Маска тумана — растр, не полигоны.** Никаких GeoJSON-полигонов с дырками для открытой
   территории: через месяц ходьбы это десятки тысяч дырок и мёртвый рендер.
5. **Маска — grow-only.** Открытая ячейка не закрывается никогда. Поэтому merge между устройствами —
   побитовый OR, он идемпотентен и коммутативен; разрешение конфликтов не нужно.
6. **Никаких ключей в репозитории.** API-ключи тайлов — через `local.properties` / переменные
   окружения, в APK только с ограничением по package name + подписи.
7. **Маска наружу не отдаётся.** Про чужого пользователя видно только имя и суммарную площадь.
   Точная геометрия — это история перемещений с точностью 5 метров, и ни один эндпоинт не должен
   отдавать её никому, кроме владельца. Идентификатор пользователя для выборки берётся из токена,
   никогда из параметров запроса.
8. **Открытые эндпоинты перечисляются поимённо.** В `SecurityConfig` не должно быть шаблонов вроде
   `/auth/**` в `permitAll`: следующий эндпоинт в группе окажется публичным по недосмотру.

## Часть 3. Соглашения

**Android**
- Kotlin, Jetpack Compose, `minSdk 26`. MapLibre Native Android.
- Слои: `:app` (UI) → `:core:fog`, `:core:routing`, `:data` (Room + Retrofit). UI не знает про Room и
  про сеть напрямую.
- `:core:fog` — чистый Kotlin/JVM без Android-зависимостей, чтобы покрывался обычными unit-тестами.
- Корутины + Flow. Никаких `LiveData`, никаких `runBlocking` в продакшн-коде.

**Бэкенд**
- Java 21, Spring Boot 3, Postgres, Flyway (миграции только вперёд, править применённую миграцию нельзя).
- Слои: `web` (контроллеры + DTO) → `service` → `repository`. Entity никогда не уходят в JSON —
  только DTO.
- Доступ к БД — Spring JDBC (`JdbcClient`), не JPA. Основная работа здесь — побитовый merge блобов
  и upsert; ORM на этом только мешает, а явный SQL читается лучше.
- Время в параметрах запросов — `OffsetDateTime`, не `Instant`: драйвер Postgres не умеет выводить
  SQL-тип для `Instant` и падает с «Can't infer the SQL type».
- `/error` обязан быть в `permitAll`, иначе внутренний форвард упирается в проверку доступа и любая
  ошибка сервера возвращается клиенту как 401.
- Тесты: Testcontainers на Postgres, без H2 (иначе расхождения в типах `bytea` и upsert-семантике).

**Общее**
- Все геокоординаты — WGS84, порядок в коде всегда `(lat, lon)`; в GeoJSON — `[lon, lat]`, помечать
  комментарием на каждой границе.
- Расстояния и площади — метры и м², конвертация в км только на слое отображения.
- Не добавлять зависимость, если задача решается 30 строками своего кода.

## Часть 4. Команды

Всё из каталога `android/`. Требуется JDK 17+; на этой машине подходит JBR из Android Studio
(`C:\Program Files\Android\Android Studio\jbr`), отдельный JDK ставить не нужно.

```bash
./gradlew test              # unit-тесты core:fog — быстрая проверка геометрии
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew installDebug
```

### Проверка на эмуляторе

AVD `Pixel_6` (API 33). Виртуализация: SVM включён в BIOS, ускорение идёт через WHPX
(`emulator -accel-check` должен дать `accel: 0`). Без ускорения эмулятор не стартует, а `-accel off`
падает с segfault — то есть при выключенном SVM проверять UI можно только на реальном телефоне.

```bash
"$ANDROID_HOME/emulator/emulator.exe" -avd Pixel_6 -no-boot-anim -no-audio -no-snapshot-save   # в фоне
adb shell am start -n dev.fogmap/.MainActivity
adb exec-out screencap -p > screen.png
adb shell input tap 540 1200
adb shell dumpsys gfxinfo dev.fogmap    # фреймтайминг; на эмуляторе не показателен
```

Тапы для вскрытия ставить с паузой ≥1 с: два подряд MapLibre принимает за double-tap и зумит.

Проверка трекинга без прогулки — мок-локация. Разрешения выдаются через `pm grant`, чтобы не
кликать по системному диалогу:

```bash
adb shell pm grant dev.fogmap android.permission.ACCESS_FINE_LOCATION
adb emu geo fix <lon> <lat>          # порядок именно lon lat, легко перепутать
adb shell input keyevent 26          # погасить экран
adb shell dumpsys battery unplug && adb shell dumpsys deviceidle force-idle   # загнать в Doze
adb shell dumpsys deviceidle get deep                                          # ожидается IDLE
adb shell dumpsys deviceidle unforce && adb shell dumpsys battery reset        # вернуть как было
```

Интервал между фиксами — не меньше 5 с (столько запрошено у `FusedLocationProvider`), иначе часть
просто не дойдёт. И следить за скоростью: шаг больше ~1 км между фиксами с секундной паузой
отбраковывается как телепорт.

### Бэкенд

Всё из каталога `backend/`, тот же JDK 21.

```bash
./gradlew test              # unit + интеграционные на Testcontainers
./gradlew bootRun           # нужен Postgres на localhost:5432
./gradlew build
```

Postgres для `bootRun` — одноразовый контейнер:

```bash
docker run -d --name fogmap-pg -e POSTGRES_DB=fogmap -e POSTGRES_USER=fogmap -e POSTGRES_PASSWORD=fogmap -p 127.0.0.1:5432:5432 postgres:16-alpine
docker start fogmap-pg      # если уже создан
```

Привязка именно к `127.0.0.1`, а не `-p 5432:5432`: второе биндит контейнер на все интерфейсы, и
база с паролем `fogmap` оказывается доступна из сети — например, в публичном Wi-Fi.

Маршруты считает встроенный GraphHopper. Без экстракта региона `/routes` отвечает 503 — так и
задумано, для тестов граф не нужен. С экстрактом:

```bash
FOGMAP_OSM_FILE=data/Moscow.osm.pbf ./gradlew bootRun
```

Экстракт качается отдельно (`backend/data/` в .gitignore), например с
`download.bbbike.org/osm/bbbike/Moscow/Moscow.osm.pbf` — 84 МБ. Первый старт строит граф ~20 с,
дальше он читается из кэша в `data/graph-cache`.

**Останавливать бэкенд надо по владельцу порта, а не через остановку задачи Gradle:** обёртка
умирает, а порождённый ею java-процесс продолжает держать 8080, и следующий `bootRun` падает,
успев прогнать миграции.

Приложение на эмуляторе ходит на `http://10.0.2.2:8080` — это хост эмулятора. Открытый HTTP
разрешён только для него, см. `network_security_config.xml`.

Интеграционным тестам нужен запущенный Docker Desktop. На этой машине доступ к реестрам
контейнеров режется — образ `postgres:16-alpine` тянется только с включённым VPN. Один раз
скачанный образ дальше берётся из локального кэша.

### Версии

AGP 8.13 / Gradle 8.13 / Kotlin 2.4.10 / compileSdk 36. Верхняя граница здесь не случайна:
`androidx.core` 1.19+ и `androidx.lifecycle` 2.10+ требуют AGP 9.1 и compileSdk 37, а AGP 9.x новее
установленной Android Studio 2025.3.3 и не открылся бы в ней. Поднимать эти зависимости можно
только вместе с обновлением Studio, AGP, Gradle и установкой платформы android-37.
