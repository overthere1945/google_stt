#!/usr/bin/env python3
# 파일명: tools/fetch_assets.py
# 목적 및 기능:
# - PC 에서 앱의 자산(STT 모델·VAD 모델)을 받아 두 가지를 만든다(D13 의 B 방법, 5단계 T4-6).
#     1) 해시 목록 app/src/main/assets/asset_manifest.json — 압축 파일과 풀린 파일의 SHA256(앱이 받은 파일을 이 값으로 검증한다)
#     2) 가져오기용 폴더 <보관 폴더>/stt_assets/<자산 ID>/<파일…> — 앱이 쓰는 낱개 파일만(휴대폰 Documents 로 옮겨 "PC 에서 가져오기")
# - 자산 목록은 tools/asset_catalog.json 을 읽는다. 이 파일은 Catalog.kt 의 사본이며 CatalogExportTest 가 같음을 지킨다.
# - 압축 파일은 통째로 풀지 않는다. 목록에 있는 항목만 스트림으로 읽어 정해진 이름으로 쓴다(압축 안의 "../" 경로를 따라가지 않는다).
# - 받다가 끊기면 이어받는다(Range). 이미 받아 해시가 맞는 자산은 건너뛴다.
#
# 사용법:
#   python3 tools/fetch_assets.py                      # 모든 자산
#   python3 tools/fetch_assets.py --only sherpa-onnx-whisper-base silero-vad
#   python3 tools/fetch_assets.py --out /data/stt --keep-archives --no-manifest
#   adb push <보관 폴더>/stt_assets /sdcard/Documents/   # 휴대폰으로 옮기기
#
# add-hyungchul-20261106-1718 — [T4-6]

import argparse          # 명령줄 인자
import datetime          # 만든 날짜
import hashlib           # SHA256
import json              # 목록·매니페스트
import os                # 경로·파일
import sys               # 종료 코드
import tarfile           # tar.bz2 읽기
import time              # 다시 시도 대기
import urllib.error      # HTTP 오류
import urllib.request    # 내려받기
import zipfile           # zip 읽기

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))          # 프로젝트 루트
CATALOG = os.path.join(ROOT, "tools", "asset_catalog.json")                 # 자산 목록(Catalog.kt 사본)
MANIFEST = os.path.join(ROOT, "app", "src", "main", "assets", "asset_manifest.json")   # 해시 목록
DEFAULT_OUT = os.path.join(ROOT, "stt_models")                              # 보관 폴더(.gitignore 대상)
BUFFER = 1024 * 1024                                                        # 읽기 단위 1 MB
RETRY_WAITS = (5, 15, 45)                                                   # 다시 시도 전 대기(초) — 앱과 같다
USER_AGENT = "google-stt-fetch-assets"                                      # 요청 머리말에 넣을 이름


def sha256_file(path):
    """목적: 파일의 SHA256 을 구한다. 입력: path - 파일 경로 / 출력: 없음 / 리턴: str - 16진수 소문자 64자"""
    h = hashlib.sha256()                                                    # 해시 계산기
    with open(path, "rb") as f:                                             # 이진 읽기
        for chunk in iter(lambda: f.read(BUFFER), b""):                     # 1 MB 씩
            h.update(chunk)
    return h.hexdigest()


