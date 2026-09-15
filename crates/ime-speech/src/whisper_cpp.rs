// Copyright © 2026 立方田 <managecode@gmail.com>
//! Offline batch provider backed by the `whisper-cli` executable.
//!
//! Inference runs on a worker thread. Platform input threads only enqueue audio
//! and poll results, preserving the runtime's non-blocking boundary.

use std::collections::HashMap;
use std::fs::File;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::mpsc::{self, Receiver, TryRecvError};

use ime_core::SpeechHypothesis;

use crate::audio::normalize_mono;
use crate::{
    AudioFrame, SpeechError, SpeechEvent, SpeechProvider, SpeechSessionConfig, SpeechSessionHandle,
};

struct WhisperSession {
    language: String,
    prompt: String,
    samples: Vec<f32>,
    ready: bool,
    result: Option<Receiver<Result<String, SpeechError>>>,
}

pub struct WhisperCppProvider {
    executable: PathBuf,
    model: PathBuf,
    temporary_directory: PathBuf,
    next: u64,
    sessions: HashMap<SpeechSessionHandle, WhisperSession>,
}

impl WhisperCppProvider {
    #[must_use]
    pub fn new(
        executable: impl Into<PathBuf>,
        model: impl Into<PathBuf>,
        temporary_directory: impl Into<PathBuf>,
    ) -> Self {
        Self {
            executable: executable.into(),
            model: model.into(),
            temporary_directory: temporary_directory.into(),
            next: 0,
            sessions: HashMap::new(),
        }
    }
}

impl SpeechProvider for WhisperCppProvider {
    fn id(&self) -> &'static str {
        "whisper.cpp.offline"
    }

    fn is_offline(&self) -> bool {
        true
    }

    fn start(&mut self, config: &SpeechSessionConfig) -> Result<SpeechSessionHandle, SpeechError> {
        if !self.executable.is_file() {
            return Err(SpeechError(
                "whisper.cpp executable was not found".to_owned(),
            ));
        }
        if !self.model.is_file() {
            return Err(SpeechError("whisper.cpp model was not found".to_owned()));
        }
        std::fs::create_dir_all(&self.temporary_directory).map_err(|error| {
            SpeechError(format!("cannot create speech work directory: {error}"))
        })?;
        self.next = self.next.saturating_add(1);
        let handle = SpeechSessionHandle(self.next);
        let language = choose_language(&config.languages);
        let prompt = config.hotwords.join(", ");
        self.sessions.insert(
            handle,
            WhisperSession {
                language,
                prompt,
                samples: Vec::new(),
                ready: true,
                result: None,
            },
        );
        Ok(handle)
    }

    fn push_audio(
        &mut self,
        session: SpeechSessionHandle,
        frame: AudioFrame,
    ) -> Result<(), SpeechError> {
        let state = self
            .sessions
            .get_mut(&session)
            .ok_or_else(|| SpeechError("unknown speech session".to_owned()))?;
        if state.result.is_some() {
            return Err(SpeechError(
                "speech session is already finishing".to_owned(),
            ));
        }
        state.samples.extend(normalize_mono(&frame, 16_000)?);
        Ok(())
    }

    fn poll(&mut self, session: SpeechSessionHandle) -> Result<Vec<SpeechEvent>, SpeechError> {
        let state = self
            .sessions
            .get_mut(&session)
            .ok_or_else(|| SpeechError("unknown speech session".to_owned()))?;
        if state.ready {
            state.ready = false;
            return Ok(vec![SpeechEvent::Ready]);
        }
        let Some(receiver) = &state.result else {
            return Ok(Vec::new());
        };
        match receiver.try_recv() {
            Ok(Ok(text)) => {
                let language = state.language.clone();
                self.sessions.remove(&session);
                Ok(vec![
                    SpeechEvent::Final(SpeechHypothesis {
                        text,
                        confidence: None,
                        language: (language != "auto").then_some(language),
                    }),
                    SpeechEvent::Ended,
                ])
            }
            Ok(Err(error)) => {
                self.sessions.remove(&session);
                Err(error)
            }
            Err(TryRecvError::Empty) => Ok(Vec::new()),
            Err(TryRecvError::Disconnected) => {
                self.sessions.remove(&session);
                Err(SpeechError(
                    "whisper.cpp worker stopped unexpectedly".to_owned(),
                ))
            }
        }
    }

    fn finish(&mut self, session: SpeechSessionHandle) -> Result<(), SpeechError> {
        let state = self
            .sessions
            .get_mut(&session)
            .ok_or_else(|| SpeechError("unknown speech session".to_owned()))?;
        if state.result.is_some() {
            return Ok(());
        }
        let audio_path = self.temporary_directory.join(format!(
            "speech-{}-{}.wav",
            std::process::id(),
            session.0
        ));
        write_float_wav(&audio_path, &state.samples, 16_000)?;
        state.samples.clear();

        let executable = self.executable.clone();
        let model = self.model.clone();
        let language = state.language.clone();
        let prompt = state.prompt.clone();
        let (sender, receiver) = mpsc::channel();
        std::thread::spawn(move || {
            let result = transcribe(&executable, &model, &audio_path, &language, &prompt);
            let _ = std::fs::remove_file(&audio_path);
            let _ = sender.send(result);
        });
        state.result = Some(receiver);
        Ok(())
    }

    fn cancel(&mut self, session: SpeechSessionHandle) {
        self.sessions.remove(&session);
    }
}

