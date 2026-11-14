"""Servicio de control de AgroCraft: endpoints HTTP de la plataforma.

  GET  /iot/                       panel de control (retroceso desde el navegador)
  GET  /iot/api/devices            estado, configuración y última telemetría de cada dispositivo
  POST /iot/api/cmd                envía un comando y espera el ack         (requiere usuario operador)
  GET  /iot/api/rules              reglas en la nube y su estado
  POST /iot/api/rules/<id>         activa o desactiva una regla             (requiere usuario operador)
  POST /iot/api/ingest/<device>    ingesta HTTPS de dispositivos sin MQTT   (token del dispositivo)
  POST /iot/api/alert              webhook de Grafana Alerting              (token de alertas)
  POST /iot/api/report             genera ya el informe con IA            (requiere usuario operador)
  GET  /iot/reportes/              informes diarios del analista con IA (Markdown)
  POST /iot/api/notify/test        mensaje de prueba a Telegram/Discord   (requiere usuario operador)
  GET  /iot/api/notify/chats       chats que escribieron al bot de Telegram (requiere usuario operador)
  GET  /iot/api/health

Caddy publica este servicio bajo https://<host>/iot/ (Grafana ya ocupa /api/).
Habla con los dispositivos solo por MQTT: publica en `cmd` y escucha `ack`, `state`, `status`, `telemetry`.
"""

import asyncio
import base64
import hmac
import html
import json
import logging
import os
import threading
import time
import uuid
from datetime import datetime, timedelta
from pathlib import Path

import paho.mqtt.client as mqtt
from aiohttp import web

from base import MQTT_HOST, MQTT_PORT, now_ms
from http_devices import PhoneProxy
from notifier import Notifier, format_alert
import report
import report_page
from rules import Rules

log = logging.getLogger("control")
STATIC = Path(__file__).resolve().parent / "static"
CONTROL_USER = os.environ.get("CONTROL_USER", "operador")
CONTROL_PASS = os.environ["CONTROL_PASS"]
ALERT_TOKEN = os.environ["ALERT_TOKEN"]
ACK_TIMEOUT_S = 10


class Hub:
    """Vista en memoria de la flota, construida a partir de lo que pasa por el bróker."""

    def __init__(self, loop: asyncio.AbstractEventLoop):
        self.loop = loop
        self.devices: dict[str, dict] = {}
        self.pending: dict[str, asyncio.Future] = {}
        self.listeners = []         # funciones (cls, device, channel, data) llamadas en el loop
        self.lock = threading.Lock()

        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="control", protocol=mqtt.MQTTv311)
        client.username_pw_set("operator", os.environ["MQTT_PASS_OPERATOR"])
        client.reconnect_delay_set(1, 30)
        client.on_connect = self._on_connect
        client.on_message = self._on_message
        self.client = client

    def start(self) -> None:
        self.client.connect_async(MQTT_HOST, MQTT_PORT, keepalive=60)
        self.client.loop_start()

    def _on_connect(self, client, userdata, flags, reason_code, properties):
        if reason_code.is_failure:
            log.error("bróker rechazó la conexión: %s", reason_code)
            return
        for channel in ("telemetry", "state", "status", "ack", "event"):
            client.subscribe(f"agro/+/+/{channel}", qos=1)
        log.info("conectado al bróker")

    def _on_message(self, client, userdata, message):
        parts = message.topic.split("/")
        if len(parts) != 4:
            return
        _, cls, device, channel = parts
        text = message.payload.decode("utf-8", errors="replace")
        try:
            data = json.loads(text) if channel != "status" else text
        except ValueError:
            return
        with self.lock:
            entry = self.devices.setdefault(device, {"device": device, "class": cls, "status": "desconocido",
                                                     "state": {}, "telemetry": {}, "telemetry_ts": None,
                                                     "last_ack": None, "last_event": None})
            if channel == "status":
                entry["status"] = data
            elif channel == "state" and isinstance(data, dict):
                entry["state"] = data
            elif channel == "telemetry" and isinstance(data, dict):
                entry["telemetry"] = {k: v for k, v in data.items() if k not in ("ts", "seq")}
                entry["telemetry_ts"] = data.get("ts")
            elif channel == "ack" and isinstance(data, dict):
                entry["last_ack"] = data
            elif channel == "event" and isinstance(data, dict):
                entry["last_event"] = data
        if channel == "ack" and isinstance(data, dict):
            future = self.pending.get(data.get("cmd_id"))
            if future is not None:
                self.loop.call_soon_threadsafe(lambda: future.done() or future.set_result(data))
        for listener in self.listeners:
            self.loop.call_soon_threadsafe(listener, cls, device, channel, data)

    def snapshot(self) -> list:
        with self.lock:
            return sorted((json.loads(json.dumps(d)) for d in self.devices.values()),
                          key=lambda d: (d["class"], d["device"]))

    def device_class(self, device: str):
        with self.lock:
            entry = self.devices.get(device)
            return entry["class"] if entry else None

    def device_state(self, device: str) -> dict:
        with self.lock:
            entry = self.devices.get(device)
            return dict(entry["state"]) if entry else {}

    async def command(self, cls: str, device: str, action: str, params: dict, source: str) -> dict:
        cmd_id = f"{source}-{uuid.uuid4().hex[:8]}"
        future = self.loop.create_future()
        self.pending[cmd_id] = future
        payload = {"cmd_id": cmd_id, "action": action, "params": params, "source": source}
        self.client.publish(f"agro/{cls}/{device}/cmd", json.dumps(payload), qos=1)
        try:
            return await asyncio.wait_for(future, ACK_TIMEOUT_S)
        except asyncio.TimeoutError:
            return {"cmd_id": cmd_id, "ok": False, "detail": f"sin respuesta en {ACK_TIMEOUT_S} s (¿dispositivo apagado?)"}
        finally:
            self.pending.pop(cmd_id, None)

    def platform_event(self, device: str, data: dict) -> None:
        """Eventos de la propia plataforma (reglas, alertas) en agro/platform/<device>/event."""
        data = {"ts": now_ms(), **data}
        self.client.publish(f"agro/platform/{device}/event", json.dumps(data, ensure_ascii=False), qos=1)


