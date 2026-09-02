use std::path::Path;

use ime_data::{LearningModel, SqliteStore};
use ime_security::{decrypt_records, encrypt_records, EncryptedSyncEnvelope};

fn main() {
    if let Err(error) = run() {
        eprintln!("data operation failed: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let arguments = std::env::args().skip(1).collect::<Vec<_>>();
    match arguments.as_slice() {
        [command, database] if command == "list" => list(Path::new(database)),
        [command, database, key, output] if command == "export-encrypted" => {
            export(Path::new(database), key, Path::new(output))
        }
        [command, database, key, input] if command == "import-encrypted" => {
            import(Path::new(database), key, Path::new(input))
        }
        [command, database, namespace, language, signature, value] if command == "delete" => {
            delete(
                Path::new(database),
                namespace,
                language,
                signature,
                value,
            )
        }
        _ => Err("usage: data-cli list <db> | export-encrypted <db> <64-hex-key> <file> | import-encrypted <db> <64-hex-key> <file> | delete <db> <namespace> <language> <signature> <value>".to_owned()),
    }
}

fn list(database: &Path) -> Result<(), String> {
    for record in SqliteStore::new(database)
        .load()
        .map_err(|error| error.to_string())?
    {
        println!(
            "{}\t{}\t{}\t{}\t{}\t{}",
            record.key.namespace,
            record.key.language,
            record.key.input_signature,
            record.key.value,
            record.effective_weight(),
            if record.deleted { "deleted" } else { "active" }
        );
    }
    Ok(())
}

fn export(database: &Path, key: &str, output: &Path) -> Result<(), String> {
    let store = SqliteStore::new(database);
    let envelope = encrypt_records(
        &parse_key(key)?,
        &store.load().map_err(|error| error.to_string())?,
    )
    .map_err(|error| error.to_string())?;
    let bytes = serde_json::to_vec_pretty(&envelope).map_err(|error| error.to_string())?;
    std::fs::write(output, bytes).map_err(|error| error.to_string())
}

fn import(database: &Path, key: &str, input: &Path) -> Result<(), String> {
    let bytes = std::fs::read(input).map_err(|error| error.to_string())?;
    let envelope: EncryptedSyncEnvelope =
        serde_json::from_slice(&bytes).map_err(|error| error.to_string())?;
    let records =
        decrypt_records(&parse_key(key)?, &envelope).map_err(|error| error.to_string())?;
    SqliteStore::new(database)
        .merge_save(&records)
        .map_err(|error| error.to_string())
}

fn delete(
    database: &Path,
    namespace: &str,
    language: &str,
    signature: &str,
    value: &str,
) -> Result<(), String> {
    let store = SqliteStore::new(database);
    let device = store.device_id().map_err(|error| error.to_string())?;
    let mut model = LearningModel::from_records(
        namespace,
        language,
        device,
        store.load().map_err(|error| error.to_string())?,
    );
    model.delete(signature, value);
    store
        .merge_save(&model.records())
        .map_err(|error| error.to_string())
}

fn parse_key(value: &str) -> Result<[u8; 32], String> {
    if value.len() != 64 {
        return Err("key must contain exactly 64 hexadecimal characters".to_owned());
    }
    let mut key = [0_u8; 32];
    for (index, byte) in key.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&value[index * 2..index * 2 + 2], 16)
            .map_err(|_| "key contains non-hexadecimal characters".to_owned())?;
    }
    Ok(key)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_256_bit_key() {
        assert_eq!(parse_key(&"07".repeat(32)).expect("key"), [7_u8; 32]);
        assert!(parse_key("bad").is_err());
    }
}
