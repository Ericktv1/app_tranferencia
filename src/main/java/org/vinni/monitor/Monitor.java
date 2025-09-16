package org.vinni.monitor;

import javax.swing.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Monitor {

    private static final String PROP_FILE = "monitor.properties";
    private static final String LOG_FILE  = "monitor.log";

    // Config común
    private String serverHost;
    private int basePort;
    private int checkIntervalSeconds;
    private int restartDelaySeconds;
    private int connectTimeoutMs;
    private int maxRestartsPerHour; // 0 = sin límite
    private String commandTemplate; // debe contener {PORT}
    private String serverWorkingDir; // opcional: directorio de trabajo para arrancar el jar
    private Integer fixedInstances;  // si viene en propiedades, no se pregunta por GUI
    private PrintWriter logWriter;

    // Gestión por puerto
    private final Map<Integer, Deque<Long>> restartWindows = new ConcurrentHashMap<>();
    private final List<Thread> watchers = new ArrayList<>();
    private volatile boolean running = true;

    public static void main(String[] args) {
        try {
            // Sugerencia visual en Windows para que el diálogo salga adelante
            System.setProperty("java.awt.headless", "false");
            new Monitor().run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void run() throws Exception {
        Properties props = loadProps();
        openLog();
        readConfig(props);

        log("=== MONITOR MULTI-INSTANCIA INICIADO ===");
        log("host=" + serverHost + " basePort=" + basePort + " checkInterval=" + checkIntervalSeconds + "s"
                + " restartDelay=" + restartDelaySeconds + "s connectTimeout=" + connectTimeoutMs + "ms"
                + " maxRestartsPerHour=" + maxRestartsPerHour);
        log("commandTemplate=" + commandTemplate +
                (serverWorkingDir.isEmpty() ? "" : (" | workingDir=" + serverWorkingDir)));

        // Número de instancias: propiedad o diálogo
        int instances = (fixedInstances != null) ? fixedInstances : askInstancesWithGui(1);
        log("Instancias solicitadas: " + instances);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            logSafe("Monitor detenido (shutdown hook).");
            try { if (logWriter != null) logWriter.close(); } catch (Exception ignored) {}
        }));

        // Crear un watcher por puerto: basePort, basePort+1, ...
        for (int i = 0; i < instances; i++) {
            int port = basePort + i;
            Thread t = new Thread(() -> watchPort(port), "watcher-" + port);
            t.start();
            watchers.add(t);
        }

        // Mantener vivo (threads terminan al cerrar)
        for (Thread t : watchers) t.join();
    }

    /** Diálogo GUI (JOptionPane + JSpinner). Retorna valor >= 1. */
    private int askInstancesWithGui(int defaultVal) {
        // Construir spinner (1..200)
        SpinnerNumberModel model = new SpinnerNumberModel(defaultVal, 1, 200, 1);
        JSpinner spinner = new JSpinner(model);
        spinner.setPreferredSize(new java.awt.Dimension(80, spinner.getPreferredSize().height));

        int r = JOptionPane.showOptionDialog(
                null,
                new Object[]{
                        "¿Cuántos servidores quieres lanzar?",
                        spinner
                },
                "Monitor – Número de instancias",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null, null, null
        );

        if (r == JOptionPane.OK_OPTION) {
            try {
                spinner.commitEdit();
            } catch (java.text.ParseException ignored) {}
            int n = (Integer) spinner.getValue();
            return Math.max(1, Math.min(200, n));
        }
        // Si cancelan, usar 1
        return Math.max(1, defaultVal);
    }

    /* ---------------- watcher por puerto ---------------- */

    private void watchPort(int port) {
        log("Watcher iniciado para puerto " + port);
        while (running) {
            boolean up = isUp(serverHost, port, connectTimeoutMs);
            if (up) {
                log("OK [" + port + "] Servidor vivo.");
                sleep(checkIntervalSeconds);
                continue;
            }

            log("WARN [" + port + "] Servidor caído o no responde.");
            if (maxRestartsPerHour > 0 && !canRestart(port)) {
                log("ERROR [" + port + "] Límite de reinicios por hora alcanzado. No se reinicia ahora.");
                sleep(checkIntervalSeconds);
                continue;
            }

            log("Esperando " + restartDelaySeconds + "s antes de reiniciar [" + port + "] ...");
            sleep(restartDelaySeconds);

            String cmd = buildCommandForPort(commandTemplate, port);
            log("Intentando reiniciar [" + port + "]:");
            log(">>> " + cmd);

            try {
                ProcessBuilder pb = pbFor(cmd);
                if (!serverWorkingDir.isEmpty()) {
                    pb.directory(new File(serverWorkingDir));
                }
                pb.redirectErrorStream(true);
                Process p = pb.start();
                pipeProcessOutputToLog(p.getInputStream(), port);

                // ventana de gracia y verificación
                sleep(5);
                if (isUp(serverHost, port, connectTimeoutMs)) {
                    noteRestart(port);
                    log("SUCCESS [" + port + "] Servidor reiniciado correctamente.");
                } else {
                    log("ERROR [" + port + "] Tras reiniciar, el servidor sigue caído.");
                }
            } catch (Exception ex) {
                log("ERROR [" + port + "] Lanzando comando: " + ex.getMessage());
            }

            sleep(checkIntervalSeconds);
        }
        log("Watcher finalizado para puerto " + port);
    }

    /* ---------------- utilidades ---------------- */

    private Properties loadProps() throws IOException {
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream(PROP_FILE)) {
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
        basePort = Integer.parseInt(p.getProperty("server.basePort",
                p.getProperty("server.port", "5000")).trim());
        checkIntervalSeconds = Integer.parseInt(p.getProperty("monitor.checkIntervalSeconds", "5").trim());
        restartDelaySeconds = Integer.parseInt(p.getProperty("monitor.restartDelaySeconds", "10").trim());
        connectTimeoutMs = Integer.parseInt(p.getProperty("monitor.connectTimeoutMs", "1500").trim());
        maxRestartsPerHour = Integer.parseInt(p.getProperty("monitor.maxRestartsPerHour", "6").trim());

        // Si quieres fijar instancias sin diálogo, pon monitor.instances en el .properties
        String inst = p.getProperty("monitor.instances", "").trim();
        fixedInstances = inst.isEmpty() ? null : Integer.valueOf(Math.max(1, Math.min(200, Integer.parseInt(inst))));

        // Debe contener {PORT}
        commandTemplate = p.getProperty("server.commandTemplate",
                "java -Dserver.autostart=true -Dserver.port={PORT} -jar AppTcp-1.0-SNAPSHOT-servidor.jar").trim();
        if (!commandTemplate.contains("{PORT}")) {
            throw new IllegalArgumentException("server.commandTemplate debe incluir el placeholder {PORT}");
        }

        // Working dir opcional (útil si el jar no está en el cwd)
        serverWorkingDir = p.getProperty("server.workingDir", "").trim();
    }

    private boolean isUp(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String buildCommandForPort(String template, int port) {
        return template.replace("{PORT}", String.valueOf(port));
    }

    private ProcessBuilder pbFor(String command) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            return new ProcessBuilder("cmd", "/c", command);
        } else {
            return new ProcessBuilder("bash", "-lc", command);
        }
    }

    private void pipeProcessOutputToLog(InputStream in, int port) {
        new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log("[srv:" + port + "] " + line);
                }
            } catch (IOException ignored) {}
        }, "srv-out-" + port).start();
    }

    private boolean canRestart(int port) {
        Deque<Long> dq = restartWindows.computeIfAbsent(port, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        long windowStart = now - 3600_000L;
        while (!dq.isEmpty() && dq.peekFirst() < windowStart) dq.pollFirst();
        return maxRestartsPerHour == 0 || dq.size() < maxRestartsPerHour;
    }

    private void noteRestart(int port) {
        Deque<Long> dq = restartWindows.computeIfAbsent(port, k -> new ArrayDeque<>());
        dq.addLast(System.currentTimeMillis());
    }

    private void sleep(int s) {
        try { Thread.sleep(s * 1000L); } catch (InterruptedException ignored) {}
    }

    private void log(String msg) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        logWriter.println(ts + " | " + msg);
        System.out.println(ts + " | " + msg);
    }
    private void logSafe(String msg) {
        try { log(msg); } catch (Exception ignored) {}
    }
}


