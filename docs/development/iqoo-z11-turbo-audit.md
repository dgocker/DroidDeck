# Аудит производительности DroidDeck — iQOO Z11 Turbo

Дата: 2026-10-12. Ветка: `claude/trusting-wozniak-01gitb` (база `22f02fb`).
Вторая итерация (исправления DD-02/03/04, layer-режим SDR, политика частоты, A/B-план):
[iqoo-z11-turbo-ab-plan.md](iqoo-z11-turbo-ab-plan.md).
Метод: чтение исходников, истории и документации репозитория; проверка бинарей из `assets/`;
хост-тесты. **Физического устройства не было**, Android SDK/NDK-сборка в этой среде недоступна
(политика сети запретила `dl.google.com`). Ни одной цифры FPS ниже не приводится: всё, что требует
устройства, помечено как гипотеза с методом проверки.

Обозначения уверенности: **подтверждено кодом** (дефект виден и воспроизведён тестом/чтением кода),
**вероятно** (механизм понятен, эффект не измерен), **гипотеза** (нужны данные с устройства).

---

## A. Executive summary

**Состояние проекта.** DroidDeck — зрелый проект с серьёзной уже проделанной работой по
производительности: in-process fast path для proot (`tools/proot/fastpath`, патч 0014), эмуляция
`futex_waitv` для fsync (`tools/linuxfs/preload/fsync.c`), телеметрия композитора каждые 10 с
(`perf`-строка), режим «display layer» (ASurfaceControl) с zero-copy, отключение xalia, база
шейдерного кеша Mesa одним файлом и т. д. Почти весь «тяжёлый» путь кадра (FEX, Wine/Proton,
DXVK/VKD3D-Proton, Turnip, gamescope, Steam) — **внешние компоненты**, которые в репозитории не
лежат. Точки контроля DroidDeck: собственный Wayland-композитор (`app/src/main/cpp/waylandcomp`),
выбор/параметры драйверов, переменные окружения игр, разрешение/частота/лимит кадров, привязка к
ядрам, preload-шимы в госте, патчи proot и gamescope.

**Главные риски для iQOO Z11 Turbo (Snapdragon 8 Gen 5, Adreno 829 по большинству источников):**

1. **Поддержка Adreno 829 в Turnip не проверена на железе** (P1-риск, гипотеза). Встроенный в APK
   Turnip для композитора (Mesa 26.0.0-devel `ff39aa7716`) содержит запись `FD829` (проверено по
   строкам бинаря), но сторонние сборки ещё в сентябре 2026 правили параметры A829 (offsets, размер
   depth cache). Драйвер рантайма по умолчанию для 829 — WinNative Balanced (не DD-Turnip, который
   выбирается только для 840), его поддержка 829 неизвестна.
2. **На 2+6 SoC рабочий стол и программы с «рельса» получали только 2 ядра** (P1, подтверждено,
   **исправлено** — `aa5832c`).
3. **Путь «copy» по умолчанию (SDR):** каждый кадр игры масштабируется блитом в swapchain размером с
   панель (≈2750×1260 на iQOO) с синхронным ожиданием fence в потоке композитора; zero-copy/layer
   режим включается только вместе с HDR (P2, механизм подтверждён кодом, эффект не измерен).
4. **144 Гц без лимита кадров:** при 60/30 fps контенте каденс 144/60 = 2,4 → кадры чередуются по
   2 и 3 обновления (13,9/20,8 мс) — judder и лишняя работа (P2, вероятно).
5. **`TU_DEBUG=sysmem` по умолчанию для всех игр и GPU** — основано на одном замере на A840
   (+3,6 %); для «sliced» A8xx с GMEM на слайс эффект на 829 неизвестен (P2, гипотеза).

**Самые перспективные направления (в порядке ожидаемой пользы/стоимости):** сначала измерить
(новая строка `pacing` в логе композитора даёт P50/P95/P99 интервалов кадров) → A/B политики частоты
дисплея (120 Гц вместо 144 для ≤60 fps) → A/B layer-режима (HWC делает масштабирование вместо GPU) →
A/B `TU_DEBUG=sysmem` по играм → A/B «Force SSBS» на Oryon → только потом переписывание синхронизации
композитора.

**Ограничения аудита:** нет устройства; нет Android-сборки и on-device тестов; исходники FEX, Proton,
DXVK, VKD3D, Turnip, gamescope, Steam в репозитории отсутствуют и не аудировались; характеристики
iQOO частично расходятся между источниками (см. E).

---

## B. Архитектура

### B.1 Компоненты (что чьё)

