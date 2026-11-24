#!/usr/bin/env python3
"""
Collect photos of professionally graded Pokémon cards from eBay listings,
labelled with the grade from the listing title, to calibrate PokéGrade.

Uses eBay's official Browse API (search only, no scraping of web pages).
You need a free eBay developer account: https://developer.ebay.com
  1. Create an application keyset (Production).
  2. Export the App ID (Client ID) and Cert ID (Client Secret):
       export EBAY_CLIENT_ID=...
       export EBAY_CLIENT_SECRET=...
  3. Run:
       python3 tools/ebay/ebay_graded_scraper.py --company PSA --grades 1-10 --per-grade 100

Output (default samples/ebay/):
  images/<company>-<grade>/<itemId>.jpg   main listing photo
  labels.csv                              filename,grade,company,title,item_id,url,price,currency
The labels.csv format is what `./gradlew :tools:run --args="grades samples/ebay"` reads.

Notes:
- Listing titles are written by sellers; the script only keeps titles that
  name exactly one grade from the chosen company, and skips lots, proxies,
  customs and "not PSA"-style titles. Expect some noise anyway.
- Photos show the card inside the slab, often with glare. They are good for
  checking centering, corners and edges at a coarse level; they are not the
  same as a phone photo of a raw card.
- The images belong to the sellers. Keep the dataset for your own testing,
  and follow eBay's API License Agreement (https://developer.ebay.com/join/api-license-agreement).
"""
from __future__ import annotations

import argparse
import base64
import csv
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

TOKEN_URL = "https://api.ebay.com/identity/v1/oauth2/token"
SEARCH_URL = "https://api.ebay.com/buy/browse/v1/item_summary/search"
SCOPE = "https://api.ebay.com/oauth/api_scope"
# eBay category: Toys & Hobbies > Collectible Card Games > CCG Individual Cards
CCG_INDIVIDUAL_CARDS = "183454"

EXCLUDE = re.compile(
    r"\b(lot|lots|bundle|bulk|proxy|custom|orica|fan ?made|replica|reprint|fake|not psa|"
    r"psa ready|pre-?grade|ungraded|raw|empty|case only|slab only|label only|sticker|"
    r"japanese|japan|korean|chinese|mystery|break|repack|pack|box|binder|choose|pick|choice|variation)\b",
    re.I,
)


def grade_pattern(company: str) -> re.Pattern:
    # "PSA 10", "PSA10", "PSA GEM MINT 10", "BGS 9.5", "CGC 8.5"
    return re.compile(
        rf"\b{company}\s*(?:gem\s*mint|gem-mt|mint|nm-mt|nm|ex-mt|ex|vg-ex|vg|good|fair|poor|pristine|authentic)?\s*"
        r"(10|[1-9](?:\.5)?)\b",
        re.I,
    )


def parse_grade(title: str, company: str) -> float | None:
    """Return the single grade named in a title, or None if absent/ambiguous/excluded."""
    if EXCLUDE.search(title):
        return None
    if re.search(r"\b(?:PSA|BGS|CGC|SGC|TAG|ACE)", title, re.I) is None:
        return None
    # "PSA 8 or 9", "PSA 9/10", "PSA 9 & 10": several grades on offer.
    if re.search(rf"\b{company}\s*\d+(?:\.5)?\s*(?:or|/|&|and|-)\s*\d", title, re.I):
        return None
    others = {"PSA", "BGS", "CGC", "SGC", "TAG", "ACE"} - {company.upper()}
    if any(re.search(rf"\b{o}\s*\d", title, re.I) for o in others):
        return None
    grades = {float(m) for m in grade_pattern(company).findall(title)}
    if len(grades) != 1:
        return None
    g = grades.pop()
    return g if 1 <= g <= 10 else None


