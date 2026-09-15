// Copyright © 2026 立方田 <managecode@gmail.com>
//! Single writer per runtime, with one coalesced full snapshot, not an unbounded queue.
use ime_data::{LearningRecord, SqliteStore};
use std::path::PathBuf;
use std::sync::{Arc, Condvar, Mutex};
use std::thread::JoinHandle;

#[derive(Default)]
struct State {
    pending: Option<Vec<LearningRecord>>,
    busy: bool,
    failed: bool,
    stop: bool,
}

pub struct LearningWriter {
    shared: Arc<(Mutex<State>, Condvar)>,
    worker: Option<JoinHandle<()>>,
}

impl LearningWriter {
    pub fn new(path: PathBuf) -> std::io::Result<Self> {
        let shared = Arc::new((Mutex::new(State::default()), Condvar::new()));
        let state = Arc::clone(&shared);
        let worker = std::thread::Builder::new()
            .name("zhimo-learning".into())
            .spawn(move || {
                let store = SqliteStore::new(path);
                let (lock, wake) = &*state;
                loop {
                    let mut state = lock
                        .lock()
                        .unwrap_or_else(std::sync::PoisonError::into_inner);
                    while state.pending.is_none() && !state.stop {
                        state = wake
                            .wait(state)
                            .unwrap_or_else(std::sync::PoisonError::into_inner);
                    }
                    let Some(records) = state.pending.take() else {
                        break;
                    };
                    state.busy = true;
                    drop(state);
                    let failed = store.merge_save(&records).is_err();
                    let mut state = lock
                        .lock()
                        .unwrap_or_else(std::sync::PoisonError::into_inner);
                    state.failed = failed;
                    state.busy = false;
                    // The runtime retains the full learning model on failure.
                    // A later submit or explicit synchronous flush retries it.
                    wake.notify_all();
                }
            })?;
        Ok(Self {
            shared,
            worker: Some(worker),
        })
    }

    pub fn submit(&self, records: Vec<LearningRecord>) {
        let (lock, wake) = &*self.shared;
        let mut state = lock
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        state.pending = Some(records);
        wake.notify_one();
    }

    /// 0 idle/saved, 1 pending, -2 last attempt failed (records remain in runtime).
    pub fn status(&self) -> i32 {
        let state = self
            .shared
            .0
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if state.pending.is_some() || state.busy {
            1
        } else if state.failed {
            -2
        } else {
            0
        }
    }

    pub fn wait(&self) {
        let (lock, wake) = &*self.shared;
        let mut state = lock
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        while state.pending.is_some() || state.busy {
            state = wake
                .wait(state)
                .unwrap_or_else(std::sync::PoisonError::into_inner);
        }
    }

    pub fn acknowledge_sync_save(&self, failed: bool) {
        self.shared
            .0
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .failed = failed;
    }
}

impl Drop for LearningWriter {
    fn drop(&mut self) {
        let (lock, wake) = &*self.shared;
        lock.lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .stop = true;
        wake.notify_one();
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ime_data::LearningModel;

    #[test]
    fn coalesces_snapshots_and_preserves_the_latest_selection() {
        let directory = std::env::temp_dir().join(format!("zhimo-writer-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        let path = directory.join("learning.sqlite3");
        let writer = LearningWriter::new(path.clone()).unwrap();
        let mut model = LearningModel::new("test", "zh-CN", "device");
        for _ in 0..100 {
            model.selected("ni", "你");
            writer.submit(model.records());
        }
        writer.wait();
        assert_eq!(writer.status(), 0);
        assert_eq!(
            SqliteStore::new(path).load().unwrap()[0].positive_count,
            100
        );
        drop(writer);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn reports_failure_and_retries_without_losing_runtime_snapshot() {
        let directory =
            std::env::temp_dir().join(format!("zhimo-writer-fail-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        let blocker = directory.join("blocked");
        std::fs::write(&blocker, b"not a directory").unwrap();
        let writer = LearningWriter::new(blocker.join("learning.sqlite3")).unwrap();
        let mut model = LearningModel::new("test", "zh-CN", "device");
        model.selected("ni", "你");
        writer.submit(model.records());
        writer.wait();
        assert_eq!(writer.status(), -2);
        std::fs::remove_file(&blocker).unwrap();
        writer.submit(model.records());
        writer.wait();
        assert_eq!(writer.status(), 0);
        drop(writer);
        std::fs::remove_dir_all(directory).unwrap();
    }
}
