//! Provider-neutral speech transcription contracts.

pub mod audio;
pub mod text;
pub mod whisper_cpp;

use std::collections::{HashMap, VecDeque};
use std::fmt;

use ime_core::{ActionBatch, InputContext, InputScope, Runtime, SessionId};
use ime_core::{InputEvent, SpeechHypothesis};

#[derive(Clone, Debug, PartialEq)]
pub struct AudioFrame {
    pub samples: Vec<f32>,
    pub sample_rate_hz: u32,
    pub channels: u16,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SpeechSessionConfig {
    pub languages: Vec<String>,
    pub interim_results: bool,
    pub offline_required: bool,
    pub hotwords: Vec<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct SpeechSessionHandle(pub u64);

#[derive(Clone, Debug, PartialEq)]
pub enum SpeechEvent {
    Ready,
    Partial(SpeechHypothesis),
    Final(SpeechHypothesis),
    Ended,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SpeechError(pub String);

impl fmt::Display for SpeechError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl std::error::Error for SpeechError {}

pub trait SpeechProvider {
    fn id(&self) -> &'static str;
    fn is_offline(&self) -> bool;
    fn start(&mut self, config: &SpeechSessionConfig) -> Result<SpeechSessionHandle, SpeechError>;
    fn push_audio(
        &mut self,
        session: SpeechSessionHandle,
        frame: AudioFrame,
    ) -> Result<(), SpeechError>;
    fn poll(&mut self, session: SpeechSessionHandle) -> Result<Vec<SpeechEvent>, SpeechError>;
    fn finish(&mut self, session: SpeechSessionHandle) -> Result<(), SpeechError>;
    fn cancel(&mut self, session: SpeechSessionHandle);
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SpeechPolicy {
    /// Hard limit protecting battery and accidental continuous recording.
    pub maximum_audio_seconds: u64,
}

impl Default for SpeechPolicy {
    fn default() -> Self {
        Self {
            maximum_audio_seconds: 120,
        }
    }
}

struct ActiveSpeechSession {
    accepted_nanoseconds: u64,
}

/// Bridges a provider to input sessions while enforcing privacy and resource policy.
pub struct SpeechController<P> {
    provider: P,
    policy: SpeechPolicy,
    sessions: HashMap<SpeechSessionHandle, ActiveSpeechSession>,
}

impl<P: SpeechProvider> SpeechController<P> {
    #[must_use]
    pub fn new(provider: P, policy: SpeechPolicy) -> Self {
        Self {
            provider,
            policy,
            sessions: HashMap::new(),
        }
    }

    pub fn start(
        &mut self,
        config: &SpeechSessionConfig,
        context: &InputContext,
    ) -> Result<SpeechSessionHandle, SpeechError> {
        if context.scope == InputScope::Password {
            return Err(SpeechError(
                "speech is disabled in password fields".to_owned(),
            ));
        }
        if config.offline_required && !self.provider.is_offline() {
            return Err(SpeechError(
                "selected provider cannot satisfy offline policy".to_owned(),
            ));
        }
        let handle = self.provider.start(config)?;
        self.sessions.insert(
            handle,
            ActiveSpeechSession {
                accepted_nanoseconds: 0,
            },
        );
        Ok(handle)
    }

    pub fn push_audio(
        &mut self,
        handle: SpeechSessionHandle,
        frame: AudioFrame,
    ) -> Result<(), SpeechError> {
        if frame.sample_rate_hz == 0 || frame.channels == 0 {
            return Err(SpeechError("invalid audio format".to_owned()));
        }
        if frame.samples.len() % usize::from(frame.channels) != 0
            || frame.samples.iter().any(|sample| !sample.is_finite())
        {
            return Err(SpeechError("invalid PCM samples".to_owned()));
        }
        let session = self
            .sessions
            .get_mut(&handle)
            .ok_or_else(|| SpeechError("unknown speech session".to_owned()))?;
        let channel_count = u64::from(frame.channels);
        let frame_samples = u64::try_from(frame.samples.len()).unwrap_or(u64::MAX) / channel_count;
        let frame_nanoseconds =
            frame_samples.saturating_mul(1_000_000_000) / u64::from(frame.sample_rate_hz);
        session.accepted_nanoseconds = session
            .accepted_nanoseconds
            .saturating_add(frame_nanoseconds);
        let maximum_nanoseconds = self
            .policy
            .maximum_audio_seconds
            .saturating_mul(1_000_000_000);
        if session.accepted_nanoseconds > maximum_nanoseconds {
            self.provider.cancel(handle);
            self.sessions.remove(&handle);
            return Err(SpeechError(
                "speech session exceeded audio duration policy".to_owned(),
            ));
        }
        self.provider.push_audio(handle, frame)
    }

    pub fn poll_into_runtime(
        &mut self,
        handle: SpeechSessionHandle,
        runtime: &Runtime,
        input_session: &SessionId,
        context: &InputContext,
    ) -> Result<Vec<ActionBatch>, SpeechError> {
        if !self.sessions.contains_key(&handle) {
            return Err(SpeechError("unknown speech session".to_owned()));
        }
        let events = self.provider.poll(handle)?;
        let ended = events
            .iter()
            .any(|event| matches!(event, SpeechEvent::Ended));
        let result = events
            .into_iter()
            .filter_map(|event| into_input_event(normalize_event(event)))
            .map(|event| {
                runtime
                    .process(input_session, &event, context)
                    .map_err(|error| {
                        SpeechError(format!("input runtime rejected speech result: {error}"))
                    })
            })
            .collect();
        if ended {
            self.sessions.remove(&handle);
        }
        result
    }

    pub fn finish(&mut self, handle: SpeechSessionHandle) -> Result<(), SpeechError> {
        if !self.sessions.contains_key(&handle) {
            return Err(SpeechError("unknown speech session".to_owned()));
        }
        self.provider.finish(handle)
    }

    pub fn cancel(&mut self, handle: SpeechSessionHandle) {
        self.provider.cancel(handle);
        self.sessions.remove(&handle);
    }

    #[must_use]
    pub fn provider_mut(&mut self) -> &mut P {
        &mut self.provider
    }

    #[must_use]
    pub fn is_active(&self, handle: SpeechSessionHandle) -> bool {
        self.sessions.contains_key(&handle)
    }
}

#[must_use]
pub fn into_input_event(event: SpeechEvent) -> Option<InputEvent> {
    match event {
        SpeechEvent::Partial(value) => Some(InputEvent::SpeechPartial(value)),
        SpeechEvent::Final(value) => Some(InputEvent::SpeechFinal(value)),
        SpeechEvent::Ready | SpeechEvent::Ended => None,
    }
}

fn normalize_event(event: SpeechEvent) -> SpeechEvent {
    match event {
        SpeechEvent::Partial(mut value) => {
            value.text = text::normalize_transcript(&value.text, value.language.as_deref());
            SpeechEvent::Partial(value)
        }
        SpeechEvent::Final(mut value) => {
            value.text = text::normalize_transcript(&value.text, value.language.as_deref());
            SpeechEvent::Final(value)
        }
        other => other,
    }
}

/// Deterministic provider for contract tests and platform probes. It performs no recording.
#[derive(Default)]
pub struct MockSpeechProvider {
    next: u64,
    queues: HashMap<SpeechSessionHandle, VecDeque<SpeechEvent>>,
}

impl MockSpeechProvider {
    pub fn emit(
        &mut self,
        session: SpeechSessionHandle,
        event: SpeechEvent,
    ) -> Result<(), SpeechError> {
        self.queues
            .get_mut(&session)
            .ok_or_else(|| SpeechError("unknown speech session".to_owned()))?
            .push_back(event);
        Ok(())
    }
}

impl SpeechProvider for MockSpeechProvider {
    fn id(&self) -> &'static str {
        "mock.offline"
    }
    fn is_offline(&self) -> bool {
        true
    }

    fn start(&mut self, _config: &SpeechSessionConfig) -> Result<SpeechSessionHandle, SpeechError> {
        self.next += 1;
        let handle = SpeechSessionHandle(self.next);
        self.queues
            .insert(handle, VecDeque::from([SpeechEvent::Ready]));
        Ok(handle)
    }

    fn push_audio(
        &mut self,
        session: SpeechSessionHandle,
        _frame: AudioFrame,
    ) -> Result<(), SpeechError> {
        if self.queues.contains_key(&session) {
            Ok(())
        } else {
            Err(SpeechError("unknown speech session".to_owned()))
        }
    }

    fn poll(&mut self, session: SpeechSessionHandle) -> Result<Vec<SpeechEvent>, SpeechError> {
        let queue = self
            .queues
            .get_mut(&session)
            .ok_or_else(|| SpeechError("unknown speech session".to_owned()))?;
        Ok(queue.drain(..).collect())
    }

    fn finish(&mut self, session: SpeechSessionHandle) -> Result<(), SpeechError> {
        self.emit(session, SpeechEvent::Ended)
    }

    fn cancel(&mut self, session: SpeechSessionHandle) {
        self.queues.remove(&session);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ime_engine_latin::LatinEngine;
    use std::sync::Arc;

    #[test]
    fn provider_streams_partial_and_final_results() {
        let mut provider = MockSpeechProvider::default();
        let handle = provider
            .start(&SpeechSessionConfig {
                languages: vec!["zh-CN".to_owned(), "en".to_owned()],
                interim_results: true,
                offline_required: true,
                hotwords: Vec::new(),
            })
            .expect("start");
        provider.poll(handle).expect("ready");
        provider
            .emit(
                handle,
                SpeechEvent::Partial(SpeechHypothesis {
                    text: "你好世".to_owned(),
                    confidence: None,
                    language: Some("zh-CN".to_owned()),
                }),
            )
            .expect("partial");
        provider
            .emit(
                handle,
                SpeechEvent::Final(SpeechHypothesis {
                    text: "你好世界".to_owned(),
                    confidence: Some(0.95),
                    language: Some("zh-CN".to_owned()),
                }),
            )
            .expect("final");
        let events = provider.poll(handle).expect("events");
        assert!(matches!(
            into_input_event(events[0].clone()),
            Some(InputEvent::SpeechPartial(_))
        ));
        assert!(matches!(
            into_input_event(events[1].clone()),
            Some(InputEvent::SpeechFinal(_))
        ));
    }

    #[test]
    fn controller_enforces_sensitive_field_policy() {
        let mut controller =
            SpeechController::new(MockSpeechProvider::default(), SpeechPolicy::default());
        let context = InputContext {
            scope: InputScope::Password,
            ..InputContext::default()
        };
        let result = controller.start(
            &SpeechSessionConfig {
                languages: vec!["en".to_owned()],
                interim_results: true,
                offline_required: true,
                hotwords: Vec::new(),
            },
            &context,
        );
        assert_eq!(
            result,
            Err(SpeechError(
                "speech is disabled in password fields".to_owned()
            ))
        );
    }

    #[test]
    fn controller_delivers_hypotheses_to_input_runtime() {
        let mut runtime = Runtime::new();
        runtime.register_engine(Arc::new(LatinEngine::new()));
        let input_session = runtime.create_session("latin").expect("input session");
        let mut controller =
            SpeechController::new(MockSpeechProvider::default(), SpeechPolicy::default());
        let handle = controller
            .start(
                &SpeechSessionConfig {
                    languages: vec!["en".to_owned()],
                    interim_results: true,
                    offline_required: true,
                    hotwords: Vec::new(),
                },
                &InputContext::default(),
            )
            .expect("speech session");
        controller
            .provider_mut()
            .poll(handle)
            .expect("discard ready");
        controller
            .provider_mut()
            .emit(
                handle,
                SpeechEvent::Partial(SpeechHypothesis {
                    text: "hello wor".to_owned(),
                    confidence: Some(0.7),
                    language: Some("en".to_owned()),
                }),
            )
            .expect("partial");
        controller
            .provider_mut()
            .emit(
                handle,
                SpeechEvent::Final(SpeechHypothesis {
                    text: "hello world".to_owned(),
                    confidence: Some(0.9),
                    language: Some("en".to_owned()),
                }),
            )
            .expect("final");
        let batches = controller
            .poll_into_runtime(handle, &runtime, &input_session, &InputContext::default())
            .expect("bridge");
        assert_eq!(batches.len(), 2);
        assert_eq!(batches[1].committed_text(), Some("hello world"));
    }

    #[test]
    fn ended_event_releases_controller_session() {
        let mut runtime = Runtime::new();
        runtime.register_engine(Arc::new(LatinEngine::new()));
        let input_session = runtime.create_session("latin").expect("input session");
        let mut controller =
            SpeechController::new(MockSpeechProvider::default(), SpeechPolicy::default());
        let handle = controller
            .start(
                &SpeechSessionConfig {
                    languages: vec!["en".to_owned()],
                    interim_results: true,
                    offline_required: true,
                    hotwords: Vec::new(),
                },
                &InputContext::default(),
            )
            .expect("speech session");
        controller.finish(handle).expect("finish");
        controller
            .poll_into_runtime(handle, &runtime, &input_session, &InputContext::default())
            .expect("poll ended");
        assert_eq!(
            controller.poll_into_runtime(
                handle,
                &runtime,
                &input_session,
                &InputContext::default()
            ),
            Err(SpeechError("unknown speech session".to_owned()))
        );
    }

    #[test]
    fn duration_policy_handles_sample_rate_changes_and_invalid_pcm() {
        let mut controller = SpeechController::new(
            MockSpeechProvider::default(),
            SpeechPolicy {
                maximum_audio_seconds: 1,
            },
        );
        let handle = controller
            .start(
                &SpeechSessionConfig {
                    languages: vec!["en".to_owned()],
                    interim_results: false,
                    offline_required: true,
                    hotwords: Vec::new(),
                },
                &InputContext::default(),
            )
            .expect("session");
        controller
            .push_audio(
                handle,
                AudioFrame {
                    samples: vec![0.0; 400],
                    sample_rate_hz: 800,
                    channels: 1,
                },
            )
            .expect("first half second");
        controller
            .push_audio(
                handle,
                AudioFrame {
                    samples: vec![0.0; 800],
                    sample_rate_hz: 1_600,
                    channels: 1,
                },
            )
            .expect("second half second");
        assert!(controller
            .push_audio(
                handle,
                AudioFrame {
                    samples: vec![0.0],
                    sample_rate_hz: 1_600,
                    channels: 1,
                },
            )
            .is_err());

        let second = controller
            .start(
                &SpeechSessionConfig {
                    languages: vec!["en".to_owned()],
                    interim_results: false,
                    offline_required: true,
                    hotwords: Vec::new(),
                },
                &InputContext::default(),
            )
            .expect("second session");
        assert!(controller
            .push_audio(
                second,
                AudioFrame {
                    samples: vec![f32::NAN],
                    sample_rate_hz: 16_000,
                    channels: 1,
                },
            )
            .is_err());
    }
}
