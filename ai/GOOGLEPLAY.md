# GOOGLEPLAY.md

Публикация «Белого списка?» в Google Play. Файл-двойник ai/FDROID.md:
история, правила, грабли. Обновлять после каждого шага.

Последнее обновление: 08.09.2026 (релиз 17 (0.5.5) отправлен на ревью
в закрытый трек, идёт набор тестировщиков).

## Аккаунт и приложение

- Тип аккаунта: **личный** (создан 08.09.2026) → действует правило
  «12 тестировщиков × 14 дней» перед доступом к production.
- Приложение создано 08.09.2026. Package `ru.netstatus.app`,
  язык по умолчанию **ru-RU**, тип App, Free.
- Automatic protection (проверка источника установки) — **отключена**
  при создании. Причина: основной канал — RuStore, приложение открытое
  и раздаётся из F-Droid/GitHub; подталкивать пользователей в Play
  против интересов проекта.
- External marketing (реклама приложения вне Play) — рассмотрено,
  решение владельца. Изменения вступают в силу до 60 дней.

## Регистрация имени пакета (Android developer verification)

С 30.03.2026 Play требует регистрации package name. Новые имена
регистрируются автоматически; `ru.netstatus.app` уже жил на Android
(RuStore, F-Droid, GitHub) → потребовалось доказать владение ключом.

Как прошло (08.09.2026):

1. Play Console → **Android developer verification** → Package names →
   Register package name.
2. Ключ `58:44:D6:44:B8:93:…:09:33` нашёлся в списке eligible
   (Google знал о нём по установкам) → «Add key».
3. Дополнительно потребовалось доказательство: консоль выдала сниппет
   (одна строка, вида `CSJ3ZGMXUVUNYAAAAAAAAAAAA`), который надо было
   положить в `app/src/main/assets/adi-registration.properties`,
   собрать release-APK своим ключом и загрузить в консоль.
4. Сборка делалась в **отдельной ветке `adi-registration`** (не в main,
   чтобы не сдвигать коммит под F-Droid), через workflow_dispatch.
   После успеха ветка удалена.
5. Статус: Draft → In review → **Registered / Verified** (08.09.2026).

Важно: файл `adi-registration.properties` в релизных сборках НЕ нужен
и не должен там быть — это идентификатор аккаунта разработчика.

Побочная польза: с 30.09.2026 незарегистрированные пакеты перестают
ставиться на сертифицированные Android-устройства (сначала Бразилия,
Индонезия, Сингапур, Таиланд). Регистрация нужна была бы и без Play.

## Play App Signing — свой ключ (КРИТИЧНО)

**Google генерирует app signing key автоматически при создании
приложения.** Если не вмешаться, Play-версия получит чужую подпись и
станет отдельным приложением: пользователь из RuStore не сможет
обновиться из Play без удаления, и наоборот.

Меняли так (08.09.2026, до первой загрузки AAB, install base 0%):

1. **Protected with Play** → раздел **Play Store protection** (раскрыть
   шевроном) → строка «Protect app signing key» → **Manage Play app
   signing**. (Пункт App integrity в меню — заглушка «settings have
   moved».)
2. **Change key** → **Export and upload a key from Java keystore**.
3. Скачать `pepk.jar` и `encryption_public_key.pem`, положить рядом
   с keystore (у владельца: `C:\AndroidKeys\whitelistcheck-release.jks`).
4. Команда (Windows, cmd открыт прямо в папке через `cmd` в адресной
   строке Проводника):

```
java -jar pepk.jar --keystore=whitelistcheck-release.jks --alias=whitelistcheck --output=output.zip --include-cert --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem
```

5. Загрузить `output.zip` в консоль. Проверить отпечаток: и **App
   signing key**, и **Upload key** должны показывать
   `58:44:D6:44:B8:93:D2:99:A5:9C:F2:CC:DC:97:93:7E:DD:CA:DC:B6:35:C4:AF:EB:D8:D2:B3:C0:F7:E9:09:33`.
6. `output.zip` после загрузки удалить.

