# dbt Python model — reference only (requires `rust-data-processing` installed in dbt's Python env).
# Rename to your project naming; keep return type as pandas.DataFrame per dbt.

def model(dbt, session):
    import pandas as pd

    import rust_data_processing as rdp

    # Use an absolute path on the runner; example points at this repo's tiny CSV fixture.
    csv_path = dbt.config.get("rdp_example_csv", "tests/fixtures/people.csv")
    schema = [
        {"name": "id", "data_type": "int64"},
        {"name": "name", "data_type": "utf8"},
    ]
    ds = rdp.ingest_from_path(csv_path, schema)
    rows = ds.to_rows()
    return pd.DataFrame(rows, columns=[f["name"] for f in schema])
