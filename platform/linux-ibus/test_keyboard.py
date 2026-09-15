#!/usr/bin/env python3
# Copyright © 2026 立方田 <managecode@gmail.com>
"""Exercise the actual IBus event handler without registering a desktop IME."""
import unittest
from types import SimpleNamespace
from zhimo_ibus import IBus, ZhimoEngine


class KeyboardTests(unittest.TestCase):
    def fixture(self, pinyin=True, composing=False, cursor=0):
        events = []
        def record(name):
            return lambda value: events.append((name, value)) or []
        obj = SimpleNamespace(
            runtime=SimpleNamespace(feed=record('feed'), command=record('command')),
            pinyin=pinyin, composing=composing, scope=0,
            candidates=list(range(20)),
            lookup_table=SimpleNamespace(get_page_size=lambda: 9, get_cursor_pos=lambda: cursor),
            close_handwriting=lambda: None, update_context=lambda: True,
            apply_actions=lambda _: None, toggle_mode=lambda: events.append(('toggle', None)),
            select_candidate_at=lambda i: events.append(('select', i)) or True,
        )
        return obj, events

    def key(self, obj, key, state=0):
        return ZhimoEngine.do_process_key_event(obj, key, 0, state)

    def test_english_characters_are_native(self):
        obj, events = self.fixture(False)
        for ch in 'Hello123!@#_+\'"':
            self.assertFalse(self.key(obj, ord(ch)))
        self.assertFalse(self.key(obj, IBus.KEY_KP_1))
        self.assertEqual(events, [])

    def test_plain_text_paste_shortcut_is_preserved(self):
        obj, events = self.fixture()
        self.assertFalse(self.key(obj, IBus.KEY_v, IBus.ModifierType.CONTROL_MASK | IBus.ModifierType.SHIFT_MASK))
        self.assertEqual(events, [])

    def test_shift_space_toggle_but_not_control_shift_space(self):
        obj, events = self.fixture(False)
        self.assertTrue(self.key(obj, IBus.KEY_space, IBus.ModifierType.SHIFT_MASK))
        self.assertFalse(self.key(obj, IBus.KEY_space, IBus.ModifierType.SHIFT_MASK | IBus.ModifierType.CONTROL_MASK))
        self.assertEqual(events, [('toggle', None)])

    def test_pinyin_letters_and_separator(self):
        obj, events = self.fixture()
        for ch in "NI'hao":
            self.assertTrue(self.key(obj, ord(ch)))
        self.assertEqual(events, [('feed', c) for c in "ni'hao"])

    def test_numbers_select_current_page(self):
        obj, events = self.fixture(composing=True, cursor=10)
        self.assertTrue(self.key(obj, IBus.KEY_1))
        self.assertEqual(events, [('select', 9)])

    def test_space_selects_highlighted_candidate(self):
        obj, events = self.fixture(composing=True, cursor=4)
        self.assertTrue(self.key(obj, IBus.KEY_space))
        self.assertEqual(events, [('select', 4)])

    def test_punctuation_closes_before_forwarding(self):
        for ch in ',.!"0':
            obj, events = self.fixture(composing=True, cursor=2)
            self.assertFalse(self.key(obj, ord(ch)))
            self.assertEqual(events, [('select', 2)])

    def test_idle_numbers_symbols_and_password_pass(self):
        obj, events = self.fixture()
        for ch in '0123456789.!"':
            self.assertFalse(self.key(obj, ord(ch)))
        obj.scope = 1
        self.assertFalse(self.key(obj, ord('n')))
        self.assertEqual(events, [])

    def mode_fixture(self):
        obj, events = self.fixture()
        obj.mode_property = IBus.Property.new('InputMode', IBus.PropType.NORMAL,
            IBus.Text.new_from_string('中'), '', IBus.Text.new_from_string('切换'),
            True, True, IBus.PropState.UNCHECKED, None)
        obj.mode_properties = IBus.PropList()
        obj.mode_properties.append(obj.mode_property)
        obj.register_properties = lambda _: events.append(('register', None))
        obj.update_property = lambda _: events.append(('update', obj.mode_property.get_symbol().get_text()))
        obj.publish_mode = lambda register=False: ZhimoEngine.publish_mode(obj, register)
        obj.runtime.pinyin_engine = 'rime'
        obj.runtime.switch_engine = lambda engine: events.append(('engine', engine)) or []
        obj.reset_composition = lambda: None
        obj.toggle_mode = lambda: ZhimoEngine.toggle_mode(obj)
        return obj, events

    def test_visible_mode_property(self):
        obj, events = self.mode_fixture()
        obj.publish_mode(register=True)
        self.assertEqual(obj.mode_property.get_key(), 'InputMode')
        self.assertEqual(obj.mode_property.get_symbol().get_text(), '中')
        self.assertEqual(obj.mode_property.get_label().get_text(), '中文拼音')
        self.assertIn(('register', None), events)

    def test_mouse_and_keyboard_share_mode_state(self):
        obj, events = self.mode_fixture()
        ZhimoEngine.do_property_activate(obj, 'InputMode', 0)
        self.assertFalse(obj.pinyin)
        self.assertEqual(obj.mode_property.get_symbol().get_text(), '英')
        self.key(obj, IBus.KEY_space, IBus.ModifierType.SHIFT_MASK)
        self.assertTrue(obj.pinyin)
        self.assertEqual(obj.mode_property.get_symbol().get_text(), '中')
        self.assertIn(('engine', 'latin'), events)
        self.assertIn(('engine', 'rime'), events)

    def test_focus_republishes_mode(self):
        obj, events = self.mode_fixture()
        ZhimoEngine.do_focus_in(obj)
        self.assertIn(('register', None), events)

    def test_password_disables_mouse_switch(self):
        obj, events = self.mode_fixture()
        obj.scope = 1
        obj.publish_mode()
        ZhimoEngine.do_property_activate(obj, 'InputMode', 0)
        self.assertFalse(obj.mode_property.get_sensitive())
        self.assertTrue(obj.pinyin)
        self.assertFalse(any(name == 'engine' for name, _ in events))


if __name__ == '__main__':
    unittest.main()
