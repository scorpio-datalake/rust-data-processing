//! Write `tests/fixtures/people.xlsx` (sheet `Sheet1`) aligned with `tests/fixtures/people.csv`.
//!
//! Run from repo root:
//! `cargo run --features excel_test_writer --bin generate_people_xlsx_fixture`
//!
//! If a full Rust build is impractical, the same workbook can be emitted with stdlib-only Python:
//! `python scripts/write_people_xlsx_stdlib.py`.

use std::path::PathBuf;

fn main() {
    use rust_xlsxwriter::Workbook;

    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let out = manifest_dir.join("tests/fixtures/people.xlsx");
    std::fs::create_dir_all(out.parent().expect("fixtures parent")).expect("create_dir_all");

    let mut wb = Workbook::new();
    let ws = wb.add_worksheet();
    ws.set_name("Sheet1").expect("set_name");

    ws.write_string(0, 0, "id").unwrap();
    ws.write_string(0, 1, "name").unwrap();
    ws.write_string(0, 2, "score").unwrap();
    ws.write_string(0, 3, "active").unwrap();

    ws.write_number(1, 0, 1).unwrap();
    ws.write_string(1, 1, "Ada").unwrap();
    ws.write_number(1, 2, 98.5).unwrap();
    ws.write_boolean(1, 3, true).unwrap();

    ws.write_number(2, 0, 2).unwrap();
    ws.write_string(2, 1, "Grace").unwrap();
    ws.write_number(2, 2, 87.25).unwrap();
    ws.write_boolean(2, 3, false).unwrap();

    wb.save(&out).expect("save xlsx");
    eprintln!("Wrote {}", out.display());
}
