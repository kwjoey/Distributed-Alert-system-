package alertsystem;

import java.net.*;
import java.io.*;
import java.util.*;

public class AlertServer {

    static final int SERVER_PORT = 6700;
    static final String DATA_DIR = "data/";

    public static void main(String[] args) {
        DatagramSocket socket = null;
        // Ensure data directory exists
        new File(DATA_DIR).mkdirs();

        try {
            socket = new DatagramSocket(SERVER_PORT);
            System.out.println("Alert Server started on port " + SERVER_PORT);

            byte[] buffer = new byte[4096];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String msg = new String(packet.getData(), 0, packet.getLength(), "UTF-8").trim();
                InetAddress senderIp = packet.getAddress();
                int senderPort = packet.getPort();

                System.out.println("[IN]  " + senderIp + ":" + senderPort + " -> " + msg);

                String response = handleMessage(msg, senderIp, senderPort, socket);

                if (response != null) {
                    byte[] resp = response.getBytes("UTF-8");
                    DatagramPacket reply = new DatagramPacket(resp, resp.length, senderIp, senderPort);
                    socket.send(reply);
                    System.out.println("[OUT] -> " + senderIp + ":" + senderPort + " : " + response);
                }
            }

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
    // Message dispatcher
    // -------------------------------------------------------------------------
    static String handleMessage(String msg, InetAddress ip, int port, DatagramSocket socket) {
        String[] parts = msg.split("\\|", -1);
        if (parts.length == 0)
            return "ERROR|Empty message";

        String cmd = parts[0].toUpperCase();

        switch (cmd) {
            case "REGISTER":
                return handleRegister(parts, ip, port);
            case "LEAVE":
                return handleLeave(parts);
            case "ALERT":
                return handleAlert(parts, socket);
            case "QUESTION":
                return handleQuestion(parts, ip, port);
            case "INBOX":
                return handleInbox(parts);
            case "REPLY":
                return handleReply(parts, socket);
            case "CREATE_GROUP":
                return handleCreateGroup(parts);
            default:
                return "ERROR|Unknown command: " + cmd;
        }
    }

    // -------------------------------------------------------------------------
    // CREATE_GROUP|<adminName>|<groupName>
    // Groups are disjoint: a group can only have one admin.
    // An admin can own multiple groups but cannot own a group already owned by
    // another.
    // -------------------------------------------------------------------------
    static String handleCreateGroup(String[] p) {
        if (p.length < 3)
            return "ERROR|Usage: CREATE_GROUP|adminName|groupName";
        String adminName = p[1].trim();
        String groupName = p[2].trim();

        // Check if group already exists
        String existingAdmin = getGroupAdmin(groupName);
        if (existingAdmin != null) {
            if (existingAdmin.equals(adminName))
                return "OK|Group '" + groupName + "' already exists and belongs to you";
            return "ERROR|Group '" + groupName + "' already belongs to admin '" + existingAdmin + "'";
        }

        // Register new group
        appendLine(DATA_DIR + "groups.txt", groupName + "|" + adminName);
        // Create empty member file
        new File(DATA_DIR + "members_" + groupName + ".txt"); // will be created on first write
        System.out.println("[GROUP] Created '" + groupName + "' for admin '" + adminName + "'");
        return "OK|Group '" + groupName + "' created. Members can now register.";
    }

    // -------------------------------------------------------------------------
    // REGISTER|<username>|<groupName>
    // -------------------------------------------------------------------------
    static String handleRegister(String[] p, InetAddress ip, int port) {
        if (p.length < 3)
            return "ERROR|Usage: REGISTER|username|groupName";
        String username = p[1].trim();
        String groupName = p[2].trim();

        // Group must exist
        String admin = getGroupAdmin(groupName);
        if (admin == null)
            return "ERROR|Group '" + groupName + "' does not exist. Ask an admin to create it.";

        // Add or update member in members_<group>.txt
        String filePath = DATA_DIR + "members_" + groupName + ".txt";
        List<String> lines = readLines(filePath);
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            String[] fields = lines.get(i).split("\\|");
            if (fields[0].equals(username)) {
                lines.set(i, username + "|" + ip.getHostAddress() + "|" + port);
                found = true;
                break;
            }
        }
        if (!found)
            lines.add(username + "|" + ip.getHostAddress() + "|" + port);
        writeLines(filePath, lines);

        return "OK|Registered '" + username + "' to group '" + groupName + "'";
    }

