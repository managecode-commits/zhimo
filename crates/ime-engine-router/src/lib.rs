// Copyright © 2026 立方田 <managecode@gmail.com>
//! Composite engine that routes one platform session across multiple language engines.

use std::collections::{BTreeSet, HashMap};
use std::sync::{Arc, Mutex};

use ime_core::{
    Action, ActionBatch, Composition, EngineError, EngineMetadata, FeedbackEvent, InputContext,
    InputEngine, InputEvent, SessionId,
};

struct RouteSession {
    active_engine: String,
}

pub struct LanguageRouter {
    id: String,
    default_engine: String,
    engines: HashMap<String, Arc<dyn InputEngine>>,
    languages: HashMap<String, String>,
    sessions: Mutex<HashMap<SessionId, RouteSession>>,
}

impl LanguageRouter {
    pub fn new(
        id: impl Into<String>,
        default_engine: impl Into<String>,
        engines: Vec<Arc<dyn InputEngine>>,
    ) -> Result<Self, EngineError> {
        let default_engine = default_engine.into();
        let mut by_id = HashMap::new();
        let mut languages = HashMap::new();
        for engine in engines {
            let metadata = engine.metadata();
            for language in metadata.languages {
                languages
                    .entry(language)
                    .or_insert_with(|| metadata.id.clone());
            }
            by_id.insert(metadata.id, engine);
        }
        if !by_id.contains_key(&default_engine) {
            return Err(EngineError {
                message: format!("default engine is not registered: {default_engine}"),
            });
        }
        Ok(Self {
            id: id.into(),
            default_engine,
            engines: by_id,
            languages,
            sessions: Mutex::new(HashMap::new()),
        })
    }

    fn switch_engine(&self, session: &SessionId, target: &str) -> Result<ActionBatch, EngineError> {
        let target_engine = self.engines.get(target).ok_or_else(|| EngineError {
            message: format!("unknown routed engine: {target}"),
        })?;
        let previous = {
            let sessions = self.sessions.lock().map_err(lock_error)?;
            sessions
                .get(session)
                .ok_or_else(|| EngineError {
                    message: "router session not found".to_owned(),
                })?
                .active_engine
                .clone()
        };
        if previous == target {
            return Ok(ActionBatch(vec![Action::Ignored]));
        }
        target_engine.create_session(session)?;
        if let Some(engine) = self.engines.get(&previous) {
            engine.close_session(session);
        }
        let mut sessions = self.sessions.lock().map_err(lock_error)?;
        let active_engine = &mut sessions
            .get_mut(session)
            .ok_or_else(|| EngineError {
                message: "router session disappeared".to_owned(),
            })?
            .active_engine;
        target.clone_into(active_engine);
        Ok(ActionBatch(vec![
            Action::UpdateComposition(Composition::default()),
            Action::CloseComposition,
        ]))
    }

    fn route_for_event(&self, event: &InputEvent) -> Option<&str> {
        let language = match event {
            InputEvent::SpeechPartial(value) | InputEvent::SpeechFinal(value) => {
                value.language.as_deref()
            }
            _ => None,
        }?;
        self.languages
            .get(language)
            .or_else(|| {
                self.languages
                    .iter()
                    .find(|(tag, _)| {
                        language.starts_with(tag.as_str()) || tag.starts_with(language)
                    })
                    .map(|(_, engine)| engine)
            })
            .map(String::as_str)
    }
}

impl InputEngine for LanguageRouter {
    fn metadata(&self) -> EngineMetadata {
        let languages = self
            .languages
            .keys()
            .cloned()
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect();
        EngineMetadata {
            id: self.id.clone(),
            display_name: "Language Router".to_owned(),
            languages,
        }
    }

    fn create_session(&self, session: &SessionId) -> Result<(), EngineError> {
        self.engines
            .get(&self.default_engine)
            .expect("validated default engine")
            .create_session(session)?;
        self.sessions.lock().map_err(lock_error)?.insert(
            session.clone(),
            RouteSession {
                active_engine: self.default_engine.clone(),
            },
        );
        Ok(())
    }

    fn process(
        &self,
        session: &SessionId,
        event: &InputEvent,
        context: &InputContext,
    ) -> Result<ActionBatch, EngineError> {
        if let InputEvent::SwitchEngine(target) = event {
            return self.switch_engine(session, target);
        }
        if let Some(target) = self.route_for_event(event) {
            self.switch_engine(session, target)?;
        }
        let active = self
            .sessions
            .lock()
            .map_err(lock_error)?
            .get(session)
            .ok_or_else(|| EngineError {
                message: "router session not found".to_owned(),
            })?
            .active_engine
            .clone();
        self.engines
            .get(&active)
            .ok_or_else(|| EngineError {
                message: "active engine not found".to_owned(),
            })?
            .process(session, event, context)
    }

