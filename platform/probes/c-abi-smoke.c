#include "shurufa_ime.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    assert(ime_runtime_abi_version() >= 0x00010001U);
    assert((ime_runtime_capabilities() & SHURUFA_CAP_STRUCTURED_ACTIONS) != 0);
    ImeHandle *ime = ime_runtime_new();
    assert(ime != NULL);
    assert(ime_runtime_set_input_scope(ime, 0) == 0);
    assert(ime_runtime_switch_engine(ime, "pinyin.reference") == 0);
    assert(ime_runtime_feed_utf8(ime, "nihao") == 0);

    const char *actions = ime_runtime_last_actions_json(ime);
    assert(actions != NULL);
    assert(strstr(actions, "你好") != NULL);
    size_t action_count = ime_runtime_action_count(ime);
    assert(action_count >= 2);
    size_t candidate_action = action_count;
    for (size_t index = 0; index < action_count; ++index) {
        if (ime_runtime_action_kind(ime, index) == 2) {
            candidate_action = index;
            break;
        }
    }
    assert(candidate_action < action_count);
    assert(ime_runtime_candidate_count(ime, candidate_action) > 0);
    assert(strcmp(ime_runtime_candidate_field(ime, candidate_action, 0, 1),
                  "你好") == 0);

    const char *committed = ime_runtime_commit(ime);
    assert(committed != NULL);
    assert(strcmp(committed, "你好") == 0);
    printf("C ABI smoke test committed: %s\n", committed);

    ime_runtime_free(ime);
    return 0;
}
