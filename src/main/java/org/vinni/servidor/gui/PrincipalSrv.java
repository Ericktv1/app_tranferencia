package org.vinni.servidor.gui;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class PrincipalSrv extends JFrame {
    private final JTextArea mensajesTxt = new JTextArea();
    private final JTextField puertoTxt = new JTextField("12345", 8);
    private final JTextField mensajeTxt = new JTextField(20);
    private final JButton btIniciar = new JButton("Iniciar Servidor");
    private final JButton btDetener = new JButton("Detener Servidor");
    private final JButton btEnviarMsg = new JButton("Enviar Mensaje");
    private final JButton btEnviarArchivo = new JButton("Enviar Archivo");

    private ServerSocket serverSocket;
    private Socket clientSocket;
    private DataOutputStream dos;
    private DataInputStream dis;

    private final AtomicBoolean servidorCorriendo = new AtomicBoolean(false);
    private final AtomicBoolean clienteConectado = new AtomicBoolean(false);
    private final Object sendLock = new Object();

    public PrincipalSrv() {
        initComponents();
    }

    private void initComponents() {
        setTitle("Servidor TCP - Chat y Archivos");
        setSize(600, 420);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
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

        mensajesTxt.setEditable(false);
        add(new JScrollPane(mensajesTxt), BorderLayout.CENTER);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Puerto:"));
        top.add(puertoTxt);
        top.add(btIniciar);
        top.add(btDetener);
        add(top, BorderLayout.NORTH);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottom.add(mensajeTxt);
        bottom.add(btEnviarMsg);
        bottom.add(btEnviarArchivo);
        add(bottom, BorderLayout.SOUTH);

        btIniciar.addActionListener(e -> iniciarServidor());
        btDetener.addActionListener(e -> detenerServidor());
        btEnviarMsg.addActionListener(e -> enviarMensaje());
        btEnviarArchivo.addActionListener(e -> enviarArchivo());

        btDetener.setEnabled(false);
        btEnviarMsg.setEnabled(false);
        btEnviarArchivo.setEnabled(false);

    }

    private void iniciarServidor() {
        if (servidorCorriendo.get()) {
            appendMessage("El servidor ya está corriendo.\n");
            return;
        }
        int puerto;
        try {
            puerto = Integer.parseInt(puertoTxt.getText().trim());
            if (puerto < 1 || puerto > 65535) throw new NumberFormatException("Puerto fuera de rango");
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Puerto inválido. Debe ser entero entre 1 y 65535.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            serverSocket = new ServerSocket(puerto);
            servidorCorriendo.set(true);
            btIniciar.setEnabled(false);
            btDetener.setEnabled(true);
            appendMessage("Servidor iniciado en puerto " + puerto + "\n");

            // Hilo para aceptar clientes (permite reconexiones sucesivas)
            new Thread(() -> {
                while (servidorCorriendo.get()) {
                    try {
                        Socket s = serverSocket.accept();
                        synchronized (this) {
                            // si ya había un cliente, lo cerramos y aceptamos el nuevo
                            if (clientSocket != null && !clientSocket.isClosed()) {
                                appendMessage("Cerrando conexión previa y aceptando nuevo cliente.\n");
                                closeClientConnection();
                            }
                            clientSocket = s;
                            dis = new DataInputStream(clientSocket.getInputStream());
                            dos = new DataOutputStream(clientSocket.getOutputStream());
                            clienteConectado.set(true);
                            appendMessage("Cliente conectado: " + clientSocket.getInetAddress() + ":" + clientSocket.getPort() + "\n");
                            btEnviarMsg.setEnabled(true);
                            btEnviarArchivo.setEnabled(true);

                            // Hilo dedicado para manejar al cliente actual
                            new Thread(() -> handleClient(clientSocket)).start();
                        }
                    } catch (SocketException se) {
                        if (servidorCorriendo.get()) appendMessage("SocketException en accept(): " + se.getMessage() + "\n");
                        break;
                    } catch (IOException ioe) {
                        if (servidorCorriendo.get()) appendMessage("IOException en accept(): " + ioe.getMessage() + "\n");
                    }
                }
            }, "Srv-Accept-Thread").start();

        } catch (BindException be) {
            JOptionPane.showMessageDialog(this, "El puerto " + puerto + " ya está en uso.", "BindException", JOptionPane.ERROR_MESSAGE);
        } catch (IOException e) {
            appendMessage("Error iniciando servidor: " + e.getMessage() + "\n");
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

        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}

        // Cerrar todos los clientes
        for (ClienteHandler ch : clientes.values()) ch.cerrarConexion();
        clientes.clear();
        appendMensaje("Servidor detenido.\n");

        // Cerrar GUI y salir del proceso para que el Monitor lo pueda reiniciar limpio
        SwingUtilities.invokeLater(() -> {
            try { setVisible(false); } catch (Exception ignored) {}
            try { dispose(); } catch (Exception ignored) {}
            System.exit(0);   // <- clave para que NO quede la ventana vieja
        });
    }


    /**
     * Envía mensaje de broadcast a todos los clientes.
     */
    private void enviarMensajeATodos() {
        String texto = campoMensaje.getText().trim();
        if (texto.isEmpty()) return;

    private void handleClient(Socket s) {
        try {
            while (clienteConectado.get() && !s.isClosed()) {
                String tipo;
                try {
                    tipo = dis.readUTF();
                } catch (EOFException eof) {
                    appendMessage("Cliente desconectó (EOF).\n");
                    break;
                }

                if ("MSG".equals(tipo)) {
                    String msg = dis.readUTF();
                    appendMessage("Cliente: " + msg + "\n");
                } else if ("FILE".equals(tipo)) {
                    recibirArchivoDeCliente();
                } else {
                    appendMessage("Tipo desconocido recibido: " + tipo + "\n");
                }
            }
        } catch (SocketException se) {
            appendMessage("Cliente desconectado (SocketException): " + se.getMessage() + "\n");
        } catch (IOException ioe) {
            appendMessage("Error leyendo cliente: " + ioe.getMessage() + "\n");
        } finally {
            closeClientConnection();
        }
    }

    private void recibirArchivoDeCliente() {
        try {
            String fileName = dis.readUTF();
            long fileLength = dis.readLong();


            File folder = new File("downloads");
            if (!folder.exists() && !folder.mkdirs()) {
                appendMessage("No se pudo crear carpeta downloads. Guardando en carpeta actual.\n");
                folder = new File(".");
            }

            File outFile = uniqueFile(folder, "recv_client_" + fileName);

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
            appendMessage("Error recibiendo archivo del cliente: " + e.getMessage() + "\n");
        }
    }

    private void enviarMensaje() {
        if (!clienteConectado.get() || dos == null) {
            appendMessage("No hay cliente conectado para enviar el mensaje.\n");
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
                appendMessage("Error enviando mensaje al cliente: " + e.getMessage() + "\n");
                closeClientConnection();
            }
        }
    }

    private void enviarArchivo() {
        if (!clienteConectado.get() || dos == null) {
            appendMessage("No hay cliente conectado para enviar archivo.\n");
            return;
        }

        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;


            } catch (IOException e) {
                // Antes: appendMensaje((nombre != null ? nombre : socket.getRemoteSocketAddress()) + " desconectado.\n");
                if (nombre != null) {                           // 👈 silencia sondas (sin nombre)
                    appendMensaje(nombre + " desconectado.\n");
                }
            } finally {
                cerrarConexion();
            }



        File f = chooser.getSelectedFile();
        if (!f.exists() || !f.isFile()) {
            appendMessage("Archivo inválido seleccionado.\n");
            return;

        }

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
                appendMessage("Error enviando archivo al cliente: " + e.getMessage() + "\n");
                closeClientConnection();
            }
        }
    }

    private void detenerServidor() {
        servidorCorriendo.set(false);
        btIniciar.setEnabled(true);
        btDetener.setEnabled(false);
        btEnviarMsg.setEnabled(false);
        btEnviarArchivo.setEnabled(false);

        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {}
        closeClientConnection();
        appendMessage("Servidor detenido.\n");
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


    private void closeClientConnection() {
        clienteConectado.set(false);
        try {
            if (dis != null) { dis.close(); dis = null; }
        } catch (IOException ignored) {}
        try {
            if (dos != null) { dos.close(); dos = null; }
        } catch (IOException ignored) {}
        try {
            if (clientSocket != null && !clientSocket.isClosed()) { clientSocket.close(); }
            clientSocket = null;
        } catch (IOException ignored) {}
        SwingUtilities.invokeLater(() -> {
            btEnviarMsg.setEnabled(false);
            btEnviarArchivo.setEnabled(false);
        });
        appendMessage("Conexión con cliente cerrada.\n");

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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            PrincipalSrv srv = new PrincipalSrv();
            srv.setVisible(true);
        });
    }
}
