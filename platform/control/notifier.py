"""Notificaciones a personas: Telegram y Discord.

Se activan solo si las variables están en platform/.env:
  TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID     bot creado con @BotFather; chat donde escribe
  DISCORD_WEBHOOK_URL                      Discord: Ajustes del canal -> Integraciones -> Webhooks
Sin ellas, send() no hace nada (y lo dice en el registro una sola vez).
"""

import logging
import os

import aiohttp

log = logging.getLogger("notificador")

TELEGRAM_LIMIT = 4000
DISCORD_LIMIT = 1900


class Notifier:
    def __init__(self):
        self.telegram_token = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()
        self.telegram_chat = os.environ.get("TELEGRAM_CHAT_ID", "").strip()
        self.discord_url = os.environ.get("DISCORD_WEBHOOK_URL", "").strip()
        self.session = None
        channels = [name for name, on in (("Telegram", self.telegram_token and self.telegram_chat),
                                          ("Discord", self.discord_url)) if on]
        log.info("canales de notificación: %s", ", ".join(channels) or "ninguno configurado")

    @property
    def channels(self) -> list:
        out = []
        if self.telegram_token and self.telegram_chat:
            out.append("telegram")
        if self.discord_url:
            out.append("discord")
        return out

    async def _http(self) -> aiohttp.ClientSession:
        if self.session is None or self.session.closed:
            self.session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=20))
        return self.session

    async def send(self, text: str) -> dict:
        """Envía el texto a todos los canales configurados. Devuelve {canal: ok|error}."""
        result = {}
        http = await self._http()
        if self.telegram_token and self.telegram_chat:
            try:
                for start in range(0, len(text), TELEGRAM_LIMIT):
                    async with http.post(f"https://api.telegram.org/bot{self.telegram_token}/sendMessage",
                                         json={"chat_id": self.telegram_chat, "text": text[start:start + TELEGRAM_LIMIT],
                                               "disable_web_page_preview": True}) as response:
                        body = await response.json()
                        if not body.get("ok"):
                            raise RuntimeError(body.get("description", response.status))
                result["telegram"] = "ok"
            except (aiohttp.ClientError, RuntimeError, TimeoutError) as error:
                result["telegram"] = f"error: {error}"
                log.warning("Telegram: %s", error)
        if self.discord_url:
            try:
                for start in range(0, len(text), DISCORD_LIMIT):
                    async with http.post(self.discord_url, json={"content": text[start:start + DISCORD_LIMIT],
                                                                 "username": "AgroCraft"}) as response:
                        if response.status >= 300:
                            raise RuntimeError(f"HTTP {response.status}: {(await response.text())[:200]}")
                result["discord"] = "ok"
            except (aiohttp.ClientError, RuntimeError, TimeoutError) as error:
                result["discord"] = f"error: {error}"
                log.warning("Discord: %s", error)
        return result

    async def telegram_chats(self) -> list:
        """Chats que le han escrito al bot (para descubrir TELEGRAM_CHAT_ID)."""
        if not self.telegram_token:
            return []
        http = await self._http()
        async with http.get(f"https://api.telegram.org/bot{self.telegram_token}/getUpdates") as response:
            body = await response.json()
        chats = {}
        for update in body.get("result", []):
            message = update.get("message") or update.get("channel_post") or update.get("my_chat_member") or {}
            chat = message.get("chat") or {}
            if chat.get("id") is not None:
                chats[chat["id"]] = chat.get("title") or chat.get("username") or chat.get("first_name") or "?"
        return [{"chat_id": key, "nombre": value} for key, value in chats.items()]


def format_alert(item: dict) -> str:
    labels, annotations = item.get("labels", {}), item.get("annotations", {})
    firing = item.get("status") == "firing"
    head = "🔴 ALERTA" if firing else "✅ Resuelta"
    text = f"{head}: {labels.get('alertname', '?')}"
    if labels.get("device"):
        text += f" · {labels['device']}"
    if annotations.get("summary") and firing:
        text += f"\n{annotations['summary']}"
    return text