| Компонент | Где | Владелец |
|---|---|---|
| Android-приложение: UI (Compose), `SessionActivity` (Surface, ввод, дровер), `SessionService` (proot, окружение, teardown) | `app/src/main/java/com/droiddeck/launcher/**` | DroidDeck |
| Wayland-композитор (libwayland-server, Vulkan через Turnip/adrenotools, ASurfaceControl-слои, эффекты, frame generation) | `app/src/main/cpp/waylandcomp/src/*` | DroidDeck (линия Bannerlator) |
| Эмуляция контроллеров (evdev/hidraw в госте через mmap-кольцо) | `input/FakeInputWriter.java`, `cpp/fakeinput_steam.cpp` | DroidDeck |
| Preload-шимы гостя (`libblsession.so`: fsync/`futex_waitv`, ntsync, opens/pathcache, dircache, drm, kgsl…) | `tools/linuxfs/preload/*.c` | DroidDeck |
| Сессионные скрипты (gamescope, Steam, watchers, core pinning) | `tools/linuxfs/overlay/usr/local/bin/droiddeck-*` | DroidDeck |
| proot + патчи 0001–0014 | `tools/proot/patches` | патчи DroidDeck, база Termux |
| gamescope 3.16.29 + патчи | `tools/gamescope/patches` | патчи DroidDeck/Armada, база Valve |
| Turnip для композитора (bionic) | `assets/graphics_driver/*.tzst` (бинарь) | Mesa/сторонние сборки |
| Turnip рантайма (glibc), FEX, Proton ARM64, Wine, DXVK, VKD3D-Proton, Steam, mangoapp | rootfs/скачиваемые пакеты | внешние |
| LSFG / Win-FG frame generation | `cpp/framegen/*` (вендорено) | сторонние, собираются в APK |

### B.2 Путь кадра (Steam-сессия, полноэкранная игра)

```
game.exe (x86-64)
 └ Wine/Proton ARM64EC, FEX как DLL в процессе (JIT x86→ARM64; TSO по пресету)
    └ DXVK (D3D9-11) / VKD3D-Proton (D3D12) → Vulkan
       └ Turnip glibc (KGSL ioctl, untraced proot) → GPU Adreno
          └ WSI → Xwayland игры (2-й сервер, STEAM_GAME_DISPLAY_0) → gamescope (nested, --backend wayland)
             ├ полный экран без оверлеев: gamescope пробрасывает dma-buf игры как wl_subsurface
             └ иначе: композиция gamescope (Vulkan) → свой буфер
                └ zwp_linux_dmabuf → DroidDeck compositor (поток "wl-compositor", nice −8)
                   ├ copy path (SDR по умолчанию): import VkImage → vkCmdBlitImage со
                   │  scale в swapchain размера SurfaceView → vkQueuePresentKHR (FIFO)
                   │  → vkWaitForFences(UINT64_MAX) → wl_buffer.release (с пейсингом)
                   └ layer path (только при HDR, либо zero-copy): ASurfaceControl-слой
                      (AHB игры без копии или один блит в пул) → ASurfaceTransaction
                      → SurfaceFlinger/HWC → панель
```

Тактирование: `Choreographer.doFrame` (UI-поток) → `nativeVsync` → pipe → `on_vsync`
(`compositor.c:2300`) → `render_scene` при «грязной» сцене → frame callbacks и
`wp_presentation_feedback` (с номинальной частотой) → gamescope/клиенты подстраиваются.
Обратное давление: `release_buffer()` отдаёт заменённый буфер игре не чаще одного раза за интервал
обновления (или лимита FPS) (`compositor.c:446`).

### B.3 Реальные точки контроля производительности

- разрешение сессии (`SessionDisplay`, по умолчанию 720p) и scale mode;
- частота панели (`PerfMode.apply`) и голос `setFrameRate` слоя (`CompositorHost.pace`);
- лимит FPS (gamescope `-r`, release pacing);
- copy vs layer path, эффекты/апскейлеры, frame generation;
- выбор драйверов (компоновщик и рантайм) и `TU_DEBUG`;
- пресеты FEX, SSBS-адаптер, fsync/ntsync/esync шимы;
- аффинити (`BL_GAME_CPUS`, `BL_CLIENT_CPUS`, `program_cores`, `droiddeck-launcher-cores`);
- proot fast path и патчи.

---

## C. Найденные дефекты

| ID | Приор. | Уверенность | Статус |
|---|---|---|---|
| DD-01 | P1 | подтверждено тестом | **исправлено** `aa5832c` |
| DD-02 | P3 | подтверждено кодом и синтетическим тестом (8,15 вместо 6,94 мс) | **исправлено** `3f0ab28` |
| DD-03 | P3 | подтверждено кодом и хост-тестом (падение на второй копии) | **исправлено** `12decf2` |
| DD-04 | P3 | подтверждено кодом (молчаливая потеря), сценарий редкий | **исправлено** `cc3d00f` |
| DD-05 | P2 (desktop) | подтверждено кодом | не исправлено |
| DD-06 | P3 | подтверждено кодом (документация ≠ код) | не исправлено |
| DD-07 | P3 | подтверждено кодом | не исправлено |
| DD-08 | P3 | подтверждено кодом | не исправлено |

