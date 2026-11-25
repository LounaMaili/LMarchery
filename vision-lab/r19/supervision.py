"""Construire les cibles partielles à partir des seuls champs admis dans chaque photo."""
import cv2 as cv
import numpy as np
import torch
from torch.nn import functional as F

from r19.affecter import minimum
from r19.donnees import R18, cell_indices, eval_size, input_size, read, work_to


def counts(indices, weights, length):
    return np.bincount(indices, weights=weights, minlength=length).astype(np.float32)


def axis_weights(instance, mask, grid, excludes):
    size = np.array(mask.shape[1::-1])
    view = eval_size(size)
    probes, side_points, lengths = [], [], []
    for path in instance['axes_supervises']:
        pts = work_to(path, size, view)
        for start, end in zip(pts[:-1],pts[1:]):
            direction = end-start
            length = np.linalg.norm(direction)
            if length < 1.e-8:
                continue
            steps = int(np.ceil(length/2))
            mid = start+((np.arange(steps)+.5)/steps)[:,None]*direction
            normal = 6*np.array([-direction[1],direction[0]])/length
            probes.extend(mid)
            side_points.extend(np.stack((mid+normal,mid-normal),axis=1))
            lengths.extend([length/steps]*steps)
    cells = int(np.prod(grid))
    if not probes:
        return np.zeros((2,cells), dtype=np.float32)
    def index(work):
        g = np.floor((work+.5)*grid/size).astype(int).clip(0,np.array(grid)-1)
        return g[:,1]*grid[0]+g[:,0]
    mass = np.array(lengths)
    on = work_to(probes,view,size)
    off = work_to(np.reshape(side_points,(-1,2)),view,size)
    pixels = np.floor(off+.5).astype(int)
    inside = ((pixels>=0)&(pixels<size)).all(axis=1)
    clipped = pixels.clip(0,size-1)
    labels = mask[clipped[:,1],clipped[:,0]]
    allowed = inside & (labels!=instance['indice']) & (labels!=255)
    for poly in excludes:
        for j in np.flatnonzero(allowed):
            if cv.pointPolygonTest(poly,tuple(off[j]),False)>=0:
                allowed[j]=False
    return np.stack((counts(index(on),mass,cells), counts(index(off[allowed]),np.repeat(mass,2)[allowed],cells)))


def contact_weights(point, size, grid):
    cells = np.zeros(grid[::-1],dtype=np.float32)
    if point is not None:
        xy = work_to(point,size,grid).clip(0,np.array(grid)-1)
        x,y = np.floor(xy).astype(int)
        dx,dy = xy-[x,y]
        for j, wy in ((y,1-dy),(min(grid[1]-1,y+1),dy)):
            for i, wx in ((x,1-dx),(min(grid[0]-1,x+1),dx)):
                cells[j,i] += wx*wy
    return cells.ravel()


