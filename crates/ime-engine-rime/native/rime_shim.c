#include <rime_api.h>

#include <stddef.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>

static RimeApi *api = NULL;
static atomic_flag lifecycle_lock = ATOMIC_FLAG_INIT;
static unsigned int client_count = 0;

static void lock_lifecycle(void) {
  while (atomic_flag_test_and_set_explicit(&lifecycle_lock, memory_order_acquire)) {}
}

static void unlock_lifecycle(void) {
  atomic_flag_clear_explicit(&lifecycle_lock, memory_order_release);
}

static size_t copy_text(const char *source, char *output, size_t capacity) {
  size_t length = source ? strlen(source) : 0;
  if (output && capacity) {
    size_t copied = length < capacity - 1 ? length : capacity - 1;
    if (copied) memcpy(output, source, copied);
    output[copied] = '\0';
  }
  return length;
}

int shurufa_rime_initialize(const char *shared_dir, const char *user_dir) {
  lock_lifecycle();
  if (client_count > 0) {
    ++client_count;
    unlock_lifecycle();
    return 1;
  }
  api = rime_get_api();
  if (!api) {
    unlock_lifecycle();
    return 0;
  }
  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared_dir;
  traits.user_data_dir = user_dir;
  traits.app_name = "shurufa";
  traits.distribution_name = "Shurufa";
  traits.distribution_code_name = "shurufa";
  traits.distribution_version = "0.1.0";
  api->setup(&traits);
  api->initialize(&traits);
  api->start_maintenance(False);
  api->join_maintenance_thread();
  client_count = 1;
  unlock_lifecycle();
  return 1;
}

void shurufa_rime_finalize(void) {
  lock_lifecycle();
  if (client_count > 0 && --client_count == 0) {
    api->finalize();
    api = NULL;
  }
  unlock_lifecycle();
}

uint64_t shurufa_rime_create_session(void) { return api ? api->create_session() : 0; }
void shurufa_rime_destroy_session(uint64_t session) { if (api) api->destroy_session(session); }
int shurufa_rime_process_key(uint64_t session, int keycode, int modifiers) { return api ? api->process_key(session, keycode, modifiers) : 0; }
void shurufa_rime_clear(uint64_t session) { if (api) api->clear_composition(session); }
int shurufa_rime_select_candidate(uint64_t session, size_t index) { return api ? api->select_candidate_on_current_page(session, index) : 0; }

size_t shurufa_rime_copy_preedit(uint64_t session, char *output, size_t capacity) {
  if (!api) return 0;
  RIME_STRUCT(RimeContext, context);
  if (!api->get_context(session, &context)) return 0;
  size_t length = copy_text(context.composition.preedit, output, capacity);
  api->free_context(&context);
  return length;
}

size_t shurufa_rime_candidate_count(uint64_t session) {
  if (!api) return 0;
  RIME_STRUCT(RimeContext, context);
  if (!api->get_context(session, &context)) return 0;
  size_t count = (size_t)context.menu.num_candidates;
  api->free_context(&context);
  return count;
}

size_t shurufa_rime_page_index(uint64_t session) {
  if (!api) return 0;
  RIME_STRUCT(RimeContext, context);
  if (!api->get_context(session, &context)) return 0;
  size_t page = (size_t)context.menu.page_no;
  api->free_context(&context);
  return page;
}

int shurufa_rime_has_next_page(uint64_t session) {
  if (!api) return 0;
  RIME_STRUCT(RimeContext, context);
  if (!api->get_context(session, &context)) return 0;
  int has_next = context.menu.num_candidates > 0 && !context.menu.is_last_page;
  api->free_context(&context);
  return has_next;
}

size_t shurufa_rime_copy_candidate(uint64_t session, size_t index, int comment, char *output, size_t capacity) {
  if (!api) return 0;
  RIME_STRUCT(RimeContext, context);
  if (!api->get_context(session, &context)) return 0;
  const char *text = NULL;
  if (index < (size_t)context.menu.num_candidates) {
    RimeCandidate *candidate = &context.menu.candidates[index];
    text = comment ? candidate->comment : candidate->text;
  }
  size_t length = copy_text(text, output, capacity);
  api->free_context(&context);
  return length;
}

size_t shurufa_rime_take_commit(uint64_t session, char *output, size_t capacity) {
  if (!api) return 0;
  RIME_STRUCT(RimeCommit, commit);
  if (!api->get_commit(session, &commit)) return 0;
  size_t length = copy_text(commit.text, output, capacity);
  api->free_commit(&commit);
  return length;
}
