//! Pytest mirror payloads for JVM tests (`python-wrapper/tests/*.py` analogues).

use std::path::PathBuf;

use crate::parity_support::*;

fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}

fn fixtures() -> PathBuf {
    repo_root().join("tests/fixtures")
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_bindings_mirror(out: *mut RdpJsonSlice) {
    write_slice(out, parity_bindings_mirror());
}

fn parity_bindings_mirror() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match mirror_bindings_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn mirror_bindings_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::execution::{ExecutionEngine, ExecutionOptions};
    use rust_data_processing::pipeline::{Agg, CastMode, DataFrame};
    use rust_data_processing::processing::{filter, reduce, ReduceOp};
    use rust_data_processing::profiling::{profile_dataset, ProfileOptions};
    use rust_data_processing::transform::{TransformSpec, TransformStep};
    use rust_data_processing::types::{DataSet, DataType, Field, Schema, Value};
    use rust_data_processing::validation::{validate_dataset, Check, Severity, ValidationSpec};
    use rust_data_processing::{
        outliers::{detect_outliers_dataset, OutlierMethod, OutlierOptions},
        profiling::SamplingMode,
    };

    // test_processing_reduce_and_filter
    let schema = Schema::new(vec![
        Field::new("id", DataType::Int64),
        Field::new("active", DataType::Bool),
        Field::new("score", DataType::Float64),
    ]);
    let ds = DataSet::new(
        schema.clone(),
        vec![
            vec![
                Value::Int64(1),
                Value::Bool(true),
                Value::Float64(10.0),
            ],
            vec![
                Value::Int64(2),
                Value::Bool(false),
                Value::Float64(20.0),
            ],
            vec![Value::Int64(3), Value::Bool(true), Value::Null],
        ],
    );
    let sum = reduce(&ds, "score", ReduceOp::Sum).ok_or("reduce sum")?;
    let kept = filter(&ds, |r| matches!(r.get(1), Some(Value::Bool(true))));

    // test_transform_apply_dict
    let ds_t = DataSet::new(
        Schema::new(vec![
            Field::new("id", DataType::Int64),
            Field::new("score", DataType::Int64),
        ]),
        vec![
            vec![Value::Int64(1), Value::Int64(10)],
            vec![Value::Int64(2), Value::Null],
        ],
    );
    let out_schema = Schema::new(vec![
        Field::new("id", DataType::Int64),
        Field::new("score_f", DataType::Float64),
    ]);
    let spec = TransformSpec::new(out_schema)
        .with_step(TransformStep::Rename {
            pairs: vec![("score".to_string(), "score_f".to_string())],
        })
        .with_step(TransformStep::Cast {
            column: "score_f".to_string(),
            to: DataType::Float64,
            mode: CastMode::Lossy,
        })
        .with_step(TransformStep::FillNull {
            column: "score_f".to_string(),
            value: Value::Float64(0.0),
        });
    let transformed = spec.apply(&ds_t).map_err(|e| e.to_string())?;

    // test_dataframe_pipeline_collect
    let ds_g = DataSet::new(
        Schema::new(vec![
            Field::new("g", DataType::Utf8),
            Field::new("v", DataType::Int64),
        ]),
        vec![
            vec![Value::Utf8("a".into()), Value::Int64(1)],
            vec![Value::Utf8("a".into()), Value::Int64(2)],
            vec![Value::Utf8("b".into()), Value::Int64(3)],
        ],
    );
    let lf = DataFrame::from_dataset(&ds_g).map_err(|e| e.to_string())?;
    let gb = lf
        .group_by(
            &["g"],
            &[Agg::Sum {
                column: "v".into(),
                alias: "s".into(),
            }],
        )
        .map_err(|e| e.to_string())?;
    let collected = gb.collect().map_err(|e| e.to_string())?;

    // test_profile_validate_outliers_helpers (subset)
    let px = DataSet::new(
        Schema::new(vec![Field::new("x", DataType::Float64)]),
        vec![vec![Value::Float64(1.0)], vec![Value::Null], vec![Value::Float64(3.0)]],
    );
    let prof = profile_dataset(
        &px,
        &ProfileOptions {
            sampling: SamplingMode::Head(2),
            quantiles: vec![0.5],
        },
    )
    .map_err(|e| e.to_string())?;

    let vds = DataSet::new(
        Schema::new(vec![Field::new("email", DataType::Utf8)]),
        vec![vec![Value::Null]],
    );
    let vrep = validate_dataset(
        &vds,
        &ValidationSpec::new(vec![Check::NotNull {
            column: "email".into(),
            severity: Severity::Error,
        }]),
    )
    .map_err(|e| e.to_string())?;

    let ods = DataSet::new(
        Schema::new(vec![Field::new("x", DataType::Float64)]),
        vec![
            vec![Value::Float64(1.0)],
            vec![Value::Float64(1.0)],
            vec![Value::Float64(1.0)],
            vec![Value::Float64(1.0)],
            vec![Value::Float64(1000.0)],
        ],
    );
    let ore = detect_outliers_dataset(
        &ods,
        "x",
        OutlierMethod::Iqr { k: 1.5 },
        &OutlierOptions {
            sampling: SamplingMode::Full,
            max_examples: 3,
        },
    )
    .map_err(|e| e.to_string())?;

    // test_execution_engine_reduce_metrics
    let ds_n = DataSet::new(
        Schema::new(vec![Field::new("n", DataType::Int64)]),
        vec![vec![Value::Int64(1)], vec![Value::Int64(2)], vec![Value::Int64(3)]],
    );
    let eng = ExecutionEngine::new(ExecutionOptions {
        chunk_size: 2,
        max_in_flight_chunks: 4,
        num_threads: Some(1),
    });
    let rsum = eng.reduce(&ds_n, "n", ReduceOp::Sum);
    let snap = eng.metrics().snapshot();

    // test_execution_filter_map_parallel_and_events
    let eng2 = ExecutionEngine::new(ExecutionOptions {
        chunk_size: 3,
        max_in_flight_chunks: 8,
        num_threads: Some(2),
    });
    let ds_i = DataSet::new(
        Schema::new(vec![Field::new("i", DataType::Int64)]),
        (0i64..10).map(|j| vec![Value::Int64(j)]).collect(),
    );
    let filt_p = eng2.filter_parallel(&ds_i, |r| match r.first() {
        Some(Value::Int64(v)) => *v % 2 == 0,
        _ => false,
    });
    let mapped_p = eng2.map_parallel(&ds_i, |r| match r.first() {
        Some(Value::Int64(v)) => vec![Value::Int64(*v * 10)],
        _ => r.to_vec(),
    });

    Ok(serde_json::json!({
        "kind": "bindings_mirror_pytest",
        "processing_reduce_sum": sum,
        "processing_filter_kept_rows": kept.row_count(),
        "transform_columns": transformed
            .schema
            .field_names()
            .map(|s| s.to_string())
            .collect::<Vec<_>>(),
        "transform_first_row": serde_json::to_value(transformed.rows.first()).unwrap_or_default(),
        "group_by_row_count": collected.row_count(),
        "profile_row_count": prof.row_count,
        "validation_failed_checks": vrep.summary.failed_checks,
        "outlier_count": ore.outlier_count,
        "execution_reduce_sum": rsum,
        "execution_metrics_rows_processed": snap.rows_processed,
        "parallel_filter_rows": filt_p.row_count(),
        "parallel_map_first": serde_json::to_value(mapped_p.rows.first()).unwrap_or_default(),
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_mapping_spec_mirror(out: *mut RdpJsonSlice) {
    write_slice(out, parity_mapping_spec_mirror());
}

fn parity_mapping_spec_mirror() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match mirror_mapping_spec_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn mirror_mapping_spec_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::pipeline::CastMode;
    use rust_data_processing::transform::{TransformSpec, TransformStep};
    use rust_data_processing::types::{DataSet, DataType, Field, Schema, Value};

    let ds = DataSet::new(
        Schema::new(vec![
            Field::new("id", DataType::Int64),
            Field::new("score", DataType::Int64),
            Field::new("name", DataType::Utf8),
        ]),
        vec![
            vec![
                Value::Int64(1),
                Value::Int64(10),
                Value::Utf8("Ada".into()),
            ],
            vec![
                Value::Int64(2),
                Value::Null,
                Value::Utf8("Grace".into()),
            ],
        ],
    );
    let out_schema = Schema::new(vec![
        Field::new("id", DataType::Int64),
        Field::new("score_i", DataType::Float64),
    ]);
    let spec = TransformSpec::new(out_schema)
        .with_step(TransformStep::Rename {
            pairs: vec![("score".to_string(), "score_i".to_string())],
        })
        .with_step(TransformStep::Cast {
            column: "score_i".to_string(),
            to: DataType::Float64,
            mode: CastMode::Strict,
        })
        .with_step(TransformStep::FillNull {
            column: "score_i".to_string(),
            value: Value::Float64(0.0),
        })
        .with_step(TransformStep::Select {
            columns: vec!["id".into(), "score_i".into()],
        });
    let out1 = spec.apply(&ds).map_err(|e| e.to_string())?;

    let ds2 = ds.clone();
    let out_schema2 = Schema::new(vec![
        Field::new("id", DataType::Int64),
        Field::new("score", DataType::Int64),
        Field::new("tag", DataType::Utf8),
    ]);
    let spec2 = TransformSpec::new(out_schema2)
        .with_step(TransformStep::WithLiteral {
            name: "tag".into(),
            value: Value::Utf8("v1".into()),
        })
        .with_step(TransformStep::Drop {
            columns: vec!["name".into()],
        })
        .with_step(TransformStep::Select {
            columns: vec!["id".into(), "score".into(), "tag".into()],
        });
    let out2 = spec2.apply(&ds2).map_err(|e| e.to_string())?;

    Ok(serde_json::json!({
        "kind": "mapping_spec_mirror_pytest",
        "rename_cast_fill_select": {
            "columns": out1.schema.field_names().map(|s| s.to_string()).collect::<Vec<_>>(),
            "rows": serde_json::to_value(&out1.rows).map_err(|e| e.to_string())?,
        },
        "drop_with_literal": {
            "columns": out2.schema.field_names().map(|s| s.to_string()).collect::<Vec<_>>(),
            "first_row_tag": serde_json::to_value(out2.rows.get(0).and_then(|r| r.get(2))).unwrap_or_default(),
        },
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_sql_suite_mirror(out: *mut RdpJsonSlice) {
    write_slice(out, parity_sql_suite_mirror());
}

fn parity_sql_suite_mirror() -> RdpJsonSlice {
    #[cfg(all(feature = "link-main", feature = "full"))]
    {
        match mirror_sql_suite_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(all(feature = "link-main", not(feature = "full")))]
    {
        json_err("rebuild rdp_jvm_sys with --features full for SQL mirror suite")
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(all(feature = "link-main", feature = "full"))]
fn people_dataset() -> rust_data_processing::types::DataSet {
    use rust_data_processing::types::{DataSet, DataType, Field, Schema, Value};
    let schema = Schema::new(vec![
        Field::new("id", DataType::Int64),
        Field::new("active", DataType::Bool),
        Field::new("score", DataType::Float64),
        Field::new("name", DataType::Utf8),
        Field::new("grp", DataType::Utf8),
    ]);
    DataSet::new(
        schema,
        vec![
            vec![
                Value::Int64(1),
                Value::Bool(true),
                Value::Float64(10.0),
                Value::Utf8("Ada".into()),
                Value::Utf8("A".into()),
            ],
            vec![
                Value::Int64(2),
                Value::Bool(false),
                Value::Float64(20.0),
                Value::Utf8("Grace".into()),
                Value::Utf8("A".into()),
            ],
            vec![
                Value::Int64(3),
                Value::Bool(true),
                Value::Float64(3.0),
                Value::Utf8("Linus".into()),
                Value::Utf8("B".into()),
            ],
            vec![
                Value::Int64(4),
                Value::Bool(true),
                Value::Null,
                Value::Utf8("Ken".into()),
                Value::Utf8("B".into()),
            ],
        ],
    )
}

#[cfg(all(feature = "link-main", feature = "full"))]
fn mirror_sql_suite_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::pipeline::DataFrame;
    use rust_data_processing::sql;

    let ds = people_dataset();
    let df = DataFrame::from_dataset(&ds).map_err(|e| e.to_string())?;

    let basic = sql::query(
        &df,
        r#"
        SELECT id, name, score
        FROM df
        WHERE active = TRUE
        ORDER BY id DESC
        LIMIT 2
        "#,
    )
    .map_err(|e| e.to_string())?
    .collect()
    .map_err(|e| e.to_string())?;

    let grouped = sql::query(
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
    .map_err(|e| e.to_string())?
    .collect()
    .map_err(|e| e.to_string())?;

    use rust_data_processing::ingestion::json::ingest_json_from_str;
    use rust_data_processing::pipeline_spec::PipelineBundle;

    let join_bundle = PipelineBundle::from_repo_fixture("sql_parity");
    let left_schema = join_bundle
        .load_schema("schemas/join_left.schema.json")
        .map_err(|e| e.to_string())?;
    let right_schema = join_bundle
        .load_schema("schemas/join_right.schema.json")
        .map_err(|e| e.to_string())?;
    let left_json = std::fs::read_to_string(join_bundle.root().join("data/join_left.json"))
        .map_err(|e| e.to_string())?;
    let right_json = std::fs::read_to_string(join_bundle.root().join("data/join_right.json"))
        .map_err(|e| e.to_string())?;
    let left = ingest_json_from_str(&left_json, &left_schema).map_err(|e| e.to_string())?;
    let right = ingest_json_from_str(&right_json, &right_schema).map_err(|e| e.to_string())?;
    let join_sql = join_bundle
        .load_query_sql("queries/join_people_scores.sql.json")
        .map_err(|e| e.to_string())?;
    let df_left = DataFrame::from_dataset(&left).map_err(|e| e.to_string())?;
    let df_right = DataFrame::from_dataset(&right).map_err(|e| e.to_string())?;
    let mut ctx = sql::Context::new();
    ctx.register("people", &df_left).map_err(|e| e.to_string())?;
    ctx.register("scores", &df_right).map_err(|e| e.to_string())?;
    let joined = ctx
        .execute(&join_sql)
        .map_err(|e| e.to_string())?
        .collect()
        .map_err(|e| e.to_string())?;

    let miss_tbl = sql::query(&df, "SELECT * FROM does_not_exist").map(|_| ());
    let miss_tbl_err = miss_tbl.err().map(|e| e.to_string()).unwrap_or_default();

    let miss_col = sql::query(&df, "SELECT missing_col FROM df").map(|_| ());
    let miss_col_err = miss_col.err().map(|e| e.to_string()).unwrap_or_default();

    Ok(serde_json::json!({
        "kind": "sql_suite_mirror_pytest",
        "basic_select": serde_json::to_value(&basic).map_err(|e| e.to_string())?,
        "group_having": serde_json::to_value(&grouped).map_err(|e| e.to_string())?,
        "join": serde_json::to_value(&joined).map_err(|e| e.to_string())?,
        "missing_table_error": miss_tbl_err,
        "missing_column_error_lower_has_missing": miss_col_err.to_ascii_lowercase().contains("missing"),
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_partition_discovery_mirror(out: *mut RdpJsonSlice) {
    write_slice(out, parity_partition_discovery_mirror());
}

fn parity_partition_discovery_mirror() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match mirror_partition_discovery_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn mirror_partition_discovery_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::ingestion::{
        discover_hive_partitioned_files, parse_partition_segment, paths_from_explicit_list,
        paths_from_glob,
    };

    let root = fixtures().join("hive_partitioned");
    let files = discover_hive_partitioned_files(&root, None).map_err(|e| e.to_string())?;
    let glob_files =
        discover_hive_partitioned_files(&root, Some("**/events.csv")).map_err(|e| e.to_string())?;

    let skip_root = fixtures().join("hive_partitioned_skip");
    let skip_files =
        discover_hive_partitioned_files(&skip_root, None).map_err(|e| e.to_string())?;

    let file_not_dir = fixtures().join("hive_partitioned/at_root.csv");
    let reject_non_directory = discover_hive_partitioned_files(&file_not_dir, None).is_err();

    let pat = fixtures().join("hive_partitioned").join("**/*.csv");
    let pat_s = pat.to_string_lossy().replace('\\', "/");
    let glob_paths = paths_from_glob(&pat_s).map_err(|e| e.to_string())?;

    let a = fixtures().join("hive_partitioned/at_root.csv");
    let b = fixtures().join("hive_partitioned/dt=2024-01-01/region=us/events.csv");
    let paths_list = vec![a.clone(), b.clone(), a.clone()];
    let explicit = paths_from_explicit_list(&paths_list).map_err(|e| e.to_string())?;

    let seg = parse_partition_segment("dt=2024-01-01");

    Ok(serde_json::json!({
        "kind": "partition_discovery_mirror_pytest",
        "discover_all_len": files.len(),
        "discover_events_glob_len": glob_files.len(),
        "skip_non_hive_len": skip_files.len(),
        "reject_non_directory_ok": reject_non_directory,
        "glob_csv_count": glob_paths.len(),
        "explicit_list_len": explicit.len(),
        "parse_dt": seg.map(|s| serde_json::json!({"key": s.key, "value": s.value})),
        "parse_nodash_is_null": parse_partition_segment("nodash").is_none(),
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_watermark_mirror(out: *mut RdpJsonSlice) {
    write_slice(out, parity_watermark_mirror());
}

fn parity_watermark_mirror() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match mirror_watermark_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn mirror_watermark_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::ingestion::{IngestionOptions, ingest_from_path};
    use rust_data_processing::types::{DataType, Field, Schema, Value};

    let schema = Schema::new(vec![
        Field::new("id", DataType::Int64),
        Field::new("ts", DataType::Int64),
    ]);

    let csv_path = fixtures().join("watermark_events.csv");
    let opts = IngestionOptions {
        watermark_column: Some("ts".into()),
        watermark_exclusive_above: Some(Value::Int64(100)),
        ..Default::default()
    };
    let ds_csv = ingest_from_path(&csv_path, &schema, &opts).map_err(|e| e.to_string())?;

    let opts_floor = IngestionOptions {
        watermark_column: Some("ts".into()),
        watermark_exclusive_above: Some(Value::Int64(200)),
        ..Default::default()
    };
    let ds_empty = ingest_from_path(&csv_path, &schema, &opts_floor).map_err(|e| e.to_string())?;

    let json_path = fixtures().join("watermark_events.json");
    let ds_json = ingest_from_path(&json_path, &schema, &opts).map_err(|e| e.to_string())?;

    let bad_opts = IngestionOptions {
        watermark_column: Some("ts".into()),
        watermark_exclusive_above: None,
        ..Default::default()
    };
    let wm_reject = ingest_from_path(&csv_path, &schema, &bad_opts);

    Ok(serde_json::json!({
        "kind": "watermark_mirror_pytest",
        "csv_row_count": ds_csv.row_count(),
        "csv_ids": ds_csv.rows.iter().filter_map(|r| r.first().cloned()).collect::<Vec<_>>(),
        "empty_row_count": ds_empty.row_count(),
        "json_row_count": ds_json.row_count(),
        "watermark_rejects_incomplete_options": wm_reject.is_err(),
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_deep_seattle_mirror(out: *mut RdpJsonSlice) {
    write_slice(out, parity_deep_seattle_mirror());
}

fn parity_deep_seattle_mirror() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match mirror_deep_seattle_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn mirror_deep_seattle_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::ingestion::{IngestionOptions, ingest_from_path};
    use rust_data_processing::pipeline::{Agg, DataFrame};
    use rust_data_processing::processing::{
        ReduceOp, VarianceKind, arg_max_row, arg_min_row, feature_wise_mean_std, reduce,
        top_k_by_frequency,
    };
    use rust_data_processing::types::{DataType, Field, Schema, Value};

    let schema = Schema::new(vec![
        Field::new("date", DataType::Utf8),
        Field::new("precipitation", DataType::Float64),
        Field::new("temp_max", DataType::Float64),
        Field::new("temp_min", DataType::Float64),
        Field::new("wind", DataType::Float64),
        Field::new("weather", DataType::Utf8),
    ]);
    let path = fixtures().join("deep/seattle-weather.csv");
    let ds = ingest_from_path(&path, &schema, &IngestionOptions::default()).map_err(|e| e.to_string())?;

    let mean_mem = reduce(&ds, "temp_max", ReduceOp::Mean);
    let mean_pol = DataFrame::from_dataset(&ds)
        .map_err(|e| e.to_string())?
        .reduce("temp_max", ReduceOp::Mean)
        .map_err(|e| e.to_string())?;

    let fw_s = feature_wise_mean_std(
        &ds,
        &["precipitation", "temp_max", "temp_min", "wind"],
        VarianceKind::Sample,
    )
    .ok_or("feature_wise sample")?;
    let fw_p = DataFrame::from_dataset(&ds)
        .map_err(|e| e.to_string())?
        .feature_wise_mean_std(
            &["precipitation", "temp_max", "temp_min", "wind"],
            VarianceKind::Sample,
        )
        .map_err(|e| e.to_string())?;

    let gb = DataFrame::from_dataset(&ds)
        .map_err(|e| e.to_string())?
        .group_by(
            &["weather"],
            &[
                Agg::Mean {
                    column: "temp_max".into(),
                    alias: "mu_tmax".into(),
                },
                Agg::Max {
                    column: "temp_min".into(),
                    alias: "max_tmin".into(),
                },
                Agg::CountRows {
                    alias: "n_rows".into(),
                },
                Agg::CountDistinctNonNull {
                    column: "date".into(),
                    alias: "n_dates".into(),
                },
                Agg::StdDev {
                    column: "wind".into(),
                    alias: "sd_wind".into(),
                    kind: VarianceKind::Sample,
                },
            ],
        )
        .map_err(|e| e.to_string())?
        .collect()
        .map_err(|e| e.to_string())?;

    let idx_date = ds.schema.index_of("date").unwrap();
    let col_names = gb.schema.field_names().collect::<Vec<_>>();
    let nidx = col_names.iter().position(|n| *n == "n_rows").unwrap();
    let mut total_n = 0i64;
    for r in &gb.rows {
        if let Some(Value::Int64(v)) = r.get(nidx) {
            total_n += *v;
        }
    }

    let (i_max, v_max) = match arg_max_row(&ds, "temp_max") {
        Some(Some((i, v))) => (Some(i), Some(v)),
        _ => (None, None),
    };
    let (_i_min, v_min) = match arg_min_row(&ds, "temp_max") {
        Some(Some((i, v))) => (Some(i), Some(v)),
        _ => (None, None),
    };
    let top = top_k_by_frequency(&ds, "weather", 5).unwrap_or_default();

    Ok(serde_json::json!({
        "kind": "deep_seattle_mirror_pytest",
        "row_count": ds.row_count(),
        "first_date": ds.rows.get(0).and_then(|r| r.get(idx_date)).cloned(),
        "reduce_mean_mem_vs_polars": serde_json::json!({ "mem": mean_mem, "polars": mean_pol }),
        "feature_wise_len_match": fw_s.len() == fw_p.len(),
        "group_by_total_rows_sum": total_n,
        "dataset_row_count": ds.row_count() as i64,
        "arg_max_idx": i_max,
        "top_weather_pairs": serde_json::to_value(&top).map_err(|e| e.to_string())?,
        "arg_max_ge_min": match (&v_max, &v_min) {
            (Some(Value::Float64(a)), Some(Value::Float64(b))) => *a >= *b,
            _ => false,
        },
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_sft_sample_mirror(out: *mut RdpJsonSlice) {
    write_slice(out, parity_sft_sample_mirror());
}

fn parity_sft_sample_mirror() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match mirror_sft_sample_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn mirror_sft_sample_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::export::dataset_to_jsonl;
    use rust_data_processing::ingestion::{IngestionOptions, ingest_from_path};
    use rust_data_processing::types::{DataType, Field, Schema};

    let path = repo_root().join("examples/sft/sample_alpaca.ndjson");
    let schema = Schema::new(vec![
        Field::new("instruction", DataType::Utf8),
        Field::new("input", DataType::Utf8),
        Field::new("output", DataType::Utf8),
    ]);
    let ds = ingest_from_path(&path, &schema, &IngestionOptions::default()).map_err(|e| e.to_string())?;
    let jsonl = dataset_to_jsonl(&ds, &["instruction".into(), "input".into(), "output".into()])
        .map_err(|e| e.to_string())?;
    let lines: Vec<&str> = jsonl.lines().collect();

    Ok(serde_json::json!({
        "kind": "sft_sample_mirror_pytest",
        "row_count": ds.row_count(),
        "jsonl_line_count": lines.len(),
        "first_line_has_instruction": lines.first().map(|s| s.contains("instruction")).unwrap_or(false),
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_benchmark_smoke_mirror(out: *mut RdpJsonSlice) {
    write_slice(out, parity_benchmark_smoke_mirror());
}

fn parity_benchmark_smoke_mirror() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match mirror_benchmark_smoke_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn mirror_benchmark_smoke_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::execution::{ExecutionEngine, ExecutionOptions};
    use rust_data_processing::pipeline::{Agg, DataFrame};
    use rust_data_processing::processing::{filter, map, reduce, ReduceOp};
    use rust_data_processing::types::{DataSet, DataType, Field, Schema, Value};

    let n = 8000usize;
    let schema = Schema::new(vec![
        Field::new("id", DataType::Int64),
        Field::new("active", DataType::Bool),
        Field::new("score", DataType::Float64),
        Field::new("aux", DataType::Float64),
        Field::new("grp", DataType::Utf8),
    ]);
    let mut rows = Vec::with_capacity(n);
    for i in 0..n {
        rows.push(vec![
            Value::Int64(i as i64),
            Value::Bool((i % 3) != 0),
            Value::Float64(i as f64 * 0.1),
            Value::Float64(i as f64 * 0.03 + 1.0),
            Value::Utf8(format!("g{}", i % 8)),
        ]);
    }
    let ds = DataSet::new(schema, rows);

    let active_idx = 1usize;
    let id_idx = 0usize;
    let score_idx = 2usize;
    let filt = filter(&ds, |r| {
        matches!(r.get(active_idx), Some(Value::Bool(true)))
            && matches!(r.get(id_idx), Some(Value::Int64(v)) if *v % 2 == 0)
    });
    let mapped = map(&filt, |r| {
        let mut o = r.to_vec();
        if let Some(Value::Float64(x)) = o.get_mut(score_idx) {
            *x *= 1.1;
        }
        o
    });
    let red = reduce(&mapped, "score", ReduceOp::Sum);

    let lf = DataFrame::from_dataset(&ds).map_err(|e| e.to_string())?;
    let _gb = lf
        .group_by(
            &["grp"],
            &[
                Agg::Mean {
                    column: "score".into(),
                    alias: "m".into(),
                },
                Agg::Sum {
                    column: "aux".into(),
                    alias: "s".into(),
                },
            ],
        )
        .map_err(|e| e.to_string())?
        .collect()
        .map_err(|e| e.to_string())?;

    let eng = ExecutionEngine::new(ExecutionOptions {
        chunk_size: 512,
        max_in_flight_chunks: 16,
        num_threads: Some(2),
    });
    let ds4000 = DataSet::new(ds.schema.clone(), ds.rows[..4000].to_vec());
    let fp = eng.filter_parallel(&ds4000, |r| match r.first() {
        Some(Value::Int64(v)) => *v % 2 == 0,
        _ => false,
    });

    Ok(serde_json::json!({
        "kind": "benchmark_smoke_mirror_pytest",
        "wide_row_count": ds.row_count(),
        "filtered_rows": filt.row_count(),
        "reduce_sum": serde_json::to_value(&red).map_err(|e| e.to_string())?,
        "group_by_collect_ok": true,
        "parallel_filter_rows": fp.row_count(),
    }))
}

#[no_mangle]
pub unsafe extern "C" fn rdp_parity_observability_mirror(out: *mut RdpJsonSlice) {
    write_slice(out, parity_observability_mirror());
}

fn parity_observability_mirror() -> RdpJsonSlice {
    #[cfg(feature = "link-main")]
    {
        match mirror_observability_impl() {
            Ok(v) => json_ok(v),
            Err(e) => json_err(e),
        }
    }
    #[cfg(not(feature = "link-main"))]
    {
        json_err("rebuild rdp_jvm_sys with --features link-main (or jvm_ffi / full)")
    }
}

#[cfg(feature = "link-main")]
fn mirror_observability_impl() -> Result<serde_json::Value, String> {
    use rust_data_processing::ingestion::{IngestionOptions, ingest_from_path};
    use rust_data_processing::types::{DataType, Field, Schema};

    let schema = Schema::new(vec![Field::new("id", DataType::Int64)]);
    let missing = fixtures().join("__does_not_exist_rdp_obs__.csv");
    let ingest_err = ingest_from_path(&missing, &schema, &IngestionOptions::default());

    Ok(serde_json::json!({
        "kind": "observability_mirror_pytest",
        "missing_file_is_err": ingest_err.is_err(),
        "note": "Python/JVM ingestion observers are not wired over this FFI; mirror checks Rust error path only.",
    }))
}
