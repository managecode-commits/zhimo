#!/usr/bin/env python3
"""End-to-end smoke test for the Linux adapter and shared Runtime."""

from __future__ import annotations

import os
import pathlib
import tempfile

from shurufa_ibus import Runtime


def main() -> int:
    repository = pathlib.Path(__file__).resolve().parents[2]
    os.environ["SHURUFA_IME_LIBRARY"] = str(repository / "target/debug/libime_ffi.so")
    with tempfile.TemporaryDirectory(prefix="shurufa-ibus-smoke-") as data_directory:
        os.environ["SHURUFA_USER_DATA_DIR"] = data_directory
        runtime = Runtime()
        runtime.set_context(1, False, "smoke.password")
        actions = runtime.feed("ni")
        candidates = next(action["ShowCandidates"] for action in actions if "ShowCandidates" in action)
        assert candidates
        runtime.command(3)
        runtime.set_context(0, True, "smoke.editor")
        runtime.switch_engine("latin")
        actions = runtime.feed("hel")
        candidates = next(action["ShowCandidates"] for action in actions if "ShowCandidates" in action)
        assert any(candidate["display_text"] == "hello" for candidate in candidates)
        runtime.close()
    print("IBus Runtime smoke test passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
