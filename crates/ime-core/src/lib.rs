// Copyright © 2026 立方田 <managecode@gmail.com>
//! Language-neutral input runtime primitives.

mod runtime;
mod types;
mod unicode;

pub use runtime::{InputEngine, Runtime, RuntimeError};
pub use types::*;
pub use unicode::*;