**Upload key оставлен тем же ключом** (пункт «create a new upload key»
пропущен намеренно) — иначе пришлось бы держать второй keystore и
править `build.yml`. Сейчас CI подписывает всё одним ключом.

Данные ключа (`keytool -list -v`): alias `whitelistcheck`, PKCS12,
RSA 4096, SHA384withRSA, действителен до 21.11.2053,
CN=Dmitry Sukhobok, O=Dmitry Sukhobok, L=Moscow, C=RU.

Следствие: `whitelistcheck-release.jks` теперь завязан на ЧЕТЫРЕ канала
(Play, RuStore, F-Droid `AllowedAPKSigningKeys`, GitHub). Потеря =
потеря обновлений везде.

## targetSdk 36 — обязательное требование

С 31.08.2026 новые приложения и обновления в Play должны таргетировать
**Android 16 (API 36)**. Исключения: Wear OS, Automotive, Android TV, XR.

Апгрейд сделан в ветке `sdk36` (08.09.2026), двумя шагами:

**Шаг 1 — инструменты** (поведение приложения не меняется):
| что | было | стало |
|---|---|---|
| AGP | 8.4.0 | **8.9.1** (минимум для API 36) |
| Gradle | 8.7 | **8.11.1** (минимум для AGP 8.9) |
| compileSdk | 34 | **36** |
| Kotlin / Compose BOM | 1.9.24 / 2024.06.00 | **не трогали** |

Фолбэк `gradle wrapper --gradle-version` в `build.yml` тоже поднят
до 8.11.1.

**Шаг 2 — targetSdk 36 + edge-to-edge.** С Android 15 приложения с
targetSdk 35+ рисуются под системными панелями, в Android 16 для
targetSdk 36 отключить это уже нельзя (флаг
`windowOptOutEdgeToEdgeEnforcement` убран). Правки в `MainActivity.kt`:

- `onCreate`: `enableEdgeToEdge()` перед `super.onCreate()` — чтобы
  поведение было одинаковым и на старых версиях.
- `AppTheme`: убраны `window.statusBarColor` и
  `window.navigationBarColor` (на Android 15+ не действуют). Управление
  светлыми/тёмными значками панелей через `WindowCompat` — оставлено,
  работает.
- `App()`: `Surface` по-прежнему `fillMaxSize()` (фон должен уходить
  ПОД панели, иначе за часами просвечивает белый фон окна), а
  содержимое обёрнуто в `Box(Modifier.fillMaxSize().safeDrawingPadding())`.
  `safeDrawing` = системные панели + вырез камеры + клавиатура.

Проверено (08.09.2026): Xiaomi 13T Pro (Android 15, HyperOS 2.0.205) —
шапка не заезжает под часы, подвал не режется навигацией, тёмная тема
без белой полосы, клавиатура в редакторе списков не перекрывает поле;
Sony Android TV — insets нулевые, ничего не сместилось.

## Сборка AAB

Play принимает только AAB. `build.yml` теперь собирает оба формата
одним прогоном: `./gradlew assembleRelease bundleRelease`, дальше два
артефакта — `WhiteListCheck` (APK для RuStore/F-Droid/GitHub) и
`WhiteListCheck-aab`. Один versionCode, один коммит, никакого
«дублирования версий».

Предупреждение «There is no deobfuscation file associated with this App
Bundle» при загрузке — ожидаемо и игнорируется: `isMinifyEnabled = false`,
R8 выключен ради воспроизводимости сборки для F-Droid.

## Карточка приложения (store listing)

- Название: `Белый список?`
- Короткое описание (лимит 80): «Проверяет, включён ли режим белого
  списка мобильного интернета в вашем регионе.» (79 символов)
- Полное описание: адаптировано из RuStore. Убрано упоминание
  «приставок и Android TV» (форм-фактор TV в Play не настроен),
  оставлено «Поддержка телефонов и планшетов».
- Иконка 512×512 — из `site/assets/icon.png`.
- **Feature graphic 1024×500** — сделана специально для Play
  (08.09.2026): логотип, название, подзаголовок, скриншот в рамке
  телефона; фирменные цвета, Golos Text. Существующая `og-image`
  НЕ подходит: другой размер и бейджи RuStore/GitHub — **упоминание
  сторонних магазинов в графике Play запрещено**.
