"""Browser launch options only; no real browser, profile or account is accessed."""
from pathlib import Path
import sys
import tempfile
from types import ModuleType
import unittest
from unittest.mock import MagicMock, patch

import jobsdb


class BrowserOptionsTests(unittest.TestCase):
    def test_native_viewport_options_keep_read_only_worker_blocking(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory)
            (data / 'scrapling-profile' / 'Default').mkdir(parents=True)
            for read_only in (False, True):
                with self.subTest(read_only=read_only):
                    session = MagicMock()
                    factory = MagicMock(return_value=session)
                    fetchers = ModuleType('scrapling.fetchers')
                    fetchers.StealthySession = factory
                    scrapling = ModuleType('scrapling')
                    scrapling.fetchers = fetchers
                    with patch.dict(sys.modules, {'scrapling': scrapling, 'scrapling.fetchers': fetchers}):
                        with jobsdb.Browser(data, read_only=read_only):
                            pass
                    expected = {'no_viewport': True, 'viewport': None, 'screen': None,
                                'device_scale_factor': None, 'is_mobile': None}
                    if read_only:
                        expected['service_workers'] = 'block'
                    self.assertEqual(expected, factory.call_args.kwargs.get('additional_args'))
                    self.assertEqual(str(data / 'scrapling-profile'), factory.call_args.kwargs['user_data_dir'])
                    session.__enter__.assert_called_once()
                    session.__exit__.assert_called_once()


if __name__ == '__main__':
    unittest.main()
