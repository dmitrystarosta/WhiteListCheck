# PROJECT.md

Техническая документация проекта WhiteListCheck. Меняется редко.
Актуально на v0.5.1 (versionCode 13).

## Описание проекта

«Белый список?» — Android-приложение для диагностики режима «белого списка»
мобильного интернета в РФ. Определяет по доступности трёх групп сайтов,
что происходит с сетью: норма / белый список / нет интернета / VPN.

- Репозиторий: https://github.com/dmitrystarosta/WhiteListCheck
  (переименован из NetStatus; старые ссылки работают через редирект GitHub)
- Релизы: https://github.com/dmitrystarosta/WhiteListCheck/releases
- Сайт: https://belyjspisok.ru/
- RuStore: https://www.rustore.ru/catalog/app/ru.netstatus.app
- Почта проекта: belyjspisok@starosta.ru
- Лицензия: MIT, © 2026 Dmitry Starosta (шрифт Golos Text — OFL)
- Название приложения на устройстве: «Белый список?»
- Имя APK-файла: WhiteListCheck_v<версия>.apk

## Архитектура

Одна Activity (MainActivity), UI на Jetpack Compose, весь код в одном
Kotlin-файле (~1510 строк). Виджет — на RemoteViews (Compose в виджетах
не работает в принципе). Логические блоки внутри MainActivity.kt
в порядке следования:

**Константы и модель данных**
- `REPO_RELEASES`, `RUSTORE_URL` — ссылки для подвала и кнопки «поделиться»
- `Probe`, `ProbeResult`, `Verdict`, `ScanState` — модель. В ScanState
  есть поле `checkedAt` (время получения показанного результата) — нужно
  для синхронизации с виджетом, см. ниже

**Списки сайтов**
- `ProbeConfig` — встроенные списки (defaultA/B/C), константа
  REMOTE_CONFIG_URL и парсер JSON `{"a":[{"name","url"}],"b":[...],"c":[...]}`
  для будущего удалённого обновления (сейчас URL пуст)
- `ProbeStore` — пользовательские списки в SharedPreferences
  ("netstatus" / "custom_lists"); приоритет над REMOTE_CONFIG_URL
- `probeFromDomain` — разбор введённого домена в Probe

**Сканирование**
- `Scanner` — object: параллельные HEAD-запросы к favicon.ico
  (HttpURLConnection, таймаут 4000 мс, редиректы выкл.; «доступен» =
  получен любой HTTP-код), перевод исключений в русские сообщения
  (humanError), определение типа сети через ConnectivityManager /
  NetworkCapabilities (**TRANSPORT_VPN проверяется ПЕРВЫМ** — с Android 12
  VPN-сеть сообщает и нижележащий транспорт), вычисление вердикта
  по большинству

**Виджет (v0.5)**
- `StatusWidgetUpdater` — object: `update()` перерисовывает все
  размещённые виджеты по last_verdict + last_check_ts;
  `showChecking()` показывает спиннер на время проверки;
  `iconFor()` — вердикт → drawable; `scanPendingIntent()` — тап
- `StatusWidget` (AppWidgetProvider) — onUpdate (система) и onReceive
  (наш ACTION_WIDGET_SCAN = тап по виджету)
- `WidgetScanWorker` (CoroutineWorker) — разовая проверка по тапу;
  уведомлений НЕ шлёт, всегда пишет результат и обновляет виджет

**Фон**
- `CheckWorker` (CoroutineWorker) — периодическая проверка: пропускает
  «нет сети», сравнивает вердикт с сохранённым, при изменении шлёт
  уведомление (канал "netmode", PendingIntent через
  getLaunchIntentForPackage); на API 33+ проверяет POST_NOTIFICATIONS
- `scheduleBackground` / `cancelBackground` — PeriodicWorkRequest 15 мин,
  constraint NetworkType.CONNECTED, уникальное имя "netcheck",
  ExistingPeriodicWorkPolicy.UPDATE

