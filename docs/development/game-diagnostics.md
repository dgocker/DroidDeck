# Game Diagnostics в DroidDeck

Дата: 2026-10-12. Ветка `claude/trusting-wozniak-01gitb`. Коммиты от `c07a1ed` до HEAD,
база — `94142b6`. Ничего не опубликовано.

**Что проверено, а что нет.** Всё ниже проверено тестами на хосте. Телефона не было, поэтому:
- Android/NDK-сборка (AGP) **не запускалась**: `dl.google.com` недоступен из среды;
- Robolectric **не запускался**;
- приложение на устройстве **не запускалось**;
- FPS и накладные расходы **не измерялись**.

Как я проверял без них, описано в разделе 9. Что сделать на устройстве — в разделе 11.

---

## 1. Что получилось

Пользователь запускает игру, играет и выходит или ловит ошибку. Дальше:

1. Гостевой лаунчер записывает начало и конец запуска.
2. Приложение собирает отдельную папку `files/diagnostics/<игра>/<запуск>/`.
3. Запуск появляется в Setup → Diagnostics → **Game diagnostics** с датой, длительностью и статусом.
4. Запуск экспортируется в zip.

В zip лежат реальные данные. Недостающие источники названы в `manifest.json` с причиной.
Пустых файлов-заглушек нет.

Цепочка «гостевой модуль → inbox → финализатор → zip» проверена сквозным прогоном. Настоящий
`droiddeck-game-diag` запускал фейковую «игру» (скрипт печатает строки DXVK/FEX и
`wine: Unhandled page fault` и выходит с кодом 5). Настоящие `SessionFinalizer` и `DiagnosticExport`
разобрали записи. Результат:
- статус `crashed`, основание `evidence` (по отчёту о сбое в выводе игры);
- собраны `wine.log`, `fex.log`, `vulkan.log`, `crash/game-crash-report.txt`, `process_exit.json`, `session.log`;
- `wayland.log`, `android.log`, `performance.csv` помечены отсутствующими с причиной;
- в экспорте `-AUTH_PASSWORD=<redacted> STEAM_TOKEN=<redacted>`.

Этот прогон — scratch-тест, в репозиторий он не входит.

---

## 2. Изменённые файлы

| Коммит | Что | Файлы |
|---|---|---|
| `c07a1ed` | Запуск игры стартует дочерним процессом, лаунчер ждёт его и записывает начало и конец | `tools/linuxfs/overlay/usr/local/bin/droiddeck-game-diag` (новый), `droiddeck-game-env`, `droiddeck-fex`, `session/SessionFiles.kt`; `tools/tests/test_game_diag.py` (новый), `test_droiddeck_fex.py` |
| `589df88` | Единый формат событий, кольцевой буфер, асинхронная запись | `diagnostics/DiagEvent.kt`, `EventRing.kt`, `EventLog.kt`; `test/.../DiagTestKit.kt`, `EventLogTest.kt` |
| `2a5ac83` | Классификация завершения только по записям | `diagnostics/LaunchRecords.kt`, `ExitClassifier.kt`; `ExitClassifierTest.kt` |
| `86162d6` | Папка запуска с manifest, нарезки логов, `performance.csv`, хранилище и ротация | `diagnostics/LogSlicer.kt`, `PerformanceCsv.kt`, `OutputScan.kt`, `DiagnosticsStore.kt`, `SessionFinalizer.kt`; `SessionFinalizerTest.kt` |
| `5452cbb` | Экспорт zip с редактированием секретов и проверкой | `diagnostics/DiagnosticExport.kt`; `DiagnosticExportTest.kt` |
| `023f442` | Менеджер в жизненном цикле приложения: inbox, восстановление, зеркалирование событий | `diagnostics/DiagnosticsManager.kt`, `DiagnosticsPrefs.kt`, `InboxPolicy.kt`; `App.kt`, `MainActivity.kt`, `session/SessionService.kt`, `SessionEvents.kt`, `CrashHandler.kt`, `wayland/CompositorHost.kt`; `InboxPolicyTest.kt` |
| `6ec0205` | UI: игры, история, сводка, экспорт и удаление | `diagnostics/DiagnosticsActivity.kt`, `ui/GameDiagnosticsScreen.kt` (новые), `ui/FrontEndScreen.kt`, `ui/FrontEndSetup.kt`, `MainActivity.kt`, `AndroidManifest.xml`, `res/values/strings.xml`, `res/values-ru/strings.xml` |
| `a96119e` | Закончившийся запуск сразу уходит из «Сейчас запущено»; при выключенной диагностике опрос не стартует | `DiagnosticsManager.kt`, `SessionService.kt` |

