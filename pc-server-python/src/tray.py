# pc-server-python/src/tray.py

import sys
import socket
from pathlib import Path
from PIL import Image, ImageDraw
import pystray
from src.config import load_config, remove_device_token, clear_all_pairings, IS_WINDOWS

if IS_WINDOWS:
    import winreg

REG_RUN_PATH = r"Software\Microsoft\Windows\CurrentVersion\Run"
REG_APP_NAME = "MumiopadServer"

class TrayIconManager:
    def __init__(self, on_exit_callback, on_unpair_callback=None):
        self.tray_icon = None
        self.on_exit = on_exit_callback
        self.on_unpair = on_unpair_callback

    def is_startup_enabled(self) -> bool:
        if not IS_WINDOWS:
            return False
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, REG_RUN_PATH, 0, winreg.KEY_READ) as key:
                winreg.QueryValueEx(key, REG_APP_NAME)
                return True
        except Exception:
            return False

    def toggle_startup_setting(self):
        if not IS_WINDOWS:
            return
        already_enabled = self.is_startup_enabled()
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, REG_RUN_PATH, 0, winreg.KEY_WRITE) as key:
                if already_enabled:
                    winreg.DeleteValue(key, REG_APP_NAME)
                else:
                    script_path = str(Path(__file__).resolve().parent.parent / "main.py")
                    pythonw_exe = sys.executable.replace("python.exe", "pythonw.exe")
                    reg_cmd = f'"{pythonw_exe}" "{script_path}"'
                    winreg.SetValueEx(key, REG_APP_NAME, 0, winreg.REG_SZ, reg_cmd)
            self.update_menu()
        except Exception:
            pass

    def create_icon_image(self):
        image = Image.new('RGB', (64, 64), color=(30, 30, 30))
        d = ImageDraw.Draw(image)
        d.rectangle([(8, 8), (56, 56)], outline=(30, 144, 255), width=4)
        d.ellipse([(26, 26), (38, 38)], fill=(0, 191, 255))
        return image

    def remove_device_and_refresh(self, token: str):
        remove_device_token(token)
        if self.on_unpair:
            self.on_unpair(token)
        self.update_menu()

    def clear_all_and_refresh(self):
        config = load_config()
        for dev in config.get("authorized_tokens", []):
            if self.on_unpair:
                self.on_unpair(dev["token"])
        clear_all_pairings()
        self.update_menu()

    # 【新增】：專屬閉包生成器，將 token 鎖死在獨立的函數範疇中，並用 *args 隔絕系統參數干擾
    def make_remove_action(self, token: str):
        return lambda *args: self.remove_device_and_refresh(token)

    def update_menu(self):
        if not self.tray_icon:
            return

        config = load_config()
        authorized_devices = config.get("authorized_tokens", [])
        pc_name = socket.gethostname()
        
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
                        # 【修正】：改用 `make_remove_action` 生成點擊事件，解決參數覆蓋 Bug
                        action=self.make_remove_action(token)
                    )
                )
            
            device_submenu_items.append(pystray.Menu.SEPARATOR)
            device_submenu_items.append(
                pystray.MenuItem(
                    "清除所有信任裝置", 
                    action=lambda *args: self.clear_all_and_refresh()
                )
            )

        menu_items = [
            pystray.MenuItem(f"電腦名稱: {pc_name}", action=None, enabled=False),
            pystray.MenuItem("已信任裝置", pystray.Menu(*device_submenu_items)),
            pystray.Menu.SEPARATOR
        ]
        
        if IS_WINDOWS:
            menu_items.append(
                pystray.MenuItem(
                    "開機自動啟動", 
                    action=lambda *args: self.toggle_startup_setting(),
                    checked=lambda *args: self.is_startup_enabled()
                )
            )
            menu_items.append(pystray.Menu.SEPARATOR)
            
        menu_items.append(pystray.MenuItem("結束程式", lambda *args: self.on_exit()))
        self.tray_icon.menu = pystray.Menu(*menu_items)

    def run(self):
        icon_img = self.create_icon_image()
        self.tray_icon = pystray.Icon("Mumiopad", icon_img, "Mumiopad Server")
        self.update_menu()
        self.tray_icon.run()

    def stop(self):
        if self.tray_icon:
            self.tray_icon.stop()