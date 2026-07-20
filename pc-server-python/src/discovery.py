# pc-server-python/src/discovery.py

import socket
from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf
from src.config import PORT # 💡 修正：匯入 PORT，避免散落硬編碼

# PORT = 8765 # 👈 修正：刪除重複硬編碼

def get_lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()

class NetworkDiscovery:
    def __init__(self, server_uuid: str):
        self.server_uuid = server_uuid
        self.azc = AsyncZeroconf()
        self.mdns_info = None

    async def register(self):
        ip = get_lan_ip()
        properties = {"pc_name": socket.gethostname()}
        self.mdns_info = ServiceInfo(
            "_mumiopad._tcp.local.",
            f"Mumiopad_{self.server_uuid}._mumiopad._tcp.local.",
            addresses=[socket.inet_aton(ip)],
            port=PORT,
            properties=properties,
        )
        await self.azc.async_register_service(self.mdns_info)

    async def unregister(self):
        if self.mdns_info:
            await self.azc.async_unregister_service(self.mdns_info)
        await self.azc.async_close()