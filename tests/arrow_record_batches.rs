#![cfg(feature = "arrow")]

use std::sync::Arc;

use arrow::array::{Int64Array, StringArray};
use arrow::datatypes::{DataType as ArrowDataType, Field, Schema as ArrowSchema};
use arrow::record_batch::RecordBatch;

use rust_data_processing::transform::arrow::record_batches_to_dataset;
use rust_data_processing::types::{DataType, Field as DField, Schema, Value};

fn batch1() -> RecordBatch {
    let schema = Arc::new(ArrowSchema::new(vec![
        Field::new("id", ArrowDataType::Int64, true),
        Field::new("name", ArrowDataType::Utf8, true),
    ]));
    let ids = Int64Array::from(vec![1, 2]);
    let names = StringArray::from(vec!["a", "b"]);
    RecordBatch::try_new(schema, vec![Arc::new(ids), Arc::new(names)]).unwrap()
}

fn batch2() -> RecordBatch {
    let schema = Arc::new(ArrowSchema::new(vec![
        Field::new("id", ArrowDataType::Int64, true),
        Field::new("name", ArrowDataType::Utf8, true),
    ]));
    let ids = Int64Array::from(vec![3]);
    let names = StringArray::from(vec!["c"]);
    RecordBatch::try_new(schema, vec![Arc::new(ids), Arc::new(names)]).unwrap()
}

#[test]
fn concat_record_batches_into_dataset() {
    let ds_schema = Schema::new(vec![
        DField::new("id", DataType::Int64),
        DField::new("name", DataType::Utf8),
    ]);
    let ds = record_batches_to_dataset(&[batch1(), batch2()], &ds_schema).unwrap();
    assert_eq!(ds.row_count(), 3);
    assert_eq!(ds.rows[2][0], Value::Int64(3));
    assert_eq!(ds.rows[2][1], Value::Utf8("c".into()));
}
