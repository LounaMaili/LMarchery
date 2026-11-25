"""Mesures R19 sur archives seules, en centres de pixels au grand côté de 704."""
from collections import defaultdict
from pathlib import Path
from hashlib import sha256

import cv2 as cv
import numpy as np

from r19.affecter import minimum, pairs
from r19.donnees import BASELINE, R18, SEEDS, cell_indices, eval_size, read, work_to

COUNTS = ('photos','connus','apparies','propositions','non_appariees','faux_etablis','non_resolus','silencieuses','vides_declenches','propositions_sur_vides')


def dev_refs(specs):
    valid = {r['photo']:r for r in read(R18/'validites.json')['vues']}
    result = {}
    for spec in specs:
        if spec['partition'] != 'dev_explore_R16':
            continue
        key = spec['photo']
        annotation = read(R18/'annotations'/(key+'.json'))
        size = np.array(spec['dimensions_travail'])
        view = eval_size(size)
        entries, axes, ids = {}, {}, {}
        for inst in valid[key]['instances']:
            name = inst['instance']
            if inst['entree_valide']:
                entries[name] = work_to(inst['entree_supervisee'],size,view)
            if inst['axe_valide']:
                axes[name] = [work_to(t,size,view) for t in inst['axes_supervises']]
            if inst['masque_valide']:
                ids[name] = inst['indice']
        ignored = [work_to(r['polygone'],size,view).astype(np.float32)
                   for r in annotation['regions_ignorees'] if 'entree' in r['taches']]
        mask = cv.imread(str(R18/'masques'/(key+'.png')),cv.IMREAD_UNCHANGED)
        result[key] = dict(spec=spec, size=size, view=view, entries=entries, axes=axes, ids=ids, mask=mask,
                           exhaustive=annotation['historique_R17_complet'], ignored=ignored, empty=spec['role']=='temoin_vide')
    return result


def baseline(refs):
    old = read(BASELINE)
    if set(refs)!=set(old):
        raise ValueError('Périmètre des sorties B')
    output = {}
    for key, r in refs.items():
        row = old[key]
        if not np.allclose(row['echelle'],r['view']/r['size'],atol=1.e-14,rtol=0):
            raise ValueError('Repère historique différent')
        contacts = [dict(id=i,xy=work_to(point,r['size'],r['view']).tolist(),score=b)
                    for i,(point,_,b,_) in enumerate(row['candidats'])]
        axis = {i:[line] for i,(_,line,_,_) in enumerate(row['segments'])}
        output[key] = dict(contacts=contacts, axes=axis)
    return output


def candidate(refs, folder):
    preds = read(folder/'predictions.json')
    if set(refs)!=set(preds):
        raise ValueError('Prédictions incomplètes')
    out = {}
    for key,r in refs.items():
        row = preds[key]
        slots = row['sorties']
        bits = cv.imread(str(folder/'masques'/(key+'.png')),cv.IMREAD_UNCHANGED)
        if (bits.dtype!=np.uint16 or list(bits.shape[1::-1])!=row['grille'] or
            sha256(bits.astype('<u2').tobytes()).hexdigest()!=row['sha256_plan_uint16_le'] or
            row['repere_704']!=r['view'].tolist() or [s['id'] for s in slots]!=list(range(12)) or np.any(bits>=4096)):
            raise ValueError('Archive candidat incohérente '+key)
        for s in slots:
            if (np.count_nonzero(bits & (1<<s['id']))!=s['cellules_masque'] or
                s['score']!=s['presence']*s['pic_spatial']):
                raise ValueError('Masque ou score modifié')
        out[key] = dict(contacts=slots, axes={s['id']:s['segments'] for s in slots if s['segments']}, bits=bits)
    return out


