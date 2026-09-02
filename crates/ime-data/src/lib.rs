//! Engine-neutral logical records used for local learning and encrypted sync payloads.

use std::cmp::Ordering;
use std::collections::HashMap;
use std::fmt;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering as AtomicOrdering};

use serde::{Deserialize, Serialize};

const STORE_SCHEMA_VERSION: u32 = 1;
static TEMP_SEQUENCE: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Debug, Deserialize, PartialEq, Eq, Hash, Serialize)]
pub struct RecordKey {
    pub namespace: String,
    pub language: String,
    pub input_signature: String,
    pub value: String,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq, Serialize)]
pub struct LogicalClock {
    pub counter: u64,
    pub device_id: String,
}

impl Ord for LogicalClock {
    fn cmp(&self, other: &Self) -> Ordering {
        self.counter
            .cmp(&other.counter)
            .then_with(|| self.device_id.cmp(&other.device_id))
    }
}

impl PartialOrd for LogicalClock {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq, Serialize)]
pub struct LearningRecord {
    pub key: RecordKey,
    pub positive_count: u64,
    pub negative_count: u64,
    pub clock: LogicalClock,
    pub deleted: bool,
}

impl LearningRecord {
    #[must_use]
    pub fn effective_weight(&self) -> i64 {
        if self.deleted {
            0
        } else {
            let positive = i64::try_from(self.positive_count).unwrap_or(i64::MAX);
            let negative = i64::try_from(self.negative_count).unwrap_or(i64::MAX);
            positive.saturating_sub(negative)
        }
    }
}

/// Deterministic last-writer-wins merge. Equal logical clocks converge by record content.
#[must_use]
pub fn merge_records(
    left: impl IntoIterator<Item = LearningRecord>,
    right: impl IntoIterator<Item = LearningRecord>,
) -> Vec<LearningRecord> {
    let mut records = HashMap::<RecordKey, LearningRecord>::new();
    for incoming in left.into_iter().chain(right) {
        records
            .entry(incoming.key.clone())
            .and_modify(|current| {
                let replace = incoming.clock > current.clock
                    || (incoming.clock == current.clock
                        && tie_breaker(&incoming) > tie_breaker(current));
                if replace {
                    *current = incoming.clone();
                }
            })
            .or_insert(incoming);
    }
    let mut output = records.into_values().collect::<Vec<_>>();
    output.sort_by(|left, right| {
        left.key
            .namespace
            .cmp(&right.key.namespace)
            .then_with(|| left.key.language.cmp(&right.key.language))
            .then_with(|| left.key.input_signature.cmp(&right.key.input_signature))
            .then_with(|| left.key.value.cmp(&right.key.value))
    });
    output
}

#[derive(Debug)]
pub struct LearningModel {
    namespace: String,
    language: String,
    device_id: String,
    clock: u64,
    records: HashMap<RecordKey, LearningRecord>,
}

impl LearningModel {
    #[must_use]
    pub fn new(
        namespace: impl Into<String>,
        language: impl Into<String>,
        device_id: impl Into<String>,
    ) -> Self {
        Self {
            namespace: namespace.into(),
            language: language.into(),
            device_id: device_id.into(),
            clock: 0,
            records: HashMap::new(),
        }
    }

    #[must_use]
    pub fn from_records(
        namespace: impl Into<String>,
        language: impl Into<String>,
        device_id: impl Into<String>,
        records: Vec<LearningRecord>,
    ) -> Self {
        let namespace = namespace.into();
        let language = language.into();
        let merged = merge_records(records, []);
        let clock = merged
            .iter()
            .map(|record| record.clock.counter)
            .max()
            .unwrap_or(0);
        let records = merged
            .into_iter()
            .filter(|record| record.key.namespace == namespace && record.key.language == language)
            .map(|record| (record.key.clone(), record))
            .collect();
        Self {
            namespace,
            language,
            device_id: device_id.into(),
            clock,
            records,
        }
    }

    #[must_use]
    pub fn score(&self, input_signature: &str, value: &str) -> i64 {
        let key = self.key(input_signature, value);
        self.records
            .get(&key)
            .map_or(0, LearningRecord::effective_weight)
    }

