# DroidDeck: аудит пути x86-64 → ARM64 (FEX)

Дата: 2026-10-12. Ветка `claude/trusting-wozniak-01gitb`, коммиты не отправлены.

## 0. Итог

- **Ограничивает ли FPS транслятор, не доказано ни в одну сторону.** Устройства не было, профилирования на устройстве не было, FPS не измерялся. Чтобы это доказать на телефоне, в диагностику запусков добавлено: счётчики FEX, частоты и температуры CPU, флаги CPU, MIDR, хэши модулей FEX, а также сводка `tools/launch_report.py`. Протокол — в разделе 8.
- **Подтверждённые ошибки** (доказательство у каждой — воспроизведение и тест):
  - DroidDeck: общий пресет FEX перекрывал настройки FEX, которые Steam и Proton задают для конкретной игры. Пресет по умолчанию глобально выключал half-barrier TSO. **Исправлено** (`4ef30e4`, `e8286a1`).
  - FEX: в режиме `X87ReducedPrecision=1`, который Proton включает по умолчанию, `FIST/FISTP/FISTTP` пишут насыщенное значение или 0 вместо «integer indefinite». **Есть патч и тест** (`patches/0002`).
  - FEX: на хостах с SVE2 и длиной вектора больше 128 бит ломаются 46 собственных тестов FEX (gather, `vmaskmov`, `pmaddubsw`, `vcvtdq2pd`…). **Есть патч** (`patches/0001`). На путь Windows-игр (ARM64EC/WoW64) не влияет. Затрагивает ли телефоны, зависит от длины вектора, она записывается в диагностику.
  - FEX: #DE (деление на 0, переполнение IDIV) не поднимает SIGFPE. В upstream это признано в его собственном тесте. Патча нет.
  - FEX: биты частного `FPREM/FPREM1` (C0/C3/C1) всегда 0. В 80-битном режиме `FPREM` ещё и неверен при огромной разнице порядков. Патча нет.
  - FEX-2607 (Proton 11.0): `FXAM` и CF у `BLSR/BLSMSK` для 32-битного нуля. Исправлено upstream: FXAM — в FEX-2610, BLSR — уже в пине Experimental.
- **Изменения, которые можно включить безопасно,** — только корректность и наблюдаемость. Ни одна «оптимизация» без замера не включена, модель памяти нигде не ослаблена.

## 1. Схема пути выполнения

```
Steam → reaper → steam-compatibility (bl_run, taskset по BL_GAME_CPUS_SETUP)
 │
 ├─ Windows-игра ─→ droiddeck-game-env ──→ droiddeck-game-diag (запись старта/выхода, tee stderr,
 │                  (переменные пресета,     сэмплер FEX SHM + cpufreq/thermal каждые 10 с)
 │                   уступает Proton             │
 │                   решённые им FEX_*)          ▼
 │                                     proton (Valve, ARM64)  — FEX_DISKCACHE=1, FEX_APP_CONFIG(_LOCATION)
 │                                          │                    ← STEAM_FEX_*, STEAM_COMPAT_FEX_CONFIG,
 │                                          │                      fex_application_profiles
 │                                          ▼
 │                                     wine ARM64EC
 │                                       ├─ x86-64 PE → libarm64ecfex.dll (FEXCore JIT, Windows-фронтенд)
 │                                       │               + aarch64-unix/libarm64ecfex.so (unixlib, FEX ≥ 2607)
 │                                       └─ x86 PE    → libwow64fex.dll + libwow64fex.so
 │                                     DXVK / VKD3D-Proton → Vulkan → Turnip / драйвер → Adreno
 │
 └─ Linux x86-игра ─→ droiddeck-fex steam → FEX (Steam app 3127680, Linux-фронтенд)
                                            в Steam Linux Runtime 3.0 sniper (1628350)
```

