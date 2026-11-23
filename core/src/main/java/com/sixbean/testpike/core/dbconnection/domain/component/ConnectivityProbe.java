package com.sixbean.testpike.core.dbconnection.domain.component;

import com.sixbean.testpike.core.dbconnection.api.dto.ConnectivityTestInfo;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;


@Component
public class ConnectivityProbe {

    private static final int LOGIN_TIMEOUT_SECONDS = 5;

    
    public ConnectivityTestInfo probe(String driverClass, String jdbcUrl, String username, String password) {
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            String label = driverClass.startsWith("com.mysql") ? "mysql" : driverClass;
            return new ConnectivityTestInfo(false,
                    "未装 " + label + " 驱动（R4 首版仅平台侧 PG 真连；MySQL 执行链路走执行器），连通测试拒绝");
        }
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement st = conn.createStatement()) {
            st.setQueryTimeout(LOGIN_TIMEOUT_SECONDS);
            st.execute("SELECT 1");
            return new ConnectivityTestInfo(true, "连通正常");
        } catch (Exception e) {
            return new ConnectivityTestInfo(false, "连接失败：" + brief(e));
        }
    }

    
    private static String brief(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        msg = msg.split("\n", 2)[0].trim();
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }
}