class Contacts:
    def __init__(self, ref, candidates):
        self.ref = ref
        self.rows = sorted(candidates,key=lambda c:(-c['score'],c['id']))
        self.names = list(ref['entries'])
        pts = np.array([r['xy'] for r in self.rows], dtype=float).reshape(-1,2)
        truth = np.array(list(ref['entries'].values())).reshape(-1,2)
        self.dist = np.linalg.norm(truth[:,None]-pts[None,:],axis=2)
        self.uncertain = np.array([not ref['exhaustive'] or any(cv.pointPolygonTest(poly,tuple(p),False)>=0
                                      for poly in ref['ignored']) for p in pts],dtype=bool)

    def report(self, n, detail=False):
        d = self.dist[:,:n]
        matching = pairs(d<=10,d/10.)
        used = {c for _,c in matching}
        unmatched = n-len(used)
        unresolved = int(self.uncertain[:n].sum()) - sum(bool(self.uncertain[c]) for c in used)
        result = dict(zip(COUNTS,(1,len(self.names),len(matching),n,unmatched,unmatched-unresolved,unresolved,
                          int(n==0),int(self.ref['empty'] and n>0),n if self.ref['empty'] else 0),strict=True))
        if detail:
            result['paires'] = [dict(instance=self.names[i],sortie=self.rows[c]['id'],distance_px=float(d[i,c]),
                                     score=self.rows[c]['score']) for i,c in matching]
            result['sans_proposition_appariee'] = [s for i,s in enumerate(self.names) if i not in {j for j,_ in matching}]
            result['non_appariees_detail'] = [dict(sortie=self.rows[c]['id'],score=self.rows[c]['score'],
                 statut='non_resolu' if self.uncertain[c] else 'faux_etabli') for c in range(n) if c not in used]
        return result


def sum_count(values):
    values = list(values)
    return {k:sum(r[k] for r in values) for k in COUNTS}


def threshold_curve(observers):
    events = defaultdict(dict)
    for k,o in observers.items():
        for n,c in enumerate(o.rows,1):
            events[c['score']][k] = n
    current = {k:o.report(0) for k,o in observers.items()}
    total = sum_count(current.values())
    global_curve = [dict(seuil=2.,**total)]
    local = {k:[dict(seuil=2.,**value)] for k,value in current.items()}
    best = (0,0,2.)
    for score,changed in sorted(events.items(),reverse=True):
        for k,n in changed.items():
            current[k] = observers[k].report(n)
            local[k].append(dict(seuil=score,**current[k]))
        total = sum_count(current.values())
        global_curve.append(dict(seuil=score,**total))
        if total['non_appariees']<=len(observers)*.25:
            best = max(best,(total['apparies'],-total['non_appariees'],score))
    return best[2],global_curve,local


def samples(paths):
    """21 sondes au milieu des quantiles d'arclongueur, sans relier deux fragments."""
    starts, vectors = [], []
    for part in paths:
        a = np.array(part)
        starts.extend(a[:-1]); vectors.extend(np.diff(a,axis=0))
    starts = np.array(starts).reshape(-1,2)
    vectors = np.array(vectors).reshape(-1,2)
    lengths = np.linalg.norm(vectors,axis=1)
    keep = lengths>1.e-9
    starts, vectors, lengths = starts[keep], vectors[keep], lengths[keep]
    if not len(lengths):
        raise ValueError('Axe sans longueur')
    end = np.cumsum(lengths)
    pos = (np.arange(21)+.5)*end[-1]/21
    j = np.searchsorted(end,pos,side='right')
    units = vectors/lengths[:,None]
    return starts[j]+(pos-(end-lengths)[j])[:,None]*units[j], units[j]


