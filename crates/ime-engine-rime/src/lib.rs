//! Generic Rime adapter. The public engine contract contains no librime types.

use std::collections::HashMap;
use std::sync::Mutex;

use ime_core::{
    Action, ActionBatch, Candidate, CandidateId, Composition, EngineError, EngineMetadata,
    FeedbackEvent, InputContext, InputEngine, InputEvent, Key, Segment, SegmentState, SessionId,
};

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct RimeSnapshot {
    pub preedit: String,
    pub candidates: Vec<(String, Option<String>)>,
    pub commit: Option<String>,
}

pub trait RimeBackend: Send {
    type Session: Copy + Send;

    fn create_session(&mut self) -> Result<Self::Session, String>;
    fn destroy_session(&mut self, session: Self::Session);
    fn process_key(
        &mut self,
        session: Self::Session,
        keycode: i32,
        modifiers: i32,
    ) -> Result<RimeSnapshot, String>;
    fn select_candidate(
        &mut self,
        session: Self::Session,
        index: usize,
    ) -> Result<RimeSnapshot, String>;
    fn clear(&mut self, session: Self::Session) -> Result<RimeSnapshot, String>;
}

pub struct RimeEngine<B: RimeBackend> {
    backend: Mutex<B>,
    sessions: Mutex<HashMap<SessionId, B::Session>>,
}

impl<B: RimeBackend> Drop for RimeEngine<B> {
    fn drop(&mut self) {
        let Ok(backend) = self.backend.get_mut() else {
            return;
        };
        let Ok(sessions) = self.sessions.get_mut() else {
            return;
        };
        for (_, session) in sessions.drain() {
            backend.destroy_session(session);
        }
    }
}

impl<B: RimeBackend> RimeEngine<B> {
    #[must_use]
    pub fn new(backend: B) -> Self {
        Self {
            backend: Mutex::new(backend),
            sessions: Mutex::new(HashMap::new()),
        }
    }

    fn actions(snapshot: RimeSnapshot) -> ActionBatch {
        let mut actions = Vec::new();
        if let Some(commit) = snapshot.commit {
            actions.push(Action::CommitText(commit));
        }
        let visible = !snapshot.preedit.is_empty();
        actions.push(Action::UpdateComposition(Composition {
            cursor: ime_core::grapheme_count(&snapshot.preedit),
            segments: if visible {
                vec![Segment {
                    text: snapshot.preedit,
                    state: SegmentState::Composing,
                    language: Some("zh-Latn-pinyin".to_owned()),
                }]
            } else {
                Vec::new()
            },
        }));
        let candidates = snapshot
            .candidates
            .into_iter()
            .enumerate()
            .map(|(index, (text, comment))| Candidate {
                id: CandidateId(format!("rime:{index}")),
                display_text: text.clone(),
                commit_text: text,
                language: Some("zh-CN".to_owned()),
                source: "librime".to_owned(),
                score: 0.0,
                annotation: comment,
                learning_allowed: true,
            })
            .collect();
        actions.push(Action::ShowCandidates(candidates));
        if !visible {
            actions.push(Action::CloseComposition);
        }
        ActionBatch(actions)
    }

    fn backend_session(&self, session: &SessionId) -> Result<B::Session, EngineError> {
        self.sessions
            .lock()
            .map_err(lock_error)?
            .get(session)
            .copied()
            .ok_or_else(|| EngineError {
                message: "Rime session not found".to_owned(),
            })
    }
}

impl<B: RimeBackend> InputEngine for RimeEngine<B> {
    fn metadata(&self) -> EngineMetadata {
        EngineMetadata {
            id: "rime".to_owned(),
            display_name: "Rime Input Engine".to_owned(),
            languages: vec!["zh-CN".to_owned(), "zh-TW".to_owned()],
        }
    }

    fn create_session(&self, session: &SessionId) -> Result<(), EngineError> {
        let native = self
            .backend
            .lock()
            .map_err(lock_error)?
            .create_session()
            .map_err(backend_error)?;
        self.sessions
            .lock()
            .map_err(lock_error)?
            .insert(session.clone(), native);
        Ok(())
    }

