#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""IBus adapter for the Zhimo runtime C ABI."""

from __future__ import annotations

import ctypes
import json
import os
import pathlib
import signal
import sys

import gi

gi.require_version("IBus", "1.0")
from gi.repository import GLib, IBus  # noqa: E402
try:
    gi.require_version("GLibUnix", "2.0")
except ValueError:
    # Older GLib typelibs expose this function directly on GLib.
    unix_signal_add = GLib.unix_signal_add
else:
    from gi.repository import GLibUnix
    # Ubuntu 24.04 has GLibUnix but only exposes signal_add_full there.
    unix_signal_add = getattr(GLibUnix, "signal_add", None) or GLib.unix_signal_add


class Runtime:
    def __init__(self) -> None:
        repository = pathlib.Path(__file__).resolve().parents[2]
        development_library = repository / "target" / "debug" / "libime_ffi.so"
        installed_library = pathlib.Path(__file__).resolve().parent.parent / "lib" / "libime_ffi.so"
        if not installed_library.exists():
            import sysconfig
            installed_library = pathlib.Path("/usr/lib") / (sysconfig.get_config_var("MULTIARCH") or "") / "zhimo/libime_ffi.so"
        default_library = development_library if development_library.exists() else installed_library
        library_path = pathlib.Path(os.environ.get("ZHIMO_IME_LIBRARY", default_library))
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
            raise RuntimeError("incompatible Zhimo Runtime ABI")
        if not self.library.ime_runtime_capabilities() & 1:
            raise RuntimeError("Zhimo Runtime does not support text input")
        default_data = pathlib.Path(os.environ.get("XDG_DATA_HOME", pathlib.Path.home() / ".local" / "share")) / "zhimo" / "user"
        data_dir = pathlib.Path(os.environ.get("ZHIMO_USER_DATA_DIR", default_data))
        shared_dir = os.environ.get("ZHIMO_RIME_SHARED_DIR")
        user_dir = os.environ.get("ZHIMO_RIME_USER_DIR")
        if shared_dir is None and user_dir is None and self.library.ime_runtime_capabilities() & (1 << 4):
            packaged = pathlib.Path("/usr/share/zhimo/rime")
            if (packaged / "zhimo_pinyin.schema.yaml").is_file():
                shared_dir, user_dir = str(packaged), str(data_dir / "rime")
        self.pinyin_engine = "pinyin.reference"
        if shared_dir and user_dir:
            pathlib.Path(user_dir).mkdir(parents=True, exist_ok=True)
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
            raise RuntimeError("failed to create Zhimo runtime")
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


