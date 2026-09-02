#!/usr/bin/env python3
"""IBus adapter for the Shurufa runtime C ABI."""

from __future__ import annotations

import ctypes
import json
import os
import pathlib
import signal
import sys

import gi

gi.require_version("IBus", "1.0")
gi.require_version("GLibUnix", "2.0")
from gi.repository import GLib, GLibUnix, IBus  # noqa: E402


class Runtime:
    def __init__(self) -> None:
        repository = pathlib.Path(__file__).resolve().parents[2]
        default_library = repository / "target" / "debug" / "libime_ffi.so"
        library_path = pathlib.Path(os.environ.get("SHURUFA_IME_LIBRARY", default_library))
        self.library = ctypes.CDLL(str(library_path))
        self.library.ime_runtime_new.restype = ctypes.c_void_p
        self.library.ime_runtime_new_with_data_dir.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
        self.library.ime_runtime_new_with_data_dir.restype = ctypes.c_void_p
        self.library.ime_runtime_free.argtypes = [ctypes.c_void_p]
        self.library.ime_runtime_feed_utf8.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        self.library.ime_runtime_switch_engine.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        self.library.ime_runtime_send_command.argtypes = [ctypes.c_void_p, ctypes.c_uint]
        self.library.ime_runtime_select_candidate.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        self.library.ime_runtime_last_actions_json.argtypes = [ctypes.c_void_p]
        self.library.ime_runtime_last_actions_json.restype = ctypes.c_char_p
        default_data = pathlib.Path(os.environ.get("XDG_DATA_HOME", pathlib.Path.home() / ".local" / "share")) / "shurufa" / "user"
        data_dir = pathlib.Path(os.environ.get("SHURUFA_USER_DATA_DIR", default_data))
        self.handle = self.library.ime_runtime_new_with_data_dir(
            b"bilingual", str(data_dir).encode("utf-8")
        )
        if not self.handle:
            raise RuntimeError("failed to create Shurufa runtime")
        if self.library.ime_runtime_switch_engine(self.handle, b"pinyin.reference") != 0:
            raise RuntimeError("failed to activate Pinyin engine")

    def close(self) -> None:
        if self.handle:
            self.library.ime_runtime_free(self.handle)
            self.handle = None

    def feed(self, text: str) -> list[object]:
        if self.library.ime_runtime_feed_utf8(self.handle, text.encode("utf-8")) != 0:
            raise RuntimeError("runtime rejected text")
        return self.actions()

    def command(self, command: int) -> list[object]:
        if self.library.ime_runtime_send_command(self.handle, command) != 0:
            raise RuntimeError("runtime rejected command")
        return self.actions()

    def select(self, candidate_id: str) -> list[object]:
        if self.library.ime_runtime_select_candidate(self.handle, candidate_id.encode("utf-8")) != 0:
            raise RuntimeError("runtime rejected candidate")
        return self.actions()

    def actions(self) -> list[object]:
        payload = self.library.ime_runtime_last_actions_json(self.handle)
        return json.loads(payload.decode("utf-8"))


class ShurufaEngine(IBus.Engine):
    __gtype_name__ = "ShurufaEngine"

    def __init__(self, **kwargs: object) -> None:
        super().__init__(**kwargs)
        self.runtime = Runtime()
        self.candidates: list[str] = []
        self.composing = False

    def do_destroy(self) -> None:
        self.runtime.close()
        super().destroy()

    def apply_actions(self, actions: list[object]) -> None:
        for action in actions:
            if not isinstance(action, dict):
                continue
            if "UpdateComposition" in action:
                composition = action["UpdateComposition"]
                text = "".join(segment["text"] for segment in composition["segments"])
                self.composing = bool(text)
                self.update_preedit_text(IBus.Text.new_from_string(text), len(text), self.composing)
            elif "ShowCandidates" in action:
                candidates = action["ShowCandidates"]
                self.candidates = [candidate["id"] for candidate in candidates]
                table = IBus.LookupTable.new(9, 0, True, False)
                for candidate in candidates:
                    display = candidate["display_text"]
                    annotation = candidate.get("annotation")
                    if annotation:
                        display = f"{display}  {annotation}"
                    table.append_candidate(IBus.Text.new_from_string(display))
                self.update_lookup_table(table, bool(candidates))
            elif "CommitText" in action:
                self.commit_text(IBus.Text.new_from_string(action["CommitText"]))
                self.composing = False
            elif "CloseComposition" in action:
                self.hide_preedit_text()
                self.hide_lookup_table()
                self.composing = False

    def do_process_key_event(self, keyval: int, keycode: int, state: int) -> bool:
        del keycode
        if state & IBus.ModifierType.RELEASE_MASK:
            return False
        blocked = IBus.ModifierType.CONTROL_MASK | IBus.ModifierType.MOD1_MASK | IBus.ModifierType.SUPER_MASK
        if state & blocked:
            return False
        if IBus.KEY_1 <= keyval <= IBus.KEY_9 and self.composing:
            index = keyval - IBus.KEY_1
            if index < len(self.candidates):
                self.apply_actions(self.runtime.select(self.candidates[index]))
                return True
        commands = {
            IBus.KEY_BackSpace: 0,
            IBus.KEY_Return: 1,
            IBus.KEY_KP_Enter: 1,
            IBus.KEY_space: 2,
            IBus.KEY_Escape: 3,
        }
        if keyval in commands:
            if not self.composing:
                return False
            self.apply_actions(self.runtime.command(commands[keyval]))
            return True
        codepoint = IBus.keyval_to_unicode(keyval)
        if codepoint and (chr(codepoint).isascii() and (chr(codepoint).isalpha() or chr(codepoint) == "'")):
            self.apply_actions(self.runtime.feed(chr(codepoint)))
            return True
        return False

    def do_reset(self) -> None:
        self.apply_actions(self.runtime.command(3))

    def do_candidate_clicked(self, index: int, button: int, state: int) -> None:
        del button, state
        if index < len(self.candidates):
            self.apply_actions(self.runtime.select(self.candidates[index]))


def main() -> int:
    IBus.init()
    bus = IBus.Bus.new()
    if not bus.is_connected():
        print("IBus session bus is unavailable", file=sys.stderr)
        return 1
    factory = IBus.Factory.new(bus.get_connection())
    factory.add_engine("shurufa", ShurufaEngine)
    if "--ibus" in sys.argv:
        bus.request_name("org.freedesktop.IBus.Shurufa", 0)
    else:
        component = IBus.Component.new(
            "org.freedesktop.IBus.Shurufa",
            "Shurufa multilingual input method",
            "0.1.0",
            "Apache-2.0",
            "Shurufa Contributors",
            "https://shurufa.dev",
            "",
            "shurufa",
        )
        component.add_engine(
            IBus.EngineDesc.new(
                "shurufa",
                "Shurufa Pinyin",
                "Privacy-first multilingual input",
                "zh_CN",
                "Apache-2.0",
                "Shurufa Contributors",
                "",
                "us",
            )
        )
        bus.register_component(component)
    loop = GLib.MainLoop()

    def stop() -> bool:
        loop.quit()
        return GLib.SOURCE_REMOVE

    GLibUnix.signal_add(GLib.PRIORITY_DEFAULT, signal.SIGINT, stop)
    GLibUnix.signal_add(GLib.PRIORITY_DEFAULT, signal.SIGTERM, stop)
    loop.run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
