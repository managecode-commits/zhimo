use std::collections::HashMap;
use std::fmt;
use std::sync::Arc;

use crate::{
    ActionBatch, EngineError, EngineMetadata, FeedbackEvent, InputContext, InputEvent, SessionId,
};

pub trait InputEngine: Send + Sync {
    fn metadata(&self) -> EngineMetadata;
    fn create_session(&self, session: &SessionId) -> Result<(), EngineError>;
    fn process(
        &self,
        session: &SessionId,
        event: &InputEvent,
        context: &InputContext,
    ) -> Result<ActionBatch, EngineError>;
    fn apply_feedback(&self, event: &FeedbackEvent) -> Result<(), EngineError>;
    fn close_session(&self, session: &SessionId);
}

#[derive(Debug, PartialEq, Eq)]
pub enum RuntimeError {
    UnknownEngine(String),
    UnknownSession(String),
    DuplicateSession(String),
    Engine(EngineError),
}

impl fmt::Display for RuntimeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::UnknownEngine(id) => write!(formatter, "unknown engine: {id}"),
            Self::UnknownSession(id) => write!(formatter, "unknown session: {id}"),
            Self::DuplicateSession(id) => write!(formatter, "duplicate session: {id}"),
            Self::Engine(error) => error.fmt(formatter),
        }
    }
}

impl std::error::Error for RuntimeError {}

impl From<EngineError> for RuntimeError {
    fn from(value: EngineError) -> Self {
        Self::Engine(value)
    }
}

#[derive(Default)]
pub struct Runtime {
    engines: HashMap<String, Arc<dyn InputEngine>>,
    sessions: HashMap<SessionId, String>,
    next_session: u64,
}

impl Runtime {
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    pub fn register_engine(&mut self, engine: Arc<dyn InputEngine>) {
        self.engines.insert(engine.metadata().id, engine);
    }

    pub fn create_session(&mut self, engine_id: &str) -> Result<SessionId, RuntimeError> {
        let engine = self
            .engines
            .get(engine_id)
            .ok_or_else(|| RuntimeError::UnknownEngine(engine_id.to_owned()))?;
        self.next_session += 1;
        let session = SessionId(format!("session-{}", self.next_session));
        if self.sessions.contains_key(&session) {
            return Err(RuntimeError::DuplicateSession(session.0));
        }
        engine.create_session(&session)?;
        self.sessions.insert(session.clone(), engine_id.to_owned());
        Ok(session)
    }

    pub fn process(
        &self,
        session: &SessionId,
        event: &InputEvent,
        context: &InputContext,
    ) -> Result<ActionBatch, RuntimeError> {
        let engine_id = self
            .sessions
            .get(session)
            .ok_or_else(|| RuntimeError::UnknownSession(session.0.clone()))?;
        let engine = self
            .engines
            .get(engine_id)
            .ok_or_else(|| RuntimeError::UnknownEngine(engine_id.clone()))?;
        engine.process(session, event, context).map_err(Into::into)
    }

    pub fn close_session(&mut self, session: &SessionId) -> Result<(), RuntimeError> {
        let engine_id = self
            .sessions
            .remove(session)
            .ok_or_else(|| RuntimeError::UnknownSession(session.0.clone()))?;
        if let Some(engine) = self.engines.get(&engine_id) {
            engine.close_session(session);
        }
        Ok(())
    }

    pub fn apply_feedback(
        &self,
        session: &SessionId,
        event: &FeedbackEvent,
    ) -> Result<(), RuntimeError> {
        let engine_id = self
            .sessions
            .get(session)
            .ok_or_else(|| RuntimeError::UnknownSession(session.0.clone()))?;
        self.engines
            .get(engine_id)
            .ok_or_else(|| RuntimeError::UnknownEngine(engine_id.clone()))?
            .apply_feedback(event)
            .map_err(Into::into)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_unknown_engine() {
        let mut runtime = Runtime::new();
        assert_eq!(
            runtime.create_session("missing"),
            Err(RuntimeError::UnknownEngine("missing".to_owned()))
        );
    }
}
