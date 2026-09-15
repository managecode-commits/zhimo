// Copyright © 2026 立方田 <managecode@gmail.com>
//! Cryptographic boundaries for opaque sync payloads and signed packages.
//!
//! Key generation, backup and OS key-store integration remain platform duties;
//! this crate accepts explicit keys and never persists them.

use std::fmt;

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use ime_data::LearningRecord;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

const SYNC_VERSION: u32 = 1;
const SYNC_AAD: &[u8] = b"zhimo-sync-envelope-v1";

#[derive(Debug)]
pub enum SecurityError {
    Random(getrandom::Error),
    Serialization(serde_json::Error),
    InvalidEnvelope,
    Authentication,
    InvalidSignature,
}

impl fmt::Display for SecurityError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Random(error) => write!(formatter, "secure random source failed: {error}"),
            Self::Serialization(error) => write!(formatter, "security payload is invalid: {error}"),
            Self::InvalidEnvelope => formatter.write_str("unsupported or malformed envelope"),
            Self::Authentication => formatter.write_str("encrypted payload authentication failed"),
            Self::InvalidSignature => formatter.write_str("artifact signature is invalid"),
        }
    }
}

impl std::error::Error for SecurityError {}

impl From<serde_json::Error> for SecurityError {
    fn from(value: serde_json::Error) -> Self {
        Self::Serialization(value)
    }
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EncryptedSyncEnvelope {
    pub schema_version: u32,
    pub nonce: Vec<u8>,
    pub ciphertext: Vec<u8>,
}

pub fn encrypt_records(
    key: &[u8; 32],
    records: &[LearningRecord],
) -> Result<EncryptedSyncEnvelope, SecurityError> {
    let plaintext = serde_json::to_vec(records)?;
    let mut nonce = [0_u8; 24];
    getrandom::fill(&mut nonce).map_err(SecurityError::Random)?;
    let cipher = XChaCha20Poly1305::new(key.into());
    let nonce_value: XNonce = nonce.into();
    let ciphertext = cipher
        .encrypt(
            &nonce_value,
            Payload {
                msg: &plaintext,
                aad: SYNC_AAD,
            },
        )
        .map_err(|_| SecurityError::Authentication)?;
    Ok(EncryptedSyncEnvelope {
        schema_version: SYNC_VERSION,
        nonce: nonce.to_vec(),
        ciphertext,
    })
}

pub fn decrypt_records(
    key: &[u8; 32],
    envelope: &EncryptedSyncEnvelope,
) -> Result<Vec<LearningRecord>, SecurityError> {
    if envelope.schema_version != SYNC_VERSION || envelope.nonce.len() != 24 {
        return Err(SecurityError::InvalidEnvelope);
    }
    let cipher = XChaCha20Poly1305::new(key.into());
    let nonce =
        XNonce::try_from(envelope.nonce.as_slice()).map_err(|_| SecurityError::InvalidEnvelope)?;
    let plaintext = cipher
        .decrypt(
            &nonce,
            Payload {
                msg: &envelope.ciphertext,
                aad: SYNC_AAD,
            },
        )
        .map_err(|_| SecurityError::Authentication)?;
    serde_json::from_slice(&plaintext).map_err(Into::into)
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtifactSignature {
    pub schema_version: u32,
    pub sha256: Vec<u8>,
    pub signature: Vec<u8>,
}

#[must_use]
pub fn sign_artifact(signing_key: &SigningKey, artifact: &[u8]) -> ArtifactSignature {
    let digest = Sha256::digest(artifact);
    ArtifactSignature {
        schema_version: 1,
        sha256: digest.to_vec(),
        signature: signing_key.sign(&digest).to_bytes().to_vec(),
    }
}

pub fn verify_artifact(
    verifying_key: &VerifyingKey,
    artifact: &[u8],
    signed: &ArtifactSignature,
) -> Result<(), SecurityError> {
    if signed.schema_version != 1 || signed.sha256.len() != 32 || signed.signature.len() != 64 {
        return Err(SecurityError::InvalidSignature);
    }
    let actual = Sha256::digest(artifact);
    if actual.as_slice() != signed.sha256 {
        return Err(SecurityError::InvalidSignature);
    }
    let signature_bytes: [u8; 64] = signed
        .signature
        .as_slice()
        .try_into()
        .map_err(|_| SecurityError::InvalidSignature)?;
    verifying_key
        .verify(&actual, &Signature::from_bytes(&signature_bytes))
        .map_err(|_| SecurityError::InvalidSignature)
}

pub use ed25519_dalek::{SigningKey as PackageSigningKey, VerifyingKey as PackageVerifyingKey};

#[cfg(test)]
mod tests {
    use ime_data::{LearningRecord, LogicalClock, RecordKey};

    use super::*;

    fn record() -> LearningRecord {
        LearningRecord {
            key: RecordKey {
                namespace: "learned-lexicon".to_owned(),
                language: "zh-CN".to_owned(),
                input_signature: "nihao".to_owned(),
                value: "你好".to_owned(),
            },
            positive_count: 2,
            negative_count: 0,
            clock: LogicalClock {
                counter: 3,
                device_id: "phone".to_owned(),
            },
            deleted: false,
        }
    }

    #[test]
    fn encrypted_sync_round_trips_and_detects_tampering() {
        let key = [7_u8; 32];
        let mut encrypted = encrypt_records(&key, &[record()]).expect("encrypt");
        assert_eq!(
            decrypt_records(&key, &encrypted).expect("decrypt"),
            vec![record()]
        );
        encrypted.ciphertext[0] ^= 1;
        assert!(matches!(
            decrypt_records(&key, &encrypted),
            Err(SecurityError::Authentication)
        ));
    }

    #[test]
    fn package_signature_covers_exact_artifact_bytes() {
        let signing = SigningKey::from_bytes(&[9_u8; 32]);
        let signed = sign_artifact(&signing, b"language pack");
        verify_artifact(&signing.verifying_key(), b"language pack", &signed).expect("verify");
        assert!(matches!(
            verify_artifact(&signing.verifying_key(), b"modified", &signed),
            Err(SecurityError::InvalidSignature)
        ));
    }
}
