/*
 * raum. Bridge – Projektkonfiguration des Matter-SDK (Server-Rolle, Android).
 * Test-Hersteller-ID 0xFFF1 / Produkt 0x8000 passen zu den Test-Echtheitszertifikaten des SDK.
 * Vor Auslieferung: eigene VID/PID und eigenes DAC (siehe docs/SECURITY.md).
 */
#pragma once

#define CHIP_DEVICE_CONFIG_ENABLE_COMMISSIONER_DISCOVERY 0
// Kein Kopplungsfenster beim Start – raum. öffnet es nur auf ausdrücklichen Wunsch (PIN-geschützt)
#define CHIP_DEVICE_CONFIG_ENABLE_PAIRING_AUTOSTART 0
#define CHIP_DEVICE_CONFIG_ENABLE_EXTENDED_DISCOVERY 0
#define CHIP_DEVICE_CONFIG_ENABLE_COMMISSIONABLE_DEVICE_TYPE 1
#define CHIP_DEVICE_CONFIG_ENABLE_COMMISSIONABLE_DEVICE_NAME 1

#define CHIP_DEVICE_CONFIG_DEVICE_VENDOR_ID 0xFFF1
#define CHIP_DEVICE_CONFIG_DEVICE_PRODUCT_ID 0x8000
#define CHIP_DEVICE_CONFIG_DEVICE_VENDOR_NAME "raum."
#define CHIP_DEVICE_CONFIG_DEVICE_PRODUCT_NAME "raum. Bridge"
#define CHIP_DEVICE_CONFIG_DEVICE_NAME "raum. Bridge"
// Aggregator (Bridge)
#define CHIP_DEVICE_CONFIG_DEVICE_TYPE 0x000E

#include <CHIPProjectConfig.h>
