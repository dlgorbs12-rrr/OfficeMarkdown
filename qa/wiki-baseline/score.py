"""Score either the current baseline or a future implementation on the same queries."""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def read(path):
    return json.loads(path.read_text(encoding="utf8"))


def measure(questions, gold, predictions):
    tp = fp = fn = exact = positive = top3 = negative = false_alarm = 0
    for q in questions:
        ordered = predictions[q["id"]]
        if not isinstance(ordered, list) or not all(isinstance(v, str) for v in ordered):
            raise ValueError(f"Expected ordered string list for {q['id']}")
        got, wanted = set(ordered), set(gold[q["id"]])
        tp += len(got & wanted)
        fp += len(got - wanted)
        fn += len(wanted - got)
        exact += got == wanted
        if wanted:
            positive += 1
            top3 += bool(wanted.intersection(ordered[:3]))
        else:
            negative += 1
            false_alarm += bool(got)
    denom = 2 * tp + fp + fn
    return {"questions": len(questions), "tp": tp, "fp": fp, "fn": fn,
            "micro_f1_pct": round(200 * tp / denom, 2) if denom else None,
            "exact": exact, "top3_hits": top3, "positive_questions": positive,
            "false_alarms": false_alarm, "negative_questions": negative}


def main(path):
    qs, gold, pred = read(HERE / "questions.json"), read(HERE / "gold.json"), read(path)
    ids = {q["id"] for q in qs}
    if len(qs) != len(ids) or ids != set(gold) or ids != set(pred):
        raise ValueError("Missing, duplicate or unexpected query IDs")
    result = {}
    for field in ("task", "family"):
        for value in sorted({q[field] for q in qs}):
            result[value] = measure([q for q in qs if q[field] == value], gold, pred)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main(Path(sys.argv[1]))
