"""Compare the existing product engine, the development B ranker, and explicit PPT connectors."""
import argparse
import csv
import hashlib
import itertools
import json
import math
import posixpath
import sqlite3
import subprocess
import zipfile
from pathlib import Path
from time import perf_counter_ns
from xml.etree import ElementTree as ET

from development import rank, read_pages, terms


def table(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def edges(root):
    labels = {}
    for shape in root.findall(".//{*}sp"):
        ident = shape.find("./{*}nvSpPr/{*}cNvPr")
        if ident is not None:
            labels[ident.get("id")] = "".join(t.text or "" for t in shape.findall("./{*}txBody//{*}t")).lower()
    pairs = []
    for line in root.findall(".//{*}cxnSp"):
        a = line.find("./{*}nvCxnSpPr/{*}cNvCxnSpPr/{*}stCxn")
        b = line.find("./{*}nvCxnSpPr/{*}cNvCxnSpPr/{*}endCxn")
        if a is not None and b is not None and a.get("id") != b.get("id"):
            left, right = labels.get(a.get("id")), labels.get(b.get("id"))
            if left and right:
                pairs.append((left, right))
    return pairs


def read_graph(documents):
    graph = {}
    for doc in documents:
        with zipfile.ZipFile(doc["local_path"]) as z:
            main = ET.fromstring(z.read("ppt/presentation.xml"))
            rels = ET.fromstring(z.read("ppt/_rels/presentation.xml.rels"))
            targets = {rel.get("Id"): rel.get("Target") for rel in rels if rel.get("Type", "").endswith("/slide")}
            slides = main.findall("./{*}sldIdLst/{*}sldId")
            if len(slides) != int(doc["page_count"]):
                raise ValueError("Slide count changed")
            for num, slide in enumerate(slides, 1):
                target = targets[next(v for k, v in slide.attrib.items() if k.endswith("}id"))]
                part = posixpath.normpath(posixpath.join("ppt", target)).lstrip("/")
                graph[doc["source_id"], num] = edges(ET.fromstring(z.read(part)))
    return graph


def boost(words, pairs):
    value = 0
    for left, right in pairs:
        a = {w for w in words if w in left}
        b = {w for w in words if w in right}
        if a and b and len(a | b) >= 2:
            value = max(value, len(a | b))
    return value


def labels(rows, tasks, documents):
    sizes = {doc["source_id"]: int(doc["page_count"]) for doc in documents}
    gold = {t: set() for t in tasks}
    seen = set()
    for row in rows:
        task, source = row["과제"], row["파일"]
        if (task, source) in seen or row["질문"] != tasks[task] or row["검토상태"] != "완료":
            raise ValueError(f"Invalid judgment {task}/{source}")
        seen.add((task, source))
        if row["적합_장번호_전체"] != "없음":
            for text in row["적합_장번호_전체"].split(","):
                page = int(text)
                if not 1 <= page <= sizes[source]:
                    raise ValueError("Invalid page number")
                gold[task].add((source, page))
    if seen != set(itertools.product(tasks, sizes)):
        raise ValueError("Incomplete judgment set")
    return gold


def graded_labels(rows, tasks, docs):
    """One adjudicated row for every frozen task and page."""
    versions = {d["source_id"]: d["version"] for d in docs}
    expected = {(t, d["source_id"], n) for t in tasks for d in docs
                for n in range(1, int(d["page_count"]) + 1)}
    direct = {t: set() for t in tasks}
    weak = {t: set() for t in tasks}
    unreadable = set()
    for r in rows:
        t, source, page = r["task"], r["source_id"], int(r["page"])
        key = (t, source, page)
        if key not in expected or r["version"] != versions[source]:
            raise ValueError("Unknown, duplicate or outdated judgment")
        expected.remove(key)
        grade = r["suitable"]
        if grade == "yes":
            direct[t].add((source, page))
        elif grade == "weak":
            weak[t].add((source, page))
        elif grade == "unreadable":
            unreadable.add(t)
        elif grade != "no":
            raise ValueError("Unknown grade")
    if expected:
        raise ValueError("Missing page judgments")
    return direct, weak, unreadable



def compare(frozen, out, java, classpath, product_b=False):
    documents = table(frozen / "documents.tsv")
    tasks = {row["task"]: row["query"] for row in table(frozen / "tasks.tsv")}
    sources = {d["source_id"]: d["local_path"] for d in documents}
    def verify():
        for doc in documents:
            if digest(Path(doc["local_path"])) != doc["sha256"]:
                raise ValueError("Frozen source changed: " + doc["source_id"])
    verify()
    out.mkdir(parents=True, exist_ok=False)
    source_file = out / "sources.json"
    source_file.write_text(json.dumps([dict(alias=k, path=v) for k, v in sources.items()]), encoding="utf-8")
    snapshot = {p: digest(frozen / p) for p in ("documents.tsv", "tasks.tsv", "판정표.tsv")}
    start = perf_counter_ns()
    graph = read_graph(documents)
    graph_ms = (perf_counter_ns() - start) / 1e6
    data = out / "data"
    db_path = data / "Index" / ".office-index-v1.sqlite"
    command = [java, "-Xmx2g", "-Dstdout.encoding=UTF-8", "-Dofficemd.dataDir=" + str(data),
               "-cp", classpath, "HoldoutBaseline", str(source_file)]
    if product_b:
        command.append("--product-b")
    predictions = {}
    samples = []
    promoted = {}
    slide_routes = {}
    with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          text=True, encoding="utf-8", bufsize=1) as engine:
        try:
            for line in engine.stdout:
                if line.rstrip() == "READY":
                    break
            else:
                raise RuntimeError("A engine failed during index preparation")
            for run in range(21):
                order = ("ABC", "BCA", "CAB", "CBA", "BAC", "ACB")
                for i, (task, query) in enumerate(tasks.items()):
                    for mode in order[(i + run) % len(order)]:
                        if mode == "A" or product_b:
                            request = {"query": query, "slides": mode != "A"} if product_b else query
                            engine.stdin.write(json.dumps(request) + "\n")
                            engine.stdin.flush()
                            response = json.loads(engine.stdout.readline())
                            results = [(p["alias"], p["number"]) for p in response["pages"]]
                            ms = response["ms"]
                            if mode != "A":
                                slide_routes[task] = response["slideRoute"]
                        else:
                            start = perf_counter_ns()
                            with sqlite3.connect(db_path.resolve().as_uri() + "?mode=ro", uri=True) as db:
                                pages = read_pages(db, sources)
                            db.close()
                            words = terms(query)
                            results = rank(pages, words, True)[:500]
                            ms = (perf_counter_ns() - start) / 1e6
                            if pages.keys() != graph.keys():
                                raise ValueError("Indexed pages differ from frozen slide order")
                        if mode == "C":
                            start = perf_counter_ns()
                            graph_words = terms(query) if product_b else words
                            scores = {key: boost(graph_words, graph[key]) for key in results}
                            results.sort(key=lambda key: -scores[key])
                            ms += (perf_counter_ns() - start) / 1e6
                            promoted[task] = [k for k, value in scores.items() if value]
                        old = predictions.setdefault(task, {}).setdefault(mode, results)
                        if old != results:
                            raise ValueError("Ranking changed between identical queries")
                        samples.append((task, mode, run, ms))
        finally:
            engine.stdin.close()
    if engine.returncode:
        raise RuntimeError("A engine exited with code " + str(engine.returncode))
    verify()
    if any(digest(frozen / p) != h for p, h in snapshot.items()):
        raise ValueError("Frozen files changed during evaluation")
    judgments = table(frozen / "판정표.tsv")
    excluded = set()
    if judgments and "suitable" in judgments[0]:
        gold, weak, unreadable = graded_labels(judgments, tasks, documents)
        excluded = unreadable | {t for t in tasks if weak[t] and not gold[t]}
    else:
        gold = labels(judgments, tasks, documents)
    scores = []
    for task, variants in predictions.items():
        if set(variants["B"]) != set(variants["C"]):
            raise ValueError("C changed the B candidate population")
        for mode, results in variants.items():
            times = sorted(ms for t, m, run, ms in samples if (t, m) == (task, mode) and run > 0)
            scores.append(dict(task=task, mode=mode, relevant=None if task in excluded else len(gold[task]), candidates=len(results),
                               relevant_retrieved=len(set(results) & gold[task]),
                               rank=next((i for i, page in enumerate(results, 1) if page in gold[task]), None),
                               p50_ms=times[math.ceil(.5 * len(times))-1], p95_ms=times[math.ceil(.95 * len(times))-1]))
    report = dict(hashes=snapshot, product_b=product_b, slide_routes=slide_routes, graph_ms=graph_ms, pages_with_edges=sum(bool(e) for e in graph.values()),
                  edges=sum(map(len, graph.values())), promoted=promoted, scores=scores,
                  predictions=predictions, samples=samples)
    (out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("frozen", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("classpath")
    parser.add_argument("--java", default="java")
    parser.add_argument("--product-b", action="store_true", help="Use the opt-in product B route instead of the development ranker")
    args = parser.parse_args()
    compare(args.frozen, args.output.resolve(), args.java, args.classpath, args.product_b)
