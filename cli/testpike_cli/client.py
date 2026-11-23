
from __future__ import annotations

import uuid
from typing import Any

import httpx


class CiError(Exception):
    pass


class CiClient:
    pass

    def __init__(self, base_url: str, token: str,
                 transport: httpx.BaseTransport | None = None,
                 timeout: float = 30.0) -> None:
        self._base = base_url.rstrip("/")
        headers = {"Authorization": f"Bearer {token}"}
        kwargs: dict[str, Any] = {"headers": headers, "timeout": timeout}
        if transport is not None:
            kwargs["transport"] = transport
        self._client = httpx.Client(**kwargs)

    def run_batch(self, payload: dict, idempotency_key: str | None = None) -> dict:
        pass
        key = idempotency_key or uuid.uuid4().hex
        return self._request("POST", "/open/ci/runs",
                             json=payload, headers={"Idempotency-Key": key})

    def get_status(self, batch_id: int) -> dict:
        return self._request("GET", f"/open/ci/runs/{batch_id}")

    def get_report(self, batch_id: int) -> dict:
        return self._request("GET", f"/open/ci/runs/{batch_id}/report")

    def _request(self, method: str, path: str, **kwargs: Any) -> dict:
        url = self._base + path
        try:
            resp = self._client.request(method, url, **kwargs)
        except httpx.HTTPError as e:
            raise CiError(f"无法访问 testPike 服务 {url}（{e.__class__.__name__}）："
                          "请检查 --base-url 与网络连通性") from e
        return self._unwrap(resp, url)

    @staticmethod
    def _unwrap(resp: httpx.Response, url: str) -> dict:
        try:
            body = resp.json()
        except ValueError:
            raise CiError(f"{url} 返回非 JSON 响应（HTTP {resp.status_code}）："
                          "请确认 base-url 指向 api 服务（默认 8082）")
        code = str(body.get("code", ""))
        if resp.status_code >= 400 or code != "0":
            msg = body.get("msg") or f"HTTP {resp.status_code}"
            raise CiError(f"{msg}（接口 {url}）")
        data = body.get("data")
        return data if isinstance(data, dict) else {}


__all__ = ["CiClient", "CiError"]
