package org.example;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.StandardRoot;
import org.apache.tomcat.util.scan.StandardJarScanner;

import java.io.File;

public class Main {
    public static void main(String[] args) throws LifecycleException {
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(9976);
        tomcat.setBaseDir("temp");

        // 指定Web应用程序的根目录
        String webappDirLocation = "src/main/webapp/";
        Context ctx = tomcat.addWebapp("/", new File(webappDirLocation).getAbsolutePath());
        // 关键：设置WebResourceRoot，让Tomcat扫描到你的类路径下的Servlet类
        File classesDir = new File("target/classes");
        WebResourceRoot resources = new StandardRoot(ctx);
        resources.addPreResources(new DirResourceSet(resources, "/WEB-INF/classes",
                classesDir.getAbsolutePath(), "/"));
        ctx.setResources(resources);
        // 配置Jar扫描器以扫描Servlet注解
        StandardJarScanner jarScanner = new StandardJarScanner();
        jarScanner.setScanAllDirectories(true);
        jarScanner.setScanClassPath(true);
        ctx.setJarScanner(jarScanner);

        // 启动Tomcat服务器
        tomcat.start();
        System.out.println("Embedded Tomcat started at http://localhost:9976");

        // 让服务器一直运行
        tomcat.getServer().await();
    }
}