    pub fn selected(&mut self, input_signature: &str, value: &str) {
        self.update(input_signature, value, RecordUpdate::Positive);
    }

    pub fn rejected(&mut self, input_signature: &str, value: &str) {
        self.update(input_signature, value, RecordUpdate::Negative);
    }

    pub fn delete(&mut self, input_signature: &str, value: &str) {
        self.update(input_signature, value, RecordUpdate::Delete);
    }

    #[must_use]
    pub fn records(&self) -> Vec<LearningRecord> {
        let mut records = self.records.values().cloned().collect::<Vec<_>>();
        records.sort_by(|left, right| {
            left.key
                .input_signature
                .cmp(&right.key.input_signature)
                .then_with(|| left.key.value.cmp(&right.key.value))
        });
        records
    }

    fn key(&self, input_signature: &str, value: &str) -> RecordKey {
        RecordKey {
            namespace: self.namespace.clone(),
            language: self.language.clone(),
            input_signature: input_signature.to_owned(),
            value: value.to_owned(),
        }
    }

    fn update(&mut self, input_signature: &str, value: &str, update: RecordUpdate) {
        self.clock = self.clock.saturating_add(1);
        let key = self.key(input_signature, value);
        let record = self
            .records
            .entry(key.clone())
            .or_insert_with(|| LearningRecord {
                key,
                positive_count: 0,
                negative_count: 0,
                clock: LogicalClock {
                    counter: 0,
                    device_id: self.device_id.clone(),
                },
                deleted: false,
            });
        match update {
            RecordUpdate::Positive => {
                record.positive_count = record.positive_count.saturating_add(1);
                record.deleted = false;
            }
            RecordUpdate::Negative => {
                record.negative_count = record.negative_count.saturating_add(1);
            }
            RecordUpdate::Delete => record.deleted = true,
        }
        record.clock = LogicalClock {
            counter: self.clock,
            device_id: self.device_id.clone(),
        };
    }
}

#[derive(Clone, Copy)]
enum RecordUpdate {
    Positive,
    Negative,
    Delete,
}

fn tie_breaker(record: &LearningRecord) -> (bool, u64, u64) {
    (record.deleted, record.positive_count, record.negative_count)
}

#[derive(Debug)]
pub enum StoreError {
    Io(std::io::Error),
    InvalidData(serde_json::Error),
    Database(rusqlite::Error),
    UnsupportedVersion(u32),
    CounterOverflow,
}

impl fmt::Display for StoreError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "learning store I/O error: {error}"),
            Self::InvalidData(error) => write!(formatter, "invalid learning store: {error}"),
            Self::Database(error) => write!(formatter, "learning database error: {error}"),
            Self::UnsupportedVersion(version) => {
                write!(formatter, "unsupported learning store version: {version}")
            }
            Self::CounterOverflow => formatter.write_str("learning counter exceeds SQLite range"),
        }
    }
}

impl std::error::Error for StoreError {}

impl From<std::io::Error> for StoreError {
    fn from(value: std::io::Error) -> Self {
        Self::Io(value)
    }
}

impl From<serde_json::Error> for StoreError {
    fn from(value: serde_json::Error) -> Self {
        Self::InvalidData(value)
    }
}

impl From<rusqlite::Error> for StoreError {
    fn from(value: rusqlite::Error) -> Self {
        Self::Database(value)
    }
}

pub trait LearningStore {
    fn load(&self) -> Result<Vec<LearningRecord>, StoreError>;
    fn save(&self, records: &[LearningRecord]) -> Result<(), StoreError>;
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct StoreEnvelope {
    schema_version: u32,
    records: Vec<LearningRecord>,
}

/// A versioned JSON store intended as the portable logical-data layer.
/// Engine-specific high-performance indexes are rebuilt from these records.
pub struct JsonFileStore {
    path: PathBuf,
}

impl JsonFileStore {
    #[must_use]
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    pub fn load(&self) -> Result<Vec<LearningRecord>, StoreError> {
        if !self.path.exists() {
            return Ok(Vec::new());
        }
        let mut bytes = Vec::new();
        File::open(&self.path)?.read_to_end(&mut bytes)?;
        let envelope: StoreEnvelope = serde_json::from_slice(&bytes)?;
        if envelope.schema_version != STORE_SCHEMA_VERSION {
            return Err(StoreError::UnsupportedVersion(envelope.schema_version));
        }
        Ok(envelope.records)
    }

