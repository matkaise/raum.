/*
 * raum. Bridge – Matter-Server (Aggregator) im eigenen Android-Prozess.
 *
 * Endpunkt 0: Wurzelknoten (Basisinformation, Kopplung, Netzwerk als Ethernet), Endpunkt 1: Aggregator,
 * ab Endpunkt 2: je freigegebenem raum.-Gerät ein „Bridged Node“ (siehe BridgedDevice). Datenmodell:
 * code-basiert (CodeDrivenDataModelProvider), Endpunkte kommen und gehen zur Laufzeit.
 *
 * Java-Seite: app.raum.matter.bridge.NativeBridge (JNI), aufgerufen vom BridgeService im Prozess „:bridge“.
 */
#include "BridgeIdentity.h"
#include "BridgedDevice.h"

#include <app/DefaultSafeAttributePersistenceProvider.h>
#include <app/InteractionModelEngine.h>
#include <app/clusters/network-commissioning/NetworkCommissioningCluster.h>
#include <app/persistence/DefaultAttributePersistenceProvider.h>
#include <app/server/Dnssd.h>
#include <app/server/Server.h>
#include <clusters/Descriptor/Attributes.h>
#include <clusters/Descriptor/ClusterId.h>
#include <credentials/GroupDataProviderImpl.h>
#include <credentials/examples/DeviceAttestationCredsExample.h>
#include <data-model-providers/codedriven/CodeDrivenDataModelProvider.h>
#include <devices/aggregator/AggregatorDevice.h>
#include <devices/root-node/RootNodeDevice.h>
#include <lib/support/CHIPMem.h>
#include <lib/support/JniReferences.h>
#include <lib/support/JniTypeWrappers.h>
#include <platform/CHIPDeviceLayer.h>
#include <platform/DefaultTimerDelegate.h>
#include <platform/NetworkCommissioning.h>
#include <platform/android/AndroidChipPlatform-JNI.h>

#include <jni.h>
#include <map>
#include <memory>
#include <pthread.h>
#include <string>

using namespace chip;
using namespace chip::app;
using namespace chip::app::Clusters;
using namespace chip::DeviceLayer;

#define JNI_METHOD(RETURN, METHOD_NAME) extern "C" JNIEXPORT RETURN JNICALL Java_app_raum_matter_bridge_NativeBridge_##METHOD_NAME

namespace raum {
namespace {

constexpr EndpointId kAggregatorEndpoint = 1;

// --- Netzwerk: das Panel hängt schon im LAN/WLAN – für Matter „Ethernet“ ohne Einrichtung --------------------

namespace NC = chip::DeviceLayer::NetworkCommissioning;

class PanelNetworkDriver final : public NC::EthernetDriver
{
public:
    struct Iterator final : public NC::NetworkIterator
    {
        size_t Count() override { return 1; }
        bool Next(NC::Network & item) override
        {
            if (mDone)
                return false;
            mDone = true;
            static const char kName[] = "panel";
            memcpy(item.networkID, kName, sizeof(kName) - 1);
            item.networkIDLen = sizeof(kName) - 1;
            item.connected    = true;
            return true;
        }
        void Release() override { delete this; }
        bool mDone = false;
    };
    uint8_t GetMaxNetworks() override { return 1; }
    NC::NetworkIterator * GetNetworks() override { return new Iterator(); }
    CHIP_ERROR Init(NC::Internal::BaseDriver::NetworkStatusChangeCallback *) override { return CHIP_NO_ERROR; }
    void Shutdown() override {}
};

class PanelRootNode : public RootNodeDevice
{
public:
    using RootNodeDevice::RootNodeDevice;

    CHIP_ERROR Register(EndpointId endpoint, CodeDrivenDataModelProvider & provider, EndpointId parentId) override
    {
        ReturnErrorOnFailure(RootNodeDevice::Register(endpoint, provider, parentId));
        mNetwork.Create(endpoint, &mDriver,
                        NetworkCommissioningCluster::Context{
                            .breadcrumbTracker   = mGeneralCommissioningCluster.Cluster(),
                            .failSafeContext     = mContext.failSafeContext,
                            .platformManager     = mContext.platformManager,
                            .deviceControlServer = mContext.deviceControlServer,
                        });
        ReturnErrorOnFailure(mNetwork.Cluster().Init());
        return provider.AddCluster(mNetwork.Registration());
    }

