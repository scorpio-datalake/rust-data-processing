//! FTP ingest against a loopback server (requires Cargo feature `cloud_connectors`).

#![cfg(feature = "cloud_connectors")]

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use rust_data_processing::ingestion::{
    IngestionFormat, IngestionOptions, ingest_from_file_transfer_uri,
};
use rust_data_processing::pipeline_spec::PipelineBundle;

fn ftp_reply(stream: &mut TcpStream, msg: &str) {
    let line = if msg.ends_with("\r\n") {
        msg.to_string()
    } else {
        format!("{msg}\r\n")
    };
    stream.write_all(line.as_bytes()).expect("reply");
}

fn run_control_session(mut ctrl: TcpStream, data_listener: TcpListener, file_bytes: Vec<u8>) {
    ftp_reply(&mut ctrl, "220 rdp-test FTP ready");
    let mut buf = [0u8; 256];
    loop {
        let n = ctrl.read(&mut buf).unwrap_or(0);
        if n == 0 {
            break;
        }
        let line = String::from_utf8_lossy(&buf[..n]);
        let upper = line.trim().to_uppercase();
        if upper.starts_with("USER") {
            ftp_reply(&mut ctrl, "331 Password required");
        } else if upper.starts_with("PASS") {
            ftp_reply(&mut ctrl, "230 User logged in");
        } else if upper.starts_with("CWD") {
            ftp_reply(&mut ctrl, "250 CWD ok");
        } else if upper.starts_with("TYPE I") || upper == "TYPE I" {
            ftp_reply(&mut ctrl, "200 Type set to I");
        } else if upper.starts_with("PASV") {
            let data_port = data_listener.local_addr().unwrap().port();
            let p1 = data_port / 256;
            let p2 = data_port % 256;
            ftp_reply(
                &mut ctrl,
                &format!("227 Entering Passive Mode (127,0,0,1,{p1},{p2})"),
            );
        } else if upper.starts_with("RETR") {
            ftp_reply(&mut ctrl, "150 Opening BINARY mode data connection");
            if let Ok((mut data, _)) = data_listener.accept() {
                let _ = data.write_all(&file_bytes);
            }
            ftp_reply(&mut ctrl, "226 Transfer complete");
        } else if upper.starts_with("QUIT") {
            ftp_reply(&mut ctrl, "221 Goodbye");
            break;
        } else {
            ftp_reply(&mut ctrl, "502 Command not implemented");
        }
    }
}

fn spawn_loopback_ftp_server(file_bytes: Vec<u8>) -> u16 {
    let control_listener = TcpListener::bind("127.0.0.1:0").expect("control bind");
    let data_listener = TcpListener::bind("127.0.0.1:0").expect("data bind");
    let control_port = control_listener.local_addr().unwrap().port();
    let (ready_tx, ready_rx) = mpsc::channel();

    thread::spawn(move || {
        let _ = ready_tx.send(());
        if let Ok((ctrl, _)) = control_listener.accept() {
            run_control_session(ctrl, data_listener, file_bytes);
        }
    });

    ready_rx
        .recv_timeout(Duration::from_secs(2))
        .expect("server ready");
    thread::sleep(Duration::from_millis(50));
    control_port
}

#[test]
fn ingest_from_ftp_uri_local_server() {
    let bundle = PipelineBundle::from_repo_fixture("file_transfer");
    let schema = bundle.expect_schema("schemas/id_name.schema.json");
    let json_path = bundle.root().join("data/two_rows.json");
    let payload = std::fs::read_to_string(&json_path).expect("read fixture json");

    let port = spawn_loopback_ftp_server(payload.into_bytes());
    let uri = format!("ftp://etl_user:secret@127.0.0.1:{port}/incoming/two_rows.json");
    let mut opts = IngestionOptions::default();
    opts.format = Some(IngestionFormat::Json);

    let ds = ingest_from_file_transfer_uri(&uri, &schema, &opts)
        .unwrap_or_else(|e| panic!("ftp ingest: {e}"));
    assert_eq!(ds.row_count(), 2);
}

#[test]
fn file_transfer_pipeline_template_resolves() {
    use std::collections::HashMap;

    let bundle = PipelineBundle::from_repo_fixture("file_transfer");
    let json = bundle
        .resolve_pipeline_json(
            "pipelines/ftp_sources_only.pipeline.json",
            &HashMap::from([
                (
                    "FTP_URI".into(),
                    "ftp://u:p@127.0.0.1:21/incoming/two_rows.json".into(),
                ),
                ("SINK_PATH".into(), "/tmp/out.parquet".into()),
            ]),
        )
        .expect("resolve pipeline");
    assert!(json.contains("file_transfer_uris"));
}