# ----------------------------------------------------------------------------------- HTTP
def authorized(request: web.Request) -> bool:
    header = request.headers.get("Authorization", "")
    if not header.startswith("Basic "):
        return False
    try:
        user, _, password = base64.b64decode(header[6:]).decode().partition(":")
    except ValueError:
        return False
    return hmac.compare_digest(user, CONTROL_USER) and hmac.compare_digest(password, CONTROL_PASS)


def require_operator(handler):
    async def wrapper(request):
        if not authorized(request):
            return web.json_response({"ok": False, "detail": "usuario o clave de operador incorrectos"},
                                     status=401)
        return await handler(request)
    return wrapper


def token_ok(request: web.Request, expected: str) -> bool:
    token = request.query.get("token", "")
    header = request.headers.get("Authorization", "")
    if header.startswith("Bearer "):
        token = header[7:]
    return bool(token) and hmac.compare_digest(token, expected)


async def index(request):
    return web.FileResponse(STATIC / "index.html")


async def devices(request):
    return web.json_response({"ts": now_ms(), "devices": request.app["hub"].snapshot()})


@require_operator
async def command(request):
    hub: Hub = request.app["hub"]
    try:
        body = await request.json()
        device, action = str(body["device"]), str(body["action"])
        params = body.get("params") or {}
        if not isinstance(params, dict):
            raise ValueError("params debe ser un objeto")
    except (ValueError, KeyError, TypeError) as error:
        return web.json_response({"ok": False, "detail": f"petición inválida: {error}"}, status=400)
    cls = body.get("class") or hub.device_class(device)
    if cls is None:
        return web.json_response({"ok": False, "detail": f"dispositivo desconocido: {device}"}, status=404)
    ack = await hub.command(cls, device, action, params, source="panel")
    log.info("panel -> %s %s %s: ok=%s %s", device, action, params, ack.get("ok"), ack.get("detail"))
    return web.json_response(ack, status=200 if ack.get("ok") else 422)


async def list_rules(request):
    return web.json_response(request.app["rules"].describe())


@require_operator
async def toggle_rule(request):
    rules: Rules = request.app["rules"]
    body = await request.json()
    ok = rules.set_enabled(request.match_info["rule"], bool(body.get("enabled")))
    return web.json_response({"ok": ok, "rules": rules.describe()}, status=200 if ok else 404)


async def ingest(request):
    proxies: dict = request.app["proxies"]
    device = request.match_info["device"]
    peer = request.headers.get("X-Forwarded-For", request.remote)
    proxy = proxies.get(device)
    if proxy is None:
        log.warning("ingesta %s desde %s: dispositivo sin ingesta HTTP", device, peer)
        return web.json_response({"ok": False, "detail": "dispositivo sin ingesta HTTP"}, status=404)
    if not token_ok(request, proxy.token):
        log.warning("ingesta %s desde %s: token inválido o ausente", device, peer)
        return web.json_response({"ok": False, "detail": "token inválido"}, status=401)
    try:
        body = await request.json()
    except ValueError:
        log.warning("ingesta %s desde %s: cuerpo no es JSON (%s)", device, peer, request.content_type)
        return web.json_response({"ok": False, "detail": "se esperaba JSON"}, status=400)
    result = proxy.ingest(body)
    names = sorted({str(i.get("name")) for i in body.get("payload", []) if isinstance(i, dict)})         if isinstance(body, dict) and isinstance(body.get("payload"), list) else []
    if names and names != request.app.get("seen_" + device):
        request.app["seen_" + device] = names
        log.info("ingesta %s: sensores que envía la app: %s", device, names)
    if not result.get("ok"):
        log.warning("ingesta %s: %s; sensores recibidos: %s", device, result.get("detail"), names)
    elif result.get("detail") == "publicado":
        log.info("ingesta %s: publicado %s", device, result.get("variables"))
    return web.json_response(result, status=200 if result.get("ok") else 422)


