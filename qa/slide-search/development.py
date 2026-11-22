"""Graph-free slide retrieval experiment on explicitly listed development sources."""
import argparse
import json
import math
import re
import sqlite3
from collections import Counter
from pathlib import Path


FILLER = frozenset("""
    잘된거 찾아줘 찾아주세요 찾아 보여주는 보여줘 보여주세요 어떻게 한 장으로 정리한 예시
    보고 싶어 참고할 자료 한눈에 보이는 장표 슬라이드 그림 있어 설명할 때 쓸
""".split())
ENDING = re.compile(r"(?<=..)(?:되는지|하는지|하기까지|기까지|하는|부터|까지|으로|에서|에게|들이|로|를|을|이|가|은|는)$")


def terms(query):
    words = re.findall(r"[^\W_]+", query.casefold())
    return list(dict.fromkeys(ENDING.sub("", word) for word in words if word not in FILLER))


def read_pages(db, sources):
    """Use exact paths. Reject missing/ambiguous notes rather than substituting another revision."""
    pages = {}
    has_queue = db.execute("SELECT 1 FROM sqlite_master WHERE name='large_pending'").fetchone()
    visible = "AND NOT EXISTS (SELECT 1 FROM large_pending p WHERE p.md=d.md)" if has_queue else ""
    for alias, source in sources.items():
        docs = db.execute(f"SELECT id,title FROM docs d WHERE source=? {visible}", (source,)).fetchall()
        if len(docs) != 1:
            raise ValueError(f"{alias}: expected one visible note, found {len(docs)}")
        doc, title = docs[0]
        if Path(title).suffix.lower() != ".pptx":
            raise ValueError(f"{alias}: expected a PPTX source")
        for section, body in db.execute(
            "SELECT section,text FROM lines WHERE doc=? AND kind=2 ORDER BY id", (doc,)
        ):
            match = re.fullmatch(r"슬라이드 (\d+)", section)
            if match:
                key = alias, int(match[1])
                pages.setdefault(key, []).append(body.casefold())
    return pages


def rank(pages, words, aggregate):
    # Match within original rows so joining text cannot create a word across shape boundaries.
    rows = {key: [{w for w in words if w in line} for line in lines] for key, lines in pages.items()}
    coverage = {key: set().union(*parts) for key, parts in rows.items()}
    frequency = Counter(word for covered in coverage.values() for word in covered)
    weights = {w: 1 + math.log((1 + len(pages)) / (1 + frequency[w])) for w in words}

    def score(matches):
        return sum(weights[w] for w in words if w in matches)

    scored = []
    for key, parts in rows.items():
        if coverage[key]:
            value = score(coverage[key]) if aggregate else max(map(score, parts))
            scored.append((value, key))
    return [key for _, key in sorted(scored, key=lambda pair: (-pair[0], pair[1]))]


def evaluate(db_path, manifest):
    sources = manifest["sources"]
    if len(set(sources.values())) != len(sources) or not sources:
        raise ValueError("Expected distinct development source paths")
    with sqlite3.connect(db_path.resolve().as_uri() + "?mode=ro", uri=True) as db:
        db.execute("BEGIN")
        pages = read_pages(db, sources)
    results = []
    for task in manifest["tasks"]:
        gold = {tuple(position) for position in task["gold"]}
        if not gold <= pages.keys():
            raise ValueError(f"{task['id']}: designated page missing from development index")
        raw = list(dict.fromkeys(re.findall(r"[^\W_]+", task["query"].casefold())))
        normalized = terms(task["query"])
        row = {"id": task["id"]}
        for name, words, aggregate in (
            ("raw_row", raw, False), ("normalized_row", normalized, False), ("normalized_slide", normalized, True)
        ):
            ranked = rank(pages, words, aggregate)
            row[name] = {"count": len(ranked), "rank": next(
                (pos for pos, key in enumerate(ranked, start=1) if key in gold), None)}
        results.append(row)
    return {"sources": len(sources), "pages": len(pages), "tasks": results}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8-sig"))
    print(json.dumps(evaluate(args.database, manifest), ensure_ascii=False, indent=2))
