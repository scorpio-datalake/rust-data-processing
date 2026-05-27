//! SFTP and FTP/FTPS file ingest (download remote object, then use local path readers).
//!
//! URIs: `sftp://`, `ftp://`, `ftps://`
//!
//! Credentials are read from the URL userinfo when present; production passwords and SSH keys
//! should be supplied via environment variables on the process running Rust (not pipeline JSON):
//!
//! - `SFTP_PASSWORD` / `FTP_PASSWORD` — override URL password when set
//! - `SFTP_USER` / `FTP_USER` — used when URL has no username
//! - `SFTP_PRIVATE_KEY_PATH` — SSH private key for SFTP (optional; password auth otherwise)

use std::io::Write;
use std::net::TcpStream;
use std::path::{Path, PathBuf};

use ssh2::Session;
use url::Url;

use crate::error::{IngestionError, IngestionResult};
use crate::types::{DataSet, Schema};

use super::{IngestionFormat, IngestionOptions, ingest_from_path};

/// Returns true when `uri` uses a supported file-transfer scheme.
pub fn is_file_transfer_uri(uri: &str) -> bool {
    file_transfer_scheme(uri).is_some()
}

/// `sftp`, `ftp`, or `ftps` when recognized.
pub fn file_transfer_scheme(uri: &str) -> Option<&'static str> {
    let lower = uri.to_ascii_lowercase();
    if lower.starts_with("sftp://") {
        Some("sftp")
    } else if lower.starts_with("ftps://") {
        Some("ftps")
    } else if lower.starts_with("ftp://") {
        Some("ftp")
    } else {
        None
    }
}

fn temp_download_path(suffix: &str) -> IngestionResult<PathBuf> {
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    Ok(std::env::temp_dir().join(format!("rdp_ft_dl_{stamp}{suffix}")))
}

fn infer_format_from_remote_path(
    remote_path: &str,
    options: &IngestionOptions,
) -> IngestionResult<IngestionFormat> {
    if let Some(f) = options.format {
        return Ok(f);
    }
    let ext = Path::new(remote_path)
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("");
    match ext.to_ascii_lowercase().as_str() {
        "csv" => Ok(IngestionFormat::Csv),
        "json" | "ndjson" => Ok(IngestionFormat::Json),
        "parquet" => Ok(IngestionFormat::Parquet),
        "xml" => Ok(IngestionFormat::Xml),
        _ => Err(IngestionError::SchemaMismatch {
            message: format!(
                "cannot infer ingest format from remote path `{remote_path}`; set sources.options.format"
            ),
        }),
    }
}

struct TransferAuth {
    username: String,
    password: Option<String>,
    private_key_path: Option<PathBuf>,
}

fn env_password(scheme: &str) -> Option<String> {
    let key = match scheme {
        "sftp" => "SFTP_PASSWORD",
        "ftp" | "ftps" => "FTP_PASSWORD",
        _ => return None,
    };
    std::env::var(key).ok().filter(|s| !s.is_empty())
}

fn env_username(scheme: &str) -> Option<String> {
    let key = match scheme {
        "sftp" => "SFTP_USER",
        "ftp" | "ftps" => "FTP_USER",
        _ => return None,
    };
    std::env::var(key).ok().filter(|s| !s.is_empty())
}

fn resolve_auth(scheme: &str, url: &Url) -> IngestionResult<TransferAuth> {
    let username = if url.username().is_empty() {
        env_username(scheme).ok_or_else(|| IngestionError::SchemaMismatch {
            message: format!(
                "{scheme} URI must include a username or set {} env var",
                if scheme == "sftp" {
                    "SFTP_USER"
                } else {
                    "FTP_USER"
                }
            ),
        })?
    } else {
        url.username().to_string()
    };

    let password = env_password(scheme).or_else(|| url.password().map(|s| s.to_string()));

    let private_key_path = if scheme == "sftp" {
        std::env::var("SFTP_PRIVATE_KEY_PATH")
            .ok()
            .filter(|s| !s.is_empty())
            .map(PathBuf::from)
    } else {
        None
    };

    Ok(TransferAuth {
        username,
        password,
        private_key_path,
    })
}

fn parse_file_transfer_url(uri: &str) -> IngestionResult<(String, u16, String, TransferAuth)> {
    let url = Url::parse(uri).map_err(|e| IngestionError::SchemaMismatch {
        message: format!("invalid file-transfer URI `{uri}`: {e}"),
    })?;
    let scheme = url.scheme();
    if !matches!(scheme, "sftp" | "ftp" | "ftps") {
        return Err(IngestionError::SchemaMismatch {
            message: format!("unsupported file-transfer scheme `{scheme}` in `{uri}`"),
        });
    }

    let host = url
        .host_str()
        .ok_or_else(|| IngestionError::SchemaMismatch {
            message: format!("file-transfer URI missing host: `{uri}`"),
        })?
        .to_string();

    let port = url.port().unwrap_or(match scheme {
        "sftp" => 22,
        "ftp" => 21,
        "ftps" => 990,
        _ => 0,
    });

    let remote_path = url.path().to_string();
    if remote_path.is_empty() || remote_path == "/" {
        return Err(IngestionError::SchemaMismatch {
            message: format!("file-transfer URI must include a remote file path: `{uri}`"),
        });
    }

    let auth = resolve_auth(scheme, &url)?;
    Ok((host, port, remote_path, auth))
}

