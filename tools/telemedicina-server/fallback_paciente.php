<?php
/**
 * BORRADOR — no está desplegado. Copiar como telemedicina/utils/fallback_paciente.php
 * (junto a utils/util.php) para que la app pueda usar el respaldo WebRTC.
 *
 * La app nativa no puede leer las variables que telemedicina_v2/index.php inyecta en el HTML,
 * así que este endpoint entrega lo mismo en JSON:
 *   { apiBase, socketUrl, jwt, iceServers }
 *
 * Reutiliza la misma configuración y el mismo login por rol paciente que la página web
 * (_fbFallbackJwtPac); la app nunca conoce correos ni contraseñas.
 *
 * SEGURIDAD: igual que la página web actual, no exige sesión, así que cualquiera que llame a
 * esta URL recibe un JWT de paciente y las credenciales TURN. Conviene protegerlo (token de
 * dispositivo, lista de IP del quiosco o validar id_usuarioWeb contra una atención activa).
 */

// Ajustar la ruta a donde viva realmente el archivo de configuración en el servidor
require_once __DIR__ . '/../../system/config_fallback_webrtc.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

function fbLoginPaciente(): string
{
    $ch = curl_init(FALLBACK_WEBRTC_URL . '/api/v1/auth/login');
    curl_setopt_array($ch, [
        CURLOPT_POST           => true,
        CURLOPT_HTTPHEADER     => ['Content-Type: application/json'],
        CURLOPT_POSTFIELDS     => json_encode([
            'email'    => FALLBACK_WEBRTC_EMAIL_PACIENTE,
            'password' => FALLBACK_WEBRTC_PASSWORD_PACIENTE,
        ]),
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT        => 5,
        // La web original desactiva la verificación; aquí se deja activa. Si el certificado de
        // videollamada.sybi.mx no valida en este servidor, corregir el certificado.
        CURLOPT_SSL_VERIFYPEER => true,
    ]);
    $respuesta = curl_exec($ch);
    curl_close($ch);

    $datos = $respuesta ? json_decode($respuesta, true) : null;
    return is_array($datos) && !empty($datos['token']) ? $datos['token'] : '';
}

$jwt = fbLoginPaciente();
if ($jwt === '') {
    http_response_code(502);
    echo json_encode(['error' => 'No se pudo autenticar con el servicio de respaldo']);
    exit;
}

echo json_encode([
    'apiBase'    => rtrim(FALLBACK_WEBRTC_URL, '/') . '/api/v1',
    'socketUrl'  => 'wss://videollamada.sybi.mx',
    'jwt'        => $jwt,
    // Mismos servidores que fallbackWebRTC-paciente.js
    'iceServers' => [
        ['urls' => 'stun:stun.l.google.com:19302'],
        ['urls' => 'turns:videollamada.sybi.mx:5349?transport=tcp',
         'username' => FALLBACK_TURN_USER, 'credential' => FALLBACK_TURN_PASSWORD],
        ['urls' => 'turn:videollamada.sybi.mx:3478?transport=udp',
         'username' => FALLBACK_TURN_USER, 'credential' => FALLBACK_TURN_PASSWORD],
    ],
]);
