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
        if not path.resolve().is_relative_to(package.resolve()) or path.is_symlink() or not path.is_file() or path.stat().st_size!=item['bytes'] or sha(path)!=item['sha256']:
            raise ValueError('Package integrity failure: '+str(relative))
    return manifest


def package(archive, images, output, evidence, application_images=None):
    if output.exists():raise ValueError('Output must be a new directory')
    if output.resolve().is_relative_to(evidence.resolve()):raise ValueError('Package output must be outside its evidence directory')
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
        configuration=json.loads(command('docker','compose','--profile','*','config','--format','json',capture_output=True,text=True).stdout)
        applications={name for name,service in configuration['services'].items() if service.get('build')}
        application_images=application_images or {}
        if set(application_images)!=applications:raise ValueError('Pin every application service, including bootstrap, without replacing shared image tags')
        required_images={service['image'] for service in configuration['services'].values() if service.get('image') and not service.get('build')}|set(application_images.values())|{'grafana/grafana:11.2.0'}
        if not required_images.issubset(set(images)):raise ValueError('Every Compose release image must be supplied')
        # A supplied tag must resolve locally. Never substitute a mock image or silently pull a different release.
        inspected=json.loads(command('docker','image','inspect',*images,capture_output=True,text=True).stdout)
        if len(inspected)!=len(images):raise ValueError('Missing release image')
        for image,item in zip(images,inspected):
            if image in application_images.values() and item.get('Config',{}).get('Labels',{}).get('org.opencontainers.image.revision')!=commit:
                raise ValueError('Application image is not pinned to the source commit: '+image)
        output.mkdir(mode=0o700)
        command('git','archive','--format=tar','--output='+str(output/'source.tar'),commit)
        shutil.copytree(temporary,output/'reference')
        shutil.copytree(evidence,output/'evidence')
        with tarfile.open(output/'dashboard.tar','w') as tar:tar.add(required[0].parent,arcname='browser')
        for path in required[1:]:shutil.copyfile(path,output/path.name)
        command('docker','image','save','--output',str(output/'images.tar'),*images)
        (output/'compose.release.yaml').write_text(json.dumps({'services':{name:{'image':image,'pull_policy':'never','build':None} for name,image in application_images.items()}},indent=2)+'\n')
        files=[{'file':str(path.relative_to(output)),'bytes':path.stat().st_size,'sha256':sha(path)}
               for path in sorted(output.rglob('*')) if path.is_file()]
        # Secrets, local environment and volumes are deliberately outside git archive.
        result={'sourceCommit':commit,'datasetId':manifest['dataset_id'],'datasetManifestSha256':manifest_sha,
                'nativeRows':rows,'applicationImages':application_images,'images':[{'id':item['Id'],'digests':item.get('RepoDigests',[]),'architecture':item['Architecture']} for item in inspected],
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
    parser.add_argument('--application-image',action='append',default=[],help='Service=immutable-image mapping; bootstrap may reuse the processor image')
    parser.add_argument('--output',type=Path)
    parser.add_argument('--evidence',type=Path)
    args=parser.parse_args()
    if args.verify:verify(args.verify)
    elif not all([args.archive,args.image,args.output,args.evidence]):parser.error('Archive, images, output and evidence are required')
    else:
        mappings=dict(item.split('=',1) for item in args.application_image)
        if len(mappings)!=len(args.application_image):parser.error('Duplicate application service mapping')
        package(args.archive,args.image,args.output.resolve(),args.evidence.resolve(),mappings)
    print('Package integrity verified. Pending acceptance remains pending.')
