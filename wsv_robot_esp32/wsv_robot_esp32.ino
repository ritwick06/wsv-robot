#include <Adafruit_BMP280.h>
#include <Adafruit_HMC5883_U.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <ArduinoJson.h>
#include <ESP32Servo.h>
#include <NetworkClient.h>
#include <WebServer.h>
#include <WiFi.h>
#include <Wire.h>
#define PIN_ESC_L 26
#define PIN_ESC_R 27
#define PIN_BUZZER 32
const char *ssid = "";
const char *password = "";
WebServer server80(80);
WiFiServer server8080(8080);
Servo escL;
Servo escR;
bool use_qmc_clone = false;
Adafruit_MPU6050 mpu;
Adafruit_HMC5883_Unified mag = Adafruit_HMC5883_Unified(12345);
Adafruit_BMP280 bmp;
struct Waypoint {
  double x;
  double y;
  bool isDock;
};
portMUX_TYPE stateMutex = portMUX_INITIALIZER_UNLOCKED;
std::vector<Waypoint> waypoints;
int totalWpCount = 0;
int currentWpIndex = 0;
bool missionActive = false;
volatile double pos_x = 0;
volatile double pos_y = 0;
volatile double vel_x = 0;
volatile double vel_y = 0;
volatile double roll = 0;
volatile double pitch = 0;
volatile double yaw = 0;
volatile int throttleLPercent = 0;
volatile int throttleRPercent = 0;
float Kp = 1.8;
float Ki = 0.05;
float Kd = 0.4;
float integralStatus = 0.0;
float previousError = 0.0;
int baseThrust = 40;
TaskHandle_t TaskWebHandle;
TaskHandle_t TaskSensorsHandle;
TaskHandle_t TaskNavHandle;
TaskHandle_t TaskBuzzerHandle;
volatile int beepQueue = 0;
volatile bool sensors_ok = false;
void playResonantTone(int freq, int duration_ms) {
  long delayValue = 1000000 / freq / 2;
  long cycles = ((long)freq * duration_ms) / 1000;
  for (long i = 0; i < cycles; i++) {
    digitalWrite(PIN_BUZZER, HIGH);
    delayMicroseconds(delayValue);
    digitalWrite(PIN_BUZZER, LOW);
    delayMicroseconds(delayValue);
  }
}
void Task_Buzzer(void *pvParameters) {
  pinMode(PIN_BUZZER, OUTPUT);
  while (true) {
    if (beepQueue == 1) {
      Serial.println("[Buzzer] Startup Beep.");
      playResonantTone(1800, 150);
      vTaskDelay(50 / portTICK_PERIOD_MS);
      playResonantTone(2000, 150);
      vTaskDelay(50 / portTICK_PERIOD_MS);
      playResonantTone(2048, 300);
      beepQueue = 0;
    } else if (beepQueue == 2) {
      Serial.println("[Buzzer] WAYPOINTS RECEIVED! Beeping.");
      playResonantTone(2048, 100);
      vTaskDelay(50 / portTICK_PERIOD_MS);
      playResonantTone(2048, 100);
      vTaskDelay(50 / portTICK_PERIOD_MS);
      playResonantTone(2400, 150);
      beepQueue = 0;
    } else if (beepQueue == 3) {
      playResonantTone(2048, 150);
      beepQueue = 0;
    } else if (beepQueue == 4) {
      Serial.println("[Buzzer] Stop Hook Beep");
      playResonantTone(1000, 300);
      vTaskDelay(100 / portTICK_PERIOD_MS);
      playResonantTone(800, 300);
      beepQueue = 0;
    }
    vTaskDelay(50 / portTICK_PERIOD_MS);
  }
}
void Task_Web(void *pvParameters) {
  server80.on("/status", HTTP_GET, []() {
    float cov = 0;
    portENTER_CRITICAL(&stateMutex);
    bool done =
        (!missionActive && totalWpCount > 0 && currentWpIndex >= totalWpCount);
    int activeIdx = currentWpIndex;
    int twp = totalWpCount;
    if (twp > 0)
      cov = (float)activeIdx / twp * 100.0;
    double t_roll = roll, t_pitch = pitch, t_yaw = yaw;
    double t_px = pos_x, t_py = pos_y;
    double tL = throttleLPercent, tR = throttleRPercent;
    portEXIT_CRITICAL(&stateMutex);
    char jsonBuf[256];
    snprintf(jsonBuf, sizeof(jsonBuf),
             "{\"wp\":%d,\"total\":%d,\"coverage\":%.1f,\"done\":%s,"
             "\"posX\":%.2f,\"posY\":%.2f,\"roll\":%.1f,\"pitch\":%.1f,"
             "\"yaw\":%.1f,\"throttleL\":%.1f,\"throttleR\":%.1f}",
             activeIdx, twp, cov, done ? "true" : "false", t_px, t_py, t_roll,
             t_pitch, t_yaw, tL, tR);
    server80.send(200, "application/json", jsonBuf);
  });
  server80.on("/stop", HTTP_GET, []() {
    portENTER_CRITICAL(&stateMutex);
    missionActive = false;
    waypoints.clear();
    totalWpCount = 0;
    currentWpIndex = 0;
    throttleLPercent = 0;
    throttleRPercent = 0;
    escL.writeMicroseconds(1000);
    escR.writeMicroseconds(1000);
    portEXIT_CRITICAL(&stateMutex);
    beepQueue = 4;
    server80.send(200, "text/plain", "Stopped");
  });
  server80.begin();
  server8080.begin();
  while (true) {
    server80.handleClient();
    WiFiClient client = server8080.available();
    if (client) {
      String jsonRaw = "";
      while (client.connected()) {
        if (client.available()) {
          char c = client.read();
          jsonRaw += c;
          if (c == '\n')
            break;
        }
      }
      if (jsonRaw.length() > 5) {
        DynamicJsonDocument doc(32768);
        DeserializationError err = deserializeJson(doc, jsonRaw);
        if (!err) {
          portENTER_CRITICAL(&stateMutex);
          waypoints.clear();
          JsonArray wps = doc["waypoints"].as<JsonArray>();
          for (JsonObject w : wps) {
            Waypoint wp;
            wp.x = w["x"];
            wp.y = w["y"];
            wp.isDock = w["dock"];
            waypoints.push_back(wp);
          }
          totalWpCount = waypoints.size();
          currentWpIndex = 0;
          if (waypoints.size() > 0) {
            pos_x = waypoints[0].x;
            pos_y = waypoints[0].y;
          } else {
            pos_x = 0;
            pos_y = 0;
          }
          vel_x = 0;
          vel_y = 0;
          missionActive = true;
          portEXIT_CRITICAL(&stateMutex);
          client.println("OK");
          Serial.println("[NETWORK] Waypoints array parsed and saved into "
                         "Memory. Triggering Buzzer.");
          beepQueue = 2;
        } else {
          client.println("PARSE_ERROR");
          Serial.println("[NETWORK] Error parsing Waypoint JSON.");
        }
      }
      client.stop();
    }
    vTaskDelay(10 / portTICK_PERIOD_MS);
  }
}
void Task_Sensors(void *pvParameters) {
  unsigned long lastTime = micros();
  while (true) {
    unsigned long now = micros();
    double dt = (now - lastTime) / 1000000.0;
    lastTime = now;
    if (sensors_ok) {
      sensors_event_t a, g, temp;
      if (mpu.getEvent(&a, &g, &temp)) {
        double accelPitch = atan2(-a.acceleration.x,
                                  sqrt(a.acceleration.y * a.acceleration.y +
                                       a.acceleration.z * a.acceleration.z)) *
                            180.0 / PI;
        double accelRoll =
            atan2(a.acceleration.y, a.acceleration.z) * 180.0 / PI;
        double gyroPitchRate = g.gyro.y * 180.0 / PI;
        double gyroRollRate = g.gyro.x * 180.0 / PI;
        portENTER_CRITICAL(&stateMutex);
        pitch = 0.96 * (pitch + gyroPitchRate * dt) + 0.04 * accelPitch;
        roll = 0.96 * (roll + gyroRollRate * dt) + 0.04 * accelRoll;
        double rPitch = pitch * PI / 180.0;
        double rRoll = roll * PI / 180.0;
        portEXIT_CRITICAL(&stateMutex);
        double rawX = 0, rawY = 0, rawZ = 0;
        bool magDataReady = false;
        if (use_qmc_clone) {
          Wire.beginTransmission(0x0D);
          Wire.write(0x00);
          if (Wire.endTransmission() == 0) {
            Wire.requestFrom((uint16_t)0x0D, (uint8_t)6);
            if (Wire.available() == 6) {
              rawX = (int16_t)(Wire.read() | (Wire.read() << 8));
              rawY = (int16_t)(Wire.read() | (Wire.read() << 8));
              rawZ = (int16_t)(Wire.read() | (Wire.read() << 8));
              magDataReady = true;
            }
          }
        } else {
          sensors_event_t m;
          if (mag.getEvent(&m)) {
            rawX = m.magnetic.x;
            rawY = m.magnetic.y;
            rawZ = m.magnetic.z;
            magDataReady = true;
          }
        }
        if (magDataReady) {
          static double fX = 0, fY = 0, fZ = 0;
          if (fX == 0 && fY == 0) {
            fX = rawX;
            fY = rawY;
            fZ = rawZ;
          }
          fX = 0.85 * fX + 0.15 * rawX;
          fY = 0.85 * fY + 0.15 * rawY;
          fZ = 0.85 * fZ + 0.15 * rawZ;
          double compX = fX * cos(rPitch) + fY * sin(rRoll) * sin(rPitch) +
                         fZ * cos(rRoll) * sin(rPitch);
          double compY = fY * cos(rRoll) - fZ * sin(rRoll);
          double heading = atan2(compY, compX);
          if (heading < 0)
            heading += 2 * PI;
          portENTER_CRITICAL(&stateMutex);
          yaw = heading * 180.0 / PI;
          portEXIT_CRITICAL(&stateMutex);
        }
      }
    }
    if (!sensors_ok) {
      double turnRate = (throttleRPercent - throttleLPercent) * 2.0;
      portENTER_CRITICAL(&stateMutex);
      yaw += turnRate * dt;
      if (yaw >= 360)
        yaw -= 360;
      if (yaw < 0)
        yaw += 360;
      portEXIT_CRITICAL(&stateMutex);
    }
    double averageThrust = (throttleLPercent + throttleRPercent) / 2.0;
    double estimatedSpeed = 0;
    if (averageThrust > 5.0) {
      estimatedSpeed = (averageThrust / 100.0) * 2.0;
    }
    double yawRads = yaw * PI / 180.0;
    vel_x = estimatedSpeed * cos(yawRads);
    vel_y = estimatedSpeed * sin(yawRads);
    portENTER_CRITICAL(&stateMutex);
    pos_x += vel_x * dt;
    pos_y += vel_y * dt;
    portEXIT_CRITICAL(&stateMutex);
    vTaskDelay(20 / portTICK_PERIOD_MS);
  }
}
void Task_Navigation(void *pvParameters) {
  while (true) {
    portENTER_CRITICAL(&stateMutex);
    bool active = missionActive;
    int idx = currentWpIndex;
    int twpCount = totalWpCount;
    Waypoint target;
    double px = pos_x, py = pos_y;
    double currentYaw = yaw;
    portEXIT_CRITICAL(&stateMutex);
    if (active && idx < twpCount) {
      portENTER_CRITICAL(&stateMutex);
      target = waypoints[idx];
      portEXIT_CRITICAL(&stateMutex);
      double targetHeading = atan2(target.y - py, target.x - px) * 180 / PI;
      if (targetHeading < 0)
        targetHeading += 360.0;
      double distance = sqrt(pow(target.x - px, 2) + pow(target.y - py, 2));
      if (distance < 2.0) {
        beepQueue = 3;
        portENTER_CRITICAL(&stateMutex);
        currentWpIndex++;
        if (currentWpIndex >= totalWpCount) {
          missionActive = false;
          beepQueue = 4;
        }
        portEXIT_CRITICAL(&stateMutex);
      } else {
        double error = targetHeading - currentYaw;
        if (error > 180)
          error -= 360;
        else if (error < -180)
          error += 360;
        integralStatus += error;
        if (integralStatus > 100)
          integralStatus = 100;
        if (integralStatus < -100)
          integralStatus = -100;
        float derivative = error - previousError;
        previousError = error;
        float correction =
            (Kp * error) + (Ki * integralStatus) + (Kd * derivative);
        int motL = baseThrust - correction;
        int motR = baseThrust + correction;
        int MAX_SAFE_SPEED = 50;
        if (motL > MAX_SAFE_SPEED)
          motL = MAX_SAFE_SPEED;
        if (motL < 0)
          motL = 0;
        if (motR > MAX_SAFE_SPEED)
          motR = MAX_SAFE_SPEED;
        if (motR < 0)
          motR = 0;
        portENTER_CRITICAL(&stateMutex);
        throttleLPercent = motL;
        throttleRPercent = motR;
        portEXIT_CRITICAL(&stateMutex);
        int pwm_L = map(motL, 0, 100, 1000, 2000);
        int pwm_R = map(motR, 0, 100, 1000, 2000);
        escL.writeMicroseconds(pwm_L);
        escR.writeMicroseconds(pwm_R);
      }
    } else {
      portENTER_CRITICAL(&stateMutex);
      throttleLPercent = 0;
      throttleRPercent = 0;
      portEXIT_CRITICAL(&stateMutex);
      escL.writeMicroseconds(1000);
      escR.writeMicroseconds(1000);
    }
    vTaskDelay(50 / portTICK_PERIOD_MS);
  }
}
void setup() {
  Serial.begin(115200);
  ESP32PWM::allocateTimer(0);
  ESP32PWM::allocateTimer(1);
  escL.setPeriodHertz(50);
  escR.setPeriodHertz(50);
  escL.attach(PIN_ESC_L, 1000, 2000);
  escR.attach(PIN_ESC_R, 1000, 2000);
  escL.writeMicroseconds(1000);
  escR.writeMicroseconds(1000);
  pinMode(PIN_BUZZER, OUTPUT);
  playResonantTone(2048, 150);
  Serial.println("\n[FW] Holding 1000us signal to Arm SimonK ESCs for 3s...");
  delay(3000);
  Serial.println("[FW] Motors Armed. Booting I2C and WiFi.");
  WiFi.begin(ssid, password);
  int wifiAttempts = 0;
  while (WiFi.status() != WL_CONNECTED && wifiAttempts < 20) {
    delay(500);
    Serial.print(".");
    wifiAttempts++;
  }
  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("\n[FW] WiFi IP: ");
    Serial.println(WiFi.localIP());
  }
  Wire.begin(21, 22);
  bool mpuOk = mpu.begin();
  if (!mpuOk) {
    Serial.println("[FW] MPU6050 NOT DETECTED!");
  } else {
    mpu.setAccelerometerRange(MPU6050_RANGE_2_G);
    mpu.setGyroRange(MPU6050_RANGE_250_DEG);
    mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);
  }
  bool magOk = false;
  Wire.beginTransmission(0x0D);
  if (Wire.endTransmission() == 0) {
    Serial.println("[FW] Detected QMC5883L (HW-246 Clone) Compass!");
    use_qmc_clone = true;
    magOk = true;
    Wire.beginTransmission(0x0D);
    Wire.write(0x0B);
    Wire.write(0x01);
    Wire.endTransmission();
    Wire.beginTransmission(0x0D);
    Wire.write(0x09);
    Wire.write(0x1D);
    Wire.endTransmission();
  } else {
    if (mag.begin()) {
      Serial.println("[FW] Detected Authentic HMC5883L Compass!");
      magOk = true;
    } else {
      Serial.println("[FW] NO COMPASS DETECTED!");
    }
  }
  bool bmpOk = bmp.begin();
  if (!bmpOk) {
    Serial.println("[FW] BMP280 NOT DETECTED!");
  } else {
    bmp.setSampling(Adafruit_BMP280::MODE_NORMAL, Adafruit_BMP280::SAMPLING_X2,
                    Adafruit_BMP280::SAMPLING_X16, Adafruit_BMP280::FILTER_X16,
                    Adafruit_BMP280::STANDBY_MS_500);
  }
  if (mpuOk && magOk) {
    sensors_ok = true;
    Serial.println("[FW] All primary sensors booted successfully.");
  } else {
    Serial.println("[FW] WARNING: Missing Sensors! Hardware task disabled.");
  }
  xTaskCreatePinnedToCore(Task_Web, "WebTask", 8192, NULL, 1, &TaskWebHandle,
                          0);
  xTaskCreatePinnedToCore(Task_Buzzer, "Buzzer", 2048, NULL, 1,
                          &TaskBuzzerHandle, 0);
  xTaskCreatePinnedToCore(Task_Sensors, "Sensors", 4096, NULL, 2,
                          &TaskSensorsHandle, 1);
  xTaskCreatePinnedToCore(Task_Navigation, "Nav", 4096, NULL, 3, &TaskNavHandle,
                          1);
  beepQueue = 1;
  Serial.println("[FW] Setup Complete. Handing over to RTOS Scheduler.");
}
void loop() { vTaskDelete(NULL); }