### DD-01 — `program_cores()` оставляет 2 ядра на 2+6 SoC (P1, исправлено)

- **Файл:** `tools/linuxfs/overlay/usr/local/bin/droiddeck-session:176-203` (до исправления 176-196);
  вызовы — `:241` (рабочий стол KDE) и `:378` (программа с «рельса», эмуляторы).
- **Сценарий:** любой рабочий стол / программа с «рельса» без явных `BL_GAME_CPUS`.
- **Причина:** правило «все ядра, кроме самого медленного кластера» рассчитано на big.LITTLE. На
  Snapdragon 8 Gen 5 (6×3,32 + 2×3,8 ГГц), 8 Elite и 8 Elite Gen 5 «медленный кластер» — это 6
  производительных ядер Oryon, и маска сжималась до `6,7`.
- **Влияние:** эмулятор/программа, которым нужно ≥3 тяжёлых потока (PPU/SPU рекомпиляция, компиляция
  шейдеров), делили 2 ядра вместо 8 — до ~4× меньше доступного CPU-времени при многопоточной нагрузке
  (оценка сверху; реальный эффект зависит от программы, не измерен).
- **Воспроизведение:** `tools/tests/test_program_cores.py` — до исправления 3 теста падают
  (`'' != '6,7'`), после — 9/9 OK.
- **Исправление:** медленный кластер исключается только если остаётся ≥4 ядер; иначе маска не
  ставится. Трёхкластерные SoC (8 Gen 2/3, 865) получают прежние маски (покрыто тестами).
- **Риски:** на 2+6 SoC с настоящими little-ядрами (например 2×A76 + 6×A55) программы снова видят
  все 8 ядер — это поведение планировщика по умолчанию, которое было до введения маски.
- **Проверка на устройстве:** строка `== program cores:` / `== desktop cores:` в `session.log`
  должна исчезнуть на iQOO; `taskset -p <pid>` эмулятора — маска `ff`.

### DD-02 — оценка периода vsync смещена вверх (P3)

- **Файл:** `app/src/main/cpp/waylandcomp/src/compositor.c:2300-2309`, использование — `:446`.
- **Причина:** `g_refresh_ns` считается по моменту *обработки* сообщения в потоке композитора, а не
  по `frameTimeNanos` Choreographer (параметр явно отброшен, `(void)frame_time_ns`). После
  задержки потока (длинный `render_scene`, ожидание fence) первый интервал длинный и принимается
  (<40 мс), а следующий короткий (<3 мс) — отбрасывается. Фильтр асимметричен → EMA растёт.
- **Влияние:** интервал release-пейсинга без лимита FPS становится длиннее периода панели → игра,
  способная выдавать ≥ частоты панели (меню Big Picture, лёгкие игры), получает буферы реже, чем
  могла бы. На 30/60 fps при 144 Гц не влияет.
- **Проверка:** логировать `g_refresh_ns` в `perf`-строке; сравнить с `nominal_refresh_ns()`.
- **Предлагаемое исправление:** считать по разности `frame_time_ns`; интервалы около k·T (k≥2)
  делить на k; при известной номинальной частоте ограничивать оценку ±10 % от неё.
- **Риск:** VRR/LTPO-панели, где реальная частота ниже номинальной — поэтому именно «делить на k», а
  не жёстко брать номинал.

### DD-03 — повторное использование fence/command buffer после тайм-аута 1 с (P3)

- **Файл:** `vk_present.c:1958-1965` (layer blit), `:2054-2060` (readback курсора),
  `:2254-2261` (layer pass): `timed_wait(g_fence, 1 s)` при `VK_TIMEOUT` возвращает −1, но fence
  остаётся связанным с незавершённой отправкой; следующий кадр делает `vkResetFences` и
  `vkResetCommandBuffer(g_cmds[0])` на ещё исполняемых объектах (нарушение VUID-vkResetFences-01123,
  VUID-vkResetCommandBuffer-00045).
- **Сценарий:** GPU занят >1 с (тяжёлая сцена при троттлинге, длительный job игры без вытеснения).
- **Исправление:** при тайм-ауте помечать «fence в полёте» и в следующий раз сначала дождаться его
  (как делает `render_impl` с `UINT64_MAX`), либо пул fence/cmd buffer.
- **Проверка:** Vulkan Validation Layers в debug-сборке + искусственная задержка GPU.

### DD-04 — потеря событий ввода при переполнении pipe (P3)

