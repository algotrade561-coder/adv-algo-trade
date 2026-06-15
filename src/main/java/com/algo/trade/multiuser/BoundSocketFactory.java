package com.algo.trade.multiuser;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A SocketFactory that binds all outbound connections to a specific local IP address.
 *
 * Used by OkHttpClient to route per-user Kite API calls (token exchange, WebSocket)
 * through different Elastic IPs attached to secondary ENIs on the EC2 instance.
 *
 * SEBI's static-IP rule (effective Apr 2026) requires each user's API key to be
 * whitelisted against a unique IP address.
 *
 * Example:
 *   User 1 → source IP 10.0.1.10 → Elastic IP 3.x.x.1 → Kite API
 *   User 2 → source IP 10.0.1.20 → Elastic IP 3.x.x.2 → Kite API
 */
public class BoundSocketFactory extends SocketFactory {

    private final InetAddress localAddress;

    public BoundSocketFactory(InetAddress localAddress) {
        this.localAddress = localAddress;
    }

    @Override
    public Socket createSocket() throws IOException {
        Socket socket = new Socket();
        socket.bind(new InetSocketAddress(localAddress, 0));
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = new Socket();
        socket.bind(new InetSocketAddress(localAddress, 0));
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket socket = new Socket();
        socket.bind(new InetSocketAddress(localAddress, localPort));
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket socket = new Socket();
        socket.bind(new InetSocketAddress(localAddress, 0));
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket socket = new Socket();
        socket.bind(new InetSocketAddress(localAddress, localPort));
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }
}