class ZhimoEngine(IBus.Engine):
    __gtype_name__ = "ZhimoEngine"

    def __init__(self, **kwargs: object) -> None:
        super().__init__(**kwargs)
        try:
            self.runtime: Runtime | None = Runtime()
        except Exception as error:
            print(f"Zhimo Runtime unavailable; keys will pass through: {error}", file=sys.stderr)
            self.runtime = None
        self.candidates: list[str] = []
        self.lookup_table: IBus.LookupTable | None = None
        self.composing = False
        self.pinyin = True
        self.caps_lock = False
        self.scope = 0
        self.handwriting = None
        self.focused = False
        self.learning_allowed = True
        self.application_id: str | None = None
        self.mode_property = IBus.Property.new(
            "InputMode",
            IBus.PropType.NORMAL,
            IBus.Text.new_from_string("中"),
            "",
            IBus.Text.new_from_string("切换中英文（Shift+Space）"),
            True,
            True,
            IBus.PropState.UNCHECKED,
            None,
        )
        self.mode_properties = IBus.PropList()
        self.mode_properties.append(self.mode_property)
        for key, label in (("Handwriting", "手写输入"), ("Speech", "离线语音输入")):
            self.mode_properties.append(IBus.Property.new(key, IBus.PropType.NORMAL,
                IBus.Text.new_from_string(label), "", IBus.Text.new_from_string(label),
                True, True, IBus.PropState.UNCHECKED, None))
        self.publish_mode(register=True)

    def publish_mode(self, register: bool = False) -> None:
        label = "A" if self.caps_lock else "中" if self.pinyin else "英"
        self.mode_property.set_label(IBus.Text.new_from_string("大写锁定 · 英文直输" if self.caps_lock else "中文拼音" if self.pinyin else "英文直输"))
        self.mode_property.set_symbol(IBus.Text.new_from_string(label))
        self.mode_property.set_tooltip(IBus.Text.new_from_string(f"当前：{label}；点击或 Shift+Space 切换中英文；Caps Lock 独立控制大写"))
        self.mode_property.set_sensitive(self.runtime is not None and self.scope != 1)
        if register:
            self.register_properties(self.mode_properties)
        self.update_property(self.mode_property)

    def do_enable(self) -> None:
        self.publish_mode(register=True)

    def do_destroy(self) -> None:
        self.close_handwriting()
        if self.runtime:
            self.runtime.close()
        super().destroy()

    def apply_actions(self, actions: list[object]) -> None:
        for action in actions:
            if action == "CloseComposition":
                self.hide_preedit_text()
                self.hide_lookup_table()
                self.composing = False
                self.candidates = []
                self.lookup_table = None
                continue
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
                self.candidates = []
                self.lookup_table = None
                self.hide_lookup_table()
            elif "CloseComposition" in action:
                self.hide_preedit_text()
                self.hide_lookup_table()
                self.composing = False
                self.candidates = []
                self.lookup_table = None

    def update_context(self) -> bool:
        if not self.runtime:
            return False
        try:
            self.runtime.set_context(self.scope, self.learning_allowed, self.application_id)
            return True
        except Exception as error:
            print(f"Zhimo context update failed: {error}", file=sys.stderr)
            return False

    def reset_composition(self) -> None:
        if self.runtime and self.composing:
            try:
                self.apply_actions(self.runtime.command(3))
            except Exception as error:
                print(f"Zhimo reset failed: {error}", file=sys.stderr)
        self.composing = False
        self.candidates = []
        self.lookup_table = None
        self.hide_preedit_text()
        self.hide_lookup_table()

    def toggle_mode(self) -> None:
        self.close_handwriting()
        if not self.runtime or self.scope == 1:
            return
        self.reset_composition()
        target = "latin" if self.pinyin else self.runtime.pinyin_engine
        try:
            self.apply_actions(self.runtime.switch_engine(target))
            self.pinyin = not self.pinyin
            self.publish_mode()
        except Exception as error:
            print(f"Zhimo engine switch failed: {error}", file=sys.stderr)

    def select_candidate_at(self, index: int) -> bool:
        if not self.runtime or index < 0 or index >= len(self.candidates):
            return False
        try:
            self.apply_actions(self.runtime.select(self.candidates[index]))
            return True
        except Exception as error:
            print(f"Zhimo candidate selection failed: {error}", file=sys.stderr)
            return False

    def do_process_key_event(self, keyval: int, keycode: int, state: int) -> bool:
        del keycode
        released = bool(state & IBus.ModifierType.RELEASE_MASK)
        caps = bool(state & IBus.ModifierType.LOCK_MASK)
        if keyval == IBus.KEY_Caps_Lock and not released:
            # Do not infer whether this backend reports pre- or post-toggle state.
            # Refresh from the release/next event's actual modifier mask instead.
            caps = self.caps_lock
        if caps != self.caps_lock:
            self.caps_lock = caps
            self.publish_mode()
        if state & IBus.ModifierType.RELEASE_MASK:
            return False
        if keyval in (IBus.KEY_h, IBus.KEY_H, IBus.KEY_F10) and state & IBus.ModifierType.CONTROL_MASK and state & IBus.ModifierType.SHIFT_MASK and not state & (IBus.ModifierType.MOD1_MASK | IBus.ModifierType.SUPER_MASK):
            if self.scope == 1 or not self.runtime or not self.focused:
                return False
            if self.handwriting and not self.handwriting.closed:
                self.close_handwriting()
            else:
                self.open_desktop_panel("speech" if keyval == IBus.KEY_F10 else "handwriting")
            return True
        self.close_handwriting()
        blocked = IBus.ModifierType.CONTROL_MASK | IBus.ModifierType.MOD1_MASK | IBus.ModifierType.SUPER_MASK
        if state & blocked:
            return False
        if keyval == IBus.KEY_space and state & IBus.ModifierType.SHIFT_MASK:
            self.toggle_mode()
            return self.runtime is not None and self.scope != 1
        if not self.runtime or self.scope == 1 or not self.update_context():
            return False
        # English mode uses the application's native keyboard handling, including
        # case, punctuation, numeric keypad and shortcuts; no word auto-replacement.
        if not self.pinyin:
            return False
        codepoint = IBus.keyval_to_unicode(keyval)
        character = codepoint if isinstance(codepoint, str) else (chr(codepoint) if codepoint else "")
        direct = self.caps_lock or (bool(state & IBus.ModifierType.SHIFT_MASK) and character.isascii() and character.isalpha())
        if keyval == IBus.KEY_Caps_Lock or direct:
            if self.composing:
                try:
                    # Commit only the original spelling; let the host insert this key.
                    self.apply_actions(self.runtime.command(1))
                except Exception as error:
                    print(f"Zhimo raw spelling commit failed: {error}", file=sys.stderr)
                    return keyval != IBus.KEY_Caps_Lock
            return False
        if IBus.KEY_1 <= keyval <= IBus.KEY_9 and self.composing:
            index = keyval - IBus.KEY_1
            if self.lookup_table:
                size = self.lookup_table.get_page_size()
                index += (self.lookup_table.get_cursor_pos() // size) * size
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
            if keyval == IBus.KEY_space and self.lookup_table and self.candidates:
                return self.select_candidate_at(self.lookup_table.get_cursor_pos())
            self.apply_actions(self.runtime.command(commands[keyval]))
            return True
        codepoint = IBus.keyval_to_unicode(keyval)
        character = codepoint if isinstance(codepoint, str) else (chr(codepoint) if codepoint else "")
        if character and self.composing and character.isprintable() and not (character.isascii() and (character.isalpha() or character == "'")):
            # Finish the selected word before forwarding the original punctuation
            # or digit. Never leave a stale preedit behind literal characters.
            if self.lookup_table and self.candidates:
                self.select_candidate_at(self.lookup_table.get_cursor_pos())
            else:
                self.apply_actions(self.runtime.command(2))
            return False
        if character and (character.isascii() and (character.isalpha() or character == "'")):
            try:
                self.apply_actions(self.runtime.feed(character.lower() if self.pinyin else character))
                return True
            except Exception as error:
                print(f"Zhimo key processing failed: {error}", file=sys.stderr)
                self.reset_composition()
                return False
        return False

    def do_reset(self) -> None:
        self.close_handwriting()
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
        if prop_name in ("mode", "InputMode"):
            self.toggle_mode()
        elif prop_name in ("Handwriting", "Speech"):
            self.open_desktop_panel("handwriting" if prop_name == "Handwriting" else "speech")

    def open_desktop_panel(self, mode):
        self.close_handwriting()
        if self.scope == 1 or not self.runtime or not self.focused: return
        if self.composing: self.reset_composition()
        try:
            from desktop_client import DesktopClient
            self.handwriting = DesktopClient(mode, self.commit_handwriting,
                lambda: self.focused and self.scope != 1 and self.runtime is not None)
        except Exception:
            print("Zhimo desktop panel unavailable; check installation", file=sys.stderr)

    def do_set_content_type(self, purpose: int, hints: int) -> None:
        self.close_handwriting()
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
        self.publish_mode()

    def do_focus_in_id(self, object_path: str, client: str) -> None:
        self.close_handwriting()
        self.focused = True
        del object_path
        self.application_id = client or None
        self.update_context()
        self.publish_mode(register=True)

    def do_focus_in(self) -> None:
        self.close_handwriting()
        self.focused = True
        self.update_context()
        self.publish_mode(register=True)

    def do_focus_out_id(self, object_path: str) -> None:
        self.focused = False
        self.close_handwriting()
        del object_path
        self.reset_composition()

    def do_focus_out(self) -> None:
        self.focused = False
        self.close_handwriting()
        self.reset_composition()

    def close_handwriting(self) -> None:
        if self.handwriting:
            self.handwriting.close()
            self.handwriting = None

    def commit_handwriting(self, text: str) -> bool:
        if not self.focused or self.scope == 1:
            return False
        self.commit_text(IBus.Text.new_from_string(text))
        return True


def main() -> int:
    IBus.init()
    bus = IBus.Bus.new()
    if not bus.is_connected():
        print("IBus session bus is unavailable", file=sys.stderr)
        return 1
    factory = IBus.Factory.new(bus.get_connection())
    factory.add_engine("zhimo", ZhimoEngine)
    if "--ibus" in sys.argv:
        bus.request_name("org.freedesktop.IBus.Zhimo", 0)
    else:
        component = IBus.Component.new(
            "org.freedesktop.IBus.Zhimo",
            "Zhimo multilingual input method",
            "0.1.0",
            "Apache-2.0",
            "Zhimo Contributors",
            "https://github.com/managecode-commits/zhimo",
            "",
            "zhimo",
        )
        component.add_engine(
            IBus.EngineDesc.new(
                "zhimo",
                "Zhimo Pinyin (知墨输入法)",
                "Privacy-first multilingual input",
                "zh_CN",
                "Apache-2.0",
                "Zhimo Contributors",
                "",
                "us",
            )
        )
        bus.register_component(component)
    loop = GLib.MainLoop()

    def stop() -> bool:
        loop.quit()
        return GLib.SOURCE_REMOVE

    unix_signal_add(GLib.PRIORITY_DEFAULT, signal.SIGINT, stop)
    unix_signal_add(GLib.PRIORITY_DEFAULT, signal.SIGTERM, stop)
    loop.run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
