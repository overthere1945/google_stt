#!/usr/bin/env python3
# 파일명: tools/check_long_run.py
# 목적 및 기능:
# - 장시간 실행(LT-01: 24시간 이상 연속)의 결과 폴더를 읽어 합격 기준을 판정한다.
#     1) 작업이 모두 끝났는가(_plan_progress.csv — DONE / FAILED / SKIPPED / INTERRUPTED 수)
#     2) 연속으로 돈 시간이 기준(기본 24시간) 이상인가(_plan_memlog.csv 의 첫 줄 ~ 마지막 줄)
#     3) 실행 중 앱이 꺼진 적이 없는가(_plan_exits.csv 에 실행 시작 뒤의 줄이 없어야 한다)
#     4) 기록이 끊긴 구간이 없는가(메모리 기록은 30초마다 — 5분 넘게 비면 앱이 멈춰 있었다는 뜻)
#     5) 메모리 부족 표시가 없는가. 앱 PSS 는 실행을 4등분해 구간별 바닥 값(낮은 쪽 10%)을 보여 준다 — 계속 커지는지는 사람이 본다
#        (구간마다 도는 모델이 달라 자동 판정에 넣지 않는다)
#     6) 품질 의심 표시가 붙은 행이 몇 개인가(result.csv 의 note — 참고용, 합격 여부에는 넣지 않는다)
# - 읽기만 한다. 폴더를 고치지 않는다.
#
# 사용법:
#   adb pull /sdcard/Documents/output/lt1_check ./lt1
#   python3 tools/check_long_run.py ./lt1/lt1_check            # 기준 24시간
#   python3 tools/check_long_run.py ./lt1/lt1_check --hours 24 --start "2026-11-09 14:05:00"
#
# add-hyungchul-20261109-1406

import argparse                      # 명령줄 인자
import csv                           # CSV 읽기
import datetime                      # 시각 계산
import glob                          # 결과 CSV 찾기
import os                            # 경로
import sys                           # 종료 코드

FMT = "%Y-%m-%d %H:%M:%S"            # 기록 파일의 시각 형식


# 목적: utf-8-sig CSV 를 줄 목록으로 읽는다("#" 로 시작하는 주석 줄은 뺀다).
# 입력: path - 파일 경로 / 출력: 없음 / 리턴: list[dict] - 파일이 없으면 빈 목록
def read_csv(path):
    if not os.path.exists(path):                                     # 파일이 없으면 빈 목록
        return []
    with open(path, encoding="utf-8-sig", newline="") as f:
        lines = [line for line in f if not line.startswith("#")]     # 주석 줄 제외
    return list(csv.DictReader(lines))


# 목적: 시각 글자를 datetime 으로 바꾼다.
# 입력: text - "2026-11-09 14:05:36" / 출력: 없음 / 리턴: datetime 또는 None(형식이 다르면)
def parse_time(text):
    try:
        return datetime.datetime.strptime(text.strip(), FMT)
    except ValueError:
        return None


