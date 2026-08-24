# HeadInspect — блок «Другие проекты» для belyjspisok.ru

Подготовлено как безопасная добавка к текущей версии сайта.

1. Загрузить в `site/assets/`:
   - `headinspect-80.webp`
   - `headinspect-160.webp`

2. В актуальном `site/index.html` вставить содержимое `other-projects.html`
   непосредственно ПЕРЕД `<footer>`.

3. В самый конец актуального `site/styles.css` добавить содержимое
   `other-projects.css`.

4. Изменить query-string CSS в `index.html`, например:
   `styles.css?v=20260824-otherprojects1`

Важно: не заменяйте текущий index.html старой копией — на сайте уже есть
последующие WebP/PageSpeed-оптимизации.
