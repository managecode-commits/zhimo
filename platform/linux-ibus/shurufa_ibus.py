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
        development_library = repository / "target" / "debug" / "libime_ffi.so"
        installed_library = pathlib.Path(__file__).resolve().parent.parent / "lib" / "libime_ffi.so"
        default_library = development_library if development_library.exists() else installed_library
        library_path = pathlib.Path(os.environ.get("SHURUFA_IME_LIBRARY", default_library))
        self.library = ctypes.CDLL(str(library_path))
        self.library.ime_runtime_abi_version.restype = ctypes.c_uint
        self.library.ime_runtime_capabilities.restype = ctypes.c_ulonglong
        self.library.ime_runtime_new.restype = ctypes.c_void_p
        self.library.ime_runtime_new_with_data_dir.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
        self.library.ime_runtime_new_with_data_dir.restype = ctypes.c_void_p
        self.library.ime_runtime_new_with_rime.argtypes = [
            ctypes.c_char_p,
            ctypes.c_char_p,
            ctypes.c_char_p,
            ctypes.c_char_p,
        ]
        self.library.ime_runtime_new_with_rime.restype = ctypes.c_void_p
        self.library.ime_runtime_free.argtypes = [ctypes.c_void_p]
        self.library.ime_runtime_feed_utf8.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        self.library.ime_runtime_switch_engine.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        self.library.ime_runtime_set_input_scope.argtypes = [ctypes.c_void_p, ctypes.c_uint]
        self.library.ime_runtime_set_privacy_policy.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
        self.library.ime_runtime_set_application_id.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        self.library.ime_runtime_send_command.argtypes = [ctypes.c_void_p, ctypes.c_uint]
        self.library.ime_runtime_select_candidate.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        self.library.ime_runtime_last_actions_json.argtypes = [ctypes.c_void_p]
        self.library.ime_runtime_last_actions_json.restype = ctypes.c_char_p
        if self.library.ime_runtime_abi_version() >> 16 != 1:
            raise RuntimeError("incompatible Shurufa Runtime ABI")
        if not self.library.ime_runtime_capabilities() & 1:
            raise RuntimeError("Shurufa Runtime does not support text input")
        default_data = pathlib.Path(os.environ.get("XDG_DATA_HOME", pathlib.Path.home() / ".local" / "share")) / "shurufa" / "user"
        data_dir = pathlib.Path(os.environ.get("SHURUFA_USER_DATA_DIR", default_data))
        shared_dir = os.environ.get("SHURUFA_RIME_SHARED_DIR")
        user_dir = os.environ.get("SHURUFA_RIME_USER_DIR")
        self.pinyin_engine = "pinyin.reference"
        if shared_dir and user_dir:
            self.handle = self.library.ime_runtime_new_with_rime(
                b"bilingual",
                str(data_dir).encode("utf-8"),
                shared_dir.encode("utf-8"),
                user_dir.encode("utf-8"),
            )
            self.pinyin_engine = "rime"
        else:
            self.handle = self.library.ime_runtime_new_with_data_dir(
                b"bilingual", str(data_dir).encode("utf-8")
            )
        if not self.handle:
            raise RuntimeError("failed to create Shurufa runtime")
        if self.library.ime_runtime_switch_engine(
            self.handle, self.pinyin_engine.encode("utf-8")
        ) != 0:
            raise RuntimeError("failed to activate Pinyin engine")

    def switch_engine(self, engine_id: str) -> list[object]:
        if self.library.ime_runtime_switch_engine(self.handle, engine_id.encode("utf-8")) != 0:
            raise RuntimeError("failed to switch input engine")
        return self.actions()

    def set_context(self, scope: int, learning_allowed: bool, application_id: str | None) -> None:
        if self.library.ime_runtime_set_input_scope(self.handle, scope) != 0:
            raise RuntimeError("failed to set input scope")
        if self.library.ime_runtime_set_privacy_policy(
            self.handle, int(learning_allowed), 0
        ) != 0:
            raise RuntimeError("failed to set privacy policy")
        encoded = application_id.encode("utf-8") if application_id else None
        if self.library.ime_runtime_set_application_id(self.handle, encoded) != 0:
            raise RuntimeError("failed to set application identity")

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
        try:
            self.runtime: Runtime | None = Runtime()
        except Exception as error:
            print(f"Shurufa Runtime unavailable; keys will pass through: {error}", file=sys.stderr)
            self.runtime = None
        self.candidates: list[str] = []
        self.lookup_table: IBus.LookupTable | None = None
        self.composing = False
        self.pinyin = True
        self.scope = 0
        self.learning_allowed = True
        self.application_id: str | None = None
        self.mode_property = IBus.Property.new(
            "mode",
            IBus.PropType.NORMAL,
            IBus.Text.new_from_string("中"),
            "",
            IBus.Text.new_from_string("切换中英文（Shift+Space）"),
            True,
            True,
            IBus.PropState.UNCHECKED,
            None,
        )
        properties = IBus.PropList()
        properties.append(self.mode_property)
        self.register_properties(properties)

    def do_destroy(self) -> None:
        if self.runtime:
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
                self.lookup_table = table
                self.update_lookup_table(table, bool(candidates))
            elif "CommitText" in action:
                self.commit_text(IBus.Text.new_from_string(action["CommitText"]))
                self.composing = False
            elif "CloseComposition" in action:
                self.hide_preedit_text()
                self.hide_lookup_table()
                self.composing = False

    def update_context(self) -> bool:
        if not self.runtime:
            return False
        try:
            self.runtime.set_context(self.scope, self.learning_allowed, self.application_id)
            return True
        except Exception as error:
            print(f"Shurufa context update failed: {error}", file=sys.stderr)
            return False

    def reset_composition(self) -> None:
        if self.runtime and self.composing:
            try:
                self.apply_actions(self.runtime.command(3))
            except Exception as error:
                print(f"Shurufa reset failed: {error}", file=sys.stderr)
        self.composing = False
        self.candidates = []
        self.lookup_table = None
        self.hide_preedit_text()
        self.hide_lookup_table()

    def toggle_mode(self) -> None:
        if not self.runtime or self.scope == 1:
            return
        self.reset_composition()
        target = "latin" if self.pinyin else self.runtime.pinyin_engine
        try:
            self.apply_actions(self.runtime.switch_engine(target))
            self.pinyin = not self.pinyin
            self.mode_property.set_label(IBus.Text.new_from_string("中" if self.pinyin else "英"))
            self.update_property(self.mode_property)
        except Exception as error:
            print(f"Shurufa engine switch failed: {error}", file=sys.stderr)

    def select_candidate_at(self, index: int) -> bool:
        if not self.runtime or index < 0 or index >= len(self.candidates):
            return False
        try:
            self.apply_actions(self.runtime.select(self.candidates[index]))
            return True
        except Exception as error:
            print(f"Shurufa candidate selection failed: {error}", file=sys.stderr)
            return False

    def do_process_key_event(self, keyval: int, keycode: int, state: int) -> bool:
        del keycode
        if state & IBus.ModifierType.RELEASE_MASK:
            return False
        if keyval == IBus.KEY_space and state & IBus.ModifierType.SHIFT_MASK:
            self.toggle_mode()
            return self.runtime is not None and self.scope != 1
        if not self.runtime or self.scope == 1 or not self.update_context():
            return False
        blocked = IBus.ModifierType.CONTROL_MASK | IBus.ModifierType.MOD1_MASK | IBus.ModifierType.SUPER_MASK
        if state & blocked:
            return False
        if IBus.KEY_1 <= keyval <= IBus.KEY_9 and self.composing:
            index = keyval - IBus.KEY_1
            if index < len(self.candidates):
                return self.select_candidate_at(index)
        if self.composing and keyval in (IBus.KEY_Up, IBus.KEY_Down, IBus.KEY_Page_Up, IBus.KEY_Page_Down):
            operations = {
                IBus.KEY_Up: self.do_cursor_up,
                IBus.KEY_Down: self.do_cursor_down,
                IBus.KEY_Page_Up: self.do_page_up,
                IBus.KEY_Page_Down: self.do_page_down,
            }
            operations[keyval]()
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
            try:
                character = chr(codepoint)
                self.apply_actions(self.runtime.feed(character.lower() if self.pinyin else character))
                return True
            except Exception as error:
                print(f"Shurufa key processing failed: {error}", file=sys.stderr)
                self.reset_composition()
                return False
        return False

    def do_reset(self) -> None:
        self.reset_composition()

    def do_candidate_clicked(self, index: int, button: int, state: int) -> None:
        del button, state
        if index < len(self.candidates):
            self.select_candidate_at(index)

    def do_cursor_up(self) -> None:
        if self.lookup_table and self.lookup_table.cursor_up():
            self.update_lookup_table(self.lookup_table, True)

    def do_cursor_down(self) -> None:
        if self.lookup_table and self.lookup_table.cursor_down():
            self.update_lookup_table(self.lookup_table, True)

    def do_page_up(self) -> None:
        if self.lookup_table and self.lookup_table.page_up():
            self.update_lookup_table(self.lookup_table, True)

    def do_page_down(self) -> None:
        if self.lookup_table and self.lookup_table.page_down():
            self.update_lookup_table(self.lookup_table, True)

    def do_property_activate(self, prop_name: str, prop_state: int) -> None:
        del prop_state
        if prop_name == "mode":
            self.toggle_mode()

    def do_set_content_type(self, purpose: int, hints: int) -> None:
        private = bool(hints & (IBus.InputHints.PRIVATE | IBus.InputHints.HIDDEN_TEXT))
        if purpose in (IBus.InputPurpose.PASSWORD, IBus.InputPurpose.PIN) or private:
            scope = 1
        elif purpose == IBus.InputPurpose.EMAIL:
            scope = 2
        elif purpose == IBus.InputPurpose.URL:
            scope = 3
        elif purpose == IBus.InputPurpose.TERMINAL:
            scope = 4
        else:
            scope = 0
        if scope != self.scope and self.composing:
            self.reset_composition()
        self.scope = scope
        self.learning_allowed = scope != 1 and not private
        self.update_context()

    def do_focus_in_id(self, object_path: str, client: str) -> None:
        del object_path
        self.application_id = client or None
        self.update_context()

    def do_focus_in(self) -> None:
        self.update_context()

    def do_focus_out_id(self, object_path: str) -> None:
        del object_path
        self.reset_composition()

    def do_focus_out(self) -> None:
        self.reset_composition()


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
