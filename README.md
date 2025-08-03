# TCP Memory Shell (TCP内存马)

## 项目简介
本项目是一个专注于研究 TCP 内存马实现技术的示例项目。通过 Hook Java 底层的 TCP 连接相关类，实现一个TCP内存马。这种技术通过修改 JVM 运行时的网络连接处理逻辑，使攻击者能够建立隐蔽的网络连接，从而绕过常规的安全检测。本项目仅用于安全研究和学习目的。

## 技术原理
- Hook Java 底层 Socket 实现
- 拦截 TCP 连接建立过程
- 自定义网络数据处理逻辑
- 内存中实现远程控制功能

## 功能特性
- 实现 TCP 连接的动态 Hook
- 支持自定义通信协议
- 无需修改磁盘文件
- 难以被常规安全软件检测
- 支持多种 JDK 版本

## 环境要求
- JDK 8+
- Maven 3.6+
- 支持 Linux/Windows 系统

## 快速开始
1. 克隆项目
```bash
git clone https://github.com/yourusername/memory-shell.git
```

2. 编译项目
```bash
cd memory-shell
mvn clean package
```

## 免责声明
本项目仅用于安全研究和学习目的。请勿在未授权的情况下对任何系统进行测试。使用本项目造成的任何问题，作者概不负责。

