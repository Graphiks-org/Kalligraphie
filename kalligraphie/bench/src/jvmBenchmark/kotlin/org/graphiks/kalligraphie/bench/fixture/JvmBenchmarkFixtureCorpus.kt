package org.graphiks.kalligraphie.bench.fixture

/** The corpus the JVM benchmark compilation reads, from the committed `test-fixtures` tree. */
public object JvmBenchmarkFixtureCorpus : FixtureCorpus by ClasspathFixtureCorpus()
