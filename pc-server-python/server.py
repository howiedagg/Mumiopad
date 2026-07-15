"""
VR Touchpad - PC 端接收伺服器（輕量重構版）
"""

import asyncio
import json
import socket
import sys
import uuid
from pathlib import Path
import threading
import time

import ctypes
import websockets
from pynput.mouse import Controller as MouseController, Button
from pynput.keyboard import Controller as KeyboardController, Key
from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

import pystray
from PIL import Image, ImageDraw

if sys.platform == "win32":
    import winreg

HOST = "0.0.0.0"
PORT = 8765
import os

# 系統標準儲存路徑（同 LINE、Chrome 等主流軟體做法）
# 保持使用者執行目錄乾淨，同時能永久記住配對
def get_secure_config_path() -> Path:
    if sys.platform == "win32":
        # 儲存在 Windows 內建的應用程式設定資料夾中
        appdata = os.environ.get("APPDATA")
        if appdata:
            base_dir = Path(appdata) / "Mumiopad"
        else:
            base_dir = Path.home() / ".mumiopad"
    else:
        base_dir = Path.home() / ".config" / "mumiopad"
        
    # 自動建立隱藏資料夾
    base_dir.mkdir(parents=True, exist_ok=True)
    return base_dir / "server_config.json"

CONFIG_FILE = get_secure_config_path()

mouse = MouseController()
keyboard = KeyboardController()

IS_WINDOWS = sys.platform == "win32"
REG_RUN_PATH = r"Software\Microsoft\Windows\CurrentVersion\Run"
REG_APP_NAME = "VRTouchpadServer"

# Win32 物理滑鼠事件常數
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010

SPECIAL_KEYS = {
    "ESC": Key.esc,
    "ENTER": Key.enter,
    "BACKSPACE": Key.backspace,
    "TAB": Key.tab,
    "CTRL": Key.ctrl,
    "ALT": Key.alt,
    "WIN": Key.cmd,
    "UP": Key.up,
    "DOWN": Key.down,
    "LEFT": Key.left,
    "RIGHT": Key.right,
    "VOLUME_UP": Key.media_volume_up,
    "VOLUME_DOWN": Key.media_volume_down,
}

loop = None  
tray_icon = None

# --- 核心資料管理：持久化 UUID 與授權設備 Token ---
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
    print("已清除所有配對紀錄")
    update_tray_menu()

# 初始化配置
server_config = load_config()
SERVER_UUID = server_config["server_uuid"]

class ServerState:
    def __init__(self):
        self.active_connections = set()

state = ServerState()

def get_lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()

# 開機自啟動
def is_startup_enabled() -> bool:
    if not IS_WINDOWS:
        return False
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, REG_RUN_PATH, 0, winreg.KEY_READ) as key:
            winreg.QueryValueEx(key, REG_APP_NAME)
            return True
    except Exception:
        return False

def toggle_startup_setting():
    if not IS_WINDOWS:
        return
    already_enabled = is_startup_enabled()
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, REG_RUN_PATH, 0, winreg.KEY_WRITE) as key:
            if already_enabled:
                winreg.DeleteValue(key, REG_APP_NAME)
                print("開機自動啟動：已關閉")
            else:
                script_path = str(Path(__file__).resolve())
                pythonw_exe = sys.executable.replace("python.exe", "pythonw.exe")
                reg_cmd = f'"{pythonw_exe}" "{script_path}"'
                winreg.SetValueEx(key, REG_APP_NAME, 0, winreg.REG_SZ, reg_cmd)
                print("開機自動啟動：已開啟")
        update_tray_menu()
    except Exception as e:
        print(f"切換開機啟動失敗: {e}")

# ==========================================
# 平滑游標引擎 (獨立渲染執行緒)
# ==========================================
class SmoothMouseEngine:
    def __init__(self):
        self.lock = threading.Lock()
        self.buffer_dx = 0.0
        self.buffer_dy = 0.0
        self.remainder_x = 0.0
        self.remainder_y = 0.0
        self.vx = 0.0
        self.vy = 0.0
        
        self.stiffness = 0.65 
        self.running = True

        if IS_WINDOWS:
            try:
                ctypes.windll.winmm.timeBeginPeriod(1)
                print("Windows 1ms 高精度計時器已啟用")
            except Exception as e:
                print(f"無法啟用系統高精度計時器: {e}")
        
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def add_movement(self, dx: float, dy: float):
        with self.lock:
            self.buffer_dx += dx
            self.buffer_dy += dy

    def _loop(self):
        sleep_time = 0.008
        while self.running:
            with self.lock:
                dx = self.buffer_dx
                dy = self.buffer_dy
                self.buffer_dx = 0.0
                self.buffer_dy = 0.0
            
            self.vx = self.vx * (1.0 - self.stiffness) + dx * self.stiffness
            self.vy = self.vy * (1.0 - self.stiffness) + dy * self.stiffness

            if abs(self.vx) < 0.05: self.vx = 0.0
            if abs(self.vy) < 0.05: self.vy = 0.0

            if self.vx != 0 or self.vy != 0:
                total_x = self.vx + self.remainder_x
                total_y = self.vy + self.remainder_y
                
                step_x = int(total_x)
                step_y = int(total_y)
                
                self.remainder_x = total_x - step_x
                self.remainder_y = total_y - step_y
                
                if step_x != 0 or step_y != 0:
                    if IS_WINDOWS:
                        ctypes.windll.user32.mouse_event(MOUSEEVENTF_MOVE, step_x, step_y, 0, 0)
                    else:
                        mouse.move(step_x, step_y)
            
            time.sleep(sleep_time)

    def __del__(self):
        if IS_WINDOWS:
            try:
                ctypes.windll.winmm.timeEndPeriod(1)
            except Exception:
                pass

