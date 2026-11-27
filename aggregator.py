import requests
from bs4 import BeautifulSoup
import feedparser
from datetime import datetime, timedelta, timezone
import re

# ---------- НАСТРОЙКИ ----------
HOURS_BACK = 12
TUTU_ROUTES = [
    # (st1, st2, "Название маршрута")
    (30404, 28904, "Икша"),
    (20000, 30404, "Савел-Икша"),    
    (28704, 30404, "Т-Икша"),
    (28904, 30404, "Д-Икша"),            

]

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Aggregator/1.0"
}

# Расшифровка кодов погоды WMO (Open-Meteo)
WEATHER_CODES = {
    0: "ясно", 1: "преимущественно ясно", 2: "переменная облачность", 3: "пасмурно",
    45: "туман", 48: "изморозь",
    51: "слабая морось", 53: "морось", 55: "сильная морось",
    56: "слабая ледяная морось", 57: "ледяная морось",
    61: "слабый дождь", 63: "дождь", 65: "сильный дождь",
    66: "слабый ледяной дождь", 67: "ледяной дождь",
    71: "слабый снег", 73: "снег", 75: "сильный снег", 77: "снежная крупа",
    80: "слабые ливни", 81: "ливни", 82: "сильные ливни",
    85: "слабый снегопад", 86: "сильный снегопад",
    95: "гроза", 96: "гроза с градом", 99: "сильная гроза с градом",
}