Итого 39 файлов, +4321/−13 (`git diff --stat 94142b6..a96119e`).

---

## 3. Архитектура

```
Steam → reaper → steam-compatibility (bl_run) → droiddeck-game-env ─┐
                                   droiddeck-fex steam (Linux-игры) ─┤
                                                                     ▼
                   droiddeck-game-diag.run()  (гость, Python, тот же процесс)
                     ├─ Popen(Proton | пакет esync | FEX), сигналы пересылаются ребёнку
                     ├─ поток diag-tee: stderr ребёнка → прежний stderr (session.log) + <id>.out.log (до лимита)
                     ├─ <id>.start.json  (атомарно: tmp + fsync + rename)
                     └─ waitpid → <id>.exit.json (сырой статус), exit(код игры | 128+N)
                                                                     │  files/diagnostics/inbox/
                                                                     ▼
 DiagnosticsManager (приложение, поток game-diag, фоновый приоритет)
   ├─ опрос inbox раз в 2 с во время сессии; <id>.owner: run_id приложения и хост-сессия; heartbeat 30 с
   ├─ InboxPolicy.decide(): ждать / завершать (11 с после выхода; сразу в конце сессии)
   ├─ SessionFinalizer → files/diagnostics/<игра>/<запуск>/  (сначала .partial, потом rename)
   ├─ sessionEnded(): из collectSessionArtifacts, до удаления папки сессии
   ├─ recover(): при старте приложения — недоделанные запуски прошлого процесса
   └─ EventLog: SessionEvents.record() → post() → очередь → поток diag-log → events.jsonl
 GameDiagnosticsScreen (DiagnosticsActivity) ← DiagnosticsStore; экспорт → DiagnosticExport → share sheet
```

Почему `exec` заменён на дочерний процесс. Раньше `droiddeck-game-env` делал `os.execvpe`, и статус
выхода игры получал только reaper Steam. Теперь лаунчер остаётся родителем:
- сигналы `TERM/INT/HUP/QUIT/USR1/USR2` пересылаются игре;
- Steam получает тот же код, что и раньше (для сигнала — `128+N`, как в `bl_run` для store-игр);
- `close_fds=False` и `restore_signals=False` сохраняют наследуемые дескрипторы и диспозиции сигналов, как при `exec`;
- `SIGCHLD=SIG_IGN` от родителя восстанавливается в ребёнке через `preexec_fn`.

Без `BL_DIAG_DIR` или с `DROIDDECK_DIAGNOSTICS=0` путь прежний: `exec`, и модуль даже не загружается.

### Формат события (`session.log`, `events.jsonl`)

```json
{"ts":"2026-10-12T10:15:30.123Z","wall_ms":1760264130123,"mono_ns":81234567890,"source":"guest","severity":"info","event":"game.started","fields":{...}}
```

`mono_ns` — CLOCK_BOOTTIME. В приложении это `SystemClock.elapsedRealtimeNanos()`, в госте —
`time.clock_gettime_ns(CLOCK_BOOTTIME)`. Часы общие, поэтому длительность считается по ним и
не ломается при переводе часов. Уровни: `error`, `warn`, `info`, `debug`.