# 목적: 결과 폴더를 판정해 화면에 적는다.
# 입력: root - 결과 폴더, need_hours - 연속 시간 기준, start_text - 실행 시작 시각(없으면 메모리 기록의 첫 줄) / 출력: 화면
# 리턴: bool - 모든 합격 기준을 채웠으면 True
def judge(root, need_hours, start_text):
    ok = True                                                        # 전체 판정
    progress = read_csv(os.path.join(root, "_plan_progress.csv"))
    memlog = read_csv(os.path.join(root, "_plan_memlog.csv"))
    exits = read_csv(os.path.join(root, "_plan_exits.csv"))
    times = [t for t in (parse_time(r.get("time", "")) for r in memlog) if t]
    start = parse_time(start_text) if start_text else None
    if start:                                                        # 시작 시각 뒤의 기록만 본다
        memlog = [r for r in memlog if (parse_time(r.get("time", "")) or start) >= start]
        times = [t for t in times if t >= start]
        progress = [r for r in progress if (parse_time(r.get("started", "")) or start) >= start]
    if not times:
        print("[판정 불가] _plan_memlog.csv 에 기록이 없다:", root)
        return False
    start = start or times[0]

    # 1) 작업 상태
    count = {}
    for r in progress:
        count[r["status"]] = count.get(r["status"], 0) + 1
    total = int(memlog[-1].get("total") or 0)                        # 이번 실행의 전체 작업 수
    done = count.get("DONE", 0)
    bad = {k: v for k, v in count.items() if k != "DONE"}
    line1 = done == total and not bad
    print("1) 작업: 전체 %d, DONE %d, 그 밖 %s → %s" % (total, done, bad or "없음", "통과" if line1 else "확인 필요"))
    for r in progress:
        if r["status"] != "DONE":
            print("     %s  %s  %s" % (r["key"], r["status"], r.get("note", "")))
    ok = ok and line1

    # 2) 연속 시간
    hours = (times[-1] - times[0]).total_seconds() / 3600
    line2 = hours >= need_hours
    print("2) 연속 시간: %s ~ %s = %.1f시간 (기준 %.1f시간) → %s" % (times[0], times[-1], hours, need_hours, "통과" if line2 else "미달"))
    ok = ok and line2

    # 3) 실행 중 앱 종료
    during = [r for r in exits if (parse_time(r.get("time", "")) or datetime.datetime.min) > start]
    line3 = not during
    print("3) 실행 중 앱 종료: %d건 → %s" % (len(during), "통과" if line3 else "실패"))
    for r in during:
        print("     %s  %s  %s" % (r.get("time"), r.get("reason"), (r.get("description") or "")[:60]))
    ok = ok and line3

    # 4) 기록이 끊긴 구간(30초 간격 → 5분 넘게 비면 멈춘 것)
    gaps = [(a, b) for a, b in zip(times, times[1:]) if (b - a).total_seconds() > 300]
    line4 = not gaps
    print("4) 기록 공백(5분 초과): %d건 → %s" % (len(gaps), "통과" if line4 else "확인 필요"))
    for a, b in gaps[:10]:
        print("     %s ~ %s (%.0f분)" % (a, b, (b - a).total_seconds() / 60))
    ok = ok and line4

    # 5) 메모리
    pss = [int(r["app_pss_mb"]) for r in memlog if (r.get("app_pss_mb") or "").isdigit()]
    low = sum(1 for r in memlog if r.get("low_memory") == "true")
    quarter = max(1, len(pss) // 4)
    floor = lambda part: sorted(part)[len(part) // 10] if part else 0   # 구간의 낮은 쪽 10% 값(모델이 내려간 때의 바닥)
    floors = [floor(pss[i * quarter:(i + 1) * quarter]) for i in range(4)]   # 실행을 4등분한 구간별 바닥 값
    line5 = low == 0                                                 # 합격 기준은 "메모리 부족 표시 없음"
    print("5) 메모리 부족 표시 %d회 → %s" % (low, "통과" if line5 else "실패"))
    print("   앱 PSS 구간별 바닥 값(4등분, MB): %s, 최대 %d MB — 뒤로 갈수록 계속 커지면 누수를 의심한다(참고: 구간마다 도는 모델이 달라 사람이 본다)"
          % (floors, max(pss)))
    ok = ok and line5
    thermal = {}
    for r in memlog:
        thermal[r.get("thermal", "")] = thermal.get(r.get("thermal", ""), 0) + 1
    print("   발열 단계별 기록 수(30초 단위):", thermal)

    # 6) 품질 의심 표시(참고)
    notes, rows, errors = {}, 0, 0
    for path in glob.glob(os.path.join(root, "*", "*_result.csv")):
        for r in read_csv(path):
            rows += 1
            errors += r.get("status") == "ERROR"
            for piece in (r.get("note") or "").split(";"):
                key = piece.split(":")[0].strip()
                if key:
                    notes[key] = notes.get(key, 0) + 1
    print("6) 결과 행 %d개, status=ERROR %d개, note 표시: %s (참고 — 합격 판정에는 넣지 않는다)" % (rows, errors, notes or "없음"))

    print("=> LT-01 판정:", "통과" if ok else "통과 아님(위의 '확인 필요'·'실패'·'미달' 항목을 본다)")
    return ok


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="장시간 실행 결과 폴더를 판정한다")
    parser.add_argument("root", help="결과 폴더(안에 _plan_progress.csv 가 있는 폴더)")
    parser.add_argument("--hours", type=float, default=24.0, help="연속 시간 기준(기본 24)")
    parser.add_argument("--start", default="", help='실행 시작 시각 "YYYY-MM-DD HH:MM:SS"(이 시각 앞의 기록은 뺀다)')
    args = parser.parse_args()
    sys.exit(0 if judge(args.root, args.hours, args.start) else 1)
