package org.vinni.cliente.gui;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;


/**
 * Cliente TCP con GUI que se conecta al servidor.
 * Permite enviar mensajes y archivos a otros clientes o a todos.
 */
public class PrincipalCli extends JFrame {
    private JTextArea areaMensajes;
    private JTextField campoHost, campoPuerto, campoMensaje;
    private JButton btnConectar, btnDesconectar, btnEnviarMsg, btnEnviarArchivo;
    private JComboBox<String> listaClientes;

    private Socket socket;
    private DataOutputStream dos;
    private DataInputStream dis;
    private String nombre;

    public PrincipalCli() {
        setTitle("Cliente TCP - Chat y Archivos");
        setSize(600, 420);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Área de mensajes
        areaMensajes = new JTextArea();
        areaMensajes.setEditable(false);
        add(new JScrollPane(areaMensajes), BorderLayout.CENTER);

        // Panel superior: host, puerto y botones conectar/desconectar
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

        // Panel inferior: mensaje, destinatario y botones enviar
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

        // Acciones de botones
        btnConectar.addActionListener(e -> conectar());
        btnDesconectar.addActionListener(e -> desconectar());
        btnEnviarMsg.addActionListener(e -> enviarMensaje());
        btnEnviarArchivo.addActionListener(e -> enviarArchivo());
    }

    /**
     * Conecta al servidor y solicita nombre de usuario.
     */
    private void conectar() {
        String host = campoHost.getText().trim();
        int puerto;
        try {
            puerto = Integer.parseInt(campoPuerto.getText().trim());
        } catch (NumberFormatException e) {
            appendMensaje("Puerto inválido.\n");
            return;
        }

        try {
            socket = new Socket(host, puerto);
            dos = new DataOutputStream(socket.getOutputStream());
            dis = new DataInputStream(socket.getInputStream());

            // Pedir nombre
            nombre = JOptionPane.showInputDialog(this, "Ingresa tu nombre:");
            if (nombre == null || nombre.trim().isEmpty()) nombre = "Cliente" + socket.getPort();
            dos.writeUTF(nombre);
            dos.flush();

            appendMensaje("Conectado al servidor.\n");

            btnConectar.setEnabled(false);
            btnDesconectar.setEnabled(true);
            btnEnviarMsg.setEnabled(true);
            btnEnviarArchivo.setEnabled(true);

            // Escuchar mensajes del servidor en hilo separado
            new Thread(this::escucharServidor).start();

        } catch (IOException e) {
            appendMensaje("Error conectando: " + e.getMessage() + "\n");
        }
    }

    /**
     * Escucha mensajes y archivos del servidor.
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
                    int tam = Integer.parseInt(partes[3]);

                    byte[] datos = new byte[tam];
                    dis.readFully(datos);

                    File folder = new File("downloads");
                    if (!folder.exists()) folder.mkdirs();
                    File outFile = new File(folder, "recv_" + nombreArchivo);
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(datos);
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
            appendMensaje("Desconectado del servidor.\n");
        } finally {
            desconectar();
        }
    }

    /**
     * Actualiza la lista de clientes disponibles.
     */
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

    /**
     * Envía mensaje al cliente seleccionado.
     */
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

    /**
     * Envía archivo al cliente seleccionado.
     */
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

        try {
            byte[] datos = new byte[(int) f.length()];
            try (FileInputStream fis = new FileInputStream(f)) {
                fis.read(datos);
            }

            dos.writeUTF("FILE:" + destino + ":" + f.getName() + ":" + datos.length);
            dos.write(datos);
            dos.flush();
            appendMensaje("Archivo enviado a " + destino + ": " + f.getName() + "\n");
        } catch (IOException e) {
            appendMensaje("Error enviando archivo: " + e.getMessage() + "\n");
        }
    }

    /**
     * Desconecta del servidor.
     */
    private void desconectar() {
        try {
            if (dis != null) dis.close();
            if (dos != null) dos.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
        socket = null;

        btnConectar.setEnabled(true);
        btnDesconectar.setEnabled(false);
        btnEnviarMsg.setEnabled(false);
        btnEnviarArchivo.setEnabled(false);

        appendMensaje("Desconectado.\n");
    }

    /**
     * Agrega mensaje al área de texto.
     */
    private void appendMensaje(String msg) {
        SwingUtilities.invokeLater(() -> areaMensajes.append(msg));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new PrincipalCli().setVisible(true));
    }
}