**Оформление**
- `golosTypography()` — Typography на шрифте Golos Text (4 начертания)
- `isAppDark()` — FEATURE_LEANBACK (ТВ всегда тёмный) || системная тема
- `verdictColors`, `statusBadgeColors`, `warnColor`, `dangerColor`,
  `StatusColors` — палитра, ВСЕ завязаны на isAppDark()
- `AppTheme` — обёртка MaterialTheme
- `Modifier.tvFocusHighlight()` — подсветка фокуса D-pad
  (onFocusChanged ДО clickable, иначе не работает)

**UI (Composable)**
- `App` — держит ScanState и CoroutineScope сканирования, переключает
  главный экран / настройки
- `MainScreen` — закреплённые шапка и карточка вердикта + LazyColumn;
  функция runScan(); DisposableEffect с LifecycleEventObserver
  для синхронизации с виджетом
- `NetworkChip` — чип «Сеть: … ⓘ» с пояснениями
- `ShareVerdictButton` / `shareVerdict` — отправка вердикта в мессенджер
- `GroupCard` — сворачиваемая карточка группы со сводкой «N/M доступны»
- `SettingsScreen` / `EditableGroup` — экран «Списки сайтов»
- `VerdictCard`, `StatusBadge`, `ProbeRow` (кликабельное имя →
  LocalUriHandler), `Footnote` (расшифровка ошибок + сноска про
  Instagram), `AppFooter` (копирайт + версия из PackageManager,
  ссылка REPO_RELEASES)

## Ключевые решения по состоянию (не ломать)

- **ScanState и CoroutineScope сканирования живут в `App`,
  не в `MainScreen`** (v0.4.3). MainScreen покидает композицию при
  переходе в настройки: remember-состояние уничтожалось вместе
  с результатами, а scope отменялся — проверка обрывалась и
  running=true оставался навсегда («вечное Проверяю…»).
- **Раскрывашки — на rememberSaveable**, не remember (v0.4.2):
  LazyColumn уничтожает уехавшие за экран элементы.
- **Синхронизация с виджетом** (v0.5.1): виджет и фон пишут вердикт
  в prefs, но экран об этом не знает. На ON_RESUME сверяем
  last_check_ts с ScanState.checkedAt — если снаружи проверка новее
  И вердикт другой, перезапускаем runScan(). Совпадающий вердикт
  не трогаем. LocalLifecycleOwner берётся из
  androidx.compose.ui.platform (в Compose 1.7+ переехал
  в androidx.lifecycle.compose).

## SharedPreferences "netstatus"

| Ключ | Смысл |
|---|---|
| bg_enabled | включена ли фоновая проверка |
| last_verdict | имя последнего вердикта (Verdict.name) |
| last_check_ts | время последней проверки, мс — для виджета и синхронизации |
| custom_lists | пользовательские списки сайтов (JSON) |

Пишут все три пути проверки: ручная (runScan), фоновая (CheckWorker)
и виджет (WidgetScanWorker).

## Структура каталогов

```
WhiteListCheck/
├── .github/workflows/build.yml
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/ru/netstatus/app/MainActivity.kt   # ВЕСЬ код
│       └── res/
│           ├── drawable/ic_launcher.xml
│           ├── drawable/banner.xml                 # баннер Android TV
│           ├── drawable/widget_bg.xml
│           ├── drawable/widget_logo_normal.xml
│           ├── drawable/widget_logo_whitelist.xml
│           ├── drawable/widget_logo_vpn.xml
│           ├── drawable/widget_logo_nonet.xml
│           ├── drawable/widget_logo_neutral.xml
│           ├── font/golos_text_*.ttf               # regular/medium/semibold/bold
│           ├── layout/widget_status.xml
│           └── xml/widget_info.xml
├── docs/badges/                     # картинки для README (кнопки, виджет)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── LICENSE
├── FONT_LICENSE.txt
├── PRIVACY.md
├── README.md
├── AI_CONTEXT.md · PROJECT.md · STATE.md · TODO.md
```

## Описание основных файлов

