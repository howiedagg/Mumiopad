# pc-server-python/src/config.py
import sys
import json
import uuid
from pathlib import Path

IS_WINDOWS = sys.platform == "win32"

def get_secure_config_path() -> Path:
    # 統一存放在 main.py 同目錄下
    return Path(__file__).resolve().parent.parent / "server_config.json"

CONFIG_FILE = get_secure_config_path()

def load_config() -> dict:
    if CONFIG_FILE.exists():
        try:
            data = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
            if "server_uuid" in data and "authorized_tokens" in data:
                return data
        except Exception:
            pass
    
    new_config = {
        "server_uuid": str(uuid.uuid4()),
        "authorized_tokens": []
    }
    save_config(new_config)
    return new_config

def save_config(config: dict):
    CONFIG_FILE.write_text(json.dumps(config, indent=2, ensure_ascii=False), encoding="utf-8")

def add_device_token(token: str, device_name: str):
    config = load_config()
    if not any(t["token"] == token for t in config["authorized_tokens"]):
        config["authorized_tokens"].append({"token": token, "name": device_name})
        save_config(config)

def remove_device_token(token: str):
    config = load_config()
    config["authorized_tokens"] = [t for t in config["authorized_tokens"] if t["token"] != token]
    save_config(config)

def clear_all_pairings():
    config = load_config()
    config["authorized_tokens"] = []
    save_config(config)