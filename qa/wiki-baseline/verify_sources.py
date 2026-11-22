"""Check frozen answers against native OOXML, independent of POI and fixture generation."""
import json
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile

HERE = Path(__file__).resolve().parent
NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
      "p": "http://schemas.openxmlformats.org/presentationml/2006/main",
      "a": "http://schemas.openxmlformats.org/drawingml/2006/main"}


def read_sources(root):
    cells, decks = {}, {}
    for path in sorted(root.iterdir()):
        with ZipFile(path) as book:
            if path.suffix == ".xlsx":
                xml = ET.fromstring(book.read("xl/worksheets/sheet1.xml"))
                cells[path.name] = {c.get("r"): "".join(c.itertext())
                                    for c in xml.findall(".//s:c", NS)}
                continue
            slides = []
            for name in sorted(n for n in book.namelist() if re.fullmatch(r"ppt/slides/slide\d+\.xml", n)):
                xml = ET.fromstring(book.read(name))
                labels = {sp.find("p:nvSpPr/p:cNvPr", NS).get("id"): sp.findtext(".//a:t", namespaces=NS)
                          for sp in xml.findall(".//p:sp", NS)}
                connectors = xml.findall(".//p:cxnSp", NS)
                if len(connectors) != 1 or xml.find(".//p:grpSp", NS) is None:
                    raise ValueError("Missing connector/group fixture")
                line = connectors[0]
                start = line.find(".//a:stCxn", NS)
                end = line.find(".//a:endCxn", NS)
                arrow = line.find(".//a:tailEnd", NS)
                if start is None or arrow is None or arrow.get("type") != "triangle":
                    raise ValueError("Incorrect attachment or arrowhead")
                edge = (labels[start.get("id")], labels[end.get("id")] if end is not None else None)
                captions = [p.get("descr") for p in xml.findall(".//p:pic/p:nvPicPr/p:cNvPr", NS)]
                slides.append((edge, set(labels.values()), captions))
            decks[path.name] = slides
    return cells, decks


def verify(root):
    cells, decks = read_sources(root)
    # These are frozen source-coordinate observations, not derived from the fixture builder.
    for doc, value in [("Atlas-trace-v1.xlsx", "SC-010"), ("Atlas-trace-v2.xlsx", "SC-012"),
                       ("Atlas-design.xlsx", "SC-012"), ("Birch-design.xlsx", "SC-090")]:
        if (cells[doc]["A2"], cells[doc]["B2"]) != ("REQ-010", value):
            raise ValueError(f"Coordinate mismatch in {doc} A2:B2")
    for doc, names in [("Atlas-route.pptx", ("Intake", "Ledger", "Archive")),
                       ("Birch-route.pptx", ("Harbor", "Relay", "Shelf"))]:
        slides = decks[doc]
        if ([s[0] for s in slides] != [(names[0], None), names[:2]]
                or any(s[1] != set(names) for s in slides)):
            raise ValueError(f"Original slide endpoints differ in {doc}")

    questions = json.loads((HERE / "questions.json").read_text(encoding="utf8"))
    gold = json.loads((HERE / "gold.json").read_text(encoding="utf8"))
    observed = {}
    for q in questions:
        term, task = q["query"], q["task"]
        values = []
        for doc in q["docs"]:
            if not doc.startswith(q["family"] + "-"):
                raise ValueError("Question crosses evaluation families")
            target = "DP" if doc == "Birch-wide.xlsx" else "B"
            values.append({cells[doc][target + key[1:]] for key, v in cells[doc].items()
                           if key.startswith("A") and v == term and cells[doc].get(target + key[1:])})
        if task == "row":
            answers = values[0]
        elif task == "agreement":
            left, right = values
            answers = {("both" if x in left and x in right else "left" if x in left else "right")
                       + "/" + x for x in left | right}
        else:
            answers = set()
            for doc, slides in decks.items():
                for n, (edge, _, captions) in enumerate(slides, 1):
                    matches = tuple(term.split()) == edge if task == "slide" else term in captions
                    if matches:
                        answers.add(f"{doc}@슬라이드 {n}")
        observed[q["id"]] = sorted(answers)
    if len(observed) != len(questions) or observed != gold:
        raise ValueError("Frozen answers differ from native source observations")
    print(f"Verified {len(cells) + len(decks)} source files and {len(observed)} answers")


if __name__ == "__main__":
    verify(Path(sys.argv[1]))