### Папка запуска

| Файл | Источник | Когда есть |
|---|---|---|
| `manifest.json` | — | всегда. Содержит: схему (`schema_version: 1`), игру, время (UTC, boot clock, часовой пояс), статус с основаниями, устройство (модель, SoC, Android, page size, ядро, ABI), сборку (версия, коммит), конфигурацию запуска (лаунчер, Proton, sync, отредактированные аргументы, запрошенный графический профиль, выбор сессии: layer mode, частота, лимит FPS, Vulkan-драйвер, HDR), то, что сказал вывод игры (устройство, драйвер, версии DXVK и VKD3D-Proton), файлы с размером и sha256, `missing` с причинами, `warnings`, лимиты, счётчики коллектора |
| `process_exit.json` | exit-запись гостя + вердикт | всегда (без exit-записи — `exit_record_present: false`) |
| `session.log` | события запуска: start/exit гостя, события приложения в окне запуска, вердикт | всегда (если запись не упала — тогда warning) |
| `wine.log` / `game.log` | stderr дерева процессов игры (Proton, Wine, DXVK/VKD3D-Proton, FEX, Mesa), копия лаунчера | если вывод был. Для Proton — `wine.log`, для Linux/FEX — `game.log` |
| `fex.log` | строки вывода игры, где упомянут FEX | если такие строки есть |
| `vulkan.log` | строки про Vulkan, DXVK/VKD3D, Mesa/Turnip, драйвер | если такие строки есть |
| `wayland.log` | строки лога композитора от −5 до +11 с вокруг запуска | если лог композитора доступен |
| `android.log` | logcat процесса DroidDeck (`app.log`) в том же окне | если `app.log` есть |
| `performance.csv` | 10-секундные строки `stats`/`perf`/`pacing` композитора | если в окне есть хоть одна сводка |
| `fex_stats.csv` | живая статистика FEX (`/dev/shm/fex-<pid>-stats`, FEXCore SHMStats v2). Лаунчер снимает её раз в `BL_DIAG_SAMPLE_INTERVAL` с (по умолчанию 10, `0` выключает) для каждого FEX-процесса из дерева игры. Строка — накопленные суммы по потокам: время в JIT и в обработчиках сигналов, SIGBUS (невыровненный атомарный доступ при TSO), SMC-инвалидации, softfloat-фоллбеки x87, промахи кэша блоков и время его блокировок, число скомпилированных блоков, попадания и промахи дискового кэша. Если известна частота таймера (`counter_hz` в manifest, `cntfrq_el0`), рядом с каждым `*_ticks` идёт `*_ms` | если FEX запускался с `ProfileStats=1` (так в `FEX_Config.json` Proton) и смог создать файл |
| `cpu.csv` | `scaling_cur_freq` каждого ядра и самая горячая thermal zone с тем же шагом | если ядро дало прочитать sysfs |
| `crash/game-crash-report.txt` | первая строка отчёта о сбое в выводе игры и 79 строк после неё | только при проблемном статусе и найденном отчёте |
| `crash/last-events.jsonl` | последние события из кольцевого буфера | только при проблемном статусе |
| `crash/android-crash-buffer.txt` | строки `logcat -b crash` в окне запуска | только при проблемном статусе и непустом окне |
| `crash/app-crash.txt` | трейс исключения DroidDeck (`crash-app.txt`) | только если приложение упало в окне запуска |

---

## 4. Источники логов

