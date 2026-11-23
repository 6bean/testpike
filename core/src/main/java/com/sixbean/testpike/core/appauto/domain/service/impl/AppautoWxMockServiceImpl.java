package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoWxMockLlmRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoWxMockPruneResult;
import com.sixbean.testpike.core.appauto.api.dto.AppautoWxMockRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoWxMockView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.component.AppautoWxMockGenKitGate;
import com.sixbean.testpike.core.appauto.domain.service.AppautoWxMockService;
import com.sixbean.testpike.core.appauto.infrastructure.remote.client.AiServiceWxMockClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;


@Service
public class AppautoWxMockServiceImpl implements AppautoWxMockService {

    private final AppautoConfigReader configReader;
    private final AiServiceWxMockClient wxMockClient;
    private final AppautoWxMockGenKitGate genKitGate;

    public AppautoWxMockServiceImpl(AppautoConfigReader configReader,
                                    AiServiceWxMockClient wxMockClient,
                                    AppautoWxMockGenKitGate genKitGate) {
        this.configReader = configReader;
        this.wxMockClient = wxMockClient;
        this.genKitGate = genKitGate;
    }

    @Override
    public AppautoWxMockView generate(AppautoWxMockRequest request) {
        if (!configReader.wxMockEnabled()) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("小程序 mock 智辅",
                    AppautoConfigReader.KEY_WX_MOCK), BizErrorCode.FORBIDDEN);
        }
        if (request == null || request.projectId() == null) {
            throw new ServiceException("小程序 mock 生成缺少项目号（模型路由与成本归属依据）"
                    + "——请从小程序项目的「项目环境」档案页发起生成", BizErrorCode.VALIDATION_ERROR);
        }
        if (request.apiPath() == null || request.apiPath().isBlank()) {
            throw new ServiceException("小程序 mock 生成缺少接口路径（wx.request 的 url）"
                    + "——请先在「项目环境」的 mock 生成表单填写接口路径再生成数据集", BizErrorCode.VALIDATION_ERROR);
        }
        if (request.contract() == null || request.contract().isEmpty()) {
            throw new ServiceException("小程序 mock 生成缺少接口契约样例（无契约不烧模型）"
                    + "——请先在「项目环境」的 mock 生成表单粘贴请求/响应字段定义再生成 mock 数据集",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> draft = wxMockClient.generate(new AppautoWxMockLlmRequest(
                request.projectId(), request.apiPath().trim(), request.method(),
                request.contract(), genKitGate.randomFamilyManifest()));
        List<Map<String, Object>> mocks = requireMockRows(draft);
        AppautoWxMockPruneResult pruned = genKitGate.prune(mocks);
        if (pruned.kept().isEmpty()) {
            throw new ServiceException("模型产出的 " + pruned.pruned() + " 个 mock 场景证件号校验位"
                    + "全部不符（" + String.join("；", pruned.reasons()) + "）——本数据集不可用，"
                    + "请重试，或让契约字段用正确写法 ${gen.idCard(seed=数字)} 占位符，由 genkit 生成真号",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return new AppautoWxMockView(pruned.kept(), pruned.pruned(), pruned.reasons());
    }

    
    private List<Map<String, Object>> requireMockRows(Map<String, Object> draft) {
        Object node = draft == null ? null : draft.get("mocks");
        if (!(node instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException("模型没有产出任何 mock 场景（应答缺少 mocks 数据集或为空）"
                    + "——请到「项目环境」核对接口契约样例完整，或稍后重试", BizErrorCode.AI_FORMAT_ERROR);
        }
        for (Object row : list) {
            if (!(row instanceof Map)) {
                throw new ServiceException("模型应答的 mock 场景不是 JSON 对象（场景形不完整）"
                        + "——本次生成不采用，请到「项目环境」重试或核对契约样例", BizErrorCode.AI_FORMAT_ERROR);
            }
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mocks = (List<Map<String, Object>>) list;
        return mocks;
    }
}
