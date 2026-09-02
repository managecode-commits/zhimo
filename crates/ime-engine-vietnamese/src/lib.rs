//! Vietnamese Telex reference engine proving the shared composition model is
//! not specific to Chinese conversion.

use std::collections::HashMap;
use std::sync::Mutex;

use ime_core::{
    Action, ActionBatch, Candidate, CandidateId, Composition, EngineError, EngineMetadata,
    FeedbackEvent, InputContext, InputEngine, InputEvent, Key, Segment, SegmentState, SessionId,
};

#[derive(Default)]
struct State {
    raw: String,
}

#[derive(Default)]
pub struct VietnameseTelexEngine {
    sessions: Mutex<HashMap<SessionId, State>>,
}

impl VietnameseTelexEngine {
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    fn render(state: &State) -> ActionBatch {
        if state.raw.is_empty() {
            return ActionBatch(vec![
                Action::UpdateComposition(Composition::default()),
                Action::ShowCandidates(Vec::new()),
                Action::CloseComposition,
            ]);
        }
        let converted = telex(&state.raw);
        let mut candidates = vec![candidate("telex:converted", &converted)];
        if converted != state.raw {
            candidates.push(candidate("telex:raw", &state.raw));
        }
        ActionBatch(vec![
            Action::UpdateComposition(Composition {
                cursor: ime_core::grapheme_count(&converted),
                segments: vec![Segment {
                    text: converted,
                    state: SegmentState::Converted,
                    language: Some("vi".to_owned()),
                }],
            }),
            Action::ShowCandidates(candidates),
        ])
    }
}

impl InputEngine for VietnameseTelexEngine {
    fn metadata(&self) -> EngineMetadata {
        EngineMetadata {
            id: "vietnamese.telex".to_owned(),
            display_name: "Vietnamese Telex".to_owned(),
            languages: vec!["vi".to_owned()],
        }
    }

    fn create_session(&self, session: &SessionId) -> Result<(), EngineError> {
        self.sessions
            .lock()
            .map_err(lock_error)?
            .insert(session.clone(), State::default());
        Ok(())
    }

    fn process(
        &self,
        session: &SessionId,
        event: &InputEvent,
        _: &InputContext,
    ) -> Result<ActionBatch, EngineError> {
        let mut sessions = self.sessions.lock().map_err(lock_error)?;
        let state = sessions.get_mut(session).ok_or_else(missing_session)?;
        match event {
            InputEvent::Key(key) if key.pressed => match key.key {
                Key::Character(value) if value.is_ascii_alphabetic() => {
                    state.raw.push(value.to_ascii_lowercase());
                    Ok(Self::render(state))
                }
                Key::Backspace => {
                    state.raw.pop();
                    Ok(Self::render(state))
                }
                Key::Space | Key::Enter => Ok(commit(state)),
                Key::Escape => {
                    state.raw.clear();
                    Ok(Self::render(state))
                }
                _ => Ok(ActionBatch(vec![Action::Ignored])),
            },
            InputEvent::SelectCandidate(id) => {
                let text = match id.0.as_str() {
                    "telex:converted" => telex(&state.raw),
                    "telex:raw" => state.raw.clone(),
                    _ => {
                        return Err(EngineError {
                            message: "unknown Vietnamese candidate".to_owned(),
                        })
                    }
                };
                state.raw.clear();
                Ok(ActionBatch(vec![
                    Action::CommitText(text),
                    Action::CloseComposition,
                ]))
            }
            InputEvent::Reset => {
                state.raw.clear();
                Ok(Self::render(state))
            }
            InputEvent::SpeechPartial(value) => {
                Ok(ActionBatch(vec![Action::UpdateComposition(Composition {
                    cursor: ime_core::grapheme_count(&value.text),
                    segments: vec![Segment {
                        text: value.text.clone(),
                        state: SegmentState::SpeechPartial,
                        language: value.language.clone(),
                    }],
                })]))
            }
            InputEvent::SpeechFinal(value) => Ok(ActionBatch(vec![
                Action::CommitText(value.text.clone()),
                Action::CloseComposition,
            ])),
            _ => Ok(ActionBatch(vec![Action::Ignored])),
        }
    }