    /// Writes to a temporary file in the destination directory and atomically renames it.
    pub fn save(&self, records: &[LearningRecord]) -> Result<(), StoreError> {
        let parent = self.path.parent().unwrap_or_else(|| Path::new("."));
        fs::create_dir_all(parent)?;
        let sequence = TEMP_SEQUENCE.fetch_add(1, AtomicOrdering::Relaxed);
        let file_name = self
            .path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("learning-store");
        let temporary = parent.join(format!(
            ".{file_name}.{}.{}.tmp",
            std::process::id(),
            sequence
        ));
        let result = (|| {
            let envelope = StoreEnvelope {
                schema_version: STORE_SCHEMA_VERSION,
                records: records.to_vec(),
            };
            let bytes = serde_json::to_vec_pretty(&envelope)?;
            let mut file = File::create(&temporary)?;
            file.write_all(&bytes)?;
            file.sync_all()?;
            fs::rename(&temporary, &self.path)?;
            sync_directory(parent)?;
            Ok(())
        })();
        if result.is_err() {
            let _ = fs::remove_file(&temporary);
        }
        result
    }
}

impl LearningStore for JsonFileStore {
    fn load(&self) -> Result<Vec<LearningRecord>, StoreError> {
        Self::load(self)
    }

    fn save(&self, records: &[LearningRecord]) -> Result<(), StoreError> {
        Self::save(self, records)
    }
}

/// Transactional production store. The logical schema stays independent of
/// engine-private databases so records remain exportable and mergeable.
pub struct SqliteStore {
    path: PathBuf,
}

impl SqliteStore {
    #[must_use]
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    fn connection(&self) -> Result<rusqlite::Connection, StoreError> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }
        let connection = rusqlite::Connection::open(&self.path)?;
        connection.busy_timeout(std::time::Duration::from_secs(5))?;
        let version: u32 = connection.query_row("PRAGMA user_version", [], |row| row.get(0))?;
        if version > STORE_SCHEMA_VERSION {
            return Err(StoreError::UnsupportedVersion(version));
        }
        connection.execute_batch(
            "PRAGMA journal_mode=WAL;
             PRAGMA synchronous=FULL;
             CREATE TABLE IF NOT EXISTS learning_records (
               namespace TEXT NOT NULL,
               language TEXT NOT NULL,
               input_signature TEXT NOT NULL,
               value TEXT NOT NULL,
               positive_count INTEGER NOT NULL CHECK (positive_count >= 0),
               negative_count INTEGER NOT NULL CHECK (negative_count >= 0),
               clock_counter INTEGER NOT NULL CHECK (clock_counter >= 0),
               device_id TEXT NOT NULL,
               deleted INTEGER NOT NULL CHECK (deleted IN (0, 1)),
               PRIMARY KEY(namespace, language, input_signature, value)
             );
             CREATE TABLE IF NOT EXISTS metadata (
               key TEXT PRIMARY KEY,
               value TEXT NOT NULL
             );",
        )?;
        if version == 0 {
            connection.pragma_update(None, "user_version", STORE_SCHEMA_VERSION)?;
        }
        Ok(connection)
    }

    pub fn load(&self) -> Result<Vec<LearningRecord>, StoreError> {
        let connection = self.connection()?;
        query_records(&connection)
    }

    pub fn save(&self, records: &[LearningRecord]) -> Result<(), StoreError> {
        let mut connection = self.connection()?;
        let transaction =
            connection.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        replace_records(&transaction, records)?;
        transaction.commit()?;
        Ok(())
    }

    /// Atomically merges a writer snapshot with data committed by other
    /// platform processes, preserving the newest logical clock and tombstones.
    pub fn merge_save(&self, incoming: &[LearningRecord]) -> Result<(), StoreError> {
        let mut connection = self.connection()?;
        let transaction =
            connection.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let merged = merge_records(query_records(&transaction)?, incoming.iter().cloned());
        replace_records(&transaction, &merged)?;
        transaction.commit()?;
        Ok(())
    }

    pub fn device_id(&self) -> Result<String, StoreError> {
        let connection = self.connection()?;
        if let Ok(value) = connection.query_row(
            "SELECT value FROM metadata WHERE key='device_id'",
            [],
            |row| row.get(0),
        ) {
            return Ok(value);
        }
        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let value = format!("device-{timestamp:x}-{:x}", std::process::id());
        connection.execute(
            "INSERT OR IGNORE INTO metadata(key, value) VALUES ('device_id', ?1)",
            [&value],
        )?;
        connection
            .query_row(
                "SELECT value FROM metadata WHERE key='device_id'",
                [],
                |row| row.get(0),
            )
            .map_err(Into::into)
    }
}

