package com.sixbean.testpike.core.datafactory.domain.service;

import com.sixbean.testpike.common.genkit.GenFunction;
import com.sixbean.testpike.core.datafactory.api.dto.GenFunctionGroupInfo;
import com.sixbean.testpike.core.datafactory.api.dto.GenFunctionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.sixbean.testpike.core.CoreApplication;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class GenkitCatalogServiceTest {

    @Autowired
    private DataFactoryService dataFactoryService;

    @Test
    void 目录覆盖注册表全集且字段完整() {
        List<GenFunctionGroupInfo> catalog = dataFactoryService.genkitCatalog();
        assertFalse(catalog.isEmpty(), "目录不得为空");

        List<String> catalogNames = catalog.stream()
                .flatMap(g -> g.functions().stream())
                .map(GenFunctionInfo::name)
                .toList();
        assertEquals(GenFunction.allNames().size(), catalogNames.size(),
                "目录函数数须与注册表一致（禁散点维护清单）");
        assertTrue(catalogNames.containsAll(GenFunction.allNames()),
                "注册表函数须全部出现在目录: 缺 " + GenFunction.allNames().stream()
                        .filter(n -> !catalogNames.contains(n)).toList());

        for (GenFunctionGroupInfo group : catalog) {
            assertFalse(group.group().isBlank(), "分组名非空");
            for (GenFunctionInfo fn : group.functions()) {
                assertFalse(fn.name().isBlank(), "函数名非空");
                assertFalse(fn.description().isBlank(), "描述人话非空: " + fn.name());
                assertFalse(fn.example().isBlank(), "示例值非空: " + fn.name());
                assertEquals(group.group(), GenFunction.byName(fn.name()).group(),
                        "分组归属须与注册表面值一致: " + fn.name());
            }
        }
    }

    @Test
    void 分组保注册序且参数说明逐函齐() {
        List<GenFunctionGroupInfo> catalog = dataFactoryService.genkitCatalog();
        assertEquals("idCard", catalog.get(0).functions().get(0).name(),
                "目录顺序=注册序（前端下拉稳定预期）");
        for (GenFunction f : GenFunction.values()) {
            GenFunctionInfo info = catalog.stream().flatMap(g -> g.functions().stream())
                    .filter(x -> x.name().equals(f.functionName())).findFirst().orElseThrow();
            assertEquals(f.paramSchema(), info.params(),
                    "参数说明须与注册表逐项一致: " + f.functionName());
        }
    }
}
