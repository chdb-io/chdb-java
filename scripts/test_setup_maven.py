"""Exercise the Maven bootstrap without network or a preinstalled Maven."""

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

INSTALLER = Path(__file__).with_name("setup-maven.sh")
PIN = re.search(r"^MAVEN_SHA512=([0-9a-f]{128})$", INSTALLER.read_text(), re.M).group(1)


class MavenSetupChecks(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory(prefix="chdb-maven-test-")
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.path_file = self.root / "github-path"
        self.path_file.touch()
        self.env = {
            **os.environ,
            "RUNNER_TEMP": str(self.root),
            "GITHUB_PATH": str(self.path_file),
            "PATH": f"{self.bin}:/usr/bin:/bin",
            "FAKE_SHA512": PIN,
            "FAKE_ROOT": str(self.root),
        }
        self.command("mvn", 'echo "untrusted system Maven must not run" >&2; exit 99')
        self.command("curl", '''
touch "$FAKE_ROOT/download-called"
if [ "${FAKE_DOWNLOAD_FAIL:-0}" = 1 ]; then exit 22; fi
while [ "$#" -gt 0 ]; do
  if [ "$1" = --output ]; then printf archive > "$2"; exit 0; fi
  shift
done
exit 1
''')
        self.command("sha512sum", 'printf "%s  %s\\n" "$FAKE_SHA512" "$1"')
        self.command("tar", '''
touch "$FAKE_ROOT/extract-called"
while [ "$#" -gt 0 ]; do
  if [ "$1" = -C ]; then destination="$2"; break; fi
  shift
done
mkdir -p "$destination/apache-maven-3.9.16/bin"
printf '#!/bin/bash\\nprintf "Apache Maven 3.9.16\\\\n"\\n' > "$destination/apache-maven-3.9.16/bin/mvn"
chmod +x "$destination/apache-maven-3.9.16/bin/mvn"
''')

    def command(self, name, body):
        command = self.bin / name
        command.write_text("#!/bin/bash\nset -eu\n" + body + "\n")
        command.chmod(0o755)

    def run_setup(self):
        return subprocess.run(["/bin/bash", str(INSTALLER)], env=self.env,
                              text=True, capture_output=True, timeout=15)

    def test_installs_without_using_system_maven(self):
        result = self.run_setup()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Apache Maven 3.9.16", result.stdout)
        installed_bin = Path(self.path_file.read_text().strip())
        self.assertTrue(installed_bin.is_relative_to(self.root))
        self.assertTrue((installed_bin / "mvn").is_file())

    def test_checksum_mismatch_stops_before_extraction(self):
        self.env["FAKE_SHA512"] = "0" * 128
        result = self.run_setup()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("SHA-512 mismatch", result.stderr)
        self.assertFalse((self.root / "extract-called").exists())
        self.assertEqual("", self.path_file.read_text())

    def test_shasum_fallback_without_sha512sum(self):
        (self.bin / "sha512sum").unlink()
        self.command("shasum", 'printf "%s  %s\\n" "$FAKE_SHA512" "$3"')
        for name in ("mktemp", "awk", "touch", "mkdir", "chmod"):
            (self.bin / name).symlink_to(shutil.which(name))
        self.env["PATH"] = str(self.bin)
        result = self.run_setup()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(self.path_file.read_text().strip())

    def test_failed_download_does_not_extract_or_update_path(self):
        self.env["FAKE_DOWNLOAD_FAIL"] = "1"
        self.assertNotEqual(0, self.run_setup().returncode)
        self.assertFalse((self.root / "extract-called").exists())
        self.assertEqual("", self.path_file.read_text())

    def test_missing_runner_context_fails_before_network(self):
        for variable in ("RUNNER_TEMP", "GITHUB_PATH"):
            with self.subTest(variable=variable):
                original = self.env.pop(variable)
                try:
                    self.assertNotEqual(0, self.run_setup().returncode)
                    self.assertFalse((self.root / "download-called").exists())
                finally:
                    self.env[variable] = original


if __name__ == "__main__":
    unittest.main()