**Подключены:**
- **Android/Kotlin:** `app.log` (logcat своего pid, `SessionLogCapture`) → `android.log`. События `SessionEvents` зеркалируются в `EventLog`.
- **Нативный уровень и композитор:** `wayland.log` → `wayland.log` (срез) и `performance.csv`.
- **Wine/Proton/DXVK/VKD3D/Mesa:** stderr дерева процессов игры → `wine.log`. Это тот же поток, что раньше попадал только в `session.log` сессии. Туда он попадает и сейчас.
- **FEX:** строки вывода игры, где упомянут FEX, включая `droiddeck-fex: SIGILL at …` от `faultreport.c` → `fex.log`. Плюс переменные `FEX_*` в manifest для Linux-игр.
- **Vulkan и драйвер:** строки вывода → `vulkan.log`. Запрошенный ICD (`VK_DRIVER_FILES`, имя файла) и драйвер, выбранный в приложении, — в manifest.
- **Ошибки запуска:** `exec_errors` (ENOENT/EACCES) и откат на стоковый Proton.
- **Сигналы, коды выхода, core-флаг:** сырой статус `waitpid`.
- **FPS и frame time:** `performance.csv`, на уровне композитора.

**Пока не собираются, и почему:**

| Источник | Причина |
|---|---|
| Ошибки до `droiddeck-game-env` (например, «no ARM64 Proton depot yet» в `steam-compatibility`, отказ Steam запустить compat tool) | Лаунчер не запускался, записывать некому. Видно в `session.log` хост-сессии |
| Linux-игры, которые Steam запускает без compat tool, программы режима `run` и десктопные не-Proton программы | Не проходят через `droiddeck-game-env` или `droiddeck-fex steam` |
| Собственные лог-файлы DXVK (`<exe>_d3d11.log`) и `steam-<appid>.log` Proton (`PROTON_LOG=1`) | Пишутся в каталог игры или в `$HOME` гостя. Пути можно добавить позже, сейчас они не копируются |
| Внутренние frame time игры | Композитор видит только выход gamescope (игра плюс оверлеи). MangoHud-логи не подключены |
| Отдельный лог FEX | Под Proton ARM64EC FEX живёт внутри процесса Wine и пишет в тот же stderr. Своего файла нет |
| Tombstone и minidump | Tombstone приложению недоступны. Wine minidump не включён. Core-файлы не собираются (флаг core dump только читается из статуса) |
| Зависание игры | Ничто в записях не отличает зависшую игру от медленной. Статуса «hang» нет, это указано в manifest |

---

## 5. Классификация завершения (`ExitClassifier`)

| Статус | Правило | Основание |
|---|---|---|
| `exited` | код 0 | confirmed. Отчёт о сбое в выводе при коде 0 попадает только в примечание |
| `exited_with_error` | ненулевой код, без отчёта о сбое и без пересланного стопа | ambiguous: коды Windows-игр свои. Для кода > 128 — подсказка про сигнал N−128, «не доказательство» |
| `crashed` | сигнал SEGV/BUS/ILL/FPE/ABRT/TRAP/SYS — confirmed; ненулевой код и строка-отчёт (`wine: Unhandled …`, `err:seh … Unhandled exception`, `droiddeck-fex: SIGILL at`, `*** stack smashing ***`, …) — evidence | |
| `stopped` | лаунчер получил TERM/INT/HUP/QUIT/KILL извне и переслал его | confirmed |
| `killed` | другой сигнал (прежде всего SIGKILL), который никто не пересылал | ambiguous: отправитель неизвестен (LMK, reaper, force-stop) |
| `launch_failed` | команду запуска не удалось стартовать | confirmed, ошибка exec в evidence |
| `lost` | exit-записи нет, сессия завершилась, процесс мёртв | inferred |
| `interrupted` | exit-записи нет, запуск вёл другой (умерший) процесс приложения; `boot_id` различает перезагрузку | inferred |
| `unknown` | exit-записи нет, лаунчер исчез при живой сессии (через 5 с ожидания) | inferred |
| `running` | процесс жив (pid и start time из `/proc/<pid>/stat` совпадают) | — |

Закрытие окна или ранний выход **никогда** не считаются сбоем.

---

## 6. Приватность и лимиты

