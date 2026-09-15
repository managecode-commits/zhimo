// Copyright © 2026 立方田 <managecode@gmail.com>
#ifndef ZHIMO_FCITX5_ENGINE_H
#define ZHIMO_FCITX5_ENGINE_H

#include <fcitx/addonfactory.h>
#include <fcitx/addoninstance.h>
#include <fcitx/addonmanager.h>
#include <fcitx/event.h>
#include <fcitx/inputcontextproperty.h>
#include <fcitx/inputmethodengine.h>
#include <fcitx/instance.h>

namespace fcitx {

class ZhimoState;
class Action;

class ZhimoEngine final : public InputMethodEngineV2 {
public:
    explicit ZhimoEngine(Instance *instance);
    ~ZhimoEngine() override;

    void keyEvent(const InputMethodEntry &entry, KeyEvent &event) override;
    void activate(const InputMethodEntry &entry, InputContextEvent &event) override;
    std::string subMode(const InputMethodEntry &entry, InputContext &ic) override;
    std::string subModeLabelImpl(const InputMethodEntry &entry, InputContext &ic) override;
    void reset(const InputMethodEntry &entry, InputContextEvent &event) override;
    void deactivate(const InputMethodEntry &entry,
                    InputContextEvent &event) override;

private:
    Instance *instance_;
    FactoryFor<ZhimoState> stateFactory_;
    std::unique_ptr<Action> modeAction_;
    std::unique_ptr<Action> handwritingAction_;
    std::unique_ptr<Action> speechAction_;
};

class ZhimoFactory final : public AddonFactory {
public:
    AddonInstance *create(AddonManager *manager) override {
        return new ZhimoEngine(manager->instance());
    }
};

} // namespace fcitx

#endif
