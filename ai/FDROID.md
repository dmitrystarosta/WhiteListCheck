# FDROID.md

История и правила публикации «Белый список?» (ru.netstatus.app) в F-Droid.
Отдельный документ, потому что процесс F-Droid сильно отличается от RuStore/GitHub.
Последнее обновление: 04.09.2026.

**Статус: практически готово** — воспроизводимая сборка сошлась, все проверки
F-Droid зелёные, MR стоит в очереди на ревью/мёрж. Дальше — ожидание.

---

## Что и где

- **Каталог F-Droid — это отдельный GitLab-репозиторий** `gitlab.com/fdroid/fdroiddata`
  (НЕ GitHub с исходниками). Приложение добавляется туда через merge request.
- **MR:** `!46918` — `gitlab.com/fdroid/fdroiddata/-/merge_requests/46918`
- **Форк:** `gitlab.com/dmitrystarosta/fdroiddata`, ветка `ru.netstatus.app`
- **Файл метаданных:** `metadata/ru.netstatus.app.yml` (в форке, ветка `ru.netstatus.app`)
- **Отпечаток сертификата подписи (публичный, не секрет):**
  `5844d644b893d299a59cf2ccdc97937eddcadcb635c4afebd8d2b3c0f7e90933`
- **Ключевой коммit сборки:** `3db0b1910f9a4a8fa5ab20ea093f656e46ec55f7` —
  из него собран публичный `WhiteListCheck_v0.5.4.apk` И его же собирает
  F-Droid ⇒ сборка воспроизводима, приложение пойдёт **с подписью автора**.
- **Участники MR:** `linsui` — мейнтейнер F-Droid (ведёт MR, запускает
  настоящие пайплайны); `duckniii/seeker` — репортёр заявки RFP (советы).

## Как устроена сборка в F-Droid (важно понимать)

- F-Droid **собирает приложение из исходников сам**, на своих серверах, из
  **строго указанного коммита** (`commit:` в yml). Он НЕ запускает твой
  GitHub Actions и НЕ берёт «последний main» — только вмороженный коммит.
- Метаданные для карточки (иконка, скриншоты, описания, changelog) F-Droid
  читает **из того же собираемого коммита** — значит они должны лежать в нём.
- **Настоящие пайплайны** идут в проекте `fdroid/fdroiddata` и запускаются
  мейнтейнером (linsui). **Пайплайны на твоём форке (`bdpiwatcher/Data`)
  всегда красные с «0 jobs» и баннером «verify your account» — это ШУМ**
  (неверифицированный аккаунт GitLab, раннеров нет). **Верифицироваться НЕ надо.**
  Форк-письма «Failed pipeline … 0 failed jobs» просто игнорировать.

## Пройденный путь (кратко)

1. **Сборка падала:** `No suitable gradle version found`. Причина — Gradle
   wrapper генерировался в CI и не коммитился в репозиторий. **Фикс:**
   закоммичены `gradle/wrapper/` + `gradlew`/`gradlew.bat`. (Теперь в
   `build.yml` шаг `if [ ! -f gradlew ]; then gradle wrapper …` пропускается —
   CI использует закоммиченный wrapper 8.7, тот же, что и F-Droid.)
