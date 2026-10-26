#!/usr/bin/env bash
set -uo pipefail

# Resolve runfiles directory
if [ -z "${RUNFILES_DIR:-}" ]; then
  if [ -z "${TEST_SRCDIR:-}" ]; then
    RUNFILES_DIR="${BASH_SOURCE[0]}.runfiles"
  else
    RUNFILES_DIR="$TEST_SRCDIR"
  fi
fi

WORKSPACE_DIR="${RUNFILES_DIR}/%{workspace}"

# The declared environment variables that name `data` labels' runfiles paths. Bazel exports them
# relative to the runfiles tree, where a test starts; the JVM below runs from a project directory
# of its own, so they are exported again with those paths absolute.
%{runfiles_env}

TOOL_JAR="${WORKSPACE_DIR}/%{tool_jar}"
JAVA="${WORKSPACE_DIR}/%{java_home}/bin/java"
MODEL_FILE="${WORKSPACE_DIR}/%{model_file}"
COVERAGE_ENABLED="%{coverage_enabled}"
QUARKUS_JACOCO_PRESENT="%{quarkus_jacoco_present}"
JACOCO_RUNNER="${WORKSPACE_DIR}/%{jacoco_runner}"
COVERAGE_REPORTER="${WORKSPACE_DIR}/%{coverage_reporter}"
COVERAGE_JARS_SOURCE="${WORKSPACE_DIR}/%{coverage_jars_file}"
TEST_KIND="%{test_kind}"
ARTIFACT_TYPE="%{artifact_type}"
ARTIFACT_PATH="${WORKSPACE_DIR}/%{artifact_path}"

# Everything the launcher writes lives in the test's private TEST_TMPDIR. Bazel recreates that
# directory before each run of the test, so what a killed test leaves behind (a Bazel timeout, an
# interrupted run), which no trap gets to remove, is reclaimed by its next run instead of
# accumulating in the host's /tmp.
if [ -z "${TEST_TMPDIR:-}" ]; then
  echo "ERROR: TEST_TMPDIR is not set; Quarkus tests run through 'bazel test'" >&2
  exit 1
fi

# Validate packaged integration artifacts before TEST-mode model generation.
if [ "$TEST_KIND" = "integration" ]; then
  if [ "$ARTIFACT_TYPE" = "native" ]; then
    if [ ! -x "$ARTIFACT_PATH" ]; then
      echo "ERROR: Quarkus native integration-test artifact is missing or not executable: $ARTIFACT_PATH" >&2
      exit 1
    fi
  elif [ "$ARTIFACT_TYPE" = "jar" ]; then
    if [ ! -f "$ARTIFACT_PATH" ]; then
      echo "ERROR: Quarkus Fast JAR runner is missing: $ARTIFACT_PATH" >&2
      exit 1
    fi
  else
    echo "ERROR: Unsupported Quarkus integration-test artifact type: $ARTIFACT_TYPE" >&2
    exit 1
  fi
fi

