"""Run the product pilot on a frozen library and score retrieval and recommendation separately."""
import argparse
import json
import os
import subprocess
from pathlib import Path

from holdout import digest, graded_labels, labels, table


def validate(trace, documents):
    inventory = {os.path.normcase(str(Path(row["local_path"]).resolve())): row for row in documents}
    stages = []
    for name, limit in (("candidates", 20), ("inspected", 4), ("recommended", 3)):
        keys = []
        for item in trace[name]:
            row = inventory.get(os.path.normcase(str(Path(item["source"]).resolve())))
            number = item["number"]
            if row is None or item["sha256"] != row["sha256"]:
                raise ValueError("Unknown source or changed version")
            if type(number) is not int or not 1 <= number <= int(row["page_count"]):
                raise ValueError("Page out of range")
            keys.append((row["source_id"], number))
        if len(keys) > limit or len(keys) != len(set(keys)):
            raise ValueError("Duplicate or excess pages")
        if stages and not set(keys).issubset(stages[-1]):
            raise ValueError("Page missing from upstream stage")
        stages.append(keys)
    if sorted(stages[1], key=stages[0].index) != stages[1]:
        raise ValueError("Inspection order differs from candidate order")
    return stages


def score(traces, documents, gold):
    scores = dict(positive=0, negative=0, hit20=0, inspected_hits=0, hit3=0,
                  absent_returns=0, errors=0)
    if traces.keys() != gold.keys():
        raise ValueError("Incomplete task set")
    for task, trace in traces.items():
        direct = gold[task]
        scores["positive" if direct else "negative"] += 1
        if trace["status"] == "error":
            scores["errors"] += 1
            continue
        if trace["status"] != "ok":
            raise ValueError("Invalid response status")
        pool, inspected, recommendations = validate(trace, documents)
        scores["hit20"] += bool(direct.intersection(pool))
        scores["inspected_hits"] += bool(direct.intersection(inspected))
        scores["hit3"] += bool(direct.intersection(recommendations))
        scores["absent_returns"] += not direct and bool(recommendations)
    return scores


def comparison(baseline, frozen, selected, gold, traces, documents):
    report = json.loads(baseline.read_text(encoding="utf-8"))
    hashes = report["hashes"]
    for name in ("tasks.tsv", "판정표.tsv", "documents.tsv"):
        if digest(frozen / name) != hashes.get(name):
            raise ValueError("Baseline differs from the frozen evaluation inputs")
    result = {}
    for mode in ("A", "B"):
        rows = {r["task"]: r for r in report["scores"] if r["mode"] == mode}
        if not set(selected).issubset(rows):
            raise ValueError("Incomplete baseline tasks")
        positive = [t for t in selected if gold[t]]
        hits = {t for t in positive if rows[t]["rank"] is not None and 1 <= rows[t]["rank"] <= 3}
        new_hits = {t for t in positive if traces[t]["status"] == "ok"
                    and gold[t].intersection(validate(traces[t], documents)[2])}
        result[mode] = dict(baseline_hit3=len(hits), pilot_hit3=len(new_hits),
                            gained=sorted(new_hits - hits), lost=sorted(hits - new_hits),
                            delta_percentage_points=100*(len(new_hits)-len(hits))/len(positive) if positive else None)
    return result


def run(exe, frozen, output, data, baseline=None):
    documents = table(frozen / "documents.tsv")
    tasks = {r["task"]: r["query"] for r in table(frozen / "tasks.tsv")}
    if any(digest(Path(d["local_path"])) != d["sha256"] for d in documents):
        raise ValueError("Frozen original changed")
    judgments = table(frozen / "판정표.tsv")
    if judgments and "suitable" in judgments[0]:
        gold, weak, unreadable = graded_labels(judgments, tasks, documents)
        excluded = unreadable | {t for t in tasks if weak[t] and not gold[t]}
    else:
        if any(r.get("판독실패_장과이유", "").strip() not in ("", "없음") for r in judgments):
            raise ValueError("Incomplete source review")
        gold, excluded = labels(judgments, tasks, documents), set()
    if not (data / "Index/.office-index-v1.sqlite").is_file():
        raise ValueError("Supply the isolated, already indexed evaluation library")
    env = dict(os.environ)
    env["JAVA_TOOL_OPTIONS"] = env.get("JAVA_TOOL_OPTIONS", "") + ' "-Dofficemd.dataDir=' + str(data.resolve()) + '"'
    traces = {}
    for task, question in tasks.items():
        proc = subprocess.run([str(exe.resolve()), "--reference-trace", "--provider", "CODEX", question], env=env,
                              capture_output=True, encoding="utf-8", errors="replace")
        trace = json.loads(proc.stdout) if proc.returncode == 0 else dict(status="error", exit_code=proc.returncode)
        if trace["status"] == "ok":
            validate(trace, documents)
        traces[task] = trace
    selected = {t: traces[t] for t in tasks if t not in excluded}
    result = dict(scores=score(selected, documents, {t: gold[t] for t in selected}),
                  excluded=sorted(excluded), traces=traces)
    if baseline is not None:
        result["comparison"] = comparison(baseline, frozen, selected, gold, traces, documents)
    with output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("exe", "frozen", "output", "data"):
        parser.add_argument(key, type=Path)
    parser.add_argument("--baseline", type=Path, help="A/B baseline report for the same frozen inputs")
    args = parser.parse_args()
    run(args.exe, args.frozen, args.output, args.data, args.baseline)
