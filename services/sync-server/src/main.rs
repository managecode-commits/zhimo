use std::path::Path;
use std::sync::{Arc, Mutex};

use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, Path as AxumPath, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::{delete, get, put};
use axum::{Json, Router};
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

const MAX_ENVELOPE_BYTES: usize = 10 * 1024 * 1024;

#[derive(Clone)]
struct AppState {
    database: Arc<Mutex<Connection>>,
    account: Arc<str>,
    token_hash: [u8; 32],
}

#[derive(Deserialize)]
struct PullQuery {
    #[serde(default)]
    after: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StoredEnvelope {
    device_id: String,
    cursor: u64,
    device_sequence: u64,
    payload: Vec<u8>,
}

#[tokio::main]
async fn main() {
    if let Err(error) = run().await {
        eprintln!("sync server failed: {error}");
        std::process::exit(1);
    }
}

async fn run() -> Result<(), String> {
    let bind = std::env::var("SHURUFA_SYNC_BIND").unwrap_or_else(|_| "127.0.0.1:8787".to_owned());
    let database = std::env::var("SHURUFA_SYNC_DATABASE")
        .unwrap_or_else(|_| "shurufa-sync.sqlite3".to_owned());
    let account = std::env::var("SHURUFA_SYNC_ACCOUNT")
        .map_err(|_| "SHURUFA_SYNC_ACCOUNT is required".to_owned())?;
    let token = std::env::var("SHURUFA_SYNC_TOKEN")
        .map_err(|_| "SHURUFA_SYNC_TOKEN is required".to_owned())?;
    if token.len() < 32 {
        return Err("SHURUFA_SYNC_TOKEN must contain at least 32 characters".to_owned());
    }
    let connection = open_database(Path::new(&database)).map_err(|error| error.to_string())?;
    let state = AppState {
        database: Arc::new(Mutex::new(connection)),
        account: Arc::from(account),
        token_hash: Sha256::digest(token.as_bytes()).into(),
    };
    let application = Router::new()
        .route("/health", get(|| async { StatusCode::NO_CONTENT }))
        .route("/v1/sync/{account}/{device}/{sequence}", put(push_envelope))
        .route("/v1/sync/{account}", get(pull_envelopes))
        .route("/v1/sync/{account}/{device}", delete(revoke_device))
        .layer(DefaultBodyLimit::max(MAX_ENVELOPE_BYTES))
        .with_state(state);
    let listener = tokio::net::TcpListener::bind(&bind)
        .await
        .map_err(|error| error.to_string())?;
    axum::serve(listener, application)
        .with_graceful_shutdown(shutdown())
        .await
        .map_err(|error| error.to_string())
}

async fn shutdown() {
    let _ = tokio::signal::ctrl_c().await;
}

async fn push_envelope(
    State(state): State<AppState>,
    AxumPath((account, device, sequence)): AxumPath<(String, String, u64)>,
    headers: HeaderMap,
    body: Bytes,
) -> StatusCode {
    if !authorized(&state, &account, &headers) {
        return StatusCode::UNAUTHORIZED;
    }
    if body.is_empty() || body.len() > MAX_ENVELOPE_BYTES || device.is_empty() {
        return StatusCode::BAD_REQUEST;
    }
    let Ok(sequence) = i64::try_from(sequence) else {
        return StatusCode::BAD_REQUEST;
    };
    let Ok(database) = state.database.lock() else {
        return StatusCode::INTERNAL_SERVER_ERROR;
    };
    let revoked = database
        .query_row(
            "SELECT revoked FROM devices WHERE account_id=?1 AND device_id=?2",
            params![account, device],
            |row| row.get::<_, bool>(0),
        )
        .unwrap_or(false);
    if revoked {
        return StatusCode::FORBIDDEN;
    }
    if database
        .execute(
            "INSERT OR IGNORE INTO devices(account_id, device_id, revoked) VALUES (?1, ?2, 0)",
            params![account, device],
        )
        .and_then(|_| {
            database.execute(
                "INSERT INTO envelopes(account_id, device_id, device_sequence, payload)
                 VALUES (?1, ?2, ?3, ?4)
                 ON CONFLICT(account_id, device_id, device_sequence) DO UPDATE SET payload=excluded.payload",
                params![account, device, sequence, body.as_ref()],
            )
        })
        .is_err()
    {
        StatusCode::INTERNAL_SERVER_ERROR
    } else {
        StatusCode::NO_CONTENT
    }
}

async fn pull_envelopes(
    State(state): State<AppState>,
    AxumPath(account): AxumPath<String>,
    Query(query): Query<PullQuery>,
    headers: HeaderMap,
) -> Result<Json<Vec<StoredEnvelope>>, StatusCode> {
    if !authorized(&state, &account, &headers) {
        return Err(StatusCode::UNAUTHORIZED);
    }
    let database = state
        .database
        .lock()
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let after = i64::try_from(query.after).map_err(|_| StatusCode::BAD_REQUEST)?;
    let mut statement = database
        .prepare(
            "SELECT e.device_id, e.cursor, e.device_sequence, e.payload
             FROM envelopes e JOIN devices d
             ON e.account_id=d.account_id AND e.device_id=d.device_id
             WHERE e.account_id=?1 AND e.cursor>?2 AND d.revoked=0
             ORDER BY e.cursor LIMIT 1000",
        )
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let rows = statement
        .query_map(params![account, after], |row| {
            let cursor: i64 = row.get(1)?;
            let device_sequence: i64 = row.get(2)?;
            Ok(StoredEnvelope {
                device_id: row.get(0)?,
                cursor: u64::try_from(cursor)
                    .map_err(|_| rusqlite::Error::IntegralValueOutOfRange(1, cursor))?,
                device_sequence: u64::try_from(device_sequence)
                    .map_err(|_| rusqlite::Error::IntegralValueOutOfRange(2, device_sequence))?,
                payload: row.get(3)?,
            })
        })
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    rows.collect::<Result<Vec<_>, _>>()
        .map(Json)
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)
}