    void Unregister(CodeDrivenDataModelProvider & provider) override
    {
        RootNodeDevice::Unregister(provider);
        if (mNetwork.IsConstructed())
        {
            LogErrorOnFailure(provider.RemoveCluster(&mNetwork.Cluster()));
            mNetwork.Destroy();
        }
    }

private:
    PanelNetworkDriver mDriver;
    LazyRegisteredServerCluster<NetworkCommissioningCluster> mNetwork;
};

// --- Rückmeldungen an Java -----------------------------------------------------------------------------------

JavaVM * sJvm       = nullptr;
jclass sBridgeClass = nullptr;
jmethodID sOnOnOff = nullptr, sOnLevel = nullptr, sOnCommand = nullptr, sOnFabricsChanged = nullptr;

JNIEnv * Env()
{
    return JniReferences::GetInstance().GetEnvForCurrentThread();
}

class JavaCallbacks : public BridgeCallbacks, public FabricTable::Delegate
{
public:
    void OnOnOff(EndpointId endpoint, bool on) override
    {
        if (auto * env = Env())
            env->CallStaticVoidMethod(sBridgeClass, sOnOnOff, static_cast<jint>(endpoint), static_cast<jboolean>(on));
    }
    void OnLevel(EndpointId endpoint, uint8_t level) override
    {
        if (auto * env = Env())
            env->CallStaticVoidMethod(sBridgeClass, sOnLevel, static_cast<jint>(endpoint), static_cast<jint>(level));
    }
    void OnCommand(EndpointId endpoint, int type, int value) override
    {
        if (auto * env = Env())
            env->CallStaticVoidMethod(sBridgeClass, sOnCommand, static_cast<jint>(endpoint), static_cast<jint>(type),
                                      static_cast<jint>(value));
    }
    void OnFabricCommitted(const FabricTable &, FabricIndex) override { FabricsChanged(); }
    void OnFabricRemoved(const FabricTable &, FabricIndex) override { FabricsChanged(); }
    void OnFabricUpdated(const FabricTable &, FabricIndex) override { FabricsChanged(); }

private:
    void FabricsChanged()
    {
        if (auto * env = Env())
            env->CallStaticVoidMethod(sBridgeClass, sOnFabricsChanged);
    }
};

// --- Zustand des Servers ---------------------------------------------------------------------------------------

JavaCallbacks gCallbacks;
DefaultTimerDelegate gTimerDelegate;
Credentials::GroupDataProviderImpl gGroupDataProvider;
DefaultSafeAttributePersistenceProvider gSafeAttributePersistence;
DefaultAttributePersistenceProvider gAttributePersistence;
CommonCaseDeviceServerInitParams gInitParams;
BridgeInstanceInfoProvider gInstanceInfo;
JavaAttestationProvider gJavaAttestation;

std::unique_ptr<CodeDrivenDataModelProvider> gProvider;
std::unique_ptr<PanelRootNode> gRootNode;
std::unique_ptr<AggregatorDevice> gAggregator;
std::map<EndpointId, std::unique_ptr<BridgedDevice>> gDevices;
bool gStarted       = false;
pthread_t gIoThread = {};

void * IoThreadMain(void *)
{
    JNIEnv * env;
    JavaVMAttachArgs args{ .version = JNI_VERSION_1_6, .name = const_cast<char *>("raum Bridge IO"), .group = nullptr };
    sJvm->AttachCurrentThreadAsDaemon(&env, &args);
    PlatformMgr().RunEventLoop();
    sJvm->DetachCurrentThread();
    return nullptr;
}

/** Teilelisten neu melden, damit verbundene Apps neue/entfernte Geräte sofort sehen. */
void PartsListChanged()
{
    for (EndpointId ep : { kRootEndpointId, kAggregatorEndpoint })
    {
        LogErrorOnFailure(InteractionModelEngine::GetInstance()->GetReportingEngine().SetDirty(
            AttributePathParams(ep, Descriptor::Id, Descriptor::Attributes::PartsList::Id)));
    }
}

CHIP_ERROR StartServer(bool customAttestation)
{
    ReturnErrorOnFailure(gInitParams.InitializeStaticResourcesBeforeServerInit());

    gGroupDataProvider.SetStorageDelegate(gInitParams.persistentStorageDelegate);
    gGroupDataProvider.SetSessionKeystore(gInitParams.sessionKeystore);
    ReturnErrorOnFailure(gGroupDataProvider.Init());
    Credentials::SetGroupDataProvider(&gGroupDataProvider);

    ReturnErrorOnFailure(gSafeAttributePersistence.Init(gInitParams.persistentStorageDelegate));
    SetSafeAttributePersistenceProvider(&gSafeAttributePersistence);
    // Eigene Echtheitszertifikate (Keystore) oder – ohne – die Testzertifikate des SDK (nur VID 0xFFF1/PID 0x8000)
    if (customAttestation)
        Credentials::SetDeviceAttestationCredentialsProvider(&gJavaAttestation);
    else
        Credentials::SetDeviceAttestationCredentialsProvider(Credentials::Examples::GetExampleDACProvider());

    ReturnErrorOnFailure(gAttributePersistence.Init(gInitParams.persistentStorageDelegate));
    gProvider = std::make_unique<CodeDrivenDataModelProvider>(*gInitParams.persistentStorageDelegate, gAttributePersistence);

    auto * deviceInfo = GetDeviceInstanceInfoProvider();
    VerifyOrReturnError(deviceInfo != nullptr, CHIP_ERROR_INCORRECT_STATE);
    Server & server = Server::GetInstance();
    gRootNode       = std::make_unique<PanelRootNode>(RootNodeDevice::Context{
              .commissioningWindowManager       = server.GetCommissioningWindowManager(),
              .configurationManager             = ConfigurationMgr(),
              .deviceControlServer              = DeviceControlServer::DeviceControlSvr(),
              .fabricTable                      = server.GetFabricTable(),
              .accessControl                    = server.GetAccessControl(),
              .persistentStorage                = server.GetPersistentStorage(),
              .failSafeContext                  = server.GetFailSafeContext(),
              .deviceInstanceInfoProvider       = *deviceInfo,
              .platformManager                  = PlatformMgr(),
              .groupDataProvider                = gGroupDataProvider,
              .sessionManager                   = server.GetSecureSessionManager(),
              .dnssdServer                      = DnssdServer::Instance(),
              .deviceLoadStatusProvider         = *InteractionModelEngine::GetInstance(),
              .diagnosticDataProvider           = GetDiagnosticDataProvider(),
              .testEventTriggerDelegate         = gInitParams.testEventTriggerDelegate,
              .dacProvider                      = *Credentials::GetDeviceAttestationCredentialsProvider(),
              .eventManagement                  = EventManagement::GetInstance(),
              .safeAttributePersistenceProvider = gSafeAttributePersistence,
              .timerDelegate                    = gTimerDelegate,
#if CHIP_CONFIG_TERMS_AND_CONDITIONS_REQUIRED
              .termsAndConditionsProvider = TermsAndConditionsManager::GetInstance(),
#endif
    });
    ReturnErrorOnFailure(gRootNode->Register(kRootEndpointId, *gProvider, kInvalidEndpointId));
    gAggregator = std::make_unique<AggregatorDevice>(gTimerDelegate);
    ReturnErrorOnFailure(gAggregator->Register(kAggregatorEndpoint, *gProvider, kInvalidEndpointId));

    gInitParams.dataModelProvider             = gProvider.get();
    gInitParams.groupDataProvider             = &gGroupDataProvider;
    gInitParams.operationalServicePort        = CHIP_PORT;
    gInitParams.userDirectedCommissioningPort = CHIP_UDC_PORT;
    ReturnErrorOnFailure(server.Init(gInitParams));
    ReturnErrorOnFailure(server.GetFabricTable().AddFabricDelegate(&gCallbacks));
    return CHIP_NO_ERROR;
}

std::string JsonEscape(CharSpan s)
{
    std::string out;
    for (char c : std::string(s.data(), s.size()))
    {
        if (c == '"' || c == '\\')
            out += '\\';
        if (static_cast<unsigned char>(c) >= 0x20)
            out += c;
    }
    return out;
}

} // namespace
} // namespace raum

