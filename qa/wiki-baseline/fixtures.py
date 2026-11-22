"""Generate only fictional source documents into a new directory."""
from io import BytesIO
from pathlib import Path
import sys

from openpyxl import Workbook
from PIL import Image, ImageDraw
from pptx import Presentation
from pptx.enum.shapes import MSO_CONNECTOR, MSO_SHAPE
from pptx.oxml.xmlchemy import OxmlElement
from pptx.util import Inches


def workbook(path, rows):
    book = Workbook()
    sheet = book.active
    sheet.title = "Mapping"
    sheet.append(["Requirement", "Screen"])
    for row in rows:
        sheet.append(row)
    book.save(path)


def box(shapes, label, x, y):
    node = shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x), Inches(y), Inches(1.8), Inches(.7))
    node.text = label
    return node


def diagram(path, left, right, spare, caption):
    deck = Presentation()
    for connected in (False, True):
        slide = deck.slides.add_slide(deck.slide_layouts[6])
        group = slide.shapes.add_group_shape()
        a = box(group.shapes, left, 1, 1)
        b = box(group.shapes, right, 5, 1)
        box(slide.shapes, spare, 5, 3)
        line = slide.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, Inches(2.8), Inches(1.35),
                                          Inches(5), Inches(1.35))
        line.begin_connect(a, 3)
        if connected:
            line.end_connect(b, 1)
        arrow = OxmlElement("a:tailEnd")
        arrow.set("type", "triangle")
        line._element.spPr.get_or_add_ln().append(arrow)
        if connected:
            picture = Image.new("RGB", (250, 60), "white")
            ImageDraw.Draw(picture).text((15, 20), caption, fill="black")
            stream = BytesIO()
            picture.save(stream, "PNG")
            stream.seek(0)
            shape = slide.shapes.add_picture(stream, Inches(1), Inches(4))
            shape._element.nvPicPr.cNvPr.set("descr", caption)
    deck.save(path)


def main(root):
    root.mkdir(parents=True, exist_ok=False)
    for version, target in [(1, "SC-010"), (2, "SC-012")]:
        workbook(root / f"Atlas-trace-v{version}.xlsx",
                 [("REQ-010", target), ("REQ-011", "SC-011"), ("REQ-012", None)])
    workbook(root / "Atlas-design.xlsx", [("REQ-010", "SC-012"), ("REQ-013", "SC-013")])
    workbook(root / "Birch-design.xlsx", [("REQ-010", "SC-090"), ("REQ-014", None)])
    wide = Workbook()
    sheet = wide.active
    sheet.title = "Mapping"
    for row, requirement, screen in [(1, "Requirement", "Screen"), (2, "REQ-020", "SC-120")]:
        sheet.cell(row, 1, requirement)
        sheet.cell(row, 120, screen)
    wide.save(root / "Birch-wide.xlsx")
    diagram(root / "Atlas-route.pptx", "Intake", "Ledger", "Archive", "AmberBeacon")
    diagram(root / "Birch-route.pptx", "Harbor", "Relay", "Shelf", "VioletMarker")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
