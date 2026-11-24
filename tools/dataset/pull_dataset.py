#!/usr/bin/env python3
"""
Download the shared PokéGrade dataset from Firebase, verify PSA cert numbers,
and lay the photos out for the training/evaluation tools.

Setup (once):
  pip install firebase-admin
  Firebase console -> Project settings -> Service accounts -> "Generate new private key".
  Save it somewhere OUTSIDE the repo, then:
    export GOOGLE_APPLICATION_CREDENTIALS=/path/to/serviceAccount.json
    export PSA_API_TOKEN=...        # from https://www.psacard.com/publicapi (free: 100 calls/day)

Run:
  python3 tools/dataset/pull_dataset.py               # pull + verify, write samples/
  python3 tools/dataset/pull_dataset.py --psa-images  # also download PSA's own slab scans (1 extra call per cert)

Then:
  ./gradlew :tools:run --args="grades $PWD/samples/graded"
  ./gradlew :tools:run --args="backs  $PWD/samples/backs --write-model"

Output (samples/ is git-ignored):
  samples/dataset/scans.jsonl       every scan's metadata (+ verification result)
  samples/dataset/images/           every uploaded photo
  samples/graded/<grade>/           fronts of slabs whose grade is verified (or all, with --include-unverified)
  samples/backs/real|fake/          backs labelled genuine (pulled from a pack / verified slab) or confirmed fake
  samples/fronts/real|fake/         fronts, same split
  samples/psa_images/<cert>_*.jpg   PSA's own scans (with --psa-images)
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

PSA_BASE = "https://api.psacard.com/publicapi"

# Public cert lookup pages, for checking non-PSA slabs by hand.
CERT_LOOKUP = {
    "PSA": "https://www.psacard.com/cert/{cert}",
    "CGC": "https://www.cgccards.com/certlookup/{cert}/",
}


# ----------------------------------------------------------------- PSA API

class PsaQuotaExceeded(Exception):
    pass


def psa_get(path: str, token: str) -> dict | list | None:
    req = urllib.request.Request(f"{PSA_BASE}{path}", headers={
        "Authorization": f"bearer {token}",
        "Accept": "application/json",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="replace")
        if e.code == 429 or "quota" in body.lower():
            raise PsaQuotaExceeded(body[:200])
        if e.code in (400, 404):
            return None
        raise


def parse_psa_grade(card_grade: str | None) -> float | None:
    """'GEM MT 10' -> 10.0, 'NM-MT 8' -> 8.0, 'MINT 9' -> 9.0, 'EX 5.5'? -> 5.5. Authentic-only -> None."""
    if not card_grade:
        return None
    m = re.search(r"(\d{1,2}(?:\.5)?)\s*$", card_grade.strip())
    if not m:
        return None
    g = float(m.group(1))
    return g if 1 <= g <= 10 else None


def verify_cert(cert: str, token: str, cache: dict) -> dict:
    """Returns {"found": bool, "grade": float|None, "subject": ..., ...}; cached by cert."""
    if cert in cache:
        return cache[cert]
    data = psa_get(f"/cert/GetByCertNumber/{cert}", token)
    psa = (data or {}).get("PSACert") if isinstance(data, dict) else None
    if not psa:
        result = {"found": False}
    else:
        result = {
            "found": True,
            "grade": parse_psa_grade(psa.get("CardGrade")),
            "cardGrade": psa.get("CardGrade"),
            "gradeDescription": psa.get("GradeDescription"),
            "subject": psa.get("Subject"),
            "year": psa.get("Year"),
            "brand": psa.get("Brand"),
            "cardNumber": psa.get("CardNumber"),
            "variety": psa.get("Variety"),
            "category": psa.get("Category"),
        }
    cache[cert] = result
    return result


def find_image_urls(obj, hint: str = "") -> list[tuple[str, str]]:
    """The image endpoint's schema isn't documented: walk the JSON and collect
    (url, side) pairs, using nearby keys like IsFrontImage to label the side."""
    out: list[tuple[str, str]] = []
    if isinstance(obj, dict):
        side = hint
        for k, v in obj.items():
            if isinstance(v, bool) and "front" in k.lower():
                side = "front" if v else "back"
        for k, v in obj.items():
            if isinstance(v, str) and v.startswith("http") and re.search(r"\.(jpe?g|png|webp)(\?|$)", v, re.I):
                s = side or ("front" if "front" in k.lower() else "back" if "back" in k.lower() else "")
                out.append((v, s))
            elif isinstance(v, (dict, list)):
                out += find_image_urls(v, side)
    elif isinstance(obj, list):
        for v in obj:
            out += find_image_urls(v, hint)
    return out


def download(url: str, dest: Path) -> bool:
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "pokegrade-dataset/1.0"})
        with urllib.request.urlopen(req, timeout=30) as r:
            dest.write_bytes(r.read())
        return True
    except Exception as e:  # noqa: BLE001
        print(f"    image download failed: {e}")
        return False


# ---------------------------------------------------------------- layout

def place(src: Path, folder: Path, name: str) -> None:
    folder.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(src, folder / name)


def grade_folder(g: float) -> str:
    return f"{g:g}"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="samples")
    ap.add_argument("--psa-images", action="store_true", help="also download PSA's scans of each verified slab")
    ap.add_argument("--include-unverified", action="store_true", help="put unverified slab grades into samples/graded too")
    ap.add_argument("--no-writeback", action="store_true", help="don't mark verified labels in Firestore")
    args = ap.parse_args()

    try:
        import firebase_admin
        from firebase_admin import firestore
    except ImportError:
        sys.exit("pip install firebase-admin")
    if not os.environ.get("GOOGLE_APPLICATION_CREDENTIALS"):
        sys.exit("Set GOOGLE_APPLICATION_CREDENTIALS to your Firebase service-account key (see top of file).")
    token = os.environ.get("PSA_API_TOKEN")
    if not token:
        print("PSA_API_TOKEN not set: PSA certs won't be verified.")

    firebase_admin.initialize_app()
    db = firestore.client()

    out = Path(args.out)
    ds = out / "dataset"
    img_dir = ds / "images"
    img_dir.mkdir(parents=True, exist_ok=True)
    cache_path = ds / "psa_cache.json"
    cache = json.loads(cache_path.read_text()) if cache_path.exists() else {}
    for sub in ["graded", "backs/real", "backs/fake", "fronts/real", "fronts/fake"]:
        shutil.rmtree(out / sub, ignore_errors=True)

    quota_hit = False
    counts = {"scans": 0, "verified": 0, "mismatch": 0, "not_found": 0, "graded": 0, "real": 0, "fake": 0}
    with (ds / "scans.jsonl").open("w", encoding="utf-8") as jl:
        for doc in db.collection("scans").stream():
            counts["scans"] += 1
            scan = doc.to_dict()
            sid = doc.id
            label = scan.get("label") or {}
            ltype = label.get("type")

            # Photos (stored as JPEG bytes in scanImages/<scanId>_<kind>).
            local: dict[str, Path] = {}
            for kind, img_id in (scan.get("images") or {}).items():
                p = img_dir / f"{sid}_{kind}.jpg"
                if not p.exists():
                    snap = db.collection("scanImages").document(img_id).get()
                    data = (snap.to_dict() or {}).get("jpeg") if snap.exists else None
                    if not data:
                        continue
                    p.write_bytes(bytes(data))
                local[kind] = p

            # Verify PSA certs.
            verification = None
            final_grade = label.get("grade")
            verified = bool(label.get("verified"))
            cert = (label.get("certNumber") or "").strip()
            if ltype == "GRADED_SLAB" and label.get("company") == "PSA" and cert and token and not quota_hit:
                try:
                    verification = verify_cert(cert, token, cache)
                except PsaQuotaExceeded as e:
                    print(f"PSA daily quota reached ({e}). Re-run tomorrow to verify the rest.")
                    quota_hit = True
                if verification is not None:
                    if not verification["found"]:
                        counts["not_found"] += 1
                        print(f"  {sid}: PSA cert {cert} not found. Typo, or a fake slab.")
                    elif verification["grade"] is not None:
                        verified = True
                        counts["verified"] += 1
                        if final_grade is not None and abs(float(final_grade) - verification["grade"]) > 0.01:
                            counts["mismatch"] += 1
                            print(f"  {sid}: user said {final_grade}, PSA says {verification['grade']} ({verification['cardGrade']})")
                        final_grade = verification["grade"]
                    if not args.no_writeback:
                        doc.reference.update({"label.verified": verified, "label.psa": verification, "label.verifiedGrade": final_grade})
                    if args.psa_images and verification.get("found") and not quota_hit:
                        try:
                            imgs = psa_get(f"/cert/GetImagesByCertNumber/{cert}", token)
                            for i, (url, side) in enumerate(find_image_urls(imgs)):
                                d = out / "psa_images"
                                d.mkdir(parents=True, exist_ok=True)
                                download(url, d / f"{cert}_{side or i}.jpg")
                        except PsaQuotaExceeded:
                            quota_hit = True

            # Lay out for the tools.
            front, back = local.get("front"), local.get("back")
            if ltype == "GRADED_SLAB" and final_grade is not None and (verified or args.include_unverified) and front:
                place(front, out / "graded" / grade_folder(float(final_grade)), f"{sid}.jpg")
                counts["graded"] += 1
            genuine = ltype == "PULLED_GENUINE" or (ltype == "GRADED_SLAB" and verified)
            if genuine or ltype == "CONFIRMED_FAKE":
                side = "real" if genuine else "fake"
                counts[side] += 1
                if front:
                    place(front, out / "fronts" / side, f"{sid}.jpg")
                if back:
                    place(back, out / "backs" / side, f"{sid}.jpg")

            company = label.get("company")
            if company in CERT_LOOKUP and cert:
                scan["certLookup"] = CERT_LOOKUP[company].format(cert=cert)
            scan["id"] = sid
            scan["verification"] = verification
            scan["finalGrade"] = final_grade
            jl.write(json.dumps(scan, default=str) + "\n")

    cache_path.write_text(json.dumps(cache, indent=1))
    print(json.dumps(counts, indent=1))


if __name__ == "__main__":
    main()