- **Редактирование при экспорте.** Каждая строка каждого текстового файла проходит через `LogRedactor.redactForShare`: токены Steam, аккаунты, GUID, query в URL, собственные и LAN-адреса. Затем `DiagnosticRedactor`: значения всего, что названо как секрет (`*PASSWORD*`, `*TOKEN*`, `*SECRET*`, `API_KEY`, `AUTH*`, `SESSION_KEY`, `COOKIE`, `epicovt`), в формах `K=V`, `K: V`, `"K": "V"`. У JSON-значений сохраняются кавычки, поэтому manifest после редактирования парсится (проверено тестом).
- **Редактирование в госте.** Аргументы запуска редактируются ещё при записи (`-AUTH_PASSWORD=<redacted>`, `--password <redacted>`, query в URL), путь к exe обрезается до `steamapps/common/…`.
- **Что не собирается.** Содержимое документов пользователя и данные других приложений. Берутся только stderr игры, логи DroidDeck и записи лаунчера.
- **Дампы.** Дамп памяти не включается никогда. Core-файлы не собираются.
- **Лимиты.** Копия stderr: 8/32/128 МБ (Minimal/Normal/Verbose). Срезы логов — по 8 МБ, выборки и `session.log` — по 2 МБ, `fex_stats.csv` и `cpu.csv` — по 4 МБ. `events.jsonl` не больше 2×4 МБ (ротация в `.1`). Хранится до 20 запусков на игру и не больше 512 МБ всего; удаляются самые старые. Недоделанные `.partial` и зависшие `.tmp` чистятся при старте приложения.
- **Мало места.** Если свободно меньше 64 МБ, пишутся только записи и вывод игры, срезы пропускаются (видно в warnings и missing). Экспорт стартует, только если места хватает на размер запусков плюс 16 МБ. Если диск заполнился во время записи, `.part` удаляется.
- **Отправка.** Автоматической отправки нет. Zip уходит только через системный share sheet по нажатию пользователя.

---

## 7. Влияние на игровой путь

- **Что делает игра.** Пишет stderr в pipe на 1 МБ (`F_SETPIPE_SZ`), как раньше писала в pipe `dd`. Файл пишет поток `diag-tee` в процессе лаунчера, блоками по 64 КБ. Если поток не поспевает, `write()` игры подождёт, как и раньше с `dd`.
- **`EventLog.post()`.** Ставит событие в кольцо и делает `offer` в ограниченную очередь. Блокировок нет, на диск не пишет. Тест: 10 000 событий при «зависшем» диске проходят меньше чем за 2 с, все записи идут только из потока `diag-log`.
- **Опрос inbox.** Раз в 2 с листинг каталога с несколькими файлами на фоновом потоке. Heartbeat — `setLastModified` раз в 30 с.
- **Стоимость, не измеренная на устройстве.** На каждый запуск игры остаётся живым процесс Python-лаунчера (RSS порядка 10–20 МБ — оценка, не замер) и один лишний перенос stderr. Сэмплер раз в 10 с читает несколько файлов в `/dev/shm`, `/proc/<pid>/stat` и sysfs. FEX пишет счётчики сам, при `ProfileStats=1` (так у Proton); сэмплер его настройку не меняет. **FPS не измерялся.** Утверждать, что FPS не изменился, нельзя. A/B-проверка — в разделе 11.

---

## 8. Тесты: какое требование чем покрыто

