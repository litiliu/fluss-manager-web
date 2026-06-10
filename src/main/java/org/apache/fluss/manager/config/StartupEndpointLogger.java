package org.apache.fluss.manager.config;

import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupEndpointLogger implements ApplicationListener<WebServerInitializedEvent> {

    private final Environment environment;

    public StartupEndpointLogger(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        String contextPath = environment.getProperty("server.servlet.context-path", "");
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath)) {
            contextPath = "";
        } else if (!contextPath.startsWith("/")) {
            contextPath = "/" + contextPath;
        }
        System.out.printf(
                "%nFluss Manager Web started successfully.%n"
                        + "Local:   http://localhost:%d%s%n"
                        + "Network: http://0.0.0.0:%d%s%n%n",
                port,
                contextPath,
                port,
                contextPath);
    }
}
