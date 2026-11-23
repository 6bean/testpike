
from __future__ import annotations

import pytest

from protocol import ProtocolError
from runner import should_reregister



class TestShouldReregister:
    def test_未注册错误_从未尝试_需要重注册(self):
        err = ProtocolError("业务错误 code=40401 msg=执行器未注册", code="40401")
        assert should_reregister(err, None, min_interval=10.0, now=100.0) is True

    def test_其他业务错误_不需要重注册(self):
        err = ProtocolError("业务错误 code=40902 msg=任务已被领取", code="40902")
        assert should_reregister(err, None, min_interval=10.0, now=100.0) is False

    def test_节流窗口内_不重复重注册(self):
        err = ProtocolError("业务错误 code=40401 msg=执行器未注册", code="40401")
        assert should_reregister(err, last_attempt=95.0, min_interval=10.0, now=100.0) is False

    def test_超过节流窗口_再次重注册(self):
        err = ProtocolError("业务错误 code=40401 msg=执行器未注册", code="40401")
        assert should_reregister(err, last_attempt=80.0, min_interval=10.0, now=100.0) is True

    def test_旧版异常无code属性_保守不重注册(self):
        err = ProtocolError("网络不可达")
        assert should_reregister(err, None, min_interval=10.0, now=100.0) is False