smooth_mouse = SmoothMouseEngine()

def apply_move(dx: float, dy: float):
    smooth_mouse.add_movement(dx, dy)

def apply_click(button: str, action: str):
    if IS_WINDOWS:
        if button == "left":
            down_flag = MOUSEEVENTF_LEFTDOWN
            up_flag = MOUSEEVENTF_LEFTUP
        else:
            down_flag = MOUSEEVENTF_RIGHTDOWN
            up_flag = MOUSEEVENTF_RIGHTUP

        if action == "click":
            ctypes.windll.user32.mouse_event(down_flag, 0, 0, 0, 0)
            ctypes.windll.user32.mouse_event(up_flag, 0, 0, 0, 0)
        elif action == "down":
            ctypes.windll.user32.mouse_event(down_flag, 0, 0, 0, 0)
        elif action == "up":
            ctypes.windll.user32.mouse_event(up_flag, 0, 0, 0, 0)
    else:
        btn = Button.left if button == "left" else Button.right
        if action == "click":
            mouse.click(btn, 1)
        elif action == "down":
            mouse.press(btn)
        elif action == "up":
            mouse.release(btn)

accumulated_scroll_y = 0.0

def apply_scroll(dy: float):
    global accumulated_scroll_y
    y_delta = -dy / 55.0
    accumulated_scroll_y += y_delta
    steps = int(accumulated_scroll_y)
    if steps != 0:
        accumulated_scroll_y -= steps
        mouse.scroll(0, steps)

def apply_text(value: str):
    if not IS_WINDOWS:
        keyboard.type(value)
        return

    import ctypes
    from ctypes import wintypes

    user32 = ctypes.windll.user32
    kernel32 = ctypes.windll.kernel32

    kernel32.GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
    kernel32.GlobalAlloc.restype = wintypes.HGLOBAL
    kernel32.GlobalLock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalLock.restype = ctypes.c_void_p
    kernel32.GlobalUnlock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalUnlock.restype = wintypes.BOOL

    user32.OpenClipboard.argtypes = [wintypes.HWND]
    user32.OpenClipboard.restype = wintypes.BOOL
    user32.CloseClipboard.argtypes = []
    user32.CloseClipboard.restype = wintypes.BOOL
    user32.EmptyClipboard.argtypes = []
    user32.EmptyClipboard.restype = wintypes.BOOL
    user32.GetClipboardData.argtypes = [wintypes.UINT]
    user32.GetClipboardData.restype = wintypes.HANDLE
    user32.SetClipboardData.argtypes = [wintypes.UINT, wintypes.HANDLE]
    user32.SetClipboardData.restype = wintypes.HANDLE
    user32.IsClipboardFormatAvailable.argtypes = [wintypes.UINT]
    user32.IsClipboardFormatAvailable.restype = wintypes.BOOL

    old_text = None
    if user32.OpenClipboard(None):
        try:
            if user32.IsClipboardFormatAvailable(13):
                h_data = user32.GetClipboardData(13)
                if h_data:
                    p_data = kernel32.GlobalLock(h_data)
                    if p_data:
                        old_text = ctypes.wstring_at(p_data)
                        kernel32.GlobalUnlock(h_data)
        except Exception:
            pass
        finally:
            user32.CloseClipboard()

    success = False
    if user32.OpenClipboard(None):
        try:
            user32.EmptyClipboard()
            text_bytes = value.encode('utf-16le') + b'\x00\x00'
            h_global = kernel32.GlobalAlloc(0x0042, len(text_bytes))
            if h_global:
                p_global = kernel32.GlobalLock(h_global)
                if p_global:
                    ctypes.memmove(p_global, text_bytes, len(text_bytes))
                    kernel32.GlobalUnlock(h_global)
                    user32.SetClipboardData(13, h_global)
                    success = True
        except Exception:
            pass
        finally:
            user32.CloseClipboard()

    if not success:
        keyboard.type(value)
        return

    with keyboard.pressed(Key.ctrl):
        keyboard.press('v')
        keyboard.release('v')

    if old_text is not None:
        def restore_task():
            time.sleep(0.12)
            if user32.OpenClipboard(None):
                try:
                    user32.EmptyClipboard()
                    old_bytes = old_text.encode('utf-16le') + b'\x00\x00'
                    h_global = kernel32.GlobalAlloc(0x0042, len(old_bytes))
                    if h_global:
                        p_global = kernel32.GlobalLock(h_global)
                        if p_global:
                            ctypes.memmove(p_global, old_bytes, len(old_bytes))
                            kernel32.GlobalUnlock(h_global)
                            user32.SetClipboardData(13, h_global)
                except Exception:
                    pass
                finally:
                    user32.CloseClipboard()

        threading.Thread(target=restore_task, daemon=True).start()