async def alert(request):
    """Webhook de Grafana Alerting. Registra cada alerta y, si trae la anotación `agro_cmd`,
    ejecuta ese comando al dispararse (retroceso iniciado por una alerta)."""
    if not token_ok(request, ALERT_TOKEN):
        return web.json_response({"ok": False}, status=401)
    hub: Hub = request.app["hub"]
    body = await request.json()
    executed = []
    for item in body.get("alerts", []):
        labels, annotations = item.get("labels", {}), item.get("annotations", {})
        status = item.get("status", body.get("status", "?"))
        hub.platform_event("alertas", {
            "type": "alert", "status": status, "alert": labels.get("alertname", "?"),
            "device": labels.get("device", ""), "summary": annotations.get("summary", ""),
            "values": item.get("valueString", "")[:300]})
        spec = annotations.get("agro_cmd")
        if status == "firing" and spec:
            try:
                cmd = json.loads(spec)
                ack = await hub.command(cmd["class"], cmd["device"], cmd["action"], cmd.get("params", {}),
                                        source="alerta")
                executed.append({"command": cmd, "ack": ack})
            except (ValueError, KeyError) as error:
                executed.append({"error": f"agro_cmd inválido: {error}"})
        text = format_alert(item)
        if status == "firing" and spec and executed and executed[-1].get("ack", {}).get("ok"):
            text += f"\n↪ acción automática: {executed[-1]['command'].get('action')} {json.dumps(executed[-1]['command'].get('params', {}), ensure_ascii=False)}"
        asyncio.ensure_future(request.app["notifier"].send(text))
    log.info("alerta recibida: %s (%d comandos ejecutados)", body.get("status"), len(executed))
    return web.json_response({"ok": True, "executed": executed})


@require_operator
async def make_report(request):
    hours = int(request.query.get("hours", "24"))
    result = await report.build_report(max(1, min(hours, 168)))
    await notify_report(request.app, result)
    return web.json_response({"ok": True, **result, "url": f"/iot/reportes/{result['name']}"})


async def notify_report(app, result):
    site = os.environ.get("SITE_HOST", "")
    await app["notifier"].send(f"📊 Informe AgroCraft {result['name']}\n\n{result['summary']}\n\n"
                               f"Completo: https://{site}/iot/reportes/{result['name']}")


async def list_reports(request):
    cards = []
    for name in report.list_reports():
        data_path = report.REPORT_DIR / f"{name}.json"
        model, summary = None, ""
        try:
            data = json.loads(data_path.read_text(encoding="utf-8"))
            model = data.get("modelo")
            summary = (data.get("analisis") or "").split("## 2")[0]
            summary = " ".join(line for line in summary.splitlines() if line and not line.startswith("#"))[:260]
        except (OSError, ValueError):
            pass
        day, hour = name[:10], name[11:13] + ":" + name[13:15]
        cards.append(f'<a class="card" href="/iot/reportes/{name}"><b>{day} · {hour}</b>'
                     f'<span>{html.escape(summary) or "Informe estadístico"}</span>'
                     f'<small>{html.escape(model or "sin IA")}</small></a>')
    return web.Response(content_type="text/html", text=REPORT_LIST.format(cards="".join(cards) or "<p>Aún no hay informes.</p>"))


REPORT_LIST = """<!doctype html><html lang=es><meta charset=utf-8><meta name=viewport content="width=device-width, initial-scale=1">
<title>Informes AgroCraft</title><style>
:root{{--bg:#f5f7fb;--card:#fff;--ink:#1d2433;--muted:#5b6475;--line:#e3e7ef;--accent:#2f6db3}}
@media (prefers-color-scheme:dark){{:root{{--bg:#0f1115;--card:#181b22;--ink:#dbe0e8;--muted:#8a93a3;--line:#2a2f3a;--accent:#6ea2e6}}}}
body{{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 "Segoe UI",system-ui,sans-serif}}
header{{background:linear-gradient(120deg,#244f86,#2f6db3 60%,#3a9a4a);color:#fff;padding:26px 16px}}
header div,main{{max-width:900px;margin:0 auto}} header a{{color:#d9e7ff;font-size:13px;text-decoration:none}}
h1{{margin:6px 0 2px}} main{{padding:20px 16px 50px;display:grid;gap:12px}}
.card{{display:block;background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 18px;color:inherit;text-decoration:none}}
.card:hover{{border-color:var(--accent)}} .card b{{color:var(--accent)}} .card span{{display:block;margin:4px 0;font-size:14px}}
.card small{{color:var(--muted)}}</style>
<header><div><a href="/">Dashboards</a> · <a href="/iot/">Panel de control</a><h1>Informes del analista con IA</h1>
<p style="margin:0;opacity:.9">Uno por día a las 07:00 (hora Colombia), con gráficas, indicadores y análisis.</p></div></header>
<main>{cards}</main></html>"""


