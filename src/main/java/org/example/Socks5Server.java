package org.example;

import org.apache.tomcat.util.net.SocketWrapperBase;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class Socks5Server {
    private static final byte SOCKS_VERSION = 0x05;
    private static final byte NO_AUTHENTICATION = 0x00;
    private static final byte CONNECT_COMMAND = 0x01;
    private static final byte IPV4_ADDRESS = 0x01;
    private static final byte DOMAIN_NAME = 0x03;
    private static final byte IPV6_ADDRESS = 0x04;

    private SocketWrapperBase<?> socketWrapper;
    private ByteBuffer readBuffer;
    private int peekPosition;

    public void handleClient(SocketChannel channel, SocketWrapperBase<?> wrapper) throws IOException {
        this.socketWrapper = wrapper;
        this.readBuffer = wrapper.getSocketBufferHandler().getReadBuffer();
        if (channel == null) {
            throw new IOException("不支持的socket类型");
        }

        try {
            // 处理认证方法协商
            if (!handleAuthenticationNegotiation(channel)) {
                return;
            }

            // 处理连接请求
            if (!handleConnectionRequest(channel)) {
                return;
            }

            // 开始转发数据
            handleDataTransfer(channel);
        } catch (Exception exception) {
            throw exception;
        } finally {
            try {
                channel.close();
            } catch (IOException e) {
                // 忽略关闭时的异常
            }
        }
    }

    private boolean handleAuthenticationNegotiation(SocketChannel channel) throws IOException {
        // 读取版本和方法数量
        ByteBuffer buffer = ByteBuffer.allocate(2);
        if (!peekRead(buffer, 2)) {
            return false;
        }
        buffer.flip();

        byte version = buffer.get();
        if (version != SOCKS_VERSION) {
            return false;
        }

        int numMethods = buffer.get() & 0xFF;

        // 读取认证方法列表
        buffer = ByteBuffer.allocate(numMethods);
        if (!peekRead(buffer, numMethods)) {
            return false;
        }

        // 确认是SOCKS5协议，消费之前peek的数据
        consumePeekedData();

        // 回复客户端，选择无认证方法
        buffer = ByteBuffer.allocate(2);
        buffer.put(SOCKS_VERSION);
        buffer.put(NO_AUTHENTICATION);
        buffer.flip();
        writeFully(channel, buffer);

        return true;
    }

    private void consumePeekedData() {
        if (this.peekPosition > 0) {
            // 将readBuffer的position向前移动peekPosition个位置，实际消费数据
            readBuffer.position(readBuffer.position() + this.peekPosition);
            // 重置peekPosition
            this.peekPosition = 0;
        }
    }

    private boolean handleConnectionRequest(SocketChannel channel) throws IOException {
        // 读取请求头
        ByteBuffer buffer = ByteBuffer.allocate(4);
        readFully(channel, buffer);
        buffer.flip();

        byte version = buffer.get();
        byte command = buffer.get();
        buffer.get(); // 保留字节
        byte addressType = buffer.get();

        if (version != SOCKS_VERSION || command != CONNECT_COMMAND) {
            sendResponse(channel, (byte) 0x07); // 命令不支持
            return false;
        }

        // 读取目标地址
        String targetHost;
        int targetPort;

        switch (addressType) {
            case IPV4_ADDRESS:
                buffer = ByteBuffer.allocate(4);
                readFully(channel, buffer);
                buffer.flip();
                byte[] ipv4 = new byte[4];
                buffer.get(ipv4);
                targetHost = InetAddress.getByAddress(ipv4).getHostAddress();
                break;

            case DOMAIN_NAME:
                buffer = ByteBuffer.allocate(1);
                readFully(channel, buffer);
                buffer.flip();
                int domainLength = buffer.get() & 0xFF;

                buffer = ByteBuffer.allocate(domainLength);
                readFully(channel, buffer);
                buffer.flip();
                byte[] domain = new byte[domainLength];
                buffer.get(domain);
                targetHost = new String(domain);
                break;

            case IPV6_ADDRESS:
                buffer = ByteBuffer.allocate(16);
                readFully(channel, buffer);
                buffer.flip();
                byte[] ipv6 = new byte[16];
                buffer.get(ipv6);
                targetHost = InetAddress.getByAddress(ipv6).getHostAddress();
                break;

            default:
                sendResponse(channel, (byte) 0x08); // 地址类型不支持
                return false;
        }

        // 读取端口
        buffer = ByteBuffer.allocate(2);
        readFully(channel, buffer);
        buffer.flip();
        targetPort = ((buffer.get() & 0xFF) << 8) | (buffer.get() & 0xFF);

        try {
            // 连接目标服务器
            SocketChannel targetChannel = SocketChannel.open();
            targetChannel.connect(new InetSocketAddress(targetHost, targetPort));

            // 发送成功响应
            InetSocketAddress localAddress = (InetSocketAddress) targetChannel.getLocalAddress();
            byte[] bindAddress = localAddress.getAddress().getAddress();
            int bindPort = localAddress.getPort();

            ByteBuffer response = ByteBuffer.allocate(6 + bindAddress.length);
            response.put(SOCKS_VERSION);
            response.put((byte) 0x00); // 成功
            response.put((byte) 0x00); // 保留字节
            response.put((byte) (bindAddress.length == 4 ? IPV4_ADDRESS : IPV6_ADDRESS));
            response.put(bindAddress);
            response.putShort((short) bindPort);
            response.flip();
            writeFully(channel, response);

            // 开始转发数据
            startForwarding(channel, targetChannel);
            return true;

        } catch (IOException e) {
            sendResponse(channel, (byte) 0x04); // 主机不可达
            return false;
        }
    }

    private boolean peekRead(ByteBuffer rawBuffer, int expectedBytes) throws IOException {
        if (readBuffer == null) {
            throw new IOException("读取缓冲区未初始化");
        }

        // 检查是否有足够的数据
        if (readBuffer.remaining() < expectedBytes) {
            return false;
        }
        // 保存当前position
        int originalPosition = readBuffer.position();

        // 读取数据
        byte[] data = new byte[expectedBytes];
        readBuffer.get(data);

        this.peekPosition += expectedBytes;
        // 恢复position
        readBuffer.position(originalPosition);
        rawBuffer.put(data);
        return true;
    }

    private void handleDataTransfer(SocketChannel channel) {
        // 数据转发已经在handleConnectionRequest中启动
        try {
            while (channel.isOpen()) {
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void startForwarding(SocketChannel clientChannel, SocketChannel targetChannel) {
        Thread clientToTarget = new Thread(() -> forward(clientChannel, targetChannel));
        Thread targetToClient = new Thread(() -> forward(targetChannel, clientChannel));

        clientToTarget.start();
        targetToClient.start();
    }

    private void forward(SocketChannel source, SocketChannel destination) {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        try {
            while (source.isOpen() && destination.isOpen()) {
                buffer.clear();
                int read = source.read(buffer);
                if (read == -1) {
                    break;
                }
                buffer.flip();
                writeFully(destination, buffer);
            }
        } catch (IOException e) {
            // 连接已断开，关闭通道
            closeQuietly(source);
            closeQuietly(destination);
        }
    }

    private void sendResponse(SocketChannel channel, byte status) throws IOException {
        ByteBuffer response = ByteBuffer.allocate(10);
        response.put(SOCKS_VERSION);
        response.put(status);
        response.put((byte) 0x00); // 保留字节
        response.put(IPV4_ADDRESS);
        response.put(new byte[]{0, 0, 0, 0}); // 绑定地址
        response.putShort((short) 0); // 绑定端口
        response.flip();
        writeFully(channel, response);
    }

    private void readFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) == -1) {
                throw new IOException("连接已关闭");
            }
        }
    }

    private void writeFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private void closeQuietly(SocketChannel channel) {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException e) {
            // 忽略关闭时的异常
        }
    }
}