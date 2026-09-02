#include "shurufa.h"

#include "shurufa_ime.h"

#include <cstdlib>
#include <cctype>
#include <filesystem>
#include <memory>
#include <stdexcept>
#include <string>

#include <fcitx-utils/capabilityflags.h>
#include <fcitx-utils/key.h>
#include <fcitx-utils/keysym.h>
#include <fcitx-utils/textformatflags.h>
#include <fcitx/candidatelist.h>
#include <fcitx/inputcontext.h>
#include <fcitx/inputcontextmanager.h>
#include <fcitx/inputpanel.h>
#include <fcitx/text.h>
#include <fcitx/userinterface.h>

namespace fcitx {

namespace {

std::filesystem::path userDataDirectory() {
    if (const char *overridePath = std::getenv("SHURUFA_USER_DATA_DIR")) {
        return overridePath;
    }
    if (const char *xdg = std::getenv("XDG_DATA_HOME")) {
        return std::filesystem::path(xdg) / "shurufa" / "user";
    }
    if (const char *home = std::getenv("HOME")) {
        return std::filesystem::path(home) / ".local" / "share" / "shurufa" /
               "user";
    }
    return std::filesystem::temp_directory_path() / "shurufa-user";
}

std::string copyQuery(const char *value) { return value ? value : ""; }

} // namespace

class ShurufaState;

class ShurufaCandidate final : public CandidateWord {
public:
    ShurufaCandidate(ShurufaState *state, std::string id,
                     const std::string &display, const std::string &annotation)
        : CandidateWord(Text(display)), state_(state), id_(std::move(id)) {
        if (!annotation.empty()) {
            setComment(Text(annotation));
        }
    }

    void select(InputContext *inputContext) const override;

private:
    ShurufaState *state_;
    std::string id_;
};

class ShurufaState final : public InputContextProperty {
public:
    explicit ShurufaState(InputContext &inputContext) : ic_(&inputContext) {
        auto dataDirectory = userDataDirectory();
        std::filesystem::create_directories(dataDirectory);
        const char *rimeShared = std::getenv("SHURUFA_RIME_SHARED_DIR");
        const char *rimeUser = std::getenv("SHURUFA_RIME_USER_DIR");
        if (rimeShared && *rimeShared && rimeUser && *rimeUser) {
            handle_ = ime_runtime_new_with_rime("bilingual", dataDirectory.c_str(),
                                                rimeShared, rimeUser);
            pinyinEngine_ = "rime";
        } else {
            handle_ = ime_runtime_new_with_data_dir("bilingual",
                                                    dataDirectory.c_str());
        }
        if (!handle_ || (ime_runtime_abi_version() >> 16) != 1 ||
            !(ime_runtime_capabilities() & SHURUFA_CAP_TEXT_INPUT) ||
            ime_runtime_switch_engine(handle_, pinyinEngine_.c_str()) != 0) {
            if (handle_) {
                ime_runtime_free(handle_);
            }
            throw std::runtime_error("failed to initialize Shurufa runtime");
        }
    }

    ~ShurufaState() override {
        if (handle_) {
            ime_runtime_free(handle_);
        }
    }

    bool feed(const std::string &text) {
        updateInputScope();
        return ime_runtime_feed_utf8(handle_, text.c_str()) == 0 && applyActions();
    }

    bool command(unsigned command) {
        updateInputScope();
        return ime_runtime_send_command(handle_, command) == 0 && applyActions();
    }

    bool select(const std::string &candidateId) {
        updateInputScope();
        return ime_runtime_select_candidate(handle_, candidateId.c_str()) == 0 &&
               applyActions();
    }

    bool composing() const { return composing_; }

    bool acceptsInput() {
        updateInputScope();
        return scope_ != 1;
    }

    bool toggleMode() {
        if (!acceptsInput()) {
            return false;
        }
        if (composing_ && !command(3)) {
            return false;
        }
        const char *engine = pinyin_ ? "latin" : pinyinEngine_.c_str();
        if (ime_runtime_switch_engine(handle_, engine) != 0) {
            return false;
        }
        pinyin_ = !pinyin_;
        applyActions();
        return true;
    }