def apply_keypress(key_name: str):
    if key_name == "BROWSER_BACK":
        with keyboard.pressed(Key.alt):
            keyboard.press(Key.left)
            keyboard.release(Key.left)
    elif key_name == "BROWSER_FORWARD":
        with keyboard.pressed(Key.alt):
            keyboard.press(Key.right)
            keyboard.release(Key.right)
    else:
        key = SPECIAL_KEYS.get(key_name)
        if key:
            keyboard.press(key)
            keyboard.release(key)

def apply_gesture(name: str, direction: str):
    if name == "multitask" and direction == "tap":
        with keyboard.pressed(Key.cmd):
            keyboard.press(Key.tab)
            keyboard.release(Key.tab)
    elif name == "desktop":
        if direction in ["down", "up"]:
            with keyboard.pressed(Key.cmd):
                keyboard.press('d')
                keyboard.release('d')

async def handle_event(msg: dict):
    t = msg.get("type")
    if t == "move":
        apply_move(msg.get("dx", 0), msg.get("dy", 0))
    elif t == "click":
        apply_click(msg.get("button", "left"), msg.get("action", "click"))
    elif t == "scroll":
        apply_scroll(msg.get("dy", 0))
    elif t == "text":
        apply_text(msg.get("value", ""))
    elif t == "keypress":
        apply_keypress(msg.get("key", ""))
    elif t == "gesture":
        apply_gesture(msg.get("name", ""), msg.get("direction", ""))

# --- 彈出式配對詢問對話框（Win32） ---
def show_pairing_dialog_windows(device_name: str) -> bool:
    result = ctypes.windll.user32.MessageBoxW(
        0, 
        f"裝置「{device_name}」請求配對並控制此電腦。\n\n是否允許配對？", 
        "VR Touchpad 配對請求", 
        0x00000004 | 0x00000020 | 0x00040000
    )
    return result == 6  # IDYES = 6

def request_pairing_permission(device_name: str) -> bool:
    if IS_WINDOWS:
        try:
            return show_pairing_dialog_windows(device_name)
        except Exception as e:
            print(f"無法顯示 Windows 配對視窗: {e}")
            return True
    return True

async def handler(websocket):
    authed = False
    current_token = None
    peer = websocket.remote_address
    state.active_connections.add(websocket)
    print(f"新連線要求: {peer}")

    try:
        sock = websocket.transport.get_extra_info('socket')
        if sock is not None:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    except Exception as e:
        print(f"設定 TCP_NODELAY 失敗: {e}")

    try:
        async for raw in websocket:
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                continue

            t = msg.get("type")

            if not authed:
                if t == "pair_request":
                    device_name = msg.get("device_name", f"Device-{peer[0]}")
                    print(f"收到來自「{device_name}」的配對請求，正在等待用戶授權...")
                    
                    allowed = await asyncio.to_thread(request_pairing_permission, device_name)
                    
                    if allowed:
                        new_token = str(uuid.uuid4())
                        add_device_token(new_token, device_name)
                        authed = True
                        current_token = new_token
                        
                        await websocket.send(json.dumps({
                            "type": "pair_success", 
                            "token": new_token,
                            "server_uuid": SERVER_UUID,
                            "pc_name": socket.gethostname()
                        }))
                        print(f"配對成功！設備: '{device_name}' 已註冊")
                        update_tray_menu()
                    else:
                        await websocket.send(json.dumps({"type": "pair_fail", "reason": "denied"}))
                        print(f"已拒絕來自「{device_name}」的配對請求")
                        await websocket.close()
                        return
                
                elif t == "auth":
                    token = msg.get("token")
                    config = load_config()
                    allowed_tokens = [x["token"] for x in config["authorized_tokens"]]
                    if token in allowed_tokens:
                        authed = True
                        current_token = token
                        await websocket.send(json.dumps({"type": "auth_ok"}))
                        print(f"裝置通過憑證驗證，連線已建立")
                    else:
                        await websocket.send(json.dumps({"type": "auth_fail"}))
                        await websocket.close()
                        return
                continue

            if t == "ping":
                await websocket.send(json.dumps({"type": "pong"}))
                continue
            
            if t == "unpair":
                if current_token:
                    remove_device_token(current_token)
                    print(f"裝置要求解除綁定，已將其憑證銷毀")
                    update_tray_menu()
                await websocket.close()
                return

            await handle_event(msg)
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        state.active_connections.discard(websocket)
        print(f"連線關閉: {peer}")