def prepare(photo, record, annotation, mask):
    size = np.array(photo['dimensions_travail'])
    grid = tuple(np.array(input_size(size))//2)
    pixels = cell_indices(size,grid)
    length = int(np.prod(grid))
    background = counts(pixels[mask==0],None,length)
    owned = counts(pixels[(mask!=0)&(mask!=255)],None,length)
    total = counts(pixels.ravel(),None,length)
    active = [i for i in record['instances'] if i['masque_valide'] or i['axe_valide'] or i['entree_valide']]
    excluded = [np.asarray(r['polygone'],np.float32) for r in annotation['regions_ignorees'] if 'axe' in r['taches']]
    labels = {k:[] for k in ('own','others','axis_on','axis_off','contact')}
    for instance in active:
        own = counts(pixels[mask==instance['indice']],None,length)
        on, off = axis_weights(instance,mask,grid,excluded)
        labels['own'].append(own)
        labels['others'].append(owned-own)
        labels['axis_on'].append(on)
        labels['axis_off'].append(off)
        labels['contact'].append(contact_weights(instance['entree_supervisee'],size,grid))
    targets = {k:torch.from_numpy(np.array(v,dtype=np.float32).reshape(len(active),length)) for k,v in labels.items()}
    targets.update(background=torch.from_numpy(background), pure_bg=torch.from_numpy((background==total)&(total>0)),
                   ids=[i['instance'] for i in active])
    return targets


def weighted_terms(logits, groups):
    """Moyenner sur les catégories présentes puis sur leurs seuls pixels pondérés."""
    n = groups[0][0].shape[0]
    cost = logits.new_zeros((n,len(logits)))
    present = logits.new_zeros(n)
    for weights,positive in groups:
        mass = weights.sum(1)
        term = F.softplus(-logits if positive else logits)
        cost = cost + (weights/mass.clamp_min(1.e-10)[:,None]) @ term.T
        present = present + (mass>0)
    return cost/present.clamp_min(1)[:,None]


def extra_on_background(fields, unmatched, pure):
    if not unmatched:
        return []
    mask = fields[unmatched,0].detach()>=0
    points = fields[unmatched,2].detach().argmax(1)
    valid = mask.any(1) & ~(mask & ~pure).any(1) & pure[points]
    return [slot for slot,ok in zip(unmatched,valid.tolist(),strict=True) if ok]


def losses(fields, presence, target):
    count = len(target['ids'])
    zero = fields.sum()*0 + presence.sum()*0
    known = [target[k].sum(1)>0 for k in ('own','axis_on','contact')]
    costs = [
        weighted_terms(fields[:,0],[(target['own'],True),(target['others'],False),
                                     (target['background'].expand(count,-1),False)]),
        weighted_terms(fields[:,1],[(target['axis_on'],True),(target['axis_off'],False)]),
        -(target['contact'] @ fields[:,2].log_softmax(1).T)]
    weights = (1.,1.,1.)
    joint = sum(w*k[:,None]*c for w,k,c in zip(weights,known,costs,strict=True))
    denom = sum(w*k.float() for w,k in zip(weights,known,strict=True))
    # Seul le choix discret est détaché. Les trois pertes retenues restent différentiables.
    fit = joint/denom.clamp_min(1)[:,None] + .25*F.softplus(-presence)[None,:]
    cols = minimum(fit.detach().numpy())
    rows = torch.arange(count)
    chosen = torch.from_numpy(cols)
    parts = {}
    for k,valid,c in zip(('masque','axe','entree'),known,costs,strict=True):
        parts[k] = c[rows[valid],chosen[valid]].mean() if valid.any() else zero
    unused = sorted(set(range(len(presence)))-set(cols.tolist()))
    negative = extra_on_background(fields,unused,target['pure_bg'])
    supervised = []
    if count:
        supervised.append(F.softplus(-presence[chosen]).mean())
    if negative:
        supervised.append(F.softplus(presence[negative]).mean())
    parts['presence'] = sum(supervised)/len(supervised) if supervised else zero
    total = parts['masque']+parts['axe']+parts['entree']+.25*parts['presence']
    audit = dict(identites=count, masques=int(known[0].sum()), axes=int(known[1].sum()),
                 entrees=int(known[2].sum()), presences_negatives=len(negative))
    return total, parts, audit


def train_targets(photos):
    records = {p['photo']:p for p in read(R18/'validites.json')['vues'] if p['partition']=='train'}
    output = {}
    for p in photos:
        if p['partition'] != 'train':
            continue
        key = p['photo']
        mask = cv.imread(str(R18/'masques'/(key+'.png')),cv.IMREAD_UNCHANGED)
        output[key] = prepare(p,records[key],read(R18/'annotations'/(key+'.json')),mask)
    if sum(len(t['ids']) for t in output.values())!=31 or len(output)!=9:
        raise ValueError('Périmètre de supervision différent')
    return output
