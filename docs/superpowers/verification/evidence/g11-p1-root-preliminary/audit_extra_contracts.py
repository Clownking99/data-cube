"""Independent, synthetic-only P1 contract probes against frozen tool bytes."""
import argparse
import hashlib
import importlib.util
import json
import pathlib
import uuid
import xml.etree.ElementTree as ET
from unittest.mock import patch


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tool', required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    tool = pathlib.Path(args.tool)
    if hashlib.sha256(tool.read_bytes()).hexdigest() != args.sha256:
        raise RuntimeError('FROZEN_TOOL_IDENTITY_MISMATCH')
    spec = importlib.util.spec_from_file_location('frozen_evidence', tool)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    out = pathlib.Path(args.out).absolute()
    out.mkdir(exist_ok=False)
    results = []

    def rejects(name, call, expected):
        try:
            call()
        except (module.Refusal, ET.ParseError) as error:
            if expected not in str(error):
                raise RuntimeError(f'WRONG_REFUSAL:{name}:{error}') from error
            results.append({'case': name, 'expectedRefusal': expected, 'actual': str(error)})
        else:
            raise RuntimeError('MISSING_REFUSAL:' + name)

    # Invalid strings only: no forbidden directory is created or inspected.
    bad_paths = [r'C:\never\.testagent\x', r'C:\never\.TESTAGENT\x',
                 r'C:\never\.testagent \x', r'C:\never\.testagent.\x',
                 r'C:\never\NUL.txt', r'C:\never\x:stream', r'C:\never\..\x']
    for index, value in enumerate(bad_paths):
        with patch.object(pathlib.Path, 'lstat', side_effect=RuntimeError('UNEXPECTED_PATH_IO')) as io:
            try:
                module.no_links(pathlib.Path(value))
            except module.Refusal as error:
                results.append({'case': f'pre-io-rejection-{index}', 'actual': str(error), 'lstatCalls': io.call_count})
            else:
                raise RuntimeError('INVALID_PATH_ACCEPTED')
            if io.call_count:
                raise RuntimeError('PATH_ACCESSED_BEFORE_REFUSAL')

    def xml_case(name, attributes, body):
        directory = out / name
        directory.mkdir()
        (directory / 'TEST-probe.xml').write_text(
            '<testsuite name="probe" ' + attributes + '>' + body + '</testsuite>', encoding='utf-8')
        return directory

    passed = '<testcase classname="Synthetic" name="case"/>'
    for field in ('tests', 'failures', 'errors', 'skipped'):
        counts = {'tests': 1, 'failures': 0, 'errors': 0, 'skipped': 0}
        counts[field] += 1
        directory = xml_case('mismatch-' + field, ' '.join(f'{k}="{v}"' for k, v in counts.items()), passed)
        rejects('mismatch-' + field, lambda directory=directory: module.xml_results(directory), 'XML_COUNT_MISMATCH')
    negative = xml_case('negative-count', 'tests="-1" failures="0" errors="0" skipped="0"', '')
    rejects('negative-count', lambda: module.xml_results(negative), 'INVALID_XML_COUNT')
    duplicate = xml_case('duplicate-suite', 'tests="1" failures="0" errors="0" skipped="0"', passed)
    (duplicate / 'TEST-copy.xml').write_bytes((duplicate / 'TEST-probe.xml').read_bytes())
    rejects('duplicate-suite', lambda: module.xml_results(duplicate), 'DUPLICATE_SUITE')
    cap = xml_case('byte-cap', 'tests="1" failures="0" errors="0" skipped="0"', passed)
    rejects('xml-byte-cap', lambda: module.xml_results(cap, file_cap=1), 'XML_BYTE_LIMIT')
    both = xml_case('error-and-failure', 'tests="1" failures="1" errors="1" skipped="0"',
                    '<testcase classname="Synthetic" name="case"><failure/><error/></testcase>')
    rejects('error-and-failure', lambda: module.xml_results(both), 'CONTRADICTORY_CASE')
    skip = xml_case('skip-reason', 'tests="2" failures="0" errors="0" skipped="2"',
                    '<testcase classname="Synthetic" name="same"><skipped/></testcase>'
                    '<testcase classname="Synthetic" name="same"><skipped message="explicit reason"/></testcase>')
    summary = module.xml_results(skip)
    if summary['skipped'] != 2 or summary['passed'] != 0 or [c['ordinal'] for c in summary['cases']] != [0, 1]:
        raise RuntimeError('SKIP_IDENTITY_OR_COUNT')
    if not summary['cases'][0]['reason'] or 'explicit reason' not in summary['cases'][1]['reason']:
        raise RuntimeError('SKIP_REASON_LOST')
    results.append({'case': 'empty-and-message-skip', 'result': summary})

    repo = out / ('synthetic-repo-' + uuid.uuid4().hex)
    (repo / 'test').mkdir(parents=True)
    for name in ('A.java', 'B.java'):
        (repo / 'test' / name).write_text('class ' + name[:-5] + ' {}', encoding='utf-8')
    binding = module.snapshot(str(repo), ['test/A.java', 'test/B.java'], 'a' * 40)
    types = module.types(binding)
    if types['sourceCount'] != 2 or types['typeCount'] != 2:
        raise RuntimeError('SOURCE_COVERAGE')
    results.append({'case': 'full-test-type-coverage', 'result': types})
    rejects('omitted-test-source', lambda: module.snapshot(str(repo), ['test/A.java'], 'a' * 40), 'INPUT_SET_MISMATCH')
    rejects('case-alias-duplicate', lambda: module.snapshot(str(repo), ['test/A.java', 'test/a.java'], 'a' * 40), 'DUPLICATE_INPUT')
    (repo / 'gradle.properties').write_text('# synthetic optional file', encoding='utf-8')
    rejects('optional-input-appeared', lambda: module.snapshot(str(repo), ['test/A.java', 'test/B.java'], 'a' * 40), 'INPUT_SET_MISMATCH')
    source_repo = out / 'source-only-repo'
    (source_repo / 'src').mkdir(parents=True)
    (source_repo / 'src/A.java').write_text('class A {}', encoding='utf-8')
    only_source = module.snapshot(str(source_repo), ['src/A.java'], 'a' * 40)
    rejects('empty-test-types', lambda: module.types(only_source), 'INVALID_TEST_INVENTORY')
    result = {'schema': 'independent-contract-audit/v1', 'tool': str(tool),
              'toolSha256': args.sha256, 'results': results, 'passed': True,
              'limits': ['synthetic Python contracts only; no Gradle or real service', 'reparse point fixture not included']}
    (out / 'receipt.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'cases': len(results), 'passed': True, 'receipt': str(out / 'receipt.json')}))


if __name__ == '__main__':
    main()