- **Файл:** `compositor.c:3294-3335` (запись), `:3601` (`pipe2(..., O_NONBLOCK)`).
- **Причина:** vsync и весь ввод идут в один неблокирующий pipe (~64 КБ ≈ 3 200 сообщений по 20 Б),
  результат `write()` игнорируется. При многосекундном простое потока композитора отпускание
  клавиши/кнопки может быть потеряно → «залипшая» клавиша.
- **Исправление:** отдельный канал для vsync (eventfd/атомарный флаг), счётчик потерь в `perf`-строке,
  для клавиш — повторная синхронизация состояния модификаторов (как уже делается для Shift).

### DD-05 — полная копия wl_shm-буфера на каждый commit, damage игнорируется (P2 для рабочего стола)

- **Файл:** `compositor.c:725-736` (`take_shm` → `vkp_image_upload_shm` копирует весь буфер),
  `:923` и `:1181` (`surface_damage`/`surface_damage_buffer` — пустые).
- **Сценарий:** режим «Desktop» (KWin рендерит программно, отдаёт wl_shm), прокрутка/видео.
- **Влияние (оценка, не замер):** при 2750×1260×4 ≈ 13,9 МБ на commit; на 60 commit/с ≈ 0,8 ГБ/с
  memcpy в потоке композитора (который же обслуживает ввод и vsync).
- **Исправление:** накапливать damage (buffer-координаты; при viewport/scale — полная копия),
  копировать только объединение прямоугольников. Тестируется на хосте как чистая функция.

### DD-06 — комментарий обещает ADPF-сессию, которой нет (P3)

- **Файл:** `waylandcomp_jni.c:227-231`: «The app opens a PerformanceHintManager session on it…».
  `nativeCompositorTid()` нигде в Java/Kotlin не вызывается, `PerformanceHintManager` не используется.
- **Следствие:** ни поток композитора, ни потоки игры не сообщают governor'у целевую длительность
  кадра (см. гипотезу H-6).

### DD-07 — нет кодовых имён платформ SM8845/SM8850 (P3)

- **Файл:** `gpu/GpuInfo.kt:110-113`. Используется только когда KGSL не отдаёт модель. Проверить на
  iQOO: `getprop ro.board.platform`, `cat /sys/class/kgsl/kgsl-3d0/gpu_model`.

### DD-08 — опрос `xprop` раз в секунду при «16:9 stretch» (P3)

- **Файл:** `droiddeck-session:586-597`: `nap 1` + `xprop -root` — 1 exec/с под proot (0,4–0,8 мс
  трассировки каждый, `docs/development/proot-performance.md`). Рядом уже используется
  `xprop -spy` — тот же приём устраняет опрос.

---

## D. Узкие места производительности

### D.1 Подтверждённые кодом (механизм виден, величина не измерена)

**PB-1. Copy path по умолчанию, блит в полное разрешение панели + синхронный fence.**
- SDR-сессия: `g_zero_copy` выключен (`SessionActivity.kt:1271` включает его только при HDR) →
  `layer_candidate` не вызывается (`compositor.c:2137`) → всегда `vkp_render`.
- Swapchain = размер окна SurfaceView без `setFixedSize` (`SessionActivity.kt:414-416`,
  `vk_present.c:732`) → на iQOO ≈ 2750×1260. Сессия по умолчанию 720p (`SessionDisplay.kt:22`).
  Блит 720p→1260p пишет ≈13,9 МБ/кадр против ≈4,5 МБ исходника; затем DPU читает буфер панельного
  размера каждое обновление.
- `render_impl` ждёт GPU (`vk_present.c:1805`, `UINT64_MAX`) в единственном потоке, который
  обслуживает commit'ы, ввод, frame callbacks. Если GPU занят игрой, ожидание включает очередь GPU.
- Ресурс: пропускная способность памяти/GPU и время потока композитора. Собственный исторический
  замер проекта: разрыв ~20 % FPS между copy-путём и X11 на A750 при высоких FPS
  (`waylandcomp/ZERO_COPY_SPIKE.md`) — на 60 fps эффект ожидается меньше.
- Проверка: `perf`-строка (`copy` vs `zero-copy`/`layer copy`, `fence wait avg/max`,
  `render_scene avg/max`) + новая `pacing`-строка; A/B: HDR-сессия (форсирует zero-copy) против SDR
  на одной игре, или отдельный переключатель (рекомендация F-3).

**PB-2. 144 Гц без лимита при 60/30 fps.**
- `PerfMode.kt:41-45`: без лимита выбирается максимальная частота (на iQOO, по спецификации, 144 Гц);
  `CompositorHost.kt:70` голосует слоем за `refreshHz`. 144/60 = 2,4 → кадры по 2 и 3 обновления
  (13,9/20,8 мс). `cadenceFits()` применяется только при заданном лимите.
