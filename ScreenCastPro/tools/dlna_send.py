#!/usr/bin/env python3
"""Envía una URL a un televisor DLNA y muestra si la TV la reproduce.

Uso:  python3 tools/dlna_send.py URL [PERFIL] [URL_CONTROL_TV]

Sirve para probar archivos o streams de referencia (p. ej. un .ts de ffmpeg servido desde el PC).
Consulta el estado de reproducción de la TV cada 2 s durante 30 s.
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dlna_probe import DEFAULT_CONTROL, didl, esc, fault, soap  # noqa: E402


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    url = sys.argv[1]
    profile = sys.argv[2] if len(sys.argv) > 2 else "http-get:*:video/mp2ts:*"
    control = sys.argv[3] if len(sys.argv) > 3 else DEFAULT_CONTROL
    print(f"TV: {control}\nURL: {url}\nPerfil: {profile}\n")

    soap(control, "Stop", "<InstanceID>0</InstanceID>")
    st, txt = soap(control, "SetAVTransportURI",
                   f"<InstanceID>0</InstanceID><CurrentURI>{esc(url)}</CurrentURI>"
                   f"<CurrentURIMetaData>{esc(didl(url, profile))}</CurrentURIMetaData>")
    if st != 200:
        print(f"SetAVTransportURI rechazado: {fault(txt)}")
        return
    soap(control, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    for segundo in range(0, 30, 2):
        time.sleep(2)
        st, txt = soap(control, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        m = re.search(r"<CurrentTransportState>([^<]+)", txt)
        estado = m.group(1) if m else f"error {fault(txt)}"
        print(f"t={segundo + 2:>2} s  estado TV: {estado}")
    soap(control, "Stop", "<InstanceID>0</InstanceID>")


if __name__ == "__main__":
    main()