    // -------------------------------------------------------------------------
    // LEAVE|<username>|<groupName>
    // -------------------------------------------------------------------------
    static String handleLeave(String[] p) {
        if (p.length < 3)
            return "ERROR|Usage: LEAVE|username|groupName";
        String username = p[1].trim();
        String groupName = p[2].trim();

        String filePath = DATA_DIR + "members_" + groupName + ".txt";
        List<String> lines = readLines(filePath);
        boolean removed = lines.removeIf(l -> l.split("\\|")[0].equals(username));
        if (!removed)
            return "ERROR|'" + username + "' is not a member of '" + groupName + "'";
        writeLines(filePath, lines);
        return "OK|'" + username + "' left group '" + groupName + "'";
    }

    // -------------------------------------------------------------------------
    // ALERT|<adminName>|<groupName>|<message>
    // groupName can be ALL → broadcast to all groups owned by this admin
    // -------------------------------------------------------------------------
    static String handleAlert(String[] p, DatagramSocket socket) {
        if (p.length < 4)
            return "ERROR|Usage: ALERT|adminName|groupName|message";
        String adminName = p[1].trim();
        String groupName = p[2].trim();
        // message may contain '|', re-join
        String message = joinFrom(p, 3);

        List<String> groups;
        if (groupName.equalsIgnoreCase("ALL")) {
            groups = getGroupsOfAdmin(adminName);
            if (groups.isEmpty())
                return "ERROR|No groups found for admin '" + adminName + "'";
        } else {
            String owner = getGroupAdmin(groupName);
            if (owner == null)
                return "ERROR|Group '" + groupName + "' does not exist";
            if (!owner.equals(adminName))
                return "ERROR|You are not the admin of group '" + groupName + "'";
            groups = new ArrayList<>();
            groups.add(groupName);
        }

        int delivered = 0;
        for (String g : groups) {
            String filePath = DATA_DIR + "members_" + g + ".txt";
            List<String> members = readLines(filePath);
            String alertMsg = "ALERT|" + g + "|" + message;
            for (String member : members) {
                String[] fields = member.split("\\|");
                if (fields.length < 3)
                    continue;
                try {
                    InetAddress addr = InetAddress.getByName(fields[1]);
                    int mport = Integer.parseInt(fields[2]);
                    byte[] data = alertMsg.getBytes("UTF-8");
                    socket.send(new DatagramPacket(data, data.length, addr, mport));
                    System.out.println("[ALERT] Sent to " + fields[0] + " @ " + fields[1] + ":" + mport);
                    delivered++;
                } catch (Exception e) {
                    System.out.println("[ALERT] Failed to reach " + fields[0] + ": " + e.getMessage());
                }
            }
        }
        return "OK|Alert delivered to " + delivered + " member(s) across " + groups.size() + " group(s)";
    }

    // -------------------------------------------------------------------------
    // QUESTION|<username>|<groupName>|<text>
    // -------------------------------------------------------------------------
    static String handleQuestion(String[] p, InetAddress ip, int port) {
        if (p.length < 4)
            return "ERROR|Usage: QUESTION|username|groupName|text";
        String username = p[1].trim();
        String groupName = p[2].trim();
        String text = joinFrom(p, 3);

        String admin = getGroupAdmin(groupName);
        if (admin == null)
            return "ERROR|Group '" + groupName + "' does not exist";

        // Generate simple ID: timestamp
        String id = String.valueOf(System.currentTimeMillis());

        String filePath = DATA_DIR + "inbox_" + admin + ".txt";
        String entry = id + "|" + username + "|" + ip.getHostAddress() + "|" + port + "|" + groupName + "|" + text
                + "|PENDING";
        appendLine(filePath, entry);

        return "QUESTION_ID|" + id + "|Question sent to admin of group '" + groupName + "'";
    }

