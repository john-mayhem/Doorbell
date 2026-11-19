"""Build schematic_page.html from page_template.html + schematic.svg (theme-aware colors)."""
import re
from pathlib import Path

here = Path(__file__).parent
svg = (here / "schematic.svg").read_text(encoding="utf-8")
svg = re.sub(r"<\?xml[^>]*>\s*", "", svg)
svg = re.sub(r"<!DOCTYPE[^>]*>\s*", "", svg)
svg = svg.replace("stroke:black", "stroke:currentColor").replace("fill:black", "fill:currentColor")
svg = svg.replace("fill:white", "fill:var(--panel)")
svg = svg.replace('fill="black"', 'fill="currentColor"')
svg = svg.replace('fill="#b4231c"', 'style="fill:var(--net)"')
# let CSS size it
svg = re.sub(r'(<svg[^>]*?)\swidth="[^"]*"', r"\1", svg, count=1)
svg = re.sub(r'(<svg[^>]*?)\sheight="[^"]*"', r"\1", svg, count=1)
page = (here / "page_template.html").read_text(encoding="utf-8").replace("{{SVG}}", svg)
(here / "schematic_page.html").write_text(page, encoding="utf-8")
print(len(page))
