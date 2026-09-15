// Copyright © 2026 立方田 <managecode@gmail.com>
use serde::Serialize;
use std::fmt;

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct SessionId(pub String);

#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize)]
pub struct CandidateId(pub String);

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Key {
    Character(char),
    Backspace,
    Enter,
    Space,
    Escape,
    Left,
    Right,
    PageUp,
    PageDown,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct KeyEvent {
    pub key: Key,
    pub pressed: bool,
    pub repeat: bool,
}

impl KeyEvent {
    #[must_use]
    pub const fn press(key: Key) -> Self {
        Self {
            key,
            pressed: true,
            repeat: false,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct SpeechHypothesis {
    pub text: String,
    pub confidence: Option<f32>,
    pub language: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum InputEvent {
    Key(KeyEvent),
    Text(String),
    SpeechPartial(SpeechHypothesis),
    SpeechFinal(SpeechHypothesis),
    SelectCandidate(CandidateId),
    SwitchEngine(String),
    Reset,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum InputScope {
    Normal,
    Password,
    Email,
    Url,
    Code,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct InputContext {
    pub scope: InputScope,
    pub surrounding_text: Option<String>,
    pub application_id: Option<String>,
    pub learning_allowed: bool,
    pub network_allowed: bool,
}

impl Default for InputContext {
    fn default() -> Self {
        Self {
            scope: InputScope::Normal,
            surrounding_text: None,
            application_id: None,
            learning_allowed: true,
            network_allowed: false,
        }
    }
}

impl InputContext {
    #[must_use]
    pub fn effective_learning_allowed(&self) -> bool {
        self.learning_allowed && self.scope != InputScope::Password
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub enum SegmentState {
    Raw,
    Composing,
    Converted,
    SpeechPartial,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Segment {
    pub text: String,
    pub state: SegmentState,
    pub language: Option<String>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct Composition {
    pub segments: Vec<Segment>,
    pub cursor: usize,
}

impl Composition {
    #[must_use]
    pub fn text(&self) -> String {
        self.segments
            .iter()
            .map(|segment| segment.text.as_str())
            .collect()
    }

    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.segments.iter().all(|segment| segment.text.is_empty())
    }
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Candidate {
    pub id: CandidateId,
    pub display_text: String,
    pub commit_text: String,
    pub language: Option<String>,
    pub source: String,
    pub score: f64,
    pub annotation: Option<String>,
    pub learning_allowed: bool,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub enum Action {
    UpdateComposition(Composition),
    ShowCandidates(Vec<Candidate>),
    CandidatePage { index: usize, has_next: bool },
    /// Optional phonetic disambiguation UI; selecting a reading must not commit text.
    PinyinReadings { readings: Vec<String>, selected: Option<String> },
    CommitText(String),
    CloseComposition,
    Ignored,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct ActionBatch(pub Vec<Action>);

impl ActionBatch {
    #[must_use]
    pub fn committed_text(&self) -> Option<&str> {
        self.0.iter().rev().find_map(|action| match action {
            Action::CommitText(text) => Some(text.as_str()),
            _ => None,
        })
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FeedbackKind {
    Selected,
    Rejected,
    Deleted,
    Undone,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FeedbackEvent {
    pub candidate_id: CandidateId,
    pub input_signature: String,
    pub committed_text: String,
    pub kind: FeedbackKind,
    pub language: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EngineMetadata {
    pub id: String,
    pub display_name: String,
    pub languages: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EngineError {
    pub message: String,
}

impl fmt::Display for EngineError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for EngineError {}