    // -------------------------------------------------------------------------
    // INBOX|<adminName>
    // -------------------------------------------------------------------------
    static String handleInbox(String[] p) {
        if (p.length < 2)
            return "ERROR|Usage: INBOX|adminName";
        String adminName = p[1].trim();

        String filePath = DATA_DIR + "inbox_" + adminName + ".txt";
        List<String> lines = readLines(filePath);
        if (lines.isEmpty())
            return "INBOX|EMPTY";

        StringBuilder sb = new StringBuilder("INBOX");
        for (String line : lines) {
            sb.append("\n").append(line);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // REPLY|<adminName>|<questionId>|<replyText>
    // -------------------------------------------------------------------------
    static String handleReply(String[] p, DatagramSocket socket) {
        if (p.length < 4)
            return "ERROR|Usage: REPLY|adminName|questionId|replyText";
        String adminName = p[1].trim();
        String questionId = p[2].trim();
        String replyText = joinFrom(p, 3);

        String filePath = DATA_DIR + "inbox_" + adminName + ".txt";
        List<String> lines = readLines(filePath);

        String targetUser = null, targetIp = null, targetPort = null;
        boolean found = false;

        for (int i = 0; i < lines.size(); i++) {
            String[] fields = lines.get(i).split("\\|", -1);
            // id|username|ip|port|group|text|status
            if (fields.length >= 7 && fields[0].equals(questionId)) {
                targetUser = fields[1];
                targetIp = fields[2];
                targetPort = fields[3];
                // Mark as answered
                fields[6] = "ANSWERED";
                lines.set(i, String.join("|", fields));
                found = true;
                break;
            }
        }

        if (!found)
            return "ERROR|Question ID '" + questionId + "' not found in your inbox";

        writeLines(filePath, lines);

        // Forward reply to the original asker
        try {
            InetAddress addr = InetAddress.getByName(targetIp);
            int port = Integer.parseInt(targetPort);
            String replyMsg = "REPLY|" + adminName + "|" + replyText;
            byte[] data = replyMsg.getBytes("UTF-8");
            socket.send(new DatagramPacket(data, data.length, addr, port));
            System.out.println("[REPLY] Sent to " + targetUser + " @ " + targetIp + ":" + targetPort);
        } catch (Exception e) {
            return "ERROR|Could not reach user '" + targetUser + "': " + e.getMessage();
        }

        return "OK|Reply sent to '" + targetUser + "'";
    }

    // =========================================================================
    // Admin / Group management helpers
    // These are administrative operations done by editing groups.txt directly
    // or via the admin client CREATE_GROUP command (handled below).
    // =========================================================================

    /** Returns the admin of the given group, or null if group doesn't exist. */
    static String getGroupAdmin(String groupName) {
        List<String> lines = readLines(DATA_DIR + "groups.txt");
        for (String line : lines) {
            String[] f = line.split("\\|");
            if (f.length >= 2 && f[0].equals(groupName))
                return f[1];
        }
        return null;
    }

    /** Returns all group names owned by a given admin. */
    static List<String> getGroupsOfAdmin(String adminName) {
        List<String> result = new ArrayList<>();
        List<String> lines = readLines(DATA_DIR + "groups.txt");
        for (String line : lines) {
            String[] f = line.split("\\|");
            if (f.length >= 2 && f[1].equals(adminName))
                result.add(f[0]);
        }
        return result;
    }

    // =========================================================================
    // File I/O helpers
    // =========================================================================

    static List<String> readLines(String path) {
        List<String> lines = new ArrayList<>();
        File f = new File(path);
        if (!f.exists())
            return lines;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) { // This is a try-with-resources, so Java
                                                                          // closes it automatically.
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty())
                    lines.add(line);
            }
        } catch (IOException e) {
            System.out.println("Read error [" + path + "]: " + e.getMessage());
        }
        return lines;
    }

    static void writeLines(String path, List<String> lines) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path, false))) { // false = overwrite the whole file
            for (String l : lines)
                pw.println(l);
        } catch (IOException e) {
            System.out.println("Write error [" + path + "]: " + e.getMessage());
        }
    }

    static void appendLine(String path, String line) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path, true))) { // true = append to the file and dont erase
                                                                             // existing content
            pw.println(line);
        } catch (IOException e) {
            System.out.println("Append error [" + path + "]: " + e.getMessage());
        }
    }

    static String joinFrom(String[] parts, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < parts.length; i++) {
            if (i > start)
                sb.append("|");
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