| Требование | Тест |
|---|---|
| 1. Сессия создаётся автоматически | `SessionFinalizerTest.aNormalExitBecomesAFolderWithItsRecordsAndOutput`; `InboxPolicyTest`; `test_game_diag.GameEnvRecordsTest.test_a_recorded_launch_…` |
| 2. Изоляция игр и перезапусков | `SessionFinalizerTest.launchesOfDifferentGamesAndRelaunchesStaySeparate` |
| 3. Нормальный выход | `ExitClassifierTest.codeZeroIsANormalExit`; `test_game_diag.test_a_normal_exit_is_recorded_with_code_zero` |
| 4. Аварийный выход с кодом | `ExitClassifierTest.aNonzeroCode…`, `aCrashSignalIsACrash`; `SessionFinalizerTest.anErrorExitWithACrashReport…`; `test_an_error_exit_keeps_its_code`, `test_a_crash_signal_and_its_core_dump_flag` |
| 5. Принудительное завершение, неоднозначный статус | `ExitClassifierTest.aForcedKillIsAmbiguous…`; `SessionFinalizerTest.aForcedKillIsRecordedAsAmbiguous`; `test_a_kill_is_a_signal_not_an_exit_code`; `test_a_signal_from_outside_is_passed_on_and_named` |
| 6. Незавершённая сессия после перезапуска | `SessionFinalizerTest.anUnfinishedLaunchFoundAfterARestartIsInterrupted…`, `aLaunchCutOffByTheSessionsEndIsLost`, `aLaunchStillRunningIsLeftInTheInbox`, `aFinalizeCutShortIsDoneAgainFromTheInbox`; `InboxPolicyTest.aLaunchAnEarlierAppRunLeftIsFinishedAtOnce` |
| 7. Переполнение кольцевого буфера | `EventLogTest.theRingKeepsTheNewestEventsInOrder`, `postingNeverWaits…` |
| 8. Ротация и лимиты | `EventLogTest.theFileRotatesAndStaysWithinTwiceItsLimit`; `SessionFinalizerTest.retentionKeepsTheNewest…`, `aCappedOutputIsMarkedTruncated`; `test_the_copy_stops_at_its_cap_…` |
| 9. Ошибки записи и мало места | `EventLogTest.aFailingDiskLosesOnlyTheDiskCopyAndRecovers`; `SessionFinalizerTest.lowDiskKeeps…`, `aFileThatCannotBeWritten…`; `DiagnosticExportTest.anExportNeedsRoom…`, `aDiskThatFillsUpMidway…`; `test_records_are_skipped_not_fatal_…`, `test_an_unwritable_output_file_…` |
| 10. Корректность manifest и zip | `SessionFinalizerTest.theManifestListsEveryFileWithItsSizeAndHash`; `DiagnosticExportTest.theZipHolds…AndVerifies`, `aDamagedZipFailsVerification` |
| 11. Редактирование секретов | `DiagnosticExportTest.secretsAreRedactedOnTheWayOut`; `test_credentials_in_launch_arguments_are_blanked` |
| 12. Нет блокирующей записи на игровом пути | `EventLogTest.postingNeverWaitsForTheDiskAndNeverWritesOnTheCallersThread`; `test_stderr_is_copied_and_still_passed_on` (копию делает поток лаунчера) |

Процессы в тестах фейковые: `FakeLauncher` пишет записи гостя, `fake_popen`/`fake_wait` в Python
подставляют статусы. Реальные процессы — только крошечные `python -c …` для проверки tee, сигналов
и `SIGCHLD`.

**Результаты:**
- `python3 -m unittest discover -s tools/tests -p 'test_*.py'`: 590 тестов, 25 skipped, 1 error. Ошибка — `test_cdp_guard.test_guarded_ipv6_bind_is_loopback_only`: в контейнере нет IPv6 (`Errno 97`), эта ошибка была и до изменений.
- Новые тесты: `test_game_diag.py` — 17, в `test_droiddeck_fex.py` — 1 новый и расширен `test_steam_verbs`.
- Kotlin: 44 теста в `app/src/test/.../diagnostics/` прошли (`DiagnosticExportTest` 6, `EventLogTest` 7, `ExitClassifierTest` 11, `InboxPolicyTest` 5, `SessionFinalizerTest` 15).
- pyflakes 3.2.0 по изменённым Python-файлам: замечаний нет.

---

## 9. Сборка и статический анализ: что запускалось, а что нет

