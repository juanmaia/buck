/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.json;

import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * Hybrid project build file parser that uses Python DSL, Skylark or any other {@link Syntax}
 * depending on which one is requested for the individual build file.
 *
 * <p>The default syntax determines the syntax used in cases when {@value #SYNTAX_MARKER_START}
 * marker is not used, but clients can explicitly request desired syntax by adding {@value
 * #SYNTAX_MARKER_START} parser directive to the beginning of the build file followed by one of the
 * supported {@link Syntax} values.
 *
 * <p>Note that default syntax is not used in cases when invalid syntax value is provided - instead
 * in such cases an exception is thrown.
 */
public class HybridProjectBuildFileParser implements ProjectBuildFileParser {

  @VisibleForTesting static String SYNTAX_MARKER_START = "# BUILD FILE SYNTAX: ";

  public enum Syntax {
    PYTHON_DSL,
    SKYLARK,
    ;

    /**
     * Converts a syntax name specified after {@value #SYNTAX_MARKER_START} in the first line of the
     * build file.
     */
    public static Optional<Syntax> from(String syntaxName) {
      for (Syntax syntax : values()) {
        if (syntax.name().equals(syntaxName)) {
          return Optional.of(syntax);
        }
      }
      return Optional.empty();
    }
  }

  private ImmutableMap<Syntax, ProjectBuildFileParser> parsers;
  private final Syntax defaultSyntax;

  private HybridProjectBuildFileParser(
      ImmutableMap<Syntax, ProjectBuildFileParser> parsers, Syntax defaultSyntax) {
    this.parsers = parsers;
    this.defaultSyntax = defaultSyntax;
  }

  @Override
  public ImmutableList<Map<String, Object>> getAll(Path buildFile, AtomicLong processedBytes)
      throws BuildFileParseException, InterruptedException, IOException {
    return getParserForBuildFile(buildFile).getAll(buildFile, processedBytes);
  }

  @Override
  public ImmutableList<Map<String, Object>> getAllRulesAndMetaRules(
      Path buildFile, AtomicLong processedBytes)
      throws BuildFileParseException, InterruptedException, IOException {
    return getParserForBuildFile(buildFile).getAllRulesAndMetaRules(buildFile, processedBytes);
  }

  @Override
  public void reportProfile() throws IOException {
    for (ProjectBuildFileParser parser : parsers.values()) {
      parser.reportProfile();
    }
  }

  @Override
  public void close() throws BuildFileParseException, InterruptedException, IOException {
    for (ProjectBuildFileParser parser : parsers.values()) {
      parser.close();
    }
  }

  /**
   * @return The build file parser that should be used for parsing {@code buildFile}. Python DSL
   *     parser is used by default, but if the first line of the build file starts with {@value
   *     #SYNTAX_MARKER_START}, the next word determines the syntax to use.
   *     <p>Passing an unknown syntax causes {@link BuildFileParseException}, since new versions of
   *     Buck might support new syntax, that does not have to be Python DSL compatible.
   */
  private ProjectBuildFileParser getParserForBuildFile(Path buildFile)
      throws IOException, BuildFileParseException {
    @Nullable String firstLine = Files.readFirstLine(buildFile.toFile(), Charsets.UTF_8);

    Syntax syntax = defaultSyntax;
    if (firstLine != null && firstLine.startsWith(SYNTAX_MARKER_START)) {
      String syntaxName = firstLine.substring(SYNTAX_MARKER_START.length());
      syntax =
          Syntax.from(syntaxName)
              .orElseThrow(
                  () ->
                      BuildFileParseException.createForUnknownParseError(
                          String.format(
                              "Unrecognized syntax [%s] requested for build file [%s]",
                              syntaxName, buildFile)));
    }
    @Nullable ProjectBuildFileParser parser = parsers.get(syntax);
    if (parser == null) {
      throw new AssertionError(syntax + " is not mapped to any parser");
    }
    return parser;
  }

  /** @return The hybrid parser that supports Python DSL and Skylark syntax. */
  public static HybridProjectBuildFileParser using(
      ImmutableMap<Syntax, ProjectBuildFileParser> parsers, Syntax defaultSyntax) {
    return new HybridProjectBuildFileParser(parsers, defaultSyntax);
  }
}