def download(url, dest, expect):
    """
    목적: url 을 dest 로 받는다. dest 가 있으면 이어받는다.
    입력: url - 주소, dest - 받을 파일 경로, expect - 예상 크기(모르면 None)
    출력: dest 가 쓰인다 / 리턴: 없음
    예외: RuntimeError - 다시 시도를 다 쓰고도 받지 못했을 때
    """
    os.makedirs(os.path.dirname(dest), exist_ok=True)                       # 받을 폴더
    if expect is not None and os.path.isfile(dest) and os.path.getsize(dest) == expect:
        return                                                              # 이미 다 받았다
    restarted = False                                                       # 416 으로 처음부터 다시 받았는지
    tries = 0                                                               # 다시 시도한 횟수
    while True:
        have = os.path.getsize(dest) if os.path.isfile(dest) else 0         # 이미 받은 크기
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        if have > 0:
            req.add_header("Range", "bytes=%d-" % have)                     # 이어받기
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:           # 리다이렉트는 자동으로 따라간다
                append = resp.status == 206                                 # 206 이면 이어 붙인다(200 이면 처음부터)
                with open(dest, "ab" if append else "wb") as out:
                    for chunk in iter(lambda: resp.read(BUFFER), b""):
                        out.write(chunk)
            if expect is None or os.path.getsize(dest) >= expect:
                return                                                      # 다 받았다
            raise OSError("받다가 끊겼다(%d / %d B)" % (os.path.getsize(dest), expect))
        except urllib.error.HTTPError as e:
            if e.code == 416 and not restarted:                             # 범위가 맞지 않음 → 지우고 한 번만 처음부터
                restarted = True
                os.remove(dest)
                continue
            if e.code < 500:
                raise RuntimeError("HTTP %d — %s" % (e.code, url))          # 4xx 는 다시 시도하지 않는다
            err = e
        except OSError as e:                                                # 끊김·시간 초과(URLError 포함)
            err = e
        if tries >= len(RETRY_WAITS):
            raise RuntimeError("받지 못했다: %s (%s)" % (url, err))
        print("    다시 시도 %d/%d (%s)" % (tries + 1, len(RETRY_WAITS), err), flush=True)
        time.sleep(RETRY_WAITS[tries])
        tries += 1


def copy_hashed(src, dest):
    """목적: 스트림을 파일로 쓰며 SHA256 을 구한다. 입력: src - 읽을 스트림, dest - 쓸 경로 / 출력: dest / 리턴: str - 해시"""
    os.makedirs(os.path.dirname(dest), exist_ok=True)                       # 하위 폴더(tokenizer/ 등)
    h = hashlib.sha256()
    with open(dest + ".part", "wb") as out:                                 # 임시 이름으로 쓴 뒤 바꾼다
        for chunk in iter(lambda: src.read(BUFFER), b""):
            out.write(chunk)
            h.update(chunk)
    os.replace(dest + ".part", dest)
    return h.hexdigest()


def extract(archive, kind, files, dest_dir):
    """
    목적: 압축 파일에서 목록의 항목만 꺼낸다.
    입력: archive - 압축 파일 경로, kind - "TAR_BZ2" 또는 "ZIP", files - [[압축 안 경로, 저장 경로], …], dest_dir - 자산 폴더
    출력: dest_dir 아래에 파일이 쓰인다 / 리턴: dict - 저장 경로 → SHA256
    예외: RuntimeError - 필요한 항목이 압축 안에 없을 때
    """
    wanted = {src: target for src, target in files}                         # 압축 안 경로 → 저장 경로
    hashes = {}                                                             # 저장 경로 → 해시
    if kind == "ZIP":
        with zipfile.ZipFile(archive) as z:
            for name in z.namelist():                                       # 항목 이름들
                if name in wanted:
                    with z.open(name) as src:
                        hashes[wanted[name]] = copy_hashed(src, os.path.join(dest_dir, wanted[name]))
    else:
        with tarfile.open(archive, "r:bz2") as t:
            for member in t:                                                # 앞에서부터 차례로(스트림)
                name = member.name[2:] if member.name.startswith("./") else member.name
                if name in wanted and member.isfile():
                    hashes[wanted[name]] = copy_hashed(t.extractfile(member), os.path.join(dest_dir, wanted[name]))
    missing = sorted(set(wanted.values()) - set(hashes))                    # 꺼내지 못한 파일
    if missing:
        raise RuntimeError("압축 안에 필요한 파일이 없다: %s" % ", ".join(missing))
    return hashes


def is_done(asset, entry, dest_dir):
    """목적: 이미 받아 해시가 맞는 자산인지 본다. 입력: asset - 자산 정의, entry - 매니페스트 항목(없으면 None), dest_dir - 자산 폴더 / 출력: 없음 / 리턴: bool"""
    if not entry or (asset["archive"] != "NONE" and "archiveSha256" not in entry):
        return False                                                        # 해시가 아직 없다
    for _src, target in asset["files"]:
        path = os.path.join(dest_dir, target)
        if not os.path.isfile(path) or entry.get("files", {}).get(target) != sha256_file(path):
            return False                                                    # 파일이 없거나 다르다
    return True