- **`./gradlew assembleRelease` и `testReleaseUnitTest` не запускались.** Android Gradle Plugin и SDK недоступны (`dl.google.com` закрыт), NDK не запускался.
- **Kotlin-тесты.** Прогнаны в scratch JVM-проекте (Kotlin 2.1.0, JUnit 4.13.2) с настоящим `org.json` 20240303. `RobolectricTestRunner` заменён заглушкой — этим тестам нужен только настоящий `org.json`. Тесты помечены `@RunWith(RobolectricTestRunner::class)`, как остальные тесты проекта с `org.json`, поэтому в CI они пойдут под Robolectric. Под ним они не запускались.
- **`DiagnosticsManager`** скомпилирован против `android.jar` (API 34), `compose-runtime` 1.6.11 и заглушек с сигнатурами, скопированными из `SessionState`, `SessionPrefs`, `CompositorHost` и других.
- **UI** (`GameDiagnosticsScreen`, `DiagnosticsActivity`) скомпилирован Compose-компилятором Kotlin 2.1.0 против JetBrains Compose Desktop 1.6.11 (`material3`, `foundation`, `ui`; BOM приложения 2024.02 — та же линия 1.6). Android-only API (`stringResource`, `LocalContext`, `BackHandler`, `ComponentActivity`) и виджеты приложения подставлены заглушками с теми же сигнатурами. Все 63 строковых ключа и 1 plurals проверены на наличие в `values/strings.xml`. Предупреждений компилятора нет.
- **Не проверено компиляцией.** Правки в `SessionService.kt`, `MainActivity.kt`, `FrontEndSetup.kt`, `FrontEndScreen.kt`, `App.kt`, `SessionEvents.kt`, `CrashHandler.kt` — это 1–9 строк вызовов с полными именами. Файлы целиком не компилировались. Ресурсы прошли только проверку XML-разбором. Android Lint не запускался.

---

## 10. Ограничения Android и сбоев

- **Нативные сбои.** Tombstone (`/data/tombstones`) приложению не отдаются. `logcat -b crash` отдаёт приложению только его собственные записи, поэтому `crash/android-crash-buffer.txt` — это сбои процессов DroidDeck (compositor, proot), а не всего устройства.
- **Сбой Windows-игры.** Wine перехватывает его и пишет в stderr. Это и есть «отчёт о сбое», который попадает в `crash/`. Minidump не создаётся.
- **Core-файлы.** Не собираются. Флаг `core_dumped` в `process_exit.json` — только то, что сообщило ядро.
- **SIGKILL от LMK, reaper или force-stop.** Отправитель не определяется (`killed`, ambiguous). Если вместе с игрой убит лаунчер, exit-записи нет: статус `lost`, `unknown` или `interrupted` по обстоятельствам.
- **`wayland.log` при выключенных логах.** Композитор открывает лог один раз на процесс. Если «Session logs» выключены, со второй сессии в том же процессе файл первой может быть уже удалён. Тогда `wayland.log` и `performance.csv` попадут в missing с причиной.
- **Окно времени.** Строки композитора и logcat отбираются по локальному времени устройства (часовой пояс приложения). Запуск через полночь обрабатывается, запуск длиннее суток — нет.
- **Задержка конца сессии.** В конце сессии менеджер ждёт до 30 с, пока завершатся игры (они пишут exit при остановке). Это фоновый поток `session-collect`, но освобождение папки сессии сдвигается на это время.

---

## 11. Проверка на устройстве (iQOO Z11 Turbo или любом другом)

Подготовка: собрать APK (`./gradlew assembleDebug`), установить, затем
`adb shell run-as com.droiddeck.launcher ls files/diagnostics` (должен быть `inbox/`).