# ---------- ВСПОМОГАТЕЛЬНЫЕ ----------
def is_fresh(published_at_str, hours=HOURS_BACK):
    """Проверяет, что дата (ISO 8601) не старше N часов."""
    if not published_at_str:
        return True
    try:
        dt = datetime.fromisoformat(published_at_str.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        cutoff = datetime.now(timezone.utc) - timedelta(hours=hours)
        return dt >= cutoff
    except Exception:
        return True


def clean_text(text, max_len=200):
    """Обрезает текст и убирает лишние пробелы."""
    if not text:
        return ""
    text = re.sub(r"\s+", " ", text).strip()
    return text[:max_len] + ("…" if len(text) > max_len else "")


# ---------- ИСТОЧНИКИ НОВОСТЕЙ ----------
def fetch_lenta():
    feed_url = "https://lenta.ru/rss/news"
    try:
        feed = feedparser.parse(feed_url)
        items = []
        for entry in feed.entries:
            published = entry.get("published", "")
            iso = ""
            if published:
                try:
                    dt = datetime(*published[:6], tzinfo=timezone.utc)
                    iso = dt.isoformat()
                except Exception:
                    iso = ""
            if is_fresh(iso):
                items.append({
                    "source": "Lenta.ru",
                    "title": clean_text(entry.get("title", "")),
                    #"url": entry.get("link", ""),
                })
        return items
    except Exception as e:
        return [{"source": "Lenta.ru", "error": str(e)}]

def fetch_blogbankir():
    url = "https://t.me/s/blogbankir"
    try:
        r = requests.get(url, headers=HEADERS, timeout=30)
        r.raise_for_status()
        soup = BeautifulSoup(r.text, "lxml")
        posts = []
        for msg in soup.select(".tgme_widget_message"):
            text_div = msg.select_one(".tgme_widget_message_text")
            if not text_div:
                continue
            text = text_div.get_text(" ", strip=True)
            time_tag = msg.select_one("time")
            iso_date = time_tag.get("datetime") if time_tag else ""
            if is_fresh(iso_date):
                posts.append({
                    "source": "Blogbankir (TG)",
                    "title": clean_text(text, max_len=2000),
                    #"url": "https://t.me/blogbankir",
                })
        return posts
    except Exception as e:
        return [{"source": "Blogbankir (TG)", "error": str(e)}]


# ---------- ПОГОДА ----------
def fetch_weather():
    url = "https://api.open-meteo.com/v1/forecast"
    params = {
        "latitude": 55.7558,
        "longitude": 37.6173,
        "daily": (
            "temperature_2m_max,temperature_2m_min,"
            "precipitation_sum,precipitation_probability_max,"
            "weather_code,wind_speed_10m_max"
        ),
        "forecast_days": 2,
        "timezone": "Europe/Moscow",
    }
    try:
        r = requests.get(url, params=params, headers=HEADERS, timeout=20)
        r.raise_for_status()
        d = r.json().get("daily", {})
        dates = d.get("time", [])
        tmax = d.get("temperature_2m_max", [])
        tmin = d.get("temperature_2m_min", [])
        precip = d.get("precipitation_sum", [])
        prob = d.get("precipitation_probability_max", [])
        codes = d.get("weather_code", [])
        wind = d.get("wind_speed_10m_max", [])

        lines = []
        for i, date in enumerate(dates):
            desc = WEATHER_CODES.get(codes[i], f"код {codes[i]}")
            wind_val = wind[i]
            wind_str = f"{wind_val} м/с"
            if wind_val is not None and wind_val > 5:
                wind_str += " ⚠️"
            lines.append(
                f"  {date}: {tmin[i]}…{tmax[i]}°C, {desc}, "
                f"осадки {precip[i]} мм ({prob[i]}%), ветер {wind_str}"
            )
        return "\n".join(lines) if lines else "  Нет данных."
    except Exception as e:
        return f"  Ошибка получения погоды: {e}"


# ---------- TUTU.RU (только отправления, в одну строку) ----------
def fetch_tutu_route(st1, st2, name):
    url = f"https://www.tutu.ru/rasp.php?st1={st1}&st2={st2}"
    try:
        r = requests.get(url, headers=HEADERS, timeout=30)
        r.raise_for_status()
        soup = BeautifulSoup(r.text, "lxml")

        time_pattern = re.compile(r"\b([01]?\d|2[0-3]):[0-5]\d\b")
        departures = []

        for element in soup.find_all(string=time_pattern):
            text = element.strip()
            match = re.match(r"^([01]?\d|2[0-3]):[0-5]\d", text)
            if match:
                departures.append(match.group(0))

        unique_departures = list(dict.fromkeys(departures))

        if not unique_departures:
            return f"Маршрут: {name}\n  Не удалось извлечь время отправления"

        return (
            f"Маршрут: {name}"
            f"   {', '.join(unique_departures[:15])}"
        )
    except Exception as e:
        return f"Маршрут: {name}  Ошибка: {e}"


def fetch_tutu():
    output = []
    for st1, st2, name in TUTU_ROUTES:
        output.append(fetch_tutu_route(st1, st2, name))
    return output

def fetch_gazeta():
    """Собирает новости Gazeta.ru через официальную RSS-ленту."""
    feed_url = "https://www.gazeta.ru/export/rss/first.xml"
    try:
        feed = feedparser.parse(feed_url)
        items = []
        for entry in feed.entries:
            # feedparser приводит дату к struct_time, конвертируем в ISO для фильтра
            published = entry.get("published_parsed")
            iso = ""
            if published:
                try:
                    dt = datetime(*published[:6], tzinfo=timezone.utc)
                    iso = dt.isoformat()
                except Exception:
                    iso = ""
            if is_fresh(iso):
                items.append({
                    "source": "Gazeta.ru",
                    "title": clean_text(entry.get("title", "")),
                    #"url": entry.get("link", ""),
                })
        return items
    except Exception as e:
        return [{"source": "Gazeta.ru", "error": str(e)}]


def fetch_strana():
    """Собирает новости Strana.news из веб-версии Telegram-канала @stranaua."""
    url = "https://t.me/s/stranaua"
    try:
        r = requests.get(url, headers=HEADERS, timeout=30)
        r.raise_for_status()
        soup = BeautifulSoup(r.text, "lxml")
        posts = []
        # Каждый пост в контейнере с классом tgme_widget_message
        for msg in soup.select(".tgme_widget_message"):
            text_div = msg.select_one(".tgme_widget_message_text")
            if not text_div:
                continue
            text = text_div.get_text(" ", strip=True)
            time_tag = msg.select_one("time")
            iso_date = time_tag.get("datetime") if time_tag else ""
            if is_fresh(iso_date):
                posts.append({
                    "source": "Strana.news (TG)",
                    "title": clean_text(text, max_len=200),
                    #"url": "https://t.me/stranaua",
                })
        return posts
    except Exception as e:
        return [{"source": "Strana.news (TG)", "error": str(e)}]
        

# ---------- СБОРКА ОТЧЁТА ----------
def build_report():
    lines = []
    now = datetime.now(timezone(timedelta(hours=3))).strftime("%Y-%m-%d %H:%M")
    lines.append(f"📅 Отчёт на {now}")
    #lines.append("=" * 50)

    #lines.append("🌤 Погода в Москве (2 дня)")
    lines.append(fetch_weather())
    lines.append("")
    
    #lines.append("🚆 Расписание электричек (tutu.ru)")
    lines.append("-" * 30)
    tutu_lines = fetch_tutu()
    lines.extend(tutu_lines if tutu_lines else ["  Нет данных."])
    lines.append("")
    
    #lines.append("📰 Новости за последние 12 часов")
    #lines.append("-" * 30)
    all_news = []
    all_news.extend(fetch_lenta())
    all_news.extend(fetch_gazeta())
    all_news.extend(fetch_strana())
    all_news.extend(fetch_blogbankir())

    sources = {}
    for item in all_news:
        src = item.get("source", "Unknown")
        sources.setdefault(src, []).append(item)

    for src, items in sources.items():
        lines.append(f"\n🔹 {src}")
        if items and "error" in items[0]:
            lines.append(f"  ❌ {items[0]['error']}")
            continue
        if not items:
            lines.append("  Нет свежих материалов.")
            continue
        for it in items[:10]:
            lines.append(f"  • {it['title']}")
            if it.get("url"):
                lines.append(f"    {it['url']}")
    lines.append("")



    return "\n".join(lines)


if __name__ == "__main__":
    report = build_report()
    print(report)  # дублируем в лог Actions

    # Сохраняем последний отчёт
    with open("report.txt", "w", encoding="utf-8") as f:
        f.write(report)

    # (опционально) архив с датой и временем
    import os
    from datetime import datetime, timezone
    os.makedirs("reports", exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d_%H-%M")
    with open(f"reports/{stamp}.txt", "w", encoding="utf-8") as f:
        f.write(report)

    print(f"\n✅ Сохранено в report.txt и reports/{stamp}.txt")    
