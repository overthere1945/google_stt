#!/usr/bin/env python3
# 파일명: tools/md_to_docx.py
# 목적 및 기능:
# - 릴리스 노트 Markdown(docs/RELEASE_NOTES_v<판>.md)을 Word 문서(.docx)로 만든다(문서 규칙: 릴리스 노트는 MD + docx).
# - 다루는 문법: 제목(#, ##, ###), 글머리표(-, 들여쓴 -), 번호 목록(1.), 표(| … |), **굵게**, `코드`, 일반 문단.
#   번호 목록은 Word 자동 번호를 쓰지 않고 번호를 글자로 적는다(자동 번호는 장이 바뀌어도 이어 세어진다).
# - 필요한 것: python-docx (pip install python-docx 또는 apt install python3-docx)
#
# 사용법:
#   python3 tools/md_to_docx.py docs/RELEASE_NOTES_v4.0.0.md            # 같은 이름의 .docx 를 만든다
#   python3 tools/md_to_docx.py 입력.md 출력.docx
#
# add-hyungchul-20261109-1127

import re                                   # 문법 판별
import sys                                  # 명령줄 인자

from docx import Document                   # 문서 만들기
from docx.enum.table import WD_TABLE_ALIGNMENT   # 표 정렬
from docx.oxml import OxmlElement           # 셀 배경색
from docx.oxml.ns import qn                 # XML 이름
from docx.shared import Cm, Pt, RGBColor    # 길이·글자 크기·색

FONT = "맑은 고딕"                           # 본문 글꼴(한글)
CODE_FONT = "Consolas"                      # 코드 글꼴
INLINE = re.compile(r"(\*\*.+?\*\*|`[^`]+`)")   # **굵게** 와 `코드`


# 목적: 런에 글꼴을 입힌다(한글 글꼴은 eastAsia 속성까지 줘야 적용된다).
# 입력: run - 런, name - 글꼴 이름, size - 글자 크기(pt, 없으면 그대로) / 출력: 런의 글꼴이 바뀐다 / 리턴: 없음
def set_font(run, name, size=None):
    run.font.name = name                                             # 영문 글꼴
    rpr = run._element.get_or_add_rPr()                              # 런 속성
    fonts = rpr.find(qn("w:rFonts"))                                 # 글꼴 요소
    if fonts is None:                                                # 없으면 만든다
        fonts = OxmlElement("w:rFonts")
        rpr.append(fonts)
    fonts.set(qn("w:eastAsia"), name)                                # 한글 글꼴
    if size is not None:
        run.font.size = Pt(size)                                     # 글자 크기


# 목적: 한 줄의 글을 **굵게**·`코드`·보통 글자로 나눠 문단에 넣는다.
# 입력: par - 문단, text - Markdown 한 줄, size - 글자 크기(pt), bold - 줄 전체를 굵게 할지 / 출력: 문단에 런이 더해진다 / 리턴: 없음
def add_runs(par, text, size=10, bold=False):
    for part in INLINE.split(text):                                  # 표시 단위로 자른다
        if not part:
            continue
        if part.startswith("**"):                                    # 굵게(안에 `코드` 가 들어 있을 수 있다)
            for piece in re.split(r"(`[^`]+`)", part[2:-2]):
                if not piece:
                    continue
                code = piece.startswith("`")                         # 굵은 글 안의 코드
                run = par.add_run(piece[1:-1] if code else piece)
                run.bold = True
                set_font(run, CODE_FONT if code else FONT, size - 0.5 if code else size)
        elif part.startswith("`"):                                   # 코드
            run = par.add_run(part[1:-1])
            run.bold = bold
            set_font(run, CODE_FONT, size - 0.5)
        else:                                                        # 보통 글자
            run = par.add_run(part)
            run.bold = bold
            set_font(run, FONT, size)


# 목적: 표 셀에 배경색을 넣는다(머리줄 구분용).
# 입력: cell - 셀, color - 16진 색(예 "D9E2F3") / 출력: 셀 배경 / 리턴: 없음
def shade(cell, color):
    tcpr = cell._element.get_or_add_tcPr()                           # 셀 속성
    shd = OxmlElement("w:shd")                                       # 배경 요소
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), color)
    tcpr.append(shd)


