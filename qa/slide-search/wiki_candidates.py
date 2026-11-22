"""Offline, source-scoped topic and one-hop candidate generator for frozen PPT evaluations."""
from collections import defaultdict
import math

from development import terms


def words(text):
    return {w for w in terms(text) if len(w) >= 2}


class WikiCandidates:
    def __init__(self, pages, graph):
        if set(pages) != set(graph):
            raise ValueError("Frozen graph and visible slide inventory differ")
        self.topic = defaultdict(set)
        self.heading = defaultdict(set)
        self.edges = defaultdict(set)
        self.labels = defaultdict(set)
        self.size = len(pages)
        endpoints = {(page[0], " ".join(label.casefold().split()))
                     for page, pairs in graph.items() for pair in pairs for label in pair}
        for page, lines in pages.items():
            content = [line.strip() for line in lines if line.strip() and not line.startswith("## 슬라이드 ")]
            for line in content:
                is_heading = line.startswith("### ")
                line = line.removeprefix("### ").removeprefix("- ")
                for word in words(line):
                    self.topic[word].add(page)
                    if is_heading:
                        self.heading[word].add(page)
                key = page[0], " ".join(line.casefold().split())
                if key in endpoints:
                    self.labels[key].add(page)
            for edge, (left, right) in enumerate(graph[page], 1):
                for direction, (seed, neighbor) in enumerate(((left, right), (right, left))):
                    label = " ".join(neighbor.casefold().split())
                    for word in words(seed):
                        self.edges[word].add((page, label, edge, direction))

    def search(self, query, baseline, limit=500, mode="G", audit=None):
        if mode not in ("T", "E", "G"):
            raise ValueError("Unknown expansion mode")
        query_words = sorted(words(query))
        matched = defaultdict(set)
        via = defaultdict(set)
        for word in query_words:
            for page in self.topic.get(word, ()):
                matched[page].add(word)
            if mode == "T":
                continue
            for origin, label, edge, direction in self.edges.get(word, ()):
                via[origin].add((word, origin, edge, direction))
                if mode == "E":
                    continue
                for page in self.labels.get((origin[0], label), ()):
                    via[page].add((word, origin, edge, direction))
        weights = {w: 1 + math.log((1+self.size)/(1+len(self.topic.get(w, ())))) for w in query_words}
        lexical = {p: sum(weights[w] * (2 if p in self.heading.get(w, ()) else 1) for w in sorted(ws))
                   for p, ws in matched.items()
                   if len(ws) >= 2 or any(p in self.heading.get(w, ()) for w in ws)}
        combined = lexical.copy()
        for page, paths in via.items():
            combined[page] = combined.get(page, 0) + sum(weights[w] for w in sorted({p[0] for p in paths}))

        ranked = sorted(combined, key=lambda p: (-combined[p], p))
        seen = set(baseline)
        additions = [p for p in ranked if p not in seen]
        merged = list(baseline) + additions
        expanded = merged[:limit]
        if audit is not None:
            audit.update(baseline=list(baseline), expansion_before_merge=ranked,
                         merged_before_limit=merged, truncated=len(merged) > limit,
                         limit=limit, timeout_enforced=False)
        reasons = []
        for page in expanded[len(baseline):]:
            reasons.append(dict(page=page, topic_words=sorted(matched.get(page, ())),
                                paths=sorted(via.get(page, ()))))
        return expanded, reasons
