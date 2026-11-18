"""Regression tests for the pre-upload consumer POM gate, with no Maven/network needed."""

import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "consumer_poms", Path(__file__).with_name("check-consumer-poms.py"))
CHECKS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKS)

POM = '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>com.clickhouse.chdb</groupId>
<artifactId>chdb-arrow</artifactId><version>1.1.0</version>
<name>Arrow</name><description>Arrow integration</description><url>https://chdb.io</url>
<licenses><license><name>Apache-2.0</name><url>https://example.com/license</url></license></licenses>
<scm><url>https://github.com/chdb-io/chdb-java</url></scm>
<developers><developer><name>chDB</name></developer></developers>
<dependencies><dependency><groupId>com.clickhouse.chdb</groupId>
<artifactId>chdb-jdbc</artifactId><version>1.1.0</version></dependency></dependencies>
</project>'''


class ConsumerPomChecks(unittest.TestCase):
    def check(self, text):
        with tempfile.TemporaryDirectory() as directory:
            pom = Path(directory) / "consumer.pom"
            pom.write_text(text, encoding="utf-8")
            return CHECKS.check(pom, "chdb-arrow", "1.1.0")

    def test_standalone_pom_is_accepted(self):
        self.assertEqual([], self.check(POM))

    def test_parent_build_and_repository_leaks_are_rejected(self):
        for element in ("parent", "build", "repositories", "pluginRepositories"):
            with self.subTest(element=element):
                self.assertTrue(self.check(POM.replace("</project>", f"<{element}/></project>")))

    def test_unresolved_version_is_rejected(self):
        self.assertTrue(self.check(POM.replace("1.1.0", "${project.version}")))

    def test_wrong_release_version_is_rejected(self):
        self.assertTrue(self.check(POM.replace("1.1.0", "1.1.1")))

    def test_internal_coordinate_is_rejected(self):
        self.assertTrue(self.check(POM.replace("chdb-jdbc", "chdb-bom")))

    def test_missing_central_metadata_is_rejected(self):
        self.assertTrue(self.check(POM.replace("<name>chDB</name>", "")))

    def test_missing_or_optional_jdbc_is_rejected(self):
        self.assertTrue(self.check(POM.replace("<dependencies>", "<unused>").replace(
            "</dependencies>", "</unused>")))
        for element in ("<optional>true</optional>", "<scope>provided</scope>",
                        "<scope>test</scope>"):
            with self.subTest(element=element):
                self.assertTrue(self.check(POM.replace("</dependency>", element + "</dependency>")))


if __name__ == "__main__":
    unittest.main()
