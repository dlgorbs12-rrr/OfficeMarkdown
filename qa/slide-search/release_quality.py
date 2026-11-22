"""Score the retrieval portion of the release protocol from frozen per-slide judgments."""
import argparse
import json
import math
import statistics
from pathlib import Path

from holdout import digest, graded_labels, labels, table


def assess(tasks, gold, predictions, pages, modes):
    if set(predictions) != set(tasks) or set(gold) != set(tasks):
        raise ValueError("Task sets differ")
    positive = [t for t in tasks if gold[t]]
    negative = [t for t in tasks if not gold[t]]
    result = {}
    for mode, baseline in modes:
        pool, top, false_positive, wins, losses, recovered = [], [], [], [], [], []
        for t in tasks:
            if set(predictions[t]) != {name for name, _ in modes}:
                raise ValueError("Mode sets differ")
            ranked = [tuple(p) for p in predictions[t][mode]]
            if any(len(p) != 2 or p[0] not in pages or type(p[1]) is not int
                   or not 1 <= p[1] <= pages[p[0]] for p in ranked) or len(set(ranked)) != len(ranked):
                raise ValueError(f"Invalid or duplicate page in {t}/{mode}")
            hit = bool(gold[t].intersection(ranked[:3]))
            if gold[t].intersection(ranked[:20]):
                pool.append(t)
            if hit:
                top.append(t)
            if not gold[t] and ranked:
                false_positive.append(t)
            if baseline:
                old = [tuple(p) for p in predictions[t][baseline]]
                if mode == "C" and set(ranked) != set(old):
                    raise ValueError("Reranking changed the B pool")
                old_hit = bool(gold[t].intersection(old[:3]))
                if hit and not old_hit:
                    wins.append(t)
                if old_hit and not hit:
                    losses.append(t)
                if gold[t].intersection(set(ranked[:20]) - set(old)):
                    recovered.append(t)
        delta = len(wins) - len(losses)
        opportunities = sum(bool(gold[t].intersection(map(tuple, predictions[t][baseline])))
                            for t in positive) if baseline else None
        enough = len(positive) >= 40 and len(negative) >= 20
        live_hits = sum(bool(gold[t].intersection(map(tuple, predictions[t]["A"][:3]))) for t in positive)
        passed = (len(pool) * 100 >= 80 * len(positive) and len(top) >= live_hits
                  and len(top) * 100 >= 60 * len(positive)
                  and delta * 100 >= 20 * len(positive) and not false_positive)
        state = ("baseline" if baseline is None else "insufficient" if not enough
                 else "not_evaluable" if mode == "C" and opportunities == 0 else "pass" if passed else "fail")
        result[mode] = dict(quality=state, sample_sufficient=enough, comparator=baseline, comparator_pool_hits=opportunities, positive=len(positive),
                            negative=len(negative), hit20=len(pool), hit3=len(top),
                            absent_returns=len(false_positive), wins=wins, losses=losses,
                            new_relevant_pages_tasks=recovered)
    return result


def latency(tasks, modes, samples):
    runs = {mode: {t: {} for t in tasks} for mode, _ in modes}
    for t, mode, run, ms in samples:
        if (t not in tasks or mode not in runs or type(run) is not int or run < 0
                or run in runs[mode][t] or type(ms) not in (int, float) or not math.isfinite(ms) or ms < 0):
            raise ValueError("Invalid timing sample")
        runs[mode][t][run] = ms
    latencies = {}
    for mode, _ in modes:
        complete = all(0 in trials and len(trials) >= 21 and trials.keys() == runs["A"][t].keys()
                       for t, trials in runs[mode].items())
        times = sorted(ms for trials in runs[mode].values() for run, ms in trials.items() if run)
        latencies[mode] = times[math.ceil(.95 * len(times)) - 1] if complete else None
    return latencies


