import unittest
from score_reference import score


class ReferenceStageTest(unittest.TestCase):
    def test_candidate_miss_and_abstention_are_separate(self):
        docs = [dict(source_id="a", local_path="a.pptx", sha256="v", page_count="5")]
        pages = [dict(source="a.pptx", number=n, version="v/date", sha256="v") for n in range(1, 6)]
        trace = dict(status="ok", candidates=pages, inspected=pages[:4], recommended=[])
        result = score({"q": trace, "absent": trace, "failed": {"status": "error"}}, docs,
                       {"q": {("a", 5)}, "absent": set(), "failed": set()})
        self.assertEqual((result["hit20"], result["hit3"], result["absent_returns"], result["errors"]), (1, 0, 0, 1))
        with self.assertRaises(ValueError):
            score({"q": dict(trace, recommended=pages[4:])}, docs, {"q": {("a", 5)}})
