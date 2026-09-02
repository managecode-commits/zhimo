//! Native librime backend enabled by the `native-librime` feature.

use std::ffi::{c_char, c_int, c_ulonglong, CString};

use crate::{RimeBackend, RimeSnapshot};

unsafe extern "C" {
    fn shurufa_rime_initialize(shared_dir: *const c_char, user_dir: *const c_char) -> c_int;
    fn shurufa_rime_finalize();
    fn shurufa_rime_create_session() -> c_ulonglong;
    fn shurufa_rime_destroy_session(session: c_ulonglong);
    fn shurufa_rime_process_key(session: c_ulonglong, keycode: c_int, modifiers: c_int) -> c_int;
    fn shurufa_rime_clear(session: c_ulonglong);
    fn shurufa_rime_select_candidate(session: c_ulonglong, index: usize) -> c_int;
    fn shurufa_rime_copy_preedit(
        session: c_ulonglong,
        output: *mut c_char,
        capacity: usize,
    ) -> usize;
    fn shurufa_rime_candidate_count(session: c_ulonglong) -> usize;
    fn shurufa_rime_copy_candidate(
        session: c_ulonglong,
        index: usize,
        comment: c_int,
        output: *mut c_char,
        capacity: usize,
    ) -> usize;
    fn shurufa_rime_take_commit(
        session: c_ulonglong,
        output: *mut c_char,
        capacity: usize,
    ) -> usize;
}

pub struct NativeRimeBackend;

impl Drop for NativeRimeBackend {
    fn drop(&mut self) {
        // SAFETY: each successful initialization owns one shim lifecycle reference.
        unsafe { shurufa_rime_finalize() }
    }
}

impl NativeRimeBackend {
    pub fn initialize(shared_dir: &str, user_dir: &str) -> Result<Self, String> {
        let shared = CString::new(shared_dir).map_err(|_| "shared directory contains NUL")?;
        let user = CString::new(user_dir).map_err(|_| "user directory contains NUL")?;
        // SAFETY: both strings are valid and retained for the duration of the call; the shim copies traits during initialization.
        if unsafe { shurufa_rime_initialize(shared.as_ptr(), user.as_ptr()) } == 0 {
            Err("librime initialization failed".to_owned())
        } else {
            Ok(Self)
        }
    }

    fn copy_string(copy: impl Fn(*mut c_char, usize) -> usize) -> String {
        let length = copy(std::ptr::null_mut(), 0);
        if length == 0 {
            return String::new();
        }
        let mut buffer = vec![0_u8; length + 1];
        copy(buffer.as_mut_ptr().cast(), buffer.len());
        String::from_utf8_lossy(&buffer[..length]).into_owned()
    }

    fn take_commit(session: u64) -> String {
        // `get_commit` consumes librime's pending commit, so this must be a
        // single call rather than the two-pass sizing used for stable context.
        let mut buffer = vec![0_u8; 64 * 1024];
        let length =
            unsafe { shurufa_rime_take_commit(session, buffer.as_mut_ptr().cast(), buffer.len()) };
        let copied = length.min(buffer.len().saturating_sub(1));
        String::from_utf8_lossy(&buffer[..copied]).into_owned()
    }

    fn snapshot(session: u64) -> RimeSnapshot {
        // SAFETY: session was created by the shim and buffers are managed by copy helpers.
        let preedit = Self::copy_string(|output, capacity| unsafe {
            shurufa_rime_copy_preedit(session, output, capacity)
        });
        let count = unsafe { shurufa_rime_candidate_count(session) };
        let candidates = (0..count)
            .map(|index| {
                let text = Self::copy_string(|output, capacity| unsafe {
                    shurufa_rime_copy_candidate(session, index, 0, output, capacity)
                });
                let comment = Self::copy_string(|output, capacity| unsafe {
                    shurufa_rime_copy_candidate(session, index, 1, output, capacity)
                });
                (text, (!comment.is_empty()).then_some(comment))
            })
            .collect();
        let commit = Self::take_commit(session);
        RimeSnapshot {
            preedit,
            candidates,
            commit: (!commit.is_empty()).then_some(commit),
        }
    }
}

impl RimeBackend for NativeRimeBackend {
    type Session = u64;
    fn create_session(&mut self) -> Result<Self::Session, String> {
        let session = unsafe { shurufa_rime_create_session() };
        (session != 0)
            .then_some(session)
            .ok_or_else(|| "librime did not create a session".to_owned())
    }
    fn destroy_session(&mut self, session: Self::Session) {
        unsafe { shurufa_rime_destroy_session(session) }
    }
    fn process_key(
        &mut self,
        session: Self::Session,
        keycode: i32,
        modifiers: i32,
    ) -> Result<RimeSnapshot, String> {
        unsafe { shurufa_rime_process_key(session, keycode, modifiers) };
        Ok(Self::snapshot(session))
    }
    fn select_candidate(
        &mut self,
        session: Self::Session,
        index: usize,
    ) -> Result<RimeSnapshot, String> {
        if unsafe { shurufa_rime_select_candidate(session, index) } == 0 {
            return Err("librime rejected candidate".to_owned());
        }
        Ok(Self::snapshot(session))
    }
    fn clear(&mut self, session: Self::Session) -> Result<RimeSnapshot, String> {
        unsafe { shurufa_rime_clear(session) };
        Ok(Self::snapshot(session))
    }
}
