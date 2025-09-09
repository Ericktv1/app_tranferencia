package org.vinni.servidor.gui;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Servidor TCP con GUI que permite múltiples clientes.
 * Los clientes pueden enviarse mensajes y archivos entre ellos,
 * pasando primero por el servidor.
 */
public class PrincipalSrv extends JFrame {
    // Área de texto para mostrar logs/mensajes
    private JTextArea areaMensajes;
    // Campo para puerto y mensajes de broadcast
    private JTextField campoPuerto, campoMensaje;
    // Botones de control
    private JButton btnIniciar, btnDetener, btnEnviarMsg, btnEnviarArchivo;

    // ServerSocket y flag de servidor corriendo
    private ServerSocket serverSocket;
    private final AtomicBoolean servidorCorriendo = new AtomicBoolean(false);

    // Map de clientes: nombre -> ClienteHandler
    private final Map<String, ClienteHandler> clientes = new ConcurrentHashMap<>();
    private final Object sendLock = new Object();

    public PrincipalSrv() {
        setTitle("Servidor TCP - Chat y Archivos");
        setSize(600, 420);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Área de mensajes
        areaMensajes = new JTextArea();
        areaMensajes.setEditable(false);
        add(new JScrollPane(areaMensajes), BorderLayout.CENTER);

        // Panel superior: puerto y botones iniciar/detener
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("Puerto:"));
        campoPuerto = new JTextField("5000", 8);
        topPanel.add(campoPuerto);

        btnIniciar = new JButton("Iniciar Servidor");
        btnDetener = new JButton("Detener Servidor");
        btnDetener.setEnabled(false);
        topPanel.add(btnIniciar);
        topPanel.add(btnDetener);
        add(topPanel, BorderLayout.NORTH);

        // Panel inferior: campo de mensaje y botones enviar
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        campoMensaje = new JTextField(20);
        bottomPanel.add(campoMensaje);

        btnEnviarMsg = new JButton("Enviar Mensaje");
        btnEnviarMsg.setEnabled(false);
        btnEnviarArchivo = new JButton("Enviar Archivo");
        btnEnviarArchivo.setEnabled(false);
        bottomPanel.add(btnEnviarMsg);
        bottomPanel.add(btnEnviarArchivo);
        add(bottomPanel, BorderLayout.SOUTH);

        // Acciones de botones
        btnIniciar.addActionListener(e -> iniciarServidor());
        btnDetener.addActionListener(e -> detenerServidor());
        btnEnviarMsg.addActionListener(e -> enviarMensajeATodos());
        btnEnviarArchivo.addActionListener(e -> enviarArchivoATodos());

        // ---- Patch para autostart ----
        String portProp = System.getProperty("server.port");
        if (portProp != null && !portProp.isBlank()) {
            campoPuerto.setText(portProp.trim());
        }

