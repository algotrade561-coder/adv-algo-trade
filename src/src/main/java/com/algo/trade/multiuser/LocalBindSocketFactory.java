package com.algo.trade.multiuser;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A {@link SocketFactory} that binds every socket it creates to a fixed local
 * (source) IP before connecting. Used to force outbound connections — e.g. the
 * shared Kite market-data WebSocket — to egress from a specific whitelisted IP
 * on a multi-IP host (SEBI static-IP rule).
 *
 * <p>OkHttp obtains its raw TCP socket from this factory's no-arg
 * {@link #createSocket()} and then connects/TLS-wraps it, so binding the raw
 * socket here pins the source IP even for {@code wss://} connections.
 *
 * <p>The bind address must be a private IP actually assigned to a local
 * interface (validate with {@link SourceIpValidator} before use); a public /
 * Elastic IP is NAT'd and can never be a valid bind source.
 */
public final class LocalBindSocketFactory extends SocketFactory {

    private final InetAddress localAddress;

    public LocalBindSocketFactory(InetAddress localAddress) {
        if (localAddress == null) {
            throw new IllegalArgumentException("localAddress must not be null");
        }
        this.localAddress = localAddress;
    }

    private Socket bound() throws IOException {
        Socket s = new Socket();
        s.bind(new InetSocketAddress(localAddress, 0)); // port 0 = ephemeral
        return s;
    }

    @Override
    public Socket createSocket() throws IOException {
        return bound(); // OkHttp connects this itself
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket s = bound();
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket s = new Socket();
        s.bind(new InetSocketAddress(localHost != null ? localHost : localAddress, localPort));
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket s = bound();
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddr, int localPort) throws IOException {
        Socket s = new Socket();
        s.bind(new InetSocketAddress(localAddr != null ? localAddr : this.localAddress, localPort));
        s.connect(new InetSocketAddress(address, port));
        return s;
    }
}
