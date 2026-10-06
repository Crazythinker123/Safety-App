/**
 * SENTINEL Hardware — ESP32 BLE Trigger Device
 * ─────────────────────────────────────────────
 * Components:
 *   • ESP32 dev board (any variant)
 *   • Push button between BUTTON_PIN and GND
 *   • (Optional) LED on LED_PIN for status feedback
 *
 * Wiring:
 *   Button  → GPIO 0  (BOOT button on most dev boards — no extra wiring needed)
 *             OR any GPIO → 10kΩ pull-up resistor → 3.3V  (if using external button)
 *   LED     → GPIO 2  (built-in LED on most dev boards)
 *   Mic     → GPIO 34 (ADC1_CH6 — reserved for future audio feature)
 *
 * Behaviour:
 *   • On power-up: advertises as "SENTINEL-HW" over BLE
 *   • LED off      = not connected
 *   • LED slow blink = connected, standby
 *   • Hold button 1.5 s → sends "SENTINEL_TRIGGER" BLE notification
 *   • LED rapid flash   = trigger sent confirmation
 *
 * Arduino IDE setup:
 *   1. Install "esp32" board package by Espressif (Boards Manager)
 *   2. Select board: "ESP32 Dev Module" (or your specific variant)
 *   3. Partition scheme: Default
 *   4. No extra libraries needed — BLE is built into the ESP32 Arduino core
 *
 * Service UUID        : 12345678-1234-5678-1234-56789abcdef0
 * Characteristic UUID : abcdef01-1234-5678-1234-56789abcdef0
 * (Must match BleManager.kt in the Android app)
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ── Pin config ────────────────────────────────────────────────────────────────
#define BUTTON_PIN  0    // GPIO 0 = BOOT button (active LOW with internal pull-up)
#define LED_PIN     2    // GPIO 2 = built-in LED
#define MIC_PIN    34    // GPIO 34 = ADC (reserved for future audio recording)

// ── UUIDs — must exactly match Android BleManager.kt ─────────────────────────
#define SERVICE_UUID        "12345678-1234-5678-1234-56789abcdef0"
#define CHARACTERISTIC_UUID "abcdef01-1234-5678-1234-56789abcdef0"

// ── Timing ────────────────────────────────────────────────────────────────────
#define HOLD_DURATION_MS  1500   // Hold duration before trigger fires (ms)
#define COOLDOWN_MS      10000   // Minimum gap between triggers (ms)

// ── BLE globals ───────────────────────────────────────────────────────────────
BLEServer*         pServer         = nullptr;
BLECharacteristic* pCharacteristic = nullptr;
bool               deviceConnected    = false;
bool               oldDeviceConnected = false;

// ── Button state ──────────────────────────────────────────────────────────────
unsigned long buttonPressStartMs = 0;
bool          buttonHeld         = false;
bool          triggeredThisPress = false;
unsigned long lastTriggerMs      = 0;

// ── BLE Server callbacks ──────────────────────────────────────────────────────
class SentinelServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) override {
    deviceConnected = true;
    Serial.println("[BLE] Phone connected");
  }
  void onDisconnect(BLEServer* pServer) override {
    deviceConnected = false;
    Serial.println("[BLE] Phone disconnected — restarting advertising");
  }
};

// ── Setup ─────────────────────────────────────────────────────────────────────
void setup() {
  Serial.begin(115200);
  pinMode(BUTTON_PIN, INPUT_PULLUP);   // Active LOW
  pinMode(LED_PIN,    OUTPUT);
  digitalWrite(LED_PIN, LOW);

  Serial.println("[SENTINEL-HW] Initialising BLE...");

  BLEDevice::init("SENTINEL-HW");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new SentinelServerCallbacks());

  BLEService* pService = pServer->createService(SERVICE_UUID);

  pCharacteristic = pService->createCharacteristic(
    CHARACTERISTIC_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  pCharacteristic->addDescriptor(new BLE2902());

  pService->start();

  BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);

  // Set device name in the advertising data so Android can find it
  // without needing a scan response (setScanResponse was causing "Not Found")
  BLEAdvertisementData advData;
  advData.setFlags(0x06);                    // BLE General Discoverable + BR/EDR Not Supported
  advData.setCompleteServices(BLEUUID(SERVICE_UUID));
  advData.setName("SENTINEL-HW");            // Name in main advertising packet ← key fix
  pAdvertising->setAdvertisementData(advData);

  pAdvertising->setScanResponse(true);        // Also enable scan response for full name
  pAdvertising->setMinPreferred(0x06);
  BLEDevice::startAdvertising();

  Serial.println("[SENTINEL-HW] Ready — advertising as SENTINEL-HW");
}

// ── Main loop ─────────────────────────────────────────────────────────────────
void loop() {
  unsigned long now         = millis();
  bool          buttonDown  = (digitalRead(BUTTON_PIN) == LOW);  // Active LOW

  // ── Button press detection ───────────────────────────────────────────────
  if (buttonDown) {
    if (!buttonHeld) {
      // Button just pressed
      buttonHeld         = true;
      triggeredThisPress = false;
      buttonPressStartMs = now;
      Serial.println("[BTN] Press started");
    }

    unsigned long heldFor = now - buttonPressStartMs;

    if (heldFor >= HOLD_DURATION_MS && !triggeredThisPress) {
      // Cooldown check
      if (now - lastTriggerMs >= COOLDOWN_MS) {
        triggeredThisPress = true;
        lastTriggerMs      = now;
        fireTrigger();
      } else {
        Serial.println("[BTN] Cooldown active — trigger suppressed");
      }
    }
  } else {
    if (buttonHeld) {
      Serial.println("[BTN] Released");
    }
    buttonHeld = false;
  }

  // ── BLE reconnect ────────────────────────────────────────────────────────
  if (!deviceConnected && oldDeviceConnected) {
    delay(300);                           // Let stack settle
    pServer->startAdvertising();
    Serial.println("[BLE] Restarting advertising...");
  }
  oldDeviceConnected = deviceConnected;

  // ── LED heartbeat ────────────────────────────────────────────────────────
  if (deviceConnected) {
    // Slow blink = connected standby
    digitalWrite(LED_PIN, (now / 1000) % 2 == 0 ? HIGH : LOW);
  } else {
    // Fast blink = searching
    digitalWrite(LED_PIN, (now / 300) % 2 == 0 ? HIGH : LOW);
  }

  delay(10);
}

// ── Trigger ───────────────────────────────────────────────────────────────────
void fireTrigger() {
  Serial.println("*** SENTINEL TRIGGERED ***");

  if (deviceConnected) {
    pCharacteristic->setValue("SENTINEL_TRIGGER");
    pCharacteristic->notify();
    Serial.println("[BLE] Notification sent to phone");
  } else {
    Serial.println("[BLE] No phone connected — notification not sent");
  }

  // LED confirmation: 4 rapid flashes
  for (int i = 0; i < 4; i++) {
    digitalWrite(LED_PIN, HIGH);
    delay(80);
    digitalWrite(LED_PIN, LOW);
    delay(80);
  }
}
