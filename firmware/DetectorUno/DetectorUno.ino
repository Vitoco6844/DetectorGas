/*
  PROYECTO: Detector de gas educativo
  PLACA: Arduino Uno

  FUNCIONES:
  - Leer el sensor conectado a A0.
  - Controlar los LED y el buzzer.
  - Enviar la lectura y el estado al ESP32.

  CONEXIONES:
  - Sensor AO: A0.
  - LED verde: D4, con resistencia de 220 ohmios.
  - LED rojo: D5, con resistencia de 220 ohmios.
  - Control del buzzer: D8.
  - Orden del ventilador: D7 hacia la etapa de control.
  - TX del Uno, pin 1: divisor resistivo hacia RX2 del ESP32.
  - GND del Uno y del ESP32 unidos.

  FORMATO ENVIADO AL ESP32:
  lectura,estado,orden_ventilador
  Ejemplo: 620,alerta,1

  IMPORTANTE:
  Las lecturas son valores ADC de 0 a 1023, no ppm.
  Los umbrales son ejemplos para el prototipo.
*/

// Pines del circuito.
const byte PIN_SENSOR = A0;
const byte LED_VERDE = 4;
const byte LED_ROJO = 5;
const byte PIN_BUZZER = 8;
const byte PIN_VENTILADOR = 7;

// En Wokwi, usar ambos valores como aparecen aquí.
// En el montaje físico, confirmar el buzzer y su circuito controlador.
const bool USAR_BUZZER = true;
const bool BUZZER_ACTIVO = false;

// Umbrales de demostración: no son límites de seguridad.
// Usar dos valores evita encendidos y apagados continuos
// cuando la lectura oscila cerca del umbral.
const int UMBRAL_ALERTA = 500;
const int UMBRAL_RETORNO = 450;

// Espera inicial de 3 minutos para esta demostración.
// NO garantiza la estabilización ni sustituye el
// acondicionamiento indicado por el fabricante.
// Para una prueba rápida SOLO EN WOKWI, puede ponerse 0UL.
const unsigned long ESPERA_INICIAL_MS = 180000UL;

// Intervalo entre lecturas: 250 ms = cuatro lecturas por segundo.
const unsigned long INTERVALO_LECTURA_MS = 250UL;

// Variables que conservan el estado del sistema.
bool alerta = false;
bool prueba = false;

unsigned long inicio;
unsigned long inicioPrueba = 0;
unsigned long ultimaLectura = 0;

void setup() {
  // Configurar los pines que controlan las salidas.
  pinMode(LED_VERDE, OUTPUT);
  pinMode(LED_ROJO, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);
  digitalWrite(PIN_VENTILADOR, LOW);
  pinMode(PIN_VENTILADOR, OUTPUT);

  // Al encender la placa, comenzar con las salidas apagadas.
  digitalWrite(LED_VERDE, LOW);
  digitalWrite(LED_ROJO, LOW);
  digitalWrite(PIN_BUZZER, LOW);

  // Esta velocidad debe coincidir con la recepción del ESP32.
  // También permite ver los mensajes en el monitor serie del Uno.
  Serial.begin(9600);

  inicio = millis();
}

void loop() {
  unsigned long ahora = millis();

  // Permitir una prueba sin utilizar gas:
  // escribir T en el monitor serie del Arduino Uno.
  while (Serial.available() > 0) {
    char comando = Serial.read();

    if (comando == 'T' || comando == 't') {
      prueba = true;
      inicioPrueba = ahora;
    }
  }

  // La prueba dura tres segundos.
  if (prueba && ahora - inicioPrueba >= 3000UL) {
    prueba = false;
  }

  // Esperar hasta la próxima lectura sin usar delay().
  if (ahora - ultimaLectura < INTERVALO_LECTURA_MS) {
    return;
  }
  ultimaLectura = ahora;

  // Leer la señal analógica del módulo de gas.
  int lectura = analogRead(PIN_SENSOR);

  // Determinar si seguimos dentro de la espera inicial.
  bool calentando = ahora - inicio < ESPERA_INICIAL_MS;

  if (calentando) {
    // Durante esta espera no evaluar el umbral de gas.
    alerta = false;
  } else if (lectura >= UMBRAL_ALERTA) {
    alerta = true;
  } else if (lectura <= UMBRAL_RETORNO) {
    alerta = false;
  }
  // Entre ambos umbrales se conserva el estado anterior.

  // La salida de alarma se activa por lectura elevada o por prueba.
  bool activarAlarma = alerta || prueba;

  digitalWrite(LED_ROJO, activarAlarma ? HIGH : LOW);
  digitalWrite(PIN_VENTILADOR, activarAlarma ? HIGH : LOW);

  // Verde parpadeando: espera inicial.
  // Verde fijo: no hay alerta según el umbral configurado.
  // Verde apagado: alerta o prueba.
  bool encenderVerde =
    !activarAlarma &&
    (!calentando || ((ahora / 500UL) % 2 == 0));

  digitalWrite(LED_VERDE, encenderVerde ? HIGH : LOW);

  // Controlar el sonido según el tipo de buzzer.
  if (USAR_BUZZER) {
    if (BUZZER_ACTIVO) {
      // Un buzzer activo genera su propio tono.
      // Se presupone un controlador que se activa con HIGH.
      digitalWrite(PIN_BUZZER, activarAlarma ? HIGH : LOW);
    } else if (activarAlarma) {
      // Un buzzer pasivo necesita una señal de frecuencia.
      // Emitir pitidos de 2000 Hz, de 150 ms de duración.
      tone(PIN_BUZZER, 2000, 150);
    } else {
      noTone(PIN_BUZZER);
      digitalWrite(PIN_BUZZER, LOW);
    }
  }

  // Elegir el nombre del estado que recibirá el ESP32.
  const char* estado;

  if (prueba) {
    estado = "prueba";
  } else if (calentando) {
    estado = "calentando";
  } else if (alerta) {
    estado = "alerta";
  } else {
    estado = "sin_alerta";
  }

  // Enviar una línea completa por TX.
  // No añadir otros mensajes aquí: el ESP32 espera este formato.
  Serial.print(lectura);
  Serial.print(',');
  Serial.print(estado);
  Serial.print(',');
  Serial.println(activarAlarma ? 1 : 0);
}