fn download_sftp(
    host: &str,
    port: u16,
    remote_path: &str,
    auth: &TransferAuth,
    local: &Path,
) -> IngestionResult<()> {
    let addr = format!("{host}:{port}");
    let tcp = TcpStream::connect(&addr).map_err(|e| IngestionError::Engine {
        message: format!("sftp connect `{addr}`"),
        source: Box::new(e),
    })?;

    let mut sess = Session::new().map_err(|e| IngestionError::Engine {
        message: "sftp session init".to_string(),
        source: Box::new(e),
    })?;
    sess.set_tcp_stream(tcp);
    sess.handshake().map_err(|e| IngestionError::Engine {
        message: format!("sftp handshake `{addr}`"),
        source: Box::new(e),
    })?;

    if let Some(key_path) = &auth.private_key_path {
        let passphrase = auth.password.as_deref();
        sess.userauth_pubkey_file(&auth.username, None, key_path, passphrase)
            .map_err(|e| IngestionError::Engine {
                message: format!("sftp pubkey auth user `{}`", auth.username),
                source: Box::new(e),
            })?;
    } else {
        let pass = auth.password.as_deref().unwrap_or("");
        sess.userauth_password(&auth.username, pass)
            .map_err(|e| IngestionError::Engine {
                message: format!("sftp password auth user `{}`", auth.username),
                source: Box::new(e),
            })?;
    }

    if !sess.authenticated() {
        return Err(IngestionError::SchemaMismatch {
            message: format!(
                "sftp authentication failed for user `{}` (set SFTP_PASSWORD or SFTP_PRIVATE_KEY_PATH)",
                auth.username
            ),
        });
    }

    let sftp = sess.sftp().map_err(|e| IngestionError::Engine {
        message: "sftp subsystem".to_string(),
        source: Box::new(e),
    })?;
    let mut remote = sftp
        .open(Path::new(remote_path))
        .map_err(|e| IngestionError::Engine {
            message: format!("sftp open `{remote_path}`"),
            source: Box::new(e),
        })?;

    let mut local_file = std::fs::File::create(local).map_err(IngestionError::Io)?;
    std::io::copy(&mut remote, &mut local_file).map_err(|e| IngestionError::Engine {
        message: format!("sftp read `{remote_path}`"),
        source: Box::new(e),
    })?;
    Ok(())
}

fn split_remote_dir_file(remote_path: &str) -> (String, String) {
    let p = remote_path.trim_start_matches('/');
    match p.rsplit_once('/') {
        Some(("", file)) => (String::new(), file.to_string()),
        Some((dir, file)) => (dir.to_string(), file.to_string()),
        None => (String::new(), p.to_string()),
    }
}

fn download_ftp(
    host: &str,
    port: u16,
    remote_path: &str,
    auth: &TransferAuth,
    local: &Path,
    use_tls: bool,
) -> IngestionResult<()> {
    let addr = format!("{host}:{port}");
    let password = auth.password.as_deref().unwrap_or("");

    let (dir, file_name) = split_remote_dir_file(remote_path);
    if file_name.is_empty() {
        return Err(IngestionError::SchemaMismatch {
            message: format!("ftp remote path must name a file: `{remote_path}`"),
        });
    }

    let bytes = if use_tls {
        download_ftp_tls(&addr, host, &auth.username, password, &dir, &file_name)?
    } else {
        download_ftp_plain(&addr, &auth.username, password, &dir, &file_name)?
    };

    let mut local_file = std::fs::File::create(local).map_err(IngestionError::Io)?;
    local_file.write_all(&bytes).map_err(IngestionError::Io)?;
    Ok(())
}

fn download_ftp_plain(
    addr: &str,
    user: &str,
    password: &str,
    dir: &str,
    file_name: &str,
) -> IngestionResult<Vec<u8>> {
    use suppaftp::FtpStream;
    use suppaftp::types::FileType;

    let mut ftp = FtpStream::connect(addr).map_err(|e| IngestionError::Engine {
        message: format!("ftp connect `{addr}`"),
        source: Box::new(e),
    })?;
    ftp.login(user, password)
        .map_err(|e| IngestionError::Engine {
            message: format!("ftp login user `{user}`"),
            source: Box::new(e),
        })?;
    if !dir.is_empty() {
        ftp.cwd(dir).map_err(|e| IngestionError::Engine {
            message: format!("ftp cwd `{dir}`"),
            source: Box::new(e),
        })?;
    }
    ftp.transfer_type(FileType::Binary)
        .map_err(|e| IngestionError::Engine {
            message: "ftp binary mode".to_string(),
            source: Box::new(e),
        })?;
    let data = ftp
        .retr_as_buffer(file_name)
        .map_err(|e| IngestionError::Engine {
            message: format!("ftp retr `{file_name}`"),
            source: Box::new(e),
        })?;
    let _ = ftp.quit();
    Ok(data.into_inner())
}

