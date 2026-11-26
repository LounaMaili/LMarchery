"""Sceller le train et les témoins du futur essai à partir des annotations et archives."""
from collections import Counter
from pathlib import Path

import cv2 as cv
import numpy as np

from r19.donnees import fingerprint, input_size, read, save
from r25.commun import SEEDS
from r26.archives import ROOT


def prepare(out=ROOT):
    specs={row['photo']:row for row in read('r19/donnees.json')['photos'] if row['partition']=='train'}
    valid={v['photo']:v for v in read('r18/validites.json')['vues'] if v['partition']=='train'}
    source={v['photo']:v for v in read('r18/sources.json') if v['photo'] in specs}
    inventory=read('r21/inventaires.json')
    caps=read('r20/plafonds.json')
    draws=Path('r21/tirages.json')
    schedule=read(draws)
    registry={}
    photos=[]
    for k,spec in specs.items():
        v=valid[k]
        png=Path('r18/masques')/(k+'.png')
        annotation=Path('r18/annotations')/(k+'.json')
        if fingerprint(png)!=spec['sha256_masque'] or fingerprint(annotation)!=spec['sha256_annotation']:
            raise ValueError('Annotation R18 modifiée '+k)
        mask=cv.imread(str(png),cv.IMREAD_UNCHANGED)
        if mask.dtype!=np.uint8 or tuple(mask.shape[1::-1])!=tuple(spec['dimensions_travail']):
            raise ValueError('Carte indexée différente')
        instances={}
        for i in v['instances']:
            if not i['masque_valide']:
                continue
            n=i['instance']
            yy,xx=np.nonzero(mask==i['indice'])
            if len(xx)!=i['pixels_masque_valides'] or len(xx)!=caps[k]['instances'][n]['positifs_natifs']:
                raise ValueError('Aire de référence incohérente '+n)
            instances[n]=dict(indice=i['indice'],positifs=len(xx),boite_xyxy=[int(xx.min()),int(yy.min()),int(xx.max())+1,int(yy.max())+1],
                        fragments_8=i['composantes_raster_8'],axe_valide=i['axe_valide'],entree_valide=i['entree_valide'],
                        etat_entree=i['etat_entree'],dice_plafond_R20=caps[k]['instances'][n]['dice_max'])
        areas=dict(fut=sum(i['positifs'] for i in instances.values()),fond_verifie=int((mask==0).sum()),ignores=int((mask==255).sum()))
        if sum(areas.values())!=mask.size or any(areas[a]!=v['pixels'][a] for a in areas):
            raise ValueError('Classes natives incohérentes '+k)
        registry[k]=dict(provenance=spec['provenance'],supports=spec['supports'],conditions=spec['conditions'],
                         dimensions=spec['dimensions_travail'],entree_modele_xy=list(input_size(spec['dimensions_travail'])),
                         inventaire_R21_complet=inventory[k]['admissible'],documentaires_sans_cible=inventory[k]['documentaires_sans_cible'],
                         pixels=areas,pixels_conflit_deja_ignores=v['pixels']['conflits'],instances=instances,
                         annotation=str(annotation),sha256_annotation=fingerprint(annotation),masque=str(png),sha256_masque=fingerprint(png),
                         sha256_BGR_travail=spec['sha256_BGR_travail'])
        ref=source[k]
        if (ref['sha256_octets_reference'],ref['emplacement_reference'])!=(spec['sha256_reference'],spec['fichier_reference']):
            raise ValueError('Capsule différente '+k)
        photos.append(dict(nom=Path(ref['emplacement_reference']).name,role='Photo train '+k,octets=ref['taille_reference'],
                           sha256=ref['sha256_octets_reference'],emplacement=ref['emplacement_reference'],droits=ref['droits']))
    protected={}
    totals=read(ROOT/'mesures/bilan.json')['etats']
    for seed in SEEDS:
        existing=set()
        for arm in ('dice','bloc8'):
            graphs=read(ROOT/'mesures'/f'{arm}-{seed}.json')['photos']
            for g in graphs.values():
                existing.update(g['appariements']['masques_31'])
        base=totals[f'dice-{seed}']['ensemble']
        protected[str(seed)]=dict(identites=sorted(existing),minimum_separes_si_conforme=len(existing)+6,
            maximum_fusions=3*base['sorties_fusionnees']//4,maximum_dedoublements=3*base['identites_dedoublees']//4)
    exposures={key:dict(Counter(r['photo'] for r in rows)) for key,rows in schedule.items()}
    if set(registry)!=set(inventory) or any(set(v)!=set(registry) or sum(v.values())!=768 for v in exposures.values()):
        raise ValueError('Plan de présentation différent')
    out.mkdir(parents=True,exist_ok=True)
    save(out/'train.json',dict(lecture='assistée, sans adjudication humaine indépendante',
            code_ignore=255,code_fond_verifie=0,photos=registry,tirages=str(draws),sha256_tirages=fingerprint(draws),expositions=exposures))
    save(out/'temoins.json',protected)
    inherited=read('r25/inventaire-local.json')['fichiers']
    save(out/'inventaire.json',dict(repere_chemins_photos='répertoire build/worktrees du dépôt',photos=photos,
         poids_historiques=[v for v in inherited if v['role'].startswith('Checkpoint final')],
         archives_R25='../r25/inventaire-local.json',empreintes_cartes='../r25/cartes/inventaire.json',
         poids_editeurs='poids-editeurs.json',nouvelle_capsule_locale=None))


if __name__=='__main__':
    prepare()