Конфигурация FEX собирается слоями. По `LoadOrder` в `FEXCore/Source/Interface/Config/Config.cpp` слой переменных окружения стоит выше всех, а слияние делается через `insert_or_assign`. Поэтому любые `FEX_*` из окружения DroidDeck перекрывают и `FEX_Config.json` Proton, и файл, который Proton пишет для конкретной игры.

## 2. Версии, источники, параметры сборки

| Компонент | Источник | Версия / хэш | Кто управляет |
|---|---|---|---|
| Proton 11.0 | `ValveSoftware/Proton`, `proton_11.0` | HEAD `bc896217` (2026-10-09); FEX-сабмодуль `1cc4b93e` = **FEX-2607** | Valve |
| Proton Experimental 11.0 (DroidDeck его предпочитает) | `experimental_11.0` | HEAD `70b7e109` (2026-10-01); FEX `0df84d38` = **FEX-2609-137-g0df84d384**, за 31 коммит до FEX-2610 | Valve |
| FEX upstream | `FEX-Emu/FEX` | **FEX-2610** = `14c92681f` (2026-10-07) = `main` на 2026-10-12 | — |
| FEX для Linux-игр | Steam app 3127680 | версия на устройстве здесь не определена, пишется в `fex_stats.csv` (`fex_version` из заголовка SHM) | Valve |
| Подмена FEX | `The412Banner/Nightlies`, релиз «FexCore-Linux» (`.wcp`), `ComponentsManager` меняет 4 файла | версии и флаги **не проверены**: API этого репозитория здесь недоступен | сторонний сборщик |
| Wine в Proton Experimental | `ValveSoftware/wine` `6d211aab` | — | Valve |

