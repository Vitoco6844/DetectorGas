/*
  PROYECTO: Detector de gas educativo
  PLACA: ESP32 con ESP-WROOM-32

  FUNCIONES:
  - Recibir las lecturas del Arduino Uno.
  - Conectarse al punto de acceso Wi-Fi del teléfono.
  - Mostrar información en el monitor serie.
  - Publicar /estado por HTTPS con clave de acceso.

  CONEXIONES:
  - GPIO16 / RX2: señal del TX del Uno, mediante divisor resistivo.
  - GND: unido al GND del Uno.
  - GPIO17 / TX2: sin conectar.
  - Alimentación de esta placa por su propio USB.

  MONITOR SERIE:
  - ESP32: 115200 baudios.
  - Comunicación con el Uno: 9600 baudios.
*/

#include <WiFi.h>
#include <esp_https_server.h>
extern "C" {
#include <mbedtls/constant_time.h>
}

#if __has_include("wifi_secrets.h")
#include "wifi_secrets.h"
#else
const char* NOMBRE_RED = "ProyectoGas";
const char* CLAVE_RED = "ESCRIBE_AQUI_TU_CLAVE";
#endif

#if __has_include("detector_tls_secrets.h")
#include "detector_tls_secrets.h"
#define DETECTOR_TLS_CONFIGURED
#endif
#include <stdlib.h>
#include <string.h>

// Puerto serie independiente del USB para recibir al Arduino.
HardwareSerial enlaceUno(2);

#ifdef DETECTOR_TLS_CONFIGURED
httpd_handle_t servidor = nullptr;
#endif
SemaphoreHandle_t datosMutex;

// Memoria para reunir los caracteres de cada mensaje.
char mensaje[64];
size_t posicion = 0;
bool descartarMensaje = false;

// Último dato válido recibido.
int lectura = 0;
String estado = "sin_datos";
bool recibido = false;
bool ordenVentiladorConocida = false;
bool ventiladorOrdenado = false;

// Control de conexión y del servidor.
bool estabaConectado = false;

// Tiempos utilizados para detectar desconexiones y reintentar.
unsigned long ultimaRecepcion = 0;
unsigned long ultimoInforme = 0;
unsigned long ultimoIntento = 0;

// Si pasan tres segundos sin mensajes, no mostrar datos antiguos
// como si todavía estuvieran actualizados.
bool hayDatosRecientes() {
  return recibido &&
         millis() - ultimaRecepcion < 3000UL;
}

// El Uno actualizado envía 620,alerta,1. El firmware anterior omite el tercer campo.
void procesarMensaje() {
  char* finLectura;
  long nuevaLectura = strtol(mensaje, &finLectura, 10);
  if (finLectura == mensaje || *finLectura != ',' ||
      nuevaLectura < 0 || nuevaLectura > 1023) {
    return;
  }

  char* estadoRecibido = finLectura + 1;
  char* ordenRecibida = strchr(estadoRecibido, ',');
  if (ordenRecibida != nullptr) {
    *ordenRecibida++ = '\0';
    if (strcmp(ordenRecibida, "0") != 0 && strcmp(ordenRecibida, "1") != 0) {
      return;
    }
  }

  String candidato = estadoRecibido;
  if (candidato != "sin_alerta" && candidato != "alerta" &&
      candidato != "prueba" && candidato != "calentando") {
    return;
  }

  xSemaphoreTake(datosMutex, portMAX_DELAY);
  lectura = nuevaLectura;
  estado = candidato;
  ordenVentiladorConocida = ordenRecibida != nullptr;
  ventiladorOrdenado = ordenVentiladorConocida && *ordenRecibida == '1';
  recibido = true;
  ultimaRecepcion = millis();
  xSemaphoreGive(datosMutex);
}

// Leer lo disponible sin esperar a que llegue una línea completa.
void recibirArduino() {
  while (enlaceUno.available() > 0) {
    char c = enlaceUno.read();

    // El salto de línea marca el final del mensaje.
    if (c == '\n') {
      if (!descartarMensaje && posicion > 0) {
        mensaje[posicion] = '\0';
        procesarMensaje();
      }

      // Prepararse para recibir la siguiente línea.
      posicion = 0;
      descartarMensaje = false;

    } else if (c != '\r' && !descartarMensaje) {
      if (posicion < sizeof(mensaje) - 1) {
        mensaje[posicion++] = c;
      } else {
        // Si la línea es demasiado larga, descartarla completa.
        descartarMensaje = true;
      }
    }
  }
}

