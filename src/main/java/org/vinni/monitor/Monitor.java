package org.vinni.monitor;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Monitor del servidor TCP.
 * - Comprueba disponibilidad por socket (host/puerto).
 * - Si detecta caída: espera restartDelaySeconds y ejecuta server.command.
 * - Config desde monitor.properties (en el working dir).
 * - Escribe log en monitor.log
 */
public class Monitor {

    private static final String PROP_FILE = "monitor.properties";
    private static final String LOG_FILE = "monitor.log";

    private String serverHost;
    private int serverPort;
    private int checkIntervalSeconds;
    private int restartDelaySeconds;
    private String serverCommand;      // comando para iniciar el servidor
    private int connectTimeoutMs;
    private int maxRestartsPerHour;    // límite de reinicios por hora (0 = sin límite)

    private final Deque<Long> restartTimestamps = new ArrayDeque<>();
    private PrintWriter logWriter;

    public static void main(String[] args) {
        try {
            new Monitor().run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void run() throws Exception {
        Properties props = loadProps();
        openLog();

        readConfig(props);
        log("=== MONITOR INICIADO ===");
        log("host=" + serverHost + " port=" + serverPort +
                " checkInterval=" + checkIntervalSeconds + "s" +
                " restartDelay=" + restartDelaySeconds + "s" +
                " connectTimeout=" + connectTimeoutMs + "ms" +
                " maxRestartsPerHour=" + maxRestartsPerHour);
        log("command=" + serverCommand);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { log("Monitor detenido."); } catch (Exception ignored) {}
            try { if (logWriter != null) logWriter.close(); } catch (Exception ignored) {}
        }));

        // Bucle principal
        while (true) {
            boolean up = isServerUp(serverHost, serverPort, connectTimeoutMs);
            if (up) {
                log("OK - Servidor vivo.");
                sleep(checkIntervalSeconds);
                continue;
            }

            log("WARN - Servidor caído o no responde.");
            if (maxRestartsPerHour > 0 && !canRestartNow()) {
                log("ERROR - Límite de reinicios por hora alcanzado. No se reinicia ahora.");
                sleep(checkIntervalSeconds);
                continue;
            }

            log("Esperando " + restartDelaySeconds + "s antes de reiniciar...");
            sleep(restartDelaySeconds);

            log("Intentando reiniciar servidor con comando:");
            log(">>> " + serverCommand);

            try {
                ProcessBuilder pb = buildProcess(serverCommand);
                pb.redirectErrorStream(true);
                // Redirigimos salida del proceso al log
                Process p = pb.start();
                pipeProcessOutputToLog(p.getInputStream());

                // No bloquearnos esperando; damos tiempo y luego verificamos
                sleep(5); // 5s “de gracia” para que inicie
                boolean upAfter = isServerUp(serverHost, serverPort, connectTimeoutMs);
                if (upAfter) {
                    noteRestart();
                    log("SUCCESS - Servidor reiniciado correctamente.");
                } else {
                    log("ERROR - Tras reiniciar, el servidor sigue caído.");
                }
            } catch (Exception ex) {
                log("ERROR lanzando comando: " + ex.getMessage());
            }

            sleep(checkIntervalSeconds);
        }
    }

    /* ---------------- Utilidades ---------------- */

    private Properties loadProps() throws IOException {
        File f = new File(PROP_FILE);
        if (!f.exists()) {
            throw new FileNotFoundException("No se encontró " + PROP_FILE + " en el directorio actual.");
        }
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream(f)) {
            p.load(fis);
        }
        return p;
    }

    private void openLog() throws IOException {
        FileOutputStream fos = new FileOutputStream(LOG_FILE, true);
        logWriter = new PrintWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8), true);
    }

    private void readConfig(Properties p) {
        serverHost = p.getProperty("server.host", "localhost").trim();
        serverPort = Integer.parseInt(p.getProperty("server.port", "5000").trim());
        checkIntervalSeconds = Integer.parseInt(p.getProperty("monitor.checkIntervalSeconds", "5").trim());
        restartDelaySeconds = Integer.parseInt(p.getProperty("monitor.restartDelaySeconds", "10").trim());
        connectTimeoutMs = Integer.parseInt(p.getProperty("monitor.connectTimeoutMs", "1500").trim());
        maxRestartsPerHour = Integer.parseInt(p.getProperty("monitor.maxRestartsPerHour", "6").trim());
        serverCommand = p.getProperty("server.command",
                "java -cp . org.vinni.servidor.gui.PrincipalSrv").trim();
    }

    private boolean isServerUp(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void sleep(int seconds) {
        try { Thread.sleep(seconds * 1000L); } catch (InterruptedException ignored) {}
    }

    private void log(String msg) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        logWriter.println(ts + " | " + msg);
        System.out.println(ts + " | " + msg);
    }

    private ProcessBuilder buildProcess(String command) {
        // Permite comandos con comillas/espacios. En Windows conviene usar "cmd /c".
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            return new ProcessBuilder("cmd", "/c", command);
        } else {
            return new ProcessBuilder("bash", "-lc", command);
        }
    }

    private void pipeProcessOutputToLog(InputStream in) {
        new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log("[srv] " + line);
                }
            } catch (IOException ignored) {}
        }, "srv-out-pipe").start();
    }

    private boolean canRestartNow() {
        long now = System.currentTimeMillis();
        long oneHourAgo = now - 3600_000L;

        // Purga entradas viejas
        while (!restartTimestamps.isEmpty() && restartTimestamps.peekFirst() < oneHourAgo) {
            restartTimestamps.pollFirst();
        }
        return restartTimestamps.size() < maxRestartsPerHour;
    }

    private void noteRestart() {
        restartTimestamps.addLast(System.currentTimeMillis());
    }
}
