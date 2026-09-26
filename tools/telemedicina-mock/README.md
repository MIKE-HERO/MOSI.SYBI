# Pruebas locales de telemedicina

Permite probar la consulta de telemedicina de la app **sin tocar sybiml.com**: no se reservan
claves de apiRTC ni se avisa a médicos reales.

| Archivo | Qué es |
|---|---|
| `MockTelemedicina.java` | Servidor falso de los PHP de telemedicina (Java 11+, sin dependencias). |
| `medico.html` | Página que hace de médico: espera la llamada de la app y entra a la misma conversación de apiRTC. |

En builds **debug** la app incluye `DebugTelemedicineLauncherActivity`, que abre la consulta
sin pasar por login/registro. No existe en release.

## 1. Levantar el servidor falso

Desde la raíz del repo:

```sh
java tools/telemedicina-mock/MockTelemedicina.java 8787
```

## 2. Abrir la consulta en el emulador

```sh
adb shell pm grant com.sybi.mosi android.permission.CAMERA
adb shell pm grant com.sybi.mosi android.permission.RECORD_AUDIO
adb shell am start --activity-clear-task -n com.sybi.mosi/.DebugTelemedicineLauncherActivity \
    --ei id_usuario_web 123 --es cabina 5 --es base_url http://10.0.2.2:8787/telemedicina/
```

`base_url` y `cabina` se guardan en los ajustes de telemedicina del dispositivo; para volver a
producción cambia la URL en Ajustes → Telemedicina.

## 3. Escenarios

Antes de abrir la consulta, elige qué responderán los PHP:

```sh
curl "http://127.0.0.1:8787/__modo?valor=normal"   # médico disponible → videollamada
curl "http://127.0.0.1:8787/__modo?valor=lleno"    # "Servidor lleno"
curl "http://127.0.0.1:8787/__modo?valor=espera"   # sin médico → lista de espera
curl "http://127.0.0.1:8787/__modo?valor=error"    # notificacion.php responde 500
curl "http://127.0.0.1:8787/__log"                 # peticiones que hizo la app
```

## 4. Videollamada con el médico simulado

Abre `http://localhost:8787/medico.html` en un navegador del PC **antes** de iniciar la
consulta en el emulador (`?colgarEn=30` hace que el médico cuelgue a los 30 s, para probar el
aviso de médico desconectado). Para no pelear por la webcam con el emulador, se puede usar Edge
sin ventana y con cámara simulada:

```sh
msedge --headless=new --use-fake-device-for-media-stream --use-fake-ui-for-media-stream \
    --user-data-dir=%TEMP%\edge-medico "http://localhost:8787/medico.html?colgarEn=45"
```

**Clave de apiRTC:** el mock entrega `apzkey:myDemoApiKey`, pero la clave pública de demo de
apiRTC ya responde `applicationUUID is not authorized`. Para probar el video hay que cambiar
`APIKEY_DEMO` en `MockTelemedicina.java` por una clave válida (cuenta de prueba de apiRTC o una
clave de sybiml destinada a pruebas).

## Notas

- `utils/util.php` responde `{}` a propósito: la app registra el fallo de Firestore y continúa,
  sin enviar nada a Google.
- El mock imita las respuestas deducidas del JavaScript de la web; hay que confirmarlas contra
  el código PHP real.
