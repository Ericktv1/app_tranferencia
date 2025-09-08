package org.vinni.cliente.gui;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public class PrincipalCli extends JFrame {
    private static final int BUF = 64 * 1024;

    private JTextArea areaMensajes;
    private JTextField campoHost, campoPuerto, campoMensaje;
    private JButton btnConectar, btnDesconectar, btnEnviarMsg, btnEnviarArchivo;
    private JComboBox<String> listaClientes;

    private Socket socket;
    private DataOutputStream dos;
    private DataInputStream dis;
    private Thread listenerThread;

    private String nombre;

    // Tolerancia a fallos
    private int maxAttempts = 5;
    private int delaySeconds = 3;
    private final AtomicBoolean manualDisconnect = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    public PrincipalCli() {
        setTitle("Cliente TCP - Chat y Archivos");
        setSize(600, 420);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Área de mensajes
        areaMensajes = new JTextArea();
        areaMensajes.setEditable(false);
        add(new JScrollPane(areaMensajes), BorderLayout.CENTER);

        // Panel superior
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("Host:"));
        campoHost = new JTextField("localhost", 10);
        topPanel.add(campoHost);
        topPanel.add(new JLabel("Puerto:"));
        campoPuerto = new JTextField("5000", 6);
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
            return;
        }
        try (FileInputStream fis = new FileInputStream(propFile)) {
            Properties p = new Properties();
            p.load(fis);
            String host = p.getProperty("server.host");
            String port = p.getProperty("server.port");
            String maxA = p.getProperty("reconnect.maxAttempts");
            String delay = p.getProperty("reconnect.delaySeconds");

            if (host != null && !host.isBlank()) campoHost.setText(host.trim());
            if (port != null && !port.isBlank()) campoPuerto.setText(port.trim());
            if (maxA != null) maxAttempts = Integer.parseInt(maxA.trim());
            if (delay != null) delaySeconds = Integer.parseInt(delay.trim());

            appendMensaje("Propiedades cargadas. maxAttempts=" + maxAttempts +
                    ", delaySeconds=" + delaySeconds + "\n");
        } catch (Exception e) {
            appendMensaje("Error cargando propiedades: " + e.getMessage() + "\n");
        }
    }

    /* -------------------- Conexión y reintentos -------------------- */
    private void conectarConReintentos() {
        manualDisconnect.set(false);
        setUiConectando(true);

        new Thread(() -> {
            int intento = 0;
            while (!manualDisconnect.get()) {
                intento++;
                boolean ok = intentarConectarUnaVez();
                if (ok) {
                    appendMensaje("Conectado al servidor.\n");
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
                appendMensaje("Reintentando conectar... (" + (intento + 1) + "/" + maxAttempts + ")\n");
            }
        }, "reconnect-initial").start();
    }

    private boolean intentarConectarUnaVez() {
        String host = campoHost.getText().trim();
        int puerto;
        try {
            puerto = Integer.parseInt(campoPuerto.getText().trim());
        } catch (NumberFormatException e) {
            appendMensaje("Puerto inválido.\n");
            return false;
        }

        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, puerto), 3000); // timeout 3s
            s.setTcpNoDelay(true);
            socket = s;
            dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            dis = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            // Nombre (si ya teníamos uno, reutilízalo)
            if (nombre == null || nombre.isBlank()) {
                nombre = JOptionPane.showInputDialog(this, "Ingresa tu nombre:");
                if (nombre == null || nombre.trim().isEmpty()) nombre = "Cliente" + socket.getLocalPort();
            }
            // El servidor primero envía "INGRESE_NOMBRE" (según tu server)
            String prompt = dis.readUTF();
            if ("INGRESE_NOMBRE".equals(prompt)) {
                dos.writeUTF(nombre);
                dos.flush();
            } else {
                // Si el servidor no envió prompt, igual mandamos el nombre por compatibilidad
                dos.writeUTF(nombre);
                dos.flush();
            }
            return true;
        } catch (IOException e) {
            appendMensaje("Error conectando: " + e.getMessage() + "\n");
            cerrarSilencioso(); // limpia recursos parciales
            return false;
        }
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
            // Intentar reconectar si NO fue desconexión manual
            if (!manualDisconnect.get()) {
                intentarReconexion();
            } else {
                desconectar(); // limpia UI
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
                boolean ok = intentarConectarUnaVez();
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
    private void enviarMensaje() {
        if (socket == null || socket.isClosed()) return;

        String texto = campoMensaje.getText().trim();
        if (texto.isEmpty()) return;

        String destino = (String) listaClientes.getSelectedItem();
        if (destino == null) destino = "Todos";

        try {
            dos.writeUTF("MSG:" + destino + ":" + texto);
            dos.flush();
            appendMensaje("Tú -> " + destino + ": " + texto + "\n");
            campoMensaje.setText("");
        } catch (IOException e) {
            appendMensaje("Error enviando mensaje: " + e.getMessage() + "\n");
        }
    }

    private void enviarArchivo() {
        if (socket == null || socket.isClosed()) return;

        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File f = chooser.getSelectedFile();
        if (!f.exists() || !f.isFile()) {
            appendMensaje("Archivo inválido.\n");
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
        SwingUtilities.invokeLater(() -> new PrincipalCli().setVisible(true));
    }
}