- **MainActivity.kt** — весь код приложения (см. Архитектура).
- **AndroidManifest.xml** — разрешения INTERNET, ACCESS_NETWORK_STATE,
  POST_NOTIFICATIONS; label «Белый список?»; icon @drawable/ic_launcher;
  banner для ТВ; theme @android:style/Theme.Material.Light.NoActionBar;
  usesCleartextTraffic="false"; **configChanges="uiMode|orientation|
  screenSize"** (смена темы и поворот не пересоздают Activity);
  LEANBACK_LAUNCHER + uses-feature required=false (Android TV);
  receiver StatusWidget с APPWIDGET_UPDATE и meta-data widget_info.
- **widget_status.xml** — разметка виджета. ТОЛЬКО RemoteViews-
  совместимые view: LinearLayout, ImageView (widget_icon), TextView
  (widget_time), ProgressBar (widget_progress, indeterminate, скрыт
  по умолчанию).
- **widget_info.xml** — 1×1 (minWidth/minHeight 40dp), resizeMode
  horizontal|vertical, updatePeriodMillis 30 мин (подстраховка;
  основные обновления шлёт приложение).
- **ic_launcher.xml** — векторная иконка «чебурнет» (глобус с ушами):
  фон #4E342E, заливка #6D4C41, контуры #FFCCBC, viewport 220.
  Без узнаваемого персонажа (авторские права). Для магазинов есть
  растровая icon_512.png из тех же координат.
- **app/build.gradle.kts** — versionCode/versionName; зависимости
  (Compose BOM, activity-compose, material3, coroutines,
  work-runtime-ktx); signingConfig "release", читающий
  keystore.properties из корня (файл создаётся только в CI из секретов);
  applicationId ru.netstatus.app; buildTypes.release: minify выкл.,
  debuggable выкл.
- **gradle.properties** — android.useAndroidX=true, jvmargs, code style.
- **settings.gradle.kts** — rootProject.name = "netstatus"
  (внутреннее имя, наружу не видно).
- **README.md** — описание, «Когда пригодится» с ключевыми фразами
  для поиска, таблица групп, вердикты, виджет + легенда цветов,
  установка, сборка, планы, обратная связь, сноска про Instagram.

## Принцип работы приложения

1. Пользователь жмёт «Проверить», тапает виджет или срабатывает
   фоновый воркер.
2. Списки берутся из ProbeStore (пользовательские) или встроенные;
   если задан REMOTE_CONFIG_URL — попытка скачать свежие (не критично).
3. Параллельные HEAD-запросы ко всем сайтам трёх групп.
4. Подсчёт «большинства живых» в каждой группе → вердикт → цветная
   карточка; вердикт и время пишутся в prefs, виджет перерисовывается.
5. Фон: вердикт сравнивается с прошлым; отличие → push-уведомление.
6. Возврат в приложение: если снаружи была более свежая проверка
   с другим вердиктом — экран сам перепроверяет сеть.

## Процесс сборки

Локальная сборка не используется. Всё собирает GitHub Actions на каждый
push в main (плюс ручной запуск workflow_dispatch). Готовый APK —
в Artifacts сборки. Релиз оформляется вручную через GitHub Releases
(тег vX.Y.Z, приложить APK, Set as the latest release).

## GitHub Actions (.github/workflows/build.yml)

1. checkout, JDK 17 (temurin)
2. Prepare keystore: `echo "$KEYSTORE_BASE64" | base64 -d > release.jks`;
   генерация keystore.properties (storeFile=../release.jks + пароли/алиас
   из секретов)
3. Build: если нет gradlew — `gradle wrapper --gradle-version 8.7`;
   `./gradlew assembleRelease`
4. Rename: app-release.apk → WhiteListCheck_v<версия>.apk
   (archivesBaseName в Gradle не работает — не повторять)
5. upload-artifact

Секреты: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.

## Подпись APK

- Release-ключ: локально у владельца C:\AndroidKeys\whitelistcheck-release.jks,
  алиас whitelistcheck, RSA 4096, validity 10000 дней; копия файла и пароль —
  в облаке владельца. Пароль ключа = паролю хранилища.