    fn apply_feedback(&self, event: &FeedbackEvent) -> Result<(), EngineError> {
        if let Some(language) = &event.language {
            if let Some(engine_id) = self.languages.get(language) {
                return self
                    .engines
                    .get(engine_id)
                    .expect("mapped engine exists")
                    .apply_feedback(event);
            }
        }
        self.engines
            .get(&self.default_engine)
            .expect("validated default engine")
            .apply_feedback(event)
    }

    fn close_session(&self, session: &SessionId) {
        if let Ok(mut sessions) = self.sessions.lock() {
            if let Some(route) = sessions.remove(session) {
                if let Some(engine) = self.engines.get(&route.active_engine) {
                    engine.close_session(session);
                }
            }
        }
    }
}

fn lock_error<T>(_: std::sync::PoisonError<T>) -> EngineError {
    EngineError {
        message: "language router state lock poisoned".to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ime_core::{InputContext, Key, KeyEvent, Runtime};

    struct EchoEngine {
        id: &'static str,
        language: &'static str,
    }

    struct FailingEngine;

    impl InputEngine for FailingEngine {
        fn metadata(&self) -> EngineMetadata {
            EngineMetadata {
                id: "fail".to_owned(),
                display_name: "Failing engine".to_owned(),
                languages: vec!["xx".to_owned()],
            }
        }
        fn create_session(&self, _: &SessionId) -> Result<(), EngineError> {
            Err(EngineError {
                message: "intentional startup failure".to_owned(),
            })
        }
        fn process(
            &self,
            _: &SessionId,
            _: &InputEvent,
            _: &InputContext,
        ) -> Result<ActionBatch, EngineError> {
            unreachable!("failed engine must never become active")
        }
        fn apply_feedback(&self, _: &FeedbackEvent) -> Result<(), EngineError> {
            Ok(())
        }
        fn close_session(&self, _: &SessionId) {}
    }

    impl InputEngine for EchoEngine {
        fn metadata(&self) -> EngineMetadata {
            EngineMetadata {
                id: self.id.to_owned(),
                display_name: self.id.to_owned(),
                languages: vec![self.language.to_owned()],
            }
        }
        fn create_session(&self, _: &SessionId) -> Result<(), EngineError> {
            Ok(())
        }
        fn process(
            &self,
            _: &SessionId,
            _: &InputEvent,
            _: &InputContext,
        ) -> Result<ActionBatch, EngineError> {
            Ok(ActionBatch(vec![Action::CommitText(self.id.to_owned())]))
        }
        fn apply_feedback(&self, _: &FeedbackEvent) -> Result<(), EngineError> {
            Ok(())
        }
        fn close_session(&self, _: &SessionId) {}
    }

    #[test]
    fn switches_active_engine_without_platform_session_recreation() {
        let en: Arc<dyn InputEngine> = Arc::new(EchoEngine {
            id: "en",
            language: "en",
        });
        let zh: Arc<dyn InputEngine> = Arc::new(EchoEngine {
            id: "zh",
            language: "zh-CN",
        });
        let router = LanguageRouter::new("bilingual", "en", vec![en, zh]).expect("router");
        let mut runtime = Runtime::new();
        runtime.register_engine(Arc::new(router));
        let session = runtime.create_session("bilingual").expect("session");
        let first = runtime
            .process(
                &session,
                &InputEvent::Key(KeyEvent::press(Key::Character('a'))),
                &InputContext::default(),
            )
            .expect("english");
        assert_eq!(first.committed_text(), Some("en"));
        runtime
            .process(
                &session,
                &InputEvent::SwitchEngine("zh".to_owned()),
                &InputContext::default(),
            )
            .expect("switch");
        let second = runtime
            .process(
                &session,
                &InputEvent::Key(KeyEvent::press(Key::Character('a'))),
                &InputContext::default(),
            )
            .expect("chinese");
        assert_eq!(second.committed_text(), Some("zh"));
    }

    #[test]
    fn failed_switch_keeps_the_previous_engine_active() {
        let en: Arc<dyn InputEngine> = Arc::new(EchoEngine {
            id: "en",
            language: "en",
        });
        let failing: Arc<dyn InputEngine> = Arc::new(FailingEngine);
        let router = LanguageRouter::new("router", "en", vec![en, failing]).expect("router");
        let mut runtime = Runtime::new();
        runtime.register_engine(Arc::new(router));
        let session = runtime.create_session("router").expect("session");
        assert!(runtime
            .process(
                &session,
                &InputEvent::SwitchEngine("fail".to_owned()),
                &InputContext::default(),
            )
            .is_err());
        let result = runtime
            .process(
                &session,
                &InputEvent::Key(KeyEvent::press(Key::Character('a'))),
                &InputContext::default(),
            )
            .expect("previous engine remains available");
        assert_eq!(result.committed_text(), Some("en"));
    }
}