#ifdef DETECTOR_TLS_CONFIGURED
// La clave de acceso viaja exclusivamente dentro de HTTPS.
esp_err_t responderEstado(httpd_req_t* solicitud) {
  char autorizacion[sizeof(DETECTOR_AUTHORIZATION)];
  if (httpd_req_get_hdr_value_str(solicitud, "Authorization", autorizacion, sizeof(autorizacion)) != ESP_OK ||
      strlen(autorizacion) != sizeof(DETECTOR_AUTHORIZATION) - 1 ||
      mbedtls_ct_memcmp(autorizacion, DETECTOR_AUTHORIZATION, sizeof(autorizacion) - 1) != 0) {
    httpd_resp_set_status(solicitud, "401 Unauthorized");
    httpd_resp_set_hdr(solicitud, "Cache-Control", "no-store");
    return httpd_resp_sendstr(solicitud, "Acceso rechazado");
  }

  xSemaphoreTake(datosMutex, portMAX_DELAY);
  bool vigente = hayDatosRecientes();

  String respuesta = "{\"lectura\":";
  respuesta += vigente ? String(lectura) : String("null");

  respuesta += ",\"estado\":\"";
  respuesta += vigente ? estado : String("sin_datos");

  respuesta += "\",\"conexion_arduino\":";
  respuesta += vigente ? "true" : "false";

  respuesta += ",\"ventilador_ordenado\":";
  respuesta += vigente && ordenVentiladorConocida
    ? (ventiladorOrdenado ? "true" : "false")
    : "null";
  respuesta += ",\"unidad\":\"ADC_0_1023\"}";

  xSemaphoreGive(datosMutex);
  httpd_resp_set_hdr(solicitud, "Cache-Control", "no-store");
  httpd_resp_set_type(solicitud, "application/json");
  return httpd_resp_sendstr(solicitud, respuesta.c_str());
}

void iniciarServidorSeguro() {
  httpd_ssl_config_t configuracion = HTTPD_SSL_CONFIG_DEFAULT();
  configuracion.servercert = reinterpret_cast<const uint8_t*>(DETECTOR_CERT);
  configuracion.servercert_len = sizeof(DETECTOR_CERT);
  configuracion.prvtkey_pem = reinterpret_cast<const uint8_t*>(DETECTOR_PRIVATE_KEY);
  configuracion.prvtkey_len = sizeof(DETECTOR_PRIVATE_KEY);
  configuracion.httpd.max_open_sockets = 2;
  configuracion.httpd.lru_purge_enable = true;

  esp_err_t resultado = httpd_ssl_start(&servidor, &configuracion);
  if (resultado == ESP_OK) {
    httpd_uri_t estadoUri = {};
    estadoUri.uri = "/estado";
    estadoUri.method = HTTP_GET;
    estadoUri.handler = responderEstado;
    resultado = httpd_register_uri_handler(servidor, &estadoUri);
    if (resultado != ESP_OK) {
      httpd_ssl_stop(servidor);
      servidor = nullptr;
    }
  }
  if (resultado == ESP_OK) {
    Serial.println("[HTTPS] Detector listo.");
  } else {
    Serial.printf("[HTTPS] Error: %s\n", esp_err_to_name(resultado));
  }
}
#endif

// Gestionar el Wi-Fi sin detener la recepción del Arduino.
void revisarWifi() {
  bool conectado = WiFi.status() == WL_CONNECTED;

  // Detectar el momento en que se obtiene la conexión.
  if (conectado && !estabaConectado) {
    Serial.println("[WIFI] CONECTADO.");

    Serial.print("[WIFI] Red: ");
    Serial.println(WiFi.SSID());

    Serial.print("[WIFI] IP del detector: ");
    Serial.println(WiFi.localIP());

#ifdef DETECTOR_TLS_CONFIGURED
    if (WiFi.localIP().toString() == DETECTOR_IP) {
      iniciarServidorSeguro();
    } else {
      Serial.println("[HTTPS] La IP asignada no coincide con el certificado.");
    }
#else
    Serial.println("[HTTPS] Genera detector_tls_secrets.h con la IP mostrada.");
#endif
  }

  if (!conectado && estabaConectado) {
    Serial.println("[WIFI] Conexion perdida.");
#ifdef DETECTOR_TLS_CONFIGURED
    if (servidor != nullptr) {
      httpd_ssl_stop(servidor);
      servidor = nullptr;
    }
#endif
  }

  estabaConectado = conectado;

  // Reintentar cada 15 segundos si todavía no conecta.
  if (!conectado && millis() - ultimoIntento >= 15000UL) {
    ultimoIntento = millis();

    Serial.println("[WIFI] Reintentando conexion...");
    WiFi.reconnect();
  }
}

void setup() {
  // Comunicación con el monitor serie del computador.
  Serial.begin(115200);

  // Comunicación con el Arduino:
  // 9600 baudios, 8 bits, sin paridad, un bit de parada.
  // RX = GPIO16; TX = GPIO17, que queda físicamente libre.
  enlaceUno.begin(9600, SERIAL_8N1, 16, 17);

  datosMutex = xSemaphoreCreateMutex();
  if (datosMutex == nullptr) abort();

  // Conectarse a la red del teléfono, sin crear una red propia.
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(NOMBRE_RED, CLAVE_RED);

  ultimoIntento = millis();

  Serial.print("[WIFI] Intentando conectar a: ");
  Serial.println(NOMBRE_RED);
}

void loop() {
  // Continuar recibiendo las lecturas aunque no haya Wi-Fi.
  recibirArduino();

  // Comprobar la conexión y gestionar los reintentos.
  revisarWifi();

  // Mostrar un informe cada dos segundos.
  if (millis() - ultimoInforme >= 2000UL) {
    ultimoInforme = millis();

    if (WiFi.status() == WL_CONNECTED) {
      Serial.print("[WIFI] Conectado | IP: ");
      Serial.println(WiFi.localIP());
    } else {
      Serial.println("[WIFI] Sin conexion al telefono.");
    }

    if (hayDatosRecientes()) {
      Serial.print("[UNO] Lectura: ");
      Serial.print(lectura);
      Serial.print(" | Estado: ");
      Serial.println(estado);
    } else {
      Serial.println("[UNO] Sin datos recientes.");
    }

    Serial.println();
  }
}
