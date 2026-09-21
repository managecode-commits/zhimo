#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Run real IBus key-routing methods with a fake editor/runtime; no desktop registration."""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("zhimo_case_ibus", ROOT / "platform/linux-ibus/zhimo_ibus.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
IBus = module.IBus
SHIFT = IBus.ModifierType.SHIFT_MASK
LOCK = IBus.ModifierType.LOCK_MASK
RELEASE = IBus.ModifierType.RELEASE_MASK


class CaseRouting(unittest.TestCase):
    def setUp(self):
        self.operations = []
        self.labels = []
        self.engine = SimpleNamespace(
            caps_lock=False, pinyin=True, composing=False, scope=0,
            focused=True, handwriting=None, candidates=[], lookup_table=None,
            runtime=SimpleNamespace(command=self.command, feed=self.feed),
            close_handwriting=lambda: None, update_context=lambda: True,
            apply_actions=lambda actions: None,
            publish_mode=lambda: self.labels.append(self.engine.caps_lock),
            toggle_mode=self.toggle,
        )

    def toggle(self):
        self.engine.pinyin = not self.engine.pinyin

    def command(self, value):
        self.operations.append(("command", value))
        self.engine.composing = False
        return []

    def feed(self, value):
        self.operations.append(("feed", value))
        return []

    def key(self, key, flags=0):
        return module.ZhimoEngine.do_process_key_event(self.engine, key, 0, flags)

    def test_caps_transition_preserves_base_language_and_original_spelling(self):
        self.engine.composing = True
        self.assertFalse(self.key(IBus.KEY_Caps_Lock))
        self.assertEqual(self.operations, [("command", 1)])
        self.assertTrue(self.engine.pinyin)
        self.assertFalse(self.key(IBus.KEY_Caps_Lock, LOCK | RELEASE))
        self.assertTrue(self.engine.caps_lock)
        self.assertFalse(self.key(IBus.KEY_A, LOCK))
        self.assertFalse(self.key(IBus.KEY_a, LOCK | SHIFT))
        self.assertEqual(self.operations, [("command", 1)])
        self.assertFalse(self.key(IBus.KEY_Caps_Lock, LOCK))
        self.assertFalse(self.key(IBus.KEY_Caps_Lock, RELEASE))
        self.assertFalse(self.engine.caps_lock)
        self.assertTrue(self.key(IBus.KEY_a))
        self.assertEqual(self.operations[-1], ("feed", "a"))

    def test_shift_letter_direct_but_shift_symbol_keeps_existing_boundary(self):
        self.engine.composing = True
        self.assertFalse(self.key(IBus.KEY_A, SHIFT))
        self.assertEqual(self.operations, [("command", 1)])
        self.engine.composing = True
        self.assertFalse(self.key(IBus.KEY_exclam, SHIFT))
        self.assertEqual(self.operations[-1], ("command", 2))

    def test_keyboard_shortcuts_and_passwords_remain_native(self):
        for flags in [IBus.ModifierType.CONTROL_MASK, IBus.ModifierType.MOD1_MASK, IBus.ModifierType.SUPER_MASK]:
            self.engine.composing = True
            self.assertFalse(self.key(IBus.KEY_A, flags | SHIFT | LOCK))
        self.engine.scope = 1
        self.assertFalse(self.key(IBus.KEY_A, LOCK))
        self.assertEqual(self.operations, [])

    def test_caps_active_does_not_block_explicit_language_switch(self):
        self.assertTrue(self.key(IBus.KEY_space, LOCK | SHIFT))
        self.assertFalse(self.engine.pinyin)
        self.assertFalse(self.key(IBus.KEY_Caps_Lock, LOCK))
        self.assertFalse(self.key(IBus.KEY_a))
        self.assertFalse(self.engine.pinyin)
        self.assertEqual(self.operations, [])

    def test_first_key_in_new_context_uses_event_caps_state(self):
        self.engine.composing = True
        self.assertFalse(self.key(IBus.KEY_A, LOCK))
        self.assertTrue(self.engine.caps_lock)
        self.assertEqual(self.labels, [True])
        self.assertEqual(self.operations, [("command", 1)])

    def test_failed_raw_commit_does_not_forward_a_letter(self):
        def fail(_):
            raise RuntimeError("injected")
        self.engine.composing = True
        self.engine.runtime.command = fail
        self.assertTrue(self.key(IBus.KEY_A, SHIFT))
        self.assertTrue(self.engine.composing)

    def test_real_action_decoder_closes_preedit_before_native_uppercase(self):
        committed = []
        hidden = []
        self.engine.composing = True
        self.engine.candidates = ["old-candidate"]
        self.engine.commit_text = lambda value: committed.append(value.get_text())
        self.engine.hide_preedit_text = lambda: hidden.append("preedit")
        self.engine.hide_lookup_table = lambda: hidden.append("candidates")
        self.engine.runtime.command = lambda value: [{"CommitText": "nihao"}, "CloseComposition"] if value == 1 else []
        self.engine.apply_actions = lambda actions: module.ZhimoEngine.apply_actions(self.engine, actions)
        self.assertFalse(self.key(IBus.KEY_A, SHIFT))
        self.assertEqual(committed, ["nihao"])
        self.assertIn("preedit", hidden)
        self.assertFalse(self.engine.composing)
        self.assertEqual(self.engine.candidates, [])


if __name__ == "__main__":
    unittest.main()