    bool selectVisibleCandidate(int index) {
        auto list = ic_->inputPanel().candidateList();
        if (!list || index < 0 || index >= list->size()) {
            return false;
        }
        list->candidate(index).select(ic_);
        return true;
    }

    bool moveCandidate(bool previous) {
        auto list = ic_->inputPanel().candidateList();
        auto *movable = list ? list->toCursorMovable() : nullptr;
        if (!movable) {
            return false;
        }
        previous ? movable->prevCandidate() : movable->nextCandidate();
        ic_->updateUserInterface(UserInterfaceComponent::InputPanel);
        return true;
    }

    bool pageCandidates(bool previous) {
        auto list = ic_->inputPanel().candidateList();
        auto *pageable = list ? list->toPageable() : nullptr;
        if (!pageable || (previous ? !pageable->hasPrev() : !pageable->hasNext())) {
            return false;
        }
        previous ? pageable->prev() : pageable->next();
        ic_->updateUserInterface(UserInterfaceComponent::InputPanel);
        return true;
    }

    bool pinyin() const { return pinyin_; }

private:
    void updateInputScope() {
        const auto flags = ic_->capabilityFlags();
        unsigned scope = 0;
        if (flags.testAny(CapabilityFlag::PasswordOrSensitive)) {
            scope = 1;
        } else if (flags.test(CapabilityFlag::Email)) {
            scope = 2;
        } else if (flags.test(CapabilityFlag::Url)) {
            scope = 3;
        } else if (flags.test(CapabilityFlag::Terminal)) {
            scope = 4;
        }
        scope_ = scope;
        (void)ime_runtime_set_input_scope(handle_, scope);
        (void)ime_runtime_set_privacy_policy(handle_, scope == 1 ? 0 : 1, 0);
        const auto &program = ic_->program();
        (void)ime_runtime_set_application_id(handle_,
                                             program.empty() ? nullptr : program.c_str());
    }

    bool applyActions() {
        auto &panel = ic_->inputPanel();
        panel.reset();
        for (size_t actionIndex = 0;
             actionIndex < ime_runtime_action_count(handle_); ++actionIndex) {
            switch (ime_runtime_action_kind(handle_, actionIndex)) {
            case 1: {
                auto text = copyQuery(
                    ime_runtime_action_text(handle_, actionIndex));
                composing_ = !text.empty();
                if (composing_) {
                    Text preedit(text, TextFormatFlag::HighLight);
                    preedit.setCursor(text.size());
                    if (ic_->capabilityFlags().test(CapabilityFlag::Preedit)) {
                        panel.setClientPreedit(preedit);
                    } else {
                        panel.setPreedit(preedit);
                    }
                }
                break;
            }
            case 2: {
                auto list = std::make_unique<CommonCandidateList>();
                list->setPageSize(9);
                list->setSelectionKey(KeyList(9));
                auto count = ime_runtime_candidate_count(handle_, actionIndex);
                for (size_t candidateIndex = 0; candidateIndex < count;
                     ++candidateIndex) {
                    auto id = copyQuery(ime_runtime_candidate_field(
                        handle_, actionIndex, candidateIndex, 0));
                    auto display = copyQuery(ime_runtime_candidate_field(
                        handle_, actionIndex, candidateIndex, 1));
                    auto annotation = copyQuery(ime_runtime_candidate_field(
                        handle_, actionIndex, candidateIndex, 3));
                    list->append<ShurufaCandidate>(this, std::move(id), display,
                                                   annotation);
                }
                if (count > 0) {
                    list->setGlobalCursorIndex(0);
                    panel.setCandidateList(std::move(list));
                }
                break;
            }
            case 3: {
                auto text = copyQuery(
                    ime_runtime_action_text(handle_, actionIndex));
                if (!text.empty()) {
                    ic_->commitString(text);
                }
                composing_ = false;
                break;
            }
            case 4:
                composing_ = false;
                break;
            default:
                break;
            }
        }
        ic_->updatePreedit();
        ic_->updateUserInterface(UserInterfaceComponent::InputPanel);
        return true;
    }

