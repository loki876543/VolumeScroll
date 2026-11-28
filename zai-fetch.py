#!/usr/bin/env python3
"""Sim racing open-source watcher — AC / rFactor 2 / Le Mans Ultimate.

Sources:
- Reddit subreddits (rfactor2, assettocorsa, simracing, lemansultimate): new posts matching content keywords
- GitHub: brand-new repos for AC/rF2/LMU (first-time projects only — new versions of existing projects are NOT reported)
- GitHub: release feed of explicitly watched repos (e.g. ac-to-rf2)
- GitLab / Codeberg: newest matching projects since cutoff
- Atom feeds: new entries from explicitly watched feeds (e.g. LiveJournal)

Output lines (TAB-separated):
- MATCH       new Reddit post matching keywords
- GH_RELEASE  new release in an explicitly watched repo
- ATOM_POST   new entry in a watched Atom feed
- NEW_REPO    brand-new repo discovered on a forge
- INFO/FAILED per-source diagnostics

State: last_seen.json (repo-relative); output appended to racing.txt until 100 KiB.
"""
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from urllib.parse import quote

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
SUBS = ["rfactor2", "assettocorsa", "simracing", "lemansultimate"]
# GitHub repos to watch for new releases (Atom feed, no API key needed)
GH_RELEASE_FEEDS = {
    "ac-to-rf2": "https://github.com/Mewzapchu/ac-to-rf2/releases.atom",
}
# releases already known/installed at watcher setup time — never reported as new
GH_KNOWN_RELEASES = [
    "https://github.com/Mewzapchu/ac-to-rf2/releases/tag/v1.0.0",
    "https://github.com/Mewzapchu/ac-to-rf2/releases/tag/v1.0",
]
NS = {"a": "http://www.w3.org/2005/Atom"}
# YouTube channels to watch for new uploads (RSS feeds, no API key needed)
YT_FEEDS = {
    "NatterNet": "https://www.youtube.com/feeds/videos.xml?channel_id=UCPgLrL17ORjrzlJbOaOZE0A",
}
YT_NS = {"a": NS["a"], "yt": "http://www.youtube.com/xml/schemas/2015"}
# Generic Atom feeds to watch for new entries (no API key needed)
ATOM_FEEDS = {
    "snake-d-ha": "https://snake-d-ha.livejournal.com/data/atom",
}
STATE_PATH = "last_seen.json"
OUTPUT_PATH = "racing.txt"
MAX_OUTPUT_BYTES = 100 * 1024  # append until 100 KiB, then overwrite
MSK = timezone(timedelta(hours=3))
KEYWORDS = re.compile(
    r"mod|tweak|track|circuit|tool|liver(y|ies)|skin|shader|csp|pure|content manager"
    r"|converter|ffb|reshade|graphics|\bai\b|career|app\b|dashboard|telemetry"
    r"|release[ds]?|version|patch|update",
    re.IGNORECASE,
)
# how far back (seconds) from last check to accept posts as "new"
CUTOFF_TOLERANCE = 1800
# look-back window for brand-new repo discovery on forges
LOOKBACK_HOURS = 30