def usability(trials, gold, modes):
    """Summarize counterbalanced positive selection tasks, including every give-up."""
    grouped = {mode: {} for mode, _ in modes}
    seen = set()
    for row in trials:
        mode, person, task = row["mode"], row["participant"].strip(), row["task"]
        key = (person, task)
        if mode not in grouped or not person or key in seen or not gold.get(task):
            raise ValueError("Unknown, repeated or non-selection trial")
        seen.add(key)
        seconds = float(row["seconds"])
        counts = [int(row[k]) for k in ("clicks", "researches", "additional_inputs")]
        if not math.isfinite(seconds) or seconds <= 0 or min(counts) < 0:
            raise ValueError("Invalid trial cost")
        selected = row["selected_source"].strip()
        page = row["selected_page"].strip()
        if bool(selected) != bool(page):
            raise ValueError("Incomplete selection")
        hit = bool(selected) and (selected, int(page)) in gold[task]
        if row["success"] != ("1" if hit else "0"):
            raise ValueError("Selection differs from recorded success")
        grouped[mode].setdefault(person, []).append((int(hit), seconds, *counts))

    result = {}
    for mode, people in grouped.items():
        trials = [values for participant in people.values() for values in participant]
        result[mode] = {"trials": len(trials), "participants": len(people), "usability": "insufficient"}
        if trials:
            result[mode].update(success=statistics.mean(x[0] for x in trials),
                                seconds=statistics.median(x[1] for x in trials),
                                clicks=statistics.mean(x[2] for x in trials),
                                researches=statistics.mean(x[3] for x in trials),
                                additional_inputs=statistics.mean(x[4] for x in trials))
    for mode, people in grouped.items():
        if (len(people) < 4 or people.keys() != grouped["A"].keys()
                or any(len(v) < 3 or len(v) != len(grouped["A"][p]) for p, v in people.items())):
            continue
        current, base = result[mode], result["A"]
        passed = current["success"] >= base["success"] and all(
            current[k] <= base[k] for k in ("seconds", "clicks", "researches", "additional_inputs"))
        current["usability"] = "baseline" if mode == "A" else "pass" if passed else "fail"
    return result


def replay(frozen, report_file, trials_file=None):
    report = json.loads(report_file.read_text(encoding="utf-8"))
    for name in ("documents.tsv", "tasks.tsv", "판정표.tsv"):
        if report["hashes"][name] != digest(frozen / name):
            raise ValueError("Frozen inputs differ")
    docs = table(frozen / "documents.tsv")
    rows = table(frozen / "tasks.tsv")
    tasks = {r["task"]: r["query"] for r in rows}
    pages = {d["source_id"]: int(d["page_count"]) for d in docs}
    if not tasks or not pages or len(tasks) != len(rows) or len(pages) != len(docs):
        raise ValueError("Empty or duplicate task/source IDs")
    if not report.get("product_b"):
        raise ValueError("Product baseline required")
    judgments = table(frozen / "판정표.tsv")
    graded = bool(judgments and "suitable" in judgments[0])
    if graded:
        gold, weak, unreadable = graded_labels(judgments, tasks, docs)
    else:
        if any(row.get("판독실패_장과이유", "").strip() not in ("", "없음") for row in judgments):
            raise ValueError("Incomplete source review; cannot score absence")
        gold = labels(judgments, tasks, docs)
        weak, unreadable = {t: set() for t in tasks}, set()
    modes = [("A", None), ("B", "A"), ("C", "B")]
    if any("G" in v for v in report["predictions"].values()):
        modes.append(("G", "B"))
    predictions = report["predictions"]
    # Validate all rankings, including excluded tasks, before evaluating the primary set.
    all_tasks = assess(tasks, gold, predictions, pages, modes)
    primary = {t for t in tasks if t not in unreadable and (gold[t] or not weak[t])}
    result = all_tasks if primary == set(tasks) else assess(
        primary, {t: gold[t] for t in primary}, {t: predictions[t] for t in primary}, pages, modes)
    families = {d.get("revision_family", "").strip() for d in docs}
    for mode, _ in modes:
        data = result[mode]
        data["excluded_tasks"] = sorted(set(tasks) - primary)
        data["sensitivity_positive"] = sum(bool(gold[t] or weak[t]) for t in tasks if t not in unreadable)
        data["sensitivity_hit3"] = sum(bool((gold[t] | weak[t]).intersection(
            map(tuple, predictions[t][mode][:3]))) for t in tasks if t not in unreadable)
        if data["quality"] == "pass" and (not graded or "" in families or len(families) < 4):
            data["quality"] = "insufficient"
    times = latency(tasks, modes, report.get("samples", []))
    user = usability(table(trials_file) if trials_file else [], {t: gold[t] for t in primary}, modes)
    for mode, _ in modes:
        ms, base = times[mode], times["A"]
        result[mode]["user_trials"] = user[mode]
        result[mode]["engine_p95_ms"] = ms
        result[mode]["engine_latency"] = ("insufficient" if ms is None or not base else
                                          "pass" if ms <= 1.2 * base else "fail")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("frozen", type=Path)
    parser.add_argument("report", type=Path)
    parser.add_argument("--trials", type=Path, help="Completed positive selection trials TSV")
    args = parser.parse_args()
    print(json.dumps(replay(args.frozen, args.report, args.trials), indent=2))