    fn process(
        &self,
        session: &SessionId,
        event: &InputEvent,
        _: &InputContext,
    ) -> Result<ActionBatch, EngineError> {
        let native = self.backend_session(session)?;
        let snapshot = match event {
            InputEvent::Key(key) if key.pressed => {
                let keycode = match key.key {
                    Key::Character(value) => i32::try_from(u32::from(value))
                        .map_err(|_| backend_error("key code overflow".to_owned()))?,
                    Key::Backspace => 0xff08,
                    Key::Enter => 0xff0d,
                    Key::Space => 0x20,
                    Key::Escape => 0xff1b,
                    Key::Left => 0xff51,
                    Key::Right => 0xff53,
                };
                self.backend
                    .lock()
                    .map_err(lock_error)?
                    .process_key(native, keycode, 0)
                    .map_err(backend_error)?
            }
            InputEvent::SelectCandidate(candidate) => {
                let index = candidate
                    .0
                    .strip_prefix("rime:")
                    .ok_or_else(|| backend_error("candidate does not belong to Rime".to_owned()))?
                    .parse::<usize>()
                    .map_err(|_| backend_error("invalid Rime candidate index".to_owned()))?;
                self.backend
                    .lock()
                    .map_err(lock_error)?
                    .select_candidate(native, index)
                    .map_err(backend_error)?
            }
            InputEvent::Reset => self
                .backend
                .lock()
                .map_err(lock_error)?
                .clear(native)
                .map_err(backend_error)?,
            InputEvent::SpeechPartial(value) => {
                return Ok(ActionBatch(vec![Action::UpdateComposition(Composition {
                    cursor: ime_core::grapheme_count(&value.text),
                    segments: vec![Segment {
                        text: value.text.clone(),
                        state: SegmentState::SpeechPartial,
                        language: value.language.clone(),
                    }],
                })]));
            }
            InputEvent::SpeechFinal(value) => {
                return Ok(ActionBatch(vec![
                    Action::CommitText(value.text.clone()),
                    Action::CloseComposition,
                ]));
            }
            InputEvent::Text(_) | InputEvent::Key(_) | InputEvent::SwitchEngine(_) => {
                return Ok(ActionBatch(vec![Action::Ignored]));
            }
        };
        Ok(Self::actions(snapshot))
    }

    fn apply_feedback(&self, _: &FeedbackEvent) -> Result<(), EngineError> {
        Ok(())
    }

    fn close_session(&self, session: &SessionId) {
        if let Ok(mut sessions) = self.sessions.lock() {
            if let Some(native) = sessions.remove(session) {
                if let Ok(mut backend) = self.backend.lock() {
                    backend.destroy_session(native);
                }
            }
        }
    }
}

fn lock_error<T>(_: std::sync::PoisonError<T>) -> EngineError {
    EngineError {
        message: "Rime adapter lock poisoned".to_owned(),
    }
}

fn backend_error(message: String) -> EngineError {
    EngineError { message }
}

#[cfg(feature = "native-librime")]
pub mod native;

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Default)]
    struct FakeRime {
        input: String,
    }

    impl RimeBackend for FakeRime {
        type Session = u64;
        fn create_session(&mut self) -> Result<Self::Session, String> {
            Ok(1)
        }
        fn destroy_session(&mut self, _: Self::Session) {}
        fn process_key(
            &mut self,
            _: Self::Session,
            keycode: i32,
            _: i32,
        ) -> Result<RimeSnapshot, String> {
            if keycode == 0x20 {
                let commit = (self.input == "nihao").then(|| "你好".to_owned());
                self.input.clear();
                Ok(RimeSnapshot {
                    commit,
                    ..RimeSnapshot::default()
                })
            } else {
                if let Some(value) = char::from_u32(u32::try_from(keycode).map_err(|_| "key")?) {
                    self.input.push(value);
                }
                Ok(RimeSnapshot {
                    preedit: self.input.clone(),
                    candidates: vec![("你好".to_owned(), Some("ni hao".to_owned()))],
                    commit: None,
                })
            }
        }
        fn select_candidate(&mut self, _: Self::Session, _: usize) -> Result<RimeSnapshot, String> {
            Ok(RimeSnapshot {
                commit: Some("你好".to_owned()),
                ..RimeSnapshot::default()
            })
        }
        fn clear(&mut self, _: Self::Session) -> Result<RimeSnapshot, String> {
            self.input.clear();
            Ok(RimeSnapshot::default())
        }
    }

    #[test]
    fn generic_adapter_maps_rime_state_to_common_actions() {
        let engine = RimeEngine::new(FakeRime::default());
        let session = SessionId("rime-test".to_owned());
        engine.create_session(&session).expect("session");
        for character in "nihao".chars() {
            let actions = engine
                .process(
                    &session,
                    &InputEvent::Key(ime_core::KeyEvent::press(Key::Character(character))),
                    &InputContext::default(),
                )
                .expect("key");
            assert!(actions
                .0
                .iter()
                .any(|action| matches!(action, Action::ShowCandidates(_))));
        }
        let actions = engine
            .process(
                &session,
                &InputEvent::Key(ime_core::KeyEvent::press(Key::Space)),
                &InputContext::default(),
            )
            .expect("commit");
        assert_eq!(actions.committed_text(), Some("你好"));
    }
}
