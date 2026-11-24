#!/usr/bin/env python3
"""Скачивает страницы из списка url.txt, конвертирует через html2text
и собирает strana.htm — индекс со ссылками на полученные тексты.

Формат строк url.txt:
  https://example.com/                                      # вся страница
  https://example.org/article | Introduction | Conclusion  # между маркерами (не включая их)
  https://example.net/page | Contact                        # от начала страницы до маркера
  https://example.net/page2 | Results |                     # от маркера до конца

Строки с '#' и пустые строки пропускаются.
Пробелы вокруг '|' обрезаются, регистр маркеров учитывается.
Результаты: pages/<имя>.md (текст) и strana.htm (индекс ссылок).
Если URL удалили из url.txt, соответствующий файл тоже удаляется.
"""

import html
import html2text
import os
import re
import sys
import urllib.request
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path

URL_FILE = Path("urls.txt")
OUT_DIR = Path("pages")
INDEX_FILE = Path("strana.htm")
USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) VolumeScroll-fetch/1.0"


class TitleParser(HTMLParser):
    """Достаёт содержимое <title>...</title>."""

    def __init__(self):
        super().__init__()
        self.in_title = False
        self.title = ""

    def handle_starttag(self, tag, attrs):
        if tag == "title":
            self.in_title = True

    def handle_endtag(self, tag):
        if tag == "title":
            self.in_title = False

    def handle_data(self, data):
        if self.in_title:
            self.title += data


def output_name(url: str) -> str:
    """Строит имя выходного файла из URL: домен + путь, санитизированные."""
    m = re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)([^?#]*)", url)
    if m:
        host, path = m.group(1), m.group(2)
    else:
        host, path = url, ""
    path = path.strip("/")
    suffix = ("_" + path.replace("/", "_")) if path else ""
    name = re.sub(r"[^A-Za-z0-9._-]", "_", host + suffix)
    return name + ".md"


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read().decode("utf-8", errors="replace")


def extract(page: str, start_marker: str | None, end_marker: str | None):
    """Вырезает фрагмент: после start_marker (или с начала) и до end_marker (или до конца).

    Маркеры в результат не включаются. Возвращает (текст, ошибка).
    """
    begin = 0
    if start_marker:
        i = page.find(start_marker)  # find чувствителен к регистру
        if i == -1:
            return None, f"начальный маркер не найден: {start_marker!r}"
        begin = i + len(start_marker)

    end = len(page)
    if end_marker:
        j = page.find(end_marker, begin)
        if j == -1:
            return None, f"конечный маркер не найден: {end_marker!r}"
        end = j

    if begin > end:
        return None, "маркеры идут в неправильном порядке"
    return page[begin:end], None


def parse_line(line: str):
    """Разбирает строку url.txt -> (url, start_marker, end_marker)."""
    parts = [p.strip() for p in line.split("|")]
    url = parts[0]
    if len(parts) == 1 or (len(parts) == 2 and not parts[1]):
        # Весь текст страницы
        return url, None, None
    if len(parts) == 2:
        # От начала страницы до маркера
        return url, None, parts[1]
    # len(parts) >= 3: между маркерами или от маркера до конца (пустой конец)
    return url, parts[1] or None, parts[2] or None


def to_text(html_fragment: str) -> str:
    """Конвертирует HTML-фрагмент в Markdown-подобный текст через html2text."""
    converter = html2text.HTML2Text()
    converter.ignore_links = True
    converter.ignore_images = True
    converter.body_width = 0  # не переносить строки
    return converter.handle(html_fragment).strip() + "\n"


def build_index(entries, updated_at: str) -> str:
    """Собирает strana.htm — список ссылок на сохранённые тексты."""
    items = []
    for entry in entries:
        title = html.escape(entry["title"] or entry["url"])
        href = html.escape(f"{OUT_DIR.name}/{entry['name']}")
        source = html.escape(entry["url"])
        items.append(
            f'      <li><a href="{href}">{title}</a> <small>'
            f'(<a href="{source}">источник</a>)</small></li>'
        )
    list_html = "\n".join(items) if items else "      <li>(нет сохранённых страниц)</li>"

    return f"""<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Сохранённые страницы</title>
</head>
<body>
  <h1>Сохранённые страницы</h1>
  <p>Обновлено: {html.escape(updated_at)}</p>
  <ul>
{list_html}
  </ul>
</body>
</html>
"""


def main() -> int:
    if not URL_FILE.exists():
        print("::error::url.txt не найден в корне репозитория")
        return 1

    OUT_DIR.mkdir(exist_ok=True)
    expected = set()
    entries, failed = [], []

    for raw in URL_FILE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue

        url, start_marker, end_marker = parse_line(line)
        name = output_name(url)
        expected.add(name)

        try:
            page = fetch(url)
        except Exception as exc:  # noqa: BLE001
            print(f"::warning title=Ошибка загрузки::{url}: {exc}")
            failed.append(f"{url} — загрузка: {exc}")
            continue

        if start_marker is None and end_marker is None:
            fragment = page
        else:
            fragment, err = extract(page, start_marker, end_marker)
            if err:
                print(f"::warning title=Маркер не найден::{url}: {err}")
                failed.append(f"{url} — {err}")
                continue

        # Заголовок страницы берём из полного HTML (фрагмент может его не содержать)
        tp = TitleParser()
        try:
            tp.feed(page)
        except Exception:  # noqa: BLE001
            pass
        title = " ".join(tp.title.split())

        content = to_text(fragment)
        (OUT_DIR / name).write_text(content, encoding="utf-8")
        print(f"Сохранено {OUT_DIR.name}/{name} ({len(content)} символов) <- {url}")
        entries.append({"name": name, "url": url, "title": title})

    # Удаляем файлы, чьих URL больше нет в url.txt
    for f in OUT_DIR.iterdir():
        if f.is_file() and f.name not in expected:
            f.unlink()
            print(f"Удалено {OUT_DIR.name}/{f.name} (URL отсутствует в url.txt)")

    # Собираем strana.htm — индекс со ссылками на тексты
    updated_at = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    INDEX_FILE.write_text(build_index(entries, updated_at), encoding="utf-8")
    print(f"Собран {INDEX_FILE}: {len(entries)} ссылок")

    # Сводка во вкладку Summary прогона
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write("### Результаты загрузки\n\n")
            for entry in entries:
                fh.write(f"- ✅ `{entry['name']}`\n")
            for item in failed:
                fh.write(f"- ❌ {item}\n")

    print(f"Готово: сохранено {len(entries)}, ошибок {len(failed)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