# ---- new-repo discovery filters -------------------------------------------
# queries: (label, keyword, require_sim_term) — require_sim cuts academic/Italian noise
GH_SEARCH_QUERIES = [
    ("AC", "assetto", True),          # "assetto" is Italian for chassis/asset -> needs sim filter
    ("rF2", "rfactor", False),
    ("rF2", "rfactor2", False),       # GitHub search is token-based: "rfactor" misses "rfactor2"
    ("rF2", "rf2", True),             # ...and "rf2" (sim filter cuts RF-radio/electronics noise)
    ("LMU", '"le mans ultimate"', False),
    ("LMU", "lmu", True),             # LMU is also Munich university -> needs sim filter
]
GITLAB_QUERIES = [
    ("AC", "assetto", True),
    ("rF2", "rfactor", False),
    ("LMU", "le mans ultimate", False),
]
CODEBERG_QUERIES = [
    ("AC", "assetto", True),
    ("rF2", "rfactor", False),
    ("LMU", "le mans ultimate", False),
]
SIM_FILTER = re.compile(
    r"sim ?racing|simulat|\bsim\b|\bracing\b|\brace\b|\bmod(s|ding)?\b|track|circuit"
    r"|\bcars?\b|vehicle|liver|skin|setup|telemetry|plugin|hud|dashboard|rfactor"
    r"|assetto|motorsport|endurance|hypercar|\bgt[123]\b|\bffb\b|\blap\b|\bstint"
    r"|\bfuel\b|\btyre|\btire|\bpit\b|\bbrake"
    r"|\bdash\b|\bdelta\b|radar",
    re.IGNORECASE,
)
EXCLUDE_FILTER = re.compile(
    r"cheat|aimbot|triggerbot|wallhack|\besp\b|\bhack|exploit|crypto|nft|casino"
    r"|gambling|betting|essay|thesis|dissertation|homework|assignment"
    r"|gambling|betting|essay|thesis|dissertation|homework|assignment|trainer\b"
    r"|deletion_scheduled|\bnsfw\b|porn|ebook|\bguide\b",
    re.IGNORECASE,
)


def iso_to_epoch(ts):
    """Parse ISO8601 (Z or +00:00 offsets) to epoch seconds; None on failure."""
    if not ts:
        return None
    try:
        return datetime.fromisoformat(str(ts).strip().replace("Z", "+00:00")).timestamp()
    except Exception:
        return None