    fn apply_feedback(&self, _: &FeedbackEvent) -> Result<(), EngineError> {
        Ok(())
    }

    fn close_session(&self, session: &SessionId) {
        if let Ok(mut sessions) = self.sessions.lock() {
            sessions.remove(session);
        }
    }
}

fn commit(state: &mut State) -> ActionBatch {
    if state.raw.is_empty() {
        return ActionBatch(vec![Action::Ignored]);
    }
    let text = telex(&state.raw);
    state.raw.clear();
    ActionBatch(vec![Action::CommitText(text), Action::CloseComposition])
}

fn candidate(id: &str, text: &str) -> Candidate {
    Candidate {
        id: CandidateId(id.to_owned()),
        display_text: text.to_owned(),
        commit_text: text.to_owned(),
        language: Some("vi".to_owned()),
        source: "vietnamese.telex".to_owned(),
        score: 1.0,
        annotation: None,
        learning_allowed: true,
    }
}

#[must_use]
pub fn telex(raw: &str) -> String {
    let mut output = raw
        .replace("dd", "đ")
        .replace("aw", "ă")
        .replace("aa", "â")
        .replace("ee", "ê")
        .replace("oo", "ô")
        .replace("ow", "ơ")
        .replace("uw", "ư");
    let Some(tone) = output.chars().last().and_then(tone_kind) else {
        return output;
    };
    output.pop();
    let Some((byte_index, vowel)) = output
        .char_indices()
        .rev()
        .find(|(_, value)| is_vowel(*value))
    else {
        output.push(tone_suffix(tone));
        return output;
    };
    let accented = apply_tone(vowel, tone);
    output.replace_range(
        byte_index..byte_index + vowel.len_utf8(),
        &accented.to_string(),
    );
    output
}

fn tone_kind(value: char) -> Option<usize> {
    match value {
        's' => Some(0),
        'f' => Some(1),
        'r' => Some(2),
        'x' => Some(3),
        'j' => Some(4),
        _ => None,
    }
}

fn tone_suffix(tone: usize) -> char {
    ['s', 'f', 'r', 'x', 'j'][tone]
}

fn is_vowel(value: char) -> bool {
    "aăâeêioôơuưy".contains(value)
}

fn apply_tone(vowel: char, tone: usize) -> char {
    let row = match vowel {
        'a' => "áàảãạ",
        'ă' => "ắằẳẵặ",
        'â' => "ấầẩẫậ",
        'e' => "éèẻẽẹ",
        'ê' => "ếềểễệ",
        'i' => "íìỉĩị",
        'o' => "óòỏõọ",
        'ô' => "ốồổỗộ",
        'ơ' => "ớờởỡợ",
        'u' => "úùủũụ",
        'ư' => "ứừửữự",
        'y' => "ýỳỷỹỵ",
        _ => return vowel,
    };
    row.chars().nth(tone).unwrap_or(vowel)
}

fn lock_error<T>(_: std::sync::PoisonError<T>) -> EngineError {
    EngineError {
        message: "Vietnamese engine lock poisoned".to_owned(),
    }
}

fn missing_session() -> EngineError {
    EngineError {
        message: "Vietnamese session not found".to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn converts_telex_shapes_and_tones() {
        assert_eq!(telex("tieengs"), "tiếng");
        assert_eq!(telex("ddawng"), "đăng");
    }

    #[test]
    fn runs_through_the_language_neutral_engine_contract() {
        let engine = VietnameseTelexEngine::new();
        let session = SessionId("vi-test".to_owned());
        engine.create_session(&session).expect("session");
        for character in "tieengs".chars() {
            engine
                .process(
                    &session,
                    &InputEvent::Key(ime_core::KeyEvent::press(Key::Character(character))),
                    &InputContext::default(),
                )
                .expect("key");
        }
        let actions = engine
            .process(
                &session,
                &InputEvent::Key(ime_core::KeyEvent::press(Key::Space)),
                &InputContext::default(),
            )
            .expect("commit");
        assert_eq!(actions.committed_text(), Some("tiếng"));
    }
}
