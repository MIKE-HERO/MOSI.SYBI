import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servidor falso de los servicios PHP de telemedicina de sybiml.com para probar la app
 * sin tocar producción. Sin dependencias: se ejecuta con
 *
 *     java tools/telemedicina-mock/MockTelemedicina.java [puerto]
 *
 * Desde el emulador de Android el servidor se ve en http://10.0.2.2:8787/telemedicina/
 *
 * Endpoints de control (para las pruebas):
 *   GET  /__modo?valor=normal|lleno|espera|error   escenario que devolverán los PHP
 *   GET  /__log                                    peticiones recibidas
 *   GET  /__ultima-llamada                         código y clave de la última notificación
 *   POST /__medico-estado                          medico.html reporta lo que le pasa
 *   GET  /medico.html                              página que hace de médico (apiRTC)
 */
public class MockTelemedicina {

    // Clave pública de demostración de apiRTC
    private static final String APIKEY_DEMO = "apzkey:myDemoApiKey";
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static volatile String modo = "normal";
    private static volatile String ultimoCodigo = "";
    private static final List<String> log = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        int puerto = args.length > 0 ? Integer.parseInt(args[0]) : 8787;
        Path medicoHtml = localizarMedicoHtml(Path.of("").toAbsolutePath());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", puerto), 0);
        server.createContext("/", ex -> {
            try {
                atender(ex, medicoHtml);
            } catch (Exception e) {
                registrar("💥 " + e);
                responder(ex, 500, "{\"error\":\"mock\"}");
            }
        });
        server.start();
        registrar("🚀 Mock de telemedicina en http://127.0.0.1:" + puerto + " (emulador: http://10.0.2.2:" + puerto + "/telemedicina/)");
    }

    private static void atender(HttpExchange ex, Path medicoHtml) throws IOException {
        String metodo = ex.getRequestMethod();
        String ruta = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if (!ruta.startsWith("/__") && !ruta.equals("/medico.html")) {
            String tipo = ex.getRequestHeaders().getFirst("Content-Type");
            registrar(metodo + " " + ruta + (query != null ? "?" + URLDecoder.decode(query, StandardCharsets.UTF_8) : "")
                    + (cuerpo.isEmpty() ? "" : "  body=" + cuerpo + "  [" + tipo + "]"));
        }

        switch (ruta) {
            // ---------- Control de pruebas ----------
            case "/__modo" -> {
                String valor = parametro(query, "valor");
                if (valor != null) modo = valor;
                registrar("🎛️ Modo: " + modo);
                responder(ex, 200, "{\"modo\":\"" + modo + "\"}");
            }
            case "/__log" -> {
                synchronized (log) {
                    responderTexto(ex, String.join("\n", log) + "\n");
                }
            }
            case "/__ultima-llamada" ->
                    responder(ex, 200, "{\"codigo\":\"" + ultimoCodigo + "\",\"apikey\":\"" + APIKEY_DEMO + "\"}");
            case "/__medico-estado" -> {
                registrar("👩‍⚕️ Médico: " + cuerpo);
                responder(ex, 200, "{}");
            }
            case "/medico.html" -> {
                byte[] html = Files.readAllBytes(medicoHtml);
                ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, html.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(html);
                }
            }

            // ---------- Servicios PHP simulados ----------
            case "/system/ML/obtenerApikeyMedico/API/index.php" -> {
                if (metodo.equals("POST")) responder(ex, 200, "{\"ok\":true}");
                else if (modo.equals("lleno")) responder(ex, 200, "{\"mensaje\":\"Servidor lleno\"}");
                else responder(ex, 200, "{\"id_apikeyMedico\":7,\"apikey\":\"" + APIKEY_DEMO + "\"}");
            }
            case "/system/ML/Notificaciones/API/medicos.php" -> {
                if (modo.equals("espera")) responder(ex, 200, "{\"status\":404,\"data\":[]}");
                else responder(ex, 200,
                        "{\"status\":200,\"data\":[{\"id\":15,\"nombre\":\"Dra. Prueba Local\",\"id_Sucursal\":3}]}");
            }
            case "/system/ML/Notificaciones/API/logTelemedicina.php" -> responder(ex, 200, "{\"id\":42}");
            case "/system/ML/Notificaciones/API/notificacion.php" -> {
                if (modo.equals("error")) {
                    responder(ex, 500, "{\"error\":\"falla simulada\"}");
                    return;
                }
                Matcher m = Pattern.compile("\"st_mensaje\"\\s*:\\s*\"([^\"]+)\"").matcher(cuerpo);
                if (m.find()) ultimoCodigo = m.group(1);
                responder(ex, 200, "{\"id_notificacion\":99,\"st_sucursal\":\"Sucursal Prueba\"}");
            }
            // Sin projectId: la app registra el fallo de Firestore y continúa (no sale a Google)
            case "/telemedicina/utils/util.php" -> responder(ex, 200, "{}");
            case "/system/ml/Notificaciones/API/medicoActual.php" ->
                    responder(ex, 200, "{\"data\":[{\"nombre\":\"Dra. Prueba Local\"}]}");
            case "/system/ML/Notificaciones/API/cancelarNotificacion.php" -> responder(ex, 200, "{\"ok\":true}");
            default -> responder(ex, 404, "{\"error\":\"no existe en el mock\"}");
        }
    }

    private static Path localizarMedicoHtml(Path base) {
        for (Path p : List.of(base.resolve("medico.html"), base.resolve("tools/telemedicina-mock/medico.html"))) {
            if (Files.exists(p)) return p;
        }
        throw new IllegalStateException("No se encontró medico.html; ejecuta desde la raíz del repo o desde tools/telemedicina-mock");
    }

    private static String parametro(String query, String nombre) {
        if (query == null) return null;
        for (String par : query.split("&")) {
            String[] kv = par.split("=", 2);
            if (kv[0].equals(nombre)) return kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
        }
        return null;
    }

    private static void registrar(String linea) {
        String conHora = LocalTime.now().format(HORA) + "  " + linea;
        System.out.println(conHora);
        synchronized (log) {
            log.add(conHora);
        }
    }

    private static void responder(HttpExchange ex, int codigo, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(codigo, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void responderTexto(HttpExchange ex, String texto) throws IOException {
        byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
