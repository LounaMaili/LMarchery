"""Rendre les traces FP32 et tous les Dice optimistes lisibles depuis GitHub."""
import json
import numpy as np

from r29.commun import ROOT, read, save


def row_file(path, meta, rows):
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('w') as f:
        header=json.dumps(meta,ensure_ascii=False,allow_nan=False)
        f.write(header[:-1]+', "lignes":[\n')
        f.write(',\n'.join(json.dumps(row,ensure_ascii=False,separators=(',',':'),allow_nan=False) for row in rows))
        f.write('\n]}\n')


def ranks(origins):
    return {int(j):r for r,j in enumerate(origins)}


def decisions(a):
    ordered,admitted,kept,final=map(ranks,[a['ordre_score'],a['admission'],a['nms'],a['propositions']])
    rows=[]
    for j in range(len(a['rpn'])):
        by=int(a['supprime_par'][j])
        if not a['boite_valide'][j]:
            why='surface_nulle_apres_bornage'
        elif not a['score_admis'][j]:
            why='score_non_positif'
        elif by>=0:
            why='NMS'
        elif j not in final:
            why='plafond'
        else:
            why='final'
        rows.append(dict(j=j,rang_score=ordered[j],score=float(a['score'][j]),
            rang_admission=admitted.get(j),rang_nms=kept.get(j),q_final=final.get(j),motif=why,
            supprime_par_j=by if by>=0 else None,iou_NMS=float(a['iou_suppression'][j]) if by>=0 else None,
            score_supprimeur=float(a['score'][by]) if by>=0 else None,
            rang_nms_supprimeur=kept.get(by),q_final_supprimeur=final.get(by)))
    return rows


def publish_photo(dest,row,rec,spec,cap):
    photo,seed=row['photo'],row['graine']
    tag=f'{seed}-{photo}'
    with np.load(ROOT/'traces'/rec['fichier'],allow_pickle=False) as f:
        a={k:f[k] for k in f.files}
    decisions_by_j=decisions(a)
    cols=['j','ancre_RPN','logit_RPN','rpn_modele','rpn_natif','logits_RoI_classe_fond','deltas_RoI',
          'score_classe','rang_score','regression_modele','regression_native','bornee_modele','bornee_native',
          'boite_valide','score_admis','rang_admission','rang_NMS','q_final','motif','supprime_par_j',
          'IoU_suppression','score_supprimeur','rang_NMS_supprimeur','q_final_supprimeur']
    quantities=['rpn','regression','borne']
    native={k:a[k]*a['echelle'] for k in quantities}
    sheets=[]
    for offset in range(0,len(decisions_by_j),250):
        values=[]
        for v in decisions_by_j[offset:offset+250]:
            j=v['j']
            values.append([j,int(a['rpn_ancres'][j]),float(a['rpn_logits'][j]),a['rpn'][j].tolist(),native['rpn'][j].tolist(),
                a['roi_logits'][j].tolist(),a['roi_deltas'][j].tolist(),v['score'],v['rang_score'],
                a['regression'][j].tolist(),native['regression'][j].tolist(),a['borne'][j].tolist(),native['borne'][j].tolist(),
                bool(a['boite_valide'][j]),bool(a['score_admis'][j]),v['rang_admission'],v['rang_nms'],v['q_final'],v['motif'],
                v['supprime_par_j'],v['iou_NMS'],v['score_supprimeur'],v['rang_nms_supprimeur'],v['q_final_supprimeur']])
        path=dest/'propositions'/f'{tag}-{offset:04d}.json'
        row_file(path,dict(graine=seed,photo=photo,provenance=row['provenance'],colonnes=cols),values)
        sheets.append(path.name)
    raw=read(ROOT/'intersections'/(tag+'.json'))
    data=np.asarray(raw['par_origine_RPN'],np.int64)
    curves,losses={},{}
    for col,n in enumerate(raw['identites']):
        t=cap[n]['positifs_natifs']
        counts=data[:,:,col]
        dice=2*counts/(t+counts)
        key=spec['instances'][n]['indice']
        path=dest/'bornes'/f'{tag}-{key}.json'
        row_file(path,dict(graine=seed,photo=photo,identite=n,provenance=row['provenance'],T=t,
            seuil=.9*cap[n]['dice_max'],colonnes=['j','I_rpn','D_rpn','I_reg','D_reg','I_borne','D_borne']),
            ([j,int(i[0]),float(d[0]),int(i[1]),float(d[1]),int(i[2]),float(d[2])] for j,(i,d) in enumerate(zip(counts,dice))))
        curves[n]=path.name
        events=[]
        for e in row['identites'][n]['transitions']:
            if e['possibilite']=='perte':
                event=dict(avant=e['avant'],apres=e['apres'],ancetres=[])
                for j in e['pertes_j']:
                    v=decisions_by_j[j]
                    event['ancetres'].append(dict(**v,bornes_rpn_regression_bornee=dice[j].tolist()))
                events.append(event)
        losses[n]=dict(dernier_possible=row['identites'][n]['dernier_possible'],
                  diagnostic_final=row['identites'][n]['diagnostic_final'],pertes_de_derniere_arete=events)
    return dict(propositions=sheets,bornes=curves),losses


def export(dest,metrics):
    snapshots=read(ROOT/'traces/index.json')['traces']
    specs=read('r26/train.json')['photos']
    caps=read('r20/plafonds.json')
    directory,losses={},{}
    for tag,record in snapshots.items():
        row=read(metrics/'photos'/(tag+'.json'))
        photo=row['photo']
        directory[tag],losses[tag]=publish_photo(dest,row,record,specs[photo],caps[photo]['instances'])
    save(dest/'index-lecture.json',directory)
    save(dest/'retraits.json',losses)
    return directory
