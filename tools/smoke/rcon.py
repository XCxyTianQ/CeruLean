# -*- coding: utf-8 -*-
"""CeruLean RCON 客户端 —— 仅标准库，参数全部外部化。

协议实现参考 AzureBranches 的 exp*-smoke.py（同源 GPLv3 派生，保留署名）；
与上游脚本的区别：**不再硬编码 host/port/password/日志路径**，全部走参数或环境变量。

环境变量：CERULEAN_RCON_HOST / CERULEAN_RCON_PORT / CERULEAN_RCON_PASSWORD
命令行示例：
    python3 rcon.py --command "list" --command "tps"
"""
import argparse
import json
import os
import socket
import struct
import sys
import time

DEFAULT_HOST = os.environ.get("CERULEAN_RCON_HOST", "127.0.0.1")
DEFAULT_PORT = int(os.environ.get("CERULEAN_RCON_PORT", "25575"))
DEFAULT_PASSWORD = os.environ.get("CERULEAN_RCON_PASSWORD", "")


class RconError(RuntimeError):
    pass


class Rcon:
    """一次性连接式 RCON 客户端（与 AzureBranches 脚本行为一致，便于对照）。"""

    def __init__(self, host=DEFAULT_HOST, port=DEFAULT_PORT, password=DEFAULT_PASSWORD,
                 timeout=10.0, quiet=True):
        self.host, self.port, self.password = host, port, password
        self.timeout = timeout
        self.quiet = quiet
        self._sock = None

    # ── 协议底层 ────────────────────────────────────────────
    @staticmethod
    def _recv_packet(sock):
        header = b""
        while len(header) < 4:
            chunk = sock.recv(4 - len(header))
            if not chunk:
                raise RconError("连接被关闭")
            header += chunk
        (length,) = struct.unpack("<i", header)
        data = b""
        while len(data) < length:
            chunk = sock.recv(length - len(data))
            if not chunk:
                raise RconError("包未收完即断开")
            data += chunk
        return data

    @staticmethod
    def _build_packet(packet_id, ptype, payload):
        body = struct.pack("<ii", packet_id, ptype) + payload + b"\x00\x00"
        return struct.pack("<i", len(body)) + body

    # ── 生命周期 ────────────────────────────────────────────
    def connect(self):
        if self._sock:
            return self
        if not self.password:
            raise RconError("RCON 密码为空（用 --password 或 CERULEAN_RCON_PASSWORD 指定）")
        try:
            self._sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        except OSError as exc:
            raise RconError(f"无法连接 RCON {self.host}:{self.port} -> {exc}") from exc
        self._sock.sendall(self._build_packet(1, 3, self.password.encode()))
        self._recv_packet(self._sock)
        return self

    def close(self):
        if self._sock:
            try:
                self._sock.close()
            finally:
                self._sock = None

    def __enter__(self):
        return self.connect()

    def __exit__(self, *exc):
        self.close()

    # ── 命令 ────────────────────────────────────────────────
    def cmd(self, command, wait=0.25):
        """发送一条命令，返回服务器响应的文本（可能为空）。"""
        self.connect()
        self._sock.sendall(self._build_packet(2, 2, command.encode("utf-8")))
        out = b""
        self._sock.settimeout(wait)
        while True:
            try:
                data = self._recv_packet(self._sock)
            except (socket.timeout, RconError):
                break
            payload = data[8:]
            if payload.endswith(b"\x00\x00"):
                payload = payload[:-2]
            out += payload
        text = out.decode("utf-8", "replace")
        if not self.quiet:
            print(f"> {command}")
            if text.strip():
                print(f"< {text.strip()!r}")
        return text


def _main(argv=None):
    ap = argparse.ArgumentParser(description="CeruLean RCON 客户端")
    ap.add_argument("--host", default=DEFAULT_HOST)
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--password", default=DEFAULT_PASSWORD)
    ap.add_argument("--timeout", type=float, default=10.0)
    ap.add_argument("--wait", type=float, default=0.25, help="每条命令等待响应的时间")
    ap.add_argument("--command", "-c", action="append", default=[], help="可重复")
    ap.add_argument("--file", help="从文件读取命令（每行一条，忽略 # 注释）")
    ap.add_argument("--json", action="store_true", help="以 JSON 输出结果")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args(argv)

    commands = list(args.command)
    if args.file:
        with open(args.file, encoding="utf-8") as fh:
            commands += [ln.strip() for ln in fh
                         if ln.strip() and not ln.lstrip().startswith("#")]
    if not commands:
        ap.error("至少给一条 --command 或 --file")

    results = []
    try:
        with Rcon(args.host, args.port, args.password, args.timeout, quiet=not args.verbose) as rc:
            for c in commands:
                results.append({"command": c, "output": rc.cmd(c, args.wait)})
    except RconError as exc:
        print(f"[错误] {exc}", file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps(results, ensure_ascii=False, indent=2))
    else:
        for r in results:
            print(f"> {r['command']}")
            if r["output"].strip():
                print(f"< {r['output'].strip()}")
    return 0


if __name__ == "__main__":
    sys.exit(_main())