fn choose_language(languages: &[String]) -> String {
    if languages.len() != 1 {
        return "auto".to_owned();
    }
    languages[0]
        .split(['-', '_'])
        .next()
        .filter(|value| !value.is_empty())
        .unwrap_or("auto")
        .to_owned()
}

fn transcribe(
    executable: &Path,
    model: &Path,
    audio_path: &Path,
    language: &str,
    prompt: &str,
) -> Result<String, SpeechError> {
    let mut command = Command::new(executable);
    command
        .args(["-m", model.to_string_lossy().as_ref()])
        .args(["-f", audio_path.to_string_lossy().as_ref()])
        .args(["-l", language, "-nt", "-np"]);
    if !prompt.is_empty() {
        command.args(["--prompt", prompt]);
    }
    let output = command
        .output()
        .map_err(|error| SpeechError(format!("cannot start whisper.cpp: {error}")))?;
    if !output.status.success() {
        return Err(SpeechError(format!(
            "whisper.cpp failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        )));
    }
    let text = String::from_utf8_lossy(&output.stdout).trim().to_owned();
    if text.is_empty() {
        Err(SpeechError("whisper.cpp returned no transcript".to_owned()))
    } else {
        Ok(text)
    }
}

fn write_float_wav(path: &Path, samples: &[f32], sample_rate: u32) -> Result<(), SpeechError> {
    let data_size = u32::try_from(samples.len().saturating_mul(4))
        .map_err(|_| SpeechError("audio is too large for WAV".to_owned()))?;
    let mut file = File::create(path)
        .map_err(|error| SpeechError(format!("cannot create temporary WAV: {error}")))?;
    file.write_all(b"RIFF")
        .and_then(|()| file.write_all(&(36_u32.saturating_add(data_size)).to_le_bytes()))
        .and_then(|()| file.write_all(b"WAVEfmt "))
        .and_then(|()| file.write_all(&16_u32.to_le_bytes()))
        .and_then(|()| file.write_all(&3_u16.to_le_bytes()))
        .and_then(|()| file.write_all(&1_u16.to_le_bytes()))
        .and_then(|()| file.write_all(&sample_rate.to_le_bytes()))
        .and_then(|()| file.write_all(&sample_rate.saturating_mul(4).to_le_bytes()))
        .and_then(|()| file.write_all(&4_u16.to_le_bytes()))
        .and_then(|()| file.write_all(&32_u16.to_le_bytes()))
        .and_then(|()| file.write_all(b"data"))
        .and_then(|()| file.write_all(&data_size.to_le_bytes()))
        .map_err(|error| SpeechError(format!("cannot write WAV header: {error}")))?;
    for sample in samples {
        file.write_all(&sample.clamp(-1.0, 1.0).to_le_bytes())
            .map_err(|error| SpeechError(format!("cannot write WAV samples: {error}")))?;
    }
    file.sync_all()
        .map_err(|error| SpeechError(format!("cannot flush WAV: {error}")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn language_selection_is_provider_neutral() {
        assert_eq!(choose_language(&["zh-CN".to_owned()]), "zh");
        assert_eq!(
            choose_language(&["zh-CN".to_owned(), "en".to_owned()]),
            "auto"
        );
    }

    #[test]
    fn writes_a_valid_ieee_float_wav_header() {
        let path = std::env::temp_dir().join(format!("zhimo-wav-{}.wav", std::process::id()));
        write_float_wav(&path, &[0.0, 0.5], 16_000).expect("write WAV");
        let bytes = std::fs::read(&path).expect("read WAV");
        assert_eq!(&bytes[0..4], b"RIFF");
        assert_eq!(&bytes[8..12], b"WAVE");
        assert_eq!(u16::from_le_bytes([bytes[20], bytes[21]]), 3);
        let _ = std::fs::remove_file(path);
    }
}