def process(asset, out, keep_archives):
    """
    목적: 자산 하나를 받아 가져오기용 폴더를 만들고 해시를 구한다.
    입력: asset - 자산 정의, out - 보관 폴더, keep_archives - 압축 파일을 남길지
    출력: <out>/stt_assets/<ID>/ 가 만들어진다 / 리턴: dict - 매니페스트 항목 {"archiveSha256"?, "files"}
    """
    dest_dir = os.path.join(out, "stt_assets", asset["id"])                 # 가져오기용 폴더
    if asset["archive"] == "NONE":                                          # 압축 없음: 파일마다 "주소/경로"
        hashes = {}
        for src, target in asset["files"]:
            path = os.path.join(dest_dir, target)
            download(asset["url"] + "/" + src, path + ".dl", None)
            os.replace(path + ".dl", path)
            hashes[target] = sha256_file(path)
        return {"files": hashes}
    archive = os.path.join(out, "_download", asset["url"].rsplit("/", 1)[1])   # 압축 파일 위치
    download(asset["url"], archive, asset["archiveBytes"])
    size = os.path.getsize(archive)
    if asset["archiveBytes"] is not None and size != asset["archiveBytes"]:
        print("    [주의] 압축 크기가 목록과 다르다: 실제 %d, 목록 %d → Catalog 의 크기를 고쳐야 한다" % (size, asset["archiveBytes"]))
    entry = {"archiveSha256": sha256_file(archive)}
    entry["files"] = extract(archive, asset["archive"], asset["files"], dest_dir)
    if not keep_archives:
        os.remove(archive)                                                  # 풀었으면 압축 파일은 지운다
    return entry


def main():
    """목적: 인자를 읽어 자산들을 처리하고 매니페스트를 쓴다. 입력: 명령줄 / 출력: 파일들 / 리턴: int - 실패가 있으면 1"""
    ap = argparse.ArgumentParser(description="자산을 받아 해시 목록과 가져오기용 폴더를 만든다")
    ap.add_argument("--out", default=DEFAULT_OUT, help="보관 폴더(기본 <프로젝트>/stt_models)")
    ap.add_argument("--only", nargs="+", metavar="ID", help="이 자산 ID 들만")
    ap.add_argument("--keep-archives", action="store_true", help="압축 파일을 지우지 않는다")
    ap.add_argument("--no-manifest", action="store_true", help="asset_manifest.json 을 고치지 않는다")
    args = ap.parse_args()
    with open(CATALOG, encoding="utf-8") as f:
        assets = json.load(f)["assets"]                                     # 자산 목록(Catalog 순서)
    known = {a["id"] for a in assets}
    for i in args.only or []:
        if i not in known:
            sys.exit("모르는 자산 ID: %s" % i)
    manifest = {}                                                           # 자산 ID → 항목(앞서 만든 값은 남긴다)
    if os.path.isfile(MANIFEST):
        with open(MANIFEST, encoding="utf-8") as f:
            manifest = json.load(f).get("assets", {})
    failed = []                                                             # 실패한 자산 ID
    for a in assets:
        if args.only and a["id"] not in args.only:
            continue
        print("[%s]" % a["id"], flush=True)
        if is_done(a, manifest.get(a["id"]), os.path.join(args.out, "stt_assets", a["id"])):
            print("    이미 있음(해시 일치)")
            continue
        try:
            manifest[a["id"]] = process(a, args.out, args.keep_archives)
            print("    완료: 파일 %d개" % len(manifest[a["id"]]["files"]), flush=True)
        except (RuntimeError, OSError, tarfile.TarError, zipfile.BadZipFile) as e:
            print("    [실패] %s" % e, flush=True)
            failed.append(a["id"])
    if not args.no_manifest:
        ordered = {a["id"]: manifest[a["id"]] for a in assets if a["id"] in manifest}   # Catalog 순서로
        doc = {
            "version": 1,
            "generated": datetime.date.today().isoformat(),
            "note": "자산 해시 목록. 값이 없는 자산은 앱에서 \"미검증\" 으로 표시된다. tools/fetch_assets.py 가 만든다.",
            "assets": ordered,
        }
        with open(MANIFEST, "w", encoding="utf-8", newline="\n") as f:
            json.dump(doc, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print("매니페스트: %s (자산 %d개)" % (MANIFEST, len(ordered)))
    if failed:
        print("실패: %s" % ", ".join(failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
