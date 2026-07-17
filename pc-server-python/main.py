# pc-server-python/main.py
import asyncio
import os
import sys
import threading
from src.config import load_config
from src.discovery import NetworkDiscovery
from src.connection import ConnectionManager
from src.tray import TrayIconManager

loop = None
tray_manager = None

async def shutdown_cleanly():
    global loop
    tasks = [t for t in asyncio.all_tasks() if t is not asyncio.current_task()]
    for task in tasks:
        task.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)
    if loop:
        loop.stop()
    os._exit(0)

def on_exit_clicked():
    global loop, tray_manager
    print("正在關閉伺服器...")
    if tray_manager:
        tray_manager.stop()
    if loop:
        asyncio.run_coroutine_threadsafe(shutdown_cleanly(), loop)

def run_tray():
    global tray_manager
    tray_manager = TrayIconManager(on_exit_callback=on_exit_clicked)
    tray_manager.run()

async def main():
    global loop
    loop = asyncio.get_running_loop()
    
    config = load_config()
    server_uuid = config["server_uuid"]

    # 註冊 mDNS
    discovery = NetworkDiscovery(server_uuid)
    await discovery.register()
    print("mDNS 區域網路探索廣播已啟動")

    # 建立連線管理器並註冊托盤選單更新回呼
    connection_manager = ConnectionManager(
        server_uuid=server_uuid,
        on_update_menu_callback=lambda: tray_manager.update_menu() if tray_manager else None
    )

    # 在獨立背景執行緒運行系統托盤介面
    tray_thread = threading.Thread(target=run_tray, daemon=True)
    tray_thread.start()

    # 啟動 WebSocket 服務
    try:
        async with connection_manager.start_server():
            await asyncio.Future()
    except asyncio.CancelledError:
        pass
    finally:
        await discovery.unregister()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)