    InputContext *ic_;
    ImeHandle *handle_ = nullptr;
    bool composing_ = false;
    bool pinyin_ = true;
    std::string pinyinEngine_ = "pinyin.reference";
    unsigned scope_ = 0;
};

void ShurufaCandidate::select(InputContext * /*inputContext*/) const {
    state_->select(id_);
}

ShurufaEngine::ShurufaEngine(Instance *instance)
    : stateFactory_([](InputContext &ic) { return new ShurufaState(ic); }) {
    instance->inputContextManager().registerProperty("shurufaState",
                                                     &stateFactory_);
}

ShurufaEngine::~ShurufaEngine() = default;

void ShurufaEngine::keyEvent(const InputMethodEntry & /*entry*/,
                             KeyEvent &event) {
    if (event.isRelease()) {
        return;
    }
    auto key = event.key();
    auto *state = event.inputContext()->propertyFor(&stateFactory_);
    if (key.check(FcitxKey_space, KeyState::Shift)) {
        if (state->toggleMode()) {
            event.filterAndAccept();
        }
        return;
    }
    if (!state->acceptsInput()) {
        return;
    }
    if (key.states().testAny(KeyState::Ctrl) ||
        key.states().testAny(KeyState::Alt) ||
        key.states().testAny(KeyState::Super) ||
        key.states().testAny(KeyState::Hyper) ||
        key.states().testAny(KeyState::Meta)) {
        return;
    }
    if (state->composing() && key.isDigit()) {
        auto index = key.digitSelection();
        if (state->selectVisibleCandidate(index)) {
            event.filterAndAccept();
            return;
        }
    }
    if (state->composing() &&
        (key.check(FcitxKey_Up) || key.check(FcitxKey_Down))) {
        if (state->moveCandidate(key.check(FcitxKey_Up))) {
            event.filterAndAccept();
            return;
        }
    }
    if (state->composing() &&
        (key.check(FcitxKey_Page_Up) || key.check(FcitxKey_Page_Down))) {
        if (state->pageCandidates(key.check(FcitxKey_Page_Up))) {
            event.filterAndAccept();
            return;
        }
    }
    if (key.check(FcitxKey_BackSpace) && state->composing()) {
        state->command(0);
        event.filterAndAccept();
        return;
    }
    if ((key.check(FcitxKey_Return) || key.check(FcitxKey_KP_Enter)) &&
        state->composing()) {
        state->command(1);
        event.filterAndAccept();
        return;
    }
    if (key.check(FcitxKey_space) && state->composing()) {
        state->command(2);
        event.filterAndAccept();
        return;
    }
    if (key.check(FcitxKey_Escape) && state->composing()) {
        state->command(3);
        event.filterAndAccept();
        return;
    }
    auto symbol = key.sym();
    if (((symbol >= FcitxKey_a && symbol <= FcitxKey_z) ||
         (symbol >= FcitxKey_A && symbol <= FcitxKey_Z) ||
         symbol == FcitxKey_apostrophe)) {
        char character = static_cast<char>(symbol);
        if (state->pinyin()) {
            character = static_cast<char>(std::tolower(
                static_cast<unsigned char>(character)));
        }
        if (state->feed(std::string(1, character))) {
            event.filterAndAccept();
        }
    }
}

void ShurufaEngine::reset(const InputMethodEntry & /*entry*/,
                          InputContextEvent &event) {
    auto *state = event.inputContext()->propertyFor(&stateFactory_);
    if (state->composing()) {
        state->command(3);
    }
}

void ShurufaEngine::deactivate(const InputMethodEntry &entry,
                               InputContextEvent &event) {
    reset(entry, event);
}

} // namespace fcitx

FCITX_ADDON_FACTORY_V2(shurufa, fcitx::ShurufaFactory);
