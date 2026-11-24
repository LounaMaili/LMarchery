"""Compter les instances et la cooccurrence des annotations sur la même photographie."""
import argparse
from collections import Counter,defaultdict
from pathlib import Path

import cv2 as cv
import numpy as np

from r14.mesurer import read,save
from r15.corpus import corpus
from r16.donnees import crosses_ignored,scaling

LAB=Path('r17')
PARTS={'train':'train','dev_indep':'dev_explore_R16','dev_explore':'dev_explore_anterieur'}


def source(p):
    s=p['source']
    return dict(credit=s.get('credit',p['provenance']),notice=s.get('url') or s['source'],
                licence_image=s.get('licence_image') or s.get('license') or s.get('licence') or s.get('terms'),
                conditions=s.get('terms') or s.get('licence_image') or s.get('licence'),
                annotation_source=s.get('licence_ann_source') or ('CC BY 4.0 · LabelMe source' if p['provenance']=='Crigges' else None),
                lecture=s.get('annotation') or 'Lecture ou reprise documentaire du laboratoire',
                fichier_source=s.get('source_file') or s.get('fichier_travail'))


def face(p,t):
    k=p['id']
    if k=='18105652008':
        return 'Sac au bison, à gauche' if t['instance'].endswith((':1',':2')) else 'Sac à la silhouette sombre, à droite'
    if k=='fl51204504370':
        return 'Face de droite, selon la lecture R15'
    if k=='wc16492853':
        return 'Mato de gauche, selon la lecture R15'
    if k=='wc82322984-t22':
        return 'Feuille de gauche' if t['instance'].endswith((':1',':2')) else 'Feuille de droite'
    return p['supports'][0] if len(p['supports'])==1 else None


def polygon_ignored(points,p):
    if not points or not p['regions_ignorees']:
        return False
    poly=np.array(points)
    factor=scaling(p)
    if any(crosses_ignored(np.array([a,b])*factor,p) for a,b in zip(poly,np.roll(poly,-1,axis=0),strict=True)):
        return True
    return any(cv.pointPolygonTest(poly.astype(np.float32),tuple(lo),False)>=0 for lo,_ in p['regions_ignorees'])


def counts(instances,acquisitions):
    annotated=[a for a in instances if a['vues_annotees']]
    result=Counter()
    for a in annotated:
        for key in a['disponibilite']:
            result[key]+=a['disponibilite'][key]
    result.update(dict(acquisitions=len(acquisitions),provenances=len({p['provenance'] for p in acquisitions}),
                       acquisitions_occupees_annotees=sum(p['occurrences_annotees']>0 for p in acquisitions),
                       provenances_occupees=len({p['provenance'] for p in acquisitions if p['occurrences_annotees']}),
                       instances_annotees=len(annotated),identites_mention_seule=len(instances)-len(annotated),
                       occurrences_annotees=sum(len(a['vues_annotees']) for a in annotated),
                       identites_multivues_annotees=sum(len(a['vues_annotees'])>1 for a in annotated),
                       mentions_sans_geometrie_dans_la_vue=sum(len(a['mentions_seule_dans_la_vue']) for a in instances)))
    return dict(result)


