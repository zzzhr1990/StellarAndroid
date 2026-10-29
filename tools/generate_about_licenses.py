"""Refresh the checked-in runtime license catalogue; never runs during app startup.

First run: gradlew -I tools/about-dependencies.init.gradle :app:exportAboutDependencies
Then: python tools/generate_about_licenses.py
Uses resolved Maven POMs and notices in the actual JAR/AAR, not build/test dependencies.
Native/resource entries are reviewed separately in about-native-libraries.json.
"""
from pathlib import Path
import hashlib
import io
import json
import re
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'
WORK = ROOT / 'app/build/reports/about-metadata'
ASSETS = ROOT / 'app/src/main/assets/licenses'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
WORK.mkdir(parents=True, exist_ok=True)
ASSETS.mkdir(parents=True, exist_ok=True)


def download(url, destination):
    if not destination.exists():
        request = urllib.request.Request(url, headers={'User-Agent': 'StellarPlayer-license-inventory'})
        with urllib.request.urlopen(request, timeout=45) as response:
            destination.write_bytes(response.read())
    return destination


def pom(group, artifact, version):
    cached = list((CACHE / group / artifact / version).glob('*/*.pom'))
    if cached:
        return ET.parse(cached[0]).getroot()
    repository = 'https://dl.google.com/dl/android/maven2/' if group.startswith(('androidx.', 'com.google.android.gms')) else 'https://repo.maven.apache.org/maven2/'
    path = f'{group.replace(".", "/")}/{artifact}/{version}/{artifact}-{version}.pom'
    return ET.parse(download(repository + path, WORK / f'{group}-{artifact}-{version}.pom')).getroot()


def metadata(group, artifact, version, depth=0):
    assert depth < 8, 'POM inheritance loop'
    root = pom(group, artifact, version)
    text = lambda path: root.findtext(path, default='', namespaces=NS).strip()
    licenses = [{'name': x.findtext('m:name', default='', namespaces=NS),
                 'url': x.findtext('m:url', default='', namespaces=NS)} for x in root.findall('m:licenses/m:license', NS)]
    url = text('m:url')
    if not licenses and text('m:parent/m:artifactId'):
        parent = metadata(text('m:parent/m:groupId'), text('m:parent/m:artifactId'), text('m:parent/m:version'), depth + 1)
        licenses = parent['licenses']
        url = url or parent['url']
    # This POM omits the license; use the upstream project's MIT notice.
    if group == 'com.microsoft.device.display' and artifact == 'display-mask':
        licenses = [{'name': 'MIT License', 'url': 'https://github.com/microsoft/surface-duo-sdk/blob/main/LICENSE'}]
    if group == 'javax.inject':
        licenses = [{'name': 'Apache-2.0', 'url': 'https://github.com/javax-inject/javax-inject/blob/master/LICENSE'}]
    assert licenses, f'Missing license: {group}:{artifact}:{version}'
    properties = {'project.groupId': group, 'project.artifactId': artifact, 'project.version': version}
    for k, v in properties.items():
        url = url.replace('${' + k + '}', v)
    if not url.startswith(('https://', 'http://')) or '${' in url:
        url = licenses[0]['url']
    return {'name': text('m:name') or artifact, 'url': url, 'licenses': licenses}


def save_notice(data):
    # Keep original bytes; duplicate upstream texts share a content-addressed file.
    data.decode('utf-8')
    name = 'notices/' + hashlib.sha256(data).hexdigest()[:24] + '.txt'
    destination = ASSETS / name
    destination.parent.mkdir(exist_ok=True)
    destination.write_bytes(data)
    return name


def archive_notices(data):
    result = []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in archive.namelist():
            leaf = name.rsplit('/', 1)[-1].lower()
            if re.match(r'^(license|licence|notice|copying)([._-]|$)', leaf) or leaf.endswith('.license'):
                content = archive.read(name)
                try:
                    result.append(save_notice(content))
                except UnicodeDecodeError:
                    raise ValueError(f'Non-UTF8 notice requires review: {name}')
            elif name == 'classes.jar':
                result.extend(archive_notices(archive.read(name)))
    return list(dict.fromkeys(result))


def license_id(name):
    lower = name.lower()
    if 'apache' in lower:
        return 'Apache-2.0'
    if lower.startswith('mit'):
        return 'MIT'
    if 'mozilla' in lower:
        return 'MPL-2.0'
    if 'lesser' in lower:
        return 'LGPL-2.1-or-later'
    if 'bsd' in lower:
        return name
    return name


def generate():
    runtime = json.loads((ROOT / 'app/build/reports/about-dependencies.json').read_text())
    entries = []
    for artifact in runtime:
        group, name, version = artifact['id'].split(':')
        info = metadata(group, name, version)
        notices = archive_notices(Path(artifact['file']).read_bytes())
        licenses = [license_id(x['name']) for x in info['licenses']]
        if 'Apache-2.0' in licenses:
            notices.append('material-symbols-LICENSE.txt')
        if 'MPL-2.0' in licenses:
            notices.append('MPL-2.0.txt')
        if 'LGPL-2.1-or-later' in licenses:
            notices.append('LGPL-2.1.txt')
        supplemental = {
            'com.microsoft.device.display:display-mask': 'microsoft-display-MIT.txt',
            'com.microsoft.identity.client:msal': 'msal-MIT.txt',
            'com.microsoft.identity:common': 'microsoft-common-MIT.txt',
            'com.microsoft.identity:common4j': 'microsoft-common-MIT.txt',
        }.get(f'{group}:{name}')
        if supplemental:
            notices.append(supplemental)
        entries.append({'id': f'{group}:{name}', 'name': info['name'], 'version': version,
                        'license': ' / '.join(licenses), 'url': info['url'],
                        'licenseUrl': next((x['url'] for x in info['licenses'] if x['url']), info['url']),
                        'notices': list(dict.fromkeys(notices)),
                        'other': any('Android Software Development Kit' in x for x in licenses)})
    native = json.loads((ROOT / 'tools/about-native-libraries.json').read_text(encoding='utf-8'))
    entries = native + entries
    ids = [entry['id'] for entry in entries]
    assert len(ids) == len(set(ids)), 'Duplicate component IDs'
    for entry in entries:
        for notice in entry['notices']:
            assert (ASSETS / notice).is_file(), f'Missing {notice}'
        assert entry['url'].startswith(('https://', 'http://')), entry
    forbidden = [e['id'] for e in entries if e['id'].startswith(('com.google.android.gms:', 'org.jupnp:', 'com.google.android.libraries.identity.')) or e['other'] and e['id'] != 'tmdb-brand']
    assert not forbidden, f'Non-open-source runtime dependency: {forbidden}'
    (ASSETS / 'catalog.json').write_text(json.dumps(entries, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Packaged {len(native)} native/resource entries + {len(runtime)} Maven runtime artifacts.')


if __name__ == '__main__':
    generate()
