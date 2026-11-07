#!/usr/bin/env python3
"""Record build inventory and check shared inputs for the reviewed applications."""
import hashlib
import json
from pathlib import Path
import platform
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MODULES = ['javalin-jdbi3', 'javalin-mybatis', 'javalin-jooq', 'javalin-kaumei-jdbc']
SHARED = ['Quote.java', 'QuoteBatch.java', 'QuoteController.java', 'QuoteRepository.java',
          'QuoteState.java', 'RepositoryFailure.java', 'StatLiteMetrics.java']
SHARED_OVERRIDES = {
    'javalin-kaumei-jdbc': {
        'Quote.java': 'Kaumei JDBC result-column mapping annotation',
        'QuoteState.java': 'Kaumei JDBC result-column mapping annotation',
        'RepositoryFailure.java': 'Formatting-only difference',
    },
}
SHARED_DEPENDENCY_OVERRIDES = {
    'javalin-kaumei-jdbc': {
        'org.jspecify:jspecify': {
            'baseline': '1.0.0',
            'resolved': '1.0.1',
            'reason': 'Kaumei JDBC runtime dependency',
        },
    },
}
ADDITIONS = {
    'javalin-jdbi3': {'org.jdbi:jdbi3-core': '3.55.0', 'io.leangen.geantyref:geantyref': '2.0.1'},
    'javalin-mybatis': {'org.mybatis:mybatis': '3.5.19'},
    'javalin-jooq': {'org.jooq:jooq': '3.21.9', 'io.r2dbc:r2dbc-spi': '1.0.0.RELEASE',
                     'org.reactivestreams:reactive-streams': '1.0.3'},
    'javalin-kaumei-jdbc': {
        'io.kaumei.jdbc:jdbc-annotation': 'main-SNAPSHOT',
        'io.kaumei.jdbc:jdbc-runtime': 'main-SNAPSHOT',
        'io.kaumei.jdbc:jdbc-tx': 'main-SNAPSHOT',
        'org.jspecify:jspecify': '1.0.1',
    },
}


def dependencies(module):
    pattern = r'([\w.\-]+):([\w.\-]+):jar:([^: ]+):(compile|runtime)'
    return {f'{m[1]}:{m[2]}': m[3] for m in re.finditer(pattern, (module/'dependencies.txt').read_text())}


def lines(path):
    return sum(bool(line.strip()) and not line.lstrip().startswith(('//', '/*', '*'))
               for line in path.read_text().splitlines())


def main():
    first = ROOT/MODULES[0]
    for name in SHARED:
        expected = (first/'src/main/java/lab'/name).read_bytes()
        for module in MODULES[1:]:
            if name in SHARED_OVERRIDES.get(module, {}):
                continue
            assert (ROOT/module/'src/main/java/lab'/name).read_bytes() == expected, (module, name)
    fixture = (first/'src/main/resources/quotes.csv').read_bytes()
    common = None
    kaumei_source = (ROOT/'my_kaumei-jdbc').resolve()
    report = {'status': 'New reproduction build; not an archived measured binary',
              'host': platform.system(),
              'java': subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT, text=True).strip(),
              'external_sources': {
                  'kaumei_jdbc': {
                      'commit': subprocess.check_output(
                          ['git', '-C', str(kaumei_source), 'rev-parse', 'HEAD'], text=True).strip(),
                      'dirty': bool(subprocess.check_output(
                          ['git', '-C', str(kaumei_source), 'status', '--porcelain'], text=True).strip()),
                  },
              },
              'shared_source_and_fixture_identical': False,
              'shared_source_identical_except_declared_overrides': True,
              'shared_source_overrides': SHARED_OVERRIDES,
              'shared_dependency_overrides': SHARED_DEPENDENCY_OVERRIDES,
              'fixture_identical': True,
              'line_count_method': 'Nonblank physical Java lines excluding full-line comments; formatting affects counts. Not a complexity score.',
              'applications': {}}
    for name in MODULES:
        module = ROOT/name
        assert (module/'src/main/resources/quotes.csv').read_bytes() == fixture, name
        resolved = dependencies(module)
        assert all(resolved.get(k) == v for k, v in ADDITIONS[name].items()), name
        shared = {k: v for k, v in resolved.items() if k not in ADDITIONS[name]}
        if common is None:
            common = shared
        overrides = SHARED_DEPENDENCY_OVERRIDES.get(name, {})
        expected_common = {k: v for k, v in common.items() if k not in overrides}
        assert shared == expected_common, f'Shared dependency versions differ: {name}'
        jar = module/'target'/f'{name}-1.0.jar'
        sources = module/'src/main/java'
        generated = (module/'target/generated-sources/annotations'
                     if name == 'javalin-kaumei-jdbc'
                     else module/'target/generated-sources/jooq')
        generated_files = sorted(generated.rglob('*.java')) if generated.exists() else []
        if name == 'javalin-jooq':
            generated_classes = list((module/'target/classes/lab/jooq/generated').rglob('*.class'))
        elif name == 'javalin-kaumei-jdbc':
            generated_classes = list((module/'target/classes/lab').glob('*$Jdbc*.class'))
        else:
            generated_classes = []
        report['applications'][name] = {
            'jar': str(jar.relative_to(ROOT)), 'jar_bytes': jar.stat().st_size,
            'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
            'handwritten_java_lines_excluding_adapter': sum(lines(p) for p in sources.rglob('*.java') if p.name != 'StatLiteMetrics.java'),
            'monitoring_adapter_lines': lines(sources/'lab/StatLiteMetrics.java'),
            'generated_java_files': len(generated_files),
            'generated_java_lines': sum(lines(p) for p in generated_files),
            'generated_class_bytes': sum(p.stat().st_size for p in generated_classes),
            'library_specific_dependencies': ADDITIONS[name],
            'resolved_runtime_dependencies': resolved,
        }
    report['shared_runtime_dependencies'] = common
    destination = ROOT/'reproduction-inventory.json'
    destination.write_text(json.dumps(report, indent=2) + '\n')
    print('PASS: identical fixture and shared inputs except declared source/dependency overrides')
    for name, app in report['applications'].items():
        print(f"{name}: {app['jar_bytes']:,} bytes; {app['handwritten_java_lines_excluding_adapter']} handwritten Java lines; {app['generated_java_lines']} generated lines")


if __name__ == '__main__':
    main()
