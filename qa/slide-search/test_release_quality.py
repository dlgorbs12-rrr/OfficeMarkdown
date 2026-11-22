import unittest

from holdout import graded_labels
from release_quality import assess, latency, usability


class QualityTest(unittest.TestCase):
    def setUp(self):
        self.gold = {str(i): {("d", 4)} if i < 40 else set() for i in range(60)}
        self.pred = {t: dict(A=[], B=[], C=[]) for t in self.gold}
        self.modes = [("A", None), ("B", "A"), ("C", "B")]

    def score(self):
        return assess(self.gold, self.gold, self.pred, {"d": 5}, self.modes)

    def test_empty_ranker_and_abstention(self):
        scores = self.score()
        self.assertEqual(scores["B"]["quality"], "fail")
        self.assertEqual(scores["C"]["quality"], "not_evaluable")

    def test_graph_lift_cannot_hide_live_regression(self):
        for i in range(40):
            pages = [("d", n) for n in (1, 2, 3, 4)]
            self.pred[str(i)] = dict(A=pages[::-1], B=pages, C=pages[::-1] if i < 24 else pages)
        self.assertEqual(self.score()["C"]["quality"], "fail")
        for i in range(40):
            self.pred[str(i)]["A"] = []
            self.pred[str(i)]["C"] = self.pred[str(i)]["B"][::-1]
        self.assertEqual(self.score()["C"]["quality"], "pass")
        self.pred["41"]["B"] = self.pred["41"]["C"] = [("d", 2)]
        self.assertEqual(self.score()["C"]["quality"], "fail")

    def test_duplicate_and_changed_pool(self):
        self.pred["0"]["C"] = [("d", 2)]
        with self.assertRaises(ValueError):
            self.score()
        self.pred["0"]["A"] = [("d", 2), ("d", 2)]
        with self.assertRaises(ValueError):
            self.score()

    def test_grades_require_full_coverage(self):
        docs = [dict(source_id="d", version="1", page_count=3)]
        rows = [dict(source_id="d", task="q", page=i, version="1", suitable=v)
                for i, v in enumerate(("yes", "weak", "unreadable"), 1)]
        direct, weak, incomplete = graded_labels(rows, ["q"], docs)
        self.assertEqual((direct["q"], weak["q"], incomplete), ({("d", 1)}, {("d", 2)}, {"q"}))
        with self.assertRaises(ValueError):
            graded_labels(rows[1:], ["q"], docs)

    def test_latency_and_missing_trials(self):
        samples = [("q", m, n, float(n)) for m, _ in self.modes for n in range(21)]
        self.assertEqual(latency(["q"], self.modes, samples)["B"], 19)
        self.assertIsNone(latency(["q"], self.modes, samples[:-1])["C"])
        self.assertEqual(usability([], self.gold, self.modes)["B"]["usability"], "insufficient")

    def test_selection_and_failure_cost(self):
        trials = []
        for p in range(4):
            for j, m in enumerate(("A", "B")):
                for k in range(3):
                    trials.append(dict(participant=str(p), task=str(j*3+k), mode=m,
                                       selected_source="d", selected_page="4", success="1",
                                       seconds="10", clicks="1", researches="0", additional_inputs="0"))
        result = usability(trials, self.gold, self.modes)
        self.assertEqual(result["B"]["usability"], "pass")
        trials[-1].update(selected_source="", selected_page="", success="0", seconds="30")
        result = usability(trials, self.gold, self.modes)
        self.assertEqual(result["B"]["usability"], "fail")
        self.assertEqual(result["B"]["trials"], 12)
