#!/usr/bin/env python3
"""Render the official host logos without recoloring, stretching or cropping them.

Requires CairoSVG (e.g. python3 -m pip install CairoSVG==2.8.2).
Upstream SVGs, attribution and hashes live in artwork/host-icons/.
"""
from hashlib import sha256
import json
from pathlib import Path
import xml.etree.ElementTree as ET

import cairosvg

root = Path(__file__).resolve().parent.parent
sources = root / "artwork/host-icons"
manifest = json.loads((sources / "sources.json").read_text())
for icon in manifest["icons"]:
    data = (sources / icon["file"]).read_bytes()
    if sha256(data).hexdigest() != icon["sha256"]:
        raise ValueError(f"Upstream SVG hash mismatch: {icon['file']}")
    # Debian's SVG uses internal namespace entities from Illustrator. ElementTree
    # resolves them without loading external DTDs; the serialized SVG is DTD-free.
    svg = ET.tostring(ET.fromstring(data))
    output = root / f"app/src/main/res/drawable-nodpi/ic_os_{icon['name']}.png"
    cairosvg.svg2png(bytestring=svg, write_to=str(output), output_width=192, output_height=192)
    print(output.relative_to(root))
