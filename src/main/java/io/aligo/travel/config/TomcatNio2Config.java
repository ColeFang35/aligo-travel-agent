package io.aligo.travel.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 强制 Tomcat 使用 NIO2（IOCP）连接器。
 *
 * <p>本机 360 安全卫士的网络监控驱动（360netmon）会拦截 JVM 建立 loopback
 * socket 对，导致 NIO Selector 创建失败（Unable to establish loopback
 * connection），Tomcat 默认 NIO connector 无法启动；NIO2 在 Windows 走
 * IOCP 完成端口，不建 loopback pipe，可正常启动。常规环境可删除本类恢复 NIO。
 */
@Configuration
public class TomcatNio2Config {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> nio2ProtocolCustomizer() {
        return factory -> factory.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol");
    }
}
