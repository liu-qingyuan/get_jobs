"""New target directions must not be lost by legacy title-only screening."""
import unittest
from batch import screen


class WorkbenchDirectionsTest(unittest.TestCase):
    def test_ai_application_algorithm_and_embodied_roles_are_searchable(self):
        for title in ('LLM Application Developer', 'RAG Engineer', 'Algorithm Engineer',
                      'Embodied Robotics Engineer', '大模型应用开发工程师', '具身智能算法工程师'):
            with self.subTest(title=title):
                self.assertEqual('', screen(title, 'Early-career role; requirements reviewed separately.', 'Hong Kong'))


if __name__ == '__main__':
    unittest.main()
