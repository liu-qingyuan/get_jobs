"""Metadata-only read admission. Never opens real profiles or a browser."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import jobsdb


class ReadProfileTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.data = Path(self.tmp.name)
        self.profile = self.data / 'scrapling-profile'
        (self.profile / 'Default').mkdir(parents=True)

    def refused(self, reason, *, resume=False):
        with patch('scrapling.fetchers.StealthySession', side_effect=AssertionError('must not launch')):
            with self.assertRaisesRegex(RuntimeError, reason):
                with jobsdb.Browser(self.data, read_only=True, allow_existing_worker=resume):
                    self.fail('Must refuse before browser startup')

    def test_dangling_worker_link_is_state_not_proof_of_absence(self):
        worker = self.profile / 'Default/Service Worker'
        worker.symlink_to(self.data / 'absent-worker')
        self.refused('existing Service Worker state')
        self.refused('profile metadata unavailable', resume=True)
        self.assertTrue(worker.is_symlink())

    def test_resume_accepts_only_a_real_worker_directory(self):
        worker = self.profile / 'Default/Service Worker'
        worker.mkdir()
        session = MagicMock()
        session.__enter__.return_value = session
        with patch('scrapling.fetchers.StealthySession', return_value=session):
            with jobsdb.Browser(self.data, read_only=True, allow_existing_worker=True):
                pass
        session.__enter__.assert_called_once()

    def test_profile_and_child_links_are_not_followed(self):
        outside = self.data / 'outside'
        outside.mkdir()
        sentinel = outside / 'sentinel'
        sentinel.write_text('Synthetic metadata fixture; not browser data')
        (self.profile / 'Default').rmdir()
        for link in (self.profile / 'Default', self.profile):
            with self.subTest(link=link.name):
                if link == self.profile:
                    self.profile.rmdir()
                link.symlink_to(outside, target_is_directory=True)
                self.refused('profile metadata unavailable')
                self.assertTrue(link.is_symlink())
                self.assertEqual('Synthetic metadata fixture; not browser data', sentinel.read_text())
                link.unlink()

    def test_metadata_errors_do_not_mean_worker_absent(self):
        for failure in (PermissionError('synthetic denial'), OSError('synthetic IO error')):
            with self.subTest(failure=type(failure).__name__), patch('os.scandir', side_effect=failure):
                self.refused('profile metadata unavailable')

    def test_missing_profile_never_creates_a_login(self):
        (self.profile / 'Default').rmdir()
        self.profile.rmdir()
        self.refused('profile metadata unavailable')
        self.assertFalse(self.profile.exists())


if __name__ == '__main__':
    unittest.main()
