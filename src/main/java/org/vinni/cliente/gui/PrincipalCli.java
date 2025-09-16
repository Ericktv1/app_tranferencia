package org.vinni.cliente.gui;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;

import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public class PrincipalCli extends JFrame {
    private static final int BUF = 64 * 1024;

    private JTextArea areaMensajes;
    private JTextField campoHost, campoPuerto, campoMensaje;
    private JButton btnConectar, btnDesconectar, btnEnviarMsg, btnEnviarArchivo;
    private JComboBox<String> listaClientes;

import java.util.concurrent.atomic.AtomicBoolean;

public class PrincipalCli extends JFrame {
    private final JTextArea mensajesTxt = new JTextArea();
    private final JTextField hostTxt = new JTextField("localhost", 10);
    private final JTextField puertoTxt = new JTextField("12345", 6);
    private final JTextField mensajeTxt = new JTextField(20);
    private final JButton btConectar = new JButton("Conectar");
    private final JButton btDesconectar = new JButton("Desconectar");
    private final JButton btEnviarMsg = new JButton("Enviar Mensaje");
    private final JButton btEnviarArchivo = new JButton("Enviar Archivo");


    private Socket socket;
    private DataOutputStream dos;
    private DataInputStream dis;

    private Thread listenerThread;

    private String nombre;


    private final AtomicBoolean conectado = new AtomicBoolean(false);
    private final Object sendLock = new Object();

    // Tolerancia a fallos
    private int maxAttempts = 5;
    private int delaySeconds = 3;
    private final AtomicBoolean manualDisconnect = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    // Puertos (lista o rango) y "sticky-port" del último exitoso
    private int[] puertos = new int[] {5000};   // por defecto
    private int lastPortIndex = -1;             // índice del último puerto exitoso

    public PrincipalCli() {
        initComponents();
    }

    private void initComponents() {
        setTitle("Cliente TCP - Chat y Archivos");
        setSize(600, 420);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());


        // Área de mensajes
        areaMensajes = new JTextArea();
        areaMensajes.setEditable(false);
        add(new JScrollPane(areaMensajes), BorderLayout.CENTER);

        // Panel superior
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("Host:"));
        campoHost = new JTextField("localhost", 10);
        campoHost.setEditable(false);
        campoHost.setFocusable(false);
        topPanel.add(campoHost);
        topPanel.add(new JLabel("Puerto:"));
        // Campo solo informativo; el cliente escanea/elige automáticamente
        campoPuerto = new JTextField("5000", 6);
        campoPuerto.setEditable(false);
        campoPuerto.setFocusable(false);
        topPanel.add(campoPuerto);

        btnConectar = new JButton("Conectar");
        btnDesconectar = new JButton("Desconectar");
        btnDesconectar.setEnabled(false);
        topPanel.add(btnConectar);
        topPanel.add(btnDesconectar);
        add(topPanel, BorderLayout.NORTH);

        // Panel inferior
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        campoMensaje = new JTextField(20);
        bottomPanel.add(campoMensaje);

        listaClientes = new JComboBox<>();
        listaClientes.addItem("Todos");
        bottomPanel.add(listaClientes);

        btnEnviarMsg = new JButton("Enviar Mensaje");
        btnEnviarMsg.setEnabled(false);
        btnEnviarArchivo = new JButton("Enviar Archivo");
        btnEnviarArchivo.setEnabled(false);
        bottomPanel.add(btnEnviarMsg);
        bottomPanel.add(btnEnviarArchivo);
        add(bottomPanel, BorderLayout.SOUTH);

        // Acciones
        btnConectar.addActionListener(e -> conectarConReintentos());
        btnDesconectar.addActionListener(e -> desconectarManual());
        btnEnviarMsg.addActionListener(e -> enviarMensaje());
        btnEnviarArchivo.addActionListener(e -> enviarArchivo());
        campoMensaje.addActionListener(e -> enviarMensaje()); // Enter para enviar

        // Cargar configuración
        cargarPropiedades();
    }

    /* -------------------- Config -------------------- */
    private void cargarPropiedades() {
        File propFile = new File("cliente.properties");
        if (!propFile.exists()) {
            appendMensaje("No se encontró cliente.properties; se usan valores por defecto.\n");
            // Rango por defecto a partir del campo (o 5000) con scan=10
            int base = parseIntOrDefault(campoPuerto.getText().trim(), 5000);
            puertos = buildRange(base, 10);
            campoPuerto.setText(String.valueOf(base)); // informativo
            return;
        }
        try (FileInputStream fis = new FileInputStream(propFile)) {
            Properties p = new Properties();
            p.load(fis);

            String host = p.getProperty("server.host");
            if (host != null && !host.isBlank()) campoHost.setText(host.trim());

            // 1) Si hay lista fija, úsala
            String portsList = p.getProperty("server.ports");
            if (portsList != null && !portsList.isBlank()) {
                puertos = parsePorts(portsList);
                if (puertos.length == 0) {
                    // lista mal formada -> cae al rango
                    int base = parseIntOrDefault(p.getProperty("server.basePort"), parseIntOrDefault(campoPuerto.getText().trim(), 5000));
                    int count = clampScanCount(parseIntOrDefault(p.getProperty("server.scan.count"), 10));
                    puertos = buildRange(base, count);
                    campoPuerto.setText(String.valueOf(base));
                } else {
                    campoPuerto.setText(String.valueOf(puertos[0])); // informativo
                }
            } else {
                // 2) Rango: basePort + scan.count
                int base = parseIntOrDefault(p.getProperty("server.basePort"), parseIntOrDefault(campoPuerto.getText().trim(), 5000));
                int count = clampScanCount(parseIntOrDefault(p.getProperty("server.scan.count"), 10));
                puertos = buildRange(base, count);
                campoPuerto.setText(String.valueOf(base));
            }

            String maxA = p.getProperty("reconnect.maxAttempts");
            String delay = p.getProperty("reconnect.delaySeconds");
            if (maxA != null) maxAttempts = Integer.parseInt(maxA.trim());
            if (delay != null) delaySeconds = Integer.parseInt(delay.trim());

            System.out.println("Propiedades cargadas. puertos=" + Arrays.toString(puertos) +
                    ", maxAttempts=" + maxAttempts + ", delaySeconds=" + delaySeconds + "\n");
        } catch (Exception e) {
            appendMensaje("Error cargando propiedades: " + e.getMessage() + "\n");
            int base = parseIntOrDefault(campoPuerto.getText().trim(), 5000);
            puertos = buildRange(base, 10);
            campoPuerto.setText(String.valueOf(base));
        }
    }

    private int[] parsePorts(String csv) {
        try {
            return Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .mapToInt(Integer::parseInt)
                    .toArray();
        } catch (Exception e) {
            return new int[0];
        }
    }

    private int parseIntOrDefault(String maybe, int def) {
        try { return (maybe == null || maybe.isBlank()) ? def : Integer.parseInt(maybe.trim()); }
        catch (Exception ignored) { return def; }
    }

    private int clampScanCount(int n) {
        if (n < 1) return 1;
        if (n > 200) return 200; // límite sano
        return n;
    }

    private int[] buildRange(int basePort, int count) {
        int[] arr = new int[count];
        for (int i = 0; i < count; i++) arr[i] = basePort + i;
        return arr;
    }

    /* -------------------- Conexión y reintentos -------------------- */
    private void conectarConReintentos() {
        manualDisconnect.set(false);
        setUiConectando(true);

        new Thread(() -> {
            int intento = 0;
            while (!manualDisconnect.get()) {
                intento++;
                boolean ok = intentarConectarUnaVez(false); // conexión inicial: con logs
                if (ok) {
                    setUiConectado(true);
                    escucharEnHilo();
                    return;
                }
                if (intento >= maxAttempts) {
                    appendMensaje("No fue posible conectar tras " + intento + " intento(s).\n");
                    setUiConectado(false);
                    return;
                }
                dormir(delaySeconds);
                appendMensaje("Reintentando conexión (" + (intento + 1) + "/" + maxAttempts + ")...\n");
            }
        }, "reconnect-initial").start();
    }

    // Versión silenciosa para reconexión
    private boolean intentarConectarUnaVez() {
        return intentarConectarUnaVez(false);
    }

    /**
     * @param quiet true para no loguear errores de socket (reconexión silenciosa)
     */
    private boolean intentarConectarUnaVez(boolean quiet) {
        String host = campoHost.getText().trim();

        int[] orden = buildPortOrder();
        Socket s = null;
        int puertoUsado = -1;

        for (int p : orden) {
            try {
                s = new Socket();
                s.connect(new InetSocketAddress(host, p), 3000); // timeout 3s
                s.setTcpNoDelay(true);
                puertoUsado = p;
                lastPortIndex = indexOf(puertos, p);
                break; // éxito
            } catch (IOException e) {
                // probar siguiente SIN log si quiet
            }
        }

        if (s == null) {
            if (!quiet) {
                appendMensaje("Error: no se pudo conectar a ningún servidor en " + Arrays.toString(puertos) + ".\n");
            }
            return false;
        }

        try {
            socket = s;
            dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            dis = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            if (nombre == null || nombre.isBlank()) {
                nombre = JOptionPane.showInputDialog(this, "Ingresa tu nombre:");
                if (nombre == null || nombre.trim().isEmpty()) nombre = "Cliente" + socket.getLocalPort();
            }

            String prompt = dis.readUTF();
            if ("INGRESE_NOMBRE".equals(prompt)) {
                dos.writeUTF(nombre);
                dos.flush();
            } else {
                dos.writeUTF(nombre);
                dos.flush();
            }

            appendMensaje("Conectado al servidor en puerto " + puertoUsado + ".\n");
            campoPuerto.setText(String.valueOf(puertoUsado)); // informativo
            return true;

        } catch (IOException e) {
            if (!quiet) appendMensaje("Error conectando: " + e.getMessage() + "\n");
            cerrarSilencioso();
            return false;
        }
    }

    // Orden de puertos: primero el último exitoso, luego el resto
    private int[] buildPortOrder() {
        if (puertos == null || puertos.length == 0) puertos = new int[] {5000};
        if (lastPortIndex < 0 || lastPortIndex >= puertos.length) {
            return Arrays.copyOf(puertos, puertos.length);

        mensajesTxt.setEditable(false);
        add(new JScrollPane(mensajesTxt), BorderLayout.CENTER);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Host:"));
        top.add(hostTxt);
        top.add(new JLabel("Puerto:"));
        top.add(puertoTxt);
        top.add(btConectar);
        top.add(btDesconectar);
        add(top, BorderLayout.NORTH);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottom.add(mensajeTxt);
        bottom.add(btEnviarMsg);
        bottom.add(btEnviarArchivo);
        add(bottom, BorderLayout.SOUTH);

        btConectar.addActionListener(e -> conectar());
        btDesconectar.addActionListener(e -> desconectar());
        btEnviarMsg.addActionListener(e -> enviarMensaje());
        btEnviarArchivo.addActionListener(e -> enviarArchivo());

        btDesconectar.setEnabled(false);
        btEnviarMsg.setEnabled(false);
        btEnviarArchivo.setEnabled(false);
    }

    private void conectar() {
        if (conectado.get()) {
            appendMessage("Ya estás conectado.\n");
            return;
        }

        String host = hostTxt.getText().trim();
        int puerto;
        try {
            puerto = Integer.parseInt(puertoTxt.getText().trim());
            if (puerto < 1 || puerto > 65535) throw new NumberFormatException("Puerto fuera de rango");
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Puerto inválido.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, puerto), 5000); // timeout 5s
            dos = new DataOutputStream(socket.getOutputStream());
            dis = new DataInputStream(socket.getInputStream());
            conectado.set(true);
            btConectar.setEnabled(false);
            btDesconectar.setEnabled(true);
            btEnviarMsg.setEnabled(true);
            btEnviarArchivo.setEnabled(true);
            appendMessage("Conectado a " + host + ":" + puerto + "\n");

            new Thread(this::listenServer, "Cli-Listen-Thread").start();
        } catch (UnknownHostException uhe) {
            appendMessage("Host desconocido: " + uhe.getMessage() + "\n");
        } catch (SocketTimeoutException ste) {
            appendMessage("Timeout al conectar: " + ste.getMessage() + "\n");
        } catch (ConnectException ce) {
            appendMessage("No se pudo conectar: " + ce.getMessage() + "\n");
        } catch (IOException ioe) {
            appendMessage("I/O error al conectar: " + ioe.getMessage() + "\n");

        }
        int[] orden = new int[puertos.length];
        orden[0] = puertos[lastPortIndex];
        int k = 1;
        for (int i = 0; i < puertos.length; i++) {
            if (i == lastPortIndex) continue;
            orden[k++] = puertos[i];
        }
        return orden;
    }

    private int indexOf(int[] arr, int val) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == val) return i;
        return -1;
    }

    private void escucharEnHilo() {
        listenerThread = new Thread(this::escucharServidor, "listener");
        listenerThread.start();
    }


    /**
     * Si la conexión se cae inesperadamente, aquí se detecta y se lanza un ciclo de reintentos.
     */
    private void escucharServidor() {
        try {
            while (socket != null && !socket.isClosed()) {
                String msg = dis.readUTF();

                if (msg.startsWith("MSG:")) {
                    String[] partes = msg.split(":", 3);
                    appendMensaje(partes[1] + " -> " + partes[2] + "\n");

                } else if (msg.startsWith("FILE:")) {
                    String[] partes = msg.split(":", 4);
                    String remitente = partes[1];
                    String nombreArchivo = partes[2];
                    long tam = Long.parseLong(partes[3]);

                    File folder = new File("downloads");
                    if (!folder.exists()) folder.mkdirs();
                    File outFile = new File(folder, "recv_" + nombreArchivo);

                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outFile))) {
                        copiarExacto(dis, bos, tam);
                    }
                    appendMensaje(remitente + " envió archivo: " + outFile.getAbsolutePath() + "\n");

                } else if (msg.startsWith("LISTA:")) {
                    actualizarListaClientes(msg.substring(6));

                } else if (msg.equals("INGRESE_NOMBRE")) {
                    dos.writeUTF(nombre);
                    dos.flush();
                }
            }
        } catch (IOException e) {
            appendMensaje("Conexión perdida con el servidor.\n");
        } finally {
            // Reconexión silenciosa (quiet=true)
            if (!manualDisconnect.get()) {
                intentarReconexion();
            } else {
                desconectar();
            }
        }
    }

    private void intentarReconexion() {
        if (reconnecting.getAndSet(true)) return; // evita bucles simultáneos

        setUiConectando(true);
        new Thread(() -> {
            int intento = 0;
            while (!manualDisconnect.get()) {
                intento++;
                appendMensaje("Reintentando conexión (" + intento + "/" + maxAttempts + ")...\n");
                boolean ok = intentarConectarUnaVez(true); // QUIET
                if (ok) {
                    appendMensaje("Reconectado.\n");
                    setUiConectado(true);
                    reconnecting.set(false);
                    escucharEnHilo();
                    return;
                }
                if (intento >= maxAttempts) {
                    appendMensaje("No se pudo reconectar tras " + intento + " intentos.\n");
                    reconnecting.set(false);
                    setUiConectado(false);
                    cerrarSilencioso();
                    return;
                }
                dormir(delaySeconds);
            }
            reconnecting.set(false);
        }, "reconnect-loop").start();
    }

    private void dormir(int seconds) {
        try { Thread.sleep(seconds * 1000L); } catch (InterruptedException ignored) {}
    }

    /* -------------------- UI helpers -------------------- */
    private void setUiConectando(boolean conectando) {
        SwingUtilities.invokeLater(() -> {
            btnConectar.setEnabled(!conectando);
            btnDesconectar.setEnabled(conectando);
            btnEnviarMsg.setEnabled(false);
            btnEnviarArchivo.setEnabled(false);
        });
    }

    private void setUiConectado(boolean conectado) {
        SwingUtilities.invokeLater(() -> {
            btnConectar.setEnabled(!conectado);
            btnDesconectar.setEnabled(true);
            btnEnviarMsg.setEnabled(conectado);
            btnEnviarArchivo.setEnabled(conectado);
        });
    }

    /* -------------------- Acciones -------------------- */

    private void listenServer() {
        try {
            while (conectado.get() && !socket.isClosed()) {
                String tipo;
                try {
                    tipo = dis.readUTF();
                } catch (EOFException eof) {
                    appendMessage("Servidor cerró la conexión (EOF).\n");
                    break;
                }

                if ("MSG".equals(tipo)) {
                    String msg = dis.readUTF();
                    appendMessage("Servidor: " + msg + "\n");
                } else if ("FILE".equals(tipo)) {
                    recibirArchivoDelServidor();
                } else {
                    appendMessage("Tipo desconocido desde servidor: " + tipo + "\n");
                }
            }
        } catch (SocketException se) {
            appendMessage("Conexión perdida (SocketException): " + se.getMessage() + "\n");
        } catch (IOException ioe) {
            appendMessage("Error leyendo servidor: " + ioe.getMessage() + "\n");
        } finally {
            closeConnection();
        }
    }

    private void recibirArchivoDelServidor() {
        try {
            String fileName = dis.readUTF();
            long fileLength = dis.readLong();

            File folder = new File("downloads");
            if (!folder.exists() && !folder.mkdirs()) {
                appendMessage("No se pudo crear carpeta downloads. Guardando en carpeta actual.\n");
                folder = new File(".");
            }

            File outFile = uniqueFile(folder, "recv_srv_" + fileName);

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[4096];
                long totalRead = 0;
                while (totalRead < fileLength) {
                    int maxToRead = (int) Math.min(buffer.length, fileLength - totalRead);
                    int read = dis.read(buffer, 0, maxToRead);
                    if (read == -1) break;
                    fos.write(buffer, 0, read);
                    totalRead += read;
                }
                fos.flush();
            }

            appendMessage("Archivo recibido y guardado: " + outFile.getAbsolutePath() + "\n");
        } catch (IOException e) {
            appendMessage("Error recibiendo archivo del servidor: " + e.getMessage() + "\n");
        }
    }


    private void enviarMensaje() {
        if (!conectado.get() || dos == null) {
            appendMessage("No estás conectado al servidor.\n");
            return;
        }

        String texto = mensajeTxt.getText().trim();
        if (texto.isEmpty()) return;

        synchronized (sendLock) {
            try {
                dos.writeUTF("MSG");
                dos.writeUTF(texto);
                dos.flush();
                appendMessage("Tú: " + texto + "\n");
                mensajeTxt.setText("");
            } catch (IOException e) {
                appendMessage("Error enviando mensaje: " + e.getMessage() + "\n");
                closeConnection();
            }
        }
    }

    private void enviarArchivo() {
        if (!conectado.get() || dos == null) {
            appendMessage("No estás conectado al servidor.\n");
            return;
        }

        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File f = chooser.getSelectedFile();
        if (!f.exists() || !f.isFile()) {
            appendMessage("Archivo inválido seleccionado.\n");
            return;
        }


        String destino = (String) listaClientes.getSelectedItem();
        if (destino == null) destino = "Todos";

        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(f))) {
            long len = f.length();
            dos.writeUTF("FILE:" + destino + ":" + f.getName() + ":" + len);
            dos.flush();

            byte[] buffer = new byte[BUF];
            int n;
            while ((n = bis.read(buffer)) != -1) {
                dos.write(buffer, 0, n);
            }
            dos.flush();
            appendMensaje("Archivo enviado a " + destino + ": " + f.getName() + " (" + len + " bytes)\n");
        } catch (IOException e) {
            appendMensaje("Error enviando archivo: " + e.getMessage() + "\n");
        }
    }

    private void desconectarManual() {
        manualDisconnect.set(true);
        appendMensaje("Desconectando por solicitud del usuario...\n");
        desconectar();
    }

    private void desconectar() {
        try { if (listenerThread != null) listenerThread.interrupt(); } catch (Exception ignored) {}
        cerrarSilencioso();

        SwingUtilities.invokeLater(() -> {
            btnConectar.setEnabled(true);
            btnDesconectar.setEnabled(false);
            btnEnviarMsg.setEnabled(false);
            btnEnviarArchivo.setEnabled(false);
        });
        appendMensaje("Desconectado.\n");
    }

    private void cerrarSilencioso() {
        try { if (dis != null) dis.close(); } catch (IOException ignored) {}
        try { if (dos != null) dos.close(); } catch (IOException ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
        socket = null; dis = null; dos = null;
    }

    /* -------------------- Utilidades -------------------- */
    private void actualizarListaClientes(String lista) {
        SwingUtilities.invokeLater(() -> {
            listaClientes.removeAllItems();
            listaClientes.addItem("Todos");
            String[] nombres = lista.split(",");
            for (String n : nombres) {
                if (n != null && !n.trim().isEmpty() && !n.equals(nombre)) listaClientes.addItem(n);
            }
        });
    }

    private void appendMensaje(String msg) {
        SwingUtilities.invokeLater(() -> areaMensajes.append(msg));

        synchronized (sendLock) {
            try (FileInputStream fis = new FileInputStream(f)) {
                dos.writeUTF("FILE");
                dos.writeUTF(f.getName());
                dos.writeLong(f.length());

                byte[] buffer = new byte[4096];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    dos.write(buffer, 0, read);
                }
                dos.flush();
                appendMessage("Archivo enviado: " + f.getName() + "\n");
            } catch (IOException e) {
                appendMessage("Error enviando archivo: " + e.getMessage() + "\n");
                closeConnection();
            }
        }
    }

    private void desconectar() {
        closeConnection();
        appendMessage("Desconectado por el usuario.\n");
    }

    private void closeConnection() {
        conectado.set(false);
        try {
            if (dis != null) { dis.close(); dis = null; }
        } catch (IOException ignored) {}
        try {
            if (dos != null) { dos.close(); dos = null; }
        } catch (IOException ignored) {}
        try {
            if (socket != null && !socket.isClosed()) { socket.close(); }
            socket = null;
        } catch (IOException ignored) {}

        SwingUtilities.invokeLater(() -> {
            btConectar.setEnabled(true);
            btDesconectar.setEnabled(false);
            btEnviarMsg.setEnabled(false);
            btEnviarArchivo.setEnabled(false);
        });
    }

    private File uniqueFile(File folder, String baseName) {
        File f = new File(folder, baseName);
        int i = 1;
        while (f.exists()) {
            f = new File(folder, baseName + "(" + i + ")");
            i++;
        }
        return f;
    }

    private void appendMessage(String msg) {
        SwingUtilities.invokeLater(() -> mensajesTxt.append(msg));

    }

    private static void copiarExacto(InputStream in, OutputStream out, long bytes) throws IOException {
        byte[] buffer = new byte[BUF];
        long restante = bytes;
        while (restante > 0) {
            int toRead = (int) Math.min(buffer.length, restante);
            int n = in.read(buffer, 0, toRead);
            if (n == -1) throw new EOFException("Fin inesperado durante recepción de archivo");
            out.write(buffer, 0, n);
            restante -= n;
        }
        out.flush();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            PrincipalCli cli = new PrincipalCli();
            cli.setVisible(true);
        });
    }
}