MODEL_REAL=$(realpath "$MODEL_FILE")
case "$MODEL_REAL" in
  */bazel-out/*) MODEL_EXEC_ROOT="${MODEL_REAL%%/bazel-out/*}" ;;
  *) MODEL_EXEC_ROOT="$WORKSPACE_DIR" ;;
esac

# Stream classpaths into temp files using awk (O(n), safe with special chars in paths).
# Each source file has separator-delimited relative paths; we prefix with WORKSPACE_DIR.
# The prefix and separator are passed via the environment: ENVIRON values are
# byte-exact, whereas awk -v would reinterpret backslash escape sequences.
_prefix_entries() {
  local sep="$1" src="$2" dest="$3"
  CP_PREFIX="${WORKSPACE_DIR}/" CP_SEP="$sep" awk \
    'BEGIN{FS=ENVIRON["CP_SEP"]; OFS=FS; p=ENVIRON["CP_PREFIX"]} {for(i=1;i<=NF;i++) $i=p $i; print}' \
    < "$src" > "$dest"
}

_realpath_entries() {
  local src="$1" dest="$2"
  while IFS=: read -ra ENTRIES; do
    local first=true
    for entry in "${ENTRIES[@]}"; do
      if [ "$first" = false ]; then printf ':' >> "$dest"; fi
      local resolved
      resolved=$(realpath "$entry") || { echo "ERROR: realpath failed for '$entry'" >&2; return 1; }
      printf '%s' "$resolved" >> "$dest"
      first=false
    done
  done < "$src"
}

# Runtime classpath (app + test jars, for both JUnit -cp and quarkifier).
#
# Both get the resolved paths. The serialized model resolves every artifact path
# (`ExplicitApplicationModelBuilder.resolveArtifactPaths` calls `toRealPath` in
# dev and test), and Quarkus compares the two by `Path.equals`, which is lexical:
# `PathTestHelper.isTestClass` answers `testLocation.equals(cpe.getRoot())`, where
# `testLocation` comes from the model and `cpe.getRoot()` from this classpath. A
# runfiles path and the exec-root path it links to are the same file and never
# equal strings, so launching JUnit with the unresolved paths makes that check --
# and the ArC removal exclusion for test classes built on it -- always false.
APP_CP_FILE=$(mktemp "$TEST_TMPDIR/app-classpath.XXXXXX")
_prefix_entries ":" "${WORKSPACE_DIR}/%{classpath_file}" "$APP_CP_FILE"
MODEL_APP_CP_FILE=$(mktemp "$TEST_TMPDIR/model-app-classpath.XXXXXX")
_realpath_entries "$APP_CP_FILE" "$MODEL_APP_CP_FILE" || exit 1

# Direct dep jars: comma-separated in source, prefix each entry. The file is
# used for test discovery and to resolve local model artifacts at test time.
DIRECT_JARS_FILE=$(mktemp "$TEST_TMPDIR/direct-jars.XXXXXX")
_prefix_entries "," "${WORKSPACE_DIR}/%{direct_jars_file}" "$DIRECT_JARS_FILE"

# Where each artifact the model names is found. The model holds the exec-root paths of the
# build that assembled it; this test has the artifacts in its runfiles tree, which on the
# build machine links into that exec root and on a remote executor is all there is. Each
# line pairs a model path with the artifact's runfiles path, made absolute here.
ARTIFACT_PATHS_FILE=$(mktemp "$TEST_TMPDIR/artifact-paths.XXXXXX")
CP_PREFIX="${WORKSPACE_DIR}/" awk \
  'BEGIN{FS="\t"; OFS=FS; p=ENVIRON["CP_PREFIX"]} NF==2 {$2=p $2; print}' \
  < "${WORKSPACE_DIR}/%{artifact_paths_file}" > "$ARTIFACT_PATHS_FILE"

# Coverage analysis includes every local runtime jar, including Quarkus
# extension runtimes that must stay excluded from application roots.
COVERAGE_JARS_FILE=""
if [ "$COVERAGE_ENABLED" = "true" ]; then
  COVERAGE_JARS_FILE=$(mktemp "$TEST_TMPDIR/coverage-jars.XXXXXX")
  _prefix_entries "," "$COVERAGE_JARS_SOURCE" "$COVERAGE_JARS_FILE"
fi

LOCAL_APP_JARS_FILE=$(mktemp "$TEST_TMPDIR/local-app-jars.XXXXXX")
while IFS=, read -ra JAR_ENTRIES; do
  first=true
  for jar_path in "${JAR_ENTRIES[@]}"; do
    if [ "$first" = false ]; then printf ':' >> "$LOCAL_APP_JARS_FILE"; fi
    resolved_jar=$(realpath "$jar_path") || { echo "ERROR: realpath failed for '$jar_path'" >&2; exit 1; }
    printf '%s' "$resolved_jar" >> "$LOCAL_APP_JARS_FILE"
    first=false
  done
done < "$DIRECT_JARS_FILE"

# Phase 1: Generate serialized ApplicationModel at test time.
# The Web Bundler stages the application's bundle here, under `project/target`, and Tailwind scans
# that staged directory for the utility classes the page uses. Tailwind applies the ignore rules of
# the git work tree the directory sits in, and Bazel's default test temporary directory is in the
# execroot, which links the workspace's .git and .gitignore. A workspace that ignores `target/`
# therefore gets a stylesheet with every application class purged: a silently unstyled page rather
# than a failed build. A workspace that bundles with Tailwind points `--test_tmpdir` outside every
# git work tree.
#
# Each augmentation extracts the web dependencies here, thousands of small files apiece. The trap
# below reclaims them when the launcher exits; Bazel's cleaning of TEST_TMPDIR reclaims them when a
# killed test never reached the trap.
MODEL_DIR=$(mktemp -d "$TEST_TMPDIR/quarkus-model.XXXXXX")
ARTIFACT_METADATA_DIR=""
_cleanup_test_dirs() {
  rm -rf "$MODEL_DIR"
  if [ -n "$ARTIFACT_METADATA_DIR" ]; then
    rm -rf "$ARTIFACT_METADATA_DIR"
  fi
}
trap _cleanup_test_dirs EXIT

(cd "$MODEL_EXEC_ROOT" && "$JAVA" \
  -Djava.util.logging.manager=org.jboss.logmanager.LogManager \
  -jar "$TOOL_JAR" \
  augmentation \
  --application-classpath-file "$MODEL_APP_CP_FILE" \
  --output-dir "$MODEL_DIR" \
  --mode test \
  --application-model "$MODEL_FILE" \
  --artifact-paths-file "$ARTIFACT_PATHS_FILE" \
  --local-app-jars-file "$LOCAL_APP_JARS_FILE" \
  --app-name %{app_name})

if [ $? -ne 0 ]; then
  rm -f "$APP_CP_FILE" "$MODEL_APP_CP_FILE" "$LOCAL_APP_JARS_FILE" "$DIRECT_JARS_FILE" "$ARTIFACT_PATHS_FILE" "$COVERAGE_JARS_FILE"
  echo "ERROR: Quarkifier test model generation failed" >&2
  exit 1
fi

# Keep MODEL_APP_CP_FILE and DIRECT_JARS_FILE for phase 2; clean the rest.
rm -f "$APP_CP_FILE" "$LOCAL_APP_JARS_FILE" "$ARTIFACT_PATHS_FILE"

if [ ! -f "$MODEL_DIR/test-app-model.dat" ]; then
  rm -f "$MODEL_APP_CP_FILE" "$DIRECT_JARS_FILE" "$COVERAGE_JARS_FILE"
  echo "ERROR: test-app-model.dat was not generated" >&2
  exit 1
fi
if [ ! -f "$MODEL_DIR/test-app-roots.txt" ]; then
  rm -f "$MODEL_APP_CP_FILE" "$DIRECT_JARS_FILE" "$COVERAGE_JARS_FILE"
  echo "ERROR: test-app-roots.txt was not generated" >&2
  exit 1
fi
# AppMakerHelper derives both project discovery and its writable build target
# from the JVM working directory. Bazel source/runfile trees are read-only, so
# provide an action-local Maven-layout root. The serialized model continues to
# point at the real workspace sources and canonical class directories.
TEST_PROJECT_DIR="$MODEL_DIR/project"
mkdir -p "$TEST_PROJECT_DIR/src/main" "$TEST_PROJECT_DIR/target"

# Quarkus trains build-phase AOT caches from the package directory and records relative JAR
# paths. Its integration launcher inherits this JVM's working directory. Present the same
# package layout in the existing writable test project, without modifying the runfiles.
# Require a discovered cache to load: incompatible caches must fail rather than fall back.
if [ "$TEST_KIND" = "integration" ] && [ "$ARTIFACT_TYPE" = "jar" ] &&
    [ -f "$(dirname "$ARTIFACT_PATH")/app.aot" ]; then
  AOT_PACKAGE_DIR="$(dirname "$ARTIFACT_PATH")"
  if [ ! -f "$AOT_PACKAGE_DIR/app.aot.jar-timestamps" ]; then
    echo "ERROR: AOT cache is missing its training-time JAR timestamps: $AOT_PACKAGE_DIR" >&2
    exit 1
  fi
  "$JAVA" -cp "$TOOL_JAR" com.clementguillot.quarkifier.augmentation.AotCachePackage \
    "$AOT_PACKAGE_DIR" "$TEST_PROJECT_DIR" || exit 1
  ARTIFACT_PATH="$TEST_PROJECT_DIR/$(basename "$ARTIFACT_PATH")"
  export QUARKUS_TEST_ARG_LINE="${QUARKUS_TEST_ARG_LINE:-} -XX:AOTMode=on -XX:AOTCache=app.aot"
fi

# Phase 2: Run JUnit with the serialized model. AppMakerHelper obtains the
# application roots from that model and locates the selected test class from
# the JUnit classpath. Adding the local jars again through OUTPUT_SOURCES_DIR
# would create duplicate application archives and duplicate Jandex ClassInfo
# instances for every application class.
# Auto-discover test classes from user jars if no explicit selectors were given.
# Unit tests follow JUnit's default naming pattern while integration tests
# follow the Maven Failsafe-compatible *IT convention.
TEST_ARGS="%{test_args}"
JAR_CMD="${WORKSPACE_DIR}/%{java_home}/bin/jar"
# The class files of a local entry, relative to its root: a direct dep is presented by its
# canonical class directory, an extension's runtime by its jar.
_class_entries() {
  if [ -d "$1" ]; then
    (cd "$1" && find . -name '*.class' | sed 's|^\./||')
  else
    "$JAR_CMD" tf "$1" 2>/dev/null
  fi
}
if ! echo "$TEST_ARGS" | grep -q -e '--select-' -e '--scan-'; then
  while IFS=, read -ra JAR_ENTRIES; do
    for jar_path in "${JAR_ENTRIES[@]}"; do
      if [ -e "$jar_path" ]; then
        while IFS= read -r cls; do
          cls="${cls%.class}"
          cls="${cls//\//.}"
          if [ "$TEST_KIND" = "integration" ] && echo "$cls" | grep -qE 'IT$'; then
            TEST_ARGS="${TEST_ARGS} --select-class=${cls}"
          elif [ "$TEST_KIND" != "integration" ] &&
              echo "$cls" | grep -qE '(^Test|[.$]Test|Tests?$)' &&
              ! echo "$cls" | grep -qE 'IT$'; then
            TEST_ARGS="${TEST_ARGS} --select-class=${cls}"
          fi
        done < <(_class_entries "$jar_path" | grep '\.class$' | grep -v '\$')
      fi
    done
  done < "$DIRECT_JARS_FILE"
fi

REPORTS_DIR=$(mktemp -d "$TEST_TMPDIR/junit-console-reports.XXXXXX")

# ConsoleLauncher writes one JUnit XML document per test engine. Bazel expects
# one XML_OUTPUT_FILE, so wrap those trusted documents in a testsuites root.
# This report is diagnostic output only; its contents never determine the test
# exit code.
_write_bazel_test_xml() {
  local output="$1"
  local reports_dir="$2"
  local report_list
  local tmp_output="${output}.tmp.$$"
  local merge_failed=0

  report_list=$(mktemp "$TEST_TMPDIR/junit-report-list.XXXXXX")
  if ! find "$reports_dir" -maxdepth 1 -type f -name 'TEST-*.xml' -print |
      LC_ALL=C sort > "$report_list"; then
    rm -f "$report_list"
    return 1
  fi
  if [ ! -s "$report_list" ]; then
    rm -f "$report_list"
    return 1
  fi

  mkdir -p "$(dirname "$output")"
  {
    printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
    printf '%s\n' '<testsuites>'
    while IFS= read -r report; do
      # JUnit emits a single XML declaration on the first line of every file.
      if ! tail -n +2 "$report"; then
        merge_failed=1
        break
      fi
    done < "$report_list"
    printf '%s\n' '</testsuites>'
  } > "$tmp_output"
  rm -f "$report_list"

  if [ "$merge_failed" -ne 0 ]; then
    rm -f "$tmp_output"
    return 1
  fi
  mv "$tmp_output" "$output"
}

# Bazel instruments local jars before the test starts. Its runner jar is used
# only for the matching offline JaCoCo runtime; ConsoleLauncher remains main.
TEST_JVM_ARGS=(
  "-Djava.util.logging.manager=org.jboss.logmanager.LogManager"
)
if [ "$TEST_KIND" = "integration" ]; then
  ARTIFACT_METADATA_DIR=$(mktemp -d "$TEST_TMPDIR/quarkus-artifact.XXXXXX")
  {
    printf 'type=%s\n' "$ARTIFACT_TYPE"
    printf 'path=%s\n' "$ARTIFACT_PATH"
  } > "$ARTIFACT_METADATA_DIR/quarkus-artifact.properties"

  # @QuarkusIntegrationTest still augments the application in this JVM, to start its Dev
  # Services, and roots that augmentation at the test classes and OUTPUT_SOURCES_DIR alone, never
  # at the serialized model's application artifact as @QuarkusTest does. The quarkifier wrote
  # that artifact's paths for it. A @QuarkusTest must not get them: it already has them from the
  # model, and a second copy duplicates every application class in the index.
  TEST_JVM_ARGS+=(
    "-Dbuild.output.directory=${ARTIFACT_METADATA_DIR}"
    "-DOUTPUT_SOURCES_DIR=$(cat "$MODEL_DIR/test-app-roots.txt")"
    "-Dquarkus.http.test-port=0"
    "-Dquarkus.http.test-ssl-port=0"
  )
  if [ "$ARTIFACT_TYPE" = "native" ]; then
    TEST_JVM_ARGS+=("-Dnative.image.path=${ARTIFACT_PATH}")
  fi
fi
COVERAGE_EXEC_FILE=""
if [ "$COVERAGE_ENABLED" = "true" ]; then
  COVERAGE_EXEC_FILE=$(mktemp "$TEST_TMPDIR/quarkus-jacoco.XXXXXX")
  TEST_JVM_ARGS+=(
    "-Djacoco-agent.output=file"
    "-Djacoco-agent.append=false"
    "-Djacoco-agent.dumponexit=true"
    "-Djacoco-agent.destfile=${COVERAGE_EXEC_FILE}"
  )
  if [ "$QUARKUS_JACOCO_PRESENT" = "true" ]; then
    TEST_JVM_ARGS+=("-Dquarkus.jacoco.enabled=false")
  fi
elif [ "$QUARKUS_JACOCO_PRESENT" = "true" ]; then
  if [ -z "${TEST_UNDECLARED_OUTPUTS_DIR:-}" ]; then
    echo "ERROR: TEST_UNDECLARED_OUTPUTS_DIR is required for Quarkus JaCoCo reports" >&2
    exit 1
  fi
  mkdir -p "$TEST_UNDECLARED_OUTPUTS_DIR"
  TEST_JVM_ARGS+=(
    "-Dquarkus.jacoco.data-file=${TEST_UNDECLARED_OUTPUTS_DIR}/jacoco-quarkus.exec"
    "-Dquarkus.jacoco.report-location=${TEST_UNDECLARED_OUTPUTS_DIR}/jacoco-report"
  )
fi

# Use a JDK @argfile to avoid E2BIG on the classpath.
# Values are double-quoted per JDK argfile syntax to handle paths with spaces;
# backslash and double-quote are escaped inside the quotes.
_argfile_escape() { tr -d '\n' | sed 's/\\/\\\\/g; s/"/\\"/g'; }

JAVA_ARGS_FILE=$(mktemp "$TEST_TMPDIR/java-args.XXXXXX")
{
  printf '%s\n' "-cp"
  printf '"'
  if [ "$COVERAGE_ENABLED" = "true" ]; then
    printf '%s:' "$JACOCO_RUNNER" | _argfile_escape
  fi
  _argfile_escape < "$MODEL_APP_CP_FILE"
  printf '"\n'
} > "$JAVA_ARGS_FILE"

(cd "$TEST_PROJECT_DIR" && "$JAVA" "@$JAVA_ARGS_FILE" \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
  %{build_property_jvm_flags} \
  -Dquarkus-internal-test.serialized-app-model.path="$MODEL_DIR/test-app-model.dat" \
  -DTEST_TO_MAIN_MAPPINGS=.quarkus-classes:.quarkus-classes \
  -Dplatform.quarkus.native.builder-image=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25@sha256:4dda6a3d677b57614849557d0d18aac7326c4f30175142b0f1bb91bdcfc5c29a \
  -Dquarkus.package.jar.type=fast-jar \
  "${TEST_JVM_ARGS[@]}" \
  %{jvm_flags} \
  org.junit.platform.console.ConsoleLauncher \
  $TEST_ARGS \
  --reports-dir="$REPORTS_DIR")
FINAL_EXIT=$?

rm -f "$JAVA_ARGS_FILE" "$MODEL_APP_CP_FILE"

# Publish JUnit's real test suites to Bazel. A reporting failure can fail an
# otherwise successful test, but never replaces or hides a JUnit failure.
if [ -n "${XML_OUTPUT_FILE:-}" ] &&
    ! _write_bazel_test_xml "$XML_OUTPUT_FILE" "$REPORTS_DIR"; then
  echo "ERROR: Failed to write Bazel JUnit XML report" >&2
  if [ "$FINAL_EXIT" -eq 0 ]; then
    FINAL_EXIT=1
  fi
fi

# Format only successful test executions. Reporter failure turns an otherwise
# successful coverage test into a failure and never hides a failed test.
if [ "$FINAL_EXIT" -eq 0 ] && [ "$COVERAGE_ENABLED" = "true" ]; then
  if [ -z "${JAVA_COVERAGE_FILE:-}" ]; then
    echo "ERROR: JAVA_COVERAGE_FILE is not set during Bazel coverage" >&2
    FINAL_EXIT=1
  elif ! "$COVERAGE_REPORTER" \
      --execution-data "$COVERAGE_EXEC_FILE" \
      --output "$JAVA_COVERAGE_FILE" \
      --class-jars-file "$COVERAGE_JARS_FILE"; then
    echo "ERROR: Bazel JaCoCo LCOV reporting failed" >&2
    FINAL_EXIT=1
  fi
fi

rm -rf "$REPORTS_DIR"
rm -f "$DIRECT_JARS_FILE" "$COVERAGE_JARS_FILE"
if [ -n "$COVERAGE_EXEC_FILE" ]; then
  rm -f "$COVERAGE_EXEC_FILE"
fi
exit "$FINAL_EXIT"