async fn revoke_device(
    State(state): State<AppState>,
    AxumPath((account, device)): AxumPath<(String, String)>,
    headers: HeaderMap,
) -> StatusCode {
    if !authorized(&state, &account, &headers) {
        return StatusCode::UNAUTHORIZED;
    }
    let Ok(database) = state.database.lock() else {
        return StatusCode::INTERNAL_SERVER_ERROR;
    };
    match database.execute(
        "INSERT INTO devices(account_id, device_id, revoked) VALUES (?1, ?2, 1)
         ON CONFLICT(account_id, device_id) DO UPDATE SET revoked=1",
        params![account, device],
    ) {
        Ok(_) => StatusCode::NO_CONTENT,
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR,
    }
}

fn authorized(state: &AppState, account: &str, headers: &HeaderMap) -> bool {
    if account != state.account.as_ref() {
        return false;
    }
    let Some(token) = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
    else {
        return false;
    };
    let actual: [u8; 32] = Sha256::digest(token.as_bytes()).into();
    bool::from(actual.ct_eq(&state.token_hash))
}

fn open_database(path: &Path) -> rusqlite::Result<Connection> {
    let database = Connection::open(path)?;
    database.execute_batch(
        "PRAGMA journal_mode=WAL;
         PRAGMA synchronous=FULL;
         CREATE TABLE IF NOT EXISTS devices (
           account_id TEXT NOT NULL,
           device_id TEXT NOT NULL,
           revoked INTEGER NOT NULL CHECK(revoked IN (0, 1)),
           PRIMARY KEY(account_id, device_id)
         );
         CREATE TABLE IF NOT EXISTS envelopes (
           cursor INTEGER PRIMARY KEY AUTOINCREMENT,
           account_id TEXT NOT NULL,
           device_id TEXT NOT NULL,
           device_sequence INTEGER NOT NULL CHECK(device_sequence >= 0),
           payload BLOB NOT NULL,
           UNIQUE(account_id, device_id, device_sequence),
           FOREIGN KEY(account_id, device_id) REFERENCES devices(account_id, device_id)
         );",
    )?;
    Ok(database)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn authentication_is_account_scoped_and_constant_time_compared() {
        let state = AppState {
            database: Arc::new(Mutex::new(Connection::open_in_memory().expect("database"))),
            account: Arc::from("alice"),
            token_hash: Sha256::digest(b"correct token with enough entropy").into(),
        };
        let mut headers = HeaderMap::new();
        headers.insert(
            axum::http::header::AUTHORIZATION,
            "Bearer correct token with enough entropy"
                .parse()
                .expect("header"),
        );
        assert!(authorized(&state, "alice", &headers));
        assert!(!authorized(&state, "bob", &headers));
    }

    #[test]
    fn database_stores_only_opaque_payload_bytes() {
        let database = open_database(Path::new(":memory:")).expect("database");
        database
            .execute("INSERT INTO devices VALUES ('alice', 'phone', 0)", [])
            .expect("device");
        database
            .execute(
                "INSERT INTO envelopes(account_id, device_id, device_sequence, payload)
                 VALUES ('alice', 'phone', 1, ?1)",
                [b"ciphertext".as_slice()],
            )
            .expect("envelope");
        let payload: Vec<u8> = database
            .query_row("SELECT payload FROM envelopes", [], |row| row.get(0))
            .expect("payload");
        assert_eq!(payload, b"ciphertext");
    }

    #[test]
    fn cursor_is_global_when_devices_reuse_the_same_sequence() {
        let database = open_database(Path::new(":memory:")).expect("database");
        database
            .execute("INSERT INTO devices VALUES ('alice', 'phone', 0)", [])
            .expect("phone");
        database
            .execute("INSERT INTO devices VALUES ('alice', 'laptop', 0)", [])
            .expect("laptop");
        for device in ["phone", "laptop"] {
            database
                .execute(
                    "INSERT INTO envelopes(account_id, device_id, device_sequence, payload)
                     VALUES ('alice', ?1, 1, X'01')",
                    [device],
                )
                .expect("envelope");
        }
        let mut statement = database
            .prepare("SELECT cursor, device_sequence FROM envelopes ORDER BY cursor")
            .expect("query");
        let values = statement
            .query_map([], |row| Ok((row.get::<_, i64>(0)?, row.get::<_, i64>(1)?)))
            .expect("rows")
            .collect::<Result<Vec<_>, _>>()
            .expect("values");
        assert_eq!(values, vec![(1, 1), (2, 1)]);
    }
}
