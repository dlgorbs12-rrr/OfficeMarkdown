import unittest
from xml.etree import ElementTree as ET

from holdout import boost, edges, labels


class HoldoutTest(unittest.TestCase):
    def test_graph_uses_only_resolved_physical_pairs(self):
        source = ET.fromstring("""<slide>
        <sp><nvSpPr><cNvPr id="1"/></nvSpPr><txBody><t>source</t></txBody></sp>
        <sp><nvSpPr><cNvPr id="2"/></nvSpPr><txBody><t>target</t></txBody></sp>
        <cxnSp><nvCxnSpPr><cNvCxnSpPr><stCxn id="1"/><endCxn id="2"/></cNvCxnSpPr></nvCxnSpPr></cxnSp>
        <cxnSp><nvCxnSpPr><cNvCxnSpPr><stCxn id="1"/><endCxn id="3"/></cNvCxnSpPr></nvCxnSpPr></cxnSp>
        </slide>""")
        pairs = edges(source)
        self.assertEqual(pairs, [("source", "target")])
        self.assertEqual(boost(["source", "target"], pairs), 2)
        self.assertEqual(boost(["source"], pairs), 0)

    def test_judgment_coverage_and_integrity(self):
        tasks = {"T01": "질문"}
        docs = [{"source_id": "H01", "page_count": "2"}]
        row = {"파일": "H01", "과제": "T01", "질문": "질문", "검토상태": "완료", "적합_장번호_전체": "1,2"}
        self.assertEqual(labels([row], tasks, docs), {"T01": {("H01", 1), ("H01", 2)}})
        for invalid in ([], [row, row], [dict(row, 질문="변경")], [dict(row, 적합_장번호_전체="3")]):
            with self.assertRaises(ValueError):
                labels(invalid, tasks, docs)