using namespace raum;

// --- JNI -----------------------------------------------------------------------------------------------------------

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM * jvm, void * reserved)
{
    sJvm = jvm;
    TEMPORARY_RETURN_IGNORED Platform::MemoryInit();
    JniReferences::GetInstance().SetJavaVm(jvm, "app/raum/matter/bridge/NativeBridge");
    JNIEnv * env = JniReferences::GetInstance().GetEnvForCurrentThread();
    if (env == nullptr)
        return JNI_ERR;
    jclass cls = env->FindClass("app/raum/matter/bridge/NativeBridge");
    if (cls == nullptr)
        return JNI_ERR;
    sBridgeClass      = static_cast<jclass>(env->NewGlobalRef(cls));
    sOnOnOff          = env->GetStaticMethodID(sBridgeClass, "onOnOff", "(IZ)V");
    sOnLevel          = env->GetStaticMethodID(sBridgeClass, "onLevel", "(II)V");
    sOnCommand        = env->GetStaticMethodID(sBridgeClass, "onCommand", "(III)V");
    sOnFabricsChanged = env->GetStaticMethodID(sBridgeClass, "onFabricsChanged", "()V");
    if (sOnOnOff == nullptr || sOnLevel == nullptr || sOnCommand == nullptr || sOnFabricsChanged == nullptr)
        return JNI_ERR;
    if (!InitAttestationJni(env, sBridgeClass))
        return JNI_ERR;
    if (AndroidChipPlatformJNI_OnLoad(jvm, reserved) != CHIP_NO_ERROR)
        return JNI_ERR;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNI_OnUnload(JavaVM *, void *)
{
    // Der Prozess „:bridge“ endet mit der Bibliothek – nichts aufzuräumen
}

/**
 * Server starten (nach new AndroidChipPlatform(...) auf der Java-Seite).
 * Identität aus der raum.-Konfiguration; customAttestation: eigene Zertifikate über attestation*() (Keystore).
 */
JNI_METHOD(jboolean, start)
(JNIEnv * env, jclass, jint vendorId, jint productId, jstring vendorName, jstring productName, jboolean customAttestation)
{
    StackLock lock;
    if (gStarted)
        return JNI_TRUE;
    JniUtfString vendor(env, vendorName), product(env, productName);
    gInstanceInfo.Init(GetDeviceInstanceInfoProvider(), static_cast<uint16_t>(vendorId), static_cast<uint16_t>(productId),
                       vendor.c_str(), product.c_str());
    SetDeviceInstanceInfoProvider(&gInstanceInfo);
    CHIP_ERROR err = StartServer(customAttestation == JNI_TRUE);
    if (err != CHIP_NO_ERROR)
    {
        ChipLogError(AppServer, "raum. Bridge: Start fehlgeschlagen: %" CHIP_ERROR_FORMAT, err.Format());
        return JNI_FALSE;
    }
    gStarted = true;
    pthread_create(&gIoThread, nullptr, IoThreadMain, nullptr);
    ChipLogProgress(AppServer, "raum. Bridge läuft");
    return JNI_TRUE;
}

JNI_METHOD(jboolean, addDevice)
(JNIEnv * env, jclass, jint endpoint, jint kind, jstring uniqueId, jstring name, jstring vendor, jstring product, jint flags,
 jint minC100, jint maxC100)
{
    StackLock lock;
    VerifyOrReturnValue(gStarted, JNI_FALSE);
    auto ep = static_cast<EndpointId>(endpoint);
    VerifyOrReturnValue(ep > kAggregatorEndpoint && gDevices.find(ep) == gDevices.end(), JNI_FALSE);
    JniUtfString u(env, uniqueId), n(env, name), v(env, vendor), p(env, product);
    // flags: Bit 0 Heizen, Bit 1 Kühlen, Bit 2 Auto (Thermostat); Bit 3 Farbe, Bit 4 Farbtemperatur (Leuchte)
    DeviceConfig config;
    config.heat     = (flags & 0x1) != 0;
    config.cool     = (flags & 0x2) != 0;
    config.autoMode = (flags & 0x4) != 0;
    config.color            = (flags & 0x8) != 0;
    config.colorTemperature = (flags & 0x10) != 0;
    if (static_cast<Kind>(kind) == Kind::kColorLight || static_cast<Kind>(kind) == Kind::kColorTempLight)
    {
        // Bei Leuchten: Farbtemperatur-Bereich des Geräts in Mired
        config.minMireds = static_cast<uint16_t>(minC100);
        config.maxMireds = static_cast<uint16_t>(maxC100);
    }
    else
    {
        config.minC100 = static_cast<int16_t>(minC100);
        config.maxC100 = static_cast<int16_t>(maxC100);
    }
    auto device = std::make_unique<BridgedDevice>(static_cast<Kind>(kind), u.c_str(), n.c_str(), v.c_str(), p.c_str(), config,
                                                  gTimerDelegate, gCallbacks);
    CHIP_ERROR err = device->Register(ep, *gProvider, kAggregatorEndpoint);
    if (err != CHIP_NO_ERROR)
    {
        ChipLogError(AppServer, "raum. Bridge: Endpunkt %u nicht angelegt: %" CHIP_ERROR_FORMAT, ep, err.Format());
        device->Unregister(*gProvider);
        return JNI_FALSE;
    }
    gDevices[ep] = std::move(device);
    PartsListChanged();
    return JNI_TRUE;
}

JNI_METHOD(void, removeDevice)(JNIEnv *, jclass, jint endpoint)
{
    StackLock lock;
    auto it = gDevices.find(static_cast<EndpointId>(endpoint));
    VerifyOrReturn(it != gDevices.end());
    it->second->Unregister(*gProvider);
    gDevices.erase(it);
    PartsListChanged();
}

JNI_METHOD(void, updateDevice)
(JNIEnv *, jclass, jint endpoint, jboolean reachable, jboolean on, jint level, jboolean contact, jboolean occupied, jint tempC100,
 jint humidity100, jint setpointC100, jint systemMode, jint coverClosed100ths, jint coverMovement, jint colorMode, jint hue,
 jint saturation, jint colorX, jint colorY, jint mireds)
{
    StackLock lock;
    auto it = gDevices.find(static_cast<EndpointId>(endpoint));
    VerifyOrReturn(it != gDevices.end());
    DeviceState state;
    state.reachable   = reachable;
    state.on          = on;
    state.level       = level;
    state.contact     = contact;
    state.occupied    = occupied;
    state.tempC100    = tempC100;
    state.humidity100 = humidity100;
    state.setpointC100      = setpointC100;
    state.systemMode        = systemMode;
    state.coverClosed100ths = coverClosed100ths;
    state.coverMovement     = coverMovement;
    state.colorMode         = colorMode;
    state.hue               = hue;
    state.saturation        = saturation;
    state.colorX            = colorX;
    state.colorY            = colorY;
    state.mireds            = mireds;
    it->second->Apply(state);
}

JNI_METHOD(jboolean, openWindow)(JNIEnv *, jclass, jint timeoutSeconds)
{
    StackLock lock;
    VerifyOrReturnValue(gStarted, JNI_FALSE);
    auto & manager = Server::GetInstance().GetCommissioningWindowManager();
    if (manager.IsCommissioningWindowOpen())
        manager.CloseCommissioningWindow();
    // Nur im Netzwerk ankündigen (kein Bluetooth) – das Panel ist bereits im LAN/WLAN
    CHIP_ERROR err = manager.OpenBasicCommissioningWindow(System::Clock::Seconds32(static_cast<uint32_t>(timeoutSeconds)),
                                                          CommissioningWindowAdvertisement::kDnssdOnly);
    if (err != CHIP_NO_ERROR)
        ChipLogError(AppServer, "raum. Bridge: Kopplungsfenster: %" CHIP_ERROR_FORMAT, err.Format());
    return err == CHIP_NO_ERROR ? JNI_TRUE : JNI_FALSE;
}

JNI_METHOD(void, closeWindow)(JNIEnv *, jclass)
{
    StackLock lock;
    VerifyOrReturn(gStarted);
    Server::GetInstance().GetCommissioningWindowManager().CloseCommissioningWindow();
}

JNI_METHOD(jboolean, isWindowOpen)(JNIEnv *, jclass)
{
    StackLock lock;
    return gStarted && Server::GetInstance().GetCommissioningWindowManager().IsCommissioningWindowOpen() ? JNI_TRUE : JNI_FALSE;
}

/** Verbundene Apps als JSON: [{"index":1,"vendorId":4937,"label":"…"}] */
JNI_METHOD(jstring, fabrics)(JNIEnv * env, jclass)
{
    StackLock lock;
    std::string json = "[";
    if (gStarted)
    {
        bool first = true;
        auto & table = Server::GetInstance().GetFabricTable();
        // Nur abgeschlossene Kopplungen – eine Fabric vor „CommissioningComplete“ kann noch verfallen
        const FabricIndex pending = table.GetPendingNewFabricIndex();
        for (const auto & fabric : table)
        {
            if (fabric.GetFabricIndex() == pending)
                continue;
            if (!first)
                json += ",";
            first = false;
            json += "{\"index\":" + std::to_string(fabric.GetFabricIndex()) +
                ",\"vendorId\":" + std::to_string(static_cast<unsigned>(fabric.GetVendorId())) + ",\"label\":\"" +
                JsonEscape(fabric.GetFabricLabel()) + "\"}";
        }
    }
    json += "]";
    return env->NewStringUTF(json.c_str());
}

JNI_METHOD(jboolean, removeFabric)(JNIEnv *, jclass, jint fabricIndex)
{
    StackLock lock;
    VerifyOrReturnValue(gStarted, JNI_FALSE);
    CHIP_ERROR err = Server::GetInstance().GetFabricTable().Delete(static_cast<FabricIndex>(fabricIndex));
    return err == CHIP_NO_ERROR ? JNI_TRUE : JNI_FALSE;
}