        String auto = System.getProperty("server.autostart", "false");
        if (auto.equalsIgnoreCase("true")) {
            SwingUtilities.invokeLater(this::iniciarServidor);
        }
    }

    /**
     * Inicia el servidor y acepta múltiples clientes.
     */
    private void iniciarServidor() {
        if (servidorCorriendo.get()) return;

        int puerto;
        try {
            puerto = Integer.parseInt(campoPuerto.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Puerto inválido", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            serverSocket = new ServerSocket(puerto);
            servidorCorriendo.set(true);
            btnIniciar.setEnabled(false);
            btnDetener.setEnabled(true);
            appendMensaje("Servidor iniciado en puerto " + puerto + "\n");

            // Hilo para aceptar clientes
            new Thread(() -> {
                while (servidorCorriendo.get()) {
                    try {
                        Socket clienteSocket = serverSocket.accept();
                        ClienteHandler handler = new ClienteHandler(clienteSocket);
                        handler.start();
                    } catch (IOException e) {
                        if (servidorCorriendo.get())
                            appendMensaje("Error aceptando cliente: " + e.getMessage() + "\n");
                    }
                }
            }, "Srv-Accept-Thread").start();

        } catch (IOException e) {
            appendMensaje("Error iniciando servidor: " + e.getMessage() + "\n");
        }
    }

    /**
     * Detiene el servidor y cierra todas las conexiones de clientes.
     */
    private void detenerServidor() {
        servidorCorriendo.set(false);
        btnIniciar.setEnabled(true);
        btnDetener.setEnabled(false);
        btnEnviarMsg.setEnabled(false);
        btnEnviarArchivo.setEnabled(false);

        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}

        // Cerrar todos los clientes
        for (ClienteHandler ch : clientes.values()) ch.cerrarConexion();
        clientes.clear();
        appendMensaje("Servidor detenido.\n");
    }

    /**
     * Envía mensaje de broadcast a todos los clientes.
     */
    private void enviarMensajeATodos() {
        String texto = campoMensaje.getText().trim();
        if (texto.isEmpty()) return;

        synchronized (sendLock) {
            for (ClienteHandler ch : clientes.values()) {
                ch.enviarMensaje("Servidor", texto);
            }
        }
        appendMensaje("Tú (Servidor): " + texto + "\n");
        campoMensaje.setText("");
    }

    /**
     * Envía un archivo a todos los clientes.
     */
    private void enviarArchivoATodos() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File f = chooser.getSelectedFile();
        if (!f.exists() || !f.isFile()) {
            appendMensaje("Archivo inválido.\n");
            return;
        }

        synchronized (sendLock) {
            for (ClienteHandler ch : clientes.values()) {
                ch.enviarArchivo("Servidor", f);
            }
        }
        appendMensaje("Archivo enviado a todos: " + f.getName() + "\n");
    }

    /**
     * Agrega texto al área de mensajes.
     */
    private void appendMensaje(String msg) {
        SwingUtilities.invokeLater(() -> areaMensajes.append(msg));
    }

    /**
     * Clase interna para manejar un cliente individual.
     */
    private class ClienteHandler extends Thread {
        private Socket socket;
        private DataOutputStream dos;
        private DataInputStream dis;
        private String nombre;

        ClienteHandler(Socket s) {
            this.socket = s;
        }

        @Override
        public void run() {
            try {
                dos = new DataOutputStream(socket.getOutputStream());
                dis = new DataInputStream(socket.getInputStream());

                // Primero, recibir nombre del cliente
                dos.writeUTF("INGRESE_NOMBRE"); // indicación al cliente
                nombre = dis.readUTF().trim();
                if (nombre.isEmpty()) nombre = "Cliente" + socket.getPort();

                clientes.put(nombre, this);
                appendMensaje(nombre + " conectado.\n");
                actualizarListaClientes();

                // Escuchar mensajes y archivos del cliente
                while (!socket.isClosed()) {
                    String mensaje = dis.readUTF();
                    if (mensaje.startsWith("MSG:")) {
                        // Formato: MSG:destino:texto
                        String[] partes = mensaje.split(":", 3);
                        String destino = partes[1];
                        String texto = partes[2];

                        if (destino.equals("Todos")) {
                            for (ClienteHandler ch : clientes.values()) {
                                if (!ch.equals(this)) ch.enviarMensaje(nombre, texto);
                            }
                        } else {
                            ClienteHandler ch = clientes.get(destino);
                            if (ch != null && !ch.equals(this)) ch.enviarMensaje(nombre, texto);
                        }
                        appendMensaje(nombre + " -> " + destino + ": " + texto + "\n");
                    } else if (mensaje.startsWith("FILE:")) {
                        // Formato: FILE:destino:nombreArchivo:tamaño
                        String[] partes = mensaje.split(":", 4);
                        String destino = partes[1];
                        String nombreArchivo = partes[2];
                        long tam = Long.parseLong(partes[3]);

                        byte[] datos = new byte[(int) tam];
                        dis.readFully(datos);

                        if (destino.equals("Todos")) {
                            for (ClienteHandler ch : clientes.values()) {
                                if (!ch.equals(this)) ch.enviarArchivo(nombre, nombreArchivo, datos);
                            }
                        } else {
                            ClienteHandler ch = clientes.get(destino);
                            if (ch != null && !ch.equals(this)) ch.enviarArchivo(nombre, nombreArchivo, datos);
                        }
                        appendMensaje(nombre + " envió archivo a " + destino + ": " + nombreArchivo + "\n");
                    }
                }

            } catch (IOException e) {
                // Antes: appendMensaje((nombre != null ? nombre : socket.getRemoteSocketAddress()) + " desconectado.\n");
                if (nombre != null) {                           // 👈 silencia sondas (sin nombre)
                    appendMensaje(nombre + " desconectado.\n");
                }
            } finally {
                cerrarConexion();
            }


        }

        /**
         * Envía mensaje a este cliente.
         */
        void enviarMensaje(String remitente, String texto) {
            try {
                dos.writeUTF("MSG:" + remitente + ":" + texto);
                dos.flush();
            } catch (IOException e) {
                appendMensaje("Error enviando mensaje a " + nombre + ": " + e.getMessage() + "\n");
            }
        }

        /**
         * Envía un archivo desde archivo físico.
         */
        void enviarArchivo(String remitente, File f) {
            try {
                byte[] datos = new byte[(int) f.length()];
                try (FileInputStream fis = new FileInputStream(f)) {
                    fis.read(datos);
                }
                enviarArchivo(remitente, f.getName(), datos);
            } catch (IOException e) {
                appendMensaje("Error enviando archivo a " + nombre + ": " + e.getMessage() + "\n");
            }
        }

        /**
         * Envía archivo ya leído como byte array.
         */
        void enviarArchivo(String remitente, String nombreArchivo, byte[] datos) {
            try {
                dos.writeUTF("FILE:" + remitente + ":" + nombreArchivo + ":" + datos.length);
                dos.write(datos);
                dos.flush();
            } catch (IOException e) {
                appendMensaje("Error enviando archivo a " + nombre + ": " + e.getMessage() + "\n");
            }
        }

        /**
         * Cierra conexión con el cliente.
         */
        void cerrarConexion() {
            if (nombre != null) {                //  proteger clave null
                clientes.remove(nombre);
                actualizarListaClientes();
            }
            try { if (dis != null) dis.close(); } catch (IOException ignored) {}
            try { if (dos != null) dos.close(); } catch (IOException ignored) {}
            try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
        }

    }

    /**
     * Actualiza lista de clientes para todos.
     */
    private void actualizarListaClientes() {
        String lista = String.join(",", clientes.keySet());
        synchronized (sendLock) {
            for (ClienteHandler ch : clientes.values()) {
                try {
                    ch.dos.writeUTF("LISTA:" + lista);
                    ch.dos.flush();
                } catch (IOException ignored) {}
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new PrincipalSrv().setVisible(true));
    }
}
