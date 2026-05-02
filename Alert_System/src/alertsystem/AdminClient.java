package alertsystem;

import java.net.*;
import java.io.*;
import java.util.Scanner;

public class AdminClient {

    static final int SERVER_PORT = 6700;
    static final int SO_TIMEOUT = 5000; // 5 s wait for server reply

    public static void main(String[] args) {
        String serverHost;
        if (args.length > 0) {
            serverHost = args[0];
        } else {
            serverHost = "localhost";
        }

        DatagramSocket socket = null;
        Scanner scanner = new Scanner(System.in);

        try {
            // Bind to any free local port so the server can send replies back
            socket = new DatagramSocket();
            socket.setSoTimeout(SO_TIMEOUT); // Sets receive timeout to 5 seconds. So socket.receive() will not block
                                             // forever.

            System.out.println("Admin Client");
            System.out.println("Listening on port " + socket.getLocalPort());
            System.out.print("Enter your admin username: ");
            String adminName = scanner.nextLine().trim();

            printAdminHelp();
            final DatagramSocket finalSocket = socket;
            Thread receiver = new Thread(() -> {
                byte[] buf = new byte[4096];
                while (!finalSocket.isClosed()) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    try {
                        finalSocket.receive(pkt);
                        String msg = new String(pkt.getData(), 0, pkt.getLength(), "UTF-8");
                        System.out.println("\n[SERVER] " + msg);
                        System.out.print("> ");
                    } catch (SocketTimeoutException e) {
                        // normal – keep looping
                    } catch (IOException e) {
                        if (!finalSocket.isClosed())
                            System.out.println("Receive error: " + e.getMessage());
                    }
                }
            });
            receiver.setDaemon(true);

            InetAddress serverAddr = InetAddress.getByName(serverHost);

            while (true) {
                System.out.print("> ");
                String input = scanner.nextLine().trim();
                if (input.isEmpty())
                    continue;

                String[] tokens = input.split(" ", 2);
                String cmd = tokens[0].toUpperCase();

                String msgToSend = null;

                switch (cmd) {
                    case "CREATE":
                        if (tokens.length < 2) {
                            System.out.println("Usage: create <groupName>");
                            continue;
                        }
                        msgToSend = "CREATE_GROUP|" + adminName + "|" + tokens[1].trim();
                        break;

                    case "ALERT":
                        // alert <groupName|ALL> <message...>
                        if (tokens.length < 2) {
                            System.out.println("Usage: alert <groupName|ALL> <message>");
                            continue;
                        }
                        String[] alertParts = tokens[1].split(" ", 2);
                        if (alertParts.length < 2) {
                            System.out.println("Usage: alert <groupName|ALL> <message>");
                            continue;
                        }
                        msgToSend = "ALERT|" + adminName + "|" + alertParts[0] + "|" + alertParts[1];
                        break;

                    case "INBOX":
                        msgToSend = "INBOX|" + adminName;
                        break;

                    case "REPLY":
                        // reply <questionId> <text...>
                        if (tokens.length < 2) {
                            System.out.println("Usage: reply <questionId> <replyText>");
                            continue;
                        }
                        String[] replyParts = tokens[1].split(" ", 2);
                        if (replyParts.length < 2) {
                            System.out.println("Usage: reply <questionId> <replyText>");
                            continue;
                        }
                        msgToSend = "REPLY|" + adminName + "|" + replyParts[0] + "|" + replyParts[1];
                        break;

                    case "HELP":
                        printAdminHelp();
                        continue;

                    case "QUIT":
                    case "EXIT":
                        System.out.println("Goodbye.");
                        return;

                    default:
                        System.out.println("Unknown command. Type HELP for usage.");
                        continue;
                }

                // Send and receive reply
                String response = sendAndReceive(socket, msgToSend, serverAddr, SERVER_PORT);
                if (response != null) {
                    formatAndPrint(response);
                } else {
                    System.out.println("[No response from server – check connectivity]");
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

    // -------------------------------------------------------------------------
    // Send a UDP packet and wait for a single reply
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

    // -------------------------------------------------------------------------
    // Pretty-print server responses
    // -------------------------------------------------------------------------
    static void formatAndPrint(String response) {
        if (response.startsWith("INBOX")) {
            String[] lines = response.split("\n");
            if (lines.length == 1 && lines[0].equals("INBOX|EMPTY")) {
                System.out.println("[Inbox is empty]");
                return;
            }
            System.out.println("INBOX ");
            // lines[0] is "INBOX", rest are entries: id|username|ip|port|group|text|status
            for (int i = 1; i < lines.length; i++) {
                String[] f = lines[i].split("\\|", -1);
                if (f.length >= 7) {
                    System.out.printf("│ ID      : %s%n", f[0]);
                    System.out.printf("│ From    : %s (group: %s)%n", f[1], f[4]);
                    System.out.printf("│ Question: %s%n", f[5]);
                    System.out.printf("│ Status  : %s%n", f[6]);
                }
            }
        } else {
            System.out.println("[Server] " + response.replace("|", " | "));
        }
    }

    static void printAdminHelp() {
        System.out.println("│  Admin Commands                                          │");
        System.out.println("│  create <groupName>           – create a new group       │");
        System.out.println("│  alert <groupName|ALL> <msg>  – send alert               │");
        System.out.println("│  inbox                        – view questions inbox     │");
        System.out.println("│  reply <questionId> <text>    – reply to a question      │");
        System.out.println("│  help                         – show this menu           │");
        System.out.println("│  quit                         – exit                     │");
    }
}