# 목적: Markdown 표 줄들을 Word 표로 만든다(첫 줄은 머리줄).
# 입력: doc - 문서, rows - 줄마다 칸 글자 목록 / 출력: 문서에 표가 더해진다 / 리턴: 없음
def add_table(doc, rows):
    cols = max(len(r) for r in rows)                                 # 칸 수
    table = doc.add_table(rows=len(rows), cols=cols)                 # 표 만들기
    table.style = "Table Grid"                                       # 테두리
    table.alignment = WD_TABLE_ALIGNMENT.CENTER                      # 가운데
    for i, row in enumerate(rows):
        for j in range(cols):
            cell = table.cell(i, j)
            par = cell.paragraphs[0]
            par.paragraph_format.space_after = Pt(0)                 # 칸 안 여백 줄이기
            add_runs(par, row[j] if j < len(row) else "", size=9, bold=(i == 0))
            if i == 0:
                shade(cell, "D9E2F3")                                # 머리줄 배경
    doc.add_paragraph().paragraph_format.space_after = Pt(2)         # 표 뒤 한 줄


# 목적: Markdown 파일을 읽어 docx 로 저장한다.
# 입력: src - 입력 .md 경로, dst - 출력 .docx 경로 / 출력: dst 파일 / 리턴: 없음
def convert(src, dst):
    doc = Document()                                                 # 빈 문서(기본 틀)
    sec = doc.sections[0]
    sec.page_width, sec.page_height = Cm(21.0), Cm(29.7)             # A4
    sec.left_margin = sec.right_margin = Cm(2.0)                     # 좌우 여백
    sec.top_margin = sec.bottom_margin = Cm(2.0)                     # 위아래 여백
    rows = []                                                        # 모으는 중인 표 줄
    for line in open(src, encoding="utf-8").read().split("\n"):
        if line.startswith("|"):                                     # 표 줄
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if not all(re.fullmatch(r":?-+:?", c) for c in cells):   # 구분 줄(---)은 버린다
                rows.append(cells)
            continue
        if rows:                                                     # 표가 끝났다
            add_table(doc, rows)
            rows = []
        if not line.strip():                                         # 빈 줄
            continue
        m = re.match(r"(#+) (.*)", line)
        if m:                                                        # 제목
            level = min(len(m.group(1)), 3)
            par = doc.add_heading(level=level)
            add_runs(par, m.group(2), size={1: 18, 2: 14, 3: 12}[level], bold=True)
            for run in par.runs:
                run.font.color.rgb = RGBColor(0x1F, 0x3A, 0x5F)      # 제목 색
            continue
        m = re.match(r"(\s*)- (.*)", line)
        if m:                                                        # 글머리표(들여쓰면 2단계)
            deep = len(m.group(1)) >= 2
            par = doc.add_paragraph(style="List Bullet 2" if deep else "List Bullet")
            par.paragraph_format.space_after = Pt(2)
            add_runs(par, m.group(2))
            continue
        m = re.match(r"\s*(\d+)\. (.*)", line)
        if m:                                                        # 번호 목록 — 번호를 글자로 적는다
            par = doc.add_paragraph()
            par.paragraph_format.left_indent = Cm(0.9)
            par.paragraph_format.first_line_indent = Cm(-0.6)
            par.paragraph_format.space_after = Pt(2)
            add_runs(par, "%s. %s" % (m.group(1), m.group(2)))
            continue
        par = doc.add_paragraph()                                    # 일반 문단
        add_runs(par, line.strip())
    if rows:                                                         # 파일이 표로 끝났다
        add_table(doc, rows)
    doc.save(dst)


if __name__ == "__main__":
    if len(sys.argv) < 2:                                            # 인자 확인
        sys.exit("사용법: python3 tools/md_to_docx.py 입력.md [출력.docx]")
    source = sys.argv[1]
    target = sys.argv[2] if len(sys.argv) > 2 else re.sub(r"\.md$", "", source) + ".docx"
    convert(source, target)
    print("만듦:", target)