- Проверка: строка `pacing` (интервалы «shown» p50≈13,9, p51+≈20,9 — именно этот случай покрыт
  тестом `test_60_fps_on_144_hz_shows_its_judder`), Perfetto FrameTimeline (jank type
  «Display HAL»/«Prediction Error»).

**PB-3. `TU_DEBUG=sysmem` по умолчанию.**
- `SessionPrefs.kt:420-428` (по умолчанию `true`), `SessionService.kt:308-318, 618`. Применяется ко
  всей сессии: и к UI Steam (Zink), где обоснование сильное, и ко всем играм.
- Обоснование в коде — один замер (Palworld 42,8 vs 41,3 fps на A840, один прогон). Для A8xx с
  GMEM на каждом слайсе («dedicated memory per slice» в брифе Qualcomm для 8 Gen 5) отказ от GMEM
  может стоить больше полосы памяти → больше энергии/нагрева при длительной игре. Гипотеза.

### D.2 Вероятные

- **PB-4.** Ввод геймпада в госте опрашивается с backoff до 2 мс (`fakeinput_steam.cpp:3852`,
  `:3592`, циклы `poll`/`read`): ≤2 мс добавочной задержки, до 500 пробуждений/с на ожидающий поток.
- **PB-5.** Если реестр fsync не создан (`shm_open` не удался), `fs_poll` опрашивает каждые 0,5 мс
  (`fsync.c:34, 403`) — признак: нет строки `droiddeck-fsync: up and running` в логе.
- **PB-6.** `GpuClockPin` (opt-in, `GpuClockPin.kt:33`, `SessionService.kt:342`) держит GPU на
  максимуме — для коротких тестов плюс, для 20+ мин игры на телефоне без активного охлаждения
  вероятен более ранний троттлинг. Держать выключенным для устойчивых FPS.

### D.3 Непроверенные гипотезы

- **H-1.** Force SSBS (`SessionPrefs.kt:584`, выкл.) даёт прирост на Oryon (в коде — «reported on
  8 Elite»); у 8 Gen 5 те же Oryon-ядра — кандидат на A/B.
- **H-2.** Пресет FEX `PERFORMANCE_TSO` (`SessionPrefs.kt:574`) — цена TSO на Oryon V3 неизвестна.
- **H-3.** Узкое место UI Steam (Chromium→ANGLE→Zink→Turnip, «~14 fps на A840» в комментарии) — на
  829 с меньшим числом слайсов может быть хуже; не влияет на FPS игр, влияет на UX.
- **H-4.** Ядро iQOO может использовать страницы 16 КБ (Android 15+). FEX/Wine рассчитаны на 4 КБ.
  Проверка: новая строка `== kernel: page size` в `session.log` (`8c91341`).
- **H-5.** CPU-bound vs GPU-bound для конкретной игры: на 8 Gen 5 игры через FEX вероятнее упираются
  в CPU (JIT, один горячий поток), а UI/эффекты — в GPU. Решается только трассой.
- **H-6.** ADPF (`PerformanceHintManager`, API 31+) для потока композитора и, если UID-проверка
  позволит, игровых потоков: может уменьшить «просадки частоты» между кадрами. Проверить на OriginOS.

### D.4 Оценка классов оптимизаций (привязка к узким местам)

| Класс | Где применим | Ожидание |
|---|---|---|
| A. Алгоритмическая | DD-05 (damage-копия), DD-02 (оценка vsync) | локально; на игры — малое |
| B. JNI | per-frame upcall `onGameFrame` — атомарный инкремент | эффекта нет, не узкое место |
| C. Потоки/синхронизация | PB-1 синхронный fence; PB-4 опрос ввода; DD-01 маски | PB-1 — средний, только после замера |
| D. Графический backend | PB-1 layer path/HWC-масштабирование; PB-3 sysmem | **наибольший потенциал в зоне DroidDeck** |
| E. Шейдеры/кеши | уже: Mesa DB cache, GPL в DXVK; внешнее | мало рычагов внутри репо |
| F. Настройки игры | 720p, лимит 60, 120 Гц, frame gen | **дёшево и сильно**, но по играм |
| G. Память/I/O | proot fast path уже включён; загрузки — внешнее | загрузка уровней, не FPS |
| H. Накладные приложения | наблюдатели скриптов, HUD 1 Гц, watchSession 2 Гц | мало |

---

## E. Специфика iQOO Z11 Turbo

### E.1 Подтверждено на устройстве

Ничего — устройства не было.

### E.2 Официальные/анонсные данные (расхождения отмечены)

