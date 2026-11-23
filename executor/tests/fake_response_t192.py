


class _FakeReq:
    def __init__(self, timing):
        self.timing = timing


class _FakeResp:
    def __init__(self, timing):
        self.request = _FakeReq(timing)
