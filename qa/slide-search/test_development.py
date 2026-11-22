import sqlite3
import unittest

from development import rank, read_pages, terms


class DevelopmentSearchTest(unittest.TestCase):
    def test_terms_from_request(self):
        self.assertEqual(terms("서버들이 네트워크로 연결되는지 보여주는 장표 찾아줘"),
                         ["서버", "네트워크", "연결"])

    def test_slide_coverage_and_row_boundaries(self):
        pages = {("a", 1): ["서버"], ("b", 2): ["네트워크", "서버"], ("c", 3): ["서", "버"]}
        words = ["서버", "네트워크"]
        self.assertEqual(rank(pages, words, True), [("b", 2), ("a", 1)])
        self.assertEqual(rank(pages, [], False), [])

    def test_exact_source_visibility(self):
        with sqlite3.connect(":memory:") as db:
            db.executescript("""
                CREATE TABLE docs(id,source,title,md);
                CREATE TABLE lines(id,doc,section,text,kind);
                CREATE TABLE large_pending(md);
                INSERT INTO docs VALUES(1,'dev','dev.pptx','note1'),(2,'other','other.pptx','note2');
                INSERT INTO lines VALUES(1,1,'슬라이드 1','내용',2),(2,2,'슬라이드 1','다른 내용',2);
            """)
            self.assertEqual(read_pages(db, {"d": "dev"}), {("d", 1): ["내용"]})
            db.execute("INSERT INTO large_pending VALUES ('note1')")
            with self.assertRaises(ValueError):
                read_pages(db, {"d": "dev"})


if __name__ == "__main__":
    unittest.main()
