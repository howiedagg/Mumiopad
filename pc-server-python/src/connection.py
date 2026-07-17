# pc-server-python/src/connection.py
import asyncio
import json
import socket
import uuid
import ctypes
from websockets.server import serve
from src.config import load_config, add_device_token, remove_device_token, IS_WINDOWS
from src.controller import apply_move, apply_click, apply_scroll, apply_text, apply_keypress, apply_gesture

HOST = "0.0.0.0"
PORT = 8765

class ConnectionManager:
    def __init__(self, server_uuid: str, on_update_menu_callback=None):
        self.server_uuid = server_uuid
        self.active_connections = set()
        self.on_update_menu = on_update_menu_callback

    def show_pairing_dialog_windows(self, device_name: str) -> bool:
        if IS_WINDOWS:
            result = ctypes.windll.user32.MessageBoxW(
                0, 
                f"裝置「{device_name}」請求配對並控制此電腦。\n\n是否允許配對？", 
                "Mumiopad 配對請求", 
                0x00000004 | 0x00000020 | 0x00040000
            )
            return result == 6  # IDYES = 6
        return True

    async def handler(self, websocket):
        authed = False
        current_token = None
        peer = websocket.remote_address
        self.active_connections.add(websocket)
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
                        allowed = await asyncio.to_thread(self.show_pairing_dialog_windows, device_name)
                        
                        if allowed:
                            new_token = str(uuid.uuid4())
                            add_device_token(new_token, device_name)
                            authed = True
                            current_token = new_token
                            
                            await websocket.send(json.dumps({
                                "type": "pair_success", 
                                "token": new_token,
                                "server_uuid": self.server_uuid,
                                "pc_name": socket.gethostname()
                            }))
                            if self.on_update_menu:
                                self.on_update_menu()
                        else:
                            await websocket.send(json.dumps({"type": "pair_fail", "reason": "denied"}))
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
                        if self.on_update_menu:
                            self.on_update_menu()
                    await websocket.close()
                    return

                await self.handle_event(msg)
        except Exception:
            pass
        finally:
            self.active_connections.discard(websocket)

    async def handle_event(self, msg: dict):
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

    def start_server(self):
        return serve(self.handler, HOST, PORT)