package io.github.richeyworks.superbeefsort.feed;

import java.util.List;

/** Feeds a sorted run into a {@link CsrbtTarget}. Implementations differ in their feeding personality. */
public interface SortFeeder<K> {

    FeedResult feed(List<K> sortedRun, CsrbtTarget<K> target);

    FeedMode mode();
}
