"""Tests for the fork's narrow exception to upstream's JVM-tag documentation gate."""
import pytest

import check_image_tags as tags


@pytest.mark.parametrize("image", [
    "ghcr.io/alchemy-run/floci:1.6.0-alchemy.1-jvm",
    "ghcr.io/alchemy-run/floci:<tag>-jvm",
])
def test_accepts_published_alchemy_jvm_references(image):
    assert tags.invalid_jvm_tag_lines(f"| JVM | `{image}` |") == []


@pytest.mark.parametrize("image", [
    "floci/floci:latest-jvm",
    "docker.io/floci/floci:1.6.0-jvm",
    "hectorvent/floci:latest-jvm",
    "ghcr.io/alchemy-run/floci:latest-jvm",
    "ghcr.io/alchemy-run/floci:1.6.0-alchemy.1-jvm-compat",
    "ghcr.io/another-owner/floci:1.6.0-alchemy.1-jvm",
    "example.com/ghcr.io/alchemy-run/floci:1.6.0-alchemy.1-jvm",
    "Use the -jvm image.",
])
def test_rejects_references_outside_the_published_fork_contract(image):
    assert tags.invalid_jvm_tag_lines(image) == [(1, image)]


def test_fork_reference_does_not_hide_an_upstream_reference_on_the_same_line():
    line = "`ghcr.io/alchemy-run/floci:<tag>-jvm` or `floci/floci:latest-jvm`"
    assert tags.invalid_jvm_tag_lines("Heading\n" + line) == [(2, line)]


def test_other_published_tags_are_unchanged():
    assert tags.invalid_jvm_tag_lines("floci/floci:latest floci/floci:x.y.z-compat") == []