| Параметр | Значение | Источник |
|---|---|---|
| SoC | Snapdragon 8 Gen 5 (SM8845), 3 нм | GSMArena, Croma, iQOO community; Qualcomm brief (до 3,8 ГГц) |
| CPU | 2× Oryon V3 Phoenix L 3,8 ГГц + 6× Phoenix M 3,32 ГГц | GSMArena; Notebookcheck (2P+6E) |
| GPU | **Adreno 829** (iQOO community, Geekbench) **или 840** (GSMArena) — расхождение; Qualcomm модель не называет («sliced Adreno, dedicated memory per slice») | см. ссылки |
| RAM / UFS | 12/16 ГБ LPDDR5X, UFS 4.1 | GSMArena, ритейл |
| Дисплей | 6,59″ AMOLED 1,5K (1260×2750), 144 Гц | GSMArena, Croma |
| ОС | Android 16, OriginOS 6 | GSMArena, iQOO community |
| Аккумулятор | 7600 мА·ч, 100 Вт | GSMArena |

### E.3 Что проверено в этом репозитории применительно к устройству

- Встроенный Turnip композитора для A8xx (`turnip-sdk36`, `vulkan.ad08XX.so`) — Mesa
  `26.0.0-devel (git-ff39aa7716)`, в таблице устройств есть `FD829`, `FD825`, `FD830`, `FD840`
  (строки бинаря). Для A7xx — Mesa `25.0.0-devel`. Выбор по модели ≥8xx → `turnip-sdk36`
  (`TurnipDriver.java:51, 341-353`).
- Модель 829 → `Family.A8XX` → «Supported/TESTED» (`GpuInfo.kt:50, 128`), хотя тестировались 830/840.
- Автовыбор пары драйверов для 829: WinNative Balanced (`DriverPairs.kt:97`).

### E.4 Применимые возможности платформы

- Oryon (все 8 ядер «большие») → не ограничивать маски малым числом ядер (DD-01); SSBS-адаптер
  (H-1); TSO-стоимость (H-2).
- 144 Гц + вероятные режимы 120/90/60 → каденс-совместимая частота (PB-2).
- HWC/DPU-масштабирование слоя (layer path) вместо GPU-блита (PB-1).
- GameManager/`game_mode_config` уже объявлены; OriginOS может иметь свой «игровой режим» — проверить,
  не режет ли он FPS/разрешение (в конфиге уже `allowGameFpsOverride=false`, `allowGameDownscaling=false`).

### E.5 Ограничения OEM/драйвера (гипотезы)

- Агрессивное управление фоном OriginOS может убивать дочерние процессы proot при сворачивании.
- KGSL-приоритеты очередей: в коде отмечено, что Turnip KGSL не передаёт приоритет
  (`droiddeck-session:144-150`), т.е. композитор не может вытеснить игру на GPU.
- Доступность `/sys/class/kgsl/kgsl-3d0/*` и thermal-зон из приложения на OriginOS — проверить.

### E.6 Что обязательно измерить на телефоне

```sh
adb shell getprop ro.product.model; getprop ro.board.platform; getprop ro.soc.model
adb shell cat /sys/class/kgsl/kgsl-3d0/gpu_model /sys/class/kgsl/kgsl-3d0/gpu_chipid
adb shell 'for c in /sys/devices/system/cpu/cpu[0-9]*; do echo $c $(cat $c/cpufreq/cpuinfo_max_freq); done'
adb shell getconf PAGESIZE
adb shell dumpsys display | grep -iE "supportedModes|mRefreshRate|renderFrameRate"
adb shell dumpsys thermalservice | head -40
# внутри сессии DroidDeck: device.txt, session.log (== kernel: page size), wayland.log (stats/perf/pacing)
```

---

## F. План оптимизации (польза / стоимость / риск)

