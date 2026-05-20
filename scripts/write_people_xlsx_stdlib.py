#!/usr/bin/env python3
"""Write tests/fixtures/people.xlsx (Sheet1) matching tests/fixtures/people.csv — stdlib only.

Matches src/bin/generate_people_xlsx_fixture.rs when a full Rust build is unnecessary.

Run from repo root: python scripts/write_people_xlsx_stdlib.py
"""

from __future__ import annotations

import zipfile
from pathlib import Path

FILES: dict[str, str] = {
    "[Content_Types].xml": """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>""",
    "_rels/.rels": """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""",
    "xl/workbook.xml": """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>""",
    "xl/_rels/workbook.xml.rels": """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>""",
    "xl/styles.xml": """<?xml version="1.0" encoding="UTF-8"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
  <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
</styleSheet>""",
    "xl/worksheets/sheet1.xml": """<?xml version="1.0" encoding="UTF-8"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
    <row r="1" spans="1:4">
      <c r="A1" t="inlineStr"><is><t>id</t></is></c>
      <c r="B1" t="inlineStr"><is><t>name</t></is></c>
      <c r="C1" t="inlineStr"><is><t>score</t></is></c>
      <c r="D1" t="inlineStr"><is><t>active</t></is></c>
    </row>
    <row r="2" spans="1:4">
      <c r="A2"><v>1</v></c>
      <c r="B2" t="inlineStr"><is><t>Ada</t></is></c>
      <c r="C2"><v>98.5</v></c>
      <c r="D2" t="b"><v>1</v></c>
    </row>
    <row r="3" spans="1:4">
      <c r="A3"><v>2</v></c>
      <c r="B3" t="inlineStr"><is><t>Grace</t></is></c>
      <c r="C3"><v>87.25</v></c>
      <c r="D3" t="b"><v>0</v></c>
    </row>
  </sheetData>
</worksheet>""",
}


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    out = root / "tests" / "fixtures" / "people.xlsx"
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for name, body in FILES.items():
            zf.writestr(name, body.encode("utf-8"))
    print(f"Wrote {out}")


if __name__ == "__main__":
    main()
