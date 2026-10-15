#!/usr/bin/env python3
"""Assemble a checksum-pinned offline package; missing inputs fail before a release manifest exists."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

ROOT=Path(__file__).resolve().parents[1]


def command(*args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, **kwargs)


def sha(path):
    result=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):result.update(block)
    return result.hexdigest()


def verify(package):
    manifest=json.loads((package/'release-manifest.json').read_text())
    for item in manifest['files']:
        relative=Path(item['file'])
        if relative.is_absolute() or '..' in relative.parts:raise ValueError('Unsafe package member')
        path=package/relative
        if path.is_symlink() or not path.is_file() or path.stat().st_size!=item['bytes'] or sha(path)!=item['sha256']:
            raise ValueError('Package integrity failure: '+str(relative))
    return manifest


def package(archive, images, output, evidence):
    if output.exists():raise ValueError('Output must be a new directory')
    if not images or any(not image or image.startswith('-') for image in images):raise ValueError('Explicit release images are required')
    command('git','diff','--quiet','HEAD','--')
    commit=command('git','rev-parse','HEAD',capture_output=True,text=True).stdout.strip()
    # Reuse the exact full-source validator; a bounded slice never qualifies.
    spec=importlib.util.spec_from_file_location('trace_archive',ROOT/'scripts/import-trace-link-reference.py')
    importer=importlib.util.module_from_spec(spec);spec.loader.exec_module(importer)
    with tempfile.TemporaryDirectory(prefix='trace-release-source-') as temporary:
        manifest,manifest_sha=importer.validate(archive,Path(temporary))
        rows=sum(1 for _ in importer.native_rows(Path(temporary),manifest))
        if rows!=362880:raise ValueError('Full archive is incomplete')
        required=[ROOT/'apps/dashboard/dist/dashboard/browser/index.html',
                  ROOT/'services/incident-service/target/incident-service-0.0.1-SNAPSHOT.jar',
                  ROOT/'services/event-generator/target/event-generator-0.3.0-SNAPSHOT.jar',
                  ROOT/'services/processor/target/processor-0.3.0-SNAPSHOT.jar']
        if any(not path.is_file() for path in required) or not evidence.is_dir():raise ValueError('Verified build assets and release evidence are required')
        if any(path.is_symlink() for path in evidence.rglob('*')):raise ValueError('Evidence must not contain symlinks')
        required_images=set(command('docker','compose','--profile','*','config','--images',capture_output=True,text=True).stdout.splitlines())|{'grafana/grafana:11.2.0'}
        if not required_images.issubset(set(images)):raise ValueError('Every Compose release image must be supplied')
        # A supplied tag must resolve locally. Never substitute a mock image or silently pull a different release.
        inspected=json.loads(command('docker','image','inspect',*images,capture_output=True,text=True).stdout)
        if len(inspected)!=len(images):raise ValueError('Missing release image')
        for image,item in zip(images,inspected):
            if not image.startswith(('postgres:','quay.io/keycloak/keycloak:','nginx:','apache/kafka:','grafana/grafana:')) and item.get('Config',{}).get('Labels',{}).get('org.opencontainers.image.revision')!=commit:
                raise ValueError('Application image is not pinned to the source commit: '+image)
        output.mkdir(mode=0o700)
        command('git','archive','--format=tar','--output='+str(output/'source.tar'),commit)
        shutil.copytree(temporary,output/'reference')
        shutil.copytree(evidence,output/'evidence')
        with tarfile.open(output/'dashboard.tar','w') as tar:tar.add(required[0].parent,arcname='browser')
        for path in required[1:]:shutil.copyfile(path,output/path.name)
        command('docker','image','save','--output',str(output/'images.tar'),*images)
        files=[{'file':str(path.relative_to(output)),'bytes':path.stat().st_size,'sha256':sha(path)}
               for path in sorted(output.rglob('*')) if path.is_file()]
        # Secrets, local environment and volumes are deliberately outside git archive.
        result={'sourceCommit':commit,'datasetId':manifest['dataset_id'],'datasetManifestSha256':manifest_sha,
                'nativeRows':rows,'images':[{'id':item['Id'],'digests':item.get('RepoDigests',[]),'architecture':item['Architecture']} for item in inspected],
                'files':files,'humanAcceptance':'PENDING','ownerSignatures':[],
                'rehearsals':[],'securePushAcceptance':'PENDING','sharedPushEnabled':False,
                'offlineCleanInstallAcceptance':'PENDING','releaseGatesPassed':False}
        (output/'release-manifest.json').write_text(json.dumps(result,indent=2)+'\n')
        verify(output)
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify',type=Path)
    parser.add_argument('--archive',type=Path)
    parser.add_argument('--image',action='append')
    parser.add_argument('--output',type=Path)
    parser.add_argument('--evidence',type=Path)
    args=parser.parse_args()
    if args.verify:verify(args.verify)
    elif not all([args.archive,args.image,args.output,args.evidence]):parser.error('Archive, images, output and evidence are required')
    else:package(args.archive,args.image,args.output.resolve(),args.evidence.resolve())
    print('Package integrity verified. Pending acceptance remains pending.')
