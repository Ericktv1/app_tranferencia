package org.vinni.cliente.gui;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
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

    private final AtomicBoolean conectado = new AtomicBoolean(false);
    private final Object sendLock = new Object();

    public PrincipalCli() {
        initComponents();
    }

    private void initComponents() {
        setTitle("Cliente TCP - Chat y Archivos");
        setSize(600, 420);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

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
    }

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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            PrincipalCli cli = new PrincipalCli();
            cli.setVisible(true);
        });
    }
}
