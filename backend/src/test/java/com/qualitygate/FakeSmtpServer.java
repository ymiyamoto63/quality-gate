package com.qualitygate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 受け取ったメールを記録するだけの SMTP サーバ（結合テスト用）。
 *
 * <p>本物のメール送信を行わずに、通知が「誰に・何を」送ったかを確かめる。
 * {@code failWith} を設定すると、DATA の応答で一時的な失敗（4xx）を返す。
 */
final class FakeSmtpServer implements AutoCloseable {

    record Mail(String to, String content) {
    }

    private final ServerSocket socket;
    private final List<Mail> received = new CopyOnWriteArrayList<>();
    private volatile String failWith;
    private final Thread thread;

    FakeSmtpServer(int port) throws IOException {
        socket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        thread = Thread.ofVirtual().start(this::serve);
    }

    List<Mail> received() {
        return received;
    }

    void failWith(String reply) {
        this.failWith = reply;
    }

    private void serve() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                handle(client);
            } catch (IOException e) {
                // 閉じたときに accept が例外になる。テストの終了なので何もしない
            }
        }
    }

    private void handle(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8);
        out.print("220 fake-smtp\r\n");
        out.flush();
        String to = null;
        String line;
        while ((line = in.readLine()) != null) {
            String command = line.toUpperCase();
            if (command.startsWith("EHLO") || command.startsWith("HELO")) {
                reply(out, "250 fake-smtp");
            } else if (command.startsWith("RCPT TO:")) {
                to = line.substring(8).replaceAll("[<>\\s]", "");
                reply(out, "250 OK");
            } else if (command.startsWith("DATA")) {
                reply(out, "354 End data with <CR><LF>.<CR><LF>");
                StringBuilder content = new StringBuilder();
                String data;
                while ((data = in.readLine()) != null && !data.equals(".")) {
                    content.append(data).append('\n');
                }
                if (failWith != null) {
                    reply(out, failWith);
                } else {
                    received.add(new Mail(to, content.toString()));
                    reply(out, "250 OK");
                }
            } else if (command.startsWith("QUIT")) {
                reply(out, "221 Bye");
                return;
            } else {
                reply(out, "250 OK");
            }
        }
    }

    private static void reply(PrintWriter out, String message) {
        out.print(message + "\r\n");
        out.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
        thread.interrupt();
    }
}