- В CI ключ восстанавливается из секрета KEYSTORE_BASE64.
- История: первый ключ скомпрометирован (keystore.properties с паролями
  закоммичен в публичный репозиторий) и заменён 06.07.2026. Правило:
  ключи и пароли в репозиторий не попадают никогда; git хранит историю.
- Потеря ключа = невозможность обновлять приложение в магазине.
- Из-за смены ключа обновление поверх версий ≤0.2 невозможно —
  старую версию нужно удалять.

## Особенности публикации в RuStore

- Аккаунт физлица: бесплатно, вход через VK ID, верификация по паспорту
  (фото + лицо), без УКЭП. Аккаунт зарегистрирован и верифицирован.
- Требуется: release-подписанный APK, иконка 512×512, минимум
  2 скриншота, краткое + полное описание, ссылка на политику
  конфиденциальности, категория (Инструменты), возраст (0+).
- versionCode должен строго расти; занятый номер магазин отклонит.
- Скриншоты для карточки подготовлены БЕЗ упоминания Instagram
  (обрезаны выше блока «Заблокированные в РФ») — чтобы не смущать
  модерацию. В описании карточки заблокированные ресурсы тоже
  не называются: «контрольная группа ресурсов с ограниченной
  доступностью».
- Консоль: console.rustore.ru. Модерация — от часов до пары дней.

## История принятых технических решений

- **Однофайловый код** — осознанно: владелец правит файлы через
  веб-GitHub, один файл проще заменять целиком.
- **HttpURLConnection вместо OkHttp** — ноль лишних зависимостей.
- **HEAD к favicon.ico, «жив = любой HTTP-ответ»** — отличает реальную
  доступность (TLS + ответ) от DNS-заглушек и сбросов соединения.
- **Вердикт по большинству** — один лежащий сайт не даёт ложной тревоги.
- **Тема @android:style/...** — библиотека тем Material3 не подключена;
  две сборки падали на Theme.Material3.DayNight.NoActionBar.
- **material-icons-extended не подключать** — тяжёлый; в базовом наборе
  нет Icons.Filled.Cancel (используются Check/Close).
- **gradle.properties добавлен после падения** checkDebugAarMetadata
  (android.useAndroidX=true).
- **Имя пакета ru.netstatus.app сохранено** при переименовании
  репозитория: смена пакета = другое приложение для Android.
- **Иконка вектором в XML** — рисуется текстом через веб-редактор,
  весит <1 КБ, масштабируется.
- **Ошибки по-русски + сноска** — вместо имён исключений; три разных
  механизма блокировок наглядно различимы.
- **МосКостюмер добавлен в группу B, затем удалён** (v0.2) — слишком
  нишевый сайт. **Drive2 и Banki.ru убраны** (v0.2.1) — открывались
  при белом списке.
- **VPN проверяется первым** в networkType (v0.4.1) — с Android 12
  VPN-сеть сообщает и нижележащий транспорт.
- **Виджет: абсолютное время вместо относительного счётчика** (v0.5) —
  RemoteViews статичен, счётчику нужна перерисовка каждую минуту,
  MIUI её душит → вечное «сейчас». Часы верны без обновлений.
- **Тап по виджету = проверка, а не открытие приложения** (v0.5) —
  иначе виджет дублирует иконку. Двухзонный тап (верх/низ) рассмотрен
  и отклонён (22.07.2026): на 1×1 мишени слишком мелкие и зоны
  не обозначены.
- **PendingIntent через getLaunchIntentForPackage** (v0.5) — прямой
  Intent(MainActivity) открывал приложение в сброшенном виде.
- **Секреты GitHub + assembleRelease в CI** — после инцидента
  с утечкой паролей.
- **Google Play отложен**: оплата $25 из РФ затруднена, для личных
  аккаунтов нужны 12 тестировщиков на 14 дней; приоритет — RuStore.
  На горизонте 2027 — глобальная верификация Android-разработчиков
  Google даже для sideload (следить за новостями).
