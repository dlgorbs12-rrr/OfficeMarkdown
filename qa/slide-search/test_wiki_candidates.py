import unittest

from holdout import frozen_sources
from wiki_candidates import WikiCandidates


class WikiCandidatesTest(unittest.TestCase):
    def setUp(self):
        self.anchor = ("a", 1)
        self.neighbor = ("a", 2)
        self.third = ("a", 3)
        self.other = ("b", 2)
        pages = {self.anchor: ["### overview"], self.neighbor: ["### overview", "- Network"],
                 self.third: ["### overview", "storage"], self.other: ["### overview", "### NETWORK"]}
        self.graph = {self.anchor: [("identity", "network")], self.neighbor: [("network", "storage")],
                      self.third: [], self.other: []}
        self.index = WikiCandidates(pages, self.graph)

    def test_retrieves_unseen_neighbor_with_trace_but_no_second_hop_or_cross_file(self):
        result, reasons = self.index.search("identity", [])
        self.assertEqual(self.index.search("identity", [], mode="T")[0], [])
        self.assertEqual(self.index.search("identity", [], mode="E")[0], [self.anchor])
        self.assertEqual(result, [self.anchor, self.neighbor])
        self.assertEqual(reasons[1]["paths"], [("identity", self.anchor, 1, 0)])

    def test_base_order_limit_and_abstention(self):
        base = [self.third, self.anchor]
        result, reasons = self.index.search("identity", base, 2)
        self.assertEqual((result, reasons), (base, []))
        self.assertEqual(self.index.search("absent", []), ([], []))

    def test_topic_control_and_visibility(self):
        topic, _ = self.index.search("overview", [], mode="T")
        result, _ = self.index.search("overview", [])
        self.assertEqual(topic, result)
        self.assertEqual(len(set(topic)), 4)
        with self.assertRaises(ValueError):
            WikiCandidates({self.anchor: []}, self.graph)

    def test_heading_and_duplicate_manifest(self):
        idx = WikiCandidates({self.anchor: ["logo", "### Network"]}, {self.anchor: []})
        self.assertEqual(idx.search("logo", [])[0], [])
        self.assertEqual(idx.search("network", [])[0], [self.anchor])
        row = dict(source_id="a", local_path="file1", sha256="abc")
        for duplicate in (dict(source_id="a"), dict(local_path="file1"), dict(sha256="abc")):
            other = dict(source_id="b", local_path="file2", sha256="def")
            other.update(duplicate)
            with self.assertRaises(ValueError):
                frozen_sources([row, other])
