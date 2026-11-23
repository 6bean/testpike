package com.sixbean.testpike.common.storage;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class StorageKeysTest {

    
    @Test
    void build_规范key_含前缀项目场景月份与uuid() {
        String ym = YearMonth.now(ZoneId.systemDefault()).toString().replace("-", "");
        String key = StorageKeys.build("tp", 12L, "script", "step1.png");
        assertTrue(key.matches("tp/12/script/" + ym + "/[0-9a-f]{8}-step1\\.png"),
                "实际 key=" + key);
    }

    
    @Test
    void build_空前缀_无前置斜杠() {
        String key = StorageKeys.build("", 3L, "recording", "a.webm");
        assertTrue(key.startsWith("3/recording/"), "实际 key=" + key);
    }

    
    @Test
    void build_中文空格文件名_清洗安全后缀() {
        String key = StorageKeys.build("", 1L, "exec-artifact", "首页 截图 (1).png");
        String name = key.substring(key.lastIndexOf('/') + 1);
        assertTrue(name.matches("[0-9a-f]{8}[-_][A-Za-z0-9._-]+\\.png"), "name=" + name);
        assertFalse(name.contains("首页") || name.contains(" ") || name.contains("("),
                "中文/空格/括号必须被清洗: " + name);
    }

    
    @Test
    void build_超长文件名_截断有界保扩展名() {
        String longName = "x".repeat(200) + ".png";
        String key = StorageKeys.build("", 1L, "import-export", longName);
        String name = key.substring(key.lastIndexOf('/') + 1);
        assertTrue(name.length() <= 8 + 1 + 64 + ".png".length(), "名称须有界, 实际=" + name.length());
        assertTrue(name.endsWith(".png"));
    }

    
    @Test
    void build_穿越文件名_中性化不逃逸() {
        String key = StorageKeys.build("tp", 9L, "skill-pack", "../../etc/passwd");
        String name = key.substring(key.lastIndexOf('/') + 1);
        assertFalse(name.contains(".."), "不得残留 ..: " + name);
        assertFalse(name.contains("/"), "不得残留分隔符: " + name);
    }

 
    @Test
    void build_探索留证场景_规范key() {
        String ym = YearMonth.now(ZoneId.systemDefault()).toString().replace("-", "");
        String key = StorageKeys.build("tp", 7L, "explore", "shot.png");
        assertTrue(key.matches("tp/7/explore/" + ym + "/[0-9a-f]{8}-shot\\.png"),
                "实际 key=" + key);
    }

 
    @Test
    void build_未知场景_拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> StorageKeys.build("tp", 1L, "hdfs-stuff", "a.bin"));
    }
}
