// Copyright © 2026 立方田 <managecode@gmail.com>
//! Model handles are caller-owned and must be serialized by the host's worker.
use ime_handwriting::{
    zinnia::{Model, MODEL_SIZE},
    Point,
};
use std::ffi::{c_char, CStr, CString};
use std::ptr;

pub struct ImeHandwritingHandle {
    model: Model,
    result: CString,
}

#[derive(serde::Deserialize)]
struct Ink {
    width: f32,
    height: f32,
    strokes: Vec<Vec<Point>>,
}

/// `path` is a valid NUL-terminated UTF-8 filename. Only the pinned model is accepted.
#[no_mangle]
pub unsafe extern "C" fn ime_handwriting_new(path: *const c_char) -> *mut ImeHandwritingHandle {
    if path.is_null() {
        return ptr::null_mut();
    }
    let Ok(path) = CStr::from_ptr(path).to_str() else {
        return ptr::null_mut();
    };
    let Ok(metadata) = std::fs::metadata(path) else {
        return ptr::null_mut();
    };
    if metadata.len() != MODEL_SIZE as u64 {
        return ptr::null_mut();
    }
    let Ok(bytes) = std::fs::read(path) else {
        return ptr::null_mut();
    };
    let Ok(model) = Model::bundled(&bytes) else {
        return ptr::null_mut();
    };
    Box::into_raw(Box::new(ImeHandwritingHandle {
        model,
        result: CString::default(),
    }))
}

#[no_mangle]
pub unsafe extern "C" fn ime_handwriting_free(handle: *mut ImeHandwritingHandle) {
    if !handle.is_null() {
        drop(Box::from_raw(handle));
    }
}

/// Result is UTF-8 JSON [{text,score}], valid until next call/free. Null means invalid request.
/// Never commits text, reads editor context, or stores ink. Host owns session/revision protection.
#[no_mangle]
pub unsafe extern "C" fn ime_handwriting_recognize_json(
    handle: *mut ImeHandwritingHandle,
    ink: *const c_char,
) -> *const c_char {
    let Some(handle) = handle.as_mut() else {
        return ptr::null();
    };
    if ink.is_null() {
        return ptr::null();
    }
    let bytes = CStr::from_ptr(ink).to_bytes();
    if bytes.len() > 4 * 1024 * 1024 {
        return ptr::null();
    }
    let Ok(ink) = serde_json::from_slice::<Ink>(bytes) else {
        return ptr::null();
    };
    let Ok(candidates) =
        handle
            .model
            .recognize_normalized(&ink.strokes, ink.width, ink.height, 100)
    else {
        return ptr::null();
    };
    let Ok(result) = serde_json::to_string(&candidates)
        .and_then(|s| CString::new(s).map_err(serde::ser::Error::custom))
    else {
        return ptr::null();
    };
    handle.result = result;
    handle.result.as_ptr()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bundled_model_c_abi_roundtrip() {
        let path = CString::new(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../models/handwriting/zh-cn/handwriting-zh_CN.model"
        ))
        .unwrap();
        unsafe {
            assert!(ime_handwriting_new(ptr::null()).is_null());
            let handle = ime_handwriting_new(path.as_ptr());
            assert!(!handle.is_null());
            let ink = CString::new(r#"{"width":1000,"height":1000,"strokes":[[{"x":100,"y":500,"time_ms":0},{"x":900,"y":500,"time_ms":1}]]}"#).unwrap();
            let result = ime_handwriting_recognize_json(handle, ink.as_ptr());
            assert!(!result.is_null());
            let result: serde_json::Value =
                serde_json::from_slice(CStr::from_ptr(result).to_bytes()).unwrap();
            assert_eq!(result[0]["text"], "一");
            let bad = CString::new("{}").unwrap();
            assert!(ime_handwriting_recognize_json(handle, bad.as_ptr()).is_null());
            ime_handwriting_free(handle);
            ime_handwriting_free(ptr::null_mut());
        }
    }
}