| # | Работа | Польза | Стоимость | Риск | Fallback на других устройствах |
|---|---|---|---|---|---|
| F-1 | Снять baseline по протоколу G на 2–3 играх (DX11/DX12/лёгкая) | основа всего | низкая | нет | — |
| F-2 | Подтвердить драйвер на 829: `vulkaninfo` обоими Turnip, 30 мин игры без GPU hang; при проблемах — DD-Turnip/другая пара | P1 стабильность | низкая | нет | Auto остаётся прежним |
| F-3 | Переключатель layer-режима для SDR (в коде есть `nativeSetZeroCopy`, нет UI) → A/B copy vs layer | средний–высокий GPU/энергия | средняя | средний (HWC-слои, поворот, оверлеи) | выкл. по умолчанию до замеров |
| F-4 | Политика частоты: без лимита и при игре ≤60 fps голосовать за 120 Гц, если панель его предлагает (`cadenceFits(…, 60)`) | judder ↓, энергия ↓ | низкая | низкий (меню при 120 вместо 144) | на 60/120-панелях без изменений |
| F-5 | A/B `TU_DEBUG=sysmem` по играм; при выигрыше GMEM — `sysmem` только для клиента Steam (через `droiddeck-game-env` снимать для игр) | ? (±) | низкая | низкий | профиль игры |
| F-6 | A/B Force SSBS на Oryon; при подтверждении — авто-включение по MIDR Oryon | ? | низкая | низкий | только Oryon |
| F-7 | DD-02 (оценка vsync по `frameTimeNanos`) | малая–средняя для uncapped | низкая | низкий | — |
| F-8 | Асинхронный copy path: 2–3 cmd buffer/fence, release буферов по fence | средняя для потока композитора | высокая | высокий (гонки с release/shm) | только после PB-1 замеров |
| F-9 | DD-05 damage-копия для desktop | высокая для desktop | средняя | средний | — |
| F-10 | DD-03, DD-04 корректность при зависаниях | стабильность | низкая | низкий | — |
| F-11 | ADPF hint session (композитор), логирование thermal status | устойчивость частот | средняя | низкий | API ≥31 |

Не рекомендуется: принудительный pin GPU (`GpuClockPin`) для длительной игры, закрепление трассировщика
proot на отдельных ядрах (доказано вредным, `proot-performance.md`), обход теплозащиты.

---

## G. Измерения

### G.1 Таблица baseline vs optimized

Реальных измерений нет (нет устройства). Все значения — неизвестны.

| Метрика | Baseline (`22f02fb`) | Optimized (эта ветка) | Δ |
|---|---|---|---|
| Средний FPS (игра 1/2/3) | не измерено | не измерено | — |
| 1 % low / 0,1 % low | не измерено | не измерено | — |
| P50/P95/P99 frame time | не измерено | не измерено | — |
| Уникальные показанные кадры/с | не измерено | не измерено | — |
| CPU/GPU время, загрузка | не измерено | не измерено | — |
| Время загрузки игры | не измерено | не измерено | — |
| RAM / GPU memory | не измерено | не измерено | — |
| Температура / частоты через 20 мин | не измерено | не измерено | — |
| Ядра программ «рельса» на 2+6 SoC (тест) | `6,7` | все ядра (без маски) | подтверждено хост-тестом |

Изменения этой ветки не должны менять FPS игр Steam: DD-01 касается только desktop/rail-программ,
`pacing` — учёт, page size — одна строка лога.

### G.2 Протокол сравнения

1. **Фиксация:** коммит DroidDeck, версия рантайма (`/.version`), пары драйверов, Proton/DXVK/VKD3D,
   пресет FEX, `TU_DEBUG`, разрешение сессии, лимит FPS, частота панели, эффекты/FG вкл/выкл,
   режим «Производительность» OriginOS, яркость 50 %, авиарежим, заряд >50 %, без зарядки, 22–26 °C.
2. **Прогрев:** 5 мин в игре до старта записи. **Длительность:** 20 мин (устойчивость) + отдельные
   3-мин окна для «холодного» сравнения. **Повторы:** ≥3, между ними остывание до исходной температуры.
3. **Источники данных:**
   - `wayland.log`: строки `stats`, `perf`, **`pacing`** (новая; p50/p95/p99/max интервалов
     commit и shown, `never shown`, commit→present) каждые 10 с;
   - mangoapp (QAM → Performance) для frame time внутри gamescope;
   - Perfetto (FrameTimeline + планировщик + частоты + питание):
     ```
     buffers { size_kb: 131072 }
     data_sources { config { name: "android.surfaceflinger.frametimeline" } }
     data_sources { config { name: "linux.process_stats" } }
     data_sources { config { name: "android.power" android_power_config { battery_poll_ms: 1000 collect_power_rails: true } } }
     data_sources { config { name: "linux.ftrace" ftrace_config {
       ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_wakeup"
       ftrace_events: "power/cpu_frequency" ftrace_events: "power/gpu_frequency"
       ftrace_events: "thermal/thermal_temperature" atrace_categories: "gfx" atrace_categories: "view" } } }
     duration_ms: 60000
     ```
     `adb shell perfetto --txt -c - -o /data/misc/perfetto-traces/dd.pftrace < cfg.txt`
   - `adb shell dumpsys SurfaceFlinger --latency '<слой SurfaceView>'`, `dumpsys thermalservice`,
     `cat /sys/class/kgsl/kgsl-3d0/{gpuclk,gpu_busy_percentage}` (если читаемо).
   - simpleperf/AGI требуют `profileable` или debuggable-сборки — для бенчмарков собрать вариант с
     `<profileable android:shell="true"/>`.