2. Добавлены fastlane-метаданные (иконка, скриншоты, описания en-US + ru-RU,
   changelog'и) и сборочный коммит переведён на тот, где они есть.
3. **Война с CRLF (`^M`):** веб-редактор GitLab + буфер обмена Windows
   постоянно вставляли CRLF, ломая джоб `fdroid rewritemeta`. **РЕШЕНИЕ:
   yml редактировать ТОЛЬКО через кнопку «Replace»** (загрузка готового
   файла), НЕ копипастом. Контроль: размер файла (LF-версия меньше на число
   строк).
4. **Метаданные-джобы починены:** `rewritemeta` (LF + перевод строки в конце),
   `schema validation` (`AutoUpdateMode` только `Version`, регэксп
   `^(None|Version( \+.+)?)$`; `Version v%v` — невалидно), `checkupdates`
   (`AutoName: Белый список?` авто-выводится инструментом — должен присутствовать).
5. **Reproducible builds:** linsui попросил — добавлены `Binaries` +
   `AllowedAPKSigningKeys`.
6. **Воспроизводимость сошлась.** Единственное расхождение было — зашитый
   git-коммит в `META-INF/version-control-info.textproto` (AGP 8.3+ вшивает
   его в APK). Решение: публичный APK и сборка F-Droid должны быть из ОДНОГО
   коммита. Сейчас оба из `3db0b191` → совпадает.
7. Категории по совету ревью: `Connectivity` + `Network Analyzer` (обе валидны).
8. linsui пометил MR `review-requested` + `reproducible-builds`, снял
   `waiting-on-response`, написал: «This MR is mostly ready. We'll test it
   later. If everything works well we'll merge it» и предупредил, что очередь
   длинная.

## Текущее состояние (04.09.2026)

- ✅ Все настоящие джобы F-Droid зелёные (`fdroid build`, `rewritemeta`,
  `schema validation`, `checkupdates`, `lint`, и т.д.).
- ✅ Воспроизводимость подтверждена: публичный APK содержит
  `revision: 3db0b191…`, F-Droid собирает `commit: 3db0b191…` — совпадает.
- ✅ MR в статусе `review-requested` + `reproducible-builds`.
- ⏳ **Осталось только ждать**, пока F-Droid дойдёт до MR в очереди
  (недели, возможно месяц-два — очередь длинная), протестирует и **смёржит**.
  После мёржа приложение соберётся на их build-сервере и появится в каталоге
  через ~сутки-двое, с подписью автора.

## Правило linsui на время ожидания

**«If you release a new version please update this MR».** То есть если
выпускаешь новую версию ДО мёржа — обнови MR (через Replace): `commit:` на
новый тегированный коммит, `versionCode`, `CurrentVersion`, `CurrentVersionCode`,
плюс changelog для новой версии в fastlane. Иначе при мёрже F-Droid опубликует
старую 0.5.4. Новые версии F-Droid-сборку НЕ ломают (собирается вмороженный
коммит), просто синхронизируй MR.

## Как делать будущие версии воспроизводимыми (0.6.0 и далее)

Gradle wrapper уже в репозитории — круг «нет wrapper'а» разомкнут. Рецепт:
1. Поднять `versionCode`/`versionName`, выпустить **тег** на коммите, где
   wrapper уже лежит.
2. GitHub Actions соберёт и выложит `WhiteListCheck_v<версия>.apk` из этого
   тега (APK зашьёт хэш тегированного коммита).
3. В MR обновить build-блок: `versionName`, `versionCode`, `commit:` = хэш
   **того же** тегированного коммита; `CurrentVersion`/`CurrentVersionCode`.
4. F-Droid соберёт тот же коммит → зашитый git-хэш совпадёт → воспроизводимо
   автоматически. Ничего в `build.gradle.kts` менять не нужно.

## Безопасность ключа (критично)

Теперь приложение раздаётся **с подписью автора** (не F-Droid). Потеря
keystore = невозможность обновлять приложение во ВСЕХ каналах (F-Droid,
RuStore, GitHub) + пользователям придётся переустанавливать. Нужен
**восстанавливаемый бэкап**: файл `.jks` + пароли (store/key) минимум в двух
независимых местах (менеджер паролей и/или вторая флешка в другом месте).
**GitHub Secrets — НЕ бэкап** (оттуда ключ прочитать обратно нельзя).
Текущее хранение ключа — см. PROJECT.md → «Подпись APK».

## Грабли и уроки (чтобы не наступить снова)

- **yml редактировать ТОЛЬКО через GitLab «Replace»** (загрузка файла), не
  копипастом — иначе CRLF (`^M`) ломает `rewritemeta`. Проверка байтов до
  пайплайна экономит цикл ожидания.
- **Форк-пайплайны и форк-письма («0 failed jobs», «verify your account») —
  шум.** Настоящие сборки — в `fdroid/fdroiddata`, запускает linsui.
  Верифицировать GitLab-аккаунт НЕ требуется.
- **Не дёргать linsui без повода** — это не ускоряет очередь. Он сам придёт,
  когда дойдёт черёд.
- `AutoUpdateMode` только `Version` (не `Version v%v`).
- `AutoName: Белый список?` — должен присутствовать (иначе `checkupdates` падает).
- F-Droid читает fastlane-метаданные из собираемого коммита.

## Итоговый файл metadata/ru.netstatus.app.yml (текущий)

```yaml
Categories:
  - Connectivity
  - Network Analyzer
License: MIT
AuthorName: Dmitry Starosta
SourceCode: https://github.com/dmitrystarosta/WhiteListCheck
IssueTracker: https://github.com/dmitrystarosta/WhiteListCheck/issues
Changelog: https://github.com/dmitrystarosta/WhiteListCheck/releases

AutoName: Белый список?

RepoType: git
Repo: https://github.com/dmitrystarosta/WhiteListCheck.git
Binaries: https://github.com/dmitrystarosta/WhiteListCheck/releases/download/v%v/WhiteListCheck_v%v.apk

Builds:
  - versionName: 0.5.4
    versionCode: 16
    commit: 3db0b1910f9a4a8fa5ab20ea093f656e46ec55f7
    subdir: app
    gradle:
      - yes

AllowedAPKSigningKeys: 5844d644b893d299a59cf2ccdc97937eddcadcb635c4afebd8d2b3c0f7e90933
AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.5.4
CurrentVersionCode: 16
```
