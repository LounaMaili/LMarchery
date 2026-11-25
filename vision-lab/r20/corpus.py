"""Périmètre train et plafond exact du Dice pour la reprojection discrète de R19."""
import argparse
from collections import Counter
from fractions import Fraction
from itertools import combinations
from pathlib import Path

import cv2 as cv
import numpy as np

from r19.decoder import fragments
from r19.donnees import INITIAL, R18, SEEDS, WEIGHT, cell_indices, eval_size, fingerprint, input_size, read, save, work_to
from r19.mesures import axis_cover

ROOT = Path('r20')
STEPS = (0, 96, 256, 512, 1024)
MEMORY_SEED = 9301


def references():
    records = {r['photo']:r for r in read(R18/'validites.json')['vues'] if r['partition']=='train'}
    refs = {}
    for spec in read('r19/donnees.json')['photos']:
        if spec['partition'] != 'train':
            continue
        k = spec['photo']
        annotation = read(R18/'annotations'/(k+'.json'))
        row = records[k]
        size = np.array(spec['dimensions_travail'])
        view, grid = eval_size(size), np.array(input_size(size))//2
        entries, axes, ids = {}, {}, {}
        for i in row['instances']:
            name = i['instance']
            if i['entree_valide']:
                entries[name] = work_to(i['entree_supervisee'],size,view)
            if i['axe_valide']:
                axes[name] = [work_to(path,size,view) for path in i['axes_supervises']]
            if i['masque_valide']:
                ids[name] = i['indice']
        ignored = [work_to(r['polygone'],size,view).astype(np.float32)
                   for r in annotation['regions_ignorees'] if 'entree' in r['taches']]
        f = R18/'masques'/(k+'.png')
        mask = cv.imread(str(f),cv.IMREAD_UNCHANGED)
        if (fingerprint(f)!=spec['sha256_masque'] or fingerprint(R18/'annotations'/(k+'.json'))!=spec['sha256_annotation']
            or mask.dtype!=np.uint8 or list(mask.shape[1::-1])!=size.tolist()):
            raise ValueError('Annotation ou carte modifiée '+k)
        # Un inventaire de fûts peut être complet alors que la position d'une entrée demeure inconnue.
        complete = annotation['historique_R17_complet'] and len(entries)==len(row['instances'])
        refs[k] = dict(spec=spec,record=row,annotation=annotation,size=size,grid=grid,view=view,entries=entries,axes=axes,
                       ids=ids,mask=mask,ignored=ignored,empty=False,exhaustive=complete)
    if len(refs)!=9 or sum(len(r['ids']) for r in refs.values())!=31 or sum(len(r['entries']) for r in refs.values())!=26:
        raise ValueError('Le train scellé R18 a changé')
    return refs


def integral_ceiling(own, known):
    """Optimum sur préfixes de pureté p/n décroissante, certifié par inégalités entières."""
    own, known = np.asarray(own,dtype=np.int64), np.asarray(known,dtype=np.int64)
    if np.any((own<0)|(own>known)) or own.shape!=known.shape or not own.any():
        raise ValueError('Masses par cellule invalides')
    total = int(own.sum())
    histogram = Counter(zip(own[own>0].tolist(),known[own>0].tolist()))
    groups = {}
    for (p,n),count in histogram.items():
        groups.setdefault(Fraction(p,n),[]).append((p,n,count))
    positive = area = 0
    best_i = best_a = 0
    for purity, group in sorted(groups.items(),reverse=True):
        positive += sum(p*c for p,n,c in group)
        area += sum(n*c for p,n,c in group)
        if positive*(total+best_a)>best_i*(total+area):
            best_i,best_a = positive,area
    # Les cellules à égalité peuvent être omises. Les cellules tout-ignoré n'ajoutent aucun pixel évalué.
    selected = own*(total+best_a)>best_i*known
    if int(own[selected].sum())!=best_i or int(known[selected].sum())!=best_a:
        raise ValueError('Certificat de Dice non satisfait')
    return dict(positifs_natifs=total,intersection_optimale=best_i,aire_evaluable_sortie_optimale=best_a,
                denominateur_dice=total+best_a,cellules_optimales=int(selected.sum()),dice_max=2*best_i/(total+best_a),
                dice_requis=.9*2*best_i/(total+best_a),
                histogramme_p_n_effectif=[[p,n,c] for (p,n),c in sorted(histogram.items())]), selected