async def show_report(request):
    name = request.match_info["name"]
    raw = name.endswith(".md")
    name = name[:-3] if raw else name
    if name not in report.list_reports():
        raise web.HTTPNotFound()
    text = (report.REPORT_DIR / f"{name}.md").read_text(encoding="utf-8")
    if raw:
        return web.Response(text=text, content_type="text/markdown", charset="utf-8")
    try:
        data = json.loads((report.REPORT_DIR / f"{name}.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        data = {}
    analysis = data.get("analisis")
    if analysis is None:                     # informes anteriores: el análisis está en el Markdown
        analysis = text.split("## Análisis", 1)[-1].split("\n## Estadísticas", 1)[0]
    page = report_page.render(name, data, analysis, data.get("modelo"))
    return web.Response(text=page, content_type="text/html", charset="utf-8")


@require_operator
async def notify_test(request):
    notifier = request.app["notifier"]
    if not notifier.channels:
        return web.json_response({"ok": False, "detail": "no hay canales configurados en platform/.env"}, status=422)
    result = await notifier.send("✅ AgroCraft: prueba de notificaciones desde https://"
                                 + os.environ.get("SITE_HOST", "") + "/iot/")
    return web.json_response({"ok": all(v == "ok" for v in result.values()), "result": result})


@require_operator
async def notify_chats(request):
    return web.json_response({"chats": await request.app["notifier"].telegram_chats()})


async def daily_reports(app):
    """Informe diario a las 07:00 hora Colombia."""
    while True:
        now = datetime.now(report.TZ)
        target = now.replace(hour=7, minute=0, second=0, microsecond=0)
        if target <= now:
            target += timedelta(days=1)
        await asyncio.sleep((target - now).total_seconds())
        try:
            result = await report.build_report(24)
            await notify_report(app, result)
            log.info("informe diario %s generado", result["name"])
        except Exception as error:          # el informe nunca debe tumbar el servicio
            log.exception("informe diario fallido: %r", error)


async def health(request):
    hub: Hub = request.app["hub"]
    return web.json_response({"ok": hub.client.is_connected(), "devices": len(hub.devices)})


async def on_startup(app):
    loop = asyncio.get_running_loop()
    hub = Hub(loop)
    proxies = {"phone-01": PhoneProxy("phone-01")}
    rules = Rules(hub)
    hub.listeners.append(rules.on_message)
    hub.start()
    for proxy in proxies.values():
        proxy.start()
    app["hub"], app["proxies"], app["rules"] = hub, proxies, rules
    app["notifier"] = Notifier()
    app["daily"] = asyncio.ensure_future(daily_reports(app))


async def on_cleanup(app):
    for proxy in app["proxies"].values():
        proxy.shutdown()
    app["hub"].client.loop_stop()
    app["daily"].cancel()
    if app["notifier"].session is not None:
        await app["notifier"].session.close()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)-10s %(levelname)s %(message)s")
    app = web.Application(client_max_size=4 * 1024 * 1024)
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    app.add_routes([
        web.get("/iot/", index),
        web.get("/iot", lambda r: web.HTTPFound("/iot/")),
        web.get("/iot/api/devices", devices),
        web.post("/iot/api/cmd", command),
        web.get("/iot/api/rules", list_rules),
        web.post("/iot/api/rules/{rule}", toggle_rule),
        web.post("/iot/api/ingest/{device}", ingest),
        web.post("/iot/api/alert", alert),
        web.get("/iot/api/health", health),
        web.post("/iot/api/report", make_report),
        web.get("/iot/reportes", lambda r: web.HTTPFound("/iot/reportes/")),
        web.get("/iot/reportes/", list_reports),
        web.get("/iot/reportes/{name}", show_report),
        web.post("/iot/api/notify/test", notify_test),
        web.get("/iot/api/notify/chats", notify_chats),
        web.static("/iot/static", STATIC),
    ])
    web.run_app(app, host="0.0.0.0", port=8080, access_log=None)


if __name__ == "__main__":
    main()
