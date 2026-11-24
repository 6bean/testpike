package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CodeStepContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode step(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private static String stepOf(String fields) {
        return "{\"action\":\"code.step\"," + fields + "}";
    }


    @Test
    void python合法片段_直通() throws Exception {
        assertNull(CodeStepContract.violation(step(stepOf(
                "\"language\":\"python\",\"code\":\"import json\\nprint(json.dumps({'a': 1}))\""))));
    }

    @Test
    void js语言_人话拒沙箱未开放() throws Exception {
        String v = CodeStepContract.violation(step(stepOf(
                "\"language\":\"js\",\"code\":\"console.log(1)\"")));
        assertNotNull(v, "js 应被拒（评估稿 B8=仅受限 Python）");
        assertTrue(v.contains("未开放"), "人话文案缺失：" + v);
    }

    @Test
    void language缺失_点名必填() throws Exception {
        String v = CodeStepContract.violation(step(stepOf("\"code\":\"print(1)\"")));
        assertNotNull(v);
        assertTrue(v.contains("选择语言"), "字段点名缺失：" + v);
    }

    @Test
    void language词表外_点名非法值() throws Exception {
        String v = CodeStepContract.violation(step(stepOf(
                "\"language\":\"ruby\",\"code\":\"puts 1\"")));
        assertNotNull(v);
        assertTrue(v.contains("ruby"), "非法值点名缺失：" + v);
        assertTrue(v.contains("python"), "允许集文案缺失：" + v);
    }

    @Test
    void code正文为空_拒() throws Exception {
        String v = CodeStepContract.violation(step(stepOf(
                "\"language\":\"python\",\"code\":\"  \"")));
        assertNotNull(v);
        assertTrue(v.contains("代码正文"), "字段点名缺失：" + v);
    }


    @Test
    void 禁网_socket与requests点名() throws Exception {
        String v1 = CodeStepContract.violation(step(stepOf(
                "\"language\":\"python\",\"code\":\"import socket\\ns = socket.socket()\"")));
        assertNotNull(v1, "socket 应命中禁网");
        assertTrue(v1.contains("网络"), "类别文案缺失：" + v1);
        assertTrue(v1.contains("socket"), "命中词点名缺失：" + v1);

        String v2 = CodeStepContract.violation(step(stepOf(
                "\"language\":\"python\",\"code\":\"import requests\\nrequests.get('http://x')\"")));
        assertNotNull(v2, "requests 应命中禁网");
        assertTrue(v2.contains("网络"), "类别文案缺失：" + v2);
    }

    @Test
    void 禁子进程_subprocess与os_system与multiprocessing点名() throws Exception {
        for (String code : new String[]{
                "import subprocess\\nsubprocess.run(['ls'])",
                "import os\\nos.system('ls')",
                "import multiprocessing\\nmultiprocessing.Pool()"}) {
            String v = CodeStepContract.violation(step(stepOf(
                    "\"language\":\"python\",\"code\":\"" + code + "\"")));
            assertNotNull(v, "子进程面应被拒：" + code);
            assertTrue(v.contains("子进程"), "类别文案缺失：" + v);
        }
    }

    @Test
    void 禁文件访问_open与shutil与pathlib点名() throws Exception {
        for (String code : new String[]{
                "f = open('/etc/passwd')",
                "import shutil\\nshutil.rmtree('/tmp/x')",
                "from pathlib import Path\\nPath('a.txt').write_text('x')"}) {
            String v = CodeStepContract.violation(step(stepOf(
                    "\"language\":\"python\",\"code\":\"" + code + "\"")));
            assertNotNull(v, "文件面应被拒：" + code);
            assertTrue(v.contains("文件"), "类别文案缺失：" + v);
        }
    }

    @Test
    void 逃逸面_eval与exec与compile与双下划线import点名() throws Exception {
        for (String code : new String[]{
                "eval('1+1')",
                "exec('import os')",
                "compile('1', '<s>', 'eval')",
                "__import__('os')"}) {
            String v = CodeStepContract.violation(step(stepOf(
                    "\"language\":\"python\",\"code\":\"" + code + "\"")));
            assertNotNull(v, "逃逸面应被拒：" + code);
            assertTrue(v.contains("逃逸"), "类别文案缺失：" + v);
        }
    }

    @Test
    void 纯计算与json处理片段_零误杀直通() throws Exception {
        assertNull(CodeStepContract.violation(step(stepOf(
                "\"language\":\"python\",\"code\":\"import math\\nimport json\\nr = sorted([3,1,2])\\n"
                        + "print(json.dumps(r))\\nprint(math.floor(1.5))\""))));
    }


    @Test
    void timeoutMs超上限_点名预算域() throws Exception {
        String v = CodeStepContract.violation(step(stepOf(
                "\"language\":\"python\",\"code\":\"print(1)\",\"timeoutMs\":60000")));
        assertNotNull(v, "超 30000 应拒");
        assertTrue(v.contains("timeoutMs"), "字段点名缺失：" + v);
        assertEquals(true, v.contains("30000"), "上限数字缺失：" + v);
    }

    @Test
    void timeoutMs非正数_拒() throws Exception {
        String v = CodeStepContract.violation(step(stepOf(
                "\"language\":\"python\",\"code\":\"print(1)\",\"timeoutMs\":0")));
        assertNotNull(v, "0 毫秒应拒");
        assertTrue(v.contains("timeoutMs"), "字段点名缺失：" + v);
    }

    @Test
    void timeoutMs非数值_拒() throws Exception {
        String v = CodeStepContract.violation(step(stepOf(
                "\"language\":\"python\",\"code\":\"print(1)\",\"timeoutMs\":\"abc\"")));
        assertNotNull(v, "字符串 timeoutMs 应拒");
        assertTrue(v.contains("timeoutMs"), "字段点名缺失：" + v);
    }

    @Test
    void timeoutMs合法域_直通() throws Exception {
        JsonNode s = step(stepOf(
                "\"language\":\"python\",\"code\":\"print(1)\",\"timeoutMs\":5000"));
        assertNull(CodeStepContract.violation(s));
    }
}