def inventory(out):
    seq=read(LAB/'historique-sequences.json')['photos']
    masks=read(LAB/'polygones.json')['polygones']
    corrections=read(LAB/'corrections-v2.json')['corrections']
    records={}
    photos={}
    def subject(ident,p):
        if ident not in records:
            records[ident]=dict(instance=ident,provenance=p['provenance'],partition_R15=p['partition'],
                usage_R17=PARTS[p['partition']],sequence=seq.get(p['id'],{}).get('sequence'),
                vues_annotees=[],mentions_seule_dans_la_vue=[])
        a=records[ident]
        if a['provenance']!=p['provenance'] or a['usage_R17']!=PARTS[p['partition']]:
            raise ValueError('Identité partagée entre partitions')
        return a
    for p in corpus():
        k=p['id']
        prior=seq.get(k,{}).get('previous_instances',[])
        actual={t['instance'] for t in p['tubes']}
        photo=dict(photo=k,provenance=p['provenance'],supports=p['supports'],partition_R15=p['partition'],
            usage_R17=PARTS[p['partition']],dimensions_travail=p['taille'],sha256_pixels=p['sha256_pixels'],
            etapes=p['etapes'],inventaire_complet_dans_la_vue=p['complet'],regions_ignorees=p['regions_ignorees'],
            conditions=p['conditions'],droits=source(p),occurrences_annotees=len(actual),
            instances_annotees=sorted(actual),mentions_sans_geometrie=[i for i in prior if i not in actual])
        photos[k]=photo
        for ident in photo['mentions_sans_geometrie']:
            subject(ident,p)['mentions_seule_dans_la_vue'].append(k)
        for t in p['tubes']:
            ident=t['instance']
            versions=[key for step in ('R7','R8') if (key:=f'{step}/{k}/{ident}') in masks]
            if bool(versions)!=t['masque_source']:
                raise ValueError(f'Polygone non retrouvé {k}/{ident}')
            xy=t['entree']
            traced=t['axe']
            etat='visible' if xy is not None else 'occultee' if t['statut']=='occluded' else 'inconnue_ou_ambigue'
            if t['contact_visible']!=(xy is not None):
                raise ValueError(ident)
            a=subject(ident,p)
            a['vues_annotees'].append(dict(acquisition=k,face=face(p,t),supports_acquisition=p['supports'],
                droit_source=k,partie_visible='trait_et_polygone' if traced and versions else
                'portion_tracee' if traced else 'silhouette_source_sans_axe' if versions else 'simple_mention',
                axe=traced,origine_axe=t.get('lecture_axe') or ('lecture archivée' if traced else None),
                masques_versions=versions,masque_reference=versions[-1] if versions else None,
                boite_instance_documentee=None,entree=xy,etat_entree=etat,statut_lecture_source=t['statut'],
                precision_source_px=t.get('precision_lecture_px'),observation_source=t.get('observation'),
                axe_touche_ignore=crosses_ignored(np.array(traced)*scaling(p),p) if traced else None,
                entree_en_zone_ignoree=any(all(a<=v<=b for a,v,b in zip(lo,xy,hi,strict=True))
                    for lo,hi in p['regions_ignorees']) if xy is not None else None,
                masque_touche_ignore=polygon_ignored(masks[versions[-1]]['points_travail'],p) if versions else None,
                propositions_v2=[s['id'] for s in corrections if (s['photo'],s['instance'])==(k,ident)]))
    for a in records.values():
        views=a['vues_annotees']
        signatures=[]
        for v in views:
            ax,mask,vis=bool(v['axe']),bool(v['masque_reference']),v['etat_entree']=='visible'
            signatures.append(dict(avec_axe=ax,avec_masque=mask,entree_visible=vis,
                axe_et_entree_meme_vue=ax and vis,masque_et_entree_meme_vue=mask and vis,
                axe_et_masque_meme_vue=ax and mask,axe_masque_entree_meme_vue=ax and mask and vis,
                axe_et_entree_hors_ignore=ax and vis and not(v['axe_touche_ignore'] or v['entree_en_zone_ignoree']),
                masque_et_entree_hors_ignore=mask and vis and not(v['masque_touche_ignore'] or v['entree_en_zone_ignoree']),
                axe_masque_entree_hors_ignore=ax and mask and vis and not(v['axe_touche_ignore'] or v['entree_en_zone_ignoree'] or v['masque_touche_ignore'])))
        a['disponibilite']={k:any(s[k] for s in signatures) for k in signatures[0]} if signatures else {}
        if views:
            a['disponibilite'].update(sans_axe=not any(v['axe'] for v in views),sans_masque=not any(v['masque_reference'] for v in views),
                sans_entree_visible=not any(v['entree'] for v in views),
                occultation_explicite_au_moins_une_vue=any(v['etat_entree']=='occultee' for v in views),
                entree_inconnue_au_moins_une_vue=any(v['etat_entree']=='inconnue_ou_ambigue' for v in views))
        a['droits_par_acquisition']={p:photos[p]['droits'] for p in [v['acquisition'] for v in views]}
    out.mkdir(parents=True,exist_ok=True)
    save(out/'acquisitions.json',photos)
    save(out/'instances.json',list(records.values()))
    result={}
    for split in PARTS.values():
        ins=[a for a in records.values() if a['usage_R17']==split]
        pics=[p for p in photos.values() if p['usage_R17']==split]
        byprov={s:counts([a for a in ins if a['provenance']==s],[p for p in pics if p['provenance']==s])
                for s in sorted({p['provenance'] for p in pics})}
        result[split]=dict(total=counts(ins,pics),par_provenance=byprov)
    save(out/'effectifs-instances.json',result)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('out',type=Path)
    inventory(p.parse_args().out)
