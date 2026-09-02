//! Validated, signed language/model package manifests.

use std::fmt;
use std::path::{Component, Path, PathBuf};

use ime_security::{verify_artifact, ArtifactSignature, PackageVerifyingKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

#[derive(Clone, Debug, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LanguagePackManifest {
    pub schema_version: u32,
    pub id: String,
    pub version: String,
    pub languages: Vec<String>,
    pub engine: String,
    pub offline: bool,
    pub license: String,
    pub minimum_runtime_version: Option<String>,
    #[serde(default)]
    pub resources: Vec<String>,
}

#[derive(Debug)]
pub enum PackageError {
    Io(std::io::Error),
    Json(serde_json::Error),
    Invalid(String),
    Signature(ime_security::SecurityError),
}

impl fmt::Display for PackageError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "package I/O error: {error}"),
            Self::Json(error) => write!(formatter, "package manifest is invalid JSON: {error}"),
            Self::Invalid(error) => write!(formatter, "package manifest is invalid: {error}"),
            Self::Signature(error) => write!(formatter, "package authentication failed: {error}"),
        }
    }
}

impl std::error::Error for PackageError {}

impl From<std::io::Error> for PackageError {
    fn from(value: std::io::Error) -> Self {
        Self::Io(value)
    }
}

impl From<serde_json::Error> for PackageError {
    fn from(value: serde_json::Error) -> Self {
        Self::Json(value)
    }
}

impl LanguagePackManifest {
    pub fn validate(&self) -> Result<(), PackageError> {
        if self.schema_version != 1 {
            return Err(PackageError::Invalid(
                "unsupported schema version".to_owned(),
            ));
        }
        if !valid_identifier(&self.id) {
            return Err(PackageError::Invalid(
                "invalid package identifier".to_owned(),
            ));
        }
        if self.version.is_empty()
            || self.engine.is_empty()
            || self.license.is_empty()
            || self.languages.is_empty()
            || self.languages.iter().any(String::is_empty)
        {
            return Err(PackageError::Invalid(
                "version, engine, license and languages are required".to_owned(),
            ));
        }
        for resource in &self.resources {
            let path = Path::new(resource);
            if path.is_absolute()
                || path.components().any(|component| {
                    matches!(
                        component,
                        Component::ParentDir | Component::RootDir | Component::Prefix(_)
                    )
                })
            {
                return Err(PackageError::Invalid(format!(
                    "resource escapes package root: {resource}"
                )));
            }
        }
        Ok(())
    }
}

pub struct VerifiedLanguagePack {
    pub root: PathBuf,
    pub manifest: LanguagePackManifest,
    pub digest: Vec<u8>,
}

pub fn load_and_verify(
    root: &Path,
    signature: &ArtifactSignature,
    trusted_key: &PackageVerifyingKey,
) -> Result<VerifiedLanguagePack, PackageError> {
    let manifest_bytes = std::fs::read(root.join("manifest.json"))?;
    let manifest: LanguagePackManifest = serde_json::from_slice(&manifest_bytes)?;
    manifest.validate()?;
    let artifact = package_artifact(root, &manifest_bytes, &manifest.resources)?;
    verify_artifact(trusted_key, &artifact, signature).map_err(PackageError::Signature)?;
    Ok(VerifiedLanguagePack {
        root: root.to_owned(),
        manifest,
        digest: Sha256::digest(&artifact).to_vec(),
    })
}

pub fn package_artifact(
    root: &Path,
    manifest_bytes: &[u8],
    resources: &[String],
) -> Result<Vec<u8>, PackageError> {
    let mut names = resources.to_vec();
    names.sort();
    let mut artifact = Vec::new();
    append_entry(&mut artifact, "manifest.json", manifest_bytes)?;
    for name in names {
        let bytes = std::fs::read(root.join(&name))?;
        append_entry(&mut artifact, &name, &bytes)?;
    }
    Ok(artifact)
}

fn append_entry(output: &mut Vec<u8>, name: &str, bytes: &[u8]) -> Result<(), PackageError> {
    let name_length = u32::try_from(name.len())
        .map_err(|_| PackageError::Invalid("resource name is too long".to_owned()))?;
    let data_length = u64::try_from(bytes.len())
        .map_err(|_| PackageError::Invalid("resource is too large".to_owned()))?;
    output.extend_from_slice(&name_length.to_le_bytes());
    output.extend_from_slice(name.as_bytes());
    output.extend_from_slice(&data_length.to_le_bytes());
    output.extend_from_slice(bytes);
    Ok(())
}

fn valid_identifier(value: &str) -> bool {
    value.len() >= 2
        && value.bytes().all(|byte| {
            byte.is_ascii_lowercase() || byte.is_ascii_digit() || matches!(byte, b'.' | b'-')
        })
        && value.as_bytes()[0].is_ascii_alphanumeric()
}

#[cfg(test)]
mod tests {
    use ime_security::{sign_artifact, PackageSigningKey};

    use super::*;

    #[test]
    fn repository_language_packs_follow_the_common_manifest_contract() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../language-packs");
        for name in ["en", "zh-hans-pinyin", "vi-telex"] {
            let bytes = std::fs::read(root.join(name).join("manifest.json")).expect("manifest");
            let manifest: LanguagePackManifest = serde_json::from_slice(&bytes).expect("JSON");
            manifest.validate().expect("valid manifest");
        }
    }

    #[test]
    fn signed_package_detects_a_modified_resource() {
        let root = std::env::temp_dir().join(format!("shurufa-package-{}", std::process::id()));
        std::fs::create_dir_all(&root).expect("package directory");
        let manifest = br#"{"schemaVersion":1,"id":"dev.test","version":"1","languages":["en"],"engine":"latin","offline":true,"license":"MIT","resources":["words.txt"]}"#;
        std::fs::write(root.join("manifest.json"), manifest).expect("manifest");
        std::fs::write(root.join("words.txt"), b"hello").expect("resource");
        let artifact =
            package_artifact(&root, manifest, &["words.txt".to_owned()]).expect("artifact");
        let key = PackageSigningKey::from_bytes(&[5_u8; 32]);
        let signed = sign_artifact(&key, &artifact);
        load_and_verify(&root, &signed, &key.verifying_key()).expect("verified package");
        std::fs::write(root.join("words.txt"), b"tampered").expect("tamper");
        assert!(matches!(
            load_and_verify(&root, &signed, &key.verifying_key()),
            Err(PackageError::Signature(_))
        ));
        std::fs::remove_dir_all(root).expect("cleanup");
    }
}
