// Copyright © 2026 立方田 <managecode@gmail.com>
#pragma once
#include <fcitx-utils/event.h>
#include <fcitx-utils/utf8.h>
#include <spawn.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <signal.h>
#include <unistd.h>
#include <cerrno>
#include <functional>
#include <string>
#include <thread>
extern char **environ;

namespace fcitx {
class DesktopClient {
public:
    ~DesktopClient() { stop(); }
    bool running() const { return pid_ > 0; }
    void stop() {
        if (timer_) timer_->setEnabled(false);
        if (fd_ >= 0) { close(fd_); fd_ = -1; }
        if (pid_ > 0) {
            kill(-pid_, SIGKILL);
            auto child = pid_;
            std::thread([child] { while (waitpid(child, nullptr, 0) < 0 && errno == EINTR) {} }).detach();
            pid_ = -1;
        }
        output_.clear();
    }
    bool start(EventLoop &loop, const char *mode, std::function<void(const std::string&)> commit,
               std::function<bool()> eligible) {
        stop();
        int pipes[2];
        if (pipe2(pipes, O_CLOEXEC) != 0) return false;
        posix_spawn_file_actions_t actions;
        posix_spawn_file_actions_init(&actions);
        posix_spawn_file_actions_addopen(&actions, STDIN_FILENO, "/dev/null", O_RDONLY, 0);
        posix_spawn_file_actions_adddup2(&actions, pipes[1], STDOUT_FILENO);
        posix_spawn_file_actions_addclose(&actions, pipes[0]);
        posix_spawn_file_actions_addclose(&actions, pipes[1]);
        posix_spawnattr_t attributes;
        posix_spawnattr_init(&attributes);
        posix_spawnattr_setflags(&attributes, POSIX_SPAWN_SETPGROUP);
        posix_spawnattr_setpgroup(&attributes, 0);
        const char *args[] = {"/usr/bin/python3", "/usr/lib/zhimo/bin/desktop_panel.py", mode, nullptr};
        int error = posix_spawn(&pid_, args[0], &actions, &attributes, const_cast<char**>(args), environ);
        posix_spawnattr_destroy(&attributes);
        posix_spawn_file_actions_destroy(&actions);
        close(pipes[1]);
        if (error) { close(pipes[0]); pid_ = -1; return false; }
        fd_ = pipes[0];
        fcntl(fd_, F_SETFL, O_NONBLOCK);
        started_ = now();
        timer_ = loop.addTimeEvent(CLOCK_MONOTONIC, now() + 50000, 1000,
            [this, commit, eligible](EventSourceTime *source, uint64_t) {
                if (!eligible()) { stop(); return true; }
                bool eof = false;
                char buffer[4096];
                ssize_t size;
                while ((size = read(fd_, buffer, sizeof(buffer))) > 0) {
                    output_.append(buffer, size);
                    if (output_.size() > 16384) { stop(); return true; }
                }
                eof = size == 0;
                if ((size < 0 && errno != EAGAIN && errno != EINTR) || now() - started_ > 300000000) {
                    stop(); return true;
                }
                if (eof) {
                    int status = 0;
                    auto result = waitpid(pid_, &status, WNOHANG);
                    if (result == pid_) {
                        kill(-pid_, SIGKILL); // any outstanding microphone subprocess
                        pid_ = -1;
                        auto text = output_;
                        stop();
                        if (WIFEXITED(status) && WEXITSTATUS(status) == 0 && !text.empty() && utf8::validate(text) && text.find('\0') == std::string::npos)
                            commit(text);
                        return true;
                    }
                    if (result < 0 && errno != EINTR) { stop(); return true; }
                }
                source->setNextInterval(50000);
                source->setEnabled(true);
                return true;
            });
        return true;
    }
private:
    static uint64_t now() {
        timespec time{}; clock_gettime(CLOCK_MONOTONIC, &time);
        return uint64_t(time.tv_sec) * 1000000 + time.tv_nsec / 1000;
    }
    pid_t pid_ = -1;
    int fd_ = -1;
    uint64_t started_ = 0;
    std::string output_;
    std::unique_ptr<EventSourceTime> timer_;
};
}