impl LearningStore for SqliteStore {
    fn load(&self) -> Result<Vec<LearningRecord>, StoreError> {
        Self::load(self)
    }

    fn save(&self, records: &[LearningRecord]) -> Result<(), StoreError> {
        Self::save(self, records)
    }
}

pub fn migrate_json_to_sqlite(
    source: &JsonFileStore,
    destination: &SqliteStore,
) -> Result<usize, StoreError> {
    let records = source.load()?;
    destination.save(&records)?;
    Ok(records.len())
}

fn sqlite_counter(value: u64) -> Result<i64, StoreError> {
    i64::try_from(value).map_err(|_| StoreError::CounterOverflow)
}

fn row_counter(row: &rusqlite::Row<'_>, index: usize) -> rusqlite::Result<u64> {
    let value: i64 = row.get(index)?;
    u64::try_from(value).map_err(|_| rusqlite::Error::IntegralValueOutOfRange(index, value))
}

fn query_records(connection: &rusqlite::Connection) -> Result<Vec<LearningRecord>, StoreError> {
    let mut statement = connection.prepare(
        "SELECT namespace, language, input_signature, value,
                positive_count, negative_count, clock_counter, device_id, deleted
         FROM learning_records
         ORDER BY namespace, language, input_signature, value",
    )?;
    let rows = statement.query_map([], |row| {
        Ok(LearningRecord {
            key: RecordKey {
                namespace: row.get(0)?,
                language: row.get(1)?,
                input_signature: row.get(2)?,
                value: row.get(3)?,
            },
            positive_count: row_counter(row, 4)?,
            negative_count: row_counter(row, 5)?,
            clock: LogicalClock {
                counter: row_counter(row, 6)?,
                device_id: row.get(7)?,
            },
            deleted: row.get(8)?,
        })
    })?;
    rows.collect::<Result<Vec<_>, _>>().map_err(Into::into)
}

fn replace_records(
    transaction: &rusqlite::Transaction<'_>,
    records: &[LearningRecord],
) -> Result<(), StoreError> {
    transaction.execute("DELETE FROM learning_records", [])?;
    let mut statement = transaction.prepare(
        "INSERT INTO learning_records (
           namespace, language, input_signature, value,
           positive_count, negative_count, clock_counter, device_id, deleted
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
    )?;
    for record in records {
        statement.execute(rusqlite::params![
            record.key.namespace,
            record.key.language,
            record.key.input_signature,
            record.key.value,
            sqlite_counter(record.positive_count)?,
            sqlite_counter(record.negative_count)?,
            sqlite_counter(record.clock.counter)?,
            record.clock.device_id,
            record.deleted,
        ])?;
    }
    Ok(())
}

#[cfg(unix)]
fn sync_directory(path: &Path) -> Result<(), std::io::Error> {
    File::open(path)?.sync_all()
}

