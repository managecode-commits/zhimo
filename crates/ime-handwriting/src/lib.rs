// Copyright © 2026 立方田 <managecode@gmail.com>
//! Portable ink contract, offline model inference and stale-result protection.
//! No network or editor access.

mod shape_ranking;
pub mod zinnia;

#[derive(Clone, Copy, Debug, PartialEq, serde::Deserialize, serde::Serialize)]
pub struct Point {
    pub x: f32,
    pub y: f32,
    pub time_ms: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Ticket {
    pub session: u64,
    pub revision: u64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Request {
    pub ticket: Ticket,
    pub language: String,
    pub width: f32,
    pub height: f32,
    pub strokes: Vec<Vec<Point>>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Capabilities {
    pub languages: Vec<String>,
    pub offline: bool,
    pub single_line: bool,
    pub overlapping: bool,
}

/// Provider polling must not block the input thread. Model installation is explicit and separate.
pub trait Provider {
    fn capabilities(&self) -> Capabilities;
    fn recognize(&mut self, request: Request) -> Result<(), String>;
    fn poll(&mut self) -> Vec<(Ticket, Result<Vec<String>, String>)>;
    fn cancel(&mut self, session: u64);
}

pub struct Session {
    ticket: Ticket,
    active: bool,
    strokes: Vec<Vec<Point>>,
    candidates: Vec<String>,
}

impl Session {
    /// Caller supplies a unique ID for each editor lifetime. Password sessions stay disabled.
    #[must_use]
    pub fn new(id: u64, password: bool) -> Self {
        Self {
            ticket: Ticket {
                session: id,
                revision: 0,
            },
            active: !password,
            strokes: Vec::new(),
            candidates: Vec::new(),
        }
    }

    /// Call on pointer DOWN as well as completed strokes, so partial new ink invalidates old results.
    pub fn invalidate(&mut self) {
        if let Some(next) = self.ticket.revision.checked_add(1) {
            self.ticket.revision = next;
        } else {
            self.active = false;
        }
        self.candidates.clear();
    }

    pub fn add(&mut self, stroke: Vec<Point>) -> bool {
        if !self.active
            || self.strokes.len() >= 128
            || stroke.is_empty()
            || stroke.len() > 4096
            || stroke
                .iter()
                .any(|p| !p.x.is_finite() || !p.y.is_finite() || p.x < 0.0 || p.y < 0.0)
            || stroke.windows(2).any(|p| p[0].time_ms > p[1].time_ms)
        {
            return false;
        }
        self.strokes.push(stroke);
        self.invalidate();
        true
    }

    pub fn undo(&mut self) {
        self.strokes.pop();
        self.invalidate();
    }
    pub fn clear(&mut self) {
        self.strokes.clear();
        self.invalidate();
    }
    pub fn close(&mut self) {
        self.clear();
        self.active = false;
    }

    #[must_use]
    pub fn request(&self, language: &str, width: f32, height: f32) -> Option<Request> {
        if !self.active
            || self.strokes.is_empty()
            || language.is_empty()
            || !width.is_finite()
            || !height.is_finite()
            || width <= 0.0
            || height <= 0.0
        {
            return None;
        }
        Some(Request {
            ticket: self.ticket,
            language: language.to_owned(),
            width,
            height,
            strokes: self.strokes.clone(),
        })
    }

    pub fn accept(&mut self, ticket: Ticket, candidates: Vec<String>) -> bool {
        if !self.active || ticket != self.ticket {
            return false;
        }
        self.candidates.clear();
        for text in candidates {
            if !text.trim().is_empty()
                && text.encode_utf16().count() <= 256
                && !text.chars().any(char::is_control)
                && !self.candidates.contains(&text)
            {
                self.candidates.push(text);
                if self.candidates.len() == 20 {
                    break;
                }
            }
        }
        true
    }

    /// Only clear after the platform confirms successful submission. Does not mutate the editor.
    #[must_use]
    pub fn candidate(&self, ticket: Ticket, index: usize) -> Option<&str> {
        if self.active && ticket == self.ticket {
            self.candidates.get(index).map(String::as_str)
        } else {
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn stroke() -> Vec<Point> {
        vec![Point {
            x: 1.0,
            y: 2.0,
            time_ms: 1,
        }]
    }
    #[test]
    fn stale_and_cross_editor_results_rejected() {
        let mut session = Session::new(1, false);
        assert!(session.add(stroke()));
        let request = session.request("zh-Hani-CN", 100.0, 100.0).unwrap();
        assert!(session.accept(request.ticket, vec!["中".into()]));
        assert_eq!(session.candidate(request.ticket, 0), Some("中"));
        session.invalidate();
        assert!(!session.accept(request.ticket, vec!["旧".into()]));
        assert_eq!(session.candidate(request.ticket, 0), None);
        let mut other = Session::new(2, false);
        other.add(stroke());
        assert!(!other.accept(request.ticket, vec!["旧".into()]));
    }
    #[test]
    fn password_closed_invalid_and_bounded_ink() {
        assert!(!Session::new(1, true).add(stroke()));
        let mut session = Session::new(2, false);
        assert!(!session.add(vec![]));
        assert!(!session.add(vec![Point {
            x: f32::NAN,
            y: 0.0,
            time_ms: 0
        }]));
        for _ in 0..128 {
            assert!(session.add(stroke()));
        }
        assert!(!session.add(stroke()));
        assert!(session.request("en", 0.0, 1.0).is_none());
        session.close();
        assert!(!session.add(stroke()));
    }
    #[test]
    fn unicode_undo_and_confirmation() {
        let mut session = Session::new(1, false);
        session.add(stroke());
        session.add(stroke());
        session.undo();
        let request = session.request("zh", 1.0, 1.0).unwrap();
        assert_eq!(request.strokes.len(), 1);
        session.accept(request.ticket, vec!["𠮷".into(), "𠮷".into(), "\n".into()]);
        assert_eq!(session.candidate(request.ticket, 0), Some("𠮷"));
        assert_eq!(session.candidate(request.ticket, 1), None);
        session.clear();
        assert_eq!(session.candidate(request.ticket, 0), None);
    }
}