def get_token(client_id: str, client_secret: str) -> str:
    auth = base64.b64encode(f"{client_id}:{client_secret}".encode()).decode()
    body = urllib.parse.urlencode({"grant_type": "client_credentials", "scope": SCOPE}).encode()
    req = urllib.request.Request(TOKEN_URL, data=body, method="POST", headers={
        "Authorization": f"Basic {auth}",
        "Content-Type": "application/x-www-form-urlencoded",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.load(r)["access_token"]
    except urllib.error.HTTPError as e:
        sys.exit(f"eBay token request failed ({e.code}): {e.read().decode(errors='replace')[:300]}\n"
                 "Check EBAY_CLIENT_ID / EBAY_CLIENT_SECRET are your *Production* keys.")


def search(token: str, query: str, offset: int, limit: int, marketplace: str) -> dict:
    params = urllib.parse.urlencode({
        "q": query,
        "category_ids": CCG_INDIVIDUAL_CARDS,
        "limit": str(limit),
        "offset": str(offset),
        "filter": "buyingOptions:{FIXED_PRICE|AUCTION}",
    })
    req = urllib.request.Request(f"{SEARCH_URL}?{params}", headers={
        "Authorization": f"Bearer {token}",
        "X-EBAY-C-MARKETPLACE-ID": marketplace,
        "Accept": "application/json",
    })
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503, 504) and attempt < 3:
                time.sleep(2 ** attempt * 2)
                continue
            raise
    return {}


def best_image_url(item: dict) -> str | None:
    url = (item.get("image") or {}).get("imageUrl") or next(
        (i.get("imageUrl") for i in item.get("thumbnailImages") or [] if i.get("imageUrl")), None)
    if not url:
        return None
    # eBay image URLs end in s-l<size>.jpg; ask for the large version.
    return re.sub(r"s-l\d+\.(jpg|jpeg|png|webp)", r"s-l1600.\1", url)


def download(url: str, dest: Path) -> str | None:
    """Download to dest; returns a content hash for de-duplication."""
    req = urllib.request.Request(url, headers={"User-Agent": "pokegrade-dataset/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            data = r.read()
    except Exception as e:  # noqa: BLE001 - best effort per image
        print(f"    download failed: {e}")
        return None
    if len(data) < 5_000:
        return None
    dest.write_bytes(data)
    return hashlib.sha1(data).hexdigest()


def parse_grades(spec: str) -> list[float]:
    out: list[float] = []
    for part in spec.split(","):
        if "-" in part:
            a, b = part.split("-")
            out += [float(g) for g in range(int(a), int(b) + 1)]
        else:
            out.append(float(part))
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--company", default="PSA", choices=["PSA", "BGS", "CGC"])
    ap.add_argument("--grades", default="1-10", help="e.g. 1-10 or 8,9,10 (BGS/CGC also 9.5 etc.)")
    ap.add_argument("--per-grade", type=int, default=100)
    ap.add_argument("--out", default="samples/ebay")
    ap.add_argument("--marketplace", default="EBAY_US")
    ap.add_argument("--extra-query", default="", help="extra search words, e.g. a set name")
    ap.add_argument("--max-pages", type=int, default=10, help="pages of 200 results per grade")
    args = ap.parse_args()

    cid, secret = os.environ.get("EBAY_CLIENT_ID"), os.environ.get("EBAY_CLIENT_SECRET")
    if not cid or not secret:
        sys.exit("Set EBAY_CLIENT_ID and EBAY_CLIENT_SECRET (see the top of this file).")

    out = Path(args.out)
    (out / "images").mkdir(parents=True, exist_ok=True)
    labels_path = out / "labels.csv"
    seen_items: set[str] = set()
    seen_hashes: set[str] = set()
    rows: list[dict] = []
    if labels_path.exists():
        with labels_path.open(newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                rows.append(r)
                seen_items.add(r["item_id"])

    token = get_token(cid, secret)
    token_time = time.time()

    for grade in parse_grades(args.grades):
        label = f"{grade:g}"
        have = sum(1 for r in rows if r["company"] == args.company and float(r["grade"]) == grade)
        print(f"{args.company} {label}: have {have}, want {args.per_grade}")
        query = f"pokemon {args.company} {label} {args.extra_query}".strip()
        folder = out / "images" / f"{args.company}-{label}"
        folder.mkdir(parents=True, exist_ok=True)

        for page in range(args.max_pages):
            if have >= args.per_grade:
                break
            if time.time() - token_time > 6000:  # tokens last ~2 h
                token, token_time = get_token(cid, secret), time.time()
            res = search(token, query, offset=page * 200, limit=200, marketplace=args.marketplace)
            items = res.get("itemSummaries") or []
            if not items:
                break
            for item in items:
                if have >= args.per_grade:
                    break
                item_id = item.get("itemId", "")
                title = item.get("title", "")
                if not item_id or item_id in seen_items:
                    continue
                if parse_grade(title, args.company) != grade:
                    continue
                url = best_image_url(item)
                if not url:
                    continue
                safe_id = re.sub(r"[^A-Za-z0-9_-]", "_", item_id)
                dest = folder / f"{safe_id}.jpg"
                digest = download(url, dest)
                if not digest or digest in seen_hashes:
                    dest.unlink(missing_ok=True)
                    continue
                seen_items.add(item_id)
                seen_hashes.add(digest)
                price = item.get("price") or {}
                rows.append({
                    "filename": str(dest.relative_to(out)),
                    "grade": label,
                    "company": args.company,
                    "title": title,
                    "item_id": item_id,
                    "url": item.get("itemWebUrl", ""),
                    "price": price.get("value", ""),
                    "currency": price.get("currency", ""),
                })
                have += 1
                time.sleep(0.2)  # be polite to the image CDN
            print(f"  page {page + 1}: now {have}")
            if res.get("next") is None:
                break

        with labels_path.open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=["filename", "grade", "company", "title", "item_id", "url", "price", "currency"])
            w.writeheader()
            w.writerows(rows)

    print(f"Done: {len(rows)} labelled photos in {labels_path}")


if __name__ == "__main__":
    main()