- Скриншоты: 8 штук из `site/assets/screenshots/` (1080×1920).
- AI asset declaration: **Don't label assets** — графика собрана
  программно из собственных ассетов, генеративных изображений нет.

## Анкеты App content

Заполнено 08.09.2026, почти всё «нет»:

- Privacy policy: `https://github.com/dmitrystarosta/WhiteListCheck/blob/main/PRIVACY.md`
  (политика на сайте `/privacy/` — про посетителей сайта и Яндекс.Метрику,
  и там прямо сказано, что она не заменяет политику приложения).
- Sign in details — вход не требуется. Ads — рекламы нет.
  Government apps / Financial features / Health — нет.
- **Advertising ID — нет** (отдельная обязательная декларация для
  targetSdk 33+; без неё релиз не отправляется на ревью).
- Content rating: категория All Other App Types → PEGI 3 / ESRB
  Everyone / USK 0 / IARC 3+.
- Target audience: **13-15, 16-17, 18+**. Группы младше 13 не
  отмечать — иначе Families-политика с доп. требованиями.
- Data safety: данные не собираются и не передаются.
- Категория: **Tools**, теги Network connectivity + Tools.

## Закрытое тестирование

Правило для личных аккаунтов после 13.11.2023: минимум **12
тестировщиков, непрерывно opted-in последние 14 дней**, затем заявка на
production (ревью обычно ≤7 дней).

Механика:
- Считаются **уникальные Google-аккаунты**, а не устройства.
- Тестировщик засчитывается только после перехода по ссылке и нажатия
  «Стать тестировщиком». Добавить email в список — недостаточно.
- Опт-ин «липкий»: удаление приложения не снимает, снимает только явный
  выход.
- **Отсчёт 14 дней начинается с подключения 12-го.** Предельного срока
  набора нет, но растягивать невыгодно.
- Google смотрит не только счётчик, но и реальную активность; в заявке
  анкета «какую обратную связь получили и что изменили».
- Брать с запасом 14–15 человек. Установка — только через Play по
  ссылке-приглашению, раздача APK не засчитывается.

Трек Alpha: 17 стран (РФ + СНГ + Прибалтика/Финляндия/Норвегия —
русскоязычная диаспора и приграничье), список «Тестировщики 0.5.5»,
канал обратной связи `belyjspisok@starosta.ru`.

## Грабли (не повторять)

- **Ctrl+A в веб-редакторе GitHub не выделяет весь файл**, если фокус
  не внутри области кода. Дважды получили дубль содержимого
  (`build.yml` → «Invalid workflow file», `app/build.gradle.kts` →
  «Only one plugins block is allowed»). Порядок: клик В КОД → Ctrl+A →
  Delete → убедиться, что пусто → вставить. Для больших файлов лучше
  **Add file → Upload files** с указанием пути.
- **Пароль keystore при русской раскладке** → `keytool error:
  Password is not ASCII`. Символы при вводе не отображаются, ошибка
  неочевидна. Переключать на ENG.
- App integrity в меню Play Console — заглушка; настройки подписи
  переехали в **Protected with Play → Play Store protection**.
- Экран Review в store listing показывает описание **без переносов
  строк** — это артефакт превью, в поле редактирования и в карточке
  переносы на месте.
- Кнопка **Run workflow** живёт на странице списка ранов workflow,
  а не на странице конкретного рана. На странице рана есть только
  Re-run jobs — она берёт СТАРЫЙ коммит.
- Ветку в веб-редакторе GitHub проще всего задать через адрес:
  `github.com/<user>/<repo>/edit/<branch>/<path>`.

## Ссылки

- Консоль: play.google.com/console (приложение `ru.netstatus.app`)
- Требование API 36: developer.android.com/google/play/requirements/target-sdk
- Правило тестирования: support.google.com/googleplay/android-developer/answer/14151465
- Минимальные AGP для API: developer.android.com/build/releases/about-agp
