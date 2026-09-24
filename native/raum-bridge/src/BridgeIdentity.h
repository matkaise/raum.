/*
 * raum. Bridge – Identität (Hersteller/Produkt aus raum.-Konfiguration) und Echtheitsnachweis (DAC).
 *
 * Hersteller-ID, Produkt-ID und Namen setzt raum. zur Laufzeit (gradle.properties → BuildConfig → JNI), statt sie in
 * die Bibliothek einzukompilieren. Eigene Echtheitszertifikate liefert die Kotlin-Seite; signiert wird im Android
 * Keystore – der private DAC-Schlüssel erreicht nie den nativen Speicher.
 */
#pragma once

#include <credentials/DeviceAttestationCredsProvider.h>
#include <platform/DeviceInstanceInfoProvider.h>

#include <string>

namespace raum {

/** Reicht alles an die Plattform durch – außer Hersteller und Produkt. */
class BridgeInstanceInfoProvider : public chip::DeviceLayer::DeviceInstanceInfoProvider
{
public:
    void Init(chip::DeviceLayer::DeviceInstanceInfoProvider * platform, uint16_t vendorId, uint16_t productId, std::string vendorName,
              std::string productName)
    {
        mPlatform    = platform;
        mVendorId    = vendorId;
        mProductId   = productId;
        mVendorName  = std::move(vendorName);
        mProductName = std::move(productName);
    }

    CHIP_ERROR GetVendorName(char * buf, size_t bufSize) override { return Copy(mVendorName, buf, bufSize); }
    CHIP_ERROR GetVendorId(uint16_t & vendorId) override
    {
        vendorId = mVendorId;
        return CHIP_NO_ERROR;
    }
    CHIP_ERROR GetProductName(char * buf, size_t bufSize) override { return Copy(mProductName, buf, bufSize); }
    CHIP_ERROR GetProductId(uint16_t & productId) override
    {
        productId = mProductId;
        return CHIP_NO_ERROR;
    }
    CHIP_ERROR GetPartNumber(char * buf, size_t bufSize) override { return mPlatform->GetPartNumber(buf, bufSize); }
    CHIP_ERROR GetProductURL(char * buf, size_t bufSize) override { return mPlatform->GetProductURL(buf, bufSize); }
    CHIP_ERROR GetProductLabel(char * buf, size_t bufSize) override { return mPlatform->GetProductLabel(buf, bufSize); }
    CHIP_ERROR GetSerialNumber(char * buf, size_t bufSize) override { return mPlatform->GetSerialNumber(buf, bufSize); }
    CHIP_ERROR GetManufacturingDate(uint16_t & year, uint8_t & month, uint8_t & day) override
    {
        return mPlatform->GetManufacturingDate(year, month, day);
    }
    CHIP_ERROR GetHardwareVersion(uint16_t & hardwareVersion) override { return mPlatform->GetHardwareVersion(hardwareVersion); }
    CHIP_ERROR GetHardwareVersionString(char * buf, size_t bufSize) override
    {
        return mPlatform->GetHardwareVersionString(buf, bufSize);
    }
    CHIP_ERROR GetRotatingDeviceIdUniqueId(chip::MutableByteSpan & uniqueIdSpan) override
    {
        return mPlatform->GetRotatingDeviceIdUniqueId(uniqueIdSpan);
    }

private:
    chip::DeviceLayer::DeviceInstanceInfoProvider * mPlatform = nullptr;
    uint16_t mVendorId = 0, mProductId = 0;
    std::string mVendorName, mProductName;

    static CHIP_ERROR Copy(const std::string & value, char * buf, size_t bufSize)
    {
        VerifyOrReturnError(value.size() < bufSize, CHIP_ERROR_BUFFER_TOO_SMALL);
        memcpy(buf, value.c_str(), value.size() + 1);
        return CHIP_NO_ERROR;
    }
};

/** Echtheitsnachweis über die Kotlin-Seite (app.raum.matter.bridge.NativeBridge.attestation*). */
class JavaAttestationProvider : public chip::Credentials::DeviceAttestationCredentialsProvider
{
public:
    CHIP_ERROR GetCertificationDeclaration(chip::MutableByteSpan & out) override;
    CHIP_ERROR GetFirmwareInformation(chip::MutableByteSpan & out) override
    {
        out.reduce_size(0);
        return CHIP_NO_ERROR;
    }
    CHIP_ERROR GetDeviceAttestationCert(chip::MutableByteSpan & out) override;
    CHIP_ERROR GetProductAttestationIntermediateCert(chip::MutableByteSpan & out) override;
    CHIP_ERROR SignWithDeviceAttestationKey(const chip::ByteSpan & message, chip::MutableByteSpan & out) override;
};

/** Java-Methoden auflösen (aus JNI_OnLoad). */
bool InitAttestationJni(void * env, void * bridgeClass);

} // namespace raum
