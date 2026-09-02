#ifndef SHURUFA_FCITX5_ENGINE_H
#define SHURUFA_FCITX5_ENGINE_H

#include <fcitx/addonfactory.h>
#include <fcitx/addoninstance.h>
#include <fcitx/addonmanager.h>
#include <fcitx/event.h>
#include <fcitx/inputcontextproperty.h>
#include <fcitx/inputmethodengine.h>
#include <fcitx/instance.h>

namespace fcitx {

class ShurufaState;

class ShurufaEngine final : public InputMethodEngine {
public:
    explicit ShurufaEngine(Instance *instance);
    ~ShurufaEngine() override;

    void keyEvent(const InputMethodEntry &entry, KeyEvent &event) override;
    void reset(const InputMethodEntry &entry, InputContextEvent &event) override;
    void deactivate(const InputMethodEntry &entry,
                    InputContextEvent &event) override;

private:
    FactoryFor<ShurufaState> stateFactory_;
};

class ShurufaFactory final : public AddonFactory {
public:
    AddonInstance *create(AddonManager *manager) override {
        return new ShurufaEngine(manager->instance());
    }
};

} // namespace fcitx

#endif
