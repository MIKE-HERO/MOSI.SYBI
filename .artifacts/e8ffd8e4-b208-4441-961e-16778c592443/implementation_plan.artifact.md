# Implementación de Reconocimiento Facial Robusto

Mejorar el sistema de reconocimiento facial actual para evitar falsos positivos cuando se registren múltiples pacientes o cuando otras personas intenten iniciar sesión.

## User Review Required

> [!IMPORTANT]
> El sistema actual aceptaba cualquier imagen con las mismas dimensiones (`bmp1.width == bmp2.width`). Con la nueva implementación:
> 1. Se utilizará **ML Kit Face Detection** para detectar y recortar estrictamente la región del rostro tanto en el registro (`FaceRegistrationActivity`) como en el login (`FaceLoginActivity`).
> 2. Se aplicará un **algoritmo de similitud por Coseno / Correlación Cruzada Normalizada** sobre imágenes normalizadas en escala de grises.
> 3. Se requerirá un **umbral mínimo de similitud (ej. 0.78)** para validar la identidad, rechazando rostros desconocidos o de otras personas.

## Proposed Changes

### Componente de Reconocimiento Facial

#### [MODIFY] [FaceRegistrationActivity.kt](file:///C:/Users/1000CXSTAS01/Desktop/kiosco/app/src/main/java/com/sybi/mosi/FaceRegistrationActivity.kt)
- Integrar ML Kit Face Detection en el proceso de captura de foto.
- Detectar el rostro, verificar que exista y recortar la imagen al bounding box del rostro antes de guardarla en Base64.

#### [MODIFY] [FaceLoginActivity.kt](file:///C:/Users/1000CXSTAS01/Desktop/kiosco/app/src/main/java/com/sybi/mosi/FaceLoginActivity.kt)
- Al detectar un rostro en tiempo real, recortar el bitmap de la cámara usando el bounding box.
- Reemplazar la función `compararFotos` actual por un cálculo robusto de similitud (Coseno / Correlación Normalizada) sobre bitmaps de rostros normalizados.
- Comparar el rostro capturado contra todos los pacientes registrados y validar que supere el umbral estricto (>= 0.78).

## Verification Plan

### Automated Tests
- Compilación del proyecto con Gradle (`app:assembleDebug`) para verificar que no existan errores de sintaxis o referencias.

### Manual Verification
- Registrar un paciente con su rostro real.
- Intentar iniciar sesión con un rostro diferente o una persona distinta para verificar que el sistema lo rechaza ("No se encontró ningún paciente con este rostro").
- Iniciar sesión con el rostro registrado para verificar que el reconocimiento sea exitoso.
