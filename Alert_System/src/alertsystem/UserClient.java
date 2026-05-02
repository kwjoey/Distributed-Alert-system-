package alertsystem;

import java.net.*;
import java.io.*;
import java.util.Scanner;

public class UserClient {

    static final int SERVER_PORT = 6700;
    static final int SO_TIMEOUT = 3000; // ms for request/reply cycle

    public static void main(String[] args) {
        String serverHost = (args.length > 0) ? args[0] : "localhost";

        DatagramSocket socket = null;
        Scanner scanner = new Scanner(System.in);

        try {
            // Let OS assign a free port; recorded by the server so alerts reach us
            socket = new DatagramSocket();

            System.out.println("=== User Client ===");
            System.out.println("My port: " + socket.getLocalPort() + " (server will push alerts here)");
            System.out.print("Enter your username: ");
            String username = scanner.nextLine().trim();

            System.out.println("Hello, " + username + "! Type HELP for commands.");

            InetAddress serverAddr = InetAddress.getByName(serverHost);

            // Background listener for push messages (alerts and replies)
            final DatagramSocket listenSocket = socket;
            Thread listener = new Thread(() -> {
                byte[] buf = new byte[4096];
                while (!listenSocket.isClosed()) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    try {
                        listenSocket.receive(pkt);
                        String msg = new String(pkt.getData(), 0, pkt.getLength(), "UTF-8").trim();
                        handlePushedMessage(msg, username);
                    } catch (SocketTimeoutException e) {
                        // normal
                    } catch (IOException e) {
                        if (!listenSocket.isClosed())
                            System.out.println("Listener error: " + e.getMessage());
                    }
                }
            });

            socket.setSoTimeout(SO_TIMEOUT);
            listener.setDaemon(true);
            listener.start();

            printUserHelp();

            while (true) {
                System.out.print("> ");
                String input = scanner.nextLine().trim();
                if (input.isEmpty())
                    continue;

                String[] tokens = input.split(" ", 2);
                String cmd = tokens[0].toUpperCase();

                String msgToSend = null;

                switch (cmd) {
                    case "REGISTER":
                        if (tokens.length < 2) {
                            System.out.println("Usage: register <groupName>");
                            continue;
                        }
                        msgToSend = "REGISTER|" + username + "|" + tokens[1].trim();
                        break;

                    case "LEAVE":
                        if (tokens.length < 2) {
                            System.out.println("Usage: leave <groupName>");
                            continue;
                        }
                        msgToSend = "LEAVE|" + username + "|" + tokens[1].trim();
                        break;

                    case "QUESTION":
                        // question <groupName> <text...>
                        if (tokens.length < 2) {
                            System.out.println("Usage: question <groupName> <your question>");
                            continue;
                        }
                        String[] qParts = tokens[1].split(" ", 2);
                        if (qParts.length < 2) {
                            System.out.println("Usage: question <groupName> <your question>");
                            continue;
                        }
                        msgToSend = "QUESTION|" + username + "|" + qParts[0] + "|" + qParts[1];
                        break;

                    case "HELP":
                        printUserHelp();
                        continue;

                    case "QUIT":
                    case "EXIT":
                        System.out.println("Goodbye, " + username + "!");
                        return;

                    default:
                        System.out.println("Unknown command. Type HELP.");
                        continue;
                }

                // Send and wait for acknowledgement from server
                String response = sendAndReceive(socket, msgToSend, serverAddr, SERVER_PORT);
                if (response != null) {
                    // Ignore push messages that slipped in; print server ack
                    if (!response.startsWith("ALERT") && !response.startsWith("REPLY")) {
                        System.out.println("[Server] " + response.replace("|", " | "));
                    } else {
                        handlePushedMessage(response, username);
                    }
                } else {
                    System.out.println("[No response – server may be down or busy]");
                }
            }

        } catch (UnknownHostException e) {
            System.out.println("Unknown host: " + e.getMessage());
        } catch (SocketException e) {
            System.out.println("Socket error: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO error: " + e.getMessage());
        } finally {
            if (socket != null)
                socket.close();
        }
    }

    static void handlePushedMessage(String msg, String username) {
        if (msg.startsWith("ALERT|")) {
            // ALERT|<groupName>|<message>
            String[] f = msg.split("\\|", 3);
            String group = (f.length > 1) ? f[1] : "?";
            String message = (f.length > 2) ? f[2] : "";
            System.out.println();
            System.out.println("SECURITY ALERT ");
            System.out.println("║  Group  : " + group);
            System.out.println("║  Message: " + message);
            System.out.print("> ");
        } else if (msg.startsWith("REPLY|")) {
            // REPLY|<adminName>|<replyText>
            String[] f = msg.split("\\|", 3);
            String admin = (f.length > 1) ? f[1] : "Admin";
            String reply = (f.length > 2) ? f[2] : "";
            System.out.println();
            System.out.println("┌── Reply from " + admin);
            System.out.println(reply);
            System.out.print("> ");
        }
    }

    // -------------------------------------------------------------------------
    // Send a UDP packet and wait for a single reply (with timeout)
    // -------------------------------------------------------------------------
    static String sendAndReceive(DatagramSocket socket, String msg,
            InetAddress serverAddr, int serverPort) throws IOException {
        byte[] data = msg.getBytes("UTF-8");
        DatagramPacket request = new DatagramPacket(data, data.length, serverAddr, serverPort);
        socket.send(request);

        byte[] buffer = new byte[4096];
        DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(reply);
            return new String(reply.getData(), 0, reply.getLength(), "UTF-8");
        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    static void printUserHelp() {
        System.out.println("│  User Commands                                           │");
        System.out.println("│  register <groupName>              – join a group        │");
        System.out.println("│  leave <groupName>                 – leave a group       │");
        System.out.println("│  question <groupName> <text>       – ask the admin       │");
        System.out.println("│  help                              – show this menu      │");
        System.out.println("│  quit                              – exit                │");
    }
}
