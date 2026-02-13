/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "vendor.lineage.livedisplay-service.nubia"

#include <aidl/vendor/lineage/livedisplay/BnReadingEnhancement.h>
#include <aidl/vendor/lineage/livedisplay/BnSunlightEnhancement.h>
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/strings.h>
#include <android/binder_ibinder.h>
#include <android/binder_interface_utils.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <mutex>
#include <string>
#include <thread>

using ::aidl::vendor::lineage::livedisplay::BnReadingEnhancement;
using ::aidl::vendor::lineage::livedisplay::BnSunlightEnhancement;
using ::android::base::ReadFileToString;
using ::android::base::Trim;
using ::android::base::WriteStringToFile;

namespace {
constexpr const char* kServiceRevision = "2026-02-09-reading-sunlight-hbm-hold";
constexpr const char* kHbmPath = "/sys/devices/platform/soc/soc:qcom,dsi-display-primary/hbm";
constexpr const char* kReadingPath = "/sys/devices/platform/soc/soc:qcom,dsi-display-primary/reading_mode";

std::string CallingIdentity() {
    return "uid=" + std::to_string(AIBinder_getCallingUid()) +
           " pid=" + std::to_string(AIBinder_getCallingPid());
}

bool ReadBoolFromSysfs(const char* path, bool* enabled) {
    std::string raw;
    if (!ReadFileToString(path, &raw, true)) {
        return false;
    }
    raw = Trim(raw);
    if (raw.empty()) {
        return false;
    }
    *enabled = (raw != "0");
    return true;
}

bool WriteBoolToSysfs(const char* path, bool enabled) {
    return WriteStringToFile(enabled ? "1" : "0", path, true);
}

class ReadingEnhancement : public BnReadingEnhancement {
  public:
    ndk::ScopedAStatus getEnabled(bool* _aidl_return) override {
        bool enabled = false;
        if (!ReadBoolFromSysfs(kReadingPath, &enabled)) {
            LOG(WARNING) << "getReadingEnabled failed: path unavailable";
            return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
        }
        *_aidl_return = enabled;
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus setEnabled(bool enabled) override {
        LOG(INFO) << "setReadingEnabled by " << CallingIdentity()
                  << " enabled=" << (enabled ? "true" : "false");
        if (!WriteBoolToSysfs(kReadingPath, enabled)) {
            LOG(WARNING) << "setReadingEnabled failed: path unavailable";
            return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
        }
        LOG(INFO) << "Applied reading via sysfs: " << (enabled ? "ON" : "OFF");
        return ndk::ScopedAStatus::ok();
    }
};

class SunlightEnhancement : public BnSunlightEnhancement {
  public:
    SunlightEnhancement() {
        keepalive_thread_ = std::thread([this]() { KeepaliveLoop(); });
    }

    ~SunlightEnhancement() override {
        stop_keepalive_.store(true);
        if (keepalive_thread_.joinable()) {
            keepalive_thread_.join();
        }
    }

    ndk::ScopedAStatus getEnabled(bool* _aidl_return) override {
        bool enabled = false;
        if (!ReadBoolFromSysfs(kHbmPath, &enabled)) {
            LOG(WARNING) << "getSunlightEnabled failed: path unavailable";
            return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
        }
        *_aidl_return = enabled;
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus setEnabled(bool enabled) override {
        LOG(INFO) << "setSunlightEnabled by " << CallingIdentity()
                  << " enabled=" << (enabled ? "true" : "false");
        {
            std::lock_guard<std::mutex> lock(state_mutex_);
            force_hbm_ = enabled;
        }
        if (!WriteBoolToSysfs(kHbmPath, enabled)) {
            LOG(WARNING) << "setSunlightEnabled failed: path unavailable";
            return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
        }
        return ndk::ScopedAStatus::ok();
    }

  private:
    void KeepaliveLoop() {
        while (!stop_keepalive_.load()) {
            bool should_hold = false;
            {
                std::lock_guard<std::mutex> lock(state_mutex_);
                should_hold = force_hbm_;
            }

            if (should_hold) {
                bool current_enabled = false;
                if (ReadBoolFromSysfs(kHbmPath, &current_enabled)) {
                    if (!current_enabled) {
                        if (WriteBoolToSysfs(kHbmPath, true)) {
                            LOG(INFO) << "Re-applied sunlight HBM after external reset";
                        } else {
                            LOG(WARNING) << "Failed to re-apply sunlight HBM";
                        }
                    }
                }
            }

            std::this_thread::sleep_for(std::chrono::milliseconds(750));
        }
    }

    std::mutex state_mutex_;
    bool force_hbm_ = false;
    std::atomic<bool> stop_keepalive_{false};
    std::thread keepalive_thread_;
};

}  // namespace

int main() {
    LOG(INFO) << "Starting LiveDisplay Nubia service rev=" << kServiceRevision;

    ABinderProcess_setThreadPoolMaxThreadCount(1);
    ABinderProcess_startThreadPool();

    auto add_service = [](const std::shared_ptr<ndk::ICInterface>& service, const char* desc) {
        const std::string name = std::string(desc) + "/default";
        const binder_status_t status =
                AServiceManager_addService(service->asBinder().get(), name.c_str());
        if (status != STATUS_OK) {
            LOG(ERROR) << "Failed to register service " << name << " status=" << status;
        } else {
            LOG(INFO) << "Registered service " << name;
        }
    };

    add_service(ndk::SharedRefBase::make<ReadingEnhancement>(), ReadingEnhancement::descriptor);
    add_service(ndk::SharedRefBase::make<SunlightEnhancement>(), SunlightEnhancement::descriptor);

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;  // should not reach
}