def fetch_json(url, attempts=2, timeout=45):
    """curl -> parsed JSON, or None (covers non-JSON throttles like Codeberg's).
    If GitHub answers with a rate-limit message, wait and retry instead of failing."""
    for i in range(attempts):
        try:
            out = subprocess.run(["curl", "-s", "-A", UA, url],
                                 capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            out = None
        txt = (out.stdout if out else "").strip()
        if txt[:1] in ("{", "["):
            try:
                d = json.loads(txt)
            except json.JSONDecodeError:
                d = None
            if isinstance(d, dict):
                msg = str(d.get("message", ""))
                if "rate limit" in msg.lower() and i < attempts - 1:
                    time.sleep(30 + 30 * i)   # wait out the rate-limit window
                    continue
                return d
            if d is not None:
                return d
        time.sleep(4 + 6 * i)
    return None


# ---- Reddit -----------------------------------------------------------------
def fetch(sub: str, limit: int):
    """Return (body, http_code) trying www first, then old.reddit as fallback."""
    body, code = "", "0"
    for host in ("www.reddit.com", "old.reddit.com"):
        url = f"https://{host}/r/{sub}/new/.rss?limit={limit}"
        out = subprocess.run(["curl", "-s", "-w", "\n%{http_code}", "-A", UA, url],
                             capture_output=True, text=True, timeout=60)
        text = out.stdout
        code = text.rsplit("\n", 1)[-1].strip() if "\n" in text else "0"
        body = text.rsplit("\n", 1)[0] if "\n" in text else text
        if "<feed" in body:
            return body, code
    return body, code


def fetch_retry(sub: str, limit: int, attempts: int = 3):
    last_code = "0"
    for i in range(attempts):
        xml_text, code = fetch(sub, limit)
        last_code = code
        if "<feed" in xml_text:
            return xml_text
        # 429/403 = per-subreddit crawler limit: wait much longer
        time.sleep(45 + 45 * i if code in ("429", "403") else 5 + 10 * i)
    print(f"FAILED\t{sub}\tlast_http={last_code}")
    return None


def parse(xml_text: str):
    root = ET.fromstring(xml_text)
    entries = []
    for e in root.findall("a:entry", NS):
        t = e.find("a:title", NS)
        l = e.find("a:link", NS)
        u = e.find("a:updated", NS)
        entries.append({
            "title": (t.text or "").strip() if t is not None else "",
            "link": (l.get("href", "") if l is not None else "").split("?")[0],
            "updated": (u.text or "").strip() if u is not None else "",
        })
    return entries


def upd_epoch(entry):
    return iso_to_epoch(entry.get("updated")) or 0.0


# ---- GitHub release feed (explicitly watched repos) --------------------------
def fetch_url(url: str, attempts: int = 3):
    """Generic fetch-with-retry returning text that looks like an Atom feed."""
    for i in range(attempts):
        out = subprocess.run(["curl", "-s", "-A", UA, url],
                             capture_output=True, text=True, timeout=60)
        if "<feed" in out.stdout:
            return out.stdout
        time.sleep(5 + 10 * i)
    return None


def check_github_releases(state: dict, now_epoch: float):
    """Report releases newer than everything already seen for each watched repo."""
    gh_state = state.setdefault("github_releases", {})
    for name, feed_url in GH_RELEASE_FEEDS.items():
        xml_text = fetch_url(feed_url)
        if xml_text is None:
            print(f"FAILED\tgithub/{name}")
            continue
        try:
            root = ET.fromstring(xml_text)
        except ET.ParseError:
            print(f"FAILED\tgithub/{name}\tparse error")
            continue
        seen = set(gh_state.get(name, {}).get("seen_ids", GH_KNOWN_RELEASES))
        entries = []
        for e in root.findall("a:entry", NS):
            l = e.find("a:link", NS)
            t = e.find("a:title", NS)
            u = e.find("a:updated", NS)
            entries.append({
                "link": (l.get("href", "") if l is not None else "").split("?")[0],
                "title": (t.text or "").strip() if t is not None else "",
                "updated": (u.text or "").strip() if u is not None else "",
            })
        fresh = [e for e in entries if e["link"] not in seen]
        for e in fresh:
            age_h = max((now_epoch - upd_epoch(e)) / 3600.0, 0)
            print(f"GH_RELEASE\t{name}\tage={age_h:.1f}h\t{e['title']}\t{e['link']}")
        print(f"INFO\tgithub/{name}\ttotal={len(entries)}\tnew={len(fresh)}")
        gh_state[name] = {
            "seen_ids": [e["link"] for e in entries][:20] or seen and list(seen)[:20],
            "newest_updated": max((e["updated"] for e in entries), default=""),
        }


# ---- YouTube channel uploads (RSS) -------------------------------------------
def check_youtube(state: dict, now_epoch: float):
    """Report new uploads on watched YouTube channels via their RSS feeds.
    First run only seeds the baseline (current videos) without reporting."""
    yt_state = state.setdefault("youtube", {})
    for name, feed_url in YT_FEEDS.items():
        out = ""
        for i in range(3):
            out = subprocess.run(["curl", "-s", "-A", UA, feed_url],
                                 capture_output=True, text=True, timeout=60).stdout
            if "<feed" in out:
                break
            time.sleep(5 + 10 * i)
        if "<feed" not in out:
            print(f"FAILED\tyoutube/{name}")
            continue
        try:
            root = ET.fromstring(out)
        except ET.ParseError:
            print(f"FAILED\tyoutube/{name}\tparse error")
            continue
        ch_state = yt_state.setdefault(name, {})
        seen = set(ch_state.get("seen_ids", []))
        entries = []
        for e in root.findall("a:entry", YT_NS):
            v = e.find("yt:videoId", YT_NS)
            t = e.find("a:title", YT_NS)
            p = e.find("a:published", YT_NS)
            l = e.find("a:link", YT_NS)
            entries.append({
                "id": (v.text or "").strip() if v is not None else "",
                "title": (t.text or "").strip() if t is not None else "",
                "published": (p.text or "").strip() if p is not None else "",
                "link": (l.get("href", "") if l is not None else "").split("?")[0],
            })
        entries = [e for e in entries if e["id"]]
        if not seen:   # first run: seed baseline without reporting
            ch_state["seen_ids"] = [e["id"] for e in entries][:20]
            ch_state["newest_published"] = max((e["published"] for e in entries), default="")
            print(f"INFO\tyoutube/{name}\tseeded={len(entries)}")
            continue
        fresh = [e for e in entries if e["id"] not in seen]
        for e in fresh:
            pub = iso_to_epoch(e["published"])
            age_h = max((now_epoch - pub) / 3600.0, 0) if pub else None
            print(f"YT_VIDEO\t{name}\tage={f'{age_h:.1f}h' if age_h is not None else '?'}"
                  f"\t{e['title']}\t{e['link']}")
        print(f"INFO\tyoutube/{name}\ttotal={len(entries)}\tnew={len(fresh)}")
        ch_state["seen_ids"] = [e["id"] for e in entries][:20]
        ch_state["newest_published"] = max((e["published"] for e in entries), default="")


# ---- generic Atom feeds (LiveJournal etc.) ----------------------------------
def check_atom_feeds(state: dict, now_epoch: float):
    """Report new entries in watched Atom feeds; first run only seeds baseline."""
    atom_state = state.setdefault("atom", {})
    for name, feed_url in ATOM_FEEDS.items():
        xml_text = fetch_url(feed_url)
        if xml_text is None:
            print(f"FAILED\tatom/{name}")
            continue
        try:
            root = ET.fromstring(xml_text)
        except ET.ParseError:
            print(f"FAILED\tatom/{name}\tparse error")
            continue
        feed_state = atom_state.setdefault(name, {})
        seen = set(feed_state.get("seen_ids", []))
        entries = []
        for e in root.findall("a:entry", NS):
            l = e.find("a:link", NS)
            t = e.find("a:title", NS)
            u = e.find("a:updated", NS)
            entries.append({
                "id": (l.get("href", "") if l is not None else "").split("?")[0],
                "title": (t.text or "").strip() if t is not None else "",
                "updated": (u.text or "").strip() if u is not None else "",
            })
        entries = [e for e in entries if e["id"]]
        if not seen:  # first run: seed baseline without reporting
            feed_state["seen_ids"] = [e["id"] for e in entries][:20]
            feed_state["newest_updated"] = max((e["updated"] for e in entries), default="")
            print(f"INFO\tatom/{name}\tseeded={len(entries)}")
            continue
        fresh = [e for e in entries if e["id"] not in seen]
        for e in fresh:
            upd = upd_epoch(e)
            age_h = max((now_epoch - upd) / 3600.0, 0) if upd else None
            print(f"ATOM_POST\t{name}\tage={f'{age_h:.1f}h' if age_h is not None else '?'}"
                  f"\t{e['title']}\t{e['id']}")
        print(f"INFO\tatom/{name}\ttotal={len(entries)}\tnew={len(fresh)}")
        feed_state["seen_ids"] = [e["id"] for e in entries][:20]
        feed_state["newest_updated"] = max((e["updated"] for e in entries), default="")


# ---- brand-new repo discovery ------------------------------------------------
def gh_latest_release_tag(full_name: str):
    """Latest release tag for a repo, or None (no releases / rate limited)."""
    d = fetch_json(f"https://api.github.com/repos/{full_name}/releases?per_page=1", attempts=1)
    if isinstance(d, list) and d:
        return d[0].get("tag_name") or d[0].get("name") or "?"
    return None


def check_new_github_repos(state: dict, now_epoch: float):
    """Search GitHub for repos created in the look-back window (new projects only)."""
    min_epoch = now_epoch - LOOKBACK_HOURS * 3600
    since_date = datetime.fromtimestamp(min_epoch, tz=timezone.utc).strftime("%Y-%m-%d")
    nr = state.setdefault("new_repos", {})
    seen = set(nr.get("seen", []))
    seen_new, reported, release_checks = [], set(), 0
    for label, kw, need_sim in GH_SEARCH_QUERIES:
        query = f"{kw} in:name,description,topics created:>={since_date} fork:false"
        url = ("https://api.github.com/search/repositories?q=" + quote(query)
               + "&sort=created&order=desc&per_page=50")
        d = fetch_json(url, attempts=3)
        items, total = [], "?"
        if isinstance(d, dict):
            items = d.get("items", [])
            total = d.get("total_count", len(items))
            if not items and d.get("message"):
                print(f"FAILED\tgh-search/{label}\t{str(d.get('message'))[:60]}")
        elif d is None:
            print(f"FAILED\tgh-search/{label}")
        hit = 0
        for r in items:
            full = (r.get("full_name") or "").strip()
            key = f"github:{full.lower()}"
            if not full or key in seen or full.lower() in reported:
                continue
            seen.add(key)
            seen_new.append(key)
            created = iso_to_epoch(r.get("created_at") or "")
            if created is not None and created < min_epoch:
                continue
            desc = (r.get("description") or "").strip()
            text = " ".join([r.get("name", ""), desc, " ".join(r.get("topics") or [])])
            if EXCLUDE_FILTER.search(text):
                continue
            if need_sim and not SIM_FILTER.search(text):
                continue
            rel = "-"
            if release_checks < 10:
                tag = gh_latest_release_tag(full)
                release_checks += 1
                if tag:
                    rel = tag
            age = (max((now_epoch - created) / 3600.0, 0) if created else None)
            print(f"NEW_REPO\tgithub\t{label}\tage={f'{age:.1f}h' if age is not None else '?'}"
                  f"\trel={rel}\t{full}\t{desc[:120]}\t{r.get('html_url', '')}")
            reported.add(full.lower())
            hit += 1
        print(f"INFO\tgh-search/{label}\ttotal={total}\treported={hit}")
        time.sleep(10)   # keep the shared IP under the search-API per-minute limit
    nr["seen"] = (seen_new + nr.get("seen", []))[:500]


def check_gitlab(state: dict, now_epoch: float):
    """GitLab.com: newest matching projects since the look-back window."""
    min_epoch = now_epoch - LOOKBACK_HOURS * 3600
    since_iso = datetime.fromtimestamp(min_epoch, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    nr = state.setdefault("new_repos", {})
    seen = set(nr.get("seen", []))
    seen_new, reported = [], set()
    for label, kw, need_sim in GITLAB_QUERIES:
        url = ("https://gitlab.com/api/v4/projects?search=" + quote(kw)
               + "&order_by=created_at&sort=desc&per_page=20&created_after=" + since_iso)
        d = fetch_json(url)
        if d is None:
            print(f"FAILED\tgitlab/{label}")
            continue
        if isinstance(d, dict):
            print(f"FAILED\tgitlab/{label}\t{str(d.get('message', d))[:60]}")
            continue
        hit = 0
        for r in d:
            full = (r.get("path_with_namespace") or "").strip()
            key = f"gitlab:{full.lower()}"
            if not full or key in seen or full.lower() in reported:
                continue
            seen.add(key)
            seen_new.append(key)
            if "deletion_scheduled" in full:   # GitLab spam waves
                continue
            created = iso_to_epoch(r.get("created_at") or "")
            if created is not None and created < min_epoch:
                continue
            desc = (r.get("description") or "").strip()
            text = " ".join([r.get("name", ""), r.get("path", ""), desc])
            if EXCLUDE_FILTER.search(text):
                continue
            if need_sim and not SIM_FILTER.search(text):
                continue
            age = (max((now_epoch - created) / 3600.0, 0) if created else None)
            print(f"NEW_REPO\tgitlab\t{label}\tage={f'{age:.1f}h' if age is not None else '?'}"
                  f"\trel=-\t{full}\t{desc[:120]}\t{r.get('web_url', '')}")
            reported.add(full.lower())
            hit += 1
        print(f"INFO\tgitlab/{label}\ttotal={len(d)}\treported={hit}")
        time.sleep(1.5)
    nr["seen"] = (seen_new + nr.get("seen", []))[:500]


def check_codeberg(state: dict, now_epoch: float):
    """Codeberg (Gitea): newest matching repos since the look-back window."""
    min_epoch = now_epoch - LOOKBACK_HOURS * 3600
    nr = state.setdefault("new_repos", {})
    seen = set(nr.get("seen", []))
    seen_new, reported = [], set()
    for label, kw, need_sim in CODEBERG_QUERIES:
        url = f"https://codeberg.org/api/v1/repos/search?q={quote(kw)}&limit=30"
        d = fetch_json(url, attempts=2)
        if d is None:   # currently throttled for search: non-JSON plain text
            print(f"FAILED\tcodeberg/{label}\tthrottled or unavailable")
            continue
        data = d.get("data", []) if isinstance(d, dict) else []
        repos = sorted(
            data,
            key=lambda r: str(r.get("created_at") or r.get("created") or ""),
            reverse=True,
        )
        hit = 0
        for r in repos:
            full = (r.get("full_name") or "").strip()
            key = f"codeberg:{full.lower()}"
            if not full or key in seen or full.lower() in reported:
                continue
            seen.add(key)
            seen_new.append(key)
            created = iso_to_epoch(r.get("created_at") or r.get("created") or "")
            if created is not None and created < min_epoch:
                continue
            desc = (r.get("description") or "").strip()
            text = " ".join([r.get("name", ""), desc])
            if EXCLUDE_FILTER.search(text):
                continue
            if need_sim and not SIM_FILTER.search(text):
                continue
            age = (max((now_epoch - created) / 3600.0, 0) if created else None)
            print(f"NEW_REPO\tcodeberg\t{label}\tage={f'{age:.1f}h' if age is not None else '?'}"
                  f"\trel=-\t{full}\t{desc[:120]}\t{r.get('html_url', '')}")
            reported.add(full.lower())
            hit += 1
        print(f"INFO\tcodeberg/{label}\ttotal={len(data)}\treported={hit}")
        time.sleep(1.5)
    nr["seen"] = (seen_new + nr.get("seen", []))[:500]


def main():
    with open(STATE_PATH) as f:
        state = json.load(f)

    # cutoff for Reddit: only posts updated after the previous check count as new
    prev = state.get("last_check_epoch", 0)
    if not prev:
        try:
            prev = time.mktime(time.strptime(state.get("checked_at", ""), "%Y-%m-%d %H:%M"))
        except Exception:
            prev = 0
    cutoff = max(prev, 0) - CUTOFF_TOLERANCE

    now_epoch = time.time()

    # forge sources first, then an intermediate save: their "seen" state survives
    # even if the Reddit section below runs long or the job hits its timeout
    check_github_releases(state, now_epoch)
    check_youtube(state, now_epoch)
    check_atom_feeds(state, now_epoch)
    check_new_github_repos(state, now_epoch)
    check_gitlab(state, now_epoch)
    check_codeberg(state, now_epoch)
    with open(STATE_PATH, "w") as f:
        json.dump(state, f, indent=2)

    new_state_subs = {}
    for sub in SUBS:
        xml_text = fetch_retry(sub, 100)
        if xml_text is None:
            continue
        entries = parse(xml_text)
        seen = set(state.get("subs", {}).get(sub, {}).get("seen_ids", []))
        fresh = [e for e in entries if e["link"] not in seen and upd_epoch(e) >= cutoff]
        matches = [e for e in fresh if KEYWORDS.search(e["title"])]
        for e in matches:
            age_h = max((now_epoch - upd_epoch(e)) / 3600.0, 0)
            print(f"MATCH\t{sub}\tage={age_h:.1f}h\t{e['title']}\t{e['link']}")
        print(f"INFO\t{sub}\ttotal_entries={len(entries)}\tfresh={len(fresh)}\tmatching={len(matches)}")
        links = [e["link"] for e in entries][:60]
        for e in matches:
            if e["link"] not in links:
                links.append(e["link"])
        new_state_subs[sub] = {
            "seen_ids": links[:80],
            "newest_updated": max((e["updated"] for e in entries), default=""),
        }

    state["checked_at"] = time.strftime("%Y-%m-%d %H:%M")
    state["last_check_epoch"] = now_epoch
    if new_state_subs:
        state.setdefault("subs", {}).update(new_state_subs)
    with open(STATE_PATH, "w") as f:
        json.dump(state, f, indent=2)
    print("state updated")


if __name__ == "__main__":
    try:
        mode = "w" if os.path.getsize(OUTPUT_PATH) >= MAX_OUTPUT_BYTES else "a"
    except OSError:
        mode = "w"
    with open(OUTPUT_PATH, mode, encoding="utf-8") as out, redirect_stdout(out):
        print("\n=== " + datetime.now(MSK).isoformat(timespec="seconds") + " ===")
        main() 
