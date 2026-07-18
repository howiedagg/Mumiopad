# pc-server-python/src/controller.py
import sys
import time
import ctypes
import threading
from pynput.mouse import Controller as MouseController, Button
from pynput.keyboard import Controller as KeyboardController, Key, KeyCode

IS_WINDOWS = sys.platform == "win32"

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010

mouse = MouseController()
keyboard = KeyboardController()

accumulated_scroll_y = 0.0
# 已徹底移除全域變數 accumulated_zoom_delta

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

        # 【修正】：只換掉「該不該歸零」的判斷依據，其他都不動。
        self.last_input_time = time.monotonic()
        self.idle_stop_threshold_s = 0.05

        if IS_WINDOWS:
            try:
                ctypes.windll.winmm.timeBeginPeriod(1)
            except Exception:
                pass
        
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def add_movement(self, dx: float, dy: float):
        with self.lock:
            self.buffer_dx += dx
            self.buffer_dy += dy
            self.last_input_time = time.monotonic()

    def _loop(self):
        sleep_time = 0.008
        while self.running:
            with self.lock:
                dx = self.buffer_dx
                dy = self.buffer_dy
                self.buffer_dx = 0.0
                self.buffer_dy = 0.0
                idle_duration = time.monotonic() - self.last_input_time

            if idle_duration >= self.idle_stop_threshold_s:
                self.vx = 0.0
                self.vy = 0.0
            else:
                self.vx = self.vx * (1.0 - self.stiffness) + dx * self.stiffness
                self.vy = self.vy * (1.0 - self.stiffness) + dy * self.stiffness

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

    def stop(self):
        self.running = False
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

def apply_scroll(dy: float):
    global accumulated_scroll_y
    y_delta = -dy / 55.0
    accumulated_scroll_y += y_delta
    steps = int(accumulated_scroll_y)
    if steps != 0:
        accumulated_scroll_y -= steps
        mouse.scroll(0, steps)

VK_OEM_PLUS = 187   # 鍵盤上的「=+」鍵
VK_OEM_MINUS = 189  # 鍵盤上的「-_」鍵

def apply_zoom(delta: float):
    steps = int(delta)
    if steps == 0:
        return
        
    try:
        # 根據正負號決定放大或縮小
        if steps > 0:
            target_key = KeyCode.from_vk(VK_OEM_PLUS)
            loop_count = steps
        else:
            target_key = KeyCode.from_vk(VK_OEM_MINUS)
            loop_count = abs(steps)
            
        # 模擬按住 Windows 鍵 (Key.cmd)，並點擊對應次數的 + 或 -
        with keyboard.pressed(Key.cmd):
            for _ in range(loop_count):
                keyboard.press(target_key)
                keyboard.release(target_key)
                time.sleep(0.01) # 微小延遲，確保 Windows 系統來得及響應
    except Exception as e:
        print(f"[MAGNIFIER ERROR] 模擬螢幕放大鏡失敗: {e}")

def apply_text(value: str):
    if not IS_WINDOWS:
        keyboard.type(value)
        return

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
    if key_name == "VOLUME_UP":
        keyboard.press(Key.media_volume_up)
        keyboard.release(Key.media_volume_up)
    elif key_name == "VOLUME_DOWN":
        keyboard.press(Key.media_volume_down)
        keyboard.release(Key.media_volume_down)
    elif key_name == "BROWSER_BACK":
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