"""Measure only the in-memory G extension on deterministic synthetic slides."""
import argparse
import json
import math
from time import perf_counter

from wiki_candidates import WikiCandidates


def measure(documents, repeats):
    pages, graph = {}, {}
    for doc in range(documents):
        topics = ["업무 절차", "시스템 구성", "연동 일정", "담당 부서", "참고 자료"]
        for number, heading in enumerate(topics, 1):
            key = (str(doc), number)
            pages[key] = ["### " + heading, f"분류{doc % 100} 계획", "인증서버" if number == 2 else "프로젝트"]
            graph[key] = [(f"단말{doc % 100}", "인증서버")] if number == 1 else []
    start = perf_counter()
    index = WikiCandidates(pages, graph)
    build = perf_counter() - start
    output = {}
    for query in ("업무 절차", "단말37", "미등록검색어"):
        expected, _ = index.search(query, [])
        samples = []
        for _ in range(repeats):
            start = perf_counter()
            actual, _ = index.search(query, [])
            samples.append(1000 * (perf_counter() - start))
            if actual != expected:
                raise ValueError("Unstable result order")
        samples.sort()
        output[query] = dict(count=len(expected), p50_ms=samples[math.ceil(.5 * repeats) - 1],
                             p95_ms=samples[math.ceil(.95 * repeats) - 1])
    return dict(documents=documents, slides=len(pages), connectors=documents, build_s=build, queries=output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--documents", type=int, default=50000)
    parser.add_argument("--repeats", type=int, default=20)
    args = parser.parse_args()
    if min(args.documents, args.repeats) < 1:
        parser.error("Expected positive counts")
    print(json.dumps(measure(args.documents, args.repeats), ensure_ascii=False, indent=2))