def axis_cover(paths, output):
    pts,tangents = samples(paths)
    seg = np.array(output).reshape(-1,2,2)
    if not len(seg):
        return 0
    direction = seg[:,1]-seg[:,0]
    lengths = np.linalg.norm(direction,axis=1)
    keep = lengths>1.e-9
    if not np.any(keep):
        return 0
    seg, lengths = seg[keep], lengths[keep]
    unit = direction[keep]/lengths[:,None]
    delta = pts[:,None]-seg[None,:,0]
    along = np.sum(delta*unit,axis=-1).clip(0,lengths)
    dist = np.linalg.norm(delta-along[:,:,None]*unit,axis=2)
    compatible = (dist<=6.) & (np.abs(tangents@unit.T)>=np.cos(np.deg2rad(20)))
    return int(compatible.any(axis=1).sum())


def axes_report(ref, prediction):
    names,slots = list(ref['axes']),sorted(prediction)
    hits = np.array([[axis_cover(ref['axes'][k],prediction[q]) for q in slots] for k in names],int).reshape(len(names),len(slots))
    assigned = pairs(hits>=11,1-hits/21.)
    return dict(references=len(names),sorties_avec_axe=len(slots),fragments_sortis=sum(map(len,prediction.values())),
                apparies=len(assigned),paires=[dict(instance=names[i],sortie=slots[q],sondes=int(hits[i,q])) for i,q in assigned],
                absents=[n for i,n in enumerate(names) if i not in {j for j,_ in assigned}])


def masks_report(ref, bits):
    ids = list(ref['ids'])
    native = ref['mask']
    grid = bits.shape[1::-1]
    index = cell_indices(ref['size'],grid)
    cell = bits.ravel()
    known_counts = np.bincount(index[native!=255],minlength=cell.size)
    areas = np.array([np.sum(known_counts[(cell & (1<<q))!=0]) for q in range(12)])
    intersections = np.zeros((len(ids),12),dtype=np.int64)
    truth = np.zeros(len(ids),dtype=int)
    for i,name in enumerate(ids):
        values = cell[index[native==ref['ids'][name]]]
        truth[i] = len(values)
        intersections[i] = [(values & (1<<q)!=0).sum() for q in range(12)]
    dice = 2*intersections/(truth[:,None]+areas[None,:]).clip(1)
    assigned = minimum(np.column_stack((1-dice,np.ones((len(ids),len(ids))))))
    per_id = []
    for i,c in enumerate(assigned):
        ok = c<12 and intersections[i,c]>0
        per_id.append(dict(instance=ids[i],sortie=int(c) if ok else None,pixels_reference=int(truth[i]),
            intersection=int(intersections[i,c]) if ok else 0,pixels_sortie_evaluables=int(areas[c]) if ok else 0,
            dice=float(dice[i,c]) if ok else 0.))
    covers = intersections>=.25*truth[:,None]
    merges = {str(q):[ids[i] for i in np.flatnonzero(covers[:,q])] for q in range(12) if covers[:,q].sum()>=2}
    splits = {name:np.flatnonzero(covers[i]).tolist() for i,name in enumerate(ids) if covers[i].sum()>=2}
    return dict(par_identite=per_id, pixels_evaluables_par_sortie=areas.tolist(), intersections=intersections.tolist(),
                fusions=merges, dedoublements=splits, sorties_masque_non_vide=sum(bool((cell & (1<<q)).any()) for q in range(12)))


def link_entries(contact, mask):
    owners = {i['sortie']:i['instance'] for i in mask['par_identite'] if i['sortie'] is not None}
    names = {i['instance'] for i in mask['par_identite']}
    return [dict(instance=c['instance'],sortie=c['sortie'],identite_masque_attribuee=owners.get(c['sortie']),
                 statut=('reference_sans_masque' if c['instance'] not in names else 'correcte' if
                          owners.get(c['sortie'])==c['instance'] else 'autre_identite' if c['sortie'] in owners else 'masque_non_attribue'))
            for c in contact['paires']]


def strates(spec):
    return ['ensemble','source/'+spec['provenance'],*spec['conditions'],
            *(['vides_uses'] if {'empty','worn'}<=set(spec['conditions']) else [])]


