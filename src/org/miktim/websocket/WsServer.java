/*
 * WsServer. WebSocket Server, MIT (c) 2020-2024 miktim@mail.ru
 *
 * Accepts sockets, creates and starts connection threads.
 *
 * Created: 2020-03-09
 */
package org.miktim.websocket;

import java.io.IOException;
import java.net.InetAddress;
//import java.io.InterruptedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WsServer extends Thread {

    static final int IS_OPEN = 0;
    static final int IS_INTERRUPTED = -1;
    static final int IS_CLOSED = -2;

    private int status = IS_CLOSED;

    private Exception error = null;
    private final boolean isSecure;
    private final WsParameters wsp;
    private final ServerSocket serverSocket;
    private final WsConnection.EventHandler connectionHandler; // connection handler
    List<WsServer> servers = null;
    private final List<WsConnection> connections
            = Collections.synchronizedList(new ArrayList<WsConnection>());

// 'dumb' handler    
    private EventHandler serverHandler = new EventHandler() {
        @Override
        public void onStart(WsServer server) {
        }

        @Override
        public boolean onAccept(WsServer server, WsConnection conn) {
            return true;
        }

        @Override
        public void onStop(WsServer server, Exception error) {
            if (error != null) {
                error.printStackTrace();
            }
        }
    };

    WsServer(ServerSocket ss, WsConnection.EventHandler h, boolean secure, WsParameters wsp) {
        this.serverSocket = ss;
        this.connectionHandler = h;
        this.isSecure = secure;
        this.wsp = wsp;
    }

    public boolean isSecure() {
        return isSecure;
    }

    public boolean isOpen() {
        return status == IS_OPEN;
    }

    public boolean isInterrupted() {
        return status == IS_INTERRUPTED;
    }

    public Exception getError() {
        return error;
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public InetAddress getBindAddress() {
        return serverSocket.getInetAddress();
    }

    public WsParameters getParameters() {
        return wsp;
    }

    public WsServer launch() {
        this.start();
        return this;
    }

    public WsConnection[] listConnections() {
        return connections.toArray(new WsConnection[0]);
    }

    public void close() {
        close(null);
    }

    synchronized public void close(String closeReason) {
        if (status != IS_CLOSED) {
            status = IS_CLOSED;
            closeServerSocket();

// close associated connections
            for (WsConnection connection : listConnections()) {
                connection.close(WsStatus.GOING_AWAY, closeReason);
            }
            servers.remove(this); // remove from WebSocket server list
        }
    }

    synchronized public void interrupt() {
        if (isOpen()) {
            status = IS_INTERRUPTED;
            closeServerSocket();
        }
    }

    synchronized public WsServer setHandler(WsServer.EventHandler handler) {
        this.serverHandler = handler;
        return this;
    }

    void closeServerSocket() {
        try {
            serverSocket.close();
        } catch (IOException e) {
//            e.printStackTrace();
        }
    }
    
    @Override
    public void run() {
        servers.add(this); // add to WebSocket server list
        status = IS_OPEN;
        serverHandler.onStart(this);
        while (isOpen()) {
            try {
// serverSocket SO_TIMEOUT = 0 by WebSocket creator
                Socket socket = serverSocket.accept();
                WsConnection conn
                        = new WsConnection(socket, connectionHandler, isSecure, wsp);
                socket.setSoTimeout(wsp.handshakeSoTimeout);
                conn.connections = this.connections;
                if (serverHandler.onAccept(this, conn)) {
                    conn.start();
                } else {
                    conn.closeSocket();
                }
            } catch (IOException e) {
                if (isOpen()) {
                    error = e;
                    interrupt();
                }
                break;
            }
        }
        serverHandler.onStop(this, error);
    }

    public interface EventHandler {

        void onStart(WsServer server);

        boolean onAccept(WsServer server, WsConnection conn);

        void onStop(WsServer server, Exception error);
    }
}
