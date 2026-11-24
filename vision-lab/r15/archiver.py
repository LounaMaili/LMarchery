"""Conserver les octets examinés et inventorier les pièces volumineuses hors Git."""
from pathlib import Path
import zipfile

from r15.corpus import LAB, fingerprint
from r15.recherche import ROOT, MEDIA, NOTICES, read, save


def write_zip(path,files):
    path.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(path,'w') as archive:
        for file in sorted(set(files)):
            info=zipfile.ZipInfo(file.relative_to(ROOT).as_posix(),(2026,11,24,0,0,0))
            info.compress_type=(zipfile.ZIP_STORED if file.suffix.lower() in ('.jpg','.png','.webm')
                                else zipfile.ZIP_DEFLATED)
            info.external_attr=0o644<<16
            archive.writestr(info,file.read_bytes())
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise ValueError(path)
        return len(archive.namelist())


def item(path,role):
    return dict(nom=path.name,role=role,octets=path.stat().st_size,sha256=fingerprint(path),emplacement=str(path.resolve()))


def main():
    photos=read(LAB/'lectures.json')['photos']
    selected={MEDIA/r['source']['fichier_travail'] for r in photos}
    selected.add(MEDIA/'wc82322984.webm')
    selected.add(NOTICES/'rf-key-overlay.png')
    documented=set(NOTICES.iterdir()) | set(ROOT.glob('*.json')) | set((ROOT/'prelevements').glob('*'))
    documented.update(MEDIA/(r['id']+'.jpg') for r in read(ROOT/'rf-exemples.json')+read(ROOT/'flickr-images.json'))
    documented.update(MEDIA/r['fichier'] for r in read(ROOT/'commons-images.json') if 'sha256' in r)
    documented.update(MEDIA.glob('shot-*.jpg'))
    documented.update((ROOT/'inspection').glob('*-frames.jpg'))
    archived=[]
    for name,files,role in [('r15-acquisitions.zip',selected,'Dix images admises, film source et calque Roboflow'),
                            ('r15-audit-web.zip',documented-selected,'Autres médias examinés, notices publiques, planches et relevés de lecture')]:
        path=Path('artifacts/r15')/name
        n=write_zip(path,files)
        archived.append(dict(item(path,role),membres=n))
    save(LAB/'inventaire-local.json',dict(capsules=archived,acquisitions=[
            item(MEDIA/r['source']['fichier_travail'],r['partition']+' / '+r['provenance']) for r in photos],
         film=item(MEDIA/'wc82322984.webm','Original du frame 550'),poids=read(LAB/'configuration.json')['poids'],
         historique=dict(index='r14/inventaire-pixels.json',sha256=fingerprint('r14/inventaire-pixels.json'),
                         capsules='r14/inventaire.json',sha256_capsules=fingerprint('r14/inventaire.json'))))


if __name__=='__main__':
    main()
