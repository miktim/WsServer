/*
 * WebSocket. MIT (c) 2020-2023 miktim@mail.ru
 *
 * Creates ServerSocket/Socket (bind, connect).
 * Creates and starts listener/connection threads.
 *
 * Release notes:
 * - Java SE 7+, Android compatible;
 * - RFC-6455: https://tools.ietf.org/html/rfc6455 ;
 * - WebSocket protocol version: 13;
 * - WebSocket extensions not supported;
 * - supports plain socket/TLS connections;
 * - stream-based messaging.
 *
 * 3.1.0
 * - wsParameters moved from WebSocket constructor to listener/connection creators
 *
 * TODO: Internationalized Domain Names (IDNs) support
 *
 * Created: 2020-06-06
 */
package org.miktim.websocket;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class WebSocket {

    public static String VERSION = "3.2.0";
    private InetAddress bindAddress = null;
    private final List<Object> connections = Collections.synchronizedList(new ArrayList<>());
    private final List<Object> listeners = Collections.synchronizedList(new ArrayList<>());

    public WebSocket() throws NoSuchAlgorithmException {
        MessageDigest.getInstance("SHA-1"); // check algorithm exists
    }

    public WebSocket(InetAddress bindAddr) throws SocketException {
        super();
        if (NetworkInterface.getByInetAddress(bindAddr) == null) {
            throw new BindException("Not interface");
        }
        bindAddress = bindAddr;
    }

    public static void setTrustStore(String jksFile, String passphrase) {
        System.setProperty("javax.net.ssl.trustStore", jksFile);
        System.setProperty("javax.net.ssl.trustStorePassword", passphrase);
    }

    public static void setKeyStore(String jksFile, String passphrase) {
        System.setProperty("javax.net.ssl.keyStore", jksFile);
        System.setProperty("javax.net.ssl.keyStorePassword", passphrase);
    }

    public WsConnection[] listConnections() {
        return connections.toArray(new WsConnection[0]);
    }

    public WsListener[] listListeners() {
        return listeners.toArray(new WsListener[0]);
    }

    public void closeAll(String closeReason) {
// close WebSocket listeners/connections 
        for (WsListener listener : listListeners()) {
            listener.close(closeReason);
        }
        for (WsConnection conn : listConnections()) {
            conn.close(WsStatus.GOING_AWAY, closeReason);
        }
    }

    public void closeAll() {
        closeAll("");
    }

    public WsListener listen(int port, WsHandler handler, WsParameters wsp)
            throws IOException, GeneralSecurityException {
        return startListener(port, handler, false, wsp);
    }

    public WsListener listenSafely(int port, WsHandler handler, WsParameters wsp)
            throws IOException, GeneralSecurityException {
        return startListener(port, handler, true, wsp);
    }

    WsListener startListener(int port, WsHandler handler, boolean secure, WsParameters wsp)
            throws IOException, GeneralSecurityException {
        if (handler == null || wsp == null) {
            throw new NullPointerException();
        }

        ServerSocket serverSocket = getServerSocketFactory(secure)
                .createServerSocket();
        if (secure) {
            ((SSLServerSocket) serverSocket).setSSLParameters(wsp.sslParameters);
        }

        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindAddress, port));
        serverSocket.setSoTimeout(0);

        WsListener listener = new WsListener(
                serverSocket, handler, secure, wsp);
        listener.listeners = listeners;
        listener.start();
        return listener;
    }

// https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/samples/sockets/server/ClassFileServer.java
    private ServerSocketFactory getServerSocketFactory(boolean isSecure)
            throws IOException, GeneralSecurityException {
//            throws NoSuchAlgorithmException, KeyStoreException,
//            FileNotFoundException, IOException, CertificateException,
//            UnrecoverableKeyException {
        if (isSecure) {
            SSLServerSocketFactory ssf;
            SSLContext ctx;
            KeyManagerFactory kmf;
            KeyStore ks;
            String ksPassphrase = System.getProperty("javax.net.ssl.keyStorePassword");
            char[] passphrase = ksPassphrase.toCharArray();

            ctx = SSLContext.getInstance("TLS");
            kmf = KeyManagerFactory.getInstance("SunX509");
            ks = KeyStore.getInstance("JKS"); //
            File ksFile = new File(System.getProperty("javax.net.ssl.keyStore"));
            ks.load(new FileInputStream(ksFile), passphrase);
            kmf.init(ks, passphrase);
//        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
//        tmf.init(ks);
//        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            ctx.init(kmf.getKeyManagers(), null, null);

            ssf = ctx.getServerSocketFactory();
            return ssf;
        } else {
            return ServerSocketFactory.getDefault();
        }
    }

    public WsConnection connect(String uri, WsHandler handler, WsParameters wsp)
            throws IOException, GeneralSecurityException, URISyntaxException {
        if (uri == null || handler == null || wsp == null) {
            throw new NullPointerException();
        }
        URI requestURI = new URI(uri);
        String scheme = requestURI.getScheme();
        String host = requestURI.getHost();
        if (host == null || scheme == null) {
            throw new URISyntaxException(uri, "Scheme and host required");
        }
        if (!(scheme.equals("ws") || scheme.equals("wss"))) {
            throw new URISyntaxException(uri, "Unsupported scheme");
        }
        Socket socket;
        boolean isSecure;
        if (scheme.equals("wss")) {
            isSecure = true;
            SSLSocketFactory factory
                    = (SSLSocketFactory) SSLSocketFactory.getDefault();
            socket = (SSLSocket) factory.createSocket();
            if (wsp.sslParameters != null) {
                ((SSLSocket) socket).setSSLParameters(wsp.sslParameters);
            }
        } else {
            isSecure = false;
            socket = new Socket();
        }
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(bindAddress, 0));
        int port = requestURI.getPort();
        if (port < 0) {
            port = isSecure ? 443 : 80;
        }
        socket.connect(
                new InetSocketAddress(requestURI.getHost(), port), wsp.handshakeSoTimeout);

        WsConnection conn = new WsConnection(socket, handler, requestURI, wsp);
        conn.connections = this.connections;
        conn.start();
        return conn;
    }

}
