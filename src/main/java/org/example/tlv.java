package org.example;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class tlv {
    // 消息类型定义
    public static final byte TYPE_INFO = 0x01;        // 基础信息请求/响应
    public static final byte TYPE_COMMAND = 0x02;     // 命令执行请求/响应
    public static final byte TYPE_HEARTBEAT = 0x03;   // 存活检测请求/响应
    private static final byte[] MAGIC_PREFIX = new byte[]{
            (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE  // 4字节魔数
    };

    private static final byte[] XOR_KEY = new byte[]{
            (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD  // 自定义异或密钥
    };

    // XOR加密/解密方法
    public static byte[] xorProcess(byte[] data) {
        if (data == null || data.length == 0) {
            return data;
        }
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ XOR_KEY[i % XOR_KEY.length]);
        }
        return result;
    }

    public static void main(String[] args) {
        byte[] encode = new Request((byte) 0x00, null).encode();
        Request decode = Request.decode(encode);
        System.out.println(decode);

        byte[] encode1 = new Response((byte) 0x00, (byte) 0x01, null).encode();
        Response decode1 = Response.decode(encode1);
        System.out.println(decode1);
    }

    public static boolean validate(byte[] data) {
        if (data.length < MAGIC_PREFIX.length) {
            return false;
        }
        for (int i = 0; i < MAGIC_PREFIX.length; i++) {
            if (data[i] != MAGIC_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    // 请求包结构
    public static class Request {
        private byte type;      // 1字节类型
        private int length;     // 4字节长度
        private byte[] data;    // 数据部分

        public Request(byte type, byte[] data) {
            this.type = type;
            this.data = data;
            this.length = data != null ? data.length : 0;
        }

        // 编码请求包（带加密）
        public byte[] encode() {
            // 加密数据
            byte[] encryptedData = xorProcess(data);

            ByteBuffer buffer = ByteBuffer.allocate(MAGIC_PREFIX.length + 5 + length);
            buffer.put(MAGIC_PREFIX);
            buffer.put(type);
            buffer.putInt(length);
            if (encryptedData != null && length > 0) {
                buffer.put(encryptedData);
            }
            return buffer.array();
        }

        // 解码请求包（带解密）
        public static Request decode(byte[] rawData) {
            if (!validate(rawData)) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(rawData);
            buffer.position(MAGIC_PREFIX.length);
            byte type = buffer.get();
            int length = buffer.getInt();

            // 读取并解密数据
            byte[] encryptedData = null;
            if (length > 0) {
                encryptedData = new byte[length];
                buffer.get(encryptedData);
                encryptedData = xorProcess(encryptedData);
            }

            return new Request(type, encryptedData);
        }

        // 获取原始数据
        public byte[] getData() {
            return data;
        }

        // 获取解密后的字符串数据
        public String getDataAsString() {
            return data != null ? new String(data, StandardCharsets.UTF_8) : "";
        }
    }

    public static class Response {
        private byte type;          // 1字节类型
        private int length;         // 4字节长度
        private byte status;        // 1字节状态码
        private byte[] data;        // 数据部分

        public Response(byte type, byte status, byte[] data) {
            this.type = type;
            this.status = status;
            this.data = data;
            this.length = (data != null ? data.length : 0) + 1; // +1是状态码的长度
        }

        // 编码响应包（带加密）
        public byte[] encode() {
            // 加密数据
            byte[] encryptedData = xorProcess(data);

            ByteBuffer buffer = ByteBuffer.allocate(MAGIC_PREFIX.length + 5 + length);
            buffer.put(MAGIC_PREFIX);
            buffer.put(type);
            buffer.putInt(length);
            buffer.put(status);
            if (encryptedData != null && encryptedData.length > 0) {
                buffer.put(encryptedData);
            }
            return buffer.array();
        }

        // 解码响应包（带解密）
        public static Response decode(byte[] rawData) {
            if (!validate(rawData)) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(rawData);
            buffer.position(MAGIC_PREFIX.length);
            byte type = buffer.get();
            int length = buffer.getInt();
            byte status = buffer.get();

            // 读取并解密数据
            byte[] encryptedData = null;
            if (length > 1) {  // length包含status的1字节
                encryptedData = new byte[length - 1];
                buffer.get(encryptedData);
                encryptedData = xorProcess(encryptedData);
            }

            return new Response(type, status, encryptedData);
        }

        // 获取原始数据
        public byte[] getData() {
            return data;
        }

        // 获取解密后的字符串数据
        public String getDataAsString() {
            return data != null ? new String(data, StandardCharsets.UTF_8) : "";
        }
    }

    // 工具方法：创建基础信息请求
    public static Request createInfoRequest() {
        return new Request(TYPE_INFO, new byte[0]);
    }

    // 工具方法：创建命令执行请求
    public static Request createCommandRequest(String cmd) {
        return new Request(TYPE_COMMAND, cmd.getBytes(StandardCharsets.UTF_8));
    }

    // 工具方法：创建心跳请求
    public static Request createHeartbeatRequest() {
        return new Request(TYPE_HEARTBEAT, new byte[0]);
    }

    // 工具方法：创建响应
    public static Response createResponse(byte type, byte status, String data) {
        return new Response(type, status, data.getBytes(StandardCharsets.UTF_8));
    }
}