#[cfg(not(unix))]
fn sync_directory(_: &Path) -> Result<(), std::io::Error> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn record(device: &str, counter: u64, deleted: bool) -> LearningRecord {
        LearningRecord {
            key: RecordKey {
                namespace: "user-lexicon".to_owned(),
                language: "zh-CN".to_owned(),
                input_signature: "nihao".to_owned(),
                value: "你好".to_owned(),
            },
            positive_count: 3,
            negative_count: 0,
            clock: LogicalClock {
                counter,
                device_id: device.to_owned(),
            },
            deleted,
        }
    }

    #[test]
    fn newer_tombstone_prevents_deleted_word_from_returning() {
        let merged = merge_records([record("desktop", 1, false)], [record("phone", 2, true)]);
        assert_eq!(merged.len(), 1);
        assert!(merged[0].deleted);
        assert_eq!(merged[0].effective_weight(), 0);
    }

    #[test]
    fn merge_is_commutative() {
        let left = record("a", 2, false);
        let right = record("b", 2, true);
        assert_eq!(
            merge_records([left.clone()], [right.clone()]),
            merge_records([right], [left])
        );
    }

    #[test]
    fn file_store_round_trips_unicode_and_tombstones() {
        let path =
            std::env::temp_dir().join(format!("shurufa-store-test-{}.json", std::process::id()));
        let store = JsonFileStore::new(&path);
        let expected = vec![record("phone", 7, true)];
        store.save(&expected).expect("save");
        assert_eq!(store.load().expect("load"), expected);
        fs::remove_file(path).expect("cleanup");
    }

    #[test]
    fn learning_model_updates_scores_and_can_be_restored() {
        let mut model = LearningModel::new("learned-lexicon", "zh-CN", "desktop");
        model.selected("ni", "泥");
        model.selected("ni", "泥");
        model.rejected("ni", "你");
        assert_eq!(model.score("ni", "泥"), 2);
        assert_eq!(model.score("ni", "你"), -1);
        let restored =
            LearningModel::from_records("learned-lexicon", "zh-CN", "phone", model.records());
        assert_eq!(restored.score("ni", "泥"), 2);
    }

    #[test]
    fn selecting_a_deleted_entry_explicitly_restores_it() {
        let mut model = LearningModel::new("learned-lexicon", "en", "desktop");
        model.selected("he", "hello");
        model.delete("he", "hello");
        assert_eq!(model.score("he", "hello"), 0);
        model.selected("he", "hello");
        assert_eq!(model.score("he", "hello"), 2);
    }

    #[test]
    fn sqlite_store_is_transactional_and_keeps_a_stable_device_id() {
        let path =
            std::env::temp_dir().join(format!("shurufa-store-test-{}.sqlite3", std::process::id()));
        let store = SqliteStore::new(&path);
        let first_id = store.device_id().expect("first device ID");
        store.save(&[record("desktop", 4, false)]).expect("save");
        assert_eq!(
            store.load().expect("load"),
            vec![record("desktop", 4, false)]
        );
        assert_eq!(store.device_id().expect("stable device ID"), first_id);
        fs::remove_file(&path).expect("cleanup database");
        let _ = fs::remove_file(path.with_extension("sqlite3-shm"));
        let _ = fs::remove_file(path.with_extension("sqlite3-wal"));
    }

    #[test]
    fn migrates_portable_json_into_sqlite() {
        let base = std::env::temp_dir().join(format!("shurufa-migrate-{}", std::process::id()));
        let json_path = base.with_extension("json");
        let sqlite_path = base.with_extension("sqlite3");
        let source = JsonFileStore::new(&json_path);
        let destination = SqliteStore::new(&sqlite_path);
        source.save(&[record("phone", 9, true)]).expect("JSON");
        assert_eq!(
            migrate_json_to_sqlite(&source, &destination).expect("migrate"),
            1
        );
        assert_eq!(
            destination.load().expect("SQLite"),
            vec![record("phone", 9, true)]
        );
        fs::remove_file(json_path).expect("cleanup JSON");
        fs::remove_file(sqlite_path).expect("cleanup SQLite");
    }

    #[test]
    fn sqlite_merge_save_preserves_a_newer_tombstone() {
        let path =
            std::env::temp_dir().join(format!("shurufa-merge-test-{}.sqlite3", std::process::id()));
        let store = SqliteStore::new(&path);
        store
            .save(&[record("desktop", 2, true)])
            .expect("seed tombstone");
        store
            .merge_save(&[record("phone", 1, false)])
            .expect("merge stale writer");
        assert!(store.load().expect("load")[0].deleted);
        fs::remove_file(path).expect("cleanup");
    }
}
