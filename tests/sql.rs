use rust_data_processing::ingestion::json::ingest_json_from_path;
use rust_data_processing::ingestion::json::ingest_json_from_str;
use rust_data_processing::pipeline::DataFrame;
use rust_data_processing::pipeline_spec::PipelineBundle;
use rust_data_processing::sql;
use rust_data_processing::types::{DataSet, Value};

fn people_dataset() -> DataSet {
    let schema = PipelineBundle::from_repo_fixture("people")
        .expect_schema("schemas/tabular_5col.schema.json");

    let rows = vec![
        vec![
            Value::Int64(1),
            Value::Bool(true),
            Value::Float64(10.0),
            Value::Utf8("Ada".to_string()),
            Value::Utf8("A".to_string()),
        ],
        vec![
            Value::Int64(2),
            Value::Bool(false),
            Value::Float64(20.0),
            Value::Utf8("Grace".to_string()),
            Value::Utf8("A".to_string()),
        ],
        vec![
            Value::Int64(3),
            Value::Bool(true),
            Value::Float64(3.0),
            Value::Utf8("Linus".to_string()),
            Value::Utf8("B".to_string()),
        ],
        vec![
            Value::Int64(4),
            Value::Bool(true),
            Value::Null,
            Value::Utf8("Ken".to_string()),
            Value::Utf8("B".to_string()),
        ],
    ];

    DataSet::new(schema, rows)
}

#[test]
fn sql_basic_select_where_order_limit_works() {
    let ds = people_dataset();
    let df = DataFrame::from_dataset(&ds).unwrap();

    let out = sql::query(
        &df,
        r#"
        SELECT id, name, score
        FROM df
        WHERE active = TRUE
        ORDER BY id DESC
        LIMIT 2
        "#,
    )
    .unwrap()
    .collect()
    .unwrap();

    assert_eq!(
        out.schema.field_names().collect::<Vec<_>>(),
        vec!["id", "name", "score"]
    );
    assert_eq!(out.row_count(), 2);
    assert_eq!(out.rows[0][0], Value::Int64(4));
    assert_eq!(out.rows[0][1], Value::Utf8("Ken".to_string()));
    assert_eq!(out.rows[0][2], Value::Null);
    assert_eq!(out.rows[1][0], Value::Int64(3));
    assert_eq!(out.rows[1][1], Value::Utf8("Linus".to_string()));
    assert_eq!(out.rows[1][2], Value::Float64(3.0));
}

#[test]
fn sql_group_by_aggregates_and_having_work() {
    let ds = people_dataset();
    let df = DataFrame::from_dataset(&ds).unwrap();

    let out = sql::query(
        &df,
        r#"
        SELECT
          grp,
          SUM(score) AS sum_score,
          COUNT(*) AS cnt
        FROM df
        GROUP BY grp
        HAVING SUM(score) > 10
        ORDER BY grp ASC
        "#,
    )
    .unwrap()
    .collect()
    .unwrap();

    assert_eq!(
        out.schema.field_names().collect::<Vec<_>>(),
        vec!["grp", "sum_score", "cnt"]
    );
    assert_eq!(out.row_count(), 1);
    assert_eq!(out.rows[0][0], Value::Utf8("A".to_string()));
    assert_eq!(out.rows[0][1], Value::Float64(30.0));
    assert_eq!(out.rows[0][2], Value::Int64(2));
}

/// Same committed fixtures as `docs/java/examples/SQLQueries.java` JOIN sketch (`sql_parity/`).
#[test]
fn sql_context_join_matches_sql_parity_fixtures() {
    let bundle = PipelineBundle::from_repo_fixture("sql_parity");
    let left_schema = bundle.load_schema("schemas/join_left.schema.json").unwrap();
    let right_schema = bundle
        .load_schema("schemas/join_right.schema.json")
        .unwrap();
    let left_json = std::fs::read_to_string(bundle.root().join("data/join_left.json")).unwrap();
    let right_json = std::fs::read_to_string(bundle.root().join("data/join_right.json")).unwrap();
    let left = ingest_json_from_str(&left_json, &left_schema).unwrap();
    let right = ingest_json_from_str(&right_json, &right_schema).unwrap();
    let sql = bundle
        .load_query_sql("queries/join_people_scores.sql.json")
        .unwrap();

    let df_left = DataFrame::from_dataset(&left).unwrap();
    let df_right = DataFrame::from_dataset(&right).unwrap();

    let mut ctx = sql::Context::new();
    ctx.register("people", &df_left).unwrap();
    ctx.register("scores", &df_right).unwrap();

    let out = ctx.execute(&sql).unwrap().collect().unwrap();

    assert_eq!(
        out.schema.field_names().collect::<Vec<_>>(),
        vec!["id", "name", "score"]
    );
    assert_eq!(out.row_count(), 2);
    assert_eq!(out.rows[0][0], Value::Int64(1));
    assert_eq!(out.rows[0][1], Value::Utf8("Ada".to_string()));
    assert_eq!(out.rows[0][2], Value::Float64(98.5));
    assert_eq!(out.rows[1][0], Value::Int64(3));
    assert_eq!(out.rows[1][1], Value::Utf8("Linus".to_string()));
    assert_eq!(out.rows[1][2], Value::Float64(77.0));
}

/// Polars SQL from `jvm_contract/pipelines/sql_query_dataset.pipeline.json` on `data/three_rows.json`
/// (JVM: `rdp_run_pipeline_json`; Python/Java doc examples use the same SQL text).
#[test]
fn sql_query_dataset_pipeline_sql_matches_jvm_contract_fixture() {
    let bundle = PipelineBundle::from_repo_fixture("jvm_contract");
    let schema = bundle
        .load_schema("schemas/three_rows.schema.json")
        .unwrap();
    let path = bundle.root().join("data/three_rows.json");
    let ds = ingest_json_from_path(path.to_str().unwrap(), &schema).unwrap();
    assert_eq!(ds.row_count(), 3);

    let sql = bundle
        .pipeline_transform_sql("pipelines/sql_query_dataset.pipeline.json")
        .unwrap();
    let df = DataFrame::from_dataset(&ds).unwrap();
    let out = sql::query(&df, &sql).unwrap().collect().unwrap();

    assert_eq!(
        out.schema.field_names().collect::<Vec<_>>(),
        vec!["id", "score"]
    );
    assert_eq!(out.row_count(), 2);
    assert_eq!(out.rows[0][0], Value::Int64(2));
    assert_eq!(out.rows[0][1], Value::Float64(20.0));
    assert_eq!(out.rows[1][0], Value::Int64(1));
    assert_eq!(out.rows[1][1], Value::Float64(10.0));
}

#[test]
fn sql_missing_table_returns_engine_error() {
    let ds = people_dataset();
    let df = DataFrame::from_dataset(&ds).unwrap();

    let err = match sql::query(&df, "SELECT * FROM does_not_exist") {
        Ok(_) => panic!("expected SQL error for missing table"),
        Err(e) => e.to_string(),
    };
    assert!(!err.is_empty());
}

#[test]
fn sql_missing_column_returns_actionable_error() {
    let ds = people_dataset();
    let df = DataFrame::from_dataset(&ds).unwrap();

    let err = match sql::query(&df, "SELECT missing_col FROM df") {
        Ok(_) => panic!("expected SQL error for missing column"),
        Err(e) => e.to_string(),
    };
    assert!(err.to_ascii_lowercase().contains("missing"));
}
