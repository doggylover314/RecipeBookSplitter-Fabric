#!/usr/bin/env python3
"""Patches a freshly generated velocity.toml for the E2E kit: loopback bind, offline mode, modern forwarding, one
backend server called "lobby", no forced hosts. Usage: patch_velocity.py <velocity.toml> <proxy-port> <backend-port>"""
import re
import sys


def replace_line(text, key, value):
    text, n = re.subn(rf"(?m)^{re.escape(key)}\s*=.*$", f"{key} = {value}", text, count=1)
    if n != 1:
        sys.exit(f"patch_velocity: key '{key}' not found in velocity.toml; has the config format changed?")
    return text


def replace_table(text, table, body):
    text, n = re.subn(rf"(?ms)^\[{re.escape(table)}\]\n.*?(?=^\[|\Z)", f"[{table}]\n{body}\n\n", text, count=1)
    if n != 1:
        sys.exit(f"patch_velocity: table [{table}] not found in velocity.toml; has the config format changed?")
    return text


def main():
    path, proxy_port, backend_port = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    with open(path) as f:
        text = f.read()
    text = replace_line(text, "bind", f'"127.0.0.1:{proxy_port}"')
    text = replace_line(text, "online-mode", "false")
    text = replace_line(text, "player-info-forwarding-mode", '"MODERN"')
    text = replace_table(text, "servers", f'lobby = "127.0.0.1:{backend_port}"\ntry = ["lobby"]')
    text = replace_table(text, "forced-hosts", "")
    with open(path, "w") as f:
        f.write(text)


if __name__ == "__main__":
    main()