def make_service_info(ip: str) -> ServiceInfo:
    properties = {
        "pc_name": socket.gethostname()
    }
    return ServiceInfo(
        "_vrtouchpad._tcp.local.",
        f"VRTouchpad_{SERVER_UUID}._vrtouchpad._tcp.local.",
        addresses=[socket.inet_aton(ip)],
        port=PORT,
        properties=properties,
    )

def create_icon_image():
    image = Image.new('RGB', (64, 64), color=(30, 30, 30))
    d = ImageDraw.Draw(image)
    d.rectangle([(8, 8), (56, 56)], outline=(30, 144, 255), width=4)
    d.ellipse([(26, 26), (38, 38)], fill=(0, 191, 255))
    return image

# 單點刪除特定裝置並即時更新系統選單
def remove_device_and_refresh(token: str):
    remove_device_token(token)
    print("已手動移除該信任裝置")
    update_tray_menu()

def update_tray_menu():
    global tray_icon
    if not tray_icon:
        return

    config = load_config()
    authorized_devices = config.get("authorized_tokens", [])
    pc_name = socket.gethostname()
    
    # 動態構建「已信任裝置」子選單
    device_submenu_items = []
    if not authorized_devices:
        device_submenu_items.append(pystray.MenuItem("無信任裝置", action=None, enabled=False))
    else:
        for dev in authorized_devices:
            token = dev["token"]
            name = dev["name"]
            device_submenu_items.append(
                pystray.MenuItem(
                    f"解除「{name}」", 
                    action=lambda item, t=token: remove_device_and_refresh(t)
                )
            )
        
        # 【優化調整】：如果有名單，在最下方增加分隔線與「清除所有」選項
        device_submenu_items.append(pystray.Menu.SEPARATOR)
        device_submenu_items.append(
            pystray.MenuItem("清除所有信任裝置", lambda item: clear_all_pairings())
        )

    # 主選單現在變得更加乾淨俐落
    menu_items = [
        pystray.MenuItem(f"電腦名稱: {pc_name}", action=None, enabled=False),
        pystray.MenuItem("已信任裝置", pystray.Menu(*device_submenu_items)),
        pystray.Menu.SEPARATOR
    ]
    
    if IS_WINDOWS:
        menu_items.append(
            pystray.MenuItem(
                "開機自動啟動", 
                action=lambda item: toggle_startup_setting(),
                checked=lambda item: is_startup_enabled()
            )
        )
        menu_items.append(pystray.Menu.SEPARATOR)
        
    menu_items.append(pystray.MenuItem("結束程式", on_exit_clicked))
    tray_icon.menu = pystray.Menu(*menu_items)

def on_exit_clicked(icon, item):
    global loop
    print("正在關閉伺服器...")
    icon.stop()
    if loop:
        asyncio.run_coroutine_threadsafe(shutdown_cleanly(), loop)

async def shutdown_cleanly():
    global loop
    tasks = [t for t in asyncio.all_tasks() if t is not asyncio.current_task()]
    for task in tasks:
        task.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)
    if loop:
        loop.stop()

def run_tray_icon():
    global tray_icon
    icon_img = create_icon_image()
    tray_icon = pystray.Icon("VRTouchpad", icon_img, "VR Touchpad Server")
    update_tray_menu()
    
    tray_thread = threading.Thread(target=tray_icon.run)
    tray_thread.start()

async def main():
    global loop
    loop = asyncio.get_running_loop()
    ip = get_lan_ip()
    print(f"監聽 IP: {ip}:{PORT}")

    azc = AsyncZeroconf()
    mdns_info = make_service_info(ip)
    await azc.async_register_service(mdns_info)
    print("mDNS 區域網路探索廣播已啟動")

    run_tray_icon()

    try:
        async with websockets.serve(handler, HOST, PORT):
            await asyncio.Future()
    except asyncio.CancelledError:
        pass
    finally:
        await azc.async_unregister_service(mdns_info)
        await azc.async_close()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)