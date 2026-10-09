"""Real Java HTTP tests, after projects/enterprise/build.ps1."""
import os
import socket
import subprocess
import time
from pathlib import Path
import httpx
import pytest

@pytest.fixture(scope="module")
def service(tmp_path_factory):
    root = Path(__file__).resolve().parents[1]
    classes = root / "target/classes"
    if not (classes / "Operations.class").exists():
        pytest.fail("Build Java service with projects/enterprise/build.ps1 before running HTTP tests.")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    tokens = {r: f"test-{r}-token-with-32-characters" for r in ["admin", "operator", "viewer"]}
    env = {**os.environ, "OPERATIONS_PORT": str(port), "OPERATIONS_DB": f"jdbc:h2:{tmp_path_factory.mktemp('sql') / 'operations'}", **{f"OPERATIONS_{r.upper()}_TOKEN": t for r,t in tokens.items()}}
    proc = subprocess.Popen(["java", "-cp", f"{classes}{os.pathsep}{root / 'target/lib/*'}", "Operations"], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    client = httpx.Client(base_url=f"http://127.0.0.1:{port}", timeout=5)
    try:
        for _ in range(100):
            if proc.poll() is not None:
                pytest.fail(proc.communicate()[1].decode())
            try:
                if client.get("/status").status_code == 200:
                    break
            except httpx.ConnectError:
                pass
            time.sleep(.1)
        else:
            pytest.fail("Java service failed to start")
        yield client, tokens
    finally:
        client.close()
        proc.terminate()
        proc.communicate(timeout=10)

def test_http_rbac_and_transactions(service):
    client,tokens=service
    auth=lambda role: {"Authorization": f"Bearer {tokens[role]}"}
    assert client.get("/inventory").status_code==401
    item={"sku":"TEST","name":"Test item","quantity":10,"price_cents":199}
    assert client.post("/inventory",headers=auth("viewer"),json=item).status_code==403
    assert client.post("/inventory",headers=auth("operator"),json=item).status_code==403
    assert client.post("/inventory",headers=auth("admin"),json=item).status_code==201
    sale={"sku":"TEST","quantity":2,"idempotency_key":"http-sale"}
    assert client.post("/transactions",headers=auth("viewer"),json=sale).status_code==403
    result=client.post("/transactions",headers=auth("operator"),json=sale)
    assert result.status_code==201 and result.json()["total_cents"]==398
    assert client.post("/transactions",headers=auth("operator"),json=sale).json()["replayed"] is True
    assert client.get("/inventory",headers=auth("viewer")).json()[0]["quantity"]==8
    assert client.post("/transactions",headers=auth("operator"),json={**sale,"quantity":3}).status_code==422
    assert client.post("/transactions",headers=auth("operator"),json={**sale,"quantity":50,"idempotency_key":"oversell"}).status_code==422
    assert len(client.get("/transactions",headers=auth("viewer")).json())==1
    assert client.get("/audit",headers=auth("admin")).status_code==200
    assert client.get("/audit",headers=auth("viewer")).status_code==403
    assert client.post("/inventory",headers=auth("admin"),content="{invalid").status_code==422
    assert client.post("/inventory",headers=auth("admin"),json={**item,"sku":"BAD","quantity":1.5}).status_code==422
