package com.sixbean.testpike.core.appauto.domain.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Component
public class RemoteAdbConnectivityProbe {

    
    static final Pattern HOST_PORT = Pattern.compile(
            "^(\\d{1,3}(?:\\.\\d{1,3}){3}|[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*):(\\d{1,5})$");

    private static final Logger log = LoggerFactory.getLogger(RemoteAdbConnectivityProbe.class);

    
    public static Integer parsedPort(String address) {
        if (address == null) {
            return null;
        }
        Matcher m = HOST_PORT.matcher(address.trim());
        if (!m.matches()) {
            return null;
        }
        int port = Integer.parseInt(m.group(2));
        return port >= 1 && port <= 65535 ? port : null;
    }

    
    public static boolean isValidHostPort(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        Matcher m = HOST_PORT.matcher(address.trim());
        if (!m.matches()) {
            return false;
        }
        int port = Integer.parseInt(m.group(2));
        return port >= 1 && port <= 65535;
    }

    
    public boolean reachable(String address, int timeoutMs) {
        String trimmed = address == null ? "" : address.trim();
        if (!isValidHostPort(trimmed)) {
            return false;
        }
        int split = trimmed.lastIndexOf(':');
        String host = trimmed.substring(0, split);
        int port = Integer.parseInt(trimmed.substring(split + 1));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Math.max(timeoutMs, 1));
            return true;
        } catch (IOException e) {
            log.debug("ADB 端点探活不可达 {}：{}", trimmed, e.getMessage());
            return false;
        }
    }
}
