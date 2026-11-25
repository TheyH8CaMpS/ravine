import io
import json
import unittest

from release_order import should_publish


def metadata(*releases):
    return io.StringIO("".join(json.dumps(release) + "\n" for release in releases))


def release(build):
    return {"tag_name": f"v0.1.1-build{build}", "name": f"Ravine 0.1.1 (build {build})"}


class ReleaseOrderTest(unittest.TestCase):
    def test_counter_reset_cannot_publish_below_the_last_legacy_build(self):
        self.assertFalse(should_publish(1, metadata()))
        self.assertFalse(should_publish(110, metadata(release(110))))
        self.assertTrue(should_publish(111, metadata(release(110))))

    def test_back_to_back_jobs_keep_the_higher_build_in_either_order(self):
        for order in [(206, 207), (207, 206)]:
            with self.subTest(order=order):
                published = [release(110)]
                for build in order:
                    if should_publish(build, metadata(*published)):
                        published.append(release(build))
                self.assertEqual(published[-1], release(207))

    def test_uses_highest_published_build_even_if_the_api_lists_an_older_one_first(self):
        published = metadata(release(185), release(195), release(181))
        self.assertFalse(should_publish(191, published))

    def test_recognizes_manual_tags_and_legacy_build_suffixes(self):
        manual = {"tag_name": "v0.1.2", "name": "Ravine 0.1.2 (build 207)"}
        legacy = {"tag_name": "v0.1.1-build110", "name": "Ravine"}
        self.assertFalse(should_publish(206, metadata(manual, legacy)))
        self.assertTrue(should_publish(208, metadata(manual, legacy)))

    def test_unidentified_apk_release_fails_closed(self):
        with self.assertRaises(ValueError):
            should_publish(208, metadata({"tag_name": "v0.1.1", "name": "Untitled"}))


if __name__ == "__main__":
    unittest.main()