def aggregate(rows):
    result = {**sum_count(p['selection'] for p in rows),
              'axes_apparies':sum(p['axes']['apparies'] for p in rows),
              'axes_reference':sum(p['axes']['references'] for p in rows),
              'sorties_avec_axe':sum(p['axes']['sorties_avec_axe'] for p in rows),
              'fragments_sortis':sum(p['axes']['fragments_sortis'] for p in rows),
              'total_candidats':sum(p['couverture']['propositions'] for p in rows),
              'contacts_couverts':sum(p['couverture']['apparies'] for p in rows)}
    if rows and 'masques' in rows[0]:
        masks = [m for p in rows for m in p['masques']['par_identite']]
        found = [m['dice'] for m in masks if m['sortie'] is not None]
        result.update(masques_ref=len(masks),masques_trouves=len(found),masques_manques=len(masks)-len(found),
            sorties_masque_non_vide=sum(p['masques']['sorties_masque_non_vide'] for p in rows),
            dice_toutes_identites=sum(found)/len(masks) if masks else None,dice_retrouvees=sum(found)/len(found) if found else None,
            sorties_fusion=sum(len(p['masques']['fusions']) for p in rows),
            identites_dedoublees=sum(len(p['masques']['dedoublements']) for p in rows))
        for mode in ('selection','couverture'):
            entries = [i for p in rows for i in p['association_'+mode]]
            result['entrees_'+mode] = {s:sum(i['statut']==s for i in entries) for s in
                                       ['correcte','autre_identite','masque_non_attribue','reference_sans_masque']}
    return result


def summarize(refs, predictions):
    obs = {k:Contacts(ref,predictions[k]['contacts']) for k,ref in refs.items()}
    threshold,curve,local = threshold_curve(obs)
    results = {}
    groups = defaultdict(list)
    for k,o in obs.items():
        p = predictions[k]
        row = dict(provenance=refs[k]['spec']['provenance'],conditions=refs[k]['spec']['conditions'],
                   selection=o.report(sum(c['score']>=threshold for c in o.rows),True),couverture=o.report(len(o.rows),True),
                   axes=axes_report(refs[k],p['axes']))
        if 'bits' in p:
            row['masques'] = masks_report(refs[k],p['bits'])
            for mode in ('selection','couverture'):
                row['association_'+mode] = link_entries(row[mode],row['masques'])
        results[k] = row
        for g in strates(refs[k]['spec']):
            groups[g].append(row)
    return dict(seuil=threshold,par_photo=results,par_strate={g:aggregate(rr) for g,rr in groups.items()}), dict(ensemble=curve,par_photo=local)


def decision(results):
    base = results['B']
    criteria = {}
    for seed in SEEDS:
        report = results[str(seed)]
        change = {}
        for k,r in report['par_photo'].items():
            original = base['par_photo'][k]
            known = lambda p:{i['instance'] for i in p['selection']['paires']}
            a,b = known(original),known(r)
            g = r['provenance']
            delta = change.setdefault(g,dict(gains=[],pertes=[]))
            delta['gains'].extend(sorted(b-a)); delta['pertes'].extend(sorted(a-b))
        old, new = (p['par_strate']['ensemble'] for p in (base,report))
        gain = new['apparies']-old['apparies']
        positive = [g for g,r in change.items() if len(r['gains'])>len(r['pertes'])]
        criteria[str(seed)] = dict(gain_net=gain,provenances_en_gain=positive,par_provenance=change,
            gain_sur_deux_provenances=gain>=2 and len(positive)>=2, sans_recul=gain>=0,
            budget=new['non_appariees']<=2,vides=new['vides_declenches']<=old['vides_declenches'],
            axes=new['axes_apparies']>=old['axes_apparies'])
    gates = list(criteria.values())
    keep = (sum(g['gain_sur_deux_provenances'] for g in gates)>=2 and
            all(all(g[k] for k in ('sans_recul','budget','vides','axes')) for g in gates))
    return dict(poursuivre=keep,graines=criteria)