def ceiling_photo(ref):
    grid, size, mask = ref['grid'],ref['size'],ref['mask']
    cells = cell_indices(size,grid)
    n = np.bincount(cells[mask!=255],minlength=int(grid.prod()))
    output = {}
    for name,idx in ref['ids'].items():
        p = np.bincount(cells[mask==idx],minlength=len(n))
        row,optimal = integral_ceiling(p,n)
        row['largeur_moyenne_approx_cellules'] = None
        if name in ref['axes']:
            paths = [work_to(t,ref['view'],grid) for t in ref['axes'][name]]
            length = sum(np.linalg.norm(np.diff(t,axis=0),axis=1).sum() for t in paths)
            row['largeur_moyenne_approx_cellules'] = float(p.sum()*np.prod(grid/size)/length)
            # Construction géométrique de faisabilité, à partir des seules lignes admises.
            spine = np.zeros(tuple(grid[::-1]),np.uint8)
            for line in paths:
                cv.polylines(spine,[np.floor(line+.5).astype(np.int32)],False,1,1,cv.LINE_8)
            raster = optimal | (spine.ravel()>0)
            lines = fragments(spine>0)
            seg = work_to(lines,grid,ref['view']).tolist() if lines else []
            row['temoin_grille'] = dict(intersection=int(p[raster].sum()),aire=int(n[raster].sum()),
                dice=float(2*p[raster].sum()/(p.sum()+n[raster].sum())),sondes_axe=axis_cover(ref['axes'][name],seg),
                fragments=len(seg))
        if name in ref['entries']:
            nearest = np.floor(work_to(ref['entries'][name],ref['view'],grid)+.5).astype(int).clip(0,grid-1)
            row['cellule_entree'] = nearest.tolist()
            row['erreur_centre_px704'] = float(np.linalg.norm(work_to(nearest,grid,ref['view'])-ref['entries'][name]))
        output[name]=row
    return dict(grille=grid.tolist(),travail=size.tolist(),evaluation=ref['view'].tolist(),
                pixels_nat_non_ignores=int(n.sum()),cellules_evaluables=int((n>0).sum()),instances=output)


def select_photo(refs, ceilings):
    eligible = []
    for key,r in refs.items():
        complete = [s for s in r['ids'] if s in r['axes'] and s in r['entries']]
        if len(complete)<2:
            continue
        rows = ceilings[key]['instances']
        pts = np.array([work_to(r['entries'][i],r['view'],r['grid']) for i in complete])
        if len({tuple(rows[i]['cellule_entree']) for i in complete}) != len(complete):
            continue
        eligible.append(dict(photo=key,provenance=r['spec']['provenance'],instances_completes=complete,
              largeur_min_cellules=min(rows[i]['largeur_moyenne_approx_cellules'] for i in complete),
              separation_min_cellules=float(min(np.linalg.norm(a-b) for a,b in combinations(pts,2)))))
    eligible.sort(key=lambda r:(-r['largeur_min_cellules'],-r['separation_min_cellules'],r['photo']))
    return eligible


def frozen():
    refs = references()
    ceilings = {k:ceiling_photo(r) for k,r in refs.items()}
    eligible = select_photo(refs,ceilings)
    selected = eligible[0]
    for name in selected['instances_completes']:
        p=ceilings[selected['photo']]['instances'][name]
        if not (p['temoin_grille']['dice']>=p['dice_requis'] and p['temoin_grille']['sondes_axe']>=11 and p['erreur_centre_px704']<=10):
            raise ValueError('Témoin géométrique insuffisant')
    finals = read('r19/executions.json')
    seal = dict(base_R19='bdd5974a99df05495bda41b585a5e352fd2a3b17',preuves_R19='f10b63fdaa0902e20547afe0d37d626bf2808d9b',
                protocole_R19='c8154f931d01a78957547b06c91c026f4ff3ee53',initial_R9=dict(fichier=str(WEIGHT),sha256=INITIAL),
                checkpoints_A={str(s):dict(fichier=f'vision-lab-r19/vision-lab/artifacts/r19/pilote/{s}/final.pt',
                    sha256=next(m['final_sha256'] for m in finals if m['graine']==s),seuil_R19=read(f'r19/mesures/{s}.json')['seuil'])
                    for s in SEEDS},train=[r['spec'] for r in refs.values()],validites_sha256=fingerprint(R18/'validites.json'),
                code_R19={p.name:fingerprint(p) for p in sorted(Path('r19').glob('*.py')) if p.name in
                          ('modele.py','donnees.py','pilote.py','decoder.py','supervision.py','affecter.py','mesures.py')},
                photo_B=selected['photo'],cibles_B=selected['instances_completes'],eligibles_par_ordre=eligible,
                graine_B=MEMORY_SEED,controles_B=list(STEPS),maximum_A=27,maximum_mises_a_jour_B=1024)
    return seal,ceilings


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    args=parser.parse_args()
    args.out.mkdir(exist_ok=True,parents=True)
    seal,ceilings=frozen()
    save(args.out/'configuration.json',seal)
    save(args.out/'plafonds.json',ceilings)