1. **Пустое состояние.** Setup → Diagnostics → **Game diagnostics**: текст «No game launches recorded yet», переключатель «Record game launches» включён.
2. **Нормальный выход.**
   - Запустить Steam-сессию и Proton-игру. Во время игры `adb shell run-as com.droiddeck.launcher ls files/diagnostics/inbox` показывает `<id>.start.json`, `<id>.out.log`, `<id>.owner`. На экране диагностики игра в «Running now».
   - Сыграть 1–2 минуты и выйти из меню игры. Через ~15 с в истории игры будет запуск со статусом «Exited normally» и верной длительностью. `inbox` пуст.
   - В сводке: `wine.log`, `vulkan.log` (для DXVK/VKD3D), `wayland.log`, `android.log`, `performance.csv`, `process_exit.json`, `session.log`. В «Not collected» — `fex.log` с причиной (если строк FEX нет) и `game.log`.
3. **Остановка из Steam.** Запустить игру и нажать «Stop» в Steam: статус «Stopped», в основаниях «the launcher was sent SIGTERM and passed it on». Если Steam убил дерево через SIGKILL, будет `lost` или `unknown` — это тоже допустимый и честный результат.
4. **Сбой по сигналу.**
   - Взять `child_pid` из `<id>.start.json` и выполнить `adb shell run-as com.droiddeck.launcher kill -SEGV <child_pid>`: статус «Crashed», «killed by SIGSEGV».
   - С `kill -9 <child_pid>`: «Killed», примечание про неизвестного отправителя.
   - Это проверка канала. Для реального сбоя нужна игра, которая падает.
5. **Конец сессии во время игры.** Запустить игру, остановить сессию из шторки: «Stopped» или «Lost when the session ended».
6. **Смерть приложения.** Запустить игру, выполнить `adb shell am force-stop com.droiddeck.launcher`, открыть приложение: запуск «Interrupted» с текстом про остановку DroidDeck. После перезагрузки во время игры текст будет про перезапуск устройства (по `boot_id`).
7. **Экспорт.**
   - «Export this launch» открывает share sheet. Сохранить zip на ПК: внутри `EXPORT.txt` и `diagnostics/steam-<appid>/<id>/manifest.json` (валидный JSON).
   - Выполнить `grep -ri` по имени аккаунта Steam и по строкам `token`/`password`: совпадений нет, только `<redacted…>`.
8. **Удаление.** «Delete this launch» и «Delete all launches» с подтверждением; папки исчезают из `files/diagnostics/steam-<appid>/`.
9. **Detail = Minimal.** Выбрать уровень, начать новую сессию, запустить игру. В `process_exit.json` → `exit_record.output.cap` = 8388608.
10. **Выключение.** Выключить «Record game launches», начать новую сессию, запустить игру: в `inbox` ничего не появляется, а родитель процесса Proton — сам reaper Steam, лишнего процесса лаунчера нет (`adb shell run-as com.droiddeck.launcher ps -A -o PID,PPID,NAME`). То же даёт строка `DROIDDECK_DIAGNOSTICS=0` в `droiddeck-env`.
11. **Накладные расходы, которые здесь не измерены.**
    - Одна игра и одна сцена 5 минут дважды: с диагностикой и с `DROIDDECK_DIAGNOSTICS=0`.
    - Сравнить `python3 tools/pacing_report.py --skip 3 A/wayland.log B/wayland.log`.
    - Посмотреть `adb shell top -p <wrapper_pid>` (RSS и CPU лаунчера, `wrapper_pid` из start.json).
    - Только после этого можно что-то утверждать про FPS.

---

## 12. Откат

- **Без пересборки.** Выключить «Record game launches» или добавить `DROIDDECK_DIAGNOSTICS=0` в `droiddeck-env`: лаунчеры снова делают `exec`, как до изменений.
- **Полностью.** `git revert a96119e 6ec0205 023f442 5452cbb 86162d6 2a5ac83 589df88 c07a1ed`.
- **Данные.** Хранятся в `files/diagnostics/` и удаляются вместе с данными приложения или кнопками удаления в UI.
