#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <jni.h>
#include <dlfcn.h>
#include <algorithm>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Library {
    void* handle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    ~Library() { if (handle) dlclose(handle); }
    template<class T> T symbol(const char* name) const {
        auto fn = reinterpret_cast<T>(dlsym(handle, name));
        if (!fn) throw std::runtime_error(name);
        return fn;
    }
};

struct Instance {
    VkInstance handle = VK_NULL_HANDLE;
    PFN_vkDestroyInstance destroy;
    ~Instance() { if (handle) destroy(handle, nullptr); }
};

// Enumeration may return VK_INCOMPLETE if the set changes between the two calls.
template<class T, class F> std::vector<T> enumerate(F fn, uint32_t maximum) {
    for (int attempt = 0; attempt < 3; ++attempt) {
        uint32_t count = 0;
        if (fn(&count, nullptr) != VK_SUCCESS || count > maximum)
            throw std::runtime_error("Vulkan enumeration count");
        if (count == 0) return {};
        std::vector<T> values(count);
        const auto result = fn(&count, values.data());
        if (result == VK_SUCCESS && count <= values.size()) {
            values.resize(count);
            return values;
        }
        if (result != VK_INCOMPLETE) throw std::runtime_error("Vulkan enumeration values");
    }
    throw std::runtime_error("Vulkan enumeration changed repeatedly");
}

std::string quoted(const char* value) {
    std::string out = "\"";
    constexpr char hex[] = "0123456789abcdef";
    for (const auto* p = reinterpret_cast<const unsigned char*>(value); *p; ++p) {
        if (*p == '"' || *p == '\\') { out += '\\'; out += static_cast<char>(*p); }
        else if (*p < 0x20) { out += "\\u00"; out += hex[*p >> 4]; out += hex[*p & 15]; }
        else out += static_cast<char>(*p);
    }
    return out + '"';
}

std::string readVulkan() {
    Library library;
    if (!library.handle) return "{\"devices\":[]}";
    const auto create = library.symbol<PFN_vkCreateInstance>("vkCreateInstance");
    Instance instance{VK_NULL_HANDLE, library.symbol<PFN_vkDestroyInstance>("vkDestroyInstance")};
    const auto physicalDevices = library.symbol<PFN_vkEnumeratePhysicalDevices>("vkEnumeratePhysicalDevices");
    const auto properties = library.symbol<PFN_vkGetPhysicalDeviceProperties>("vkGetPhysicalDeviceProperties");
    const auto extensions = library.symbol<PFN_vkEnumerateDeviceExtensionProperties>("vkEnumerateDeviceExtensionProperties");
    uint32_t loaderApi = VK_API_VERSION_1_0;
    const auto instanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(dlsym(library.handle, "vkEnumerateInstanceVersion"));
    if (instanceVersion && instanceVersion(&loaderApi) != VK_SUCCESS)
        throw std::runtime_error("vkEnumerateInstanceVersion");

    // Request only Vulkan 1.0: all data below is available without extensions or a logical device.
    VkApplicationInfo application{};
    application.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application.pApplicationName = "Stellar device information";
    application.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &application;
    const auto result = create(&createInfo, nullptr, &instance.handle);
    if (result == VK_ERROR_INCOMPATIBLE_DRIVER)
        return "{\"loaderApi\":" + std::to_string(loaderApi) + ",\"devices\":[]}";
    if (result != VK_SUCCESS) throw std::runtime_error("vkCreateInstance");

    const auto devices = enumerate<VkPhysicalDevice>([&](auto count, auto data) {
        return physicalDevices(instance.handle, count, data);
    }, 64);
    std::string json = "{\"loaderApi\":" + std::to_string(loaderApi) + ",\"devices\":[";
    for (size_t i = 0; i < devices.size(); ++i) {
        VkPhysicalDeviceProperties info{};
        properties(devices[i], &info);
        if (i) json += ',';
        json += "{\"name\":" + quoted(info.deviceName) +
            ",\"api\":" + std::to_string(info.apiVersion) +
            ",\"driver\":" + std::to_string(info.driverVersion) +
            ",\"vendor\":" + std::to_string(info.vendorID) +
            ",\"id\":" + std::to_string(info.deviceID) +
            ",\"type\":" + std::to_string(info.deviceType) +
            ",\"maxImage2D\":" + std::to_string(info.limits.maxImageDimension2D) +
            ",\"extensions\":";
        try {
            auto list = enumerate<VkExtensionProperties>([&](auto count, auto data) {
                return extensions(devices[i], nullptr, count, data);
            }, 4096);
            std::vector<std::string> names;
            for (const auto& extension : list) names.emplace_back(extension.extensionName);
            std::sort(names.begin(), names.end());
            json += '[';
            for (size_t j = 0; j < names.size(); ++j) {
                if (j) json += ',';
                json += quoted(names[j].c_str());
            }
            json += ']';
        } catch (const std::runtime_error&) {
            json += "null";
        }
        json += '}';
    }
    return json + "]}";
}
} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_coldlake_stellar_opensource_settings_VulkanInfoNative_readBytes(JNIEnv* env, jobject) {
    try {
        // Driver names are UTF-8, which is not JNI's modified UTF-8 string encoding.
        const auto json = readVulkan();
        auto bytes = env->NewByteArray(static_cast<jsize>(json.size()));
        if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(json.size()),
            reinterpret_cast<const jbyte*>(json.data()));
        return bytes;
    } catch (const std::exception& error) {
        const auto type = env->FindClass("java/lang/IllegalStateException");
        if (type) env->ThrowNew(type, error.what());
        return nullptr;
    }
}
