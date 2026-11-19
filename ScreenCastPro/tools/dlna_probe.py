#!/usr/bin/env python3
"""Prueba qué formatos reproduce realmente un televisor DLNA.

Uso:  python3 tools/dlna_probe.py http://IP_TELEFONO:8765/TOKEN [URL_CONTROL_TV]

Para cada perfil envía SetAVTransportURI + Play a la TV con la URL del teléfono y mira
el estado de reproducción (GetTransportInfo). Requiere que la app esté transmitiendo.
"""
import re
import sys
import time
import urllib.error
import urllib.request

DEFAULT_CONTROL = "http://10.0.20.121:18400/upnp/control/mingusavtr"
AVT = "urn:schemas-upnp-org:service:AVTransport:1"
PROFILES = [
    "http-get:*:video/mp4:*",
    "http-get:*:video/mp2ts:*",
    "http-get:*:video/m2ts:*",
    "http-get:*:video/mpeg:*",
    "http-get:*:video/mp2t:*",
]


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def soap(control, action, args):
    body = (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
        's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
        f'<u:{action} xmlns:u="{AVT}">{args}</u:{action}></s:Body></s:Envelope>'
    ).encode()
    req = urllib.request.Request(control, data=body, method="POST", headers={
        "Content-Type": 'text/xml; charset="utf-8"',
        "SOAPACTION": f'"{AVT}#{action}"',
    })
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            return r.status, r.read().decode(errors="ignore")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="ignore")
    except Exception as e:  # red, timeout
        return 0, str(e)


def fault(text):
    code = re.search(r"<errorCode>(\d+)</errorCode>", text)
    desc = re.search(r"<errorDescription>([^<]*)</errorDescription>", text)
    if code:
        return f"{code.group(1)} {desc.group(1) if desc else ''}".strip()
    return text[:120]


def didl(url, proto):
    return (
        '<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
        'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
        '<item id="0" parentID="-1" restricted="1"><dc:title>probe</dc:title>'
        '<upnp:class>object.item.videoItem</upnp:class>'
        f'<res protocolInfo="{proto}">{esc(url)}</res></item></DIDL-Lite>'
    )


def phone_clients(base):
    """Número de espectadores que el teléfono ve conectados (0 si el token es incorrecto o no hay flujo)."""
    try:
        with urllib.request.urlopen(base + "/status", timeout=5) as r:
            m = re.search(r'"clients":(\d+)', r.read().decode(errors="ignore"))
            return int(m.group(1)) if m else -1
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code} (¿token incorrecto?)"
    except Exception as e:
        return f"sin respuesta ({e})"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    base = sys.argv[1].rstrip("/")
    control = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_CONTROL
    print(f"TV: {control}\nTeléfono: {base}")
    print("Verificación de token:", phone_clients(base), "\n")
    for proto in PROFILES:
        url = base + ("/live.mp4" if "mp4" in proto else "/live.ts")
        soap(control, "Stop", "<InstanceID>0</InstanceID>")
        st, txt = soap(control, "SetAVTransportURI",
                       f"<InstanceID>0</InstanceID><CurrentURI>{esc(url)}</CurrentURI>"
                       f"<CurrentURIMetaData>{esc(didl(url, proto))}</CurrentURIMetaData>")
        if st != 200:
            print(f"{proto:30} SetAVTransportURI rechazado: {fault(txt)}")
            continue
        soap(control, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
        time.sleep(5)
        st, txt = soap(control, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        m = re.search(r"<CurrentTransportState>([^<]+)", txt)
        estado = m.group(1) if m else f"error {fault(txt)}"
        clientes = phone_clients(base)
        print(f"{proto:30} estado TV: {estado:20} espectadores en el teléfono: {clientes}")
    soap(control, "Stop", "<InstanceID>0</InstanceID>")


if __name__ == "__main__":
    main()