fn download_ftp_tls(
    addr: &str,
    host: &str,
    user: &str,
    password: &str,
    dir: &str,
    file_name: &str,
) -> IngestionResult<Vec<u8>> {
    use std::sync::Arc;

    use suppaftp::rustls::ClientConfig;
    use suppaftp::types::FileType;
    use suppaftp::{RustlsConnector, RustlsFtpStream};

    let mut root_store = suppaftp::rustls::RootCertStore::empty();
    root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

    let config = ClientConfig::builder()
        .with_root_certificates(root_store)
        .with_no_client_auth();

    let ftp_plain = RustlsFtpStream::connect(addr).map_err(|e| IngestionError::Engine {
        message: format!("ftps connect `{addr}`"),
        source: Box::new(e),
    })?;
    let mut ftp = ftp_plain
        .into_secure(RustlsConnector::from(Arc::new(config)), host)
        .map_err(|e| IngestionError::Engine {
            message: format!("ftps tls handshake `{host}`"),
            source: Box::new(e),
        })?;
    ftp.login(user, password)
        .map_err(|e| IngestionError::Engine {
            message: format!("ftps login user `{user}`"),
            source: Box::new(e),
        })?;
    if !dir.is_empty() {
        ftp.cwd(dir).map_err(|e| IngestionError::Engine {
            message: format!("ftps cwd `{dir}`"),
            source: Box::new(e),
        })?;
    }
    ftp.transfer_type(FileType::Binary)
        .map_err(|e| IngestionError::Engine {
            message: "ftps binary mode".to_string(),
            source: Box::new(e),
        })?;
    let data = ftp
        .retr_as_buffer(file_name)
        .map_err(|e| IngestionError::Engine {
            message: format!("ftps retr `{file_name}`"),
            source: Box::new(e),
        })?;
    let _ = ftp.quit();
    Ok(data.into_inner())
}

/// Download a remote file over SFTP or FTP/FTPS and ingest into a [`DataSet`].
pub fn ingest_from_file_transfer_uri(
    uri: &str,
    schema: &Schema,
    options: &IngestionOptions,
) -> IngestionResult<DataSet> {
    let scheme = file_transfer_scheme(uri).ok_or_else(|| IngestionError::SchemaMismatch {
        message: format!("not a file-transfer URI (expected sftp://, ftp://, or ftps://): `{uri}`"),
    })?;

    let (host, port, remote_path, auth) = parse_file_transfer_url(uri)?;
    let fmt = infer_format_from_remote_path(&remote_path, options)?;
    let suffix = match fmt {
        IngestionFormat::Csv => ".csv",
        IngestionFormat::Json => ".json",
        IngestionFormat::Parquet => ".parquet",
        IngestionFormat::Xml => ".xml",
        IngestionFormat::Excel => {
            return Err(IngestionError::SchemaMismatch {
                message: "excel ingest from file-transfer URIs is not supported".to_string(),
            });
        }
    };

    let local = temp_download_path(suffix)?;
    match scheme {
        "sftp" => download_sftp(&host, port, &remote_path, &auth, &local)?,
        "ftp" => download_ftp(&host, port, &remote_path, &auth, &local, false)?,
        "ftps" => download_ftp(&host, port, &remote_path, &auth, &local, true)?,
        _ => unreachable!(),
    }

    let mut opts = options.clone();
    opts.format = Some(fmt);
    let ds = ingest_from_path(&local, schema, &opts)?;
    let _ = std::fs::remove_file(&local);
    Ok(ds)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_file_transfer_uri_recognizes_schemes() {
        assert!(is_file_transfer_uri("sftp://u:p@host:22/a.parquet"));
        assert!(is_file_transfer_uri("ftp://u:p@host/rdp/in.csv"));
        assert!(is_file_transfer_uri("ftps://u:p@host:990/x.json"));
        assert!(!is_file_transfer_uri("s3://bucket/key"));
    }

    #[test]
    fn parse_url_uses_default_ports() {
        let (host, port, path, auth) = parse_file_transfer_url(
            "ftp://etl_user:secret@ftp.example.com/rdp/incoming/data.parquet",
        )
        .unwrap();
        assert_eq!(host, "ftp.example.com");
        assert_eq!(port, 21);
        assert_eq!(path, "/rdp/incoming/data.parquet");
        assert_eq!(auth.username, "etl_user");
        assert_eq!(auth.password.as_deref(), Some("secret"));
    }

    #[test]
    fn split_remote_dir_file_handles_nested_paths() {
        let (dir, file) = split_remote_dir_file("/rdp/incoming/data.parquet");
        assert_eq!(dir, "rdp/incoming");
        assert_eq!(file, "data.parquet");
    }
}
