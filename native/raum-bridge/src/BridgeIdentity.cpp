/*
 * raum. Bridge – Echtheitsnachweis über die Kotlin-Seite (siehe BridgeIdentity.h).
 */
#include "BridgeIdentity.h"

#include <crypto/CHIPCryptoPAL.h>
#include <lib/support/JniReferences.h>
#include <lib/support/JniTypeWrappers.h>
#include <lib/support/Span.h>
#include <lib/support/logging/CHIPLogging.h>
#include <platform/CHIPDeviceLayer.h>

#include <jni.h>

using namespace chip;

namespace raum {
namespace {

jclass sClass = nullptr;
jmethodID sCd = nullptr, sDac = nullptr, sPai = nullptr, sSign = nullptr;

/** Java aufrufen – ohne Matter-Stapelsperre, damit der Keystore nicht unter der Sperre wartet. */
CHIP_ERROR CallBytes(jmethodID method, jbyteArray argument, MutableByteSpan & out)
{
    VerifyOrReturnError(sClass != nullptr && method != nullptr, CHIP_ERROR_INCORRECT_STATE);
    JNIEnv * env = JniReferences::GetInstance().GetEnvForCurrentThread();
    VerifyOrReturnError(env != nullptr, CHIP_JNI_ERROR_NO_ENV);
    jbyteArray result;
    {
        DeviceLayer::StackUnlock unlock;
        result = static_cast<jbyteArray>(argument ? env->CallStaticObjectMethod(sClass, method, argument)
                                                  : env->CallStaticObjectMethod(sClass, method));
    }
    if (env->ExceptionCheck())
    {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return CHIP_ERROR_INTERNAL;
    }
    VerifyOrReturnError(result != nullptr && env->GetArrayLength(result) > 0, CHIP_ERROR_NOT_FOUND);
    JniByteArray bytes(env, result);
    return CopySpanToMutableSpan(bytes.byteSpan(), out);
}

} // namespace

bool InitAttestationJni(void * rawEnv, void * bridgeClass)
{
    auto * env = static_cast<JNIEnv *>(rawEnv);
    sClass     = static_cast<jclass>(bridgeClass);
    sCd        = env->GetStaticMethodID(sClass, "attestationCd", "()[B");
    sDac       = env->GetStaticMethodID(sClass, "attestationDac", "()[B");
    sPai       = env->GetStaticMethodID(sClass, "attestationPai", "()[B");
    sSign      = env->GetStaticMethodID(sClass, "attestationSign", "([B)[B");
    return sCd != nullptr && sDac != nullptr && sPai != nullptr && sSign != nullptr;
}

CHIP_ERROR JavaAttestationProvider::GetCertificationDeclaration(MutableByteSpan & out)
{
    return CallBytes(sCd, nullptr, out);
}

CHIP_ERROR JavaAttestationProvider::GetDeviceAttestationCert(MutableByteSpan & out)
{
    return CallBytes(sDac, nullptr, out);
}

CHIP_ERROR JavaAttestationProvider::GetProductAttestationIntermediateCert(MutableByteSpan & out)
{
    return CallBytes(sPai, nullptr, out);
}

CHIP_ERROR JavaAttestationProvider::SignWithDeviceAttestationKey(const ByteSpan & message, MutableByteSpan & out)
{
    // Ergebnis: ECDSA P-256 über SHA-256, roh (r || s, 64 Byte) – so erwartet es Matter
    VerifyOrReturnError(out.size() >= Crypto::kP256_ECDSA_Signature_Length_Raw, CHIP_ERROR_BUFFER_TOO_SMALL);
    JNIEnv * env = JniReferences::GetInstance().GetEnvForCurrentThread();
    VerifyOrReturnError(env != nullptr, CHIP_JNI_ERROR_NO_ENV);
    jbyteArray input = env->NewByteArray(static_cast<jsize>(message.size()));
    VerifyOrReturnError(input != nullptr, CHIP_ERROR_NO_MEMORY);
    env->SetByteArrayRegion(input, 0, static_cast<jsize>(message.size()), reinterpret_cast<const jbyte *>(message.data()));
    CHIP_ERROR err = CallBytes(sSign, input, out);
    env->DeleteLocalRef(input);
    ReturnErrorOnFailure(err);
    VerifyOrReturnError(out.size() == Crypto::kP256_ECDSA_Signature_Length_Raw, CHIP_ERROR_INVALID_SIGNATURE);
    return CHIP_NO_ERROR;
}

} // namespace raum