Сборка FEX в Proton (`Makefile.in`):
- общее для обеих веток: `-DENABLE_FEXCORE_PROFILER=True -DENABLE_LTO=False -DBUILD_TESTING=False -DTUNE_CPU=none -DRANGES_NATIVE=OFF`, `Release`, mingw `arm64ec-w64-mingw32` и `aarch64-w64-mingw32`, unixlib — отдельной сборкой для `aarch64-unix`;
- только в Experimental: `-DBUILD_STEAM_SUPPORT=True`. Из-за этого дисковый кэш FEX кладётся в `$STEAM_COMPAT_SHADER_PATH/fex-emu/`. В 11.0 он лежит в `%LOCALAPPDATA%\fex-emu\` префикса (`Source/Common/Config.cpp`, `GetCacheDirectory`).

`FEX_Config.json` в Proton (blob `f088cfa4`, одинаковый в обеих ветках):

| Параметр | Proton | По умолчанию в FEX |
|---|---|---|
| TSOEnabled | 1 | 1 |
| VectorTSOEnabled / MemcpySetTSOEnabled | 0 / 0 | 0 / 0 |
| HalfBarrierTSOEnabled | 1 | 1 |
| Multiblock | 1 | 1 |
| **MaxInst** | **500** | 5000 |
| **X87ReducedPrecision** | **1** | 0 |
| **ProfileStats** | **1** | 0 |

Сверху Proton накладывает настройки конкретной игры: `STEAM_FEX_TSOENABLED`, `STEAM_FEX_MULTIBLOCK`, `STEAM_COMPAT_FEX_CONFIG` и `fex_application_profiles` (например, для `setup*` Witcher 3 — `X87ReducedPrecision=0`).

**Что в репозитории DroidDeck, а что нет.** Исходников и бинарников FEX в репозитории нет. DroidDeck управляет:
- переменными окружения (пресеты FEX, `FexPreset.kt`; по умолчанию теперь пусто);
- выбором Proton;
- подменой DLL FEX из Nightlies;
- привязкой к ядрам;
- диагностикой.

Всё, что меняет кодогенерацию FEX (раздел 3, патчи), требует пересборки FEX: в Proton это делает Valve, у Nightlies — их сборщик.

**Хэши.** Бинарников с устройства у меня нет. Теперь каждая запись запуска содержит sha256 `libarm64ecfex.dll` и `libwow64fex.dll` (`droiddeck-game-env`, `fex_build`). Два запуска можно сравнивать, только если хэши совпадают.

## 3. Подтверждённые ошибки

Метод проверки: дифференциальный тест `tools/fexdiff`. Одна статическая x86-64 программа (musl) запускается на x86-64 машине (эталон) и под FEX (aarch64 в qemu-user 8.2.2), выводы сравниваются построчно. Плюс собственные ASM-тесты FEX через их `TestHarnessRunner`. Ограничения такой проверки — в разделе 9.

### DD-FEX-1. Общий пресет перекрывал решения Steam и Proton для игры (DroidDeck) — исправлено

- **Где:** `droiddeck-game-env`, `GameEnvironmentStore.publish`.
- **Причина:** переменные `FEX_*` из окружения стоят выше `FEX_APP_CONFIG` (`LoadOrder`).
- **Воспроизведение:** игра со `STEAM_FEX_TSOENABLED=0` при общем пресете с `FEX_TSOENABLED=1` получала TSO=1. Ожидалось решение Steam.
- **Влияние:** терялись и пер-игровые исправления самого Proton.
- **Исправление:** `4ef30e4`. Имена, которые решил Proton, отдаются ему. Пресет, выбранный для самой игры, и окружение сессии по-прежнему главнее.
- **Тесты:** `tools/tests/test_game_environment.py` — разбор `fex_application_profiles` литералом без выполнения, уступка, приоритет пресета игры, сквозной запуск.

### DD-FEX-2. Пресет по умолчанию глобально выключал half-barrier TSO (DroidDeck) — исправлено

- **Где:** `SessionPrefs.kt`, `DEFAULT_FEX_PRESET`.
- **Причина:** против `FEX_Config.json` Proton пресет `PERFORMANCE_TSO` менял один параметр: `HalfBarrierTSOEnabled 1 → 0`. Тогда невыровненный доступ, вызвавший SIGBUS, навсегда переписывается в обычный load/store без упорядочения (`UnalignedHandlerType::NonAtomic`, `FEXCore/Source/Utils/ArchHelpers/Arm64.cpp`).
- **Влияние:** модель памяти x86 ослаблялась для всех игр ради прироста, который никто не измерял.
- **Исправление:** `e8286a1`. По умолчанию переменных FEX нет, сохранённый выбор пользователя не меняется.
- **Тест:** `GameEnvironmentStoreTest.unchosenFexPresetLeavesFexToProton`.
- **Возможный регресс производительности:** игры, которые раньше шли с NonAtomic, теперь платят за half-barrier. Замер: `warm_sigbus_s` и `warm_signal_ms_s` в `launch_report.py`. Откат для конкретной игры — выбрать ей пресет `PERFORMANCE_TSO`.

### FEX-1. FIST/FISTP/FISTTP в режиме пониженной точности x87 — патч `0002`

- **Где:** `FEXCore/Source/Interface/Core/OpcodeDispatcher/X87F64.cpp`, `FISTF64`.
- **Причина:** ARM `fcvtzs`/`fcvtns` насыщают результат и превращают NaN в 0, а x86 пишет «integer indefinite» — наименьшее значение размера назначения. SSE-путь (`CVTFPR_To_GPRImpl`) это корректирует, F64-путь x87 — нет. 16-битная форма к тому же пишет младшие 16 бит 32-битного результата.
- **Воспроизведение:** `fistp qword` от 1e20 → `0x7fffffffffffffff` (x86: `0x8000000000000000`); `fistp dword` от NaN → 0 (x86: `0x80000000`); `fistp word` от 40000,5 → `0x9c40` (x86: `0x8000`).
- **Где проявляется:** во всех трёх пинах (2607, `0df84d38`, 2610), с конфигурацией Proton. В 80-битном режиме всё верно.
- **Влияние:** только x87-код, то есть почти исключительно 32-битные игры (WoW64); 64-битный код считает через SSE. Затронуты преобразования float→int для NaN и значений вне диапазона: код, собранный MSVC, может рассчитывать на `INT_MIN` как на признак ошибки. Конкретных игр с этим симптомом я не знаю.
- **Исправление:** коррекция как у CVT\*2SI: FRINT{32,64}{X,Z} при FEAT_FRINTTS, иначе сравнение с выбором; для 16 бит — проверка диапазона. 64-битное сохранение остаётся в FPR, если есть FRINTTS.
- **Тесты:**
  - новый `unittests/ASM/X87_F64/FIST_Indefinite_F64.asm` падает на FEX-2610 и проходит с патчем на хостах с FRINTTS и без него (qemu `max` и `neoverse-n1`);
  - его 80-битная копия проходит и до, и после;
  - `diffx86 x87`: 20 расхождений → 0.

### FEX-2. Ветки SVE128 при векторе длиннее 128 бит — патч `0001`

- **Где:** `Source/Common/HostFeatures.cpp`. `OverrideFeatures` без `ForceSVEWidth` выключает `SupportsSVE256`, а `SupportsSVE128` остаётся включённым при любом SVE2.
- **Причина:** бэкенд выбирает SVE-инструкции над целым Z-регистром при «SVE128 и не SVE256» (`uunpkhi`, gather, маскированные загрузки/сохранения…). Это верно только при VL=128.
- **Воспроизведение:** qemu `-cpu max,sme=off,sve-default-vector-length=32`. `pmaddubsw xmm0, xmm1` обнуляет верхние 4 слова, `vcvtdq2pd ymm, xmm` берёт верхние double из чужой полосы. JIT-код, снятый через `qemu -d in_asm`, содержит `uunpklo/uunpkhi z.h, z.b` для 128-битной SSE-операции.
- **Собственные тесты FEX:** на FEX-2610 при VL=256 падают 46 тестов из `VEX/H0F38/SSELanePreservation/OpSize`, 16 из них — с SIGSEGV (gather с переполнением индекса). С патчем проходят все 918, при VL=128 и VL=256.
- **Влияние на DroidDeck:**
  - путь Windows-игр не затронут: `Source/Windows/Common/CPUFeatures.cpp` всегда выключает SVE;
  - для Linux-игр затронуты только хосты с SVE2 и VL>128;
  - телефонные Cortex-X/A7xx (SVE2, 128 бит) не затронуты;
  - есть ли у Oryon в Snapdragon 8 Gen 5 обычный (не streaming) SVE2, источники расходятся ([Heise](https://heise.de/-10668204) пишет «SVE и SME», [TechInsights](https://techinsights.com/ja/node/61041) — SME1). Ответ даст `sve_vector_bits` в manifest первого же запуска.
- **Исправление:** при SVE2 и VL≠128 без SVE256 выключать `SupportsSVE128`, тогда работают ASIMD-пути, как на CPU без SVE.

### FEX-3. #DE не поднимается — патча нет

- **Воспроизведение:** `diffx86 sig`, 9 случаев: `div`/`idiv` на 0 (8/16/32/64 бит), `INT_MIN/-1`, переполнение частного. На x86 `SIGFPE` с `FPE_INTDIV`, под FEX сигнала нет, частное — результат ARM `sdiv/udiv` (на 0 это 0).
- **Статус в upstream:** признано в `unittests/FEXLinuxTests/tests/signal/synchronous-signal-block.cpp` («sfpe and afpe fail on arm because we don't raise FPE»), тест в `Known_Failures`.
- **Влияние:** код, который ловит деление на 0 через SEH или сигнал (защита от взлома, некоторые движки), продолжит работу с мусором. Частоту таких игр не оцениваю.
- **Почему нет патча:** нужно проверять делитель в каждом `div` и доставлять исключение в оба фронтенда, Linux и Windows. Это изменение дизайна с ценой на каждое деление, его стоит решать в upstream.

### FEX-4. FPREM/FPREM1 — патча нет

- C0/C3/C1 (младшие биты частного) всегда 0, в обоих режимах x87. Тесты FEX проверяют только остаток и C2.
- В 80-битном режиме `FPREM` вычисляет `lhs − trunc(lhs/rhs)·rhs` с 64-битной мантиссой частного. При разнице порядков больше 63 остаток неверен: для `0x46293e5939a08cea mod 7` получается 0 вместо 5. Настоящий x87 делает частичную редукцию с C2=1. F64-режим использует точный `fmod`, поэтому у Proton остаток верен.
- **Влияние:** биты частного читают x87-реализации редукции аргумента и старые `fmod`. Это 32-битный код, приоритет низкий.

### FEX-5…7. Уже исправлено upstream или известный компромисс

- **FXAM** (класс нуля, бесконечности, NaN, денормала): ошибка в 2607 и пине Experimental, исправлено в FEX-2610 (`6788bcf26`). Подтверждено прогоном: 13 → 5 расхождений, оставшиеся 5 — FPREM.
- **BLSR/BLSMSK 32-бит, CF при нуле:** ошибка только в FEX-2607 (Proton 11.0), исправлено `635befb4c`, уже в пине Experimental.
- **Разрыв split-lock атомиков** при `StrictInProcessSplitLocks=0` (по умолчанию): `lock`-операции через границу кэш-линии и 16 байт теряют или добавляют инкременты (775 609 вместо 800 000). Документированный компромисс FEX; `=1` исправляет ценой глобального мьютекса на каждой такой операции.

### Известные приближения (низкий приоритет)

- Флаги исключений MXCSR не ведутся: 2412 из 2588 строк `sse.*` отличаются только полем `exc`.
- Знак «indefinite» NaN: x86 даёт `0xffc00000`/`0xfff8…`, FEX — `0x7fc00000`/`0x7ff8…`.
- `FCOMI` с NaN в F64-режиме не ставит IE.
- Свойства самого `X87ReducedPrecision=1`, а не ошибки: 80-битные денормалы и максимум не помещаются в f64 (`FXAM` классифицирует их как 0 и ∞), точность 53 бита.

Совпадают с x86 полностью:
- целые числа и флаги — 42 383 строки, неопределённые флаги маскированы по SDM;
- AVX2/FMA/F16C при VL=128 и без SVE;
- SMC, включая двойное отображение;
- syscalls и futex;
- переиспользование JIT.

## 4. Возможные проблемы (с уверенностью)

| # | Что | Уверенность | Как проверить |
|---|---|---|---|
| P-1 | Если внутри bwrap не читается `/sys/devices/system/cpu/cpuN/regs/identification/midr_el1`, Wine (`dlls/ntdll/unix/system.c:2342`) не передаст FEX MIDR. Тогда FEX не узнает Oryon, и обход errata RNDRRS (`HandleErrata`) не сработает: x86 `RDSEED` может зациклиться. | гипотеза; механизм виден в коде, доступ на устройстве не проверен | `launch.guest_cpu_ids` в manifest: список MIDR или ошибка чтения |
| P-2 | Дисковый кэш FEX в Experimental строится из Unix-пути `STEAM_COMPAT_SHADER_PATH` внутри Windows-процесса. Куда он попадает в DroidDeck и переживает ли сессию, не проверено. | средняя | второй запуск игры: `disk_cache_hits > 0` и `cold_jit_ms_s` ниже, чем в первом |
| P-3 | FEX из Nightlies: происхождение и флаги сборки неизвестны, версия может не совпадать с пином Proton. | факт подмены; качество сборки неизвестно | `launch.fex.build` (версия и sha256) в manifest |
| P-4 | `MaxInst=500` у Proton против 5000 по умолчанию: меньшие блоки — больше переходов между блоками, но меньше перекомпиляции. Эффект на Oryon неизвестен. | неизвестно | A/B через `STEAM_COMPAT_FEX_CONFIG` или пресет игры (раздел 8) |
| P-5 | Цена `ProfileStats=1`: счётчики обновляются на компиляцию блока и сигнал, а не на инструкцию. Ожидается мало, но не измерено. | низкая | A/B `FEX_PROFILESTATS=0`, только без потери данных диагностики |
| P-6 | Пресеты `EXTREME*` выключают проверки SMC (`FEX_SMCCHECKS=none`), `PERFORMANCE`/`EXTREME`/`DENUVO` выключают TSO. Это осознанный выбор пользователя с предупреждением в описании. По умолчанию не применяются. | факт | — |

## 5. Профилирование: что сделано и что нет

**На устройстве не сделано ничего:** устройства не было. Время под qemu не отражает ни Oryon, ни барьеры TSO, и в оценках производительности не используется.

Без устройства есть только статическая цена кода из upstream InstCountCI — число ARM64-инструкций на x86-инструкцию в FEX-2610. Это не время выполнения:
- x87, 415 общих операций: медиана 16 (80 бит) против 12 (F64). `fadd/fmul/fdiv st0, st1` — 16 против 9, `fsqrt` — 11 против 5, `fld/fstp qword` — 19 против 12. Это обоснование `X87ReducedPrecision=1` у Proton.
- Дорогие одиночные инструкции:
  - `rep movs*` — 83;
  - `shld r, r, cl` — 22–25;
  - `lock sbb/adc` (16 бит) — 18–20;
  - `vpsadbw ymm` — 21;
  - `vzeroupper` — 16;
  - `pmaddubsw mm` — 13.

  Медиана по основным таблицам — 2–7.

План на устройстве — раздел 8. Каждую игру сначала классифицировать как CPU- или GPU-bound, и только потом искать горячие точки в JIT.

## 6. Upstream: что изучено

Между пином Experimental и FEX-2610 — 31 коммит:

| Коммит | Что | Для DroidDeck |
|---|---|---|
| `6788bcf26` | x87: полный FXAM | исправление корректности (подтверждено прогоном) |
| `5ec147109` | peephole `vpcmpeqd + vgatherdps` | прирост только для AVX2-gather; не измерено |
| `aad2c969b` | ARM64EC: номера NT-syscall без хардкода | только Windows без Wine (ветка `else` при отсутствии Wine-диспетчера); на Proton не влияет |
| `6b43daccf` | Allocator не виснет без `/proc` | DroidDeck монтирует `/proc` в bwrap (`BwrapSpawner.kt`); не влияет |
| `1644d185c` | argv и путь при portable reexec | Linux-фронтенд без binfmt; возможно для `droiddeck-fex`, не проверено |
| `e3f402a38` | ключ DiskCache с gnu build-id | Linux-фронтенд; меньше ложных попаданий кэша |
| `b2dffb5a8`, `c72474224` | версия DiskCache | после обновления FEX кэш строится заново (холодный старт) |

**Вывод:** вручную подменять FEX на 2610 ради этих изменений нет смысла. Исправление FXAM придёт с пином Valve. Два патча из этого аудита применяются ко всем трём пинам (проверено `git apply --check`) и готовы к отправке в upstream (`docs/development/fex-audit/patches/`). Как сторонний вклад они не отправлялись.

## 7. Тесты и регрессии

| Набор | Конфигурации | Результат |
|---|---|---|
| `tools/fexdiff` (9 секций) | пин Experimental, FEX-2607, FEX-2610, патченный 2610 × qemu `max` без SVE (как Oryon), `neoverse-n1` (ARMv8.2: без LRCPC2/LSE2/FRINTTS), SVE2 128 бит, SVE 256 бит × умолчания FEX и конфигурация Proton | расхождения — раздел 3; патч меняет только целевые строки |
| ASM-тесты FEX: X87 (217), X87_F64 (150) | без SVE, FEX-2610 и патченный | 365/367 на обоих; 2 падения (`D9_F2`, `D9_F9`) в upstream `Disabled_Tests` |
| ASM-тесты FEX: VEX, H0F38, SSELanePreservation, OpSize (918) | VL 128 и 256, FEX-2610 и патченный | 2610: 918 и 872; патченный: 918 и 918; **регрессий 0** |
| Новый `FIST_Indefinite_F64.asm` / `vcvtdq2pd.asm` (upstream) | FRINTTS есть / нет; VL 256 | падают на 2610, проходят с патчем |
| Python `tools/tests` | — | 605: 1 ошибка окружения (`test_cdp_guard`, в контейнере нет IPv6), 25 пропущено |
| Kotlin, диагностика (отдельный JVM-проект с заглушками) | — | 48/48 |

**Регрессии совместимости** записываются отдельно:
- от изменений DroidDeck на qemu не наблюдалось;
- единственный ожидаемый риск — DD-FEX-2 (производительность, не корректность).

Как откатить:
- `git revert` отдельного коммита;
- пресет для конкретной игры в интерфейсе;
- `BL_DIAG_SAMPLE_INTERVAL=0` выключает сэмплер.

## 8. Влияние на DroidDeck: протокол замеров на устройстве

Что пишется для каждого запуска (Setup → Diagnostics → экспорт):
- `manifest.json`:
  - устройство, флаги CPU, implementer:part;
  - `sve_vector_bits`, `counter_hz`, MIDR в госте;
  - Proton, сборка FEX с sha256, `FEX_*`, решения Steam и Proton;
  - графический профиль;
- `fex_stats.csv` — счётчики FEX каждые 10 с;
- `cpu.csv` — частоты и самая горячая зона;
- `performance.csv` — FPS и интервалы кадров композитора.

Порядок замеров:
1. **Классификация.** Одна сцена, 10 минут, 3 повтора. Затем `python3 tools/launch_report.py --warmup 90 <папки>`.
   - **GPU-bound:** высокие `fence_wait`/`render` в `performance.csv`, частоты CPU не на максимуме, `warm_jit_ms_s ≈ 0`. Транслятор не главный ограничитель, дальше смотреть DXVK/VKD3D и драйвер.
   - **CPU-bound:** высокие частоты больших ядер, FPS не растёт при снижении разрешения. Переходить к шагу 2.
2. **Холодная и тёплая фаза.** Первый запуск после установки против второго:
   - `cold_jit_ms_s`, `warm_jit_ms_s`, `disk_cache_hits/misses` — эффект дискового кэша, P-2;
   - `warm_jit_blocks_s > 0` надолго — постоянная перекомпиляция, смотреть `warm_smc_s`;
   - `warm_sigbus_s` и `warm_signal_ms_s` — цена TSO и невыровненных атомиков, сравнить с DD-FEX-2.
3. **Горячие точки в транслированном коде** (только CPU-bound):
   - `simpleperf` на debug-APK: release-сборка DroidDeck не debuggable и не profileable, на телефоне без root `simpleperf` к ней не подключится. Вариант — `<profileable android:shell="true"/>` в манифесте; это решение для вас, я его не вносил;
   - для имён JIT-блоков: `FEX_GLOBALJITNAMING=1` (весь JIT как один символ: доля времени в транслированном коде) или `FEX_BLOCKJITNAMING=1` (по блокам, пишет `/tmp/perf-<pid>.map` с накладными расходами на каждый блок);
   - работает ли это в ARM64EC-сборке под Wine и как `simpleperf` прочитает карту из `/tmp` гостя, **не проверено**.
4. **A/B по одной переменной**, 3+ повтора, одна сцена, телефон остывший, одинаковый заряд, `launch.fex.build.sha256` совпадает:
   - E1: `MaxInst` 500 против 5000 (P-4);
   - E2: `HalfBarrierTSOEnabled` 1 против 0 — только как замер цены, не как значение по умолчанию;
   - E3: `StrictInProcessSplitLocks` 0 против 1 для игр с подозрением на split lock;
   - E4: пин FEX (Proton 11.0 против Experimental, или пакет Nightlies).

   Результат — таблица `launch_report.py`: медиана по повторам, FPS, оценка 1% low, частоты, температура.

## 9. Ограничения

- **Устройство:** не было. Ни одного FPS, частоты, температуры или профиля с iQOO Z11 Turbo нет. Наличие и длина SVE у Oryon V3 не установлены.
- **Эмуляция:** все прогоны FEX — Linux-фронтенд под qemu-user 8.2.2 на x86-64.
  - Windows-фронтенд (ARM64EC/WoW64, путь Windows-игр) не запускался. Общий у них FEXCore (декодер, IR, JIT, x87, SSE), а не обработка сигналов и исключений.
  - В qemu 8.2.2 `LDAPUR` с отрицательным смещением возвращает 0 (проверено отдельной aarch64-программой `rcpc2.c`). Поэтому все прогоны идут с `FEX_HOSTFEATURES=disablelrcpc2`, и пути LRCPC2, которыми FEX пользуется на Oryon, не покрыты.
  - Litmus-тесты модели памяти под qemu на x86-хосте ничего не доказывают и не проводились.
- **glibc static** под FEX/qemu падает при выделении TLS. Взят musl; это свойство окружения, не ошибка FEX.
- **Сборки:** Android (AGP/NDK) здесь не собирается. JNI-функции `nativeCounterFrequency`/`nativeSveVectorBits` проверены отдельным фрагментом под qemu, но не в составе `libwaylandcomp`. Kotlin проверен в отдельном JVM-проекте с заглушками.
- **Nightlies:** список релизов недоступен из этой сессии.

## 10. План по приоритету

1. Поставить сборку на телефон. Первый же экспорт диагностики закроет P-1 (MIDR), P-3 (какой FEX реально запущен) и вопрос SVE у Oryon.
2. Классификация CPU/GPU-bound на 2–3 играх (раздел 8, шаг 1). Без неё любые изменения FEX — догадки.
3. Отправить патчи `0002` (FIST) и `0001` (SVE128) в FEX upstream. Ценность `0002` для DroidDeck выше: он затрагивает конфигурацию Proton по умолчанию. После принятия они придут через пин Valve.
4. Если P-1 подтвердится (MIDR не читается), отдать гостю `midr_el1` через bind в `BwrapSpawner`, по образцу `/proc/cpuinfo`.
5. Если P-2 подтвердится (кэш не переживает запуск), разобраться с путём кэша для Experimental (`FEX_APP_CACHE_LOCATION`).
6. Эксперименты E1–E4 только для CPU-bound игр. Решения — для конкретных игр, через профили. Глобальные изменения семантики — нет.
7. #DE и FPREM — к обсуждению в upstream (issue с воспроизведением из `tools/fexdiff`). Сам их не переписываю: нет доказательств, что это нужно играм DroidDeck.

## Коммиты аудита

| Коммит | Что |
|---|---|
| `8fefbaa` | дифференциальный тест `tools/fexdiff` |
| `4ef30e4` | DD-FEX-1: пресет не перекрывает решения Steam и Proton |
| `e8286a1` | DD-FEX-2: по умолчанию настройки FEX у Proton, half-barrier TSO сохранён |
| `0c8b8ab` | диагностика: счётчики FEX, частоты и температуры CPU для каждого запуска |
| `ab9a546` | флаги CPU, SVE, implementer:part в manifest; `tools/launch_report.py` |
| `cc2fc36` | новые случаи в `diffx86`; патчи FEX с тестами |
| `937ea36` | MIDR в госте и sha256 модулей FEX в записи запуска |