4. **Анализ:** не смешивать (а) FPS игры (интервалы commit), (б) уникальные показанные кадры
   (интервалы shown), (в) частоту панели (ticks в `perf`). Сравнивать медианы по повторам, разброс
   (min–max) указывать; вывод только если разница больше разброса.

---

## H. Изменения исходного кода

| Коммит | Файлы | Что | Проверка |
|---|---|---|---|
| `aa5832c` fix: desktop and rail programs keep every core on 2+6 SoCs | `droiddeck-session`, `tools/tests/test_program_cores.py` | DD-01 | новый тест: до — 3/9 FAIL, после — 9/9 OK |
| `d4c1574` feat: the compositor logs frame pacing percentiles every 10 s | `waylandcomp/src/frame_stats.{c,h}`, `compositor.c`, `waylandcomp/CMakeLists.txt`, `tools/tests/{frame_stats_harness.c,test_frame_stats.py}` | строка `pacing` в `wayland.log` | 6 хост-тестов OK; мутационная проверка (нижняя граница бина) — 5 FAIL; `clang -fsyntax-only` `compositor.c`/`frame_stats.c` с Android-заглушками — чисто (1 старое предупреждение `g_render_idle`) |
| `8c91341` chore: the session log names the kernel's page size | `droiddeck-session` | H-4 диагностика | `bash -n`, тесты сессии OK |

Полный набор `python3 -m unittest discover -s tools/tests`: 555 тестов, 1 ошибка среды
(`test_cdp_guard` — в песочнице нет IPv6), 25 пропусков; до изменений — 540 тестов с той же ошибкой.

**Не выполнено (ограничения среды):** `./gradlew assembleRelease` и `testDebugUnitTest` (нет Android
SDK), NDK-сборка `libdroiddeckwayland.so`, запуск на устройстве. Новые C-файлы собраны только хостовым
clang; NDK-сборка CI проверит их в первом же прогоне.

---

## I. Следующие шаги (максимальный ожидаемый эффект)

1. Установить сборку этой ветки на iQOO Z11 Turbo; собрать `device.txt`, `session.log`,
   `wayland.log` с 20-мин сессией в одной DX11 и одной DX12 игре — это даст первые реальные
   P50/P95/P99 и ответ на H-4 (page size) и F-2 (драйвер 829).
2. По `perf`/`pacing`: если `fence wait` и `render_scene` max > 3–4 мс или `shown` p95 ≫ p50 —
   заняться PB-1 (F-3 переключатель layer-режима, затем F-8).
3. Если игра стабильно ≤60 fps и `shown` показывает 13,9/20,9 мс — реализовать F-4 (120 Гц).
4. A/B `TU_DEBUG` (sysmem vs autotune) и Force SSBS по протоколу G; решения — по играм/профилям.
5. Исправить DD-02/DD-03/DD-04 малыми патчами с хост-тестами (чистые функции выносятся как
   `frame_stats.c`).
6. Для desktop-режима — DD-05.

---

### Источники по устройству

- [GSMArena: iQOO Z11 Turbo goes official (SD 8 Gen 5, 7600 mAh)](https://m.gsmarena.com/iqoo_z11_turbo_goes_official_with_snapdragon_8_gen_5_7600mah_battery-news-71130.php)
- [GSMArena: vivo iQOO Z11 Turbo specs](https://m.gsmarena.com/vivo_iqoo_z11_turbo_5g-14392.php)
- [iQOO community: Z11 Turbo launched in China](https://community.iqoo.com/in/thread/134805)
- [Croma: iQOO Z11 Turbo announced](https://www.croma.com/unboxed/iqoo-z11-turbo-announced-price-specifications)
- [Qualcomm: Snapdragon 8 Gen 5 product brief](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Product-Brief-Snapdragon-8-Gen-5.pdf)
- [Qualcomm: Snapdragon 8 Gen 5 product page](https://www.qualcomm.com/smartphones/products/8-series/snapdragon-8-gen-5-mobile-platform)
- [Notebookcheck: SD 8 Gen 5 unveiled](https://www.notebookcheck.net/Qualcomm-officially-unveils-Snapdragon-8-Gen-5-SoC-with-Oryon-CPU-cores.1171204.0.html)
- [Notebookcheck: Adreno 829](https://www.notebookcheck.net/Qualcomm-Adreno-829-Benchmarks-and-Specs.1220448.0.html)
- [Android Headlines: Geekbench lists Adreno 829](https://www.androidheadlines.com/?p=1252869)
- [whitebelyash/freedreno_turnip-CI: Turnip A8XX v23 (829 «added, no proper testing»)](https://github.com/whitebelyash/freedreno_turnip-CI/releases/tag/tu_v23)
- [DiskDVD/A8XX-Y PR #80: fixes for A825/829 parameters](https://github.com/DiskDVD/A8XX-Y/pull/80)
