# pc-server-python/src/discovery.py
import socket
from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

PORT = 8765